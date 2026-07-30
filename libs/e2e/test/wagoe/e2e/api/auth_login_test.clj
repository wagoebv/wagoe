(ns wagoe.e2e.api.auth-login-test
  "E2E tests for POST /api/v1/auth/login.

   Verified behaviour (discovered against the live server):
   - The endpoint always returns HTTP 200.
   - On success: body has {:authenticated true, :user {...}, :session {...},
     :sessionToken \"...\", :jwtToken \"...\"}.
   - On failure: body has {:authenticated false, :reason \"...\", :message \"...\"}
     (message key may be absent for unknown-email).
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
      ;; Status is always 200 for this endpoint
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
      ;; No password-hash leak anywhere in response
      (let [s (body-str resp)]
        (is (not (str/includes? s "password-hash")))
        (is (not (str/includes? s "passwordHash")))
        (is (not (str/includes? s "password_hash")))))))

(deftest ^:integration ^:e2e login-wrong-password-returns-unauthenticated
  (testing "Wrong password yields authenticated:false with no session data"
    (let [resp (users/login {:email    (-> fx/*seed* :admin :email)
                             :password "Wrong-Pass-1234!"})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (false? (:authenticated body)))
      (is (= "authentication-failed" (:reason body)))
      ;; No session token should be in the response
      (is (nil? (:sessionToken body)))
      (is (nil? (:session body))))))

(deftest ^:integration ^:e2e login-unknown-email-no-enumeration
  (testing "Nonexistent email yields a generic failure — no user-enumeration clues"
    (let [resp (users/login {:email    "nonexistent@acme.test"
                             :password "Wrong-Pass-1234!"})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (false? (:authenticated body)))
      ;; The message must NOT reveal whether the email exists
      (let [s (str/lower-case (body-str resp))]
        (is (not (str/includes? s "not found")))
        (is (not (str/includes? s "does not exist")))))))
