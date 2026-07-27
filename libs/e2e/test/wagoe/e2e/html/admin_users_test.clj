(ns wagoe.e2e.html.admin-users-test
  "E2E browser tests for the admin Users UI: list overview, search,
   detail/edit forms, field visibility, and access control."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [wagoe.e2e.fixtures :as fx]
            [wagoe.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

;; ---------------------------------------------------------------------------
;; List overview
;; ---------------------------------------------------------------------------

(deftest ^:integration ^:e2e list-shows-data-table
  (testing "Admin users list page loads and shows table.data-table with expected columns"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/users"))
      (page/wait-for-load-state pg)
      (is (loc/is-visible? (page/locator pg "table.data-table"))
          "table.data-table should be visible on users list page")
      (let [headers (admin/table-headers pg)]
        (is (some #(str/includes? % "email") headers)
            "Table should have an email column")
        (is (some #(str/includes? % "name") headers)
            "Table should have a name column")
        (is (some #(str/includes? % "role") headers)
            "Table should have a role column")))))

(deftest ^:integration ^:e2e list-hides-sensitive-fields
  (testing "Sensitive fields (password-hash, mfa-secret) are not shown in the table"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/users"))
      (page/wait-for-load-state pg)
      (let [headers (admin/table-headers pg)]
        (is (not (some #(str/includes? % "password") headers))
            "password-hash should not be a table column")
        (is (not (some #(str/includes? % "mfa-secret") headers))
            "mfa-secret should not be a table column")
        (is (not (some #(str/includes? % "backup") headers))
            "mfa-backup-codes should not be a table column")))))

(deftest ^:integration ^:e2e search-filters-by-email
  (testing "Search input filters the users table via HTMX"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/users"))
      (page/wait-for-load-state pg)
      ;; Search for the admin email — should find exactly that user
      (admin/search! pg "admin@acme")
      (let [rows (admin/table-row-count pg)]
        (is (pos? rows) "Search for 'admin@acme' should return at least 1 row"))
      ;; Search for something that doesn't exist
      (admin/search! pg "nonexistent-user-xyz")
      (is (admin/has-empty-state? pg)
          "Search for nonexistent user should show empty state"))))

(deftest ^:integration ^:e2e search-htmx-no-full-reload
  (testing "Search triggers HTMX fragment update, not a full page reload"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/users"))
      (page/wait-for-load-state pg)
      ;; Set a marker on the DOM that would be lost on full page reload
      (page/evaluate pg "document.body.setAttribute('data-e2e-marker', 'alive')")
      ;; Trigger search
      (admin/search! pg "admin")
      ;; The marker should still be present (no full reload happened)
      (let [marker (page/evaluate pg "document.body.getAttribute('data-e2e-marker')")]
        (is (= "alive" marker)
            "DOM marker should survive HTMX fragment update (no full page reload)")))))

;; ---------------------------------------------------------------------------
;; Detail & edit
;; ---------------------------------------------------------------------------

(deftest ^:integration ^:e2e detail-shows-field-groups
  (testing "User detail page shows field groups 'identity' and 'access'"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/users/" (-> fx/*seed* :admin :id))))
      (page/wait-for-load-state pg)
      (is (admin/field-group-visible? pg "identity")
          "Field group 'identity' (email, name) should be visible")
      (is (admin/field-group-visible? pg "access")
          "Field group 'access' (role, active) should be visible"))))

(deftest ^:integration ^:e2e detail-readonly-fields
  (testing "Readonly fields (id, created-at, updated-at) are not editable inputs in the form"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/users/" (-> fx/*seed* :admin :id))))
      (page/wait-for-load-state pg)
      (is (loc/is-visible? (page/locator pg "form.entity-form"))
          "Entity form should be visible")
      (is (zero? (loc/count-elements (page/locator pg "form.entity-form input[name='id']")))
          "id should not be an editable input in the form")
      (is (zero? (loc/count-elements (page/locator pg "form.entity-form input[name='created-at']")))
          "created-at should not be an editable input in the form"))))

(deftest ^:integration ^:e2e edit-role-via-dropdown
  (testing "Changing the role dropdown and submitting updates the user's role"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      ;; Navigate to the regular user's detail page (not admin, to safely change role)
      (page/navigate pg (admin/admin-url (str "/users/" (-> fx/*seed* :user :id))))
      (page/wait-for-load-state pg)
      ;; Change role from user to viewer
      (loc/select-option (page/locator pg "select[name='role']") "viewer")
      ;; Install settle listener before submitting
      (admin/install-htmx-settle-listener! pg)
      (loc/click (page/locator pg "form.entity-form button[type='submit']"))
      (admin/await-htmx-settle! pg)
      (page/wait-for-load-state pg)
      ;; Verify the role is now viewer
      (let [role-value (loc/input-value (page/locator pg "select[name='role']"))]
        (is (= "viewer" role-value)
            "Role should be updated to 'viewer' after form submission")))))

(deftest ^:integration ^:e2e edit-form-submit-preserves-values
  (testing "Editing a field and submitting preserves the new value on the re-rendered form"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/users/" (-> fx/*seed* :user :id))))
      (page/wait-for-load-state pg)
      ;; Change the name to a new value
      (loc/fill (page/locator pg "input[name='name']") "Changed Name")
      ;; Install settle listener before submitting
      (admin/install-htmx-settle-listener! pg)
      (loc/click (page/locator pg "form.entity-form button[type='submit']"))
      (admin/await-htmx-settle! pg)
      (page/wait-for-load-state pg)
      ;; After re-render, the name field should contain the new value
      (let [name-value (loc/input-value (page/locator pg "input[name='name']"))]
        (is (= "Changed Name" name-value)
            "Name should be preserved as 'Changed Name' after form submission")))))

(deftest ^:integration ^:e2e edit-success-shows-notification
  (testing "Successful edit shows a success notification"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/users/" (-> fx/*seed* :user :id))))
      (page/wait-for-load-state pg)
      ;; Make a valid change — update the name
      (loc/fill (page/locator pg "input[name='name']") "Updated Test User")
      ;; Submit — the form uses hx-target="body" hx-swap="outerHTML" which
      ;; replaces the entire body, so wait for the flash element to appear
      (loc/click (page/locator pg "form.entity-form button[type='submit']"))
      (page/wait-for-selector pg ".alert.alert-success" {:timeout 10000.0})
      (is (admin/flash-visible? pg :success)
          "Success notification should be visible after saving"))))

;; ---------------------------------------------------------------------------
;; Access control
;; ---------------------------------------------------------------------------

(deftest ^:integration ^:e2e unauthenticated-redirects-to-login
  (testing "Visiting admin without a session redirects to /web/login"
    (spel/with-testing-page [pg]
      (page/navigate pg (admin/admin-url "/users"))
      (page/wait-for-url pg #".*/web/login.*" {:timeout 10000.0})
      (is (str/includes? (page/url pg) "/web/login")
          "Unauthenticated user should be redirected to /web/login"))))

(deftest ^:integration ^:e2e regular-user-denied-admin
  (testing "Regular user cannot access admin UI"
    (spel/with-testing-page [pg]
      (admin/login-as-user! pg fx/*seed*)
      ;; Try to navigate to admin
      (page/navigate pg (admin/admin-url "/users"))
      (page/wait-for-load-state pg)
      ;; Should NOT see the admin data table — either 403 error page or redirect
      (let [has-table (pos? (loc/count-elements (page/locator pg "table.data-table")))]
        (is (not has-table)
            "Regular user should not see the admin data table")))))
