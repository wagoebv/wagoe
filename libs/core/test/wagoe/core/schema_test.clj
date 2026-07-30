(ns wagoe.core.schema-test
  "Unit tests for wagoe.core.schema — verifies canonical schema definitions."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [wagoe.core.schema :as schema]))

(deftest ^:unit validation-result-schema-test
  (testing "ValidationResult is a valid Malli schema"
    (is (some? (m/schema schema/ValidationResult))))

  (testing "success result matches schema"
    (is (m/validate schema/ValidationResult
                    {:valid? true :data {:email "user@example.com"}})))

  (testing "failure result matches schema"
    (is (m/validate schema/ValidationResult
                    {:valid? false
                     :errors [{:field :email
                               :code :invalid-format
                               :message "Invalid email"}]})))

  (testing "minimal valid result (only :valid?)"
    (is (m/validate schema/ValidationResult {:valid? true})))

  (testing "invalid result — missing :valid?"
    (is (not (m/validate schema/ValidationResult {:data {:foo "bar"}})))))

(deftest ^:unit interceptor-context-schema-test
  (testing "InterceptorContext is a valid Malli schema"
    (is (some? (m/schema schema/InterceptorContext))))

  (testing "minimal context matches schema"
    (is (m/validate schema/InterceptorContext
                    {:op :test-op
                     :system {:logger nil}})))

  (testing "full context matches schema"
    (is (m/validate schema/InterceptorContext
                    {:op :user-create
                     :system {:logger nil :metrics nil}
                     :request {:headers {"content-type" "application/json"}
                               :body {:name "Test"}}
                     :interface-type :http
                     :correlation-id "abc-123"
                     :halt? false
                     :breadcrumbs [{:category :test}]})))

  (testing "invalid context — missing required :op"
    (is (not (m/validate schema/InterceptorContext
                         {:system {}})))))
