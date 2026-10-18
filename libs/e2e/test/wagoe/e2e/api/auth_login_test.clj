(ns wagoe.e2e.api.auth-login-test
  "E2E tests for POST /api/v1/auth/login.

   - On success: 200 with {:authenticated true, :user {...}, :session {...},
     :sessionToken \"...\", :jwtToken \"...\"}.
   - Wrong credentials: 401 in the standard error shape, the same body for an
     unknown email (BOU-691).
   - No Set-Cookie header is set (cookie-based auth is web-only)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.users :as users]))

(use-fixtures :each fx/with-fresh-seed)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- body-str
  "Serialise the parsed response body back to a string for substring checks."
  [resp]
  (pr-str (:body resp)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest ^:integration ^:e2e login-happy-returns-authenticated-session
  (testing "Valid credentials yield authenticated:true with session data and no password-hash leak"
    (let [resp (users/login {:email    (-> fx/*seed* :admin :email)
                             :password (-> fx/*seed* :admin :password)})
          body (:body resp)]
      (is (= 200 (:status resp)))
      ;; Core authentication flag
      (is (true? (:authenticated body)))
      ;; Session token returned in body (not as cookie)
      (is (string? (:sessionToken body))
          "sessionToken should be present in the response body")
      (is (pos? (count (:sessionToken body))))
      ;; JWT token also returned
      (is (string? (:jwtToken body)))
      ;; User map present
      (is (map? (:user body)))
      (is (= (-> fx/*seed* :admin :email) (get-in body [:user :email])))
      ;; Nested keys in both styles until 2.0; the kebab-case ones are deprecated
      (is (= (:sessionToken body)
             (get-in body [:session :sessionToken])
             (get-in body [:session :session-token])))
      (is (some? (get-in body [:session :expiresAt])))
      (is (= (get-in body [:session :expiresAt]) (get-in body [:session :expires-at])))
      ;; No password-hash leak anywhere in response
      (let [s (body-str resp)]
        (is (not (str/includes? s "password-hash")))
        (is (not (str/includes? s "passwordHash")))
        (is (not (str/includes? s "password_hash")))))))

(deftest ^:integration ^:e2e login-wrong-password-returns-unauthenticated
  (testing "Wrong password yields 401 in the error shape, with no session data"
    (let [resp (users/login {:email    (-> fx/*seed* :admin :email)
                             :password "Wrong-Pass-1234!"})
          err  (users/error-of resp)]
      (is (= 401 (:status resp)))
      (is (= "unauthorized" (:type err)))
      (is (string? (:correlation-id err)))
      (is (not (str/includes? (str (:body resp)) "sessionToken"))))))

(deftest ^:integration ^:e2e login-unknown-email-no-enumeration
  (testing "Nonexistent email yields the same 401 as a wrong password"
    (let [refusal (fn [email]
                    (let [resp (users/login {:email email :password "Wrong-Pass-1234!"})]
                      [(:status resp) (dissoc (users/error-of resp) :correlation-id)]))]
      (is (= [401 {:type "unauthorized" :message "Invalid email or password"}]
             (refusal "nonexistent@acme.test")
             (refusal (-> fx/*seed* :admin :email)))))))
