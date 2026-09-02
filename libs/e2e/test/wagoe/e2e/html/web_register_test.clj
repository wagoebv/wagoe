(ns wagoe.e2e.html.web-register-test
  "E2E browser tests for the /web/register page using spel (Playwright).

   These tests exercise the self-service registration flow: form rendering,
   successful registration with auto-login, and password validation feedback."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.reset :as reset]))

(use-fixtures :each fx/with-fresh-seed)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private base-url (reset/default-base-url))

(defn- find-cookie
  "Find a cookie by name from the browser context. Returns the cookie map or nil."
  [page cookie-name]
  (let [ctx (page/page-context page)
        cookies (spel/context-cookies ctx)]
    (first (filter #(= cookie-name (:name %)) cookies))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest ^:integration ^:e2e get-renders-register-form
  (testing "GET /web/register renders a visible form with name, email, and password fields"
    (spel/with-testing-page [pg]
      (page/navigate pg (str base-url "/web/register"))
      (page/wait-for-load-state pg)
      ;; Register form uses class "form-card"
      (let [form (page/locator pg "form.form-card")]
        (is (loc/is-visible? form)
            "Register form with .form-card class should be visible"))
      ;; Name input
      (let [name-input (page/locator pg "input[name='name']")]
        (is (loc/is-visible? name-input)
            "Name input should be present"))
      ;; Email input
      (let [email-input (page/locator pg "input[name='email']")]
        (is (loc/is-visible? email-input)
            "Email input should be present"))
      ;; Password input
      (let [pw-input (page/locator pg "input[name='password']")]
        (is (loc/is-visible? pw-input)
            "Password input should be present")))))

(deftest ^:integration ^:e2e happy-creates-user-and-redirects
  (testing "Successful registration redirects away from /web/register with session cookie"
    (spel/with-testing-page [pg]
      (page/navigate pg (str base-url "/web/register"))
      (page/wait-for-load-state pg)
      (loc/fill (page/locator pg "input[name='name']") "New Test User")
      (loc/fill (page/locator pg "input[name='email']") "newuser-html@acme.test")
      (loc/fill (page/locator pg "input[name='password']") "Strong-Pass-1234!")
      (loc/click (page/locator pg "form.form-card button[type='submit']"))
      ;; Should redirect away from /web/register (to /web/profile per handler)
      (page/wait-for-url pg (fn [url] (not (str/includes? url "/web/register")))
                         {:timeout 10000.0})
      (is (not (str/includes? (page/url pg) "/web/register"))
          "Should redirect away from /web/register after successful registration")
      ;; Session cookie should be set (auto-login after registration)
      (let [cookie (find-cookie pg "session-token")]
        (is (some? cookie)
            "session-token cookie should be set after registration")))))

(deftest ^:integration ^:e2e weak-password-shows-validation-errors
  (testing "Weak password stays on /web/register with validation errors"
    (spel/with-testing-page [pg]
      (page/navigate pg (str base-url "/web/register"))
      (page/wait-for-load-state pg)
      (loc/fill (page/locator pg "input[name='name']") "Weak User")
      (loc/fill (page/locator pg "input[name='email']") "weak-html@acme.test")
      (loc/fill (page/locator pg "input[name='password']") "abc")
      (loc/click (page/locator pg "form.form-card button[type='submit']"))
      ;; Should stay on register page
      (page/wait-for-load-state pg)
      (is (str/includes? (page/url pg) "/web/register")
          "Should remain on /web/register with a weak password")
      ;; Visible against the field that caused it: the password error used to
      ;; render in `.validation-errors`, the form-level panel, while the name
      ;; and email errors beside it were inline `.field-errors` (BOU-393).
      (let [errors (page/locator pg ".field-errors")]
        (is (loc/is-visible? errors)
            "Validation errors should be shown for weak password")))))
