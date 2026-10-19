(ns wagoe.observability.errors.core-helpers-test
  "The classification, wrapping and breadcrumb helpers in errors.core."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.observability.errors.core :as ec]
            [wagoe.observability.errors.ports :as ports]))

(defn- recording-reporter
  "An IErrorReporter that keeps what it was given, or throws when `fail?`."
  ([calls] (recording-reporter calls false))
  ([calls fail?]
   (reify ports/IErrorReporter
     (capture-exception [_ e] (swap! calls conj [:exception e nil]) "id")
     (capture-exception [_ e ctx]
       (when fail? (throw (RuntimeException. "sentry down")))
       (swap! calls conj [:exception e ctx]) "id")
     (capture-exception [_ e ctx _tags] (swap! calls conj [:exception e ctx]) "id")
     (capture-message [_ m level] (swap! calls conj [:message m level nil]) "id")
     (capture-message [_ m level ctx] (swap! calls conj [:message m level ctx]) "id")
     (capture-message [_ m level ctx _tags] (swap! calls conj [:message m level ctx]) "id")
     (capture-event [_ ev] (swap! calls conj [:event ev]) "id"))))

(deftest ^:unit classify-exception-test
  (testing "the message picks the category"
    (doseq [[message category] {"Invalid email"         :validation
                                "permission denied"     :security
                                "Connection refused"    :connectivity
                                "SQL syntax error"      :database
                                "external API returned" :external-service
                                "something else"        :application}]
      (is (= category (:category (ec/classify-exception (RuntimeException. ^String message))))
          message)))

  (testing "the class picks the severity"
    (is (= :high (:severity (ec/classify-exception (SecurityException. "x")))))
    (is (= :medium (:severity (ec/classify-exception (java.net.SocketTimeoutException. "x")))))
    (is (= :low (:severity (ec/classify-exception (IllegalArgumentException. "x")))))
    (is (= :medium (:severity (ec/classify-exception (RuntimeException. "x"))))))

  (testing "an exception without a message is classified, not an NPE"
    ;; re-find on a nil message threw, so a NullPointerException — the most
    ;; common 500 — was the one error reporting could not report.
    (is (= {:type "NullPointerException" :category :application :severity :medium}
           (ec/classify-exception (NullPointerException.))))))

(deftest ^:unit report-application-error-reports-a-message-less-exception
  (let [calls (atom [])
        e     (NullPointerException.)]
    (ec/report-application-error (recording-reporter calls) e "HTTP request failed" {:uri "/x"})
    (let [[[kind reported ctx]] @calls]
      (is (= :exception kind))
      (is (identical? e reported))
      (is (= "/x" (:uri ctx)))
      (is (= :application (:error-type ctx)))
      (is (= "HTTP request failed" (:message ctx))))))

(deftest ^:unit should-report-exception-test
  (testing "the default floor is :medium"
    (is (true? (ec/should-report-exception? (RuntimeException. "boom") {})))
    (is (false? (ec/should-report-exception? (IllegalArgumentException. "boom") {}))))

  (testing "a lower floor lets :low through"
    (is (true? (ec/should-report-exception? (IllegalArgumentException. "boom") {:min-severity :low}))))

  (testing "an excluded category is never reported"
    (is (false? (ec/should-report-exception? (SecurityException. "permission denied")
                                             {:excluded-categories #{:security}})))))

(deftest ^:unit context-helpers-test
  (testing "sanitize-context redacts only the keys present"
    (is (= {:password "[REDACTED]" :user "ann"}
           (ec/sanitize-context {:password "hunter2" :user "ann"} #{:password :token}))))

  (testing "with-request-context upper-cases the method"
    (is (= {:request-id "r1" :http-method "POST" :http-path "/a" :http-headers {}}
           (ec/with-request-context {} "r1" :post "/a" {}))))

  (testing "with-operation-context stringifies"
    (is (= {:operation ":save" :entity "user" :action "create"}
           (ec/with-operation-context {} :save "user" "create"))))

  (testing "build-error-context keeps the base and nests the extra data"
    (let [ctx (ec/build-error-context {:a 1} {:b 2})]
      (is (= 1 (:a ctx)))
      (is (= {:b 2} (:extra ctx)))
      (is (int? (:timestamp ctx)))))

  (testing "the default sensitive keys cover credentials"
    (is (every? (ec/create-default-sensitive-keys) [:password :token :api-key :authorization]))))

(deftest ^:unit wrapping-helpers-test
  (testing "with-error-reporting reports and rethrows the same exception"
    (let [calls (atom [])
          e     (IllegalStateException. "broken")]
      (is (identical? e (try (ec/with-error-reporting (recording-reporter calls) {:op "x"} #(throw e))
                             (catch IllegalStateException thrown thrown))))
      (is (= 1 (count @calls)))))

  (testing "with-error-reporting reports nothing on success"
    (let [calls (atom [])]
      (is (= 3 (ec/with-error-reporting (recording-reporter calls) {} (constantly 3))))
      (is (empty? @calls))))

  (testing "with-fallback-error-reporting returns the fallback after reporting"
    (let [calls (atom [])]
      (is (= :fallback (ec/with-fallback-error-reporting
                         (recording-reporter calls) {} :fallback #(throw (RuntimeException. "x")))))
      (is (= 1 (count @calls)))))

  (testing "report-and-continue survives a reporter that throws"
    (is (= "error-reporting-failed"
           (ec/report-and-continue (recording-reporter (atom []) true)
                                   (RuntimeException. "x") "msg" {}))))

  (testing "report-and-continue returns the reporter's event id"
    (is (= "id" (ec/report-and-continue (recording-reporter (atom [])) (RuntimeException. "x") "msg" {})))))

(deftest ^:unit message-reporters-test
  (let [calls    (atom [])
        reporter (recording-reporter calls)]
    (ec/report-validation-error reporter [{:field :email} {:field :name}] {:form "signup"})
    (ec/report-external-service-error reporter "stripe" "charge" {:status 502} {})
    (ec/report-security-incident reporter :brute-force {:attempts 9} {})
    (let [[validation external security] @calls]
      (is (= [:message "Validation failed" :warning] (subvec validation 0 3)))
      (is (= 2 (:error-count (peek validation))))
      (is (= "signup" (:form (peek validation))))
      (is (= [:message "External service error: stripe" :error] (subvec external 0 3)))
      (is (= {:status 502} (:service-error (peek external))))
      (is (= [:message "Security incident: :brute-force" :error] (subvec security 0 3)))
      (is (= :high (:severity (peek security)))))))

(deftest ^:unit breadcrumb-helpers-test
  (let [crumbs  (atom [])
        context #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify ports/IErrorContext
          (add-breadcrumb! [_ b] (swap! crumbs conj b)))]
    (ec/track-user-navigation context "/orders" :get)
    (ec/track-user-action context "clicked" "pay")
    (let [[nav action] @crumbs]
      (is (= {:message "GET /orders" :category "navigation" :level :info
              :data {:path "/orders" :method :get}}
             (dissoc nav :timestamp)))
      (is (inst? (:timestamp nav)))
      (is (= "User clicked pay" (:message action)))
      (is (= "user-action" (:category action))))))

(deftest ^:unit fingerprint-and-duplicates-test
  (testing "the same throw site and operation group together"
    (let [[a b] (mapv (fn [_] (RuntimeException. "x")) [1 2])]
      (is (= (ec/create-error-fingerprint a {:operation "save"})
             (ec/create-error-fingerprint b {:operation "save"})))
      (is (not= (ec/create-error-fingerprint a {:operation "save"})
                (ec/create-error-fingerprint a {:operation "load"})))
      (is (re-find #"^RuntimeException:unknown:" (ec/create-error-fingerprint a {})))))

  (testing "a fingerprint reported inside the window is suppressed"
    (let [now (System/currentTimeMillis)]
      (is (true? (ec/should-suppress-duplicate? "fp" {"fp" now} 60000)))
      (is (false? (ec/should-suppress-duplicate? "fp" {"fp" (- now 120000)} 60000)))
      (is (false? (ec/should-suppress-duplicate? "fp" {} 60000))))))

(deftest ^:unit merge-error-configs-test
  (testing "a later value wins, as with merge"
    (is (= {:environment "prod" :sample-rate 0.1}
           (ec/merge-error-configs {:environment "dev" :sample-rate 1.0}
                                   {:environment "prod" :sample-rate 0.1}))))

  (testing "a later nil does not clear an earlier value"
    (is (= {:release "1.2"} (ec/merge-error-configs {:release "1.2"} {:release nil}))))

  (testing "collections are replaced, not merged"
    (is (= {:tags {:b 2}} (ec/merge-error-configs {:tags {:a 1}} {:tags {:b 2}})))))
