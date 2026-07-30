(ns wagoe.user.authentication-integration-test
  "Integration tests for the authentication system.
   
   These tests verify the complete authentication flow including:
   - Password hashing during user creation
   - Login credential validation
   - JWT token generation
   - Session management
   - Authentication middleware"
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.user.core.authentication :as auth-core]
            [wagoe.user.shell.auth :as auth-shell]
            [wagoe.user.schema :as schema]
            [malli.core :as m])
  (:import (java.time Instant)
           (java.util UUID)))

;; =============================================================================
;; Test Data and Helpers
;; =============================================================================

(def test-user
  {:id (UUID/randomUUID)
   :email "test@example.com"
   :name "Test User"
   :role :user
   :active true
   :password-hash "$2a$12$test.hash.here"
   :created-at (Instant/now)
   :updated-at (Instant/now)
   :login-count 0
   :failed-login-count 0})

(def test-login-config
  {:max-failed-attempts 5
   :lockout-duration-minutes 15
   :alert-threshold 3})

;; =============================================================================
;; Authentication Core Tests
;; =============================================================================

(deftest ^:unit test-authentication-core-functions
  (testing "validate-login-credentials"
    (let [valid-credentials {:email "test@example.com" :password "password123"}
          invalid-credentials {:email "test@example.com"}]

      (testing "accepts valid credentials"
        (let [result (auth-core/validate-login-credentials valid-credentials)]
          (is (:valid? result))
          (is (= valid-credentials (:data result)))))

      (testing "rejects invalid credentials"
        (let [result (auth-core/validate-login-credentials invalid-credentials)]
          (is (not (:valid? result)))
          (is (:errors result))))))

  (testing "should-allow-login-attempt?"
    (let [current-time (Instant/now)]

      (testing "allows login for active user"
        (let [result (auth-core/should-allow-login-attempt? test-user test-login-config current-time)]
          (is (:allowed? result))))

      (testing "rejects login for inactive user"
        (let [inactive-user (assoc test-user :active false)
              result (auth-core/should-allow-login-attempt? inactive-user test-login-config current-time)]
          (is (not (:allowed? result)))
          (is (= "Account is deactivated" (:reason result)))))

      (testing "rejects login for deleted user"
        (let [deleted-user (assoc test-user :deleted-at (Instant/now))
              result (auth-core/should-allow-login-attempt? deleted-user test-login-config current-time)]
          (is (not (:allowed? result)))
          (is (= "Account no longer exists" (:reason result)))))

      (testing "rejects login for locked user"
        (let [locked-until (.plusSeconds current-time 900) ; 15 minutes
              locked-user (assoc test-user :lockout-until locked-until)
              result (auth-core/should-allow-login-attempt? locked-user test-login-config current-time)]
          (is (not (:allowed? result)))
          (is (= "Account temporarily locked due to failed login attempts" (:reason result)))
          (is (= locked-until (:retry-after result)))))

      (testing "rejects login for non-existent user"
        (let [result (auth-core/should-allow-login-attempt? nil test-login-config current-time)]
          (is (not (:allowed? result)))
          (is (= "Invalid credentials" (:reason result)))))))

  (testing "calculate-failed-login-consequences"
    (let [current-time (Instant/now)]

      (testing "increments failed login count"
        (let [user-with-failures (assoc test-user :failed-login-count 2)
              result (auth-core/calculate-failed-login-consequences user-with-failures test-login-config current-time)]
          (is (= 3 (:failed-login-count result)))
          (is (:should-alert? result))))

      (testing "locks account after max attempts"
        (let [user-with-max-failures (assoc test-user :failed-login-count 4)
              result (auth-core/calculate-failed-login-consequences user-with-max-failures test-login-config current-time)]
          (is (= 5 (:failed-login-count result)))
          (is (:lockout-until result))
          (is (:should-alert? result))))))

  (testing "prepare-successful-login-updates"
    (let [current-time (Instant/now)
          user-with-failures (assoc test-user :failed-login-count 3 :login-count 5)
          result (auth-core/prepare-successful-login-updates user-with-failures current-time)]

      (is (= current-time (:last-login result)))
      (is (= 6 (:login-count result)))
      (is (= 0 (:failed-login-count result)))
      (is (nil? (:lockout-until result))))))

;; =============================================================================
;; Authentication Shell Tests  
;; =============================================================================

(deftest ^:unit test-authentication-shell-functions
  (testing "password hashing and verification"
    (let [plain-password "test-password-123"
          hashed-password (auth-shell/hash-password plain-password)]

      (testing "hash-password produces valid hash"
        (is (string? hashed-password))
        (is (.startsWith hashed-password "bcrypt+sha512$"))
        (is (> (count hashed-password) 50)))

      (testing "verify-password works with hashed password"
        (is (auth-shell/verify-password plain-password hashed-password))
        (is (not (auth-shell/verify-password "wrong-password" hashed-password))))))

  (testing "JWT token operations"
    ;; Mock JWT_SECRET for testing since it's required from environment
    (with-redefs-fn {#'wagoe.user.shell.auth/get-jwt-secret 
                     (constantly "test-secret-minimum-32-characters-long-for-testing")}
      #(let [test-user-for-jwt (dissoc test-user :password-hash)
             jwt-token (auth-shell/create-jwt-token test-user-for-jwt 24)]

         (testing "create-jwt-token produces valid JWT"
           (is (string? jwt-token))
           (is (> (count jwt-token) 100)) ; JWTs are typically long
           (is (.contains jwt-token "."))) ; JWTs have dots as separators

         (testing "verify-jwt-token validates JWT correctly"
           (let [result (auth-shell/validate-jwt-token jwt-token)]
             (is (:valid? result))
             (is (= (str (:id test-user)) (get-in result [:claims :sub])))
             (is (= (:email test-user) (get-in result [:claims :email])))
             (is (= (name (:role test-user)) (get-in result [:claims :role]))))

           (testing "rejects invalid JWT"
             (let [result (auth-shell/validate-jwt-token "invalid-jwt-token")]
               (is (not (:valid? result)))
               (is (:error result)))))))))

;; =============================================================================
;; Schema Validation Tests
;; =============================================================================

(deftest ^:unit test-authentication-schemas
  (testing "LoginRequest schema"
    (let [valid-login {:email "test@example.com" :password "password123"}
          invalid-login {:email "not-an-email" :password "123"}]

      (is (m/validate schema/LoginRequest valid-login))
      (is (not (m/validate schema/LoginRequest invalid-login)))))

  (testing "CreateUserRequest includes password"
    (let [valid-user {:email "test@example.com"
                      :name "Test User"
                      :password "secure-password"
                      :role :user}
          invalid-user {:email "test@example.com"
                        :name "Test User"
                        :role :user}] ; Missing password

      (is (m/validate schema/CreateUserRequest valid-user))
      (is (not (m/validate schema/CreateUserRequest invalid-user))))))

;; =============================================================================
;; Password Policy Tests
;; =============================================================================

(deftest ^:unit test-password-policies
  (testing "meets-password-policy?"
    (let [policy {:min-length 8 :require-uppercase true :require-lowercase true :require-numbers true}
          user-context {:email "test@example.com"}]

      (testing "accepts strong password"
        (let [result (auth-core/meets-password-policy? "StrongPass123" policy user-context)]
          (is (:valid? result))))

      (testing "rejects weak passwords"
        (let [weak-result (auth-core/meets-password-policy? "weak" policy user-context)
              no-upper-result (auth-core/meets-password-policy? "nouppercase123" policy user-context)
              no-lower-result (auth-core/meets-password-policy? "NOLOWERCASE123" policy user-context)
              no-digits-result (auth-core/meets-password-policy? "NoDigitsHere" policy user-context)]
          (is (not (:valid? weak-result)))
          (is (not (:valid? no-upper-result)))
          (is (not (:valid? no-lower-result)))
          (is (not (:valid? no-digits-result)))))))

  (testing "calculate-password-strength"
    (testing "calculates strength scores"
      (let [weak-result (auth-core/calculate-password-strength "weak")
            strong-result (auth-core/calculate-password-strength "VeryStr0ng!P@ssw0rd")]
        (is (< (:strength-score weak-result) 50))
        (is (> (:strength-score strong-result) 80))
        (is (#{:weak :moderate} (:strength-level weak-result)))
        (is (#{:strong :very-strong} (:strength-level strong-result)))))))