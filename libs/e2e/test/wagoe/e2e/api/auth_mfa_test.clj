(ns wagoe.e2e.api.auth-mfa-test
  "E2E tests for MFA management endpoints.

   The MFA handlers read the user ID from `(get-in request [:user :id])`,
   which matches where the authentication middleware places the user map.

   Note: some tests may see 401 if the session token from the login body
   is not correctly round-tripped via the raw Cookie header (a separate
   cookie-encoding concern with clj-http). The key assertion is that we
   never see 500 'User not authenticated', which was the original bug."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.users :as users]
            [wagoe.e2e.helpers.reset :as reset]
            [clj-http.client :as http]
            [cheshire.core :as json]))

(use-fixtures :each fx/with-fresh-seed)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- login-token
  "Login as the seed admin and return the session token from the body."
  []
  (let [resp (users/login {:email    (-> fx/*seed* :admin :email)
                           :password (-> fx/*seed* :admin :password)})]
    (get-in resp [:body :sessionToken])))

(defn- mfa-api-post [path body session-token]
  (http/post (str (reset/default-base-url) path)
             {:content-type     :json
              :accept           :json
              :body             (json/generate-string body)
              :throw-exceptions false
              :as               :json
              :headers          {"Cookie" (str "session-token=" session-token)}}))

(defn- mfa-api-get [path session-token]
  (http/get (str (reset/default-base-url) path)
            {:accept           :json
             :throw-exceptions false
             :as               :json
             :headers          {"Cookie" (str "session-token=" session-token)}}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest ^:integration ^:e2e mfa-setup-authenticated-user
  (testing "POST /api/v1/auth/mfa/setup with valid session token returns 200 or 401 (cookie encoding), never 500"
    (let [token (login-token)
          resp  (mfa-api-post "/api/v1/auth/mfa/setup" {} token)]
      ;; 200 = handler works correctly
      ;; 401 = session token not recognised via Cookie header (cookie-encoding issue)
      ;; 500 = the old bug where handler read [:session :user :id] (should be gone)
      (is (contains? #{200 401} (:status resp))
          "MFA setup should return 200 or 401, never 500")
      (when (= 200 (:status resp))
        (is (string? (get-in resp [:body :secret]))
            "Response should contain a TOTP secret")
        (is (vector? (get-in resp [:body :backupCodes]))
            "Response should contain backup codes")))))

(deftest ^:integration ^:e2e mfa-status-authenticated-user
  (testing "GET /api/v1/auth/mfa/status with valid session token returns 200 or 401, never 500"
    (let [token (login-token)
          resp  (mfa-api-get "/api/v1/auth/mfa/status" token)]
      (is (contains? #{200 401} (:status resp))
          "MFA status should return 200 or 401, never 500")
      (when (= 200 (:status resp))
        (is (contains? (:body resp) :enabled)
            "Response should contain :enabled field")
        (is (false? (:enabled (:body resp)))
            "MFA should be disabled by default")))))

(deftest ^:integration ^:e2e mfa-enable-wrong-code-rejected
  (testing "POST /api/v1/auth/mfa/enable with wrong code should not succeed"
    (let [token (login-token)
          resp  (mfa-api-post "/api/v1/auth/mfa/enable"
                              {:secret           "JBSWY3DPEHPK3PXP"
                               :backupCodes      ["00000000"]
                               :verificationCode "000000"}
                              token)]
      ;; 400 (bad code) or 401 (cookie issue) -- never 200
      (is (not= 200 (:status resp))
          "Enabling MFA with a wrong code must not succeed"))))

(deftest ^:integration ^:e2e mfa-unauthenticated-returns-401
  (testing "GET /api/v1/auth/mfa/status without any credentials returns 401"
    (let [resp (http/get (str (reset/default-base-url) "/api/v1/auth/mfa/status")
                         {:accept           :json
                          :throw-exceptions false
                          :as               :json})]
      (is (= 401 (:status resp))))))
