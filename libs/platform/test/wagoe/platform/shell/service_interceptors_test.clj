(ns wagoe.platform.shell.service-interceptors-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wagoe.observability.errors.ports :as error-ports]
            [wagoe.observability.logging.ports :as log-ports]
            [wagoe.observability.metrics.ports :as metrics-ports]
            [wagoe.observability.metrics.shell.adapters.prometheus :as prom]
            [wagoe.platform.shell.http.interceptors :as http-interceptors]
            [wagoe.platform.shell.service-interceptors :as si]))

(defn- recording-system
  "A logger and error reporter that keep everything handed to them."
  [seen]
  (let [record! (fn [& args] (swap! seen conj (pr-str args)) nil)]
    {:logger #_{:clj-kondo/ignore [:missing-protocol-method]}
     (reify
       log-ports/ILogger
       (info [_ m c] (record! m c))
       (error [_ m c] (record! m c))
       log-ports/IAuditLogger
       (audit-event [_ t a r ac res c] (record! t a r ac res c)))
     :error-reporter #_{:clj-kondo/ignore [:missing-protocol-method]}
     (reify
       error-ports/IErrorReporter
       (capture-exception [_ e c] (record! (.getMessage ^Throwable e) c))
       error-ports/IErrorContext
       (add-breadcrumb! [_ b] (record! b)))}))

(deftest ^:unit ^:security service-operation-does-not-log-secrets-test
  ;; BOU-556: params reach the logger, breadcrumbs and error reports.
  (let [params {:user-id 7
                :user-entity {:id 7 :password-hash "bcrypt+sha512$SECRETHASH" :mfa-secret "MFASECRETVALUE"}
                ;; the shape authenticate-user passes
                :user-credentials {:email "a@b.c" :password "PLAINPASSWORD" :mfa-code "924613"}}]
    (doseq [[label f] [["success" (constantly {:ok true})]
                       ["failure" (fn [_] (throw (ex-info "boom" {:type :internal-error})))]]]
      (let [seen (atom [])]
        (try
          (si/execute-service-operation :update-user params f (recording-system seen))
          (catch clojure.lang.ExceptionInfo _))
        (let [text (str/join "\n" @seen)]
          (is (str/includes? text "update-user") (str label ": the operation is recorded"))
          (is (not (str/includes? text "SECRETHASH")) (str label ": password hash recorded"))
          (is (not (str/includes? text "MFASECRETVALUE")) (str label ": mfa secret recorded"))
          (is (not (str/includes? text "PLAINPASSWORD")) (str label ": password recorded"))
          (is (not (str/includes? text "924613")) (str label ": mfa code recorded")))))))

(deftest ^:unit a-typed-client-error-fails-the-operation-below-error
  ;; BOU-704: the user service's :forbidden was logged at ERROR here.
  (doseq [[type level] [[:forbidden :warn] [:not-found :info] [:internal-error :error]]]
    (let [levels (atom [])
          logger #_{:clj-kondo/ignore [:missing-protocol-method]}
          (reify log-ports/ILogger
            (info [_ m _] (when (str/includes? m "failed") (swap! levels conj :info)))
            (warn [_ m _] (when (str/includes? m "failed") (swap! levels conj :warn)))
            (error [_ m _] (when (str/includes? m "failed") (swap! levels conj :error))))]
      (try
        (si/execute-service-operation :get-user {} (fn [_] (throw (ex-info "no" {:type type})))
                                      {:logger logger})
        (catch clojure.lang.ExceptionInfo _))
      (is (= [level] @levels) (str type)))))

(deftest ^:unit only-a-server-error-reaches-the-error-tracker
  ;; BOU-714: and before it, nothing did — the reporter read the wrong key.
  (doseq [[ex reported?] [[(ex-info "no" {:type :validation-error}) false]
                          [(ex-info "no" {:type :forbidden}) false]
                          [(ex-info "no" {:type :not-found}) false]
                          [(ex-info "no" {:type :conflict}) false]
                          [(ex-info "no" {:type :internal-error}) true]
                          [(RuntimeException. "no") true]]]
    (let [captured (atom 0)
          reporter #_{:clj-kondo/ignore [:missing-protocol-method]}
          (reify
            error-ports/IErrorReporter
            (capture-exception [_ _ _] (swap! captured inc))
            error-ports/IErrorContext
            (add-breadcrumb! [_ _] nil))]
      (try
        (si/execute-service-operation :get-user {} (fn [_] (throw ex))
                                      {:error-reporter reporter})
        (catch Exception _))
      (is (= (if reported? 1 0) @captured) (str ex)))))

(deftest ^:unit a-service-5xx-in-a-request-is-reported-once
  ;; The service reports it, then the HTTP layer sees the same throwable (BOU-714).
  (let [captured (atom [])
        reporter #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify
          error-ports/IErrorReporter
          (capture-exception [_ e _] (swap! captured conj (ex-message e)))
          error-ports/IErrorContext
          (add-breadcrumb! [_ _] nil))
        system   {:error-reporter reporter}
        request! (fn [msg]
                   (http-interceptors/run-http-interceptors
                    (fn [_] (si/execute-service-operation
                             :get-user {} (fn [_] (throw (RuntimeException. ^String msg))) system))
                    http-interceptors/default-http-interceptors
                    {:request-method :get :uri "/x" :headers {}}
                    system))]
    (is (= 500 (:status (request! "first"))))
    (is (= 1 (count @captured)) "once, not by the service and again by the HTTP layer")
    (request! "second")
    (is (= 2 (count @captured)) "a different exception is reported too")))

(deftest ^:unit service-operations-are-counted-and-timed
  (let [m      (prom/create-metrics-component {})
        system (assoc (recording-system (atom [])) :metrics-emitter m)
        total  #(reduce + 0 (vals (:series (metrics-ports/get-metric-values m %))))]
    (si/execute-service-operation :get-user {:user-id 7} (constantly {:ok true}) system)
    (try (si/execute-service-operation :get-user {} (fn [_] (throw (ex-info "boom" {:type :internal-error}))) system)
         (catch clojure.lang.ExceptionInfo _))
    (is (= 2 (total "get-user-attempted")))
    (is (= 1 (total "get-user-successful")))
    (is (= 1 (total "get-user-failed")))
    (is (= [{:operation-type "get-user"}]
           (keys (:series (metrics-ports/get-metric-values m "service-operation-duration"))))
        "no user id in the labels")))
