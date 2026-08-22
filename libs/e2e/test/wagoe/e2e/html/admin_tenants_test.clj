(ns wagoe.e2e.html.admin-tenants-test
  "E2E browser tests for the admin Tenants UI: list overview, search,
   detail/edit forms, soft-delete, and access control."
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
  (testing "Admin tenants list page loads and shows table.data-table with expected columns"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      (is (loc/is-visible? (page/locator pg "table.data-table"))
          "table.data-table should be visible on tenants list page")
      (let [headers (admin/table-headers pg)]
        (is (some #(str/includes? % "slug") headers)
            "Table should have a slug column")
        (is (some #(str/includes? % "name") headers)
            "Table should have a name column")
        (is (some #(str/includes? % "status") headers)
            "Table should have a status column")))))

(deftest ^:integration ^:e2e list-hides-internal-fields
  (testing "Internal fields (schema-name, settings, deleted-at) are not shown in the table"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      (let [headers (admin/table-headers pg)]
        (is (not (some #(str/includes? % "schema") headers))
            "schema-name should not be a table column")
        (is (not (some #(str/includes? % "setting") headers))
            "settings should not be a table column")
        (is (not (some #(str/includes? % "deleted") headers))
            "deleted-at should not be a table column")))))

(deftest ^:integration ^:e2e search-filters-by-name
  (testing "Search input filters the tenants table via HTMX"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      ;; Search for the seed tenant
      (admin/search! pg "Acme")
      (let [rows (admin/table-row-count pg)]
        (is (pos? rows) "Search for 'Acme' should return at least 1 row"))
      ;; Search for something that doesn't exist
      (admin/search! pg "zzz-nonexistent-tenant")
      (is (admin/has-empty-state? pg)
          "Search for nonexistent tenant should show empty state"))))

(deftest ^:integration ^:e2e search-htmx-no-full-reload
  (testing "Search triggers HTMX fragment update, not a full page reload"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      (page/evaluate pg "document.body.setAttribute('data-e2e-marker', 'alive')")
      (admin/search! pg "Acme")
      (let [marker (page/evaluate pg "document.body.getAttribute('data-e2e-marker')")]
        (is (= "alive" marker)
            "DOM marker should survive HTMX fragment update (no full page reload)")))))

;; ---------------------------------------------------------------------------
;; Detail & edit
;; ---------------------------------------------------------------------------

(deftest ^:integration ^:e2e detail-shows-field-groups
  (testing "Tenant detail page shows field groups 'identity' and 'state'"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/tenants/" (-> fx/*seed* :tenant :id))))
      (page/wait-for-load-state pg)
      (is (admin/field-group-visible? pg "identity")
          "Field group 'identity' (slug, name) should be visible")
      (is (admin/field-group-visible? pg "state")
          "Field group 'state' (status, schema-name) should be visible"))))

(deftest ^:integration ^:e2e detail-shows-editable-fields
  (testing "Tenant detail page shows editable fields and excludes readonly fields"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/tenants/" (-> fx/*seed* :tenant :id))))
      (page/wait-for-load-state pg)
      (is (loc/is-visible? (page/locator pg "form.entity-form"))
          "Entity form should be visible")
      ;; Editable fields should be present
      (is (pos? (loc/count-elements (page/locator pg "form.entity-form [name='slug']")))
          "Slug field should be present in the form")
      (is (pos? (loc/count-elements (page/locator pg "form.entity-form [name='name']")))
          "Name field should be present in the form")
      ;; Readonly fields (id, schema-name, created-at, updated-at) should NOT be in the form
      (is (zero? (loc/count-elements (page/locator pg "form.entity-form [name='id']")))
          "Readonly field 'id' should not be in the editable form")
      (is (zero? (loc/count-elements (page/locator pg "form.entity-form [name='created-at']")))
          "Readonly field 'created-at' should not be in the editable form"))))

(deftest ^:integration ^:e2e edit-name-change
  (testing "Changing tenant name and submitting persists correctly"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/tenants/" (-> fx/*seed* :tenant :id))))
      (page/wait-for-load-state pg)
      (page/wait-for-selector pg "form.entity-form" {:timeout 10000.0})
      ;; Change the name
      (loc/fill (page/locator pg "input[name='name']") "Updated Acme")
      (admin/submit-entity-form! pg)
      ;; Verify name updated
      (let [name-value (loc/input-value (page/locator pg "input[name='name']"))]
        (is (= "Updated Acme" name-value)
            "Name should be updated to 'Updated Acme' after form submission")))))

(deftest ^:integration ^:e2e soft-delete-removes-from-list
  (testing "Soft-deleting a tenant via the detail page delete button removes it from the list"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      ;; Navigate to the tenant detail page
      (page/navigate pg (admin/admin-url (str "/tenants/" (-> fx/*seed* :tenant :id))))
      (page/wait-for-load-state pg)
      ;; Auto-accept any confirm dialog (native browser or HTMX)
      (page/on-dialog pg (fn [dialog] (.accept dialog)))
      ;; Click the Delete button on the detail page
      (loc/click (page/locator pg "button:has-text('Delete')"))
      ;; Should redirect to the list page after deletion
      (page/wait-for-url pg #".*/web/admin/tenants.*" {:timeout 10000.0})
      (page/wait-for-load-state pg)
      ;; The deleted tenant should no longer appear in the list
      (let [rows (admin/table-row-count pg)]
        (is (zero? rows)
            "Soft-deleted tenant should not appear in the list")))))
