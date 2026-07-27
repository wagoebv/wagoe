(ns wagoe.e2e.api.auth-sessions-test
  "E2E tests for session management and security-related assertions.

   Discovered behaviour:
   - POST /api/v1/sessions creates a session (same as /api/v1/auth/login).
   - GET/DELETE /api/v1/sessions/:token work with URL-safe base64 session
     tokens (no +, /, or = chars). Helpers URL-encode the token for safety.
   - Lockout: enforced at both auth-shell and service layers after 5 failed
     attempts (15 min lockout window).
   - Unauthenticated access to protected endpoints returns 401 (empty body)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.users :as users]
            [wagoe.e2e.helpers.reset :as reset]
            [clj-http.client :as http]))

(use-fixtures :each fx/with-fresh-seed)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- login-resp
  "Login as seed admin via the sessions endpoint. Returns the full response."
  []
  (users/create-session {:email    (-> fx/*seed* :admin :email)
                         :password (-> fx/*seed* :admin :password)}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest ^:integration ^:e2e create-session-returns-token
  (testing "POST /api/v1/sessions with valid creds returns authenticated:true with session"
    (let [resp (login-resp)
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (true? (:authenticated body)))
      (is (string? (:sessionToken body)))
      (is (map? (:session body)))
      (is (string? (get-in body [:session :id]))
          "Session should have a UUID id"))))

(deftest ^:integration ^:e2e session-validate-with-token-returns-200
  (testing "GET /api/v1/sessions/:token with actual session token returns 200"
    (let [resp      (login-resp)
          token     (:sessionToken (:body resp))
          val-resp  (users/validate-session token)]
      (is (= 200 (:status val-resp))
          "Session validate with URL-safe token should succeed")
      (is (true? (get-in val-resp [:body :valid]))
          "Validated session should be marked valid"))))

(deftest ^:integration ^:e2e delete-session-with-token-returns-204
  (testing "DELETE /api/v1/sessions/:token with actual session token returns 204"
    (let [resp      (login-resp)
          token     (:sessionToken (:body resp))
          del-resp  (users/invalidate-session token)]
      (is (= 204 (:status del-resp))
          "Session invalidation with URL-safe token should return 204"))))

(deftest ^:integration ^:e2e protected-endpoint-without-token-is-401
  (testing "GET /api/v1/auth/mfa/status without any credentials returns 401"
    (let [resp (http/get (str (reset/default-base-url) "/api/v1/auth/mfa/status")
                         {:accept           :json
                          :throw-exceptions false})]
      (is (= 401 (:status resp))))))

(deftest ^:integration ^:e2e password-hash-never-appears-in-auth-responses
  (testing "Neither login nor register responses contain password-hash variants"
    ;; Login response
    (let [login-body (pr-str (:body (users/login
                                     {:email    (-> fx/*seed* :admin :email)
                                      :password (-> fx/*seed* :admin :password)})))]
      (is (not (str/includes? login-body "password-hash")))
      (is (not (str/includes? login-body "passwordHash")))
      (is (not (str/includes? login-body "password_hash"))))
    ;; Register response (web form — body is HTML string)
    (let [reg-body (str (:body (users/register
                                {:email    "security-check@acme.test"
                                 :password "Strong-Pass-1234!"
                                 :name     "Security Check"})))]
      (is (not (str/includes? reg-body "password-hash")))
      (is (not (str/includes? reg-body "passwordHash")))
      (is (not (str/includes? reg-body "password_hash"))))))

;; ---------------------------------------------------------------------------
;; Lockout — enforced at service level
;; ---------------------------------------------------------------------------

(deftest ^:integration ^:e2e lockout-enforced-after-threshold
  (testing "Account is locked out after 5 failed login attempts"
    ;; Fire 6 failed login attempts (threshold is 5)
    (dotimes [_ 6]
      (users/login {:email    (-> fx/*seed* :admin :email)
                    :password "Wrong-Pass-1234!"}))
    ;; Correct password should now be rejected because account is locked
    (let [resp (users/login {:email    (-> fx/*seed* :admin :email)
                             :password (-> fx/*seed* :admin :password)})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (false? (:authenticated body))
          "Login must be rejected when account is locked out")
      (is (some? (:message body))
          "Lockout response should include a human-readable message"))))
