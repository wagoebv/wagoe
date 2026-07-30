(ns wagoe.core.config.feature-flags-test
  "Unit tests for wagoe.core.config.feature-flags namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.core.config.feature-flags :as flags]))

(deftest ^:unit parse-bool-test
  (testing "truthy values"
    (is (true? (flags/parse-bool "true")))
    (is (true? (flags/parse-bool "TRUE")))
    (is (true? (flags/parse-bool "True")))
    (is (true? (flags/parse-bool "1")))
    (is (true? (flags/parse-bool "yes")))
    (is (true? (flags/parse-bool "on"))))

  (testing "falsy values return false"
    (is (false? (flags/parse-bool "false")))
    (is (false? (flags/parse-bool "0")))
    (is (false? (flags/parse-bool "no")))
    (is (false? (flags/parse-bool "off")))
    (is (false? (flags/parse-bool "random"))))

  (testing "nil input"
    (is (nil? (flags/parse-bool nil)))))

(deftest ^:unit get-env-value-test
  (testing "reads from provided env map"
    (is (= "true" (flags/get-env-value "WAG_DEVEX_VALIDATION"
                                       {"WAG_DEVEX_VALIDATION" "true"}))))

  (testing "returns nil for missing key"
    (is (nil? (flags/get-env-value "MISSING_KEY" {})))))

(deftest ^:unit enabled?-test
  (testing "known flag enabled via env"
    (is (true? (flags/enabled? :devex-validation
                               {"WAG_DEVEX_VALIDATION" "true"}))))

  (testing "known flag disabled via env"
    (is (false? (flags/enabled? :devex-validation
                                {"WAG_DEVEX_VALIDATION" "false"}))))

  (testing "known flag uses default when env not set"
    (is (false? (flags/enabled? :devex-validation {}))))

  (testing "unknown flag defaults to false"
    (is (false? (flags/enabled? :nonexistent-flag {})))))

(deftest ^:unit all-flags-test
  (testing "returns map of all flags with status"
    (let [result (flags/all-flags {"WAG_DEVEX_VALIDATION" "true"})]
      (is (map? result))
      (is (true? (:devex-validation result)))
      (is (false? (:structured-logging result)))))

  (testing "all defaults when empty env"
    (let [result (flags/all-flags {})]
      (is (false? (:devex-validation result)))
      (is (false? (:structured-logging result))))))

(deftest ^:unit flag-info-test
  (testing "known flag returns full info"
    (let [info (flags/flag-info :devex-validation
                                {"WAG_DEVEX_VALIDATION" "true"})]
      (is (true? (:enabled? info)))
      (is (= "WAG_DEVEX_VALIDATION" (:env-var info)))
      (is (string? (:description info)))
      (is (= "true" (:current-value info)))))

  (testing "unknown flag returns error"
    (let [info (flags/flag-info :unknown-flag {})]
      (is (= "Unknown feature flag" (:error info))))))

(deftest ^:unit add-flags-to-config-test
  (testing "adds :feature-flags to config map"
    (let [config {:app-name "test"}
          result (flags/add-flags-to-config config {})]
      (is (= "test" (:app-name result)))
      (is (map? (:feature-flags result)))
      (is (contains? (:feature-flags result) :devex-validation)))))
