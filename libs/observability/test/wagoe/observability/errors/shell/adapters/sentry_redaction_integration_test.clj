(ns wagoe.observability.errors.shell.adapters.sentry-redaction-integration-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.observability.errors.shell.adapters.sentry :as sentry-adapter]
            [wagoe.observability.errors.ports :as ports]
            [wagoe.core.utils.pii-redaction :as pii]
            [sentry-clj.core :as sentry]))

(def test-config
  {:dsn "https://fake-key@fake-sentry.io/fake-project"
   :environment "test"})

(deftest ^:unit sentry-reporter-uses-shared-redactor-once
  (testing "Sentry error reporter delegates to shared redactor exactly once per capture"
    (let [call-count (atom 0)
          captured (atom nil)
          original-apply-redaction pii/apply-redaction]
      (with-redefs [pii/apply-redaction (fn [ctx cfg]
                                          (swap! call-count inc)
                                          (original-apply-redaction ctx cfg))
                    sentry/init! (fn [& _] nil)
                    sentry/send-event (fn [event]
                                        (reset! captured event)
                                        nil)]
        (let [reporter (sentry-adapter/create-sentry-error-reporter test-config)]
          (ports/capture-exception
           reporter
           (ex-info "boom" {})
           {:extra {:password "pw"
                    :email "user@example.com"}
            :tags {:token "abc"}})
          (is (= 1 @call-count) "shared redactor should be invoked once")
          (is (= "[REDACTED]" (get-in @captured [:extra :password])))
          (is (= "[REDACTED]" (get-in @captured [:tags :token])))
          (is (re-find #".*\*\*\*@example\.com$"
                       (get-in @captured [:extra :email]))))))))

(deftest ^:unit ^:security ex-data-in-the-cause-chain-is-redacted
  ;; BOU-1094: sentry-clj copied every ex-data into extras unredacted.
  (let [opts (atom nil)]
    (with-redefs [sentry/init! (fn [_dsn o] (reset! opts o))]
      (sentry-adapter/create-sentry-error-reporter test-config))
    (is (false? (:enable-uncaught-exception-handler @opts)) "the uncaught handler skips the redactor")
    (let [cause  (ex-info "inner" {:password "pw" :nested {:token "t"}})
          event  (#'sentry/map->event {:throwable   (ex-info "outer" {:email "user@example.com"} cause)
                                       :breadcrumbs [{:message "call" :data {:email "bob@example.com"}}]})
          extras (.getExtras ^io.sentry.SentryEvent ((:before-send-fn @opts) event nil))]
      (is (re-find #"^u\*\*\*@example\.com$" (get-in extras ["ex-data" "email"])))
      (is (= "[REDACTED]" (get-in extras ["ex-data, cause 1: inner" "password"])))
      (is (= "[REDACTED]" (get-in extras ["ex-data, cause 1: inner" "nested" "token"])))
      (is (= "b***@example.com" (get (.getData ^io.sentry.Breadcrumb (first (.getBreadcrumbs event))) "email"))))))
