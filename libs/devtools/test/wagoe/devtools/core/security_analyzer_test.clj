(ns wagoe.devtools.core.security-analyzer-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.devtools.core.security-analyzer :as sec]))

(deftest ^:unit analyze-password-policy
  (testing "extracts password policy strength indicators"
    (let [policy {:min-length 12
                  :require-uppercase? true
                  :require-lowercase? true
                  :require-numbers? true
                  :require-special-chars? true}
          result (sec/analyze-password-policy policy)]
      (is (= :strong (:strength result)))
      (is (= 12 (:min-length result))))))

(deftest ^:unit analyze-weak-password-policy
  (testing "flags weak password policy"
    (let [policy {:min-length 4
                  :require-uppercase? false
                  :require-lowercase? false
                  :require-numbers? false
                  :require-special-chars? false}
          result (sec/analyze-password-policy policy)]
      (is (= :weak (:strength result))))))

(deftest ^:unit analyze-auth-methods
  (testing "detects auth methods from Integrant keys in config"
    (let [cfg {:boundary/settings {:features {:mfa {:enabled? true}}}
               :boundary/auth-service {:some "config"}
               :boundary/session-repository {:some "repo"}}
          result (sec/analyze-auth-methods cfg)]
      (is (contains? (set (:methods result)) :jwt))
      (is (contains? (set (:methods result)) :session))
      (is (contains? (set (:methods result)) :mfa))))

  (testing "returns empty methods when no auth keys are configured"
    (let [cfg {:boundary/settings {}}
          result (sec/analyze-auth-methods cfg)]
      (is (empty? (:methods result)))
      (is (false? (:mfa-enabled? result))))))

(deftest ^:unit build-security-summary
  (testing "builds complete security summary with runtime data"
    (let [cfg {:boundary/settings
               {:user-validation
                {:password-policy {:min-length 12
                                   :require-uppercase? true
                                   :require-lowercase? true
                                   :require-numbers? true
                                   :require-special-chars? false}
                 :role-restrictions {:allowed-roles #{:user :admin}}}}}
          summary (sec/build-security-summary cfg {:active-sessions 5
                                                   :recent-auth-failures [{:type :failed-login}]})]
      (is (map? summary))
      (is (:password-policy summary))
      (is (:auth-methods summary))
      (is (:csp summary))
      (is (= 5 (:active-sessions summary)))
      (is (= 1 (count (:recent-failures summary)))))))

(deftest ^:unit lockout-reads-flat-validation-keys
  (testing "reads :max-failed-attempts and :lockout-duration-minutes from validation config"
    (let [cfg {:boundary/settings
               {:user-validation
                {:max-failed-attempts 3
                 :lockout-duration-minutes 30}}}
          summary (sec/build-security-summary cfg {})]
      (is (= 3 (get-in summary [:lockout :max-attempts])))
      (is (= 30 (get-in summary [:lockout :duration-mins]))))))

(deftest ^:unit rate-limiting-from-runtime-data
  (testing "rate-limiting? comes from runtime data, not config"
    (let [cfg {}]
      (is (false? (:rate-limiting? (sec/build-security-summary cfg {}))))
      (is (true? (:rate-limiting? (sec/build-security-summary cfg {:rate-limiting? true})))))))
