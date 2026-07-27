(ns wagoe.e2e.html.web-login-test
  "E2E browser tests for the /web/login page using spel (Playwright).

   These tests exercise the full HTML login flow: form rendering, credential
   submission, redirects, cookie management, remember-me, and error feedback."
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

(defn- submit-login!
  "Fill the login form and click submit. Does NOT wait for navigation."
  [pg email password]
  (loc/fill (page/locator pg "input[name='email']") email)
  (loc/fill (page/locator pg "input[name='password']") password)
  (loc/click (page/locator pg "form.form-card button[type='submit']")))

(defn- url-decoded
  "URL-decode a string (handles %2F etc. in redirect URLs)."
  [s]
  (java.net.URLDecoder/decode s "UTF-8"))

(defn- wait-for-navigation-away-from-login!
  "Wait until the browser leaves the bare /web/login page (the URL changes
   because the server issues a redirect). We use a predicate that returns
   true when the path is no longer exactly /web/login."
  [pg]
  (page/wait-for-url pg (fn [url]
                          (let [path (second (re-find #"https?://[^/]+(/.*)$" url))]
                            (and path
                                 (not= "/web/login" path))))
                     {:timeout 10000.0}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest ^:integration ^:e2e get-renders-login-form
  (testing "GET /web/login renders a visible login form with email, password, and remember fields"
    (spel/with-testing-page [pg]
      (page/navigate pg (str base-url "/web/login"))
      (page/wait-for-load-state pg)
      ;; The login form uses class "form-card"
      (let [form (page/locator pg "form.form-card")]
        (is (loc/is-visible? form)
            "Login form with .form-card class should be visible"))
      ;; Email input
      (let [email-input (page/locator pg "input[name='email']")]
        (is (loc/is-visible? email-input)
            "Email input should be present"))
      ;; Password input
      (let [pw-input (page/locator pg "input[name='password']")]
        (is (loc/is-visible? pw-input)
            "Password input should be present"))
      ;; Remember checkbox — use type=checkbox to disambiguate from the hidden field
      (let [remember (page/locator pg "input[type='checkbox'][name='remember']")]
        (is (loc/is-visible? remember)
            "Remember-me checkbox should be present")))))

(deftest ^:integration ^:e2e happy-user-redirects-to-dashboard
  (testing "Valid user credentials set session-token cookie and land on dashboard"
    (spel/with-testing-page [pg]
      (page/navigate pg (str base-url "/web/login"))
      (page/wait-for-load-state pg)
      (submit-login! pg
                     (-> fx/*seed* :user :email)
                     (-> fx/*seed* :user :password))
      ;; Wait for the server redirect to complete at the dashboard
      (page/wait-for-url pg #".*/web/dashboard$" {:timeout 10000.0})
      ;; The user should land on the dashboard, NOT be bounced back to login
      (is (str/ends-with? (page/url pg) "/web/dashboard")
          "User should land on /web/dashboard after login")
      ;; Session cookie must be set
      (let [cookie (find-cookie pg "session-token")]
        (is (some? cookie)
            "session-token cookie should be set after login")
        (is (true? (:httpOnly cookie))
            "session-token cookie should be HttpOnly")))))

(deftest ^:integration ^:e2e happy-admin-redirects-to-admin-users
  (testing "Admin credentials set session cookie and land on /web/admin/users"
    (spel/with-testing-page [pg]
      (page/navigate pg (str base-url "/web/login"))
      (page/wait-for-load-state pg)
      (submit-login! pg
                     (-> fx/*seed* :admin :email)
                     (-> fx/*seed* :admin :password))
      ;; Wait for the server redirect to complete at the admin users page
      (page/wait-for-url pg #".*/web/admin/users$" {:timeout 10000.0})
      ;; Admin should land on /web/admin/users, NOT be bounced back to login
      (is (str/ends-with? (page/url pg) "/web/admin/users")
          "Admin should land on /web/admin/users after login")
      (is (some? (find-cookie pg "session-token"))
          "session-token cookie should be set after admin login"))))

(deftest ^:integration ^:e2e return-to-honoured
  (testing "return-to query param is honoured after successful login"
    (spel/with-testing-page [pg]
      (page/navigate pg (str base-url "/web/login?return-to=/web/dashboard/settings"))
      (page/wait-for-load-state pg)
      ;; Verify return-to hidden field is present (type=hidden, so check count not visibility)
      (let [return-to-input (page/locator pg "input[name='return-to']")]
        (is (pos? (loc/count-elements return-to-input))
            "return-to hidden field should be in the form"))
      ;; Login with valid creds
      (submit-login! pg
                     (-> fx/*seed* :user :email)
                     (-> fx/*seed* :user :password))
      ;; Should redirect to the return-to URL
      (page/wait-for-url pg #".*\/web\/dashboard\/settings.*"
                         {:timeout 10000.0})
      (is (str/includes? (page/url pg) "/web/dashboard/settings")
          "Should redirect to the return-to URL"))))

(deftest ^:integration ^:e2e remember-me-checkbox-sets-cookie
  (testing "Checking remember-me sets the remembered-email cookie after login"
    (spel/with-testing-page [pg]
      (page/navigate pg (str base-url "/web/login"))
      (page/wait-for-load-state pg)
      (loc/fill (page/locator pg "input[name='email']")
                (-> fx/*seed* :user :email))
      (loc/fill (page/locator pg "input[name='password']")
                (-> fx/*seed* :user :password))
      ;; Check the remember checkbox (uses type=checkbox to avoid the hidden field)
      (loc/check (page/locator pg "input[type='checkbox'][name='remember']"))
      (loc/click (page/locator pg "form.form-card button[type='submit']"))
      ;; Wait for redirect away from bare login
      (wait-for-navigation-away-from-login! pg)
      ;; The handler now accepts any truthy form value for remember-me
      (let [cookie (find-cookie pg "remembered-email")]
        (is (some? cookie)
            "remembered-email cookie should be set when remember-me is checked")
        (when cookie
          (is (= (-> fx/*seed* :user :email)
                 (url-decoded (:value cookie)))
              "remembered-email cookie value should match the login email"))))))

(deftest ^:integration ^:e2e remembered-email-prefills-form-via-http
  (testing "When remembered-email cookie is set, revisiting /web/login prefills the email field"
    ;; Set the remembered-email cookie manually via JavaScript and verify
    ;; the prefill behavior.
    (spel/with-testing-page [pg]
      ;; Navigate to login to establish the domain for cookies
      (page/navigate pg (str base-url "/web/login"))
      (page/wait-for-load-state pg)
      ;; Set the remembered-email cookie manually
      (let [user-email (-> fx/*seed* :user :email)]
        (page/evaluate pg (str "document.cookie = 'remembered-email=" user-email "; path=/'"))
        ;; Reload the login page
        (page/navigate pg (str base-url "/web/login"))
        (page/wait-for-load-state pg)
        (let [email-val (loc/input-value (page/locator pg "input[name='email']"))]
          (is (= user-email email-val)
              "Email field should be prefilled from remembered-email cookie"))))))

(deftest ^:integration ^:e2e invalid-credentials-show-error-no-session
  (testing "Wrong password stays on /web/login with error feedback and no session cookie"
    (spel/with-testing-page [pg]
      (page/navigate pg (str base-url "/web/login"))
      (page/wait-for-load-state pg)
      (submit-login! pg
                     (-> fx/*seed* :admin :email)
                     "Wrong-Pass-1234!")
      ;; Should stay on login page — wait for the response to finish loading
      (page/wait-for-load-state pg)
      ;; URL should still be /web/login
      (is (str/includes? (page/url pg) "/web/login")
          "Should remain on /web/login after failed login")
      ;; Error feedback should be visible. The form-field component renders
      ;; errors as .field-errors spans. Wait for them to appear.
      (let [errors (page/locator pg ".field-errors")]
        (page/wait-for-selector pg ".field-errors" {:timeout 5000.0})
        (is (loc/is-visible? errors)
            "Field error messages should be shown for wrong credentials"))
      ;; No session-token cookie
      (let [cookie (find-cookie pg "session-token")]
        (is (nil? cookie)
            "No session-token cookie should be set on failed login")))))

;; ---------------------------------------------------------------------------
;; MFA test — TODO: implement full MFA login flow
;; ---------------------------------------------------------------------------

(comment
  ;; The MFA handlers now correctly read `(get-in request [:user :id])`,
  ;; so MFA setup and enable work via API. This test can be implemented
  ;; when the full MFA-during-login browser flow is needed.
  ;;
  ;; Expected flow:
  ;; 1. Login as user via API to get session token
  ;; 2. Enable MFA via API (setup + enable with TOTP code)
  ;; 3. Login via HTML form -> should show MFA code input page
  ;; 4. Submit wrong code -> error shown, stays on MFA page
  ;; 5. Submit correct code -> redirected to dashboard

  (deftest ^:e2e mfa-required-second-step
    (testing "MFA-enabled user sees MFA code prompt and can complete login"
      (spel/with-testing-page [pg]
        ;; ... test body to be implemented ...
        ))))
