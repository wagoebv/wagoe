(ns wagoe.user.core.ui
  "User-specific UI components based on User schema.
   
   This namespace contains pure functions for generating user-related Hiccup
   structures. Components are derived from the User schema and handle
   user-specific business logic for display and forms."
  (:require [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.shared.ui.core.table :as table-ui]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.user.core.authentication :as auth-core]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Table query helpers (inlined from platform to avoid core→shell dependency)
;; ---------------------------------------------------------------------------

(defn- table-query->params
  "Convert a TableQuery map into a string-keyed param map for query strings."
  [{:keys [sort dir page page-size]}]
  {"sort"      (when sort (name sort))
   "dir"       (when dir (name dir))
   "page"      (str page)
   "page-size" (str page-size)})

(defn- encode-query-params
  "Turn a string-keyed map into a query string (nil values are omitted)."
  [m]
  (->> m
       (remove (comp nil? val))
       (map (fn [[k v]] (str k "=" v)))
       (str/join "&")))

(defn- search-filters->params
  "Convert parsed search/filter map back into a string-keyed param map."
  [filters]
  (into {}
        (for [[k v] filters]
          [(name k) (str v)])))

(defn- page-layout
  "User module page layout with daisyUI pilot styling."
  [title content & [opts]]
  (layout/pilot-page-layout
   title
   content
   opts))

;; =============================================================================
;; User Table Components
;; =============================================================================

(defn user-row
  "Generate user table row data based on User schema.
   
   Args:
     user: User entity map (from User schema)
     
   Returns:
     Vector of cell values for table row (some as Hiccup structures)"
  [user]
  (let [role (:role user)
        active? (boolean (:active user))]
    [(:id user)
     (:name user)
     (:email user)
     (ui/badge (-> role name str/capitalize)
               {:variant (case role
                           :admin :info
                           :user :neutral
                           :viewer :outline
                           :neutral)
                :class "user-role-badge"})
     (ui/badge (if active? [:t :user/badge-active] [:t :user/badge-inactive])
               {:variant (if active? :success :warning)
                :class "user-status-badge"})]))

(defn users-table-fragment
  "Generate just the users table container fragment (for HTMX refresh).
   
   Args:
     users:       Collection of User entity maps
     table-query: Normalized TableQuery (see wagoe.platform.shell.web.table)
     total-count: Total number of users
     filters:     Optional map of parsed search filters (see wagoe.platform.shell.web.table)

   Returns:
     Hiccup structure for users table container (no page layout)"
  ([users table-query total-count]
   (users-table-fragment users table-query total-count {}))
  ([users table-query total-count filters]
   (let [{:keys [sort dir page page-size]} table-query
         base-url      "/web/users/table"
         hx-target     "#users-table-container"
         table-params  (table-query->params table-query)
         filter-params (search-filters->params filters)
         qs-map        (merge table-params filter-params)
         hx-url        (str base-url "?" (encode-query-params qs-map))]
     (if (empty? users)
       [:div#users-table-container
        {:hx-get     hx-url
         :hx-trigger "userCreated from:body, userUpdated from:body, userDeleted from:body"
         :hx-target  hx-target}
        [:div.empty-state [:t :user/empty-state-no-users]]]
       [:div#users-table-container
        {:hx-get     hx-url
         :hx-trigger "userCreated from:body, userUpdated from:body, userDeleted from:body"
         :hx-target  hx-target}
        [:form#bulk-action-form {:hx-post   "/web/users/bulk"
                                 :hx-target "#users-table-container"
                                 :hx-swap   "outerHTML"
                                 :onchange  "const count = this.querySelectorAll('input[name=\"user-ids\"]:checked').length; document.getElementById('bulk-action-btn').disabled = !count;"}
          ;; Preserve current table state and filters when posting bulk actions
         (for [[k v] table-params]
           [:input {:type "hidden" :name k :value v}])
         (for [[k v] filter-params]
           [:input {:type "hidden" :name k :value v}])
          ;; Action selector for bulk operations (moved to header for better UX)
         [:input#bulk-action-input {:type "hidden" :name "action" :value "deactivate"}]
         [:div.table-wrapper
          [:table {:class "data-table" :id "users-table"}
           [:thead
            [:tr
             [:th.checkbox-header ""]
             (table-ui/sortable-th {:label        [:t :common/column-id]
                                    :field        :id
                                    :current-sort sort
                                    :current-dir  dir
                                    :base-url     base-url
                                    :push-url-base "/web/users"
                                    :page         page
                                    :page-size    page-size
                                    :hx-target    hx-target
                                    :hx-push-url? true
                                    :extra-params filters})
             (table-ui/sortable-th {:label        [:t :common/column-name]
                                    :field        :name
                                    :current-sort sort
                                    :current-dir  dir
                                    :base-url     base-url
                                    :push-url-base "/web/users"
                                    :page         page
                                    :page-size    page-size
                                    :hx-target    hx-target
                                    :hx-push-url? true
                                    :extra-params filters})
             (table-ui/sortable-th {:label        [:t :common/column-email]
                                    :field        :email
                                    :current-sort sort
                                    :current-dir  dir
                                    :base-url     base-url
                                    :push-url-base "/web/users"
                                    :page         page
                                    :page-size    page-size
                                    :hx-target    hx-target
                                    :hx-push-url? true
                                    :extra-params filters})
             [:th [:t :common/column-role]]
             [:th [:t :common/column-status]]]]
           [:tbody
            (for [user users
                  :when (nil? (:deleted-at user))
                  :let [row (user-row user)]]
              [:tr {:onclick (str "window.location.href='/web/users/" (:id user) "'")}
               [:td.checkbox-cell
                [:input {:type    "checkbox"
                         :name    "user-ids"
                         :value   (str (:id user))
                         :onclick "event.stopPropagation();"}]]
               (for [cell row]
                 [:td cell])])]]
          (table-ui/pagination {:table-query  table-query
                                :total-count total-count
                                :base-url    base-url
                                :push-url-base "/web/users"
                                :hx-target   hx-target
                                :extra-params filters})]]]))))

(defn users-table
  "Generate a table displaying users based on User schema.

   Arities:
   - ([users])                         ; basic table with default sorting, no query params in hx-get
   - ([users table-query total-count]) ; full control for paging/sorting
   - ([users table-query total-count filters]) ; with search filters

   Args (3-arity):
     users:       Collection of User entity maps
     table-query: TableQuery map (sorting/paging)
     total-count: Total number of users

   Returns:
     Hiccup structure for users table"
  ([users]
   ;; Backwards-compatible helper used mainly in unit tests.
   (let [table-query {:sort      :created-at
                      :dir       :desc
                      :page      1
                      :page-size 20
                      :offset    0
                      :limit     20}
         total-count (count users)
         fragment    (users-table-fragment users table-query total-count {})]
     (update fragment 1 assoc :hx-get "/web/users/table")))
  ([users table-query total-count]
   (users-table users table-query total-count {}))
  ([users table-query total-count filters]
   (users-table-fragment users table-query total-count filters)))

;; =============================================================================
;; Password Validation UI Components
;; =============================================================================

(defn password-requirement-item
  "Display a single password requirement with status indicator.
   
   Args:
     requirement - map with :met?, :message
     
   Returns:
     Hiccup list item with checkmark/x indicator
     
   Pure: true"
  [requirement]
  (let [icon (if (:met? requirement) "✓" "✗")
        class (if (:met? requirement) "requirement-met" "requirement-unmet")]
    [:li {:class class}
     [:span.requirement-icon icon]
     [:span.requirement-text (:message requirement)]]))

(def default-password-policy
  "Fallback for callers that do not supply one. Matches what
   `meets-password-policy?` enforces on its own defaults; the shell passes the
   configured policy instead, which can be stricter."
  {:min-length 8 :require-numbers true})

(defn password-requirements-list
  "Display list of password requirements with current status.
   
   Args:
     violations - vector of violation maps from meets-password-policy?
     policy     - password policy configuration map
     
   Returns:
     Hiccup list of requirements
     
   Pure: true"
  [violations policy]
  (let [violation-codes (set (map :code violations))
        requirements [{:code :too-short
                       :met? (not (contains? violation-codes :too-short))
                       :message [:t :user/password-requirement-min-length {:n (get policy :min-length 8)}]}
                      {:code :missing-uppercase
                       :met? (not (contains? violation-codes :missing-uppercase))
                       :message [:t :user/password-requirement-uppercase]
                       :required? (get policy :require-uppercase false)}
                      {:code :missing-lowercase
                       :met? (not (contains? violation-codes :missing-lowercase))
                       :message [:t :user/password-requirement-lowercase]
                       :required? (get policy :require-lowercase false)}
                      {:code :missing-number
                       :met? (not (contains? violation-codes :missing-number))
                       :message [:t :user/password-requirement-number]
                       :required? (get policy :require-numbers true)}
                      {:code :missing-special-char
                       :met? (not (contains? violation-codes :missing-special-char))
                       :message [:t :user/password-requirement-special-char]
                       :required? (get policy :require-special-chars false)}]
        active-requirements (filter #(not= false (:required? %)) requirements)]
    [:div.password-requirements
     [:h4 [:t :user/password-requirements-title]]
     [:ul.requirements-list
      (for [req active-requirements]
        (password-requirement-item req))]]))

(defn password-strength-indicator
  "Display password strength meter based on violation count.
   
   Args:
     violations - vector of violation maps
     
   Returns:
     Hiccup structure for strength meter
     
   Pure: true"
  [violations]
  (let [violation-count (count violations)
        strength (cond
                   (= violation-count 0) :strong
                   (<= violation-count 1) :medium
                   (<= violation-count 2) :weak
                   :else :very-weak)
        strength-label (case strength
                         :strong [:t :user/password-strength-strong]
                         :medium [:t :user/password-strength-medium]
                         :weak   [:t :user/password-strength-weak]
                         :very-weak [:t :user/password-strength-very-weak])
        strength-class (name strength)]
    [:div.password-strength-indicator
     [:div.strength-meter {:class (str "strength-" strength-class)}
      [:div.strength-bar]]
     [:span.strength-label strength-label]]))

;; =============================================================================
;; User Form Components  
;; =============================================================================

(defn user-detail-form
  "Generate a form for viewing/editing user details based on User schema.

   Args:
     user:   User entity map (from User schema)
     errors: Optional validation errors map, field keyword -> messages
     opts:   Optional map; :notice is rendered at the top of the form

   Returns:
     Hiccup structure for user detail form"
  ([user] (user-detail-form user nil nil))
  ([user errors] (user-detail-form user errors nil))
  ([user errors opts]
   (let [active? (boolean (:active user))]
     [:div#user-detail
      [:h2 [:t :user/form-detail-title]]
      ;; outerHTML: the response is itself a #user-detail, so an innerHTML swap
      ;; nests one inside the other and leaves a duplicate id behind (BOU-381).
      [:form {:hx-put    (str "/web/users/" (:id user))
              :hx-target "#user-detail"
              :hx-swap   "outerHTML"
              :class     "form-card"}
       (:notice opts)
       (ui/form-field :name [:t :common/label-name]
                      (ui/text-input :name (:name user) {:required true})
                      (:name errors))
       (ui/form-field :email [:t :common/label-email]
                      (ui/email-input :email (:email user) {:required true})
                      (:email errors))
       (ui/form-field :role [:t :common/label-role]
                      (ui/select-field :role
                                       [[:admin [:t :common/role-admin]]
                                        [:user [:t :common/role-user]]
                                        [:viewer [:t :common/role-viewer]]]
                                       (:role user))
                      (:role errors))
       (ui/form-field :active [:t :user/field-active]
                      (ui/checkbox :active active?)
                      (:active errors))
       [:div.form-actions
        (ui/submit-button [:t :user/button-update] {:loading-text [:t :user/button-update-loading]})
        ;; Show appropriate action button based on active status
        (if active?
          [:button.button.danger
           {:type "button"
            :onclick (str "if(confirm('Are you sure you want to deactivate this user?')) {"
                          "fetch('/web/users/" (:id user) "', {method: 'DELETE', headers: {'HX-Request': 'true'}});"
                          "window.location.href='/web/users';"
                          "}")}
           [:t :user/button-deactivate]]
          ;; Reactivate button for inactive users - uses the same update endpoint but sets active=true
          [:button.button.primary
           {:type "button"
            :onclick (str "const form = this.closest('form');"
                          "const activeCheckbox = form.querySelector('input[name=active]');"
                          "activeCheckbox.checked = true;"
                          "form.requestSubmit();")}
           [:t :user/button-reactivate]])]]])))

(defn create-user-form
  "Generate a form for creating new users based on CreateUserRequest schema.

   Args:
     data: Optional form data map for pre-filling
     errors: Optional validation errors map
     password-violations: Optional password violations from policy check
     policy: Optional password policy configuration
     opts: Optional map with :return-to URL for post-create redirect

   Returns:
     Hiccup structure for create user form"
  ([data errors password-violations policy opts]
   ;; A password is never echoed back, so the field is always rendered blank.
   ;; Bound once because the requirements list below has to describe the same
   ;; value the user is looking at.
   (let [rendered-password ""]
     [:div#create-user-form
      [:h2 [:t :user/form-create-title]]
      ;; outerHTML: the response is itself a #create-user-form, so an innerHTML
      ;; swap nests one inside the other and leaves a duplicate id behind
      ;; (BOU-381).
      [:form {:hx-post   "/web/users"
              :hx-target "#create-user-form"
              :hx-swap   "outerHTML"
              :class     "form-card"}
       (when-let [return-to (:return-to opts)]
         [:input {:type "hidden" :name "return-to" :value return-to}])
       (ui/form-field :name [:t :common/label-name]
                      (ui/text-input :name (:name data) {:required true})
                      (:name errors))
       (ui/form-field :email [:t :common/label-email]
                      (ui/email-input :email (:email data) {:required true})
                      (:email errors))
       ;; Password field with validation feedback
       [:div {:class "form-field"}
        [:label {:for "password"} [:t :user/field-password]]
        (ui/password-input :password rendered-password {:required true})
        ;; The list describes the field as rendered, and the field is never
        ;; pre-filled — so on a re-render nothing is met yet, whatever was
        ;; submitted. A nil violations argument used to read as an empty list
        ;; and tick every rule instead, which put a green "At least 8
        ;; characters" beside both an empty box and the error saying it was too
        ;; short (BOU-381). What was wrong with the submitted password is said
        ;; by the error under the field, not by these ticks.
        (when policy
          (password-requirements-list
           (or password-violations
               (:violations (auth-core/meets-password-policy?
                             rendered-password policy nil)))
           policy))
        ;; Show validation errors if present
        (when (seq (:password errors))
          [:div.validation-errors
           (for [err (:password errors)]
             [:p err])])]
       (ui/form-field :role [:t :common/label-role]
                      (ui/select-field :role
                                       [[:user [:t :common/role-user]]
                                        [:admin [:t :common/role-admin]]
                                        [:viewer [:t :common/role-viewer]]]
                                       (:role data))
                      (:role errors))
       (ui/form-field :send-welcome [:t :user/checkbox-send-welcome]
                      (ui/checkbox :send-welcome (get data :send-welcome true))
                      nil)
       (ui/submit-button [:t :user/button-create] {:loading-text [:t :user/button-create-loading]})]]))
  ([data errors password-violations policy]
   (create-user-form data errors password-violations policy nil))
  ([data errors]
   (create-user-form data errors nil default-password-policy nil))
  ([]
   (create-user-form {} {} nil default-password-policy nil)))

;; =============================================================================
;; User Success Messages
;; =============================================================================

(defn user-created-success
  "Generate success message for user creation based on User schema.
   
   Args:
     user: Created User entity map
     
   Returns:
     Hiccup structure showing success message"
  [user]
  (let [active? (boolean (:active user))]
    (page-layout
     [:t :user/page-created-title]
     [:div.user-created-page.auth-page
      [:div.user-created-card
       [:div.user-created-check "✓"]
       [:h1 [:t :user/page-created-heading]]
       [:p.user-created-lead
        [:t :user/page-created-welcome {:name (:name user)}]]
       [:div.user-created-summary
        [:p [:t :user/page-created-details-intro]]
        [:div.user-created-details
         [:div.user-created-row
          [:span.user-created-label [:t :user/created-label-email]]
          [:span (:email user)]]
         [:div.user-created-row
          [:span.user-created-label [:t :user/created-label-name]]
          [:span (:name user)]]
         [:div.user-created-row
          [:span.user-created-label [:t :user/created-label-status]]
          [:span.user-created-status (if active? [:t :user/badge-active] [:t :user/badge-inactive])]]]]
       [:div.user-created-actions
        [:p [:t :user/page-created-signin-prompt]]
        [:a.button.primary {:href "/web/login"} [:t :user/button-signin]]]]]
     {:skip-header false})))

(defn user-updated-success
  "Generate success message for user update based on User schema.
   
   Args:
     user: Updated User entity map
     
   Returns:
     Hiccup structure showing success message"
  [user]
  (ui/success-message
   [:div
    [:h3 [:t :user/message-updated]]
    [:p [:t :user/message-updated-details {:name (:name user) :email (:email user)}]]
    [:div.user-details
     [:p [:t :user/updated-role {:role (str/upper-case (name (:role user)))}]]]
    [:a.button {:href (str "/web/users/" (:id user))} [:t :user/button-view-user]]]))
(defn user-deleted-success
  "Generate success message for user deletion.
   
   Args:
     user-id: ID of deleted user
     
   Returns:
     Hiccup structure showing success message"
  [user-id]
  (ui/success-message
   [:div
    [:h3 [:t :user/message-deleted]]
    [:p [:t :user/message-deleted-details {:id user-id}]]
    [:a.button {:href "/web/users"} [:t :user/button-view-all-users]]]))

;; =============================================================================
;; User-specific Validation Display
;; =============================================================================

(defn user-validation-errors
  "Generate validation error display for user forms based on User schema.
   
   Args:
     errors: Map of field -> error messages from user validation
     
   Returns:
     Hiccup structure for validation errors"
  [errors]
  (ui/validation-errors errors))

;; =============================================================================
;; User Page Templates
;; =============================================================================

(defn users-page
  "Complete users listing page.

   Arities:
   - ([users])                  ; infer total-count and no extra options
   - ([users opts])             ; infer total-count, with page options
   - ([users total-count opts]) ; full control

   Args (3-arity):
     users:       Collection of User entities
     total-count: Total number of users
     opts:        Optional page options (user context, flash messages, etc.)
                  May contain :table-query for sorting/paging and :filters.

   Returns:
     Complete HTML page for users listing"
  ([users]
   (users-page users (count users) {}))
  ([users opts]
   (users-page users (count users) opts))
  ([users total-count opts]
   (let [opts        (or opts {})
         table-query (or (:table-query opts)
                         {:sort      :created-at
                          :dir       :desc
                          :page      1
                          :page-size 20
                          :offset    0
                          :limit     20})
         filters     (or (:filters opts) {})]
     (page-layout
      [:t :user/page-users-title]
      [:div.users-page
       [:div.page-header
        [:h1 [:t :user/page-users-title]]
        [:div.page-actions
            ;; Bulk action selector and button
         [:div.bulk-action-controls
          [:select#bulk-action-select
           {:onchange "document.getElementById('bulk-action-input').value = this.value;"}
           [:option {:value "deactivate"} [:t :user/bulk-action-deactivate]]
           [:option {:value "activate"} [:t :user/bulk-action-activate]]
           [:option {:value "delete"} [:t :user/bulk-action-delete]]
           [:option {:value "role-admin"} [:t :user/bulk-action-role-admin]]
           [:option {:value "role-user"} [:t :user/bulk-action-role-user]]]
          [:button#bulk-action-btn.icon-button.danger
           {:disabled true
            :title [:t :user/bulk-action-apply-title]
            :onclick "const action = document.getElementById('bulk-action-select').value; const count = document.querySelectorAll('input[name=\"user-ids\"]:checked').length; if(confirm(`Are you sure you want to ${action} ${count} selected user(s)?`)) { document.getElementById('bulk-action-form').submit(); }"}
           [:t :user/button-apply-bulk]]]
           ;; Inline compact search/filter with overlay
         [:details#search-filter-details.search-filter-inline
          [:summary.search-toggle
           (icons/icon :search {:size 16})
           [:span [:t :user/button-filters-label]]]
          [:div.search-filter-overlay
           {:onclick "if(event.target === this) document.getElementById('search-filter-details').open = false;"}
           [:form.search-filter-form
            {:method "get"
             :action "/web/users"
             :onsubmit "setTimeout(() => document.getElementById('search-filter-details').open = false, 100);"}
            [:div.filter-header
             [:h3 [:t :user/filters-search-title]]
             [:button.close-button
              {:type "button"
               :onclick "document.getElementById('search-filter-details').open = false;"
               :aria-label [:t :common/button-close]}
              "×"]]
            [:input {:type        "search"
                     :name        "q"
                     :placeholder [:t :user/filter-placeholder-email]
                     :value       (get filters :q "")}]
            [:select {:name "role"}
             [:option {:value "" :selected (nil? (:role filters))} [:t :common/filter-all-roles]]
             [:option {:value "user" :selected (= "user" (:role filters))} [:t :common/role-user]]
             [:option {:value "admin" :selected (= "admin" (:role filters))} [:t :common/role-admin]]
             [:option {:value "viewer" :selected (= "viewer" (:role filters))} [:t :common/role-viewer]]]
            [:div.filter-actions
             [:button.button.small {:type "submit"} [:t :common/button-apply]]
             [:a.button.small.secondary {:href "/web/users"} [:t :common/button-clear]]]]]]
         [:a.button.primary {:href "/web/users/new"} [:t :user/button-create]]]]
       (users-table users table-query total-count filters)]
      opts))))

(defn user-detail-page
  "Complete user detail page.
   
   Args:
     user: User entity
     opts: Optional page options (user context, flash messages, etc.)
     
   Returns:
     Complete HTML page for user details"
  [user & [opts]]
  (let [current-user (:user opts)
        is-admin? (= :admin (:role current-user))]
    (page-layout
     [:t :user/detail-page-title {:name (:name user)}]
     [:div.user-detail-page
      [:div.page-header
       [:h1 (:name user)]
       [:div.page-actions
         ;; Admin-only hard delete button
        (when (and is-admin? (not (:active user)))
          [:form.hard-delete-form {:method "post"
                                   :action (str "/web/users/" (:id user) "/hard-delete")
                                   :onsubmit "return confirm('⚠️  PERMANENT DELETE\\n\\nThis will IRREVERSIBLY delete this user and ALL related data.\\n\\nThis action cannot be undone.\\n\\nAre you absolutely sure?');"}
           (csrf/hidden-field)
           [:button.button.danger {:type "submit"}
            (icons/icon :trash {:size 16})
            [:span [:t :user/button-delete-permanently]]]])
        [:a.button.secondary {:href (str "/web/users/" (:id user) "/sessions")} [:t :user/button-view-sessions]]
        [:a.button {:href "/web/users"} [:t :user/button-back-to-users]]]]
      (user-detail-form user)]
     opts)))

(defn create-user-page
  "Complete create user page.

   Args:
     data: Optional form data for pre-filling
     errors: Optional validation errors
     opts: Optional page options. Supports :return-to for the 'Back to users'
           button target and to thread through the form as a hidden field so
           the HTMX POST handler can redirect to the same URL on success, and
           :password-policy for the rules the form lists — the shell reads that
           from config, so the page and the failed POST advertise the same set.

   Returns:
     Complete HTML page for creating users"
  [& [data errors opts]]
  (let [return-to (or (:return-to opts) "/web/admin/users")
        policy    (or (:password-policy opts) default-password-policy)]
    (page-layout
     [:t :user/page-create-user-title]
     [:div.create-user-page
      [:div.page-header
       [:h1 [:t :user/form-create-title]]
       [:div.page-actions
        [:a.button {:href return-to} [:t :user/button-back-to-users]]]]
      (create-user-form data errors nil policy {:return-to return-to})]
     opts)))

;; =============================================================================
;; Web Root Landing Page
;; =============================================================================

(defn web-root-page
  "Landing page shown at /web — logo, welcome message, and login link.

   Args:
     opts: Optional page options (flash messages, etc.)

   Returns:
     Complete HTML page structure"
  [& [opts]]
  (page-layout
   [:t :user/page-welcome-title]
   [:div.web-root-page
    [:div.web-root-content
     [:div.web-root-logo
      (icons/brand-logo {:size 180})]
     [:h1 [:t :user/page-welcome-heading]]
     [:p [:t :user/page-welcome-description]]
     [:div.web-root-actions
      [:a.button.primary {:href "/web/login"}
       (icons/icon :log-in {:size 18})
       [:span [:t :user/link-signin]]]]]]
   (assoc opts :skip-header true)))

;; =============================================================================
;; Login Form & Page
;; =============================================================================

(defn login-form
  "Login form for email/password sign-in.

   Args:
      data   - map with optional :email / :remember / :return-to
      errors - map of field keyword -> vector of error messages"
  [data errors]
  (let [_err (fn [k]
               (when-let [es (seq (get errors k))]
                 [:div.validation-errors
                  (for [e es]
                    [:p e])]))]
    [:div#login-form {:class "auth-card ui-card"}
     [:div.card-body
      [:h2.auth-title
       (icons/icon :log-in {:size 24})
       [:span [:t :user/link-signin]]]
      [:form {:method "post"
              :action "/web/login"
              :class  "form-card ui-form-shell"}
       (csrf/hidden-field)
       ;; Hidden field to preserve return-to URL
       (when (:return-to data)
         [:input {:type "hidden" :name "return-to" :value (:return-to data)}])
       (ui/form-field :email [:t :common/label-email]
                      (ui/email-input :email (:email data) {:required true})
                      (:email errors))
       (ui/form-field :password [:t :user/field-password]
                      (ui/password-input :password "" {:required true})
                      (:password errors))
       (ui/form-field :remember [:t :user/field-remember]
                      (ui/checkbox :remember (boolean (:remember data)))
                      nil)
       (ui/submit-button [:t :user/link-signin] {:loading-text [:t :user/button-signin-loading]})]
      [:div.auth-footer
       [:p [:t :user/text-no-account]]
       [:a.button.secondary {:href "/web/register"} [:t :user/button-create-account]]]]]))

(defn login-page
  "Complete login page.

   Args:
     data   - form data map (may be empty)
     errors - validation errors (may be nil)
     opts   - page options (user context, flash messages, return-to URL, etc.)"
  [& [data errors opts]]
  (let [data-with-return (if (:return-to opts)
                           (assoc data :return-to (:return-to opts))
                           data)]
    (page-layout
     [:t :user/link-signin]
     [:div.login-page.auth-page
      [:div.page-header.auth-header
       (when-let [logo-url (:logo-url opts)]
         [:img.auth-logo {:src logo-url :alt "" :style "height:48px; width:auto; display:block; margin:0 auto 12px;"}])
       [:h1 [:t :wagoe/app-name]]
       [:p [:t :user/login-subtitle]]]
      (login-form data-with-return errors)]
     opts)))

;; =============================================================================
;; MFA Login Form & Page
;; =============================================================================

(defn mfa-login-form
  "Form for entering MFA code during login.

   Args:
      data   - map with :email, :password, :remember, :return-to
      errors - map of field keyword -> vector of error messages"
  [data errors]
  (let [_err (fn [k]
               (when-let [es (seq (get errors k))]
                 [:div.validation-errors
                  (for [e es]
                    [:p e])]))]
    [:div#mfa-login-form {:class "auth-card ui-card"}
     [:div.card-body
      [:h2.auth-title
       (icons/icon :shield {:size 24})
       [:span [:t :user/mfa-title]]]
      [:p.auth-subtitle
       [:t :user/mfa-prompt]]
      [:form {:method "post"
              :action "/web/login"
              :class  "form-card ui-form-shell"}
       (csrf/hidden-field)
      ;; Hidden fields to preserve login data
       (when (:return-to data)
         [:input {:type "hidden" :name "return-to" :value (:return-to data)}])
       [:input {:type "hidden" :name "email" :value (:email data)}]
       [:input {:type "hidden" :name "password" :value (:password data)}]
       (when (:remember data)
         [:input {:type "hidden" :name "remember" :value "on"}])

      ;; MFA code input
       (ui/form-field :mfa-code [:t :user/mfa-code-label]
                      [:input {:type "text"
                               :id "mfa-code"
                               :name "mfa-code"
                               :placeholder "000000"
                               :autocomplete "one-time-code"
                               :inputmode "numeric"
                               :pattern "[0-9]{6}"
                               :maxlength "6"
                               :required true
                               :autofocus true
                               :class "mfa-code-input"}]
                      (:mfa-code errors))

       [:div.mfa-login-actions
        (ui/submit-button [:t :user/button-verify] {:loading-text [:t :user/button-verify-loading]})
        [:a.button.secondary {:href "/web/login"} [:t :common/button-cancel]]]]
      [:div.auth-footer
       [:p [:t :user/mfa-lost-access]]
       [:p [:t :user/mfa-backup-codes-prompt]]]]]))

(defn mfa-login-page
  "Complete MFA login page (shown after successful password verification).

   Args:
     data   - form data map (must contain :email and :password)
     errors - validation errors (may be nil)
     opts   - page options (user context, flash messages, return-to URL, etc.)"
  [& [data errors opts]]
  (let [data-with-return (if (:return-to opts)
                           (assoc data :return-to (:return-to opts))
                           data)]
    (page-layout
     [:t :user/mfa-title]
     [:div.mfa-login-page.auth-page
      [:div.page-header.auth-header
       (when-let [logo-url (:logo-url opts)]
         [:img.auth-logo {:src logo-url :alt "" :style "height:48px; width:auto; display:block; margin:0 auto 12px;"}])
       [:h1 [:t :wagoe/app-name]]
       [:p [:t :user/mfa-subtitle]]]
      (mfa-login-form data-with-return errors)]
     opts)))

;; =============================================================================
;; Self-Service Registration Form & Page
;; =============================================================================

(defn register-form
  "Form for self-service account creation.

   Args:
     data   - map with optional :name / :email / :return-to
     errors - map of field keyword -> vector of error messages
     password-violations - optional password violations from policy check
     policy - optional password policy configuration"
  ([data errors password-violations policy]
   [:div#register-form {:class "auth-card ui-card"}
    [:div.card-body
     [:h2.auth-title
      (icons/icon :user-plus {:size 24})
      [:span [:t :user/button-create-account]]]
     [:form {:method "post"
             :action "/web/register"
             :class  "form-card ui-form-shell"}
      (csrf/hidden-field)
      (when (:return-to data)
        [:input {:type "hidden" :name "return-to" :value (:return-to data)}])
      (ui/form-field :name [:t :common/label-name]
                     (ui/text-input :name (:name data) {:required true})
                     (:name errors))
      (ui/form-field :email [:t :common/label-email]
                     (ui/email-input :email (:email data) {:required true})
                     (:email errors))
       ;; Password field with validation feedback
      [:div {:class "form-field"}
       [:label {:for "password"} [:t :user/field-password]]
       (ui/password-input :password "" {:required true})
        ;; The field is never pre-filled, so the list describes an empty
        ;; password unless the caller says otherwise — a nil argument used to
        ;; read as an empty list and tick every rule instead (BOU-381).
       (when policy
         (password-requirements-list
          (or password-violations
              (:violations (auth-core/meets-password-policy? "" policy nil)))
          policy))
        ;; Show validation errors if present
       (when (seq (:password errors))
         [:div.validation-errors
          (for [err (:password errors)]
            [:p err])])]
      (ui/submit-button [:t :user/button-create-account] {:loading-text [:t :user/button-create-loading]})]]])
  ([data errors]
   (register-form data errors nil default-password-policy)))

(defn register-page
  "Complete self-service registration page.

   Args:
     data   - form data map (may be empty)
     errors - validation errors (may be nil)
     opts   - page options (user context, flash messages, etc.)"
  [& [data errors opts]]
  (let [data-with-return (if (:return-to opts)
                           (assoc data :return-to (:return-to opts))
                           data)]
    (page-layout
     [:t :user/page-create-account-title]
     [:div.register-page.auth-page
      [:div.page-header.auth-header
       [:h1 [:t :user/button-create-account]]
       [:div.page-actions
        [:a.button {:href "/web/login"}
         (icons/icon :arrow-left {:size 16})
         [:span [:t :user/button-back-to-login]]]]]
      (register-form data-with-return errors)]
     opts)))

;; =============================================================================
;; Session Management UI
;; =============================================================================

(defn format-relative-time*
  "Format a timestamp as relative time (e.g., '2 hours ago', 'just now').
   
   Args:
     timestamp - java.time.Instant or similar
     now - java.time.Instant supplied by the shell
     
   Returns:
     String describing time relative to the explicit reference time
     
   Pure: true"
  [timestamp now zone-id]
  (when timestamp
    (let [duration (java.time.Duration/between timestamp now)
          seconds (.getSeconds duration)
          minutes (quot seconds 60)
          hours (quot minutes 60)
          days (quot hours 24)]
      (cond
        (< seconds 60) "just now"
        (< minutes 60) (str minutes " minute" (when (> minutes 1) "s") " ago")
        (< hours 24) (str hours " hour" (when (> hours 1) "s") " ago")
        (< days 7) (str days " day" (when (> days 1) "s") " ago")
        :else (.format (java.time.format.DateTimeFormatter/ofPattern "MMM d, yyyy")
                       (.atZone timestamp zone-id))))))

(defn parse-user-agent
  "Extract browser and device information from user-agent string.

   Args:
     user-agent - user-agent string

   Returns:
     A translation marker rendering as 'Chrome on Desktop' or 'Safari on
     Mobile', or nil for no user-agent.

     A marker rather than a string: word order differs between languages, so
     the browser and device names are passed as parameters and the sentence
     lives in the catalogue. The parameters are themselves markers — the
     renderer resolves those first, so each name is translated before the
     sentence is assembled.

     The :user/browser-* and :user/device-* keys already existed in the
     catalogue, unreferenced. They were added for this and never wired up.

   Pure: true"
  [user-agent]
  (when user-agent
    (let [ua (str user-agent)
          browser (cond
                    (re-find #"Chrome" ua)  :user/browser-chrome
                    (re-find #"Safari" ua)  :user/browser-safari
                    (re-find #"Firefox" ua) :user/browser-firefox
                    (re-find #"Edge" ua)    :user/browser-edge
                    :else                   :user/browser-unknown)
          device (cond
                   (re-find #"Mobile|Android|iPhone" ua) :user/device-mobile
                   (re-find #"Tablet|iPad" ua)           :user/device-tablet
                   :else                                 :user/device-desktop)]
      [:t :user/session-device {:browser [:t browser] :device [:t device]}])))

(defn session-row
  "Single row in the sessions table.
   
   Args:
     session       - session map with :token, :ip-address, :user-agent, :created-at, :last-active
     current-token - the current session token (to mark current session)
     user-id       - user ID for action URLs
     
   Returns:
     Hiccup table row
     
   Pure: true"
  [session current-token user-id current-time zone-id]
  (let [is-current? (= (:session-token session) current-token)]
    [:tr {:class (when is-current? "current-session")}
     [:td
      [:strong (parse-user-agent (:user-agent session))]
      (when is-current?
        [:span.badge.current " Current Session"])]
     [:td (or (:ip-address session) [:t :common/unknown])]
     [:td (format-relative-time* (:last-accessed-at session) current-time zone-id)]
     [:td (format-relative-time* (:created-at session) current-time zone-id)]
     [:td
      (when-not is-current?
        [:form {:method "post"
                :action "/web/sessions/revoke"
                :class "session-revoke-form"}
         (csrf/hidden-field)
         [:input {:type "hidden" :name "session-token" :value (:session-token session)}]
         [:input {:type "hidden" :name "user-id" :value user-id}]
         [:button.button.secondary.small
          {:type "submit"
           :onclick "return confirm('Revoke this session? The device will need to sign in again.');"}
          [:t :user/button-revoke]]])]]))

(defn sessions-list
  "Table displaying all user sessions.
   
   Args:
     sessions      - collection of session maps
     current-token - the current session token
     user-id       - user ID for action URLs
     
   Returns:
     Hiccup table
     
   Pure: true"
  [sessions current-token user-id current-time zone-id]
  (if (empty? sessions)
    [:div.empty-state.card [:t :user/sessions-empty-state]]
    [:div.sessions-table-shell
     (ui/table-wrapper
      [:table {:class "data-table"}
       [:thead
        [:tr
         [:th [:t :user/column-device]]
         [:th [:t :common/column-ip-address]]
         [:th [:t :user/column-last-active]]
         [:th [:t :common/column-created]]
         [:th [:t :common/column-actions]]]]
       [:tbody
        (for [session sessions]
          (session-row session current-token user-id current-time zone-id))]])]))

(defn user-sessions-page
  "Complete page for managing user sessions.
   
   Args:
     user          - user map
     sessions      - collection of session maps
     current-token - the current session token
     opts          - page options (user context, flash messages, etc.)
     
   Returns:
     Complete page hiccup
     
   Pure: false"
  [user sessions current-token opts]
  (let [current-time (:current-time opts)
        zone-id (:zone-id opts)]
    (page-layout
     [:t :user/page-sessions-title {:name (:name user)}]
     [:div.user-sessions-page
      [:div.page-header
       [:div
        [:h1 [:t :user/sessions-title]]
        [:p [:t :user/sessions-manage-for {:name (:name user)}]]]
       [:div.page-actions
        [:a.button.secondary {:href (str "/web/admin/users/" (:id user))} [:t :user/button-back-to-user]]
        (when (> (count sessions) 1)
          [:form.session-revoke-all-form {:method "post"
                                          :action (str "/web/users/" (:id user) "/sessions/revoke-all")}
           (csrf/hidden-field)
           [:input {:type "hidden" :name "keep-current" :value "true"}]
           [:button.button.danger
            {:type "submit"
             :onclick "return confirm('Revoke all other sessions? This will log out all other devices.');"}
            [:t :user/button-revoke-all-others]]])]]
      (sessions-list sessions current-token (:id user) current-time zone-id)]
     opts)))

;; =============================================================================
;; Audit Log Components
;; =============================================================================

(defn action-badge
  "Display action type with appropriate styling.
   
   Args:
     action - keyword action type (:create, :update, :delete, etc.)
     
   Returns:
     Hiccup span with styled badge
     
   Pure: true"
  [action]
  (let [action-name (name action)
        variant (case action
                  :create :success
                  :update :info
                  :delete :danger
                  :deactivate :warning
                  :activate :success
                  :login :info
                  :logout :neutral
                  :role-change :warning
                  :bulk-action :info
                  :neutral)]
    (ui/badge (str/capitalize action-name) {:variant variant
                                            :class "audit-action-badge"})))

(defn result-badge
  "Display result status with appropriate styling.
   
   Args:
     result - keyword result (:success or :failure)
     
   Returns:
     Hiccup span with styled badge
     
   Pure: true"
  [result]
  (if (= result :success)
    (ui/badge [:t :common/audit-success] {:variant :success
                                          :class "audit-result-badge"
                                          :icon (icons/icon :check-circle {:size 15})})
    (ui/badge [:t :common/audit-failure] {:variant :danger
                                          :class "audit-result-badge"
                                          :icon (icons/icon :x-circle {:size 15})})))

(defn format-audit-timestamp
  "Format timestamp for audit log display.

   Args:
     timestamp - java.time.Instant or similar

   Returns:
     Formatted timestamp string like '2026-02-01 07:39:15'

   Pure: true"
  [timestamp]
  (when timestamp
    (-> (str timestamp)
        (str/replace #"T" " ")
        (str/replace #"\.\d+Z$" "")
        (str/replace #"Z$" ""))))

(defn audit-log-row
  "Generate audit log table row.
   
   Args:
     audit-log - audit log entity map
     
   Returns:
     Hiccup table row
     
   Pure: true"
  [audit-log]
  (let [action (:action audit-log)
        result (:result audit-log)
        created-at (:created-at audit-log)
        error-msg (:error-message audit-log)]
    [:tr
     [:td (format-audit-timestamp created-at)]
     [:td (action-badge action)]
     [:td (or (:actor-email audit-log) [:em [:t :user/audit-actor-system]])]
     [:td (or (:target-user-email audit-log) "-")]
     [:td (result-badge result)]
     [:td (or (:ip-address audit-log) "-")]
     [:td
      [:button.button.small.secondary.audit-detail-trigger
       {:type "button"
        :title [:t :user/audit-view-details]
        :data-action (name action)
        :data-actor (or (:actor-email audit-log) [:t :user/audit-actor-system])
        :data-target (or (:target-user-email audit-log) "-")
        :data-ip (or (:ip-address audit-log) "-")
        :data-user-agent (or (:user-agent audit-log) "-")
        :data-error (or error-msg "")
        :data-timestamp (or (format-audit-timestamp created-at) "-")}
       (icons/icon :eye {:size 15})]]]))

(defn audit-detail-modal
  "Reusable modal for viewing audit log details."
  []
  [:dialog#audit-detail-modal.modal
   [:div.modal-box.audit-detail-modal
    [:h3 [:t :user/audit-modal-title]]
    [:div.audit-detail-grid
     [:div.audit-detail-row
      [:span.audit-detail-label [:t :common/audit-timestamp]]
      [:code#audit-detail-timestamp.audit-detail-value "-"]]
     [:div.audit-detail-row
      [:span.audit-detail-label [:t :common/audit-action]]
      [:code#audit-detail-action.audit-detail-value "-"]]
     [:div.audit-detail-row
      [:span.audit-detail-label [:t :common/audit-actor]]
      [:span#audit-detail-actor.audit-detail-value "-"]]
     [:div.audit-detail-row
      [:span.audit-detail-label [:t :common/audit-target]]
      [:span#audit-detail-target.audit-detail-value "-"]]
     [:div.audit-detail-row
      [:span.audit-detail-label [:t :common/audit-ip]]
      [:span#audit-detail-ip.audit-detail-value "-"]]
     [:div.audit-detail-row
      [:span.audit-detail-label [:t :user/audit-user-agent]]
      [:span#audit-detail-user-agent.audit-detail-value "-"]]
     [:div#audit-detail-error-row.audit-detail-row.hidden
      [:span.audit-detail-label [:t :common/audit-error]]
      [:code#audit-detail-error.audit-detail-value "-"]]]
    [:div.modal-action
     [:form {:method "dialog"}
      [:button.button.secondary {:type "submit"} [:t :common/button-close]]]]]])

(defn audit-detail-modal-script
  "Script for wiring audit detail buttons to modal."
  []
  [:script
   "(() => {
      if (window.__wagoeAuditModalInit) return;
      window.__wagoeAuditModalInit = true;
      document.addEventListener('click', (event) => {
        const trigger = event.target.closest('.audit-detail-trigger');
        if (!trigger) return;
        const modal = document.getElementById('audit-detail-modal');
        if (!modal) return;
        const setText = (id, value) => {
          const node = document.getElementById(id);
          if (node) node.textContent = value || '-';
        };
        setText('audit-detail-timestamp', trigger.dataset.timestamp);
        setText('audit-detail-action', trigger.dataset.action);
        setText('audit-detail-actor', trigger.dataset.actor);
        setText('audit-detail-target', trigger.dataset.target);
        setText('audit-detail-ip', trigger.dataset.ip);
        setText('audit-detail-user-agent', trigger.dataset.userAgent);
        setText('audit-detail-error', trigger.dataset.error);
        const errorRow = document.getElementById('audit-detail-error-row');
        if (errorRow) {
          if (trigger.dataset.error) {
            errorRow.classList.remove('hidden');
          } else {
            errorRow.classList.add('hidden');
          }
        }
        modal.showModal();
      });
    })();"])

(defn audit-logs-table
  "Generate audit logs table.
   
   Args:
     audit-logs - collection of audit log entity maps
     table-query - normalized TableQuery
     total-count - total number of audit logs
     filters - optional map of parsed search filters
     
   Returns:
     Hiccup structure for audit logs table
     
   Pure: true"
  ([audit-logs table-query total-count]
   (audit-logs-table audit-logs table-query total-count {}))
  ([audit-logs table-query total-count filters]
   (let [{:keys [sort dir page page-size]} table-query
         base-url "/web/audit/table"
         hx-target "#audit-table-container"
         table-params (table-query->params table-query)
         filter-params (search-filters->params filters)
         qs-map (merge table-params filter-params)
         hx-url (str base-url "?" (encode-query-params qs-map))]
     (if (empty? audit-logs)
       [:div#audit-table-container
        {:hx-get hx-url
         :hx-trigger "auditRefresh from:body"
         :hx-target hx-target}
        [:div.empty-state [:t :user/audit-empty-state]]]
       [:div#audit-table-container
        {:hx-get hx-url
         :hx-trigger "auditRefresh from:body"
         :hx-target hx-target}
        (ui/table-wrapper
         [:table {:class "data-table" :id "audit-table"}
          [:thead
           [:tr
            (table-ui/sortable-th {:label [:t :common/audit-timestamp]
                                   :field :created-at
                                   :current-sort sort
                                   :current-dir dir
                                   :base-url base-url
                                   :push-url-base "/web/audit"
                                   :page page
                                   :page-size page-size
                                   :hx-target hx-target
                                   :hx-push-url? true
                                   :extra-params filters})
            [:th [:t :common/audit-action]]
            [:th [:t :common/audit-actor]]
            [:th [:t :user/audit-column-target]]
            [:th [:t :user/audit-column-result]]
            [:th [:t :common/column-ip-address]]
            [:th [:t :common/column-actions]]]]
          [:tbody
           (for [audit-log audit-logs]
             (audit-log-row audit-log))]]
         (table-ui/pagination
          {:table-query table-query
           :total-count total-count
           :base-url base-url
           :push-url-base "/web/audit"
           :hx-target hx-target
           :extra-params filter-params}))]))))

(defn audit-filters
  "Generate filter form for audit logs.
   
   Args:
     filters - current filter values
     
   Returns:
     Hiccup form for filtering
     
   Pure: true"
  [filters]
  [:div.filters-panel.audit-filters-card
   [:h3 [:t :user/audit-filters-title]]
   [:form.audit-filters-form {:hx-get "/web/audit/table"
                              :hx-target "#audit-table-container"
                              :hx-push-url "true"}
    [:div.filter-group
     [:label {:for "action"} [:t :common/audit-action]]
     [:select {:name "action" :id "action"}
      [:option {:value ""} [:t :common/audit-all-actions]]
      [:option {:value "create" :selected (= "create" (:action filters))} [:t :common/audit-create]]
      [:option {:value "update" :selected (= "update" (:action filters))} [:t :common/audit-update]]
      [:option {:value "delete" :selected (= "delete" (:action filters))} [:t :common/audit-delete]]
      [:option {:value "activate" :selected (= "activate" (:action filters))} [:t :common/audit-activate]]
      [:option {:value "deactivate" :selected (= "deactivate" (:action filters))} [:t :common/audit-deactivate]]
      [:option {:value "login" :selected (= "login" (:action filters))} [:t :common/audit-login]]
      [:option {:value "logout" :selected (= "logout" (:action filters))} [:t :common/audit-logout]]
      [:option {:value "role-change" :selected (= "role-change" (:action filters))} [:t :common/audit-role-change]]
      [:option {:value "bulk-action" :selected (= "bulk-action" (:action filters))} [:t :common/audit-bulk-action]]]]

    [:div.filter-group
     [:label {:for "result"} [:t :user/audit-filter-result-label]]
     [:select {:name "result" :id "result"}
      [:option {:value ""} [:t :common/audit-all-results]]
      [:option {:value "success" :selected (= "success" (:result filters))} [:t :common/audit-success]]
      [:option {:value "failure" :selected (= "failure" (:result filters))} [:t :common/audit-failure]]]]

    [:div.filter-group
     [:label {:for "target-email"} [:t :user/audit-filter-target-email]]
     [:input {:type "text"
              :name "target-email"
              :id "target-email"
              :placeholder "user@example.com"
              :value (:target-email filters)}]]

    [:div.filter-group
     [:label {:for "actor-email"} [:t :user/audit-filter-actor-email]]
     [:input {:type "text"
              :name "actor-email"
              :id "actor-email"
              :placeholder "admin@example.com"
              :value (:actor-email filters)}]]

    [:div.form-actions
     [:button.button.primary {:type "submit"} [:t :common/button-apply]]
     [:button.button.secondary
      {:type "button"
       :onclick "window.location.href='/web/audit'"}
      [:t :common/button-clear]]]]])

(defn audit-page
  "Full audit logs page with layout.
   
   Args:
     audit-logs - collection of audit log entity maps
     table-query - normalized TableQuery
     total-count - total number of audit logs
     filters - optional map of parsed search filters
     opts - page options (user context, flash messages, etc.)
     
   Returns:
     Complete page hiccup
     
   Pure: false"
  ([audit-logs table-query total-count opts]
   (audit-page audit-logs table-query total-count {} opts))
  ([audit-logs table-query total-count filters opts]
   (page-layout
    [:t :user/page-audit-title]
    [:div.audit-page
     [:div.page-header
      [:div
       [:h1 [:t :user/page-audit-heading]]
       [:p [:t :user/page-audit-description]]]]

     [:div.audit-content.audit-layout
      [:div.filters-sidebar
       (audit-filters filters)]

      [:div.audit-main
       [:div.audit-table-card
        (audit-logs-table audit-logs table-query total-count filters)]]
      (audit-detail-modal)
      (audit-detail-modal-script)]]
    opts)))

;; =============================================================================
;; Dashboard Page
;; =============================================================================

(defn- format-date-relative*
  "Format date relative to an explicit reference time (e.g., 'today', '2 days ago', 'never').
   
   Args:
     instant - java.time.Instant or nil
     now - java.time.Instant supplied by the shell
     
   Returns:
     String describing relative time"
  [instant now]
  (if-not instant
    "never"
    (let [duration (java.time.Duration/between instant now)
          seconds (.getSeconds duration)]
      (cond
        (< seconds 60) "just now"
        (< seconds 3600) (str (quot seconds 60) " minutes ago")
        (< seconds 86400) (str (quot seconds 3600) " hours ago")
        (< seconds 172800) "yesterday"
        (< seconds 604800) (str (quot seconds 86400) " days ago")
        :else (-> instant str (clojure.string/split #"T") first)))))

(defn- format-date-short
  "Format date in short format (YYYY-MM-DD).
   
   Args:
     instant - java.time.Instant or nil
     
   Returns:
     String in YYYY-MM-DD format or 'N/A'"
  [instant]
  (if instant
    (-> instant str (clojure.string/split #"T") first)
    "N/A"))

(defn dashboard-page
  "User dashboard/welcome page with account statistics.
   
   Args:
     user - authenticated user entity
     dashboard-data - map containing:
       :active-sessions-count - number of active sessions
       :mfa-enabled - boolean indicating MFA status
     opts - page options (flash messages, etc.)
     
   Returns:
     Complete page hiccup
     
   Pure: false"
  [user dashboard-data opts]
  (let [current-time (:current-time opts)
        active-sessions (:active-sessions-count dashboard-data 0)
        mfa-enabled? (:mfa-enabled dashboard-data false)
        login-count (:login-count user 0)
        last-login (:last-login user)
        created-at (:created-at user)]
    (page-layout
     [:t :user/page-dashboard-title]
     [:div.dashboard-page
      [:div.welcome-section
       [:h1 [:t :user/dashboard-welcome {:name (:name user)}]]
       [:p.subtitle [:t :user/dashboard-subtitle]]]

      ;; Account Stats Summary
      [:div.dashboard-stats
       [:div.stat-card
        [:div.stat-icon (icons/icon :calendar {:size 20})]
        [:div.stat-content
         [:div.stat-label [:t :user/stat-member-since]]
         [:div.stat-value (format-date-short created-at)]]]
       [:div.stat-card
        [:div.stat-icon (icons/icon :log-in {:size 20})]
        [:div.stat-content
         [:div.stat-label [:t :user/stat-total-logins]]
         [:div.stat-value (str login-count)]]]
       [:div.stat-card
        [:div.stat-icon (icons/icon :clock {:size 20})]
        [:div.stat-content
         [:div.stat-label [:t :user/stat-last-login]]
         [:div.stat-value (format-date-relative* last-login current-time)]]]
       [:div.stat-card
        [:div.stat-icon {:class (if mfa-enabled? "text-success" "text-warning")}
         (icons/icon :shield {:size 20})]
        [:div.stat-content
         [:div.stat-label [:t :user/stat-mfa]]
         [:div.stat-value {:class (if mfa-enabled? "text-success" "text-warning")}
          (if mfa-enabled? [:t :common/status-enabled] [:t :common/status-disabled])]]]]

       ;; Action Cards
      [:div.dashboard-cards
       [:div.dashboard-card
        [:div.card-icon (icons/icon :user {:size 32})]
        [:h2 [:t :user/dashboard-card-profile-title]]
        [:p [:t :user/dashboard-card-profile-description]]
        [:a.button.secondary {:href "/web/profile"} [:t :user/button-view-profile]]]

       [:div.dashboard-card
        [:div.card-icon (icons/icon :lock {:size 32})]
        [:h2 [:t :user/dashboard-card-sessions-title]]
        [:p [:t :user/dashboard-card-sessions-description {:n active-sessions} active-sessions]]
        [:a.button.secondary {:href (str "/web/users/" (:id user) "/sessions")}
         [:t :user/button-manage-sessions]]]

        ;; Admin Panel card - only for admin users
       (when (= :admin (:role user))
         [:div.dashboard-card
          [:div.card-icon (icons/icon :shield {:size 32})]
          [:h2 [:t :user/dashboard-card-admin-title]]
          [:p [:t :user/dashboard-card-admin-description]]
          [:a.button.secondary {:href "/web/admin/"} [:t :user/button-admin-panel]]])

       ;; Extra cards injected by the host application via opts
       (when-let [extra (:extra-cards opts)]
         (for [card extra] card))]]
     opts)))
