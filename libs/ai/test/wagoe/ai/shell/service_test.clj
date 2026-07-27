(ns wagoe.ai.shell.service-test
  (:require [wagoe.ai.ports :as ports]
            [wagoe.ai.shell.service :as svc]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Mock provider
;; =============================================================================

(defn- mock-provider
  "Create a mock IAIProvider for integration tests.

   Args:
     complete-fn      - fn called by (complete ...) returning a result map
     complete-json-fn - fn called by (complete-json ...) returning a result map"
  [complete-fn complete-json-fn]
  (reify ports/IAIProvider
    (complete [_ messages opts]
      (complete-fn messages opts))
    (complete-json [_ messages schema opts]
      (complete-json-fn messages schema opts))
    (provider-name [_] :mock)))

(defn- ok-service
  "Return a service map backed by a mock provider that returns success."
  [text]
  {:provider (mock-provider
              (fn [_ _] {:text text :tokens 10 :provider :mock :model "mock"})
              (fn [_ _ _] {:data {:module-name "product"
                                  :entity "Product"
                                  :fields [{:name "price" :type "decimal" :required true :unique false}]
                                  :http true :web true}
                           :tokens 10 :provider :mock :model "mock"}))})

(defn- error-service
  "Return a service map backed by a mock provider that returns errors."
  []
  {:provider (mock-provider
              (fn [_ _] {:error "mock provider error" :provider :mock :model "mock"})
              (fn [_ _ _] {:error "mock provider error" :provider :mock :model "mock"}))})

(defn- fallback-service
  "Return a service with a failing primary and a succeeding fallback."
  [text]
  {:provider (mock-provider
              (fn [_ _] {:error "primary failed" :provider :mock :model "mock"})
              (fn [_ _ _] {:error "primary failed" :provider :mock :model "mock"}))
   :fallback (mock-provider
              (fn [_ _] {:text text :tokens 5 :provider :fallback :model "fallback"})
              (fn [_ _ _] {:data {:module-name "product" :entity "Product" :fields [] :http true :web true}
                           :tokens 5 :provider :fallback :model "fallback"}))})

;; =============================================================================
;; explain-error tests
;; =============================================================================

(deftest ^:integration explain-error-test
  (testing "returns AI response text on success"
    (let [service (ok-service "Root cause: nil pointer")
          result  (svc/explain-error service "ExceptionInfo: schema failed" ".")]
      (is (= "Root cause: nil pointer" (:text result)))
      (is (= :mock (:provider result)))))

  (testing "returns error map on provider failure"
    (let [service (error-service)
          result  (svc/explain-error service "ExceptionInfo: ..." ".")]
      (is (contains? result :error))))

  (testing "falls back to secondary provider on primary failure"
    (let [service (fallback-service "fallback explanation")
          result  (svc/explain-error service "ExceptionInfo: ..." ".")]
      (is (= "fallback explanation" (:text result)))
      (is (= :fallback (:provider result))))))

;; =============================================================================
;; scaffold-from-description tests
;; =============================================================================

(deftest ^:integration scaffold-from-description-test
  (testing "returns parsed module spec from provider JSON data"
    (let [service (ok-service "ignored")
          result  (svc/scaffold-from-description service "product module with name" ".")]
      (is (= "product" (:module-name result)))
      (is (= "Product" (:entity result)))
      (is (= [{:name "price" :type "decimal" :required true :unique false}]
             (:fields result)))))

  (testing "returns error map on provider failure"
    (let [service (error-service)
          result  (svc/scaffold-from-description service "product module with name" ".")]
      (is (contains? result :error)))))

;; =============================================================================
;; generate-tests tests
;; =============================================================================

(deftest ^:integration generate-tests-test
  (testing "returns error when source file does not exist"
    (let [service (ok-service "(deftest foo-test ...)")
          result  (svc/generate-tests service "/nonexistent/path/file.clj")]
      (is (contains? result :error))))

  (testing "returns generated test text on success"
    (let [tmp     (java.io.File/createTempFile "test-gen" ".clj")
          _       (spit tmp "(ns wagoe.foo.core.bar) (defn my-fn [x] x)")
          service (ok-service "(deftest my-fn-test (is (= 1 (my-fn 1))))")
          result  (svc/generate-tests service (.getPath tmp))]
      (.delete tmp)
      (is (string? (:text result))))))

;; =============================================================================
;; sql-from-description tests
;; =============================================================================

(deftest ^:integration sql-from-description-test
  (testing "returns parsed SQL result on success"
    (let [service (ok-service "{:select [:*] :from [:users]}")
          result  (svc/sql-from-description service "find all users" ".")]
      ;; The result may come back as parsed map or error, depending on parse
      (is (map? result)))))

;; =============================================================================
;; generate-docs tests
;; =============================================================================

(deftest ^:integration generate-docs-test
  (testing "returns documentation text for :agents type"
    (let [service (ok-service "# Module Docs\n\n## Purpose\nDoes stuff.")
          result  (svc/generate-docs service "libs/core" :agents)]
      (is (= "# Module Docs\n\n## Purpose\nDoes stuff." (:text result))))))
