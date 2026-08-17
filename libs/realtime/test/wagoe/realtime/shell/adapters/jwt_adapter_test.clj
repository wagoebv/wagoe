(ns wagoe.realtime.shell.adapters.jwt-adapter-test
  "Integration tests for JWT verification adapters (shell layer).
   
   Tests JWT verification operations - verifies token validation,
   claims transformation, and error handling."
  {:kaocha.testable/meta {:integration true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.shell.adapters.jwt-adapter :as jwt]
            [wagoe.realtime.ports :as ports]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def test-user-id #uuid "550e8400-e29b-41d4-a716-446655440000")

(def test-claims
  {:user-id test-user-id
   :email "test@example.com"
   :roles #{:user}
   :permissions #{}
   :exp 1735689600 ; 2025-01-01
   :iat 1704153600}) ; 2024-01-01

;; =============================================================================
;; TestJWTAdapter Tests
;; =============================================================================

(deftest ^:unit test-adapter-verify-valid-token-test
  (testing "verifying valid token with test adapter"
    (let [adapter (jwt/create-test-jwt-adapter
                   (assoc test-claims :expected-token "valid-token"))
          claims (ports/verify-jwt adapter "valid-token")]

      (testing "returns expected claims"
        (is (= test-user-id (:user-id claims)))
        (is (= "test@example.com" (:email claims)))
        (is (= #{:user} (:roles claims)))
        (is (= 1735689600 (:exp claims)))
        (is (= 1704153600 (:iat claims))))

      (testing "does not include expected-token in claims"
        (is (nil? (:expected-token claims)))))))

(deftest ^:unit test-adapter-verify-invalid-token-test
  (testing "verifying invalid token with test adapter"
    (let [adapter (jwt/create-test-jwt-adapter
                   (assoc test-claims :expected-token "valid-token"))]

      (testing "throws unauthorized exception"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Unauthorized.*Invalid test token"
             (ports/verify-jwt adapter "invalid-token"))))

      (testing "exception has correct ex-data"
        (try
          (ports/verify-jwt adapter "invalid-token")
          (catch clojure.lang.ExceptionInfo e
            (let [data (ex-data e)]
              (is (= :unauthorized (:type data)))
              (is (some? (:message data))))))))))

(deftest ^:unit test-adapter-verify-nil-token-test
  (testing "verifying nil token with test adapter"
    (let [adapter (jwt/create-test-jwt-adapter
                   (assoc test-claims :expected-token "valid-token"))]

      (testing "throws unauthorized exception"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Unauthorized"
             (ports/verify-jwt adapter nil)))))))

(deftest ^:unit test-adapter-verify-empty-token-test
  (testing "verifying empty token with test adapter"
    (let [adapter (jwt/create-test-jwt-adapter
                   (assoc test-claims :expected-token "valid-token"))]

      (testing "throws unauthorized exception"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Unauthorized"
             (ports/verify-jwt adapter "")))))))

(deftest ^:unit test-adapter-multiple-verify-calls-test
  (testing "multiple verification calls with test adapter"
    (let [adapter (jwt/create-test-jwt-adapter
                   (assoc test-claims :expected-token "valid-token"))]

      (testing "can verify same token multiple times"
        (let [claims-1 (ports/verify-jwt adapter "valid-token")
              claims-2 (ports/verify-jwt adapter "valid-token")
              claims-3 (ports/verify-jwt adapter "valid-token")]

          (is (= claims-1 claims-2 claims-3))
          (is (= test-user-id (:user-id claims-1))))))))

(deftest ^:unit test-adapter-with-multiple-roles-test
  (testing "test adapter with user having multiple roles"
    (let [multi-role-claims (assoc test-claims :roles #{:user :admin :moderator})
          adapter (jwt/create-test-jwt-adapter
                   (assoc multi-role-claims :expected-token "token"))
          claims (ports/verify-jwt adapter "token")]

      (testing "returns all roles"
        (is (= #{:user :admin :moderator} (:roles claims)))))))

(deftest ^:unit test-adapter-with-permissions-test
  (testing "test adapter with permissions"
    (let [claims-with-perms (assoc test-claims
                                   :permissions #{:read :write :delete})
          adapter (jwt/create-test-jwt-adapter
                   (assoc claims-with-perms :expected-token "token"))
          claims (ports/verify-jwt adapter "token")]

      (testing "returns all permissions"
        (is (= #{:read :write :delete} (:permissions claims)))))))

(deftest ^:unit test-adapter-claims-structure-test
  (testing "test adapter claims structure"
    (let [adapter (jwt/create-test-jwt-adapter
                   (assoc test-claims :expected-token "token"))
          claims (ports/verify-jwt adapter "token")]

      (testing "claims have expected keys"
        (is (contains? claims :user-id))
        (is (contains? claims :email))
        (is (contains? claims :roles))
        (is (contains? claims :permissions))
        (is (contains? claims :exp))
        (is (contains? claims :iat)))

      (testing "claims have correct types"
        (is (uuid? (:user-id claims)))
        (is (string? (:email claims)))
        (is (set? (:roles claims)))
        (is (set? (:permissions claims)))
        (is (number? (:exp claims)))
        (is (number? (:iat claims)))))))

;; =============================================================================
;; Factory Function Tests
;; =============================================================================

(deftest ^:unit create-test-jwt-adapter-test
  (testing "creating test JWT adapter"
    (let [test-claims {:expected-token "token"
                       :user-id test-user-id
                       :email "test@example.com"
                       :roles #{:user}}
          adapter (jwt/create-test-jwt-adapter test-claims)]

      (testing "returns adapter implementing protocol"
        (is (satisfies? ports/IJWTVerifier adapter)))

      (testing "adapter has internal state"
        (is (some? (:test-claims adapter)))
        (is (instance? clojure.lang.Atom (:test-claims adapter)))))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest ^:unit test-adapter-error-data-structure-test
  (testing "error data structure for invalid tokens"
    (let [adapter (jwt/create-test-jwt-adapter
                   (assoc test-claims :expected-token "valid-token"))]

      (try
        (ports/verify-jwt adapter "bad-token")
        (is false "Expected exception to be thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (testing "has :type key"
              (is (= :unauthorized (:type data))))

            (testing "has :message key"
              (is (string? (:message data)))
              (is (seq (:message data))))))))))

(deftest ^:unit test-adapter-consistent-errors-test
  (testing "test adapter produces consistent errors"
    (let [adapter (jwt/create-test-jwt-adapter
                   (assoc test-claims :expected-token "valid-token"))]

      (testing "same invalid token produces same error type"
        (let [error-1 (try (ports/verify-jwt adapter "bad")
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))
              error-2 (try (ports/verify-jwt adapter "bad")
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))]

          (is (= (:type error-1) (:type error-2)))
          (is (= :unauthorized (:type error-1))))))))

;; =============================================================================
;; Claims Transformation Tests
;; =============================================================================

(deftest ^:unit test-adapter-claims-transformation-test
  (testing "test adapter does not transform claims"
    (let [original-claims {:user-id test-user-id
                           :email "test@example.com"
                           :roles #{:user :admin}
                           :custom-field "custom-value"
                           :expected-token "token"}
          adapter (jwt/create-test-jwt-adapter original-claims)
          returned-claims (ports/verify-jwt adapter "token")]

      (testing "preserves all fields except expected-token"
        (is (= test-user-id (:user-id returned-claims)))
        (is (= "test@example.com" (:email returned-claims)))
        (is (= #{:user :admin} (:roles returned-claims)))
        (is (= "custom-value" (:custom-field returned-claims)))
        (is (nil? (:expected-token returned-claims)))))))

;; Whatever issues the tokens has its own claim format, so translating it to
;; realtime's — {:user-id uuid :roles #{kw} …} — belongs in the application's
;; IJWTVerifier, and is tested there.
