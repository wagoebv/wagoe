(ns wagoe.user.core.ui-test
  "Unit tests for user-specific UI components.
   
   Tests cover all user UI components including table rows, forms,
   success messages, and complete page compositions based on User schema."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [wagoe.user.core.ui :as ui]))

;; =============================================================================
;; Test Data
;; =============================================================================

(def sample-user
  {:id 123
   :name "John Doe"
   :email "john@example.com"
   :role :admin
   :active true})

(def inactive-user
  {:id 456
   :name "Jane Smith"
   :email "jane@example.com"
   :role :user
   :active false})

(def sample-users [sample-user inactive-user])

(def create-user-data
  {:name "New User"
   :email "newuser@example.com"
   :role :user})

(def validation-errors
  {:name ["Name is required"]
   :email ["Email format is invalid"]
   :password ["Password must be at least 6 characters"]})

;; =============================================================================
;; User Table Component Tests
;; =============================================================================

(deftest ^:unit user-row-test
  (testing "generates correct table row for active user"
    (let [row (ui/user-row sample-user)]
      (is (vector? row))
      (is (= 5 (count row)))
      (is (= 123 (nth row 0)))
      (is (= "John Doe" (nth row 1)))
      (is (= "john@example.com" (nth row 2)))

      ;; Role badge cell
      (let [role-cell (nth row 3)]
        (is (vector? role-cell))
        (is (= :span (first role-cell)))
        (is (str/includes? (get-in role-cell [1 :class]) "user-role-badge"))
        (is (str/includes? (get-in role-cell [1 :class]) "ui-badge"))
        (is (= "Admin" (nth role-cell 2))))

      ;; Status badge cell
      (let [status-cell (nth row 4)]
        (is (vector? status-cell))
        (is (= :span (first status-cell)))
        (is (str/includes? (get-in status-cell [1 :class]) "user-status-badge"))
        (is (str/includes? (get-in status-cell [1 :class]) "ui-badge-success"))
        (is (= [:t :user/badge-active] (nth status-cell 2))))))

  (testing "generates correct table row for inactive user"
    (let [row (ui/user-row inactive-user)]
      ;; Role badge: user
      (let [role-cell (nth row 3)]
        (is (= "User" (nth role-cell 2))))

      ;; Status badge: inactive
      (let [status-cell (nth row 4)]
        (is (= [:t :user/badge-inactive] (nth status-cell 2)))
        (is (str/includes? (get-in status-cell [1 :class]) "user-status-badge"))
        (is (str/includes? (get-in status-cell [1 :class]) "ui-badge-warning")))))

  (testing "handles different role types"
    (let [viewer-user (assoc sample-user :role :viewer)
          row         (ui/user-row viewer-user)
          role-cell   (nth row 3)]
      ;; Capitalized label for viewer role
      (is (= "Viewer" (nth role-cell 2))))))

(deftest ^:unit users-table-test
  (testing "generates table with users wrapped in container"
    (let [table (ui/users-table sample-users)]
      (is (vector? table))
      ;; New structure: container div with HTMX attributes
      (is (= :div#users-table-container (first table)))
      (is (map? (second table)))
      (is (contains? (second table) :hx-get))
      (is (= "/web/users/table" (:hx-get (second table))))))

  (testing "shows empty state when no users"
    (let [table (ui/users-table [])]
      ;; New structure: container div with empty state inside
      (is (= :div#users-table-container (first table)))
      (is (some #(= [:div.empty-state [:t :user/empty-state-no-users]] %) table))))

  (testing "includes HTMX attributes for dynamic updates"
    (let [table (ui/users-table sample-users)
          attrs (second table)]
      (is (= "/web/users/table" (:hx-get attrs)))
      (is (= "#users-table-container" (:hx-target attrs)))
      (is (= "userCreated from:body, userUpdated from:body, userDeleted from:body" (:hx-trigger attrs))))))

(deftest ^:unit audit-logs-table-test
  (testing "renders non-empty audit table without throwing and keeps wrapper container"
    (let [audit-log {:action :login
                     :result :success
                     :created-at "2026-03-19T08:00:00Z"
                     :actor-email "admin@example.com"
                     :target-user-email "user@example.com"
                     :ip-address "127.0.0.1"}
          table-query {:sort :created-at
                       :dir :desc
                       :page 1
                       :page-size 50
                       :offset 0
                       :limit 50}
          result (ui/audit-logs-table [audit-log] table-query 1 {})]
      (is (vector? result))
      (is (= :div#audit-table-container (first result)))
      (let [html (str result)]
        (is (re-find #"audit-table" html))
        (is (re-find #"audit-action-badge" html))))))

(deftest ^:unit session-row-test
  (testing "posts revoke action to stable endpoint with token in hidden field"
    (let [session  {:session-token    "abc/def+ghi=="
                    :user-agent       "Mozilla/5.0 Chrome"
                    :ip-address       "127.0.0.1"
                    :created-at       (java.time.Instant/parse "2026-03-19T08:00:00Z")
                    :last-accessed-at (java.time.Instant/parse "2026-03-19T08:00:00Z")}
          current-time (java.time.Instant/parse "2026-03-19T10:00:00Z")
          zone-id (java.time.ZoneId/of "UTC")
          row      (ui/session-row session "different-current-token" "user-123" current-time zone-id)
          row-html (str row)]
      (is (re-find #"/web/sessions/revoke" row-html))
      (is (re-find #":name \"session-token\"" row-html))
      (is (re-find #"abc/def\+ghi==" row-html))
      (is (re-find #"2 hours ago" row-html)))))

(deftest ^:unit parse-user-agent-test
  (testing "browser and device are markers, not English"
    ;; This used to return the string "Chrome on Desktop", which no locale
    ;; could change. The keys it now emits were already in the catalogue,
    ;; unreferenced — added for this and never wired up.
    (is (= [:t :user/session-device {:browser [:t :user/browser-chrome]
                                     :device  [:t :user/device-desktop]}]
           (ui/parse-user-agent "Mozilla/5.0 Chrome"))))

  (testing "device is detected independently of browser"
    (is (= [:t :user/device-mobile]
           (:device (nth (ui/parse-user-agent "Mozilla/5.0 Firefox Android") 2))))
    (is (= [:t :user/browser-firefox]
           (:browser (nth (ui/parse-user-agent "Mozilla/5.0 Firefox Android") 2)))))

  (testing "an unrecognised agent still yields markers"
    (is (= [:t :user/session-device {:browser [:t :user/browser-unknown]
                                     :device  [:t :user/device-desktop]}]
           (ui/parse-user-agent "curl/8.0"))))

  (testing "no user-agent yields nil rather than a marker for nothing"
    (is (nil? (ui/parse-user-agent nil)))))

(deftest ^:unit format-relative-time-explicit-zone-test
  (testing "long-range relative formatting uses explicit zone id"
    (let [result (ui/format-relative-time* (java.time.Instant/parse "2026-03-01T08:00:00Z")
                                           (java.time.Instant/parse "2026-03-19T10:00:00Z")
                                           (java.time.ZoneId/of "UTC"))]
      (is (re-find #"2026" result))
      (is (re-find #"1" result))
      (is (not= "2026-03-01" result)))))

(deftest ^:unit dashboard-page-relative-time-test
  (testing "dashboard page renders relative last-login from explicit current time"
    (let [user (assoc sample-user
                      :last-login (java.time.Instant/parse "2026-03-19T08:00:00Z")
                      :created-at (java.time.Instant/parse "2026-03-01T08:00:00Z")
                      :login-count 7)
          page (ui/dashboard-page user
                                  {:active-sessions-count 2
                                   :mfa-enabled true}
                                  {:current-time (java.time.Instant/parse "2026-03-19T10:00:00Z")
                                   :zone-id (java.time.ZoneId/of "UTC")})
          page-html (str page)]
      (is (re-find #"2 hours ago" page-html))
      (is (re-find #"2026-03-01" page-html)))))

;; =============================================================================
;; User Form Component Tests
;; =============================================================================

(deftest ^:unit user-detail-form-test
  (testing "generates form with correct structure"
    (let [form (ui/user-detail-form sample-user)]
      (is (vector? form))
      ;; New structure: div with ID instead of class
      (is (= :div#user-detail (first form)))
      (is (= [:h2 [:t :user/form-detail-title]] (second form)))))

  (testing "includes HTMX form attributes"
    (let [form (ui/user-detail-form sample-user)
          form-element (nth form 2)]
      (is (= :form (first form-element)))
      (let [attrs (second form-element)]
        ;; New URLs: /web/users/*
        (is (= "/web/users/123" (:hx-put attrs)))
        (is (= "#user-detail" (:hx-target attrs))))))

  (testing "pre-fills form fields with user data"
    (let [form (ui/user-detail-form sample-user)
          form-str (str form)]
      (is (re-find #"John Doe" form-str))
      (is (re-find #"john@example.com" form-str))))

  (testing "includes all required form fields"
    (let [form (ui/user-detail-form sample-user)
          form-content (str form)]
      (is (re-find #"label-name" form-content))
      (is (re-find #"label-email" form-content))
      (is (re-find #"label-role" form-content))
      (is (re-find #"field-active" form-content)))))

(deftest ^:unit create-user-form-test
  (testing "generates form with correct structure"
    (let [form (ui/create-user-form)]
      (is (vector? form))
      ;; New structure: div with ID instead of class
      (is (= :div#create-user-form (first form)))
      (is (= [:h2 [:t :user/form-create-title]] (second form)))))

  (testing "accepts data and errors parameters"
    (let [form (ui/create-user-form create-user-data validation-errors)]
      (is (vector? form))
      (is (= :div#create-user-form (first form)))))

  (testing "includes HTMX form attributes"
    (let [form (ui/create-user-form)
          form-element (nth form 2)]
      (is (= :form (first form-element)))
      (let [attrs (second form-element)]
        ;; New URLs: /web/users
        (is (= "/web/users" (:hx-post attrs)))
        (is (= "#create-user-form" (:hx-target attrs))))))

  (testing "no-arg version works"
    (let [form (ui/create-user-form)]
      (is (vector? form))
      (is (= :div#create-user-form (first form)))))

  (testing "includes password field (not in detail form)"
    (let [form (ui/create-user-form)
          form-content (str form)]
      (is (re-find #"field-password" form-content)))))

;; =============================================================================
;; Success Message Component Tests  
;; =============================================================================

(deftest ^:unit user-created-success-test
  (testing "generates success page with user details"
    (let [page (ui/user-created-success sample-user)]
      (is (vector? page))
      (is (= :html (first page)))
      (let [content (str page)]
        (is (re-find #"page-created-heading" content))
        (is (re-find #"John Doe" content))
        (is (re-find #"john@example.com" content))
        (is (re-find #"badge-active" content)))))

  (testing "includes sign in link"
    (let [page (ui/user-created-success sample-user)
          content (str page)]
      (is (re-find #"/web/login" content))
      (is (re-find #"button-signin" content)))))

(deftest ^:unit user-updated-success-test
  (testing "generates success message with user details"
    (let [message (ui/user-updated-success sample-user)]
      (is (vector? message))
      (let [content (str message)]
        (is (re-find #"message-updated" content))
        (is (re-find #"John Doe" content))
        (is (re-find #"john@example.com" content)))))

  (testing "includes user-specific navigation link"
    (let [message (ui/user-updated-success sample-user)
          content (str message)]
      ;; New URLs: /web/users/123
      (is (re-find #"/web/users/123" content))
      (is (re-find #"button-view-user" content)))))

(deftest ^:unit user-deleted-success-test
  (testing "generates success message with user ID"
    (let [message (ui/user-deleted-success 123)]
      (is (vector? message))
      (let [content (str message)]
        (is (re-find #"message-deleted" content))
        (is (re-find #"123" content))
        (is (re-find #"message-deleted-details" content)))))

  (testing "includes navigation link to users list"
    (let [message (ui/user-deleted-success 123)
          content (str message)]
      ;; New URLs: /web/users
      (is (re-find #"/web/users" content))
      (is (re-find #"button-view-all-users" content)))))

;; =============================================================================
;; Validation Error Component Tests
;; =============================================================================

(deftest ^:unit user-validation-errors-test
  (testing "delegates to shared validation errors component"
    (let [errors-display (ui/user-validation-errors validation-errors)]
      (is (vector? errors-display))
      (is (some? errors-display))))

  (testing "returns nil for empty errors map"
    (let [errors-display (ui/user-validation-errors {})]
      (is (nil? errors-display)))))

;; =============================================================================
;; Page Template Component Tests
;; =============================================================================

(deftest ^:unit users-page-test
  (testing "generates complete users page"
    (let [page (ui/users-page sample-users)]
      (is (vector? page))
      (let [content (str page)]
        (is (re-find #"page-users-title" content))
        (is (re-find #"button-create" content))
        ;; New URLs: /web/users/new
        (is (re-find #"/web/users/new" content)))))

  (testing "accepts optional parameters"
    (let [opts {:flash {:success "User created"}}
          page (ui/users-page sample-users opts)]
      (is (vector? page))))

  (testing "includes users table"
    (let [page (ui/users-page sample-users)
          content (str page)]
      (is (re-find #"john@example.com" content))
      (is (re-find #"jane@example.com" content)))))

(deftest ^:unit user-detail-page-test
  (testing "generates complete user detail page"
    (let [page (ui/user-detail-page sample-user)]
      (is (vector? page))
      (let [content (str page)]
        ;; The title is a marker now, not "User: John Doe" — core emits
        ;; markers and the shell resolves them, so the name travels as a
        ;; parameter and the sentence lives in the catalogue.
        (is (re-find #":user/detail-page-title" content))
        (is (re-find #"John Doe" content))
        (is (re-find #"button-back-to-users" content)))))

  (testing "accepts optional parameters"
    (let [opts {:flash {:info "User updated"}}
          page (ui/user-detail-page sample-user opts)]
      (is (vector? page))))

  (testing "includes user detail form"
    (let [page (ui/user-detail-page sample-user)
          content (str page)]
      (is (re-find #"form-detail-title" content))
      (is (re-find #"john@example.com" content)))))

(deftest ^:unit create-user-page-test
  (testing "generates complete create user page with no params"
    (let [page (ui/create-user-page)]
      (is (vector? page))
      (let [content (str page)]
        (is (re-find #"page-create-user-title" content))
        (is (re-find #"form-create-title" content))
        (is (re-find #"button-back-to-users" content)))))

  (testing "accepts data parameter"
    (let [page (ui/create-user-page create-user-data)]
      (is (vector? page))))

  (testing "accepts data and errors parameters"
    (let [page (ui/create-user-page create-user-data validation-errors)]
      (is (vector? page))))

  (testing "accepts all optional parameters"
    (let [opts {:flash {:error "Validation failed"}}
          page (ui/create-user-page create-user-data validation-errors opts)]
      (is (vector? page))))

  (testing "includes create user form"
    (let [page (ui/create-user-page)
          content (str page)]
      (is (re-find #"form-create-title" content))
      (is (re-find #"field-password" content)))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest ^:unit ui-component-integration-test
  (testing "all components return valid Hiccup structures"
    (is (vector? (ui/user-row sample-user)))
    (is (vector? (ui/users-table sample-users)))
    (is (vector? (ui/user-detail-form sample-user)))
    (is (vector? (ui/create-user-form)))
    (is (vector? (ui/user-created-success sample-user)))
    (is (vector? (ui/user-updated-success sample-user)))
    (is (vector? (ui/user-deleted-success 123)))
    (is (vector? (ui/user-validation-errors validation-errors)))
    (is (vector? (ui/users-page sample-users)))
    (is (vector? (ui/user-detail-page sample-user)))
    (is (vector? (ui/create-user-page))))

  (testing "components handle edge cases gracefully"
    (is (vector? (ui/user-row (assoc sample-user :name nil))))
    (is (vector? (ui/users-table [])))
    (is (vector? (ui/create-user-form {} {})))
    (is (nil? (ui/user-validation-errors {})))))
