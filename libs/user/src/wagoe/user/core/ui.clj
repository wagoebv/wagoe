(ns wagoe.user.core.ui
  "User-specific UI components based on User schema.
   
   This namespace contains pure functions for generating user-related Hiccup
   structures. Components are derived from the User schema and handle
   user-specific business logic for display and forms."
  (:require [wagoe.core.utils.text :as text]
            [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.htmx :as htmx]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.shared.ui.core.modal :as modal]
            [wagoe.shared.ui.core.shell :as shell]
            [wagoe.shared.ui.core.table :as table-ui]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.alpine :as alpine]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.user.core.rights-ui :as rights-ui]
            [clojure.string :as str]))

(defn- page-layout
  "User module page layout with daisyUI pilot styling."
  [title content & [opts]]
  (layout/pilot-page-layout
   title
   content
   opts))

(defn- auth-header
  "Wagoe's logo, app name and `subtitle` above a sign-in form."
  [opts subtitle]
  [:div.page-header.auth-header
   (when-let [logo-url (:logo-url opts)]
     [:img.auth-logo {:src logo-url :alt "" :style "height:48px; width:auto; display:block; margin:0 auto 12px;"}])
   [:h1 [:t :wagoe/app-name]]
   [:p [:t subtitle]]])

(defn- auth-page
  "A sign-in page: Wagoe's `header` above `content` in the pilot layout, or,
   when opts carry the app's `:login-layout`, `content` alone handed to it.
   The forms, CSRF field and errors are in `content` either way."
  [title page page-class header content opts]
  (if-let [app-layout (:login-layout opts)]
    (app-layout title
                (into [page-class] content)
                (-> opts (dissoc :login-layout) (assoc :page page)))
    (page-layout title
                 (into (cond-> [page-class] header (conj header)) content)
                 opts)))

;; =============================================================================
;; User Table Components
;; =============================================================================

(defn user-row
  "The cells of a user's row: name, email, role and status, the badges
   styled as the admin's enum cells.

   Args:
     user: User entity map (from User schema)

   Returns:
     Vector of cell values for table row (some as Hiccup structures)"
  [user]
  (let [role (or (:role user) :user)
        active? (boolean (:active user))
        badge (fn [text tone class]
                (update-in (ui/tone-badge text tone) [1 :class] str " " class))]
    [(:name user)
     (:email user)
     (badge (-> role name text/capitalize) (if (= :admin role) :info :neutral) "user-role-badge")
     (badge (if active? [:t :user/badge-active] [:t :user/badge-inactive])
            (if active? :success :warning)
            "user-status-badge")]))

(def ^:private user-columns
  ;; The columns of `user-row`, in its order
  [{:field :name :label [:t :common/column-name] :class "c-title"}
   {:field :email :label [:t :common/column-email]}
   {:field :role :label [:t :common/column-role] :sortable? false}
   {:field :status :label [:t :common/column-status] :sortable? false}])

(defn- user-table-row
  "A row that opens the user (admin-ux.js), with a box for bulk actions."
  [user]
  (let [href (str "/web/admin/users/" (:id user))]
    [:tr {:class "clickable-row" :data-href href}
     [:td.checkbox-cell [:input (alpine/row-checkbox-attrs (:id user))]]
     (map (fn [{:keys [field] col-class :class} cell]
            [:td {:class (str "field-" (name field) (when col-class (str " " col-class)))}
             (if (= :name field) [:a {:href href} cell] cell)])
          user-columns
          (user-row user))
     [:td.actions-cell
      [:a.row-nav-hint {:href href :aria-label [:t :user/button-view-user]}
       (icons/icon :chevron-right {:size 14})]]]))

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
   (let [base-url      "/web/users"
         hx-target     "#users-table-container"
         table-params  (table-ui/table-query->params table-query)
         filter-params (table-ui/search-filters->params filters)]
     [:div#users-table-container
      {:hx-get     (table-ui/list-url base-url table-query filters)
       :hx-trigger "userCreated from:body"
       :hx-target  hx-target
       :hx-swap    "outerHTML"}
      ;; What the toolbar's search, role filter and bulk form send along: they
      ;; sit outside this swap, so their own copies would go stale
      [:div {:class "table-state" :hidden true}
       [:div {:class "table-sort"}
        (table-ui/hidden-inputs (dissoc table-params "page"))]
       ;; Outside .table-sort: a filter starts at page one, a bulk action stays
       (table-ui/hidden-inputs (select-keys table-params ["page"]))
       (table-ui/hidden-inputs filter-params)]
      (if (empty? users)
        [:div.empty-state [:t :user/empty-state-no-users]]
        [:div.table-and-pagination
         [:div.table-wrapper
          (table-ui/data-table
           {:url         base-url
            :hx-target   hx-target
            :table-query table-query
            :filters     filters
            :columns     user-columns
            :head-before [[:th.checkbox-header [:input (alpine/select-all-checkbox-attrs)]]]
            :head-after  [[:th.actions-header [:span.sr-only [:t :common/column-actions]]]]
            :rows        (for [user users
                               :when (nil? (:deleted-at user))]
                           (user-table-row user))})]
         (table-ui/pagination {:table-query  table-query
                               :total-count  total-count
                               :base-url     base-url
                               :hx-target    hx-target
                               :extra-params filters})])])))

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
     (update fragment 1 assoc :hx-get "/web/users")))
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
     requirement - map with :met?, :message. A nil :met? means the state is not
                   known — the server cannot see what is in the field — and
                   renders as a neutral bullet rather than a ✓ or a ✗.

   Returns:
     Hiccup list item with status indicator

   Pure: true"
  [requirement]
  (let [[icon class] (case (:met? requirement)
                       true  ["✓" "requirement-met"]
                       false ["✗" "requirement-unmet"]
                       ["•" "requirement-pending"])]
    [:li {:class class}
     [:span.requirement-icon icon]
     [:span.requirement-text (:message requirement)]]))

;; Fallback for callers that do not supply a policy: the schema minimum, and
;; the digit `meets-password-policy?` requires by default. The shell passes the
;; configured policy instead, which can be stricter. Written as a comment
;; rather than a docstring because `bb i18n:scan` does not treat a string in
;; `def` position as one — `(def title "Users")` is the literal it exists to
;; catch.
(def default-password-policy
  {:min-length 8 :require-numbers true})

(defn password-requirements-list
  "Display list of password requirements with current status.
   
   Args:
     violations - vector of violation maps from meets-password-policy?, or nil
                  when nothing has been checked. nil is not an empty list: a
                  password box is never pre-filled, so on a re-render the server
                  has no idea what is in it, and the rules render as neither met
                  nor unmet. Reading nil as \"no violations\" ticked every rule
                  green above an empty field (BOU-381).
     policy     - password policy configuration map

   Returns:
     Hiccup list of requirements

   Pure: true"
  [violations policy]
  (let [checked?        (some? violations)
        violation-codes (set (map :code violations))
        met?            (fn [code] (when checked? (not (contains? violation-codes code))))
        requirements [{:code :too-short
                       :met? (met? :too-short)
                       :message [:t :user/password-requirement-min-length {:n (get policy :min-length 8)}]}
                      {:code :missing-uppercase
                       :met? (met? :missing-uppercase)
                       :message [:t :user/password-requirement-uppercase]
                       :required? (get policy :require-uppercase false)}
                      {:code :missing-lowercase
                       :met? (met? :missing-lowercase)
                       :message [:t :user/password-requirement-lowercase]
                       :required? (get policy :require-lowercase false)}
                      {:code :missing-number
                       :met? (met? :missing-number)
                       :message [:t :user/password-requirement-number]
                       :required? (get policy :require-numbers true)}
                      {:code :missing-special-char
                       :met? (met? :missing-special-char)
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

(defn- form-section
  "`fields` under `title`, laid out as the admin's forms are."
  [title & fields]
  [:div.form-card
   [:div.form-section
    [:div.form-section-header [:h3.form-section-title title]]
    (into [:div.form-fields] fields)]])

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
      ;; No heading of its own: both callers sit under a page header that
      ;; already renders `:user/form-create-title` as the `h1`, so carrying an
      ;; `h2` here put "Create New User" on the screen twice — the second one
      ;; left-aligned beside the centred form, which reads as a stray label
      ;; rather than a heading (BOU-393).
      ;; outerHTML: the response is itself a #create-user-form, so an innerHTML
      ;; swap nests one inside the other and leaves a duplicate id behind
      ;; (BOU-381).
      [:form {:hx-post   "/web/users"
              :hx-target "#create-user-form"
              :hx-swap   "outerHTML"
              :class     "entity-form"}
       (when-let [return-to (:return-to opts)]
         [:input {:type "hidden" :name "return-to" :value return-to}])
       ;; Errors the service reported against no particular field. Without this
       ;; they were collected and then never rendered, so the form swapped back
       ;; in carrying nothing.
       (when (seq (:form errors))
         [:div.validation-errors {:role "alert"}
          (for [err (:form errors)]
            [:p err])])
       (form-section
        [:t :user/form-detail-title]
        (ui/form-field :name [:t :common/label-name]
                       (ui/text-input :name (:name data) {:required true})
                       (:name errors))
        (ui/form-field :email [:t :common/label-email]
                       (ui/email-input :email (:email data) {:required true})
                       (:email errors))
        ;; Password field with validation feedback
        [:div {:class "form-field"}
         [:label {:for "password"} [:t :user/field-password]]
         (cond-> (ui/password-input :password rendered-password {:required true})
           (seq (:password errors))
           (ui/describe-input :password (ui/field-error-id :password)))
         ;; Passed straight through: with a blank field and no violations to go
         ;; on, the rules render as neither met nor unmet. What was wrong with
         ;; the submitted password is said by the error below, not by ticks
         ;; against a box the user has to fill in again (BOU-381).
         (when policy
           (password-requirements-list password-violations policy))
         ;; `.field-errors`, the same shape `ui/form-field` gives every other
         ;; field — `.validation-errors` is the form-level style used above for
         ;; errors that belong to no field, and rendering a password error in it
         ;; put a full-width panel under the field while the email error beside
         ;; it was a line of small red text (BOU-393).
         (ui/field-errors (ui/field-error-id :password) (:password errors))]
        (ui/form-field :role [:t :common/label-role]
                       (ui/select-field :role
                                        [[:user [:t :common/role-user]]
                                         [:admin [:t :common/role-admin]]]
                                        (or (:role data) :user))
                       (:role errors))
        (ui/form-field :send-welcome [:t :user/checkbox-send-welcome]
                       (ui/checkbox :send-welcome (get data :send-welcome true))
                       nil))
       [:div.form-actions.save-bar
        [:div.save-actions
         (ui/submit-button [:t :user/button-create] {:loading-text [:t :user/button-create-loading]})]]]]))
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

(defn- admin-page
  "`content` in the admin shell, `heading` in its topbar."
  [title heading content opts]
  (shell/shell-page title content (assoc opts :page-title heading)))

(defn- page-header
  "Breadcrumbs (`crumbs`, [label href] pairs, the last without href), the
   page's `actions` and `heading`, as the admin's detail pages have them."
  [crumbs actions heading]
  (apply rights-ui/header crumbs heading actions))

(defn- bulk-bar
  "The bar that appears once users are selected: the action and how to stop.
   Inside the bulkSelection scope of the users page."
  []
  (table-ui/bulk-bar
   {:form-attrs {:hx-post    "/web/users/bulk"
                 :hx-target  "#users-table-container"
                 :hx-swap    "outerHTML"
                 :hx-include "[name='ids[]'], #users-table-container .table-state input"
                 :class      "flex flex-wrap items-center gap-2"
                 ;; The rows it acted on are gone or changed: start over
                 "hx-on::after-request" "if (event.detail.successful) window.dispatchEvent(new CustomEvent('bulk-clear'))"}
    :controls   [[:select#bulk-action-select {:name "action" :class "select select-sm"
                                              :aria-label [:t :user/bulk-action-apply-title]}
                  [:option {:value "deactivate"} [:t :user/bulk-action-deactivate]]
                  [:option {:value "activate"} [:t :user/bulk-action-activate]]
                  [:option {:value "delete"} [:t :user/bulk-action-delete]]
                  [:option {:value "role-admin"} [:t :user/bulk-action-role-admin]]
                  [:option {:value "role-user"} [:t :user/bulk-action-role-user]]]
                 [:button#bulk-action-btn.button {:type "submit"} [:t :user/button-apply-bulk]]]
    :confirm    {:id      "bulk-action-confirm"
                 :title   [:t :user/bulk-action-apply-title]
                 :message [:t :user/confirm-bulk-action]}}))

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
         filters     (or (:filters opts) {})
         ;; Page one, sorted as now, with the other filter's value
         filter-opts (fn [other]
                       {:url        "/web/users"
                        :hx-target  "#users-table-container"
                        :hx-include (str "#users-table-container .table-sort input, " other)})]
     (admin-page
      [:t :user/page-users-title]
      [:t :user/page-users-title]
      [:div.users-page (merge (alpine/bulk-selection-attrs)
                              {:class "space-y-4"
                               ;; Escape clears the selection (keyboard.js)
                               :x-on:bulk-clear.window "clearSelection()"})
       [:div.table-toolbar-container
        [:div.toolbar-row-actions
         [:div.toolbar-search
          (table-ui/filter-input
           (merge (filter-opts "#users-role-filter")
                  {:name        "q"
                   :value       (:q filters)
                   :label       [:t :user/filter-placeholder-email]
                   :placeholder [:t :user/filter-placeholder-email]
                   :attrs       {:type "text" :class "search-input" :id "users-search"}}))
          [:span {:class "search-icon" :aria-hidden "true"} (icons/icon :search {:size 15})]]
         [:div.toolbar-filter
          (table-ui/filter-input
           (merge (filter-opts "#users-search")
                  {:name        "role"
                   :value       (:role filters)
                   :label       [:t :common/label-role]
                   :placeholder [:t :common/filter-all-roles]
                   :options     [["user" [:t :common/role-user]]
                                 ["admin" [:t :common/role-admin]]]
                   :attrs       {:id "users-role-filter" :class "select select-sm"}}))]
         [:div.toolbar-actions
          [:a.button.primary {:href "/web/users/new" :class "gap-2"}
           (icons/icon :plus {:size 18})
           [:t :user/button-create]]]]]
       (bulk-bar)
       (users-table users table-query total-count filters)]
      opts))))

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
    (admin-page
     [:t :user/page-create-user-title]
     [:t :user/page-users-title]
     [:div.entity-detail-page.create-user-page
      (page-header [[[:t :user/page-users-title] return-to] [[:t :user/form-create-title]]]
                   nil
                   [:t :user/form-create-title])
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
      data   - map with optional :email / :remember / :return-to; :registration?
               false hides the link to /web/register
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
      (when-let [providers (seq (:oidc-providers data))]
        [:div.oidc-providers
         [:p.auth-divider [:t :user/oidc-divider]]
         (for [{:keys [id label]} providers]
           [:a.button.secondary
            {:href (str "/web/auth/oidc/" id "/start"
                        (when (:return-to data)
                          (str "?return-to=" (java.net.URLEncoder/encode ^String (:return-to data) "UTF-8"))))}
            [:t :user/oidc-sign-in-with {:provider label}]])])
      (when-not (false? (:registration? data))
        [:div.auth-footer
         [:p [:t :user/text-no-account]]
         [:a.button.secondary {:href "/web/register"} [:t :user/button-create-account]]])]]))

(defn login-page
  "Complete login page.

   Args:
     data   - form data map (may be empty)
     errors - validation errors (may be nil)
     opts   - page options (user context, flash messages, return-to URL, etc.)"
  [& [data errors opts]]
  (let [data-with-return (cond-> (or data {})
                           (:return-to opts)      (assoc :return-to (:return-to opts))
                           (:oidc-providers opts) (assoc :oidc-providers (:oidc-providers opts))
                           (false? (:registration? opts)) (assoc :registration? false))]
    (auth-page
     [:t :user/link-signin]
     :login
     :div.login-page.auth-page
     (auth-header opts :user/login-subtitle)
     [(when-let [message (:error opts)]
        (ui/error-message message))
      (login-form data-with-return errors)]
     opts)))

(defn oidc-mfa-page
  "The second-factor prompt after a provider sign-in. The pending sign-in is
   in a cookie, so the form carries only the code."
  [errors opts]
  (auth-page
   [:t :user/mfa-title]
   :oidc-mfa
   :div.mfa-login-page.auth-page
   nil
   [[:div#mfa-login-form {:class "auth-card ui-card"}
     [:div.card-body
      [:h2.auth-title (icons/icon :shield {:size 24}) [:span [:t :user/mfa-title]]]
      [:p.auth-subtitle [:t :user/mfa-prompt]]
      [:form {:method "post" :action "/web/auth/oidc/mfa" :class "form-card ui-form-shell"}
       (csrf/hidden-field)
       (ui/form-field :mfa-code [:t :user/mfa-code-label]
                      [:input {:type "text" :id "mfa-code" :name "mfa-code"
                               :autocomplete "one-time-code" :required true
                               :autofocus true :class "mfa-code-input"}]
                      (:mfa-code errors))
       [:div.mfa-login-actions
        (ui/submit-button [:t :user/button-verify] {:loading-text [:t :user/button-verify-loading]})
        [:a.button.secondary {:href "/web/login"} [:t :common/button-cancel]]]]]]]
   opts))

(defn oidc-continue-page
  "Body of the answer to a provider callback that signed someone in; the
   handler's Refresh header moves the browser on."
  [location opts]
  (auth-page
   [:t :user/link-signin]
   :oidc-continue
   :div.auth-page
   nil
   [[:p [:a {:href location} [:t :user/oidc-continue]]]]
   opts))

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
    (auth-page
     [:t :user/mfa-title]
     :mfa
     :div.mfa-login-page.auth-page
     (auth-header opts :user/mfa-subtitle)
     [(mfa-login-form data-with-return errors)]
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
      ;; Errors on no field this form shows — :form, and :role or :active,
      ;; which self-service sets itself. Unrendered, a 400 said nothing.
      (when-let [other (seq (mapcat val (dissoc errors :name :email :password)))]
        [:div.validation-errors {:role "alert"}
         (for [err other]
           [:p err])])
      (ui/form-field :name [:t :common/label-name]
                     (ui/text-input :name (:name data) {:required true})
                     (:name errors))
      (ui/form-field :email [:t :common/label-email]
                     (ui/email-input :email (:email data) {:required true})
                     (:email errors))
       ;; Password field with validation feedback
      [:div {:class "form-field"}
       [:label {:for "password"} [:t :user/field-password]]
       (cond-> (ui/password-input :password "" {:required true})
         (seq (:password errors))
         (ui/describe-input :password (ui/field-error-id :password)))
        ;; nil violations render as neither met nor unmet — the field is never
        ;; pre-filled, so there is nothing to report against (BOU-381).
       (when policy
         (password-requirements-list password-violations policy))
        ;; `.field-errors`, as on the create-user form and for the same reason:
        ;; the name and email errors above come from `ui/form-field` in that
        ;; shape, so a password error in the form-level panel made one
        ;; submission answer in two different voices (BOU-393).
       (ui/field-errors (ui/field-error-id :password) (:password errors))]
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
    (auth-page
     [:t :user/page-create-account-title]
     :register
     :div.register-page.auth-page
     nil
     ;; In the content, not the header: an app layout keeps the way back.
     [[:div.page-header.auth-header
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
     session            - session map with :id, :ip-address, :user-agent, :created-at, :last-accessed-at
     current-session-id - id of the session making the request (marked current)
     user-id            - user ID for action URLs

   Returns:
     Hiccup table row

   Pure: true"
  [session current-session-id user-id current-time zone-id]
  (let [is-current? (and (some? current-session-id) (= (:id session) current-session-id))]
    [:tr {:class (when is-current? "current-session")}
     [:td
      [:strong (parse-user-agent (:user-agent session))]
      (when is-current?
        [:span.badge.current " Current Session"])]
     [:td (or (:ip-address session) [:t :common/unknown])]
     [:td (format-relative-time* (:last-accessed-at session) current-time zone-id)]
     [:td (format-relative-time* (:created-at session) current-time zone-id)]
     [:td.actions-cell
      (when-not is-current?
        ;; Asks the page's revoke-session-confirm; the handler reloads the page
        [:button.button.secondary.small
         (merge (modal/confirm-attrs "revoke-session-confirm")
                {:type    "button"
                 :hx-post (str "/web/users/" user-id "/sessions/" (:id session) "/revoke")})
         [:t :user/button-revoke]])]]))

(defn sessions-list
  "Table displaying all user sessions.
   
   Args:
     sessions      - collection of session maps
     current-session-id - id of the session making the request
     user-id       - user ID for action URLs
     
   Returns:
     Hiccup table
     
   Pure: true"
  [sessions current-session-id user-id current-time zone-id]
  (if (empty? sessions)
    [:div.empty-state.card [:t :user/sessions-empty-state]]
    (ui/table-wrapper
     [:table {:class "data-table table"}
      [:thead
       [:tr
        [:th [:t :user/column-device]]
        [:th [:t :common/column-ip-address]]
        [:th [:t :user/column-last-active]]
        [:th [:t :common/column-created]]
        [:th.actions-header [:t :common/column-actions]]]]
      [:tbody
       (for [session sessions]
         (session-row session current-session-id user-id current-time zone-id))]])))

(defn- hard-delete-button
  "Delete `user` for good, after the confirm modal. The handler sends the
   browser to the users list (HX-Redirect)."
  [user]
  (list
   [:button.button.ghost.button-danger-text
    (merge (modal/confirm-attrs "hard-delete-confirm")
           {:type    "button"
            :class   "gap-2"
            :hx-post (str "/web/users/" (:id user) "/hard-delete")})
    (icons/icon :trash {:size 14})
    [:t :user/button-delete-permanently]]
   (modal/confirm-modal {:id            "hard-delete-confirm"
                         :title         [:t :user/button-delete-permanently]
                         :message       [:t :user/confirm-delete-permanently]
                         :confirm-label [:t :user/button-delete-permanently]
                         :danger?       true})))

(defn user-sessions-page
  "Complete page for managing user sessions.

   Args:
     user          - user map
     sessions      - collection of session maps
     current-session-id - id of the session making the request
     opts          - page options (user context, flash messages, etc.)

   Returns:
     Complete page hiccup

   Pure: false"
  [user sessions current-session-id opts]
  (let [current-time (:current-time opts)
        zone-id (:zone-id opts)
        ;; The user pages behind the crumbs are for admins; a user viewing
        ;; their own sessions gets the crumbs without links
        admin? (= :admin (:role (:user opts)))]
    (admin-page
     [:t :user/page-sessions-title {:name (:name user)}]
     [:t :user/sessions-title]
     [:div.entity-detail-page.user-sessions-page
      (page-header
       [[[:t :user/page-users-title] (when admin? "/web/admin/users")]
        [(:name user) (when admin? (str "/web/admin/users/" (:id user)))]
        [[:t :user/sessions-title]]]
       [(when (> (count sessions) 1)
          [:button.button.ghost.button-danger-text
           (merge (modal/confirm-attrs "revoke-all-confirm")
                  {:type    "button"
                   :hx-post (str "/web/users/" (:id user) "/sessions/revoke-all")
                   :hx-vals "{\"keep-current\": \"true\"}"})
           [:t :user/button-revoke-all-others]])
        ;; Only an inactive user, and only an admin: there is no undo
        (when (and admin? (not (:active user)))
          (hard-delete-button user))]
       [:t :user/sessions-manage-for {:name (:name user)}])
      (when admin? (rights-ui/user-tabs (:id user) :sessions))
      (sessions-list sessions current-session-id (:id user) current-time zone-id)
      (modal/confirm-modal {:id            "revoke-session-confirm"
                            :title         [:t :user/button-revoke]
                            :message       [:t :user/confirm-revoke-session]
                            :confirm-label [:t :user/button-revoke]
                            :danger?       true})
      (modal/confirm-modal {:id            "revoke-all-confirm"
                            :title         [:t :user/button-revoke-all-others]
                            :message       [:t :user/confirm-revoke-all-sessions]
                            :confirm-label [:t :user/button-revoke-all-others]
                            :danger?       true})]
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
                  :permanent-delete :danger
                  :deactivate :warning
                  :activate :success
                  :login :info
                  :logout :neutral
                  :session-revoked :warning
                  :data-export :warning
                  :mfa-disabled :warning
                  :role-change :warning
                  :bulk-action :info
                  (:group-join :grant-add) :info
                  (:group-leave :grant-remove) :warning
                  :neutral)]
    (ui/badge (text/capitalize (str/replace action-name "-" " ")) {:variant variant
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

(def ^:private audit-columns
  [{:field :created-at :label [:t :common/audit-timestamp]}
   {:field :action :label [:t :common/audit-action] :sortable? false}
   {:field :actor :label [:t :common/audit-actor] :sortable? false}
   {:field :target :label [:t :user/audit-column-target] :sortable? false}
   {:field :result :label [:t :user/audit-column-result] :sortable? false}
   {:field :ip-address :label [:t :common/column-ip-address] :sortable? false}])

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
    (conj
     ;; The labels head a cell when a narrow screen shows rows as cards
     (into [:tr]
           (map (fn [{:keys [label]} content] [:td {:data-label label} content])
                audit-columns
                [(format-audit-timestamp created-at)
                 (action-badge action)
                 (or (:actor-email audit-log) [:em [:t :user/audit-actor-system]])
                 (or (:target-user-email audit-log) "-")
                 (result-badge result)
                 (or (:ip-address audit-log) "-")]))
     [:td.actions-cell
      [:button.button.small.secondary.audit-detail-trigger
       (merge (modal/open-attrs "audit-detail-modal")
              {:title [:t :user/audit-view-details]
               :data-action (name action)
               :data-actor (or (:actor-email audit-log) [:t :user/audit-actor-system])
               :data-target (or (:target-user-email audit-log) "-")
               :data-ip (or (:ip-address audit-log) "-")
               :data-user-agent (or (:user-agent audit-log) "-")
               :data-error (or error-msg "")
               :data-timestamp (or (format-audit-timestamp created-at) "-")})
       (icons/icon :eye {:size 15})]])))

(defn audit-detail-modal
  "The modal a row's detail button opens; admin-ux.js fills it from the
   button's data attributes."
  []
  (modal/modal
   {:id "audit-detail-modal" :title [:t :user/audit-modal-title] :class "audit-detail-modal"}
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
    [:div#audit-detail-error-row.audit-detail-row {:hidden true}
     [:span.audit-detail-label [:t :common/audit-error]]
     [:code#audit-detail-error.audit-detail-value "-"]]]))

(def ^:private audit-url "/web/audit")
(def ^:private audit-target "#audit-table-container")

(defn audit-count-badge
  "The toolbar's entry count. The table fragment carries it out of band, so
   it follows the filters."
  [total-count]
  [:span {:id "audit-record-count" :class "record-count-badge"} (str total-count)])

(defn audit-logs-table
  "The audit table, its pagination or an empty state, in the container the
   page's controls re-render. They request `/web/audit` and push it, so the
   address bar holds sort, filters and page.

   Args:
     audit-logs - collection of audit log entity maps
     table-query - normalized TableQuery
     total-count - total number of audit logs
     filters - map of parsed search filters

   Pure: true"
  [audit-logs table-query total-count filters]
  [:div#audit-table-container
   ;; The sort the toolbar filters send along: they sit outside this swap
   [:div {:class "table-state" :hidden true}
    (table-ui/hidden-inputs (select-keys (table-ui/table-query->params table-query)
                                         ["sort" "dir" "page-size"]))]
   (cond
     (and (empty? audit-logs) (seq filters))
     [:div.empty-state {:class "empty-state-filtered"}
      [:div.empty-state-icon (icons/icon :filter {:size 32})]
      [:p [:t :user/audit-empty-state-filtered]]
      [:a.button.secondary {:href audit-url} [:t :common/button-clear]]]

     (empty? audit-logs)
     [:div.empty-state {:class "p-10 text-center"}
      [:div.empty-state-icon (icons/icon :inbox {:size 48})]
      [:p [:t :user/audit-empty-state]]]

     :else
     (let [pagination (table-ui/pagination {:table-query  table-query
                                            :total-count  total-count
                                            :base-url     audit-url
                                            :hx-target    audit-target
                                            :extra-params filters})]
       [:div {:class (when pagination "table-and-pagination")}
        (ui/table-wrapper
         (table-ui/data-table
          {:url         audit-url
           :hx-target   audit-target
           :table-query table-query
           :filters     filters
           :class       "audit-table"
           :columns     audit-columns
           :head-after  [[:th {:class "actions-header"} [:span.sr-only [:t :common/column-actions]]]]
           :rows        (map audit-log-row audit-logs)}))
        pagination]))])

(defn audit-table-fragment
  "What the table's controls get back: the table, and the count out of band."
  [audit-logs table-query total-count filters]
  (list (audit-logs-table audit-logs table-query total-count filters)
        (htmx/oob (audit-count-badge total-count))))

(def ^:private audit-actions
  [["create" :common/audit-create] ["update" :common/audit-update]
   ["delete" :common/audit-delete] ["permanent-delete" :common/audit-permanent-delete]
   ["activate" :common/audit-activate] ["deactivate" :common/audit-deactivate]
   ["login" :common/audit-login] ["logout" :common/audit-logout]
   ["session-revoked" :common/audit-session-revoked] ["role-change" :common/audit-role-change]
   ["bulk-action" :common/audit-bulk-action]
   ["group-join" :common/audit-group-join] ["group-leave" :common/audit-group-leave]
   ["grant-add" :common/audit-grant-add] ["grant-remove" :common/audit-grant-remove]
   ["data-export" :common/audit-data-export] ["mfa-disabled" :common/audit-mfa-disabled]])

(defn audit-filters
  "The toolbar's filters. Each re-queries the table as it changes, sending
   the others and the table's sort along, and pushes the URL.

   Pure: true"
  [filters]
  (let [input (fn [k label opts]
                (table-ui/filter-input
                 (merge {:name       (name k)
                         :value      (get filters k)
                         :label      label
                         :url        audit-url
                         :hx-target  audit-target
                         :hx-include "#audit-filters [name], #audit-table-container .table-state input"
                         :attrs      {:id (str "audit-filter-" (name k))}}
                        opts)))]
    [:div#audit-filters.toolbar-filters
     (input :action [:t :common/audit-action]
            {:placeholder [:t :common/audit-all-actions]
             :options     (for [[v k] audit-actions] [v [:t k]])})
     (input :result [:t :user/audit-filter-result-label]
            {:placeholder [:t :common/audit-all-results]
             :options     [["success" [:t :common/audit-success]]
                           ["failure" [:t :common/audit-failure]]]})
     (input :target-email [:t :user/audit-filter-target-email]
            {:placeholder [:t :user/audit-filter-target-email]})
     (input :actor-email [:t :user/audit-filter-actor-email]
            {:placeholder [:t :user/audit-filter-actor-email]})]))

(defn audit-page
  "The audit trail in the admin shell: a toolbar of filters over the table.

   Args:
     audit-logs - collection of audit log entity maps
     table-query - normalized TableQuery
     total-count - total number of audit logs
     filters - map of parsed search filters
     opts - :user and :flash

   Pure: false (the shell reads the request's navigation)"
  [audit-logs table-query total-count filters opts]
  (shell/shell-page
   [:t :user/page-audit-title]
   [:div.entity-list-page {:class "space-y-4"}
    [:div.table-toolbar-container
     [:div.toolbar-row-actions
      [:div.record-meta (audit-count-badge total-count)]
      (audit-filters filters)]]
    (audit-logs-table audit-logs table-query total-count filters)
    (audit-detail-modal)]
   (assoc opts :page-title [:t :user/page-audit-heading])))
