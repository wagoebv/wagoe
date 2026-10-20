(ns wagoe.e2e.html.web-profile-test
  "The profile renders in the admin shell, is reached from the user menu, and
   two-factor authentication can be turned on and off there."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]
            [wagoe.e2e.helpers.totp :as totp]))

(use-fixtures :each fx/with-fresh-seed)

(defn- await-selector!
  "spel answers a failed wait with an anomaly map rather than throwing."
  [pg selector]
  (is (not (map? (page/wait-for-selector pg selector {:timeout 10000.0}))) selector))

(defn- topbar-title [pg]
  (loc/text-content (page/locator pg "h1.admin-topbar-title")))

(deftest ^:integration ^:e2e mfa-on-and-off-from-the-profile
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)

    (testing "the user menu links to the profile, which is in the shell"
      (loc/click (page/locator pg "summary.user-menu-button"))
      (loc/click (page/locator pg ".user-menu-panel a[href='/web/profile']"))
      (page/wait-for-url pg #".*/web/profile$" {:timeout 10000.0})
      (is (loc/is-visible? (page/locator pg "aside.admin-sidebar")))
      (is (= "Profile & Security" (topbar-title pg))))

    (testing "setup: scan, verify, get backup codes"
      (loc/click (page/locator pg "a[href='/web/profile/mfa/setup']"))
      (page/wait-for-url pg #".*/web/profile/mfa/setup$" {:timeout 10000.0})
      (is (loc/is-visible? (page/locator pg "aside.admin-sidebar")))
      (loc/click (page/locator pg "button[hx-post='/web/profile/mfa/setup']"))
      (await-selector! pg ".secret-code code")
      (let [secret (loc/text-content (page/locator pg ".secret-code code"))]
        (loc/fill (page/locator pg "input[name='verification-code']") (totp/fresh-code secret)))
      (loc/click (page/locator pg "form[hx-post='/web/profile/mfa/verify'] button[type='submit']"))
      (await-selector! pg ".backup-codes-grid")
      (is (pos? (loc/count-elements (page/locator pg ".backup-code-item")))))

    (testing "disable: confirm with the password"
      (page/navigate pg (admin/app-url "/web/profile"))
      (loc/click (page/locator pg "a[href='/web/profile/mfa/disable']"))
      (page/wait-for-url pg #".*/web/profile/mfa/disable$" {:timeout 10000.0})
      (is (= "Disable Two-Factor Authentication" (topbar-title pg)))
      (loc/fill (page/locator pg "input[name='password']") (-> fx/*seed* :admin :password))
      (loc/click (page/locator pg "form[action='/web/profile/mfa/disable'] button[type='submit']"))
      (page/wait-for-url pg #".*/web/profile$" {:timeout 10000.0})
      (is (loc/is-visible? (page/locator pg "a[href='/web/profile/mfa/setup']"))
          "MFA is off again"))))
