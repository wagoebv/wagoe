(ns wagoe.platform.shell.service-interceptors-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wagoe.observability.errors.ports :as error-ports]
            [wagoe.observability.logging.ports :as log-ports]
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
