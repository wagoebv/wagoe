(ns wagoe.e2e.html.admin-rights-test
  "Who may do what (ADR-041 §7): the roles page composes and duplicates roles,
   and a user's Rights tab shows each permission with its group and role."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

(defn- text [pg selector]
  (str (page/evaluate pg (str "() => document.querySelector(\"" selector "\")?.textContent"))))

(defn- shown? [pg selector]
  ;; wait-for-selector answers a failed wait with an anomaly map, not a throw
  (not (map? (page/wait-for-selector pg selector {:timeout 10000.0}))))

(defn- create-role! [pg role-name permissions]
  (page/navigate pg (admin/admin-url "/roles"))
  (page/wait-for-selector pg "form.role-create-form" {:timeout 10000.0})
  (loc/fill (page/locator pg "#role-name") role-name)
  (doseq [p permissions]
    (loc/check (page/locator pg (str "form.role-create-form input[value='" p "']"))))
  (loc/click (page/locator pg "form.role-create-form button[type='submit']"))
  (page/wait-for-selector pg "form.role-edit-form" {:timeout 10000.0}))

(deftest ^:integration ^:e2e roles-are-composed-and-duplicated
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (page/navigate pg (admin/admin-url "/roles"))
    (page/wait-for-load-state pg)

    (testing "code roles are listed, locked"
      (is (pos? (loc/count-elements (page/locator pg "tr.role-code .role-locked")))))

    (testing "a managed role is a checklist of the registry"
      (create-role! pg "tenants/clerk" ["admin.tenants/read" "admin.users/read"])
      (is (shown? pg "#toasts .alert.alert-success"))
      (is (= 2 (loc/count-elements (page/locator pg "form.role-edit-form input[name='permissions']:checked")))))

    (testing "a code role is duplicated into a managed one"
      (page/navigate pg (admin/admin-url "/roles/view?name=admin.tenants%2Fviewer"))
      (page/wait-for-selector pg "form.role-duplicate-form" {:timeout 10000.0})
      (is (zero? (loc/count-elements (page/locator pg "form.role-edit-form"))) "a code role is read-only")
      (loc/fill (page/locator pg "#role-new-name") "tenants/reader")
      (loc/click (page/locator pg "form.role-duplicate-form button[type='submit']"))
      (page/wait-for-selector pg "form.role-edit-form" {:timeout 10000.0})
      (is (str/includes? (text pg "h1.page-title") "tenants/reader"))
      (is (loc/is-checked? (page/locator pg "form.role-edit-form input[value='admin.tenants/read']"))))))

(deftest ^:integration ^:e2e the-rights-tab-shows-each-permission-with-its-group-and-role
  (spel/with-testing-page [pg]
    (admin/login-as-admin! pg fx/*seed*)
    (create-role! pg "tenants/clerk" ["admin.tenants/read"])

    (page/navigate pg (admin/admin-url "/groups"))
    (page/wait-for-selector pg "form.group-create-form" {:timeout 10000.0})
    (loc/fill (page/locator pg "#group-name") "Finance")
    (loc/click (page/locator pg "form.group-create-form button[type='submit']"))
    (page/wait-for-selector pg "form.group-member-form" {:timeout 10000.0})
    (let [email (-> fx/*seed* :user :email)]
      (loc/fill (page/locator pg "#user-id") email)
      (page/wait-for-selector pg (str "#user-id-listbox li[data-label*='" email "']") {:timeout 10000.0})
      (loc/press (page/locator pg "#user-id") "ArrowDown")
      (loc/press (page/locator pg "#user-id") "Enter")
      (loc/click (page/locator pg "form.group-member-form button[type='submit']"))
      (page/wait-for-selector pg "section.group-members table" {:timeout 10000.0}))
    (loc/select-option (page/locator pg "#group-role") "tenants/clerk")
    (loc/click (page/locator pg "form.group-role-form button[type='submit']"))
    (page/wait-for-selector pg "section.group-roles table" {:timeout 10000.0})

    (testing "the user's admin page has a Rights tab"
      (page/navigate pg (admin/admin-url (str "/users/" (-> fx/*seed* :user :id))))
      (page/wait-for-selector pg "nav.record-tabs" {:timeout 10000.0})
      (loc/click (page/locator pg "nav.record-tabs a:has-text('Rights')"))
      (page/wait-for-url pg #".*/web/users/.*/rights$" {:timeout 10000.0})
      (is (str/ends-with? (page/url pg) (str "/web/users/" (-> fx/*seed* :user :id) "/rights"))))

    (testing "it lists the group, and the permission with its role and group"
      (is (shown? pg "tr.rights-group"))
      (is (str/includes? (text pg "tr.rights-group") "Finance"))
      (let [row (text pg "tr.rights-permission")]
        (is (str/includes? row "admin.tenants/read"))
        (is (str/includes? row "tenants/clerk"))
        (is (str/includes? row "Finance"))))))
