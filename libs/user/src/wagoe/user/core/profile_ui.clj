(ns wagoe.user.core.profile-ui
  "Profile-specific UI components for user profile management.
   
   This namespace contains pure functions for generating profile-related Hiccup
   structures including profile viewing, editing, password changes, and MFA setup."
  (:require [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.platform.core.csrf :as csrf]
            [clojure.string :as str]))

(declare profile-info-fragment
         preferences-fragment
         password-section-fragment)

(defn- page-layout
  "Profile page layout with daisyUI pilot styling."
  [title content & [opts]]
  (layout/pilot-page-layout title content opts))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- format-date
  "Format an Instant for display."
  [instant]
  (when instant
    (-> instant
        str
        (str/replace #"T" " ")
        (str/replace #"\.\d+Z" ""))))

(defn- format-role
  "Format role keyword for display."
  [role]
  (when role
    (-> role name str/capitalize)))

;; =============================================================================
;; Profile Information Components
;; =============================================================================

(defn profile-info-card
  "Display user profile information card (read-only view).
   
   Args:
     user: User entity map
     
   Returns:
     Hiccup structure for profile info card"
  [user]
  [:div.profile-card
   [:div.card-header
    [:h2 [:t :user/profile-card-info-title]]
    [:button.button.secondary {:type "button"
                               :hx-get "/web/profile/edit"
                               :hx-target "#profile-info-card"
                               :hx-swap "outerHTML"}
     (icons/icon :edit {:size 16})
     " " [:t :common/button-edit]]]
   (profile-info-fragment user)])

(defn profile-info-fragment
  "Profile information card-body fragment for HTMX swaps."
  [user]
  [:div#profile-info-card.card-body
   [:div.info-row
    [:label [:t :common/label-name]]
    [:span (:name user)]]
   [:div.info-row
    [:label [:t :common/label-email]]
    [:span (:email user)]]
   [:div.info-row
    [:label [:t :common/label-role]]
    (ui/badge (format-role (:role user))
              {:variant :outline
               :class "user-role-badge profile-role-badge"})]
   [:div.info-row
    [:label [:t :user/profile-label-member-since]]
    [:span (format-date (:created-at user))]]])

(defn profile-edit-form
  "Display editable profile form (HTMX partial).
   
   Args:
     user: User entity map
     errors: Validation errors map (optional)
     
   Returns:
     Hiccup structure for profile edit form"
  ([user] (profile-edit-form user {}))
  ([user errors]
   [:div#profile-info-card.card-body
    [:form {:hx-post "/web/profile"
            :hx-target "#profile-info-card"
            :hx-swap "outerHTML"}
     (ui/form-field :name [:t :common/label-name]
                    (ui/text-input :name (:name user) {:required true})
                    (:name errors))
     [:div.info-row
      [:label [:t :common/label-email]]
      [:span (:email user) " "
       [:small.text-muted [:t :user/profile-email-note-readonly]]]]
     [:div.info-row
      [:label [:t :common/label-role]]
      [:div.profile-role-inline
       (ui/badge (format-role (:role user))
                 {:variant :outline
                  :class "user-role-badge profile-role-badge"})
       [:small.text-muted [:t :user/profile-role-note-admin-managed]]]]
     [:div.form-actions
      [:button.button.primary {:type "submit"}
       (icons/icon :save {:size 16})
       " " [:t :user/button-save-changes]]
      [:button.button.secondary {:type "button"
                                 :hx-get "/web/profile/info"
                                 :hx-target "#profile-info-card"
                                 :hx-swap "outerHTML"}
       [:t :common/button-cancel]]]]]))

(defn preferences-card
  "Display user preferences card.
   
   Args:
     user: User entity map
     
   Returns:
     Hiccup structure for preferences card"
  [user]
  [:div.profile-card
   [:div.card-header
    [:h2 [:t :user/preferences-card-title]]
    [:button.button.secondary {:type "button"
                               :hx-get "/web/profile/preferences/edit"
                               :hx-target "#preferences-card"
                               :hx-swap "outerHTML"}
     (icons/icon :edit {:size 16})
     " " [:t :common/button-edit]]]
   (preferences-fragment user)])

(defn preferences-fragment
  "Preferences card-body fragment for HTMX swaps."
  [user]
  [:div#preferences-card.card-body
   [:div.info-row
    [:label [:t :user/preferences-label-date-format]]
    [:span (case (:date-format user)
             :iso [:t :user/preferences-date-iso]
             :us [:t :user/preferences-date-us]
             :eu [:t :user/preferences-date-eu]
             [:t :user/preferences-not-set])]]
   [:div.info-row
    [:label [:t :user/preferences-label-time-format]]
    [:span (case (:time-format user)
             :12h [:t :user/preferences-time-12h]
             :24h [:t :user/preferences-time-24h]
             [:t :user/preferences-not-set])]]
   [:div.info-row
    [:label [:t :user/preferences-label-language]]
    [:span (case (keyword (:language user))
             :en [:t :user/preferences-language-en]
             :nl [:t :user/preferences-language-nl]
             [:t :user/preferences-not-set])]]])

(defn preferences-edit-form
  "Display editable preferences form (HTMX partial).
   
   Args:
     user: User entity map
     errors: Validation errors map (optional)
     
   Returns:
     Hiccup structure for preferences edit form"
  ([user] (preferences-edit-form user {}))
  ([user errors]
   [:div#preferences-card.card-body
    [:form {:hx-post "/web/profile/preferences"
            :hx-target "#preferences-card"
            :hx-swap "outerHTML"}
     [:div.form-field
      [:label {:for "date-format"} [:t :user/preferences-label-date-format]]
      [:select#date-format {:name "date-format"}
       [:option {:value "iso" :selected (= :iso (:date-format user))} [:t :user/preferences-date-iso]]
       [:option {:value "us" :selected (= :us (:date-format user))} [:t :user/preferences-date-us]]
       [:option {:value "eu" :selected (= :eu (:date-format user))} [:t :user/preferences-date-eu]]]
      (when (:date-format errors)
        [:span.error (first (:date-format errors))])]
     [:div.form-field
      [:label {:for "time-format"} [:t :user/preferences-label-time-format]]
      [:select#time-format {:name "time-format"}
       [:option {:value "12h" :selected (= :12h (:time-format user))} [:t :user/preferences-time-12h]]
       [:option {:value "24h" :selected (= :24h (:time-format user))} [:t :user/preferences-time-24h]]]
      (when (:time-format errors)
        [:span.error (first (:time-format errors))])]
     [:div.form-field
      [:label {:for "language"} [:t :user/preferences-label-language]]
      [:select#language {:name "language"}
       [:option {:value "en" :selected (= "en" (:language user))} [:t :user/preferences-language-en]]
       [:option {:value "nl" :selected (= "nl" (:language user))} [:t :user/preferences-language-nl]]]
      (when (:language errors)
        [:span.error (first (:language errors))])]
     [:div.form-actions
      [:button.button.primary {:type "submit"}
       (icons/icon :save {:size 16})
       " " [:t :user/button-save-preferences]]
      [:button.button.secondary {:type "button"
                                 :hx-get "/web/profile/preferences"
                                 :hx-target "#preferences-card"
                                 :hx-swap "outerHTML"}
       [:t :common/button-cancel]]]]]))

;; =============================================================================
;; Password Change Components
;; =============================================================================

(defn password-change-card
  "Display password change card in security section.
   
   Args:
     expanded?: Boolean indicating if form should be shown
     errors: Validation errors map (optional)
     
   Returns:
     Hiccup structure for password change card"
  ([expanded?] (password-change-card expanded? {}))
  ([expanded? errors]
   [:div.profile-card
    [:div.card-header
     [:h2 [:t :user/profile-card-password-title]]
     [:span.text-muted "••••••••"]]
    (password-section-fragment expanded? errors)]))

(defn password-section-fragment
  "Password card-body fragment for HTMX swaps."
  ([expanded?] (password-section-fragment expanded? {}))
  ([expanded? errors]
   (if-not expanded?
     [:div#password-section.card-body
      [:button.button.secondary {:type "button"
                                 :hx-get "/web/profile/password/form?fragment=1"
                                 :hx-target "#password-section"
                                 :hx-swap "outerHTML"}
       (icons/icon :key {:size 16})
       " " [:t :user/button-change-password]]]
     [:div#password-section.card-body
      [:form {:hx-post "/web/profile/password"
              :hx-target "#password-section"
              :hx-swap "outerHTML"}
       (ui/form-field :current-password [:t :user/password-field-current]
                      (ui/password-input :current-password "" {:required true})
                      (:current-password errors))
       (ui/form-field :new-password [:t :user/password-field-new]
                      (ui/password-input :new-password "" {:required true})
                      (:new-password errors))
       (ui/form-field :confirm-password [:t :user/password-field-confirm]
                      (ui/password-input :confirm-password "" {:required true})
                      (:confirm-password errors))
       [:div.form-actions
        [:button.button.primary {:type "submit"}
         (icons/icon :save {:size 16})
         " " [:t :user/button-change-password]]
        [:button.button.secondary {:type "button"
                                   :hx-get "/web/profile/password?fragment=1"
                                   :hx-target "#password-section"
                                   :hx-swap "outerHTML"}
         [:t :common/button-cancel]]]]])))

(defn password-change-success
  "Display success message after password change.
   
   Returns:
     Hiccup structure for success message"
  []
  [:div#password-section.card-body
   [:div.alert.alert-success
    (icons/icon :check-circle {:size 20})
    " " [:t :user/message-password-changed]]
   [:button.button.secondary {:hx-get "/web/profile/password?fragment=1"
                              :hx-target "#password-section"
                              :hx-swap "outerHTML"}
    [:t :user/button-back-to-profile]]])

;; =============================================================================
;; MFA Status Components
;; =============================================================================

(defn mfa-status-card
  "Display MFA status card with enable/disable actions.
   
   Args:
     user: User entity map
     mfa-status: MFA status map from mfa-service
     
    Returns:
      Hiccup structure for MFA status card"
  [_user mfa-status]
  [:div.profile-card
   [:div.card-header
    [:h2 [:t :user/mfa-card-title]]
    (if (:enabled mfa-status)
      (ui/badge [:t :common/status-enabled]
                {:variant :success
                 :class "user-status-badge mfa-status-badge"
                 :icon (icons/icon :shield {:size 16})})
      (ui/badge [:t :user/mfa-badge-not-enabled]
                {:variant :neutral
                 :class "user-status-badge mfa-status-badge"
                 :icon (icons/icon :shield {:size 16})}))]
   [:div.card-body
    (if (:enabled mfa-status)
      ;; MFA is enabled
      [:div
       [:div.info-row
        [:label [:t :common/label-status]]
        [:span (icons/icon :check-circle {:size 16 :class "text-success"})
         " " [:t :user/mfa-status-active]]]
       (when (:enabled-at mfa-status)
         [:div.info-row
          [:label [:t :user/mfa-label-enabled-since]]
          [:span (format-date (:enabled-at mfa-status))]])
       [:div.info-row
        [:label [:t :user/mfa-label-backup-codes]]
        [:span [:t :user/mfa-backup-codes-remaining {:n (:backup-codes-remaining mfa-status)} (:backup-codes-remaining mfa-status)]]]
       [:div.form-actions
        [:a.button.danger {:href "/web/profile/mfa/disable"}
         (icons/icon :unlock {:size 16})
         " " [:t :user/button-disable-mfa]]]]
      ;; MFA is not enabled
      [:div
       [:p [:t :user/mfa-setup-intro]]
       [:div.form-actions
        [:a.button.primary {:href "/web/profile/mfa/setup"}
         (icons/icon :shield {:size 16})
         " " [:t :user/button-enable-mfa]]]])]])

;; =============================================================================
;; MFA Setup Components
;; =============================================================================

(defn mfa-setup-page
  "Display MFA setup page with instructions.
   
   Args:
     opts: Optional map with :user, :flash, etc.
     
   Returns:
     Complete HTML page for MFA setup"
  [& [opts]]
  (page-layout
   [:t :user/mfa-setup-title]
   [:div.profile-page.profile-shell
    [:div.page-header.profile-header
     [:h1
      [:a {:href "/web/profile"} (icons/icon :chevron-left {:size 20})]
      " " [:t :user/mfa-setup-title]]]
    [:div.profile-content.profile-grid
     [:div.mfa-setup-intro
      [:p [:t :user/mfa-setup-description]]
      [:ul
       [:li [:t :user/mfa-setup-requirement-app]]
       [:li [:t :user/mfa-setup-requirement-password]]]
      [:div.form-actions
       [:button.button.primary {:hx-post "/web/profile/mfa/setup"
                                :hx-target "#mfa-setup-content"
                                :hx-swap "innerHTML"}
        (icons/icon :shield {:size 16})
        " " [:t :user/button-start-setup]]
       [:a.button.secondary {:href "/web/profile"}
        [:t :common/button-cancel]]]]
     [:div#mfa-setup-content]]]
   opts))

(defn mfa-qr-code-step
  "Display QR code and verification step (HTMX fragment).
   
   Args:
     secret: TOTP secret
     qr-code-url: QR code image URL
     issuer: Application name
     account-name: User's email
     errors: Validation errors (optional)
     
   Returns:
     Hiccup structure for QR code step"
  ([secret qr-code-url issuer account-name]
   (mfa-qr-code-step secret qr-code-url issuer account-name [] {}))
  ([secret qr-code-url issuer account-name backup-codes]
   (mfa-qr-code-step secret qr-code-url issuer account-name backup-codes {}))
  ([secret qr-code-url _issuer _account-name backup-codes errors]
   [:div.mfa-setup-steps
    [:div.mfa-step
     [:h3 [:t :user/mfa-step1-title]]
     [:p [:t :user/mfa-step1-description]]
     [:div.qr-code-container
      [:img {:src qr-code-url
             :alt [:t :user/mfa-qr-code-alt]
             :width "200"
             :height "200"}]]
     [:div.manual-entry
      [:p [:t :user/mfa-manual-entry-prompt]]
      [:div.secret-code
       [:code secret]
       [:button.button.secondary.small {:type "button"
                                        :onclick (str "navigator.clipboard.writeText('" secret "');"
                                                      "this.textContent='Copied!';"
                                                      "setTimeout(()=>this.textContent='Copy',2000);")}
        [:t :common/button-copy]]]]]
    [:div.mfa-step
     [:h3 [:t :user/mfa-step2-title]]
     [:p [:t :user/mfa-step2-description]]
     [:form {:hx-post "/web/profile/mfa/verify"
             :hx-target "#mfa-setup-content"
             :hx-swap "innerHTML"}
       ;; Store secret and backup codes in hidden fields for verification
      [:input {:type "hidden" :name "secret" :value secret}]
      [:input {:type "hidden" :name "backup-codes" :value (pr-str backup-codes)}]
      (ui/form-field :verification-code [:t :user/mfa-field-verification-code]
                     (ui/text-input :verification-code ""
                                    {:required true
                                     :maxlength "6"
                                     :pattern "[0-9]{6}"
                                     :placeholder "000000"})
                     (:verification-code errors))
      [:div.form-actions
       [:button.button.primary {:type "submit"}
        (icons/icon :check {:size 16})
        " " [:t :user/button-verify-code]]
       [:a.button.secondary {:href "/web/profile"}
        [:t :common/button-cancel]]]]]]))

(defn mfa-backup-codes-display
  "Display backup codes after MFA enable (HTMX fragment or full page).
   
   Args:
     backup-codes: Vector of backup code strings
     full-page?: Boolean indicating if this should be a full page
     opts: Optional map with :user, :flash, etc.
     
   Returns:
     Hiccup structure for backup codes display"
  ([backup-codes] (mfa-backup-codes-display backup-codes false {}))
  ([backup-codes full-page?] (mfa-backup-codes-display backup-codes full-page? {}))
  ([backup-codes full-page? opts]
   (let [content
         [:div.mfa-backup-codes
          [:div.alert.alert-success
           (icons/icon :check-circle {:size 20})
           " " [:t :user/mfa-backup-codes-success]]
          [:h2 [:t :user/mfa-backup-codes-title]]
          [:p [:t :user/mfa-backup-codes-description]]
          [:div.backup-codes-grid
           (for [[idx code] (map-indexed vector backup-codes)]
             [:div.backup-code-item {:key idx}
              [:span.code-number (str (inc idx) ".")]
              [:code code]])]
          [:div.backup-codes-actions
           [:button.button.secondary {:type "button"
                                      :onclick (str "const codes = "
                                                    (pr-str (str/join "\n" backup-codes))
                                                    ";"
                                                    "navigator.clipboard.writeText(codes);"
                                                    "this.textContent='Copied!';"
                                                    "setTimeout(()=>this.textContent='Copy All',2000);")}
            (icons/icon :copy {:size 16})
            " " [:t :user/button-copy-all]]
           [:button.button.secondary {:type "button"
                                      :onclick "window.print();"}
            (icons/icon :download {:size 16})
            " " [:t :common/button-print]]]
          [:div.backup-codes-confirm
           [:label
            [:input {:type "checkbox" :id "codes-saved"}]
            " " [:t :user/mfa-backup-codes-confirmation]]]
          [:div.form-actions
           [:a.button.primary {:href "/web/profile"}
            [:t :user/button-done-return]]]]]
     (if full-page?
       (page-layout
        [:t :user/mfa-backup-codes-page-title]
        [:div.profile-page.profile-shell
         [:div.page-header.profile-header
          [:h1 [:t :user/mfa-backup-codes-heading]]]
         [:div.profile-content.profile-grid
          content]]
        opts)
       content))))

;; =============================================================================
;; MFA Disable Components
;; =============================================================================

(defn mfa-disable-confirm-page
  "Display MFA disable confirmation page with password verification.
   
   Args:
     errors: Validation errors map (optional)
     opts: Optional map with :user, :flash, etc.
     
   Returns:
     Complete HTML page for MFA disable confirmation"
  ([errors] (mfa-disable-confirm-page errors {}))
  ([errors opts]
   (page-layout
    [:t :user/mfa-disable-title]
    [:div.profile-page.profile-shell
     [:div.page-header.profile-header
      [:h1
       [:a {:href "/web/profile"} (icons/icon :chevron-left {:size 20})]
       " " [:t :user/mfa-disable-title]]]
     [:div.profile-content.profile-grid
      [:div.alert.alert-warning
       (icons/icon :alert-circle {:size 20})
       " " [:t :user/mfa-disable-warning]]
      [:div.profile-card
       [:div.card-body
        [:p [:t :user/mfa-disable-prompt]]
        [:form {:method "POST" :action "/web/profile/mfa/disable"}
         (csrf/hidden-field)
         (ui/form-field :password [:t :user/password-field-current]
                        (ui/password-input :password "" {:required true})
                        (:password errors))
         [:div.form-actions
          [:button.button.danger {:type "submit"}
           (icons/icon :unlock {:size 16})
           " " [:t :user/mfa-disable-title]]
          [:a.button.secondary {:href "/web/profile"}
           [:t :common/button-cancel]]]]]]]]
    opts)))

;; =============================================================================
;; Main Profile Page
;; =============================================================================

(defn profile-page
  "Display main profile page with all sections.
   
   Args:
     user: User entity map
     mfa-status: MFA status map from mfa-service
     opts: Optional map with :user (for layout), :flash, etc.
     
   Returns:
     Complete HTML page for user profile"
  [user mfa-status & [opts]]
  (page-layout
   [:t :user/profile-page-title]
   [:div.profile-page.profile-shell
    [:div.page-header.profile-header
     [:h1 [:t :user/profile-page-title]]]
    [:div.profile-content.profile-grid
     (profile-info-card user)
     (preferences-card user)
     (password-change-card false)
     (mfa-status-card user mfa-status)
     [:div.profile-card
      [:div.card-header
       [:h2 [:t :user/sessions-title]]]
      [:div.card-body
       [:div.info-row
        [:label [:t :user/profile-label-sessions]]
        [:span
         [:a {:href (str "/web/users/" (:id user) "/sessions")}
          [:t :user/button-manage-sessions] " "
          (icons/icon :chevron-right {:size 16})]]]]]]]
   opts))
