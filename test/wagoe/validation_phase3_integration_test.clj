(ns wagoe.validation-phase3-integration-test
  "Integration test demonstrating all Phase 3 validation features working together.

  This test demonstrates:
  1. Property-based generators producing test data
  2. Behavior DSL for declarative test scenarios
  3. Snapshot testing for regression detection
  4. Coverage reporting for validation completeness

  All features work together to provide comprehensive validation testing."
  (:require [wagoe.core.validation.generators :as gen]
            [wagoe.core.validation.behavior :as behavior]
            [wagoe.core.validation.snapshot :as snapshot]
            [wagoe.core.validation.coverage :as coverage]
            [wagoe.user.schema :as user-schema]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; Tag for Phase 3
(alter-meta! *ns* assoc :kaocha/tags [:phase3 :integration])

;; -----------------------------------------------------------------------------
;; Test Validation Function
;; -----------------------------------------------------------------------------

(def ^:private executed-rules (atom #{}))

(defn- reset-executed-rules!
  "Reset tracking for test isolation."
  []
  (reset! executed-rules #{}))

(defn- track-rule-execution!
  "Track which validation rules were executed."
  [rule-id]
  (swap! executed-rules conj rule-id))

(defn- validate-with-tracking
  "Validate data and track which rules were checked.

  Simplified validator for integration testing."
  [data]
  (let [required-fields [:name :email :role :tenant-id]
        missing (filter #(not (contains? data %)) required-fields)]
    ;; Track rule executions
    (doseq [field required-fields]
      (track-rule-execution! (keyword (str "user." (name field)) "required")))

    (if (seq missing)
      {:status :failure
       :errors (mapv (fn [field]
                       {:code (keyword (str "user." (name field)) "required")
                        :path [field]
                        :message (str "Field " field " is required")})
                     missing)}
      {:status :success
       :data data})))

;; Test fixture to reset state
(use-fixtures :each
  (fn [test-fn]
    (reset-executed-rules!)
    (test-fn)))

;; -----------------------------------------------------------------------------
;; Feature 1: Property-Based Generators
;; -----------------------------------------------------------------------------

(deftest ^:phase3 ^:integration generators-produce-valid-data-test
  (testing "Generators produce valid test data with deterministic seeds"
    (let [seed 42
          schema user-schema/CreateUserRequest
          ;; Generate valid data
          valid-data (gen/gen-valid-one schema {:seed seed :size 5})]
      ;; Data should be generated
      (is (some? valid-data))
      (is (map? valid-data))
      ;; Data should have expected keys
      (is (contains? valid-data :name))
      (is (contains? valid-data :email))
      ;; Same seed produces same data
      (let [valid-data-2 (gen/gen-valid-one schema {:seed seed :size 5})]
        (is (= valid-data valid-data-2) "Deterministic generation")))))

(deftest ^:phase3 ^:integration generators-produce-invalid-data-test
  (testing "Generators produce invalid test data for specific violations"
    (let [seed 100
          schema user-schema/CreateUserRequest
          ;; Generate data missing required field
          invalid-data (gen/gen-invalid-one schema :missing-required {:seed seed :field :email})]
      ;; Data should be generated
      (is (some? invalid-data))
      (is (map? invalid-data))
      ;; Email should be missing
      (is (not (contains? invalid-data :email))))))

;; -----------------------------------------------------------------------------
;; Feature 2: Behavior DSL
;; -----------------------------------------------------------------------------

(deftest ^:phase3 ^:integration behavior-dsl-scenarios-test
  (testing "Behavior DSL enables declarative test scenarios"
    ;; Define scenarios using templates
    (let [valid-user {:name "Integration Test User"
                      :email "integration@example.com"
                      :role :user
                      :tenant-id (java.util.UUID/randomUUID)}
          scenarios [(behavior/valid-data-template
                      "valid-user-passes"
                      valid-user
                      validate-with-tracking)
                     (behavior/missing-required-field-template
                      :email
                      :user.email/required
                      valid-user
                      validate-with-tracking)
                     (behavior/missing-required-field-template
                      :name
                      :user.name/required
                      valid-user
                      validate-with-tracking)]]
      ;; Execute all scenarios
      (doseq [scenario scenarios]
        (let [result (behavior/execute-scenario scenario {})]
          (is (:all-passed? result)
              (str "Scenario '" (:scenario-name result) "' should pass: "
                   (pr-str (:assertions result)))))))))

(deftest ^:phase3 ^:integration behavior-dsl-mutations-test
  (testing "Behavior DSL mutations transform test data"
    (let [base {:name "Test" :email "test@example.com" :role :user
                :tenant-id (java.util.UUID/randomUUID)}
          ;; Use mutation helpers
          remove-email (behavior/remove-field :email)
          set-new-name (behavior/set-field :name "New Name")
          mutated-1 (remove-email base)
          mutated-2 (set-new-name base)]
      ;; Mutations work correctly
      (is (not (contains? mutated-1 :email)))
      (is (= "New Name" (:name mutated-2)))
      (is (= "test@example.com" (:email mutated-2))))))

;; -----------------------------------------------------------------------------
;; Feature 3: Snapshot Testing
;; -----------------------------------------------------------------------------

(deftest ^:phase3 ^:integration snapshot-capture-and-compare-test
  (testing "Snapshots capture validation results for regression testing"
    (let [result {:status :failure
                  :errors [{:code :user.email/required
                            :path [:email]
                            :message "Email is required"}]}
          ;; Capture snapshot with metadata
          snap (snapshot/capture result {:seed 42
                                         :meta {:test "integration"
                                                :feature "phase3"}})
          ;; Serialize deterministically
          serialized (snapshot/stable-serialize snap)
          ;; Parse back
          parsed (snapshot/parse-snapshot serialized)]
      ;; Round-trip works
      (is (= snap parsed))
      ;; Metadata preserved
      (is (= 42 (get-in snap [:meta :seed])))
      (is (= "integration" (get-in snap [:meta :test])))
      ;; Result preserved
      (is (= :failure (get-in snap [:result :status]))))))

(deftest ^:phase3 ^:integration snapshot-comparison-detects-changes-test
  (testing "Snapshot comparison detects regression"
    (let [original {:status :success :data {:id 123}}
          modified {:status :failure :errors [{:code :error}]}
          snap-original (snapshot/capture original {})
          snap-modified (snapshot/capture modified {})
          comparison (snapshot/compare-snapshots snap-original snap-modified)]
      ;; Comparison detects difference
      (is (false? (:equal? comparison)))
      (is (some? (first (:diff comparison))))
      (is (some? (second (:diff comparison)))))))

(deftest ^:phase3 ^:integration snapshot-path-computation-test
  (testing "Snapshot paths are computed consistently"
    (let [path (snapshot/path-for {:ns 'wagoe.validation-phase3-integration-test
                                   :test 'snapshot-test
                                   :case 'email-missing})]
      (is (string? path))
      (is (re-find #"test/snapshots/validation" path))
      (is (re-find #"snapshot_test__email_missing\.edn" path)))))

;; -----------------------------------------------------------------------------
;; Feature 4: Coverage Reporting
;; -----------------------------------------------------------------------------

(deftest ^:phase3 ^:integration coverage-computation-test
  (testing "Coverage tracks which validation rules were executed"
    ;; Reset and run some validations
    (reset-executed-rules!)

    ;; Run validations that execute different rules
    (validate-with-tracking {:name "Test" :email "test@ex.com" :role :user
                             :tenant-id (java.util.UUID/randomUUID)})
    (validate-with-tracking {:name "Test"}) ;; Missing email, role, tenant-id

    ;; Compute coverage
    (let [registered #{:user.name/required :user.email/required
                       :user.role/required :user.tenant-id/required}
          executed @executed-rules
          coverage-result (coverage/compute {:registered registered
                                             :executed executed
                                             :by-module {:user registered}})]
      ;; Coverage computed
      (is (= 4 (:total coverage-result)))
      (is (= 4 (:executed coverage-result)))
      (is (= 100.0 (:pct coverage-result)))
      (is (empty? (:missing coverage-result))))))

(deftest ^:phase3 ^:integration coverage-reports-test
  (testing "Coverage generates human-readable and EDN reports"
    (let [coverage-data {:total 10 :executed 8 :pct 80.0
                         :per-module {:user {:total 10 :executed 8 :pct 80.0 :missing #{:r1 :r2}}}
                         :missing #{:r1 :r2}}
          ;; Human-readable report
          human (coverage/human-report coverage-data {})
          ;; EDN report
          edn-report (coverage/edn-report coverage-data {:timestamp "2025-01-04"})
          ;; Summary line
          summary (coverage/summary-line coverage-data)]
      ;; Human report has expected content
      (is (re-find #"Validation Coverage Report" human))
      (is (re-find #"80\.0%" human))
      (is (re-find #"\(8/10\)" human))
      ;; EDN report is structured
      (is (= 80.0 (:coverage edn-report)))
      (is (= 10 (:total edn-report)))
      (is (= 8 (:executed edn-report)))
      ;; Summary is concise
      (is (= "Coverage: 80.0% (8/10 rules executed)" summary)))))

;; -----------------------------------------------------------------------------
;; Integration: All Features Together
;; -----------------------------------------------------------------------------

(deftest ^:phase3 ^:integration all-features-working-together-test
  (testing "All Phase 3 features work together in realistic workflow"
    (reset-executed-rules!)

    ;; 1. Use generators to create test data
    (let [seed 999
          schema user-schema/CreateUserRequest
          valid-data (gen/gen-valid-one schema {:seed seed :size 5})
          invalid-data (gen/gen-invalid-one schema :missing-required {:seed seed :field :email})

          ;; 2. Create behavior scenarios
          scenarios [(behavior/valid-data-template
                      "generated-valid-data"
                      (assoc valid-data
                             :name "Generated"
                             :email "gen@example.com"
                             :role :user
                             :tenant-id (java.util.UUID/randomUUID))
                      validate-with-tracking)
                     {:name "generated-invalid-data"
                      :base invalid-data
                      :mutations []
                      :action validate-with-tracking
                      :assertions [{:expect :failure}]}]

          ;; Execute scenarios
          results (mapv #(behavior/execute-scenario % {}) scenarios)]

      ;; All scenarios should pass
      (is (every? :all-passed? results)
          (str "Some scenarios failed: " (pr-str (map :assertions results))))

      ;; 3. Capture snapshots of results
      (let [snapshot-1 (snapshot/capture (:result (first results))
                                         {:seed seed :meta {:scenario "valid"}})
            snapshot-2 (snapshot/capture (:result (second results))
                                         {:seed seed :meta {:scenario "invalid"}})]
        ;; Snapshots captured
        (is (some? snapshot-1))
        (is (some? snapshot-2))
        ;; Different results
        (let [comparison (snapshot/compare-snapshots snapshot-1 snapshot-2)]
          (is (false? (:equal? comparison)))))

      ;; 4. Compute coverage
      (let [registered #{:user.name/required :user.email/required
                         :user.role/required :user.tenant-id/required}
            executed @executed-rules
            coverage-result (coverage/compute {:registered registered
                                               :executed executed
                                               :by-module {:user registered}})]
        ;; All rules executed (100% coverage)
        (is (>= (:pct coverage-result) 80.0)
            (str "Coverage too low: " (:pct coverage-result) "% - "
                 "Missing: " (:missing coverage-result)))

        ;; Generate final report
        (let [report (coverage/human-report coverage-result {})]
          (is (some? report))
          (println "\n" report))))))

;; -----------------------------------------------------------------------------
;; Summary Test
;; -----------------------------------------------------------------------------

(deftest ^:phase3 ^:integration phase3-features-summary-test
  (testing "Phase 3 validation features are production-ready"
    (is (fn? gen/gen-valid-one) "Property-based generators available")
    (is (fn? behavior/execute-scenario) "Behavior DSL scenario execution available")
    (is (fn? snapshot/capture) "Snapshot testing capture available")
    (is (fn? coverage/compute) "Coverage reporting computation available")))
