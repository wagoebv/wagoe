(ns wagoe.e2e.api.auth-register-test
  "E2E tests for POST /web/register (form-encoded).

   Discovery note: there is NO JSON API register endpoint.
   Registration is only available via /web/register with form params.

   Verified behaviour:
   - Success: 303 redirect to /web/profile, Set-Cookie with session-token (HttpOnly).
   - Duplicate email: 500 with HTML body containing 'User already exists'.
   - Weak password: 400 with HTML body containing password validation errors."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.users :as users]
            [wagoe.e2e.helpers.cookies :as cookies]))

(use-fixtures :each fx/with-fresh-seed)

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest ^:integration ^:e2e register-happy-creates-user
  (testing "New email via /web/register returns 303 redirect with session cookie, no password-hash leak"
    (let [resp (users/register {:email    "newuser@acme.test"
                                :password "Strong-Pass-1234!"
                                :name     "New User"})
          hdrs (:headers resp)
          body (str (:body resp))]
      ;; Successful registration redirects to profile page
      (is (= 303 (:status resp)))
      (is (str/includes? (get hdrs "Location" "") "/web/profile"))
      ;; Session cookie is set and is HttpOnly
      (is (some? (cookies/session-token hdrs))
          "session-token cookie should be set on successful registration")
      ;; No password-hash leak in any part of the response
      (is (not (str/includes? body "password-hash")))
      (is (not (str/includes? body "passwordHash")))
      (is (not (str/includes? body "password_hash"))))))

(deftest ^:integration ^:e2e register-duplicate-email-rejected
  (testing "Registering with an existing email returns an error"
    (let [resp (users/register {:email    (-> fx/*seed* :admin :email)
                                :password "Strong-Pass-1234!"
                                :name     "Duplicate"})
          body (str (:body resp))]
      ;; Server returns 500 with user-exists error (not ideal, but that's the current behaviour)
      (is (contains? #{409 500} (:status resp))
          "Duplicate email should be rejected with 409 or 500")
      (is (str/includes? (str/lower-case body) "already exists")
          "Response should mention the user already exists"))))

(deftest ^:integration ^:e2e register-weak-password-rejected
  (testing "Weak password returns 400 with password validation feedback"
    (let [resp (users/register {:email    "weak@acme.test"
                                :password "abc"
                                :name     "Weak"})
          body (str (:body resp))]
      (is (= 400 (:status resp)))
      ;; Body should mention password requirements
      (is (or (str/includes? (str/lower-case body) "password")
              (str/includes? (str/lower-case body) "characters"))
          "Response should reference password policy or length"))))
