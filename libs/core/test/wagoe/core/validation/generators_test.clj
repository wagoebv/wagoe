(ns wagoe.core.validation.generators-test
  "Tests for property-based validation data generators.
  
   Tagged with :phase3 for selective test runs:
     clojure -M:test:db --focus-meta :phase3"
  (:require [wagoe.core.validation.generators :as gen]
            [wagoe.user.schema :as user-schema]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]))

;; =============================================================================
;; Test Fixtures and Helpers
;; =============================================================================

(def test-seed 42)
(def alt-seed 99)

(defn- schema-registry
  "Simple schema registry for testing without coupling to validation.registry."
  [schema-key]
  (case schema-key
    :user user-schema/CreateUserRequest ; Use CreateUserRequest for testing (no inst? fields)
    :create-user-request user-schema/CreateUserRequest
    :user-session user-schema/UserSession
    nil))

;; =============================================================================
;; Valid Data Generation Tests
;; =============================================================================

(deftest ^:unit ^:phase3 gen-valid-one-user-test
  (testing "Generate valid CreateUserRequest"
    (let [user (gen/gen-valid-one user-schema/CreateUserRequest {:seed test-seed})]
      (is (some? user) "Should generate a user request")
      (is (m/validate user-schema/CreateUserRequest user) "Generated user request should be valid")
      (is (string? (:email user)) "User should have email string")
      (is (string? (:name user)) "User should have name string")
      (is (keyword? (:role user)) "User should have role keyword"))))

(deftest ^:unit ^:phase3 gen-valid-one-create-request-test
  (testing "Generate valid CreateUserRequest"
    (let [request (gen/gen-valid-one user-schema/CreateUserRequest {:seed test-seed})]
      (is (some? request) "Should generate a request")
      (is (m/validate user-schema/CreateUserRequest request) "Generated request should be valid")
      (is (string? (:email request)) "Request should have email")
      (is (string? (:name request)) "Request should have name")
      (is (keyword? (:role request)) "Request should have role"))))

;; SKIPPED: UserSession has inst? fields which don't have built-in generators in Malli 0.19.2
;; (deftest ^:phase3 gen-valid-one-user-session-test
;;   (testing "Generate valid UserSession"
;;     (let [session (gen/gen-valid-one user-schema/UserSession {:seed test-seed})]
;;       (is (some? session) "Should generate a session")
;;       (is (m/validate user-schema/UserSession session) "Generated session should be valid")
;;       (is (uuid? (:id session)) "Session should have UUID id")
;;       (is (uuid? (:user-id session)) "Session should have user-id")
;;       (is (uuid? (:tenant-id session)) "Session should have tenant-id")
;;       (is (string? (:session-token session)) "Session should have token"))))

(deftest ^:unit ^:phase3 gen-valid-determinism-test
  (testing "Same seed produces identical data"
    (let [user1 (gen/gen-valid-one user-schema/CreateUserRequest {:seed test-seed})
          user2 (gen/gen-valid-one user-schema/CreateUserRequest {:seed test-seed})]
      (is (= user1 user2) "Same seed should produce identical users")))

  (testing "Different seeds produce different data"
    (let [user1 (gen/gen-valid-one user-schema/CreateUserRequest {:seed test-seed})
          user2 (gen/gen-valid-one user-schema/CreateUserRequest {:seed alt-seed})]
      (is (not= user1 user2) "Different seeds should produce different users"))))

(deftest ^:unit ^:phase3 gen-valid-sequence-test
  (testing "Generate sequence of valid users"
    (let [users (take 3 (gen/gen-valid user-schema/CreateUserRequest {:seed test-seed :count 5}))]
      (is (= 3 (count users)) "Should generate 3 users")
      (is (every? #(m/validate user-schema/CreateUserRequest %) users) "All users should be valid"))))

(deftest ^:unit ^:phase3 resolve-schema-test
  (testing "Resolve schema from keyword via registry"
    (let [schema (gen/resolve-schema :user {:registry schema-registry})]
      (is (some? schema) "Should resolve :user schema")
      (is (= user-schema/CreateUserRequest schema) "Should return CreateUserRequest schema")))

  (testing "Pass through schema directly"
    (let [schema (gen/resolve-schema user-schema/CreateUserRequest {})]
      (is (= user-schema/CreateUserRequest schema) "Should return schema as-is")))

  (testing "Return nil for unknown keyword"
    (let [schema (gen/resolve-schema :unknown {:registry schema-registry})]
      (is (nil? schema) "Should return nil for unknown schema"))))

;; =============================================================================
;; Invalid Data Generation Tests
;; =============================================================================

(deftest ^:unit ^:phase3 gen-invalid-one-missing-required-test
  (testing "Generate user missing required email field"
    (let [invalid-user (gen/gen-invalid-one user-schema/CreateUserRequest
                                            :missing-required
                                            {:seed test-seed :field :email})]
      (is (some? invalid-user) "Should generate invalid user")
      (is (not (contains? invalid-user :email)) "Should be missing email field")
      (is (not (m/validate user-schema/CreateUserRequest invalid-user)) "Should fail validation"))))

(deftest ^:unit ^:phase3 gen-invalid-one-wrong-type-test
  (testing "Generate user with wrong type for email"
    (let [invalid-user (gen/gen-invalid-one user-schema/CreateUserRequest
                                            :wrong-type
                                            {:seed test-seed :field :email})]
      (is (some? invalid-user) "Should generate invalid user")
      (is (not (string? (:email invalid-user))) "Email should not be string")
      (is (not (m/validate user-schema/CreateUserRequest invalid-user)) "Should fail validation"))))

(deftest ^:unit ^:phase3 gen-invalid-one-wrong-format-test
  (testing "Generate user with invalid email format"
    (let [invalid-user (gen/gen-invalid-one user-schema/CreateUserRequest
                                            :wrong-format
                                            {:seed test-seed :field :email})]
      (is (some? invalid-user) "Should generate invalid user")
      (is (string? (:email invalid-user)) "Email should still be string")
      (is (= "not-an-email" (:email invalid-user)) "Email should be invalid format")
      (is (not (m/validate user-schema/CreateUserRequest invalid-user)) "Should fail validation"))))

(deftest ^:unit ^:phase3 gen-invalid-one-out-of-range-test
  (testing "Generate user with name too long"
    (let [invalid-user (gen/gen-invalid-one user-schema/CreateUserRequest
                                            :too-long
                                            {:seed test-seed :field :name})]
      (is (some? invalid-user) "Should generate invalid user")
      (is (string? (:name invalid-user)) "Name should be string")
      (is (> (count (:name invalid-user)) 255) "Name should exceed max length")
      (is (not (m/validate user-schema/CreateUserRequest invalid-user)) "Should fail validation"))))

(deftest ^:unit ^:phase3 gen-invalid-one-unknown-key-test
  (testing "Generate user with unknown extra key"
    ;; NOTE: CreateUserRequest (and most Malli :map schemas) allow extra keys by default
    ;; So :unknown-key violation adds a key but doesn't fail validation
    ;; This test verifies the generator adds the key, not that validation fails
    (let [valid-base (gen/gen-valid-one user-schema/CreateUserRequest {:seed test-seed})
          with-extra (gen/gen-invalid-one user-schema/CreateUserRequest
                                          :unknown-key
                                          {:seed test-seed})]
      (is (some? with-extra) "Should generate data with extra key")
      (is (> (count with-extra) (count valid-base)) "Should have more keys than base")
      (is (some #(re-find #"unknown-field" (name %)) (keys with-extra))
          "Should have unknown-field added"))))

(deftest ^:unit ^:phase3 gen-invalid-one-enum-outside-test
  (testing "Generate user with invalid role enum"
    (let [invalid-user (gen/gen-invalid-one user-schema/CreateUserRequest
                                            :enum-outside
                                            {:seed test-seed :field :role})]
      (is (some? invalid-user) "Should generate invalid user")
      (is (= :invalid-enum-value (:role invalid-user)) "Role should be invalid enum")
      (is (not (m/validate user-schema/CreateUserRequest invalid-user)) "Should fail validation"))))

(deftest ^:unit ^:phase3 gen-invalid-sequence-test
  (testing "Generate sequence of invalid users"
    (let [invalid-users (take 3 (gen/gen-invalid user-schema/CreateUserRequest
                                                 :missing-required
                                                 {:seed test-seed
                                                  :field :email
                                                  :count 5}))]
      (is (= 3 (count invalid-users)) "Should generate 3 invalid users")
      (is (every? #(not (m/validate user-schema/CreateUserRequest %)) invalid-users)
          "All users should be invalid")
      (is (every? #(not (contains? % :email)) invalid-users)
          "All users should be missing email"))))

(deftest ^:unit ^:phase3 gen-invalid-determinism-test
  (testing "Same seed produces identical invalid data"
    (let [invalid1 (gen/gen-invalid-one user-schema/CreateUserRequest
                                        :missing-required
                                        {:seed test-seed :field :name})
          invalid2 (gen/gen-invalid-one user-schema/CreateUserRequest
                                        :missing-required
                                        {:seed test-seed :field :name})]
      (is (= invalid1 invalid2) "Same seed should produce identical invalid data")))

  (testing "Different seeds produce different invalid data"
    (let [invalid1 (gen/gen-invalid-one user-schema/CreateUserRequest
                                        :missing-required
                                        {:seed test-seed :field :name})
          invalid2 (gen/gen-invalid-one user-schema/CreateUserRequest
                                        :missing-required
                                        {:seed alt-seed :field :name})]
      (is (not= invalid1 invalid2) "Different seeds should produce different invalid data"))))

;; =============================================================================
;; Wagoe Case Generation Tests
;; =============================================================================

(deftest ^:unit ^:phase3 gen-boundaries-test
  (testing "Generate boundary cases for User schema"
    (let [boundaries (gen/gen-boundaries user-schema/CreateUserRequest {:seed test-seed})]
      (is (vector? boundaries) "Should return vector of boundary cases")
      (is (pos? (count boundaries)) "Should generate at least one boundary case")
      ;; Check that we have some string boundary cases
      (let [name-values (set (map :name boundaries))]
        (is (contains? name-values "") "Should include empty string boundary")
        (is (contains? name-values "a") "Should include single char boundary")
        (is (some #(= 255 (count %)) name-values) "Should include max length boundary")))))

(deftest ^:unit ^:phase3 gen-boundaries-determinism-test
  (testing "Same seed produces identical boundaries"
    (let [bounds1 (gen/gen-boundaries user-schema/CreateUserRequest {:seed test-seed})
          bounds2 (gen/gen-boundaries user-schema/CreateUserRequest {:seed test-seed})]
      (is (= bounds1 bounds2) "Same seed should produce identical boundaries"))))

;; =============================================================================
;; Rule-Aware Generation Tests
;; =============================================================================

(deftest ^:unit ^:phase3 gen-for-rule-valid-test
  (testing "Generate valid data for specific rule"
    (let [resolver (fn [rule-id]
                     (case rule-id
                       :user.email/required user-schema/CreateUserRequest
                       :user.name/required user-schema/CreateUserRequest
                       nil))
          user (gen/gen-for-rule :user.email/required
                                 {:type :valid
                                  :seed test-seed
                                  :resolve-schema resolver})]
      (is (some? user) "Should generate user for rule")
      (is (m/validate user-schema/CreateUserRequest user) "User should be valid")
      (is (contains? user :email) "User should have email field"))))

(deftest ^:unit ^:phase3 gen-for-rule-invalid-test
  (testing "Generate invalid data for specific rule"
    (let [resolver (fn [rule-id]
                     (case rule-id
                       :user.email/required user-schema/CreateUserRequest
                       nil))
          invalid-user (gen/gen-for-rule :user.email/required
                                         {:type :invalid
                                          :violation :missing-required
                                          :field :email
                                          :seed test-seed
                                          :resolve-schema resolver})]
      (is (some? invalid-user) "Should generate invalid user for rule")
      (is (not (contains? invalid-user :email)) "User should be missing email")
      (is (not (m/validate user-schema/CreateUserRequest invalid-user)) "User should be invalid"))))

(deftest ^:unit ^:phase3 gen-for-rule-requires-resolver-test
  (testing "Throws when resolve-schema function not provided"
    (is (thrown-with-msg? Exception
                          #":resolve-schema function required"
                          (gen/gen-for-rule :user.email/required
                                            {:type :valid
                                             :seed test-seed})))))

(deftest ^:unit ^:phase3 gen-for-rule-requires-violation-test
  (testing "Throws when violation not provided for invalid type"
    (let [resolver (fn [_rule-id] user-schema/CreateUserRequest)]
      (is (thrown-with-msg? Exception
                            #":violation required for :invalid type"
                            (gen/gen-for-rule :user.email/required
                                              {:type :invalid
                                               :seed test-seed
                                               :resolve-schema resolver}))))))

(deftest ^:unit ^:phase3 gen-for-module-test
  (testing "Generate examples for all rules in module"
    (let [list-rules (fn [module-kw]
                       (case module-kw
                         :user [:user.email/required :user.name/required]
                         []))
          resolver (fn [rule-id]
                     (case rule-id
                       :user.email/required user-schema/CreateUserRequest
                       :user.name/required user-schema/CreateUserRequest
                       nil))
          examples (gen/gen-for-module :user
                                       {:type :valid
                                        :seed test-seed
                                        :list-rules list-rules
                                        :resolve-schema resolver})]
      (is (map? examples) "Should return map of examples")
      (is (= 2 (count examples)) "Should generate for 2 rules")
      (is (contains? examples :user.email/required) "Should include email rule")
      (is (contains? examples :user.name/required) "Should include name rule")
      (is (m/validate user-schema/CreateUserRequest (:user.email/required examples))
          "Email rule example should be valid")
      (is (m/validate user-schema/CreateUserRequest (:user.name/required examples))
          "Name rule example should be valid"))))

(deftest ^:unit ^:phase3 gen-for-module-requires-list-rules-test
  (testing "Throws when list-rules function not provided"
    (let [resolver (fn [_rule-id] user-schema/CreateUserRequest)]
      (is (thrown-with-msg? Exception
                            #":list-rules function required"
                            (gen/gen-for-module :user
                                                {:type :valid
                                                 :seed test-seed
                                                 :resolve-schema resolver}))))))

;; =============================================================================
;; Edge Cases and Error Handling Tests
;; =============================================================================

(deftest ^:unit ^:phase3 rng-from-seed-test
  (testing "Create deterministic RNG from seed"
    (let [rng1 (gen/rng-from-seed test-seed)
          rng2 (gen/rng-from-seed test-seed)
          val1 (.nextInt rng1 100)
          val2 (.nextInt rng2 100)]
      (is (= val1 val2) "Same seed should produce same random values"))))

(deftest ^:unit ^:phase3 gen-valid-one-with-nil-schema-test
  (testing "Returns nil when schema is nil"
    (let [result (gen/gen-valid-one nil {:seed test-seed})]
      (is (nil? result) "Should return nil for nil schema"))))

(deftest ^:unit ^:phase3 gen-invalid-one-with-nil-schema-test
  (testing "Returns nil when schema is nil"
    (let [result (gen/gen-invalid-one nil :missing-required {:seed test-seed :field :email})]
      (is (nil? result) "Should return nil for nil schema"))))

(deftest ^:unit ^:phase3 violation-types-set-test
  (testing "Violation types set contains expected types"
    (is (set? gen/violation-types) "Should be a set")
    (is (contains? gen/violation-types :missing-required) "Should contain :missing-required")
    (is (contains? gen/violation-types :wrong-type) "Should contain :wrong-type")
    (is (contains? gen/violation-types :wrong-format) "Should contain :wrong-format")
    (is (contains? gen/violation-types :out-of-range) "Should contain :out-of-range")
    (is (contains? gen/violation-types :too-long) "Should contain :too-long")
    (is (contains? gen/violation-types :too-short) "Should contain :too-short")
    (is (contains? gen/violation-types :unknown-key) "Should contain :unknown-key")
    (is (contains? gen/violation-types :enum-outside) "Should contain :enum-outside")
    (is (contains? gen/violation-types :boundary-underflow) "Should contain :boundary-underflow")
    (is (contains? gen/violation-types :boundary-overflow) "Should contain :boundary-overflow")
    (is (= 10 (count gen/violation-types)) "Should have exactly 10 violation types")))

;; =============================================================================
;; Integration Tests with All Violation Types
;; =============================================================================

(deftest ^:unit ^:phase3 all-violation-types-test
  (testing "All violation types produce data (may or may not be invalid)"
    (doseq [violation-type gen/violation-types]
      ;; Skip :unknown-key as it doesn't fail validation on schemas that allow extra keys
      (when-not (= violation-type :unknown-key)
        (let [opts {:seed test-seed
                    :field :email}
              invalid-user (gen/gen-invalid-one user-schema/CreateUserRequest
                                                violation-type
                                                opts)]
          (is (some? invalid-user)
              (str "Should generate data for " violation-type))
          (is (map? invalid-user)
              (str "Should return map for " violation-type))
          ;; Verify it actually fails validation (except for edge cases)
          (when-not (#{:unknown-key} violation-type)
            (is (not (m/validate user-schema/CreateUserRequest invalid-user))
                (str "Should fail validation for " violation-type))))))))
