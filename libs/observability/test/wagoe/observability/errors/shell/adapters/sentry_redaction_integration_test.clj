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