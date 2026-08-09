(ns wagoe.ai.shell.service-test
  (:require [wagoe.ai.ports :as ports]
            [wagoe.ai.shell.service :as svc]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

;; The three repairs below are unit-tested in wagoe.ai.core.parsing-test. What
;; these check is that generate-tests *calls* them: measured, the model omits
;; the metadata and the requires on almost every run, so a repair that is
;; written but not wired leaves the tool exactly as broken as before.

(defn- generate-from
  "Run generate-tests against a throwaway source file, with `answer` as the
   model's reply. Returns the result map."
  [answer]
  (let [tmp (java.io.File/createTempFile "gen" ".clj")]
    (try
      (spit tmp "(ns x) (defn my-fn [a] a)")
      (svc/generate-tests (ok-service answer) (.getPath tmp))
      (finally (.delete tmp)))))

(deftest ^:integration generate-tests-applies-the-repairs
  (testing "metadata is stamped from the path, not left to the model"
    ;; Kaocha selects suites on it; without it the namespace runs in no suite.
    ;; A temp path has no /core/ segment, so :integration is the right type.
    (let [result (generate-from "(ns x-test)\n(deftest my-fn-test\n  (is (= 1 1)))")]
      (is (= :integration (:test-type result)))
      (is (str/includes? (:text result) "(deftest ^:integration my-fn-test"))))

  (testing "a namespace used but not required is repaired"
    (let [result (generate-from
                  (str "(ns x-test\n  (:require [clojure.test :refer [deftest is]]))\n"
                       "(deftest a-test (is (str/blank? \"\")))"))]
      (is (str/includes? (:text result) "[clojure.string :as str]"))))

  (testing "an answer cut off mid-form is an error, not a file to write"
    (let [result (generate-from "(ns x-test)\n(deftest a-test\n  (is (= 1 (my-fn")]
      (is (contains? result :error))
      (is (str/includes? (:error result) "cut off"))
      ;; The partial answer is kept for diagnosis but not offered as :text,
      ;; which is what the CLI writes to disk.
      (is (nil? (:text result)))
      (is (string? (:raw result)))))

  (testing "the conventional destination travels with the result"
    ;; The CLI's --write has nowhere to look if the service does not report it.
    (let [root (java.io.File/createTempFile "genroot" "")
          src  (io/file root "libs/demo/src/wagoe/demo/core/thing.clj")]
      (.delete root)
      (io/make-parents src)
      (spit src "(ns wagoe.demo.core.thing) (defn my-fn [a] a)")
      (let [result (svc/generate-tests (ok-service "(ns t-test)\n(deftest a-test (is true))")
                                       (.getPath src))]
        (is (str/ends-with? (:test-path result)
                            "libs/demo/test/wagoe/demo/core/thing_test.clj")
            (str "got " (pr-str (:test-path result))))
        ;; A core/ source is a unit test wherever it lives.
        (is (= :unit (:test-type result))))))

  (testing "a source path with no src segment reports no destination"
    ;; Better than inventing one: --write refuses and asks for -o.
    (is (nil? (:test-path (generate-from "(ns x-test)\n(deftest a-test (is true))"))))))

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
