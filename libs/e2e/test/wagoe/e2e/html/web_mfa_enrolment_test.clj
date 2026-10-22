(ns wagoe.e2e.html.web-mfa-enrolment-test
  "A user the app requires a second factor of, who has none, is led from
   sign-in to setting one up: on registering, and after an admin reset it.
   The test profile's hook requires one of anyone at @mfa-required.test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]
            [wagoe.e2e.helpers.reset :as reset]
            [wagoe.e2e.helpers.totp :as totp]))

(use-fixtures :each fx/with-fresh-seed)

(def ^:private base-url (reset/default-base-url))
(def ^:private email "eve@mfa-required.test")
(def ^:private password "Str0ng-Pass-2026")

(defn- enrol!
  "On the setup page: scan, so to speak, and confirm with a code."
  [pg]
  (page/wait-for-url pg #".*/web/login/mfa-setup$" {:timeout 10000.0})
  (let [secret (loc/get-attribute (page/locator pg "input[name='secret']") "value")]
    (loc/fill (page/locator pg "input[name='verification-code']") (totp/fresh-code secret))
    (loc/click (page/locator pg "form button[type='submit']"))
    (page/wait-for-selector pg ".backup-codes-grid" {:timeout 10000.0})
    secret))

(defn- sign-in! [pg]
  (page/navigate pg (str base-url "/web/login"))
  (loc/fill (page/locator pg "input[name='email']") email)
  (loc/fill (page/locator pg "input[name='password']") password)
  (loc/click (page/locator pg "form.form-card button[type='submit']")))

(deftest ^:integration ^:e2e reset-then-sign-in-leads-to-enrolment
  (testing "a new account sets up its second factor first"
    (spel/with-testing-page [pg]
      (page/navigate pg (str base-url "/web/register"))
      (loc/fill (page/locator pg "#name") "Eve")
      (loc/fill (page/locator pg "#email") email)
      (loc/fill (page/locator pg "#password") password)
      (loc/click (page/locator pg "#register-form button[type='submit']"))
      (is (string? (enrol! pg)))))
  (testing "the admin resets it"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/users"))
      (loc/click (page/locator pg (str "table.data-table tbody tr:has-text('" email "') a.row-nav-hint")))
      (page/wait-for-url pg #".*/web/admin/users/[0-9a-f-]+(\?.*)?$" {:timeout 10000.0})
      (let [id (second (re-find #"/web/admin/users/([0-9a-f-]+)" (page/url pg)))]
        (page/navigate pg (admin/app-url (str "/web/users/" id "/sessions"))))
      (loc/click (page/locator pg "#reset-mfa"))
      (page/wait-for-selector pg "#reset-mfa-confirm[open]" {:timeout 5000.0})
      (loc/click (page/locator pg "#reset-mfa-confirm button[value='confirm']"))
      (page/wait-for-selector pg "#reset-mfa" {:state :detached :timeout 10000.0})
      (is (zero? (loc/count-elements (page/locator pg "#reset-mfa"))) "the second factor is off")))
  (testing "signing in leads to setup again, and then asks for the new code"
    (spel/with-testing-page [pg]
      (sign-in! pg)
      (let [secret (enrol! pg)]
        (loc/click (page/locator pg ".mfa-backup-codes a.button.primary"))
        (page/wait-for-url pg #".*/web/login(\?.*)?$" {:timeout 10000.0})
        (sign-in! pg)
        (page/wait-for-selector pg "input[name='mfa-code']" {:timeout 10000.0})
        (loc/fill (page/locator pg "input[name='mfa-code']") (totp/fresh-code secret))
        (loc/click (page/locator pg "form.form-card button[type='submit']"))
        (page/wait-for-url pg #".*/web/(?!login).*" {:timeout 10000.0})
        (is (some #(= "session-token" (:name %)) (spel/context-cookies (page/page-context pg))))))))
