(ns wagoe.admin.core.ui-test
  "Unit tests for admin UI pure functions.

   Tests cover:
   - Layout components (admin-sidebar, admin-shell, admin-layout, admin-home)
   - Entity list components (search, table, pagination)
   - Field rendering (render-field-value with different types)
   - Form widgets (render-field-widget with all widget types)
   - Entity forms (create/edit forms)
   - URL construction (build-table-url)
   - Error pages (forbidden, not-found)
   - Utility functions (format-field-label, get-field-errors)
   
   All tests are pure function tests - data in, Hiccup out.
   No browser or HTTP simulation required."
  (:require [wagoe.admin.core.ui :as ui]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str])
  (:import [java.time Instant]
           [java.util UUID]))

^{:kaocha.testable/meta {:unit true :admin true}}

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def sample-user
  "Sample user for testing"
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "test@example.com"
   :display-name "Test User"
   :role :admin
   :active true})

(def sample-entity-config
  "Sample entity configuration"
  {:label "Users"
   :description "User management"
   :icon :users
   :primary-key :id
   :list-fields [:email :name :active]
   :editable-fields [:email :name :active]
   :search-fields [:email :name]
   :hide-fields #{:password-hash}
   :readonly-fields #{:id :created-at}
   :fields {:id {:type :uuid :label "ID"}
            :email {:type :string :label "Email" :required true}
            :name {:type :string :label "Name" :required true}
            :active {:type :boolean :label "Active"}
            :created-at {:type :instant :label "Created At"}
            :password-hash {:type :string :widget :password-input}}})

(def sample-entity-configs
  "Sample entity configs map"
  {:users sample-entity-config
   :orders {:label "Orders" :icon :shopping-cart}
   :products {:label "Products" :icon :box}})

(def sample-record
  "Sample entity record"
  {:id #uuid "00000000-0000-0000-0000-000000000002"
   :email "user@example.com"
   :name "Sample User"
   :active true
   :created-at (Instant/parse "2026-01-09T12:00:00Z")
   :password-hash "hashed123"})

(def sample-permissions
  "Sample admin permissions"
  {:can-view true
   :can-create true
   :can-edit true
   :can-delete true
   :can-bulk-delete true})

;; =============================================================================
;; Utility Function Tests
;; =============================================================================

(deftest ^:unit format-field-label-test
  (testing "Format field names as human-readable labels"
    (is (= "Email" (ui/format-field-label :email)))
    (is (= "Created at" (ui/format-field-label :created-at)))
    (is (= "Password hash" (ui/format-field-label :password-hash)))
    (is (= "User id" (ui/format-field-label :user_id)))
    (is (= "First name" (ui/format-field-label :first-name)))
    (is (= "Id" (ui/format-field-label :id)))))

(deftest ^:unit get-field-errors-test
  (testing "Extract errors for specific field from validation result"
    (testing "Errors as map (field -> vector of messages)"
      (let [errors {:email ["Required field" "Invalid format"]
                    :name ["Too short"]}]
        (is (= ["Required field" "Invalid format"]
               (ui/get-field-errors errors :email)))
        (is (= ["Too short"]
               (ui/get-field-errors errors :name)))
        (is (= []
               (ui/get-field-errors errors :active)))))

    (testing "Errors as vector of maps with :field key"
      (let [errors [{:field :email :message "Required"}
                    {:field :email :message "Invalid"}
                    {:field :name :message "Too short"}]]
        (is (= [{:field :email :message "Required"}
                {:field :email :message "Invalid"}]
               (ui/get-field-errors errors :email)))
        (is (= [{:field :name :message "Too short"}]
               (ui/get-field-errors errors :name)))
        (is (= []
               (ui/get-field-errors errors :active)))))

    (testing "Empty or nil errors"
      (is (= [] (ui/get-field-errors {} :email)))
      (is (= [] (ui/get-field-errors [] :email)))
      (is (= [] (ui/get-field-errors nil :email))))))

(deftest ^:unit build-table-url-test
  (testing "Build table URL with query parameters"
    (testing "Base URL with no parameters"
      (let [url (ui/build-table-url :users {})]
        (is (= "/web/admin/users/table" url))))

    (testing "With pagination parameters"
      (let [url (ui/build-table-url :users {:page 2 :page-size 50})]
        (is (str/includes? url "/web/admin/users/table?"))
        (is (str/includes? url "page=2"))
        (is (str/includes? url "page-size=50"))))

    (testing "With sorting parameters"
      (let [url (ui/build-table-url :users {:sort "email" :dir "asc"})]
        (is (str/includes? url "/web/admin/users/table?"))
        (is (str/includes? url "sort=email"))
        (is (str/includes? url "dir=asc"))))

    (testing "With search parameter"
      (let [url (ui/build-table-url :users {:search "test"})]
        (is (str/includes? url "/web/admin/users/table?"))
        (is (str/includes? url "search=test"))))

    (testing "With multiple parameters"
      (let [url (ui/build-table-url :users {:page 1
                                            :page-size 20
                                            :sort "name"
                                            :dir "desc"
                                            :search "admin"})]
        (is (str/starts-with? url "/web/admin/users/table?"))
        (is (str/includes? url "page=1"))
        (is (str/includes? url "page-size=20"))))))

(deftest ^:unit entity-create-url-test
  (testing "Default generic admin create URL when no delegate configured"
    (is (= "/web/admin/users/new"
           (ui/entity-create-url :users {:label "Users"})))
    (is (= "/web/admin/users/new"
           (ui/entity-create-url :users {:label "Users"} "/web/admin/users?page=2"))
        "caller-url is ignored for the non-delegated path because the generic create handler does not honor return-to"))

  (testing "Delegated create URL without caller context"
    (is (= "/web/users/new"
           (ui/entity-create-url :users {:create-redirect-url "/web/users/new"}))
        "2-arity returns the plain delegate URL")
    (is (= "/web/users/new"
           (ui/entity-create-url :users {:create-redirect-url "/web/users/new"} nil))
        "nil caller-url returns the plain delegate URL"))

  (testing "Delegated create URL threads caller URL as URL-encoded return-to"
    (let [caller "/web/admin/users?page=2&sort=email&filter%5Bactive%5D=true"
          url (ui/entity-create-url :users
                                    {:create-redirect-url "/web/users/new"}
                                    caller)]
      (is (str/starts-with? url "/web/users/new?return-to="))
      (is (str/includes? url (java.net.URLEncoder/encode caller "UTF-8"))
          "caller URL must appear URL-encoded in the return-to parameter")
      (is (not (str/includes? url "return-to=/web/admin/users?page=2&"))
          "caller URL must not appear as a raw unencoded value")))

  (testing "Delegated URL that already contains a query string uses & separator"
    (let [url (ui/entity-create-url :users
                                    {:create-redirect-url "/web/users/new?source=admin"}
                                    "/web/admin/users?page=2")]
      (is (str/includes? url "?source=admin&return-to="))
      (is (not (str/includes? url "?source=admin?return-to="))
          "must not introduce a second '?' separator"))))

;; =============================================================================
;; Field Rendering Tests
;; =============================================================================

(deftest ^:unit render-field-value-test
  (testing "Render field values for display in tables/detail views"
    (testing "Nil values"
      (let [result (ui/render-field-value :email nil {:type :string})]
        (is (vector? result))
        (is (= :span.null-value (first result)))
        (is (= "—" (last result)))))

    (testing "Boolean values"
      (let [true-result (ui/render-field-value :active true {:type :boolean})
            false-result (ui/render-field-value :active false {:type :boolean})]
        (is (vector? true-result))
        (is (= :span (first true-result)))
        (is (str/includes? (str true-result) ":common/option-yes"))
        (is (vector? false-result))
        (is (str/includes? (str false-result) ":common/option-no"))))

    (testing "Instant/datetime values"
      (let [instant (Instant/parse "2026-01-09T12:00:00Z")
            result (ui/render-field-value :created-at instant {:type :instant})]
        (is (string? result))
        (is (str/includes? result "2026-01-09"))))

    (testing "a stored timestamp loses the ISO plumbing and the microseconds"
      ;; The column showed the raw database value, microseconds and all
      ;; (BOU-382). The stored shape is a string, not an Instant — list-entities
      ;; does no read-side coercion.
      (is (= "2026-08-27 06:12"
             (ui/render-field-value :created-at "2026-08-27T06:12:50.459979Z"
                                    {:type :instant}))))

    (testing "the shell's zone and pattern are honoured"
      (let [value "2026-08-27T06:12:50.459979Z"]
        (is (= "2026-08-27 08:12"
               (ui/render-field-value :created-at value {:type :instant}
                                      {:zone-id (java.time.ZoneId/of "Europe/Amsterdam")}))
            "rendered in the zone the shell supplies, not UTC")
        (is (= "2026-08-27 06:12:50"
               (ui/render-field-value :created-at value {:type :instant}
                                      {:date-time-format "yyyy-MM-dd HH:mm:ss"}))
            "and in the configured pattern")))

    (testing "the driver's timestamp shapes all render"
      ;; H2 and PostgreSQL hand back java.sql.Timestamp / OffsetDateTime rather
      ;; than a string, so formatting has to coerce before it formats.
      (let [instant (Instant/parse "2026-08-27T06:12:50Z")]
        (doseq [[label value] [["Instant" instant]
                               ["java.sql.Timestamp" (java.sql.Timestamp/from instant)]
                               ["OffsetDateTime" (.atOffset instant java.time.ZoneOffset/UTC)]]]
          (is (= "2026-08-27 06:12"
                 (ui/render-field-value :created-at value {:type :instant}))
              (str "should format a " label)))))

    (testing "a value that is not a timestamp is left alone, not swallowed"
      (is (= "not-a-timestamp"
             (ui/render-field-value :created-at "not-a-timestamp" {:type :instant}))))

    (testing "a zone-less timestamp is reformatted where it stands"
      ;; H2 via next.jdbc's read-as-local, and a SQLite TEXT column, both yield
      ;; a value with no zone. They used to fall through to the raw passthrough
      ;; — the exact bug BOU-382 fixes. Nothing is shifted: the wall clock is
      ;; all the value carries.
      (is (= "2026-08-27 06:12"
             (ui/render-field-value :created-at
                                    (java.time.LocalDateTime/parse "2026-08-27T06:12:50")
                                    {:type :instant})))
      (is (= "2026-08-27 06:12"
             (ui/render-field-value :created-at "2026-08-27 06:12:50" {:type :instant}))))

    (testing "an unparseable configured pattern falls back instead of throwing"
      ;; `ofPattern` throws on an unknown pattern letter, from a core namespace,
      ;; once per cell — one typo in config.edn would 500 the whole list page.
      (is (= "2026-08-27 06:12"
             (ui/render-field-value :created-at "2026-08-27T06:12:50Z" {:type :instant}
                                    {:date-time-format "yyyy-MM-dd bb"}))))

    (testing "a pattern the value cannot satisfy falls back to the default pattern"
      ;; `yyyy-MM-dd HH:mm z` compiles, but a zone-less LocalDateTime has no
      ;; zone to print. Formatting it threw a DateTimeException that dropped the
      ;; cell to the raw database value — the bug BOU-382 fixes, reachable
      ;; through a perfectly legal config.
      (is (= "2026-08-27 06:12"
             (ui/render-field-value :created-at
                                    (java.time.LocalDateTime/parse "2026-08-27T06:12:50")
                                    {:type :instant}
                                    {:date-time-format "yyyy-MM-dd HH:mm z"}))))

    (testing "textual patterns follow the supplied locale, not the JVM default"
      (is (= "27 augustus 2026"
             (ui/render-field-value :created-at "2026-08-27T06:12:50Z" {:type :instant}
                                    {:date-time-format "dd MMMM yyyy"
                                     :locale (java.util.Locale/forLanguageTag "nl")})))
      (is (= "27 August 2026"
             (ui/render-field-value :created-at "2026-08-27T06:12:50Z" {:type :instant}
                                    {:date-time-format "dd MMMM yyyy"
                                     :locale (java.util.Locale/forLanguageTag "en")}))))

    (testing "Date values"
      (let [result (ui/render-field-value :birth-date "2026-01-09" {:type :date})]
        (is (string? result))
        (is (= "2026-01-09" result))))

    (testing "the configured date pattern applies to date-only values"
      ;; `:date-format` was inert: every realistic date shape failed the
      ;; instant coercion and took the raw passthrough (BOU-382 follow-up).
      (doseq [[label value] [["ISO string"  "2026-01-09"]
                             ["LocalDate"   (java.time.LocalDate/parse "2026-01-09")]
                             ["java.sql.Date" (java.sql.Date/valueOf "2026-01-09")]]]
        (is (= "09/01/2026"
               (ui/render-field-value :birth-date value {:type :date}
                                      {:date-format "dd/MM/yyyy"}))
            (str "should format a " label))))

    (testing "a date is not moved across midnight by the display zone"
      ;; A date carries no zone; rendering UTC midnight in New York would show
      ;; the previous day.
      (is (= "2026-01-09"
             (ui/render-field-value :birth-date
                                    (java.sql.Timestamp/from (Instant/parse "2026-01-09T00:00:00Z"))
                                    {:type :date}
                                    {:zone-id (java.time.ZoneId/of "America/New_York")}))))

    (testing "UUID values"
      (let [uuid (UUID/randomUUID)
            result (ui/render-field-value :id uuid {:type :uuid})]
        (is (vector? result))
        (is (= :span.uuid-value (first result)))
        (is (str/includes? (str result) (str uuid)))))

    (testing "Enum values"
      (let [result (ui/render-field-value :status :active {:type :enum})]
        (is (vector? result))
        (is (= :span.enum-badge (first result)))
        (is (= "Active" (last result)))))

    (testing "JSON values"
      (let [result (ui/render-field-value :metadata {:key "value"} {:type :json})]
        (is (vector? result))
        (is (= :code (first result)))))

    (testing "String values"
      (testing "Short strings"
        (let [result (ui/render-field-value :name "John Doe" {:type :string})]
          (is (= "John Doe" result))))

      (testing "Long strings get truncated"
        (let [long-string (apply str (repeat 60 "a"))
              result (ui/render-field-value :description long-string {:type :string})]
          (is (string? result))
          (is (< (count result) (count long-string)))
          (is (str/ends-with? result "...")))))

    (testing "Other values (numbers, etc.)"
      (is (= "42" (ui/render-field-value :count 42 {:type :integer})))
      (is (= "19.99" (ui/render-field-value :price 19.99 {:type :decimal}))))))

;; =============================================================================
;; Form Widget Tests
;; =============================================================================

(deftest ^:unit render-field-widget-test
  (testing "Render form input widgets based on field configuration"
    (testing "Text input widget"
      (let [widget (ui/render-field-widget :name "John"
                                           {:widget :text-input
                                            :label "Name"
                                            :required true}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))
        ;; Check for label
        (is (some #(and (vector? %) (= :label (first %))) widget))
        ;; Check for required indicator
        (is (str/includes? (str widget) "*"))))

    (testing "Email input widget"
      (let [widget (ui/render-field-widget :email "test@example.com"
                                           {:widget :email-input
                                            :label "Email"}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))

    (testing "Password input widget"
      (let [widget (ui/render-field-widget :password nil
                                           {:widget :password-input
                                            :label "Password"
                                            :required true}
                                           nil)]
        (is (vector? widget))
        (is (str/includes? (str widget) "Password"))))

    (testing "Checkbox widget"
      (let [widget (ui/render-field-widget :active true
                                           {:widget :checkbox
                                            :label "Active"
                                            :help-text "Enable user account"}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))
        ;; Should have help text
        (is (str/includes? (str widget) "Enable user account"))))

    (testing "Select widget"
      (let [widget (ui/render-field-widget :role :admin
                                           {:widget :select
                                            :label "Role"
                                            :options [[:admin "Admin"] [:user "User"]]}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))

    (testing "Textarea widget"
      (let [widget (ui/render-field-widget :description "Long text..."
                                           {:widget :textarea
                                            :label "Description"
                                            :rows 8}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))

    (testing "Number input widget"
      (let [widget (ui/render-field-widget :age 25
                                           {:widget :number-input
                                            :label "Age"
                                            :min 0
                                            :max 120}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))

    (testing "Date input widget"
      (let [widget (ui/render-field-widget :birth-date "2000-01-01"
                                           {:widget :date-input
                                            :label "Birth Date"}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))

    (testing "Datetime input widget"
      (let [widget (ui/render-field-widget :created-at "2026-01-09T12:00"
                                           {:widget :datetime-input
                                            :label "Created At"}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))

    (testing "Hidden widget"
      (let [widget (ui/render-field-widget :id "123"
                                           {:widget :hidden}
                                           nil)]
        (is (vector? widget))
        ;; Hidden field should still be in form-field wrapper
        (is (= :div.form-field (first widget)))))

    (testing "Widget with validation errors"
      (let [widget (ui/render-field-widget :email ""
                                           {:widget :email-input
                                            :label "Email"
                                            :required true}
                                           ["Required field" "Invalid format"])]
        (is (vector? widget))
        ;; Should have has-errors class
        (is (some #(and (map? %) (contains? % :class)) (tree-seq coll? seq widget)))
        ;; Should display errors
        (is (str/includes? (str widget) "Required field"))
        (is (str/includes? (str widget) "Invalid format"))))

    (testing "Readonly field"
      (let [widget (ui/render-field-widget :id "123"
                                           {:widget :text-input
                                            :label "ID"
                                            :readonly true}
                                           nil)]
        (is (vector? widget))
        (is (= :div.form-field (first widget)))))))

;; =============================================================================
;; Layout Component Tests
;; =============================================================================

(deftest ^:unit admin-sidebar-test
  (testing "Admin sidebar with entity navigation"
    (let [entities [:users :orders :products]
          sidebar (ui/admin-sidebar entities sample-entity-configs :users)]

      (is (vector? sidebar))
      (is (= :aside.admin-sidebar (first sidebar)))

      ;; Should have sidebar header with toggle controls
      (is (str/includes? (str sidebar) "sidebar-toggle"))

      ;; Should have navigation for each entity
      (is (str/includes? (str sidebar) "Users"))
      (is (str/includes? (str sidebar) "Orders"))
      (is (str/includes? (str sidebar) "Products"))

      ;; Should have links to entity pages
      (is (str/includes? (str sidebar) "/web/admin/users"))
      (is (str/includes? (str sidebar) "/web/admin/orders"))

      ;; Current entity should have active class
      (is (str/includes? (str sidebar) "active")))))

(deftest ^:unit admin-shell-test
  (testing "Admin shell layout with sidebar and topbar"
    (let [content [:div "Main content"]
          opts {:user sample-user
                :current-entity :users
                :entities [:users :orders]
                :entity-configs sample-entity-configs
                :page-title "Users"}
          shell (ui/admin-shell content opts)]

      (is (vector? shell))
      ;; Shell now returns a wrapper div [:div.hiccup-fragment-wrapper store-init [:div.admin-shell ...]]
      ;; for Alpine.js sidebar store initialization (using div instead of fragment for Hiccup compatibility)
      (is (= :div.hiccup-fragment-wrapper (first shell)))

      ;; Should contain admin-shell div
      (is (some #(and (vector? %) (= :div.admin-shell (first %)))
                (tree-seq vector? seq shell)))

      ;; Should contain sidebar
      (is (some #(and (vector? %) (= :aside.admin-sidebar (first %)))
                (tree-seq vector? seq shell)))

      ;; Should have topbar with user info (i18n key + interpolated name)
      (is (str/includes? (str shell) ":admin/header-welcome"))
      (is (str/includes? (str shell) "Test User"))

      ;; Should have main content area
      (is (str/includes? (str shell) "Main content"))

      ;; Should have page title
      (is (str/includes? (str shell) "Users"))

      ;; Alpine.js sidebar store is initialized in external admin-ux.js
      ;; Shell should have Alpine state bindings
      (is (str/includes? (str shell) "$store.sidebar.state")))))

(deftest ^:unit admin-layout-test
  (testing "Complete admin page layout"
    (let [content [:div "Page content"]
          opts {:user sample-user
                :current-entity :users
                :entities [:users :orders]
                :entity-configs sample-entity-configs
                :flash {:success "Operation successful"}}
          layout (ui/admin-layout content opts)]

      (is (vector? layout))

      ;; Should be a complete page with HTML structure
      ;; The layout/page-layout function wraps everything
      (is (str/includes? (str layout) "Page content")))))

(deftest ^:unit admin-home-test
  (testing "Admin dashboard home page"
    (let [entities [:users :orders :products]
          stats {:users {:count 42}
                 :orders {:count 156}
                 :products {:count 89}}
          home (ui/admin-home entities sample-entity-configs stats)]

      (is (vector? home))
      (is (= :div.admin-home (first home)))

      ;; Should have dashboard title (i18n key)
      (is (str/includes? (str home) ":admin/page-heading"))

      ;; Should have entity cards
      (is (str/includes? (str home) "Users"))
      (is (str/includes? (str home) "Orders"))
      (is (str/includes? (str home) "Products"))

      ;; Should show record counts via i18n key with count param
      (is (str/includes? (str home) ":admin/entity-card-count"))
      (is (str/includes? (str home) "{:count 42}"))
      (is (str/includes? (str home) "{:count 156}"))
      (is (str/includes? (str home) "{:count 89}"))

      ;; Entity cards should link to entity pages
      (is (str/includes? (str home) "/web/admin/users"))
      (is (str/includes? (str home) "/web/admin/orders")))))

;; =============================================================================
;; Entity List Component Tests
;; =============================================================================

(deftest ^:unit entity-search-form-test
  (testing "Entity search form for filtering"
    (testing "Entity with search fields"
      (let [form (ui/entity-search-form :users sample-entity-config "test" nil)]
        (is (vector? form))
        (is (= :div.entity-search-form (first form)))

        ;; Should have search input (i18n key for placeholder/aria-label)
        (is (str/includes? (str form) ":admin/filter-placeholder-search"))

        ;; Should have current search value
        (is (str/includes? (str form) "test"))

        ;; Should have HTMX attributes
        (is (str/includes? (str form) "hx-get"))
        (is (str/includes? (str form) "/web/admin/users/table"))))

    (testing "Entity without search fields"
      (let [config (dissoc sample-entity-config :search-fields)
            form (ui/entity-search-form :users config nil nil)]
        ;; Should return nil when no search fields
        (is (nil? form))))))

(deftest ^:unit entity-table-row-test
  (testing "Entity table row generation"
    (let [row (ui/entity-table-row :users sample-record sample-entity-config sample-permissions)]

      (is (vector? row))
      (is (= :tr (first row)))

      ;; Should have checkbox for bulk selection
      (is (str/includes? (str row) "checkbox"))

      ;; Should display list-fields values
      (is (str/includes? (str row) "user@example.com"))
      (is (str/includes? (str row) "Sample User"))

      ;; Should have action buttons (edit)
      (is (str/includes? (str row) "/web/admin/users/"))

      ;; Should NOT display hidden fields (password-hash)
      (is (not (str/includes? (str row) "hashed123")))))

  (testing "Row is clickable when user can edit"
    (let [row (ui/entity-table-row :users sample-record sample-entity-config
                                   {:can-edit true :can-delete false})
          row-str (str row)]
      (is (str/includes? row-str "clickable-row"))
      (is (str/includes? row-str "data-href"))
      ;; Chevron nav hint is visible because the row is clickable
      (is (str/includes? row-str "row-nav-hint"))))

  (testing "Row is NOT clickable when user cannot edit (read-only view)"
    ;; The detail/edit handler is guarded by assert-can-edit-entity!, so
    ;; rows must not advertise a detail-page link when :can-edit is false —
    ;; otherwise clicking any cell lands on a 403.
    (let [row (ui/entity-table-row :users sample-record sample-entity-config
                                   {:can-edit false :can-delete false})
          row-str (str row)]
      (is (not (str/includes? row-str "clickable-row")))
      (is (not (str/includes? row-str "data-href")))
      ;; Chevron nav hint is also hidden when the row isn't navigable
      (is (not (str/includes? row-str "row-nav-hint"))))))

(deftest ^:unit entity-edit-form-return-to-encoding-test
  (testing "return_to is URL-encoded on the edit form action"
    ;; When an edit page is reached from a contextual list URL that
    ;; already carries query parameters (filters, pagination, etc.), the
    ;; cancel-url must be URL-encoded before being appended as
    ;; ?return_to=...; otherwise any raw `&` inside splits into a second
    ;; top-level query parameter on the PUT request and the handler sees
    ;; a truncated value.
    (let [cancel-url "/web/admin/users?page=2&filter%5Bactive%5D=true&sort=email"
          form (ui/entity-form :users sample-entity-config sample-record nil
                               sample-permissions cancel-url)
          form-str (str form)]
      (is (str/includes? form-str
                         (str "return_to=" (java.net.URLEncoder/encode cancel-url "UTF-8")))
          "edit form action should contain the percent-encoded return_to value")
      (is (not (str/includes? form-str "return_to=/web/admin/users?page=2&"))
          "edit form action must not contain the raw unencoded return_to value"))))

(deftest ^:unit entity-table-test
  (testing "Complete entity table with records"
    (let [records [sample-record]
          table-query {:page 1 :page-size 20 :sort :email :dir :asc}
          total-count 1
          table (ui/entity-table :users records sample-entity-config
                                 table-query total-count sample-permissions nil)]

      (is (vector? table))
      (is (= :div#entity-table-container (first table)))

      ;; Should have table element
      (is (some #(and (vector? %) (= :table.data-table (first %)))
                (tree-seq vector? seq table)))

      ;; Should have column headers for list-fields
      (is (str/includes? (str table) "Email"))
      (is (str/includes? (str table) "Name"))
      (is (str/includes? (str table) "Active"))

      ;; Should have record data
      (is (str/includes? (str table) "user@example.com"))

      ;; Should have HTMX attributes for dynamic updates
      (is (str/includes? (str table) "hx-trigger"))
      (is (str/includes? (str table) "entityCreated")))

    (testing "Empty table state"
      (let [table (ui/entity-table :users [] sample-entity-config
                                   {:page 1 :page-size 20} 0
                                   sample-permissions nil)]
        (is (vector? table))
        ;; Should show empty state message (i18n key)
        (is (str/includes? (str table) ":admin/empty-state-no-records"))

        ;; Should show create button if can-create (i18n key)
        (is (str/includes? (str table) ":admin/button-create-first-record"))))))

(deftest ^:unit entity-list-page-test
  (testing "Complete entity list page"
    (let [records [sample-record]
          table-query {:page 1 :page-size 20}
          total-count 1
          opts {:search "test" :filters {} :flash {:success "Saved successfully"}}
          page (ui/entity-list-page :users records sample-entity-config
                                    table-query total-count sample-permissions opts)]

      (is (vector? page))
      (is (= :div.entity-list-page (first page)))

      ;; Should show flash message
      (is (str/includes? (str page) "Saved successfully"))

      ;; Should have toolbar with search and create button
      (is (str/includes? (str page) "search-input"))
      (is (str/includes? (str page) "/web/admin/users/new"))

      ;; Should show record count
      (is (str/includes? (str page) "1 Users"))

      ;; Should have the table
      (is (str/includes? (str page) "user@example.com")))))

(deftest ^:unit entity-list-page-delegated-create-preserves-context-test
  (testing "Delegated create button on list page preserves caller filter/pagination context"
    ;; When the entity delegates creation via :create-redirect-url, the hero
    ;; "New" button and the empty-state create button must carry the current
    ;; admin list URL (with filters/pagination) through as return-to, so the
    ;; delegated create flow can bring the admin back to their contextual
    ;; list view after cancel or success.
    (let [delegated-config (assoc sample-entity-config
                                  :create-redirect-url "/web/users/new")
          records [sample-record]
          table-query {:page 2 :page-size 20 :sort :email :dir :asc}
          opts {:search nil :filters {:active "true"}}
          page (ui/entity-list-page :users records delegated-config
                                    table-query 42 sample-permissions opts)
          page-str (str page)]
      (is (str/includes? page-str "/web/users/new?return-to=")
          "hero New button should point to the delegated URL with return-to")
      (is (str/includes? page-str "page%3D2")
          "encoded caller URL must preserve pagination")
      (is (str/includes? page-str "sort%3Demail")
          "encoded caller URL must preserve sort field")
      (is (not (str/includes? page-str "/web/admin/users/new"))
          "must not fall through to the generic admin create URL"))))

(deftest ^:unit entity-table-empty-state-delegated-create-preserves-context-test
  (testing "Delegated empty-state create button preserves caller context"
    (let [delegated-config (assoc sample-entity-config
                                  :create-redirect-url "/web/users/new")
          table-query {:page 3 :page-size 25}
          table (ui/entity-table :users [] delegated-config table-query 0
                                 sample-permissions {:role "admin"})
          table-str (str table)]
      (is (str/includes? table-str "/web/users/new?return-to=")
          "empty-state create button should include delegate URL with return-to")
      (is (str/includes? table-str "page%3D3")
          "encoded caller URL must preserve pagination from table-query"))))

;; =============================================================================
;; Column Width Tests
;; =============================================================================

(deftest ^:unit list-column-weight-test
  (testing "Explicit :width overrides any type-based default"
    (is (= 9 (ui/list-column-weight :anything {:type :boolean :width 9})))
    (is (= 2 (ui/list-column-weight :name {:type :string :width 2}))))

  (testing "Type-based defaults"
    (is (= 1 (ui/list-column-weight :active {:type :boolean})))
    (is (= 2 (ui/list-column-weight :status {:type :enum})))
    (is (= 2 (ui/list-column-weight :qty {:type :int})))
    (is (= 2 (ui/list-column-weight :price {:type :decimal})))
    (is (= 2 (ui/list-column-weight :id {:type :uuid})))
    (is (= 2 (ui/list-column-weight :meta {:type :json})))
    (is (= 2 (ui/list-column-weight :blob {:type :binary})))
    (is (= 3 (ui/list-column-weight :created-at {:type :instant})))
    (is (= 3 (ui/list-column-weight :birth-date {:type :date})))
    (is (= 6 (ui/list-column-weight :bio {:type :text}))))

  (testing "String name heuristic"
    (testing "Long-form names get the widest weight"
      (is (= 6 (ui/list-column-weight :description {:type :string})))
      (is (= 6 (ui/list-column-weight :omschrijving {:type :string})))
      (is (= 6 (ui/list-column-weight :address {:type :string})))
      (is (= 6 (ui/list-column-weight :comment {:type :string}))))

    (testing "Label-ish names get medium weight"
      (is (= 4 (ui/list-column-weight :name {:type :string})))
      (is (= 4 (ui/list-column-weight :naam {:type :string})))
      (is (= 4 (ui/list-column-weight :title {:type :string})))
      (is (= 4 (ui/list-column-weight :email {:type :string})))
      (is (= 4 (ui/list-column-weight :first-name {:type :string}))
          "kebab-cased names match because '-' is a word boundary"))

    (testing "Plain strings get the default weight"
      (is (= 3 (ui/list-column-weight :sku {:type :string})))
      (is (= 3 (ui/list-column-weight :code {:type :string}))))

    (testing "Word boundaries prevent substring false positives"
      (is (= 3 (ui/list-column-weight :username {:type :string}))
          "'name' inside 'username' must not match the medium pattern"))

    (testing "Unknown/nil type falls through to the string heuristic"
      (is (= 6 (ui/list-column-weight :description {})))
      (is (= 3 (ui/list-column-weight :whatever nil))))))

(defn- col-width
  "Parse the numeric percentage out of a [:col {:style \"width:N%\"}] element."
  [[_ {:keys [style]}]]
  (-> style (str/replace #"[^0-9.]" "") Double/parseDouble))

(deftest ^:unit list-column-styles-test
  (let [cfg {:fields {:name        {:type :string}
                      :active      {:type :boolean}
                      :description {:type :text}
                      :created-at  {:type :instant}}}
        fields [:name :active :description :created-at]
        cols (ui/list-column-styles fields cfg)]

    (testing "Returns a seq (so Hiccup splices) of [:col ...] elements"
      (is (seq? cols))
      (is (not (vector? cols)))
      (is (= 4 (count cols)))
      (is (every? #(= :col (first %)) cols)))

    (testing "Widths are proportional to weights (4/1/6/3 of 14)"
      (let [[n a d c] (map col-width cols)]   ; name active description created-at
        (is (< a c n d) "boolean narrowest, text widest")
        (is (= 7.14 a) "active = 1/14 ≈ 7.14%")))

    (testing "Widths sum to 100% (last column absorbs rounding remainder)"
      (is (< (Math/abs (- 100.0 (reduce + 0.0 (map col-width cols)))) 0.001)))

    (testing "Explicit :width is honoured"
      (let [override-cols (ui/list-column-styles
                           [:name :active]
                           {:fields {:name {:type :string :width 1}
                                     :active {:type :boolean :width 1}}})]
        (is (= [50.0 50.0] (map col-width override-cols))))))

  (testing "Empty field list yields no columns"
    (is (empty? (ui/list-column-styles [] {:fields {}}))))

  (testing "Whole-number percentages render without a trailing .0"
    (let [cols (ui/list-column-styles
                [:a :b]
                {:fields {:a {:type :boolean} :b {:type :boolean}}})]
      (is (every? #(str/includes? (str %) "width:50%") cols)
          "50% must not be rendered as 50.0%"))))

;; =============================================================================
;; Entity Form Tests
;; =============================================================================

(deftest ^:unit entity-form-test
  (testing "Entity create/edit form generation"
    (testing "Create form (new record)"
      (let [form (ui/entity-form :users sample-entity-config nil nil sample-permissions)]
        (is (vector? form))
        (is (= :form.entity-form (first form)))

        ;; Should have HTMX post for create
        (is (str/includes? (str form) "hx-post"))
        (is (str/includes? (str form) "/web/admin/users"))

        ;; Should have editable fields
        (is (str/includes? (str form) "Email"))
        (is (str/includes? (str form) "Name"))

        ;; Should have submit button with create i18n key
        (is (str/includes? (str form) ":admin/button-create"))

        ;; Should have cancel link with i18n key
        (is (str/includes? (str form) ":common/button-cancel"))))

    (testing "Edit form (existing record)"
      (let [form (ui/entity-form :users sample-entity-config sample-record nil sample-permissions)]
        (is (vector? form))

        ;; Should have HTMX put for update
        (is (str/includes? (str form) "hx-put"))

        ;; Should have current values
        (is (str/includes? (str form) "user@example.com"))
        (is (str/includes? (str form) "Sample User"))

        ;; Should have submit button with update i18n key
        (is (str/includes? (str form) ":admin/button-update"))))

    (testing "Form with validation errors"
      (let [errors {:email ["Required field"] :name ["Too short"]}
            form (ui/entity-form :users sample-entity-config nil errors sample-permissions)]
        (is (vector? form))

        ;; Should display validation errors
        (is (str/includes? (str form) "Required field"))
        (is (str/includes? (str form) "Too short"))))))

(deftest ^:unit entity-detail-page-test
  (testing "Entity detail/edit page"
    (testing "Edit existing record"
      (let [page (ui/entity-detail-page :users sample-entity-config sample-record
                                        nil sample-permissions nil)]
        (is (vector? page))
        (is (= :div.entity-detail-page (first page)))

        ;; Should have breadcrumbs (i18n markers)
        (is (str/includes? (str page) ":admin/breadcrumb-admin"))
        (is (str/includes? (str page) "Users"))

        ;; Should have page title (i18n key)
        (is (str/includes? (str page) ":admin/page-edit-title"))

        ;; Should contain the form
        (is (str/includes? (str page) "user@example.com"))))

    (testing "Create new record"
      (let [page (ui/entity-detail-page :users sample-entity-config nil
                                        nil sample-permissions nil)]
        (is (vector? page))

        ;; Should have create title i18n key
        (is (str/includes? (str page) ":admin/page-create-title"))))))

(deftest ^:unit entity-new-page-test
  (testing "Entity creation page (convenience wrapper)"
    (let [page (ui/entity-new-page :users sample-entity-config nil sample-permissions nil)]
      (is (vector? page))
      (is (= :div.entity-detail-page (first page)))

      ;; Should be a create page (i18n key)
      (is (str/includes? (str page) ":admin/page-create-title")))))

;; =============================================================================
;; Dialog Component Tests
;; =============================================================================

(deftest ^:unit confirm-delete-dialog-test
  (testing "Confirmation dialog for delete operations"
    (let [dialog (ui/confirm-delete-dialog :users "123")]
      (is (vector? dialog))
      (is (= :div.modal#confirm-delete-modal (first dialog)))

      ;; Should have warning message (i18n keys)
      (is (str/includes? (str dialog) ":admin/modal-confirm-delete-title"))
      (is (str/includes? (str dialog) ":admin/modal-confirm-delete-message"))

      ;; Should have HTMX delete button
      (is (str/includes? (str dialog) "hx-delete"))
      (is (str/includes? (str dialog) "/web/admin/users/123"))

      ;; Should have cancel button (i18n key)
      (is (str/includes? (str dialog) ":common/button-cancel")))))

;; =============================================================================
;; Error Page Tests
;; =============================================================================

(deftest ^:unit admin-forbidden-page-test
  (testing "403 Forbidden error page"
    (let [page (ui/admin-forbidden-page "You must be an admin" sample-user)]
      (is (vector? page))

      ;; Should show 403 status
      (is (str/includes? (str page) "403"))
      (is (str/includes? (str page) ":admin/page-access-denied-title"))

      ;; Should show reason
      (is (str/includes? (str page) "You must be an admin"))

      ;; Should have link to dashboard (user is logged in, i18n key)
      (is (str/includes? (str page) ":admin/button-go-dashboard")))

    (testing "Forbidden page for unauthenticated user"
      (let [page (ui/admin-forbidden-page "Not authenticated" nil)]
        (is (vector? page))

        ;; Should have link to login (i18n key)
        (is (str/includes? (str page) ":admin/button-login"))))))

(deftest ^:unit admin-not-found-page-test
  (testing "404 Not Found error page for admin entities"
    (let [page (ui/admin-not-found-page :users sample-user)]
      (is (vector? page))

      ;; Should show 404 status
      (is (str/includes? (str page) "404"))
      (is (str/includes? (str page) ":admin/page-not-found-title"))

      ;; Should mention the entity
      (is (str/includes? (str page) "users"))

      ;; Should have link back to admin (i18n key)
      (is (str/includes? (str page) ":admin/button-back-to-admin")))))

;; =============================================================================
;; HTMX Integration Tests
;; =============================================================================

(deftest ^:unit htmx-attributes-test
  (testing "HTMX attributes are correctly generated"
    (testing "Table has HTMX trigger for entity events"
      (let [table (ui/entity-table :users [sample-record] sample-entity-config
                                   {:page 1 :page-size 20} 1 sample-permissions nil)]
        (is (str/includes? (str table) "hx-get"))
        (is (str/includes? (str table) "hx-trigger"))
        (is (str/includes? (str table) "entityCreated"))))

    (testing "Form has HTMX submit"
      (let [form (ui/entity-form :users sample-entity-config nil nil sample-permissions)]
        (is (str/includes? (str form) "hx-post"))
        (is (str/includes? (str form) "hx-swap"))))

    (testing "Search input has HTMX change trigger"
      (let [search (ui/entity-search-form :users sample-entity-config nil nil)]
        (when search
          (is (str/includes? (str search) "hx-get"))
          (is (str/includes? (str search) "hx-trigger")))))))

;; =============================================================================
;; Permission-Based UI Tests
;; =============================================================================

(deftest ^:unit permission-based-ui-test
  (testing "UI elements shown/hidden based on permissions"
    (testing "Create button shown when can-create"
      (let [page (ui/entity-list-page :users [] sample-entity-config
                                      {:page 1 :page-size 20} 0
                                      {:can-create true :can-edit false :can-delete false}
                                      nil)]
        (is (str/includes? (str page) "/web/admin/users/new"))))

    (testing "Create button hidden when cannot create"
      (let [page (ui/entity-list-page :users [] sample-entity-config
                                      {:page 1 :page-size 20} 0
                                      {:can-create false :can-edit false :can-delete false}
                                      nil)]
        ;; Should not have create link
        (is (not (str/includes? (str page) "/web/admin/users/new")))))

    (testing "Edit button shown when can-edit"
      (let [row (ui/entity-table-row :users sample-record sample-entity-config
                                     {:can-edit true :can-delete false})]
        (is (str/includes? (str row) "/web/admin/users/"))))

    (testing "Edit button hidden when cannot edit"
      (let [row (ui/entity-table-row :users sample-record sample-entity-config
                                     {:can-edit false :can-delete false})]
        ;; Should not have edit link (no actions)
        (is (not (str/includes? (str row) "Edit")))))))

;; =============================================================================
;; Accessibility Tests
;; =============================================================================

(deftest ^:unit accessibility-form-labels-test
  (testing "Form inputs have associated labels"
    (testing "Text input has label with for attribute"
      (let [widget (ui/render-field-widget :name "John"
                                           {:widget :text-input
                                            :label "Name"
                                            :required true}
                                           nil)
            widget-str (str widget)]
        ;; Should have label element
        (is (str/includes? widget-str ":label"))
        ;; Should have for attribute matching input id
        (is (str/includes? widget-str ":for"))
        (is (str/includes? widget-str "name"))))

    (testing "All field widgets have labels"
      (let [field-configs {:email {:widget :email-input :label "Email"}
                           :password {:widget :password-input :label "Password"}
                           :active {:widget :checkbox :label "Active"}
                           :role {:widget :select :label "Role" :options [[:admin "Admin"]]}
                           :description {:widget :textarea :label "Description"}}]
        (doseq [[field-name field-config] field-configs]
          (let [widget (ui/render-field-widget field-name nil field-config nil)
                widget-str (str widget)]
            ;; Each widget should have a label
            (is (str/includes? widget-str ":label")
                (str "Missing label for field: " field-name))))))))

(deftest ^:unit accessibility-aria-labels-test
  (testing "Interactive elements have aria-label attributes"
    (testing "Icon buttons have aria-label"
      (let [page (ui/entity-list-page :users [sample-record] sample-entity-config
                                      {:page 1 :page-size 20} 1 sample-permissions nil)
            page-str (str page)]
        ;; Search button should have aria-label (i18n key)
        (is (str/includes? page-str "aria-label"))
        (is (str/includes? page-str ":common/button-search"))))

    (testing "Sidebar buttons have aria-label"
      (let [sidebar (ui/admin-sidebar [:users :orders] sample-entity-configs :users)
            sidebar-str (str sidebar)]
        ;; Toggle and pin buttons should have aria-label (i18n keys)
        (is (str/includes? sidebar-str "aria-label"))
        (is (str/includes? sidebar-str ":admin/sidebar-toggle-button"))
        (is (str/includes? sidebar-str ":admin/sidebar-pin-button"))))

    (testing "Table row has navigation hint"
      (let [row (ui/entity-table-row :users sample-record sample-entity-config
                                     {:can-edit true :can-delete false})
            row-str (str row)]
        ;; Row should have clickable-row class and chevron navigation hint
        (is (str/includes? row-str "clickable-row"))
        (is (str/includes? row-str "row-nav-hint"))))))

(deftest ^:unit accessibility-semantic-html-test
  (testing "Pages use semantic HTML elements"
    (testing "Sidebar uses nav element"
      (let [sidebar (ui/admin-sidebar [:users] sample-entity-configs :users)]
        ;; Should have nav element
        (is (some #(and (vector? %) (= :nav.admin-sidebar-nav (first %)))
                  (tree-seq vector? seq sidebar)))))

    (testing "Tables use proper table structure"
      (let [table (ui/entity-table :users [sample-record] sample-entity-config
                                   {:page 1 :page-size 20} 1 sample-permissions nil)]
        ;; Should have table, thead, tbody
        (is (some #(and (vector? %) (= :table.data-table (first %)))
                  (tree-seq vector? seq table)))
        (is (str/includes? (str table) ":thead"))
        (is (str/includes? (str table) ":tbody"))))

    (testing "Forms use form element"
      (let [form (ui/entity-form :users sample-entity-config nil nil sample-permissions)]
        (is (= :form.entity-form (first form)))))))

(deftest ^:unit accessibility-required-fields-test
  (testing "Required fields are properly marked"
    (testing "Required indicator in label"
      (let [widget (ui/render-field-widget :email "test@example.com"
                                           {:widget :email-input
                                            :label "Email"
                                            :required true}
                                           nil)
            widget-str (str widget)]
        ;; Should have asterisk or "required" indicator
        (is (str/includes? widget-str "*"))))

    (testing "Required attribute on input"
      (let [widget (ui/render-field-widget :email "test@example.com"
                                           {:widget :email-input
                                            :label "Email"
                                            :required true}
                                           nil)
            widget-str (str widget)]
        ;; Should have required attribute
        (is (str/includes? widget-str ":required true"))))))

(deftest ^:unit accessibility-error-messages-test
  (testing "Validation errors are properly associated with fields"
    (testing "Error messages displayed near field"
      (let [widget (ui/render-field-widget :email ""
                                           {:widget :email-input
                                            :label "Email"
                                            :required true}
                                           ["Required field" "Invalid format"])
            widget-str (str widget)]
        ;; Should have error messages
        (is (str/includes? widget-str "Required field"))
        (is (str/includes? widget-str "Invalid format"))
        ;; Should have error styling class
        (is (str/includes? widget-str "has-errors"))))

    (testing "Form-level errors displayed"
      (let [page (ui/entity-detail-page :users sample-entity-config nil
                                        {:email ["Required"] :name ["Too short"]}
                                        sample-permissions nil)
            page-str (str page)]
        ;; Should display validation errors
        (is (str/includes? page-str "Required"))
        (is (str/includes? page-str "Too short"))))))

(deftest ^:unit accessibility-color-contrast-test
  (testing "Status indicators use both color and text"
    (testing "Boolean field shows text (not just color)"
      (let [active-result (ui/render-field-value :active true {:type :boolean})
            inactive-result (ui/render-field-value :active false {:type :boolean})]
        ;; Should show "Yes"/"No" text via i18n markers (not just color)
        (is (str/includes? (str active-result) ":common/option-yes"))
        (is (str/includes? (str inactive-result) ":common/option-no"))))))

;; =============================================================================
;; Field Grouping Tests
;; =============================================================================

(def sample-grouped-entity-config
  "Sample entity configuration with field groups"
  {:label "Users"
   :primary-key :id
   :editable-fields [:email :name :role :active :bio]
   :field-groups [{:id :identity
                   :label "Identity"
                   :fields [:email :name]}
                  {:id :access
                   :label "Access Control"
                   :fields [:role :active]}]
   :ui {:field-grouping {:other-label "Additional Info"}}
   :fields {:id {:type :uuid :label "ID"}
            :email {:type :string :label "Email" :required true}
            :name {:type :string :label "Name" :required true}
            :role {:type :string :label "Role"}
            :active {:type :boolean :label "Active"}
            :bio {:type :text :label "Biography"}}})

(deftest ^:unit entity-form-grouped-rendering-test
  (testing "Entity form renders field groups when :field-groups is configured"
    (let [form (ui/entity-form :users sample-grouped-entity-config nil nil sample-permissions)
          form-str (str form)]

      (testing "Renders group headings"
        (is (str/includes? form-str "Identity"))
        (is (str/includes? form-str "Access Control")))

      (testing "Renders 'Other' group for ungrouped fields"
        ;; :bio is not in any configured group, should be in "Additional Info" group
        (is (str/includes? form-str "Additional Info")))

      (testing "Contains form-field-group divs"
        (is (str/includes? form-str "form-field-group")))

      (testing "Contains all editable fields"
        (is (str/includes? form-str "email"))
        (is (str/includes? form-str "name"))
        (is (str/includes? form-str "role"))
        (is (str/includes? form-str "active"))
        (is (str/includes? form-str "bio"))))))

(deftest ^:unit entity-form-flat-rendering-test
  (testing "Entity form renders flat fields when no :field-groups configured"
    (let [form (ui/entity-form :users sample-entity-config nil nil sample-permissions)
          form-str (str form)]

      (testing "Does not render group headings"
        ;; sample-entity-config has no :field-groups
        (is (not (str/includes? form-str "form-field-group"))))

      (testing "Contains all editable fields"
        (is (str/includes? form-str "email"))
        (is (str/includes? form-str "name"))
        (is (str/includes? form-str "active"))))))

(deftest ^:unit field-grouping-other-label-test
  (testing "Other group uses configured label"
    (testing "Uses entity-level override"
      (let [config (assoc sample-grouped-entity-config
                          :ui {:field-grouping {:other-label "Extra Fields"}})
            form (ui/entity-form :users config nil nil sample-permissions)
            form-str (str form)]
        (is (str/includes? form-str "Extra Fields"))))

    (testing "Uses default 'Other' when not configured"
      (let [config (-> sample-grouped-entity-config
                       (dissoc :ui)
                       ;; Add a field that's not in any group
                       (assoc :editable-fields [:email :name :role :active :bio]))
            form (ui/entity-form :users config nil nil sample-permissions)
            form-str (str form)]
        ;; Should use default "Other" (i18n marker)
        (is (str/includes? form-str ":admin/fieldgroup-other"))))))

(deftest ^:unit field-grouping-filters-non-editable-test
  (testing "Groups only contain editable fields"
    (let [;; Config where :role is in a group but NOT in editable-fields
          config (-> sample-grouped-entity-config
                     (assoc :editable-fields [:email :name :active :bio]))
          form (ui/entity-form :users config nil nil sample-permissions)
          form-str (str form)]
      ;; Identity group should still render (has :email :name)
      (is (str/includes? form-str "Identity"))
      ;; Access Control should still render (has :active)
      (is (str/includes? form-str "Access Control"))
      ;; :role is NOT editable, so it should not appear in the form
      ;; Note: we're checking that the role INPUT doesn't appear
      ;; The word "role" might appear elsewhere (like in the :role keyword as data)
      (is (not (str/includes? form-str ":name \"role\""))))))

(deftest ^:unit field-grouping-empty-groups-excluded-test
  (testing "Groups with no editable fields are not rendered"
    (let [;; Config where Access Control group's fields are not editable
          config (-> sample-grouped-entity-config
                     (assoc :editable-fields [:email :name :bio]))
          form (ui/entity-form :users config nil nil sample-permissions)
          form-str (str form)]
      ;; Identity group should render
      (is (str/includes? form-str "Identity"))
      ;; Access Control group should NOT render (no editable fields)
      (is (not (str/includes? form-str "Access Control"))))))
