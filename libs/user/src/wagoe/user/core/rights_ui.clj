(ns wagoe.user.core.rights-ui
  "Admin pages answering who may do what (ADR-041 §7): roles, permissions and
   a user's rights. Pure Hiccup in the admin shell; plain forms that post and
   redirect."
  (:require [clojure.string :as str]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.platform.core.scope :as scope]
            [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.shell :as shell]
            [wagoe.shared.ui.core.table :as table-ui]
            [wagoe.user.core.group :as group]))

(def roles-path "/web/admin/roles")
(def permissions-path "/web/admin/permissions")

(defn- view-path [base n]
  (str base "/view?" (table-ui/encode-query-params {"name" (group/permission-str n)})))

(defn role-path [role-name] (view-path roles-path role-name))

(defn permission-path [permission] (view-path permissions-path permission))

(def groups-path "/web/admin/groups")

(defn group-path [group-id] (str groups-path "/" group-id))

(defn user-rights-path [user-id] (str "/web/users/" user-id "/rights"))

(defn page
  "`content` in the admin shell, `title` in its topbar."
  [title content opts]
  (shell/shell-page title [:div.entity-detail-page.rights-page {:class "space-y-4"} content]
                    (assoc opts :page-title title)))

(defn header
  "Breadcrumbs, `crumbs` [label href] pairs, the page's `actions` and `heading`."
  [crumbs heading & actions]
  [:div.page-header
   [:div.page-header-row
    [:div.page-breadcrumbs
     (interpose " / " (for [[label href] crumbs] (if href [:a {:href href} label] [:span label])))]
    (into [:div.page-header-actions {:class "flex flex-wrap items-center gap-2"}] actions)]
   [:h1.page-title heading]])

(defn notice [{:keys [error]}]
  (when error [:div {:class "alert alert-error" :role "alert"} error]))

(defn section
  "`content` in a card under `title`. A map first is the section's attributes."
  [title & content]
  (let [[attrs content] (if (map? (first content)) [(first content) (rest content)] [nil content])]
    [:section.form-card attrs
     [:div.form-section
      [:div.form-section-header [:h2.form-section-title title]]
      (into [:div.form-section-body {:class "space-y-3"}] content)]]))

(defn- table-of [class headers rows]
  (ui/table-wrapper
   [:table {:class class}
    [:thead (into [:tr] (map (fn [h] [:th h]) headers))]
    (into [:tbody] rows)]))

(defn table
  "A table of `rows` under `headers`."
  [headers rows]
  (table-of "table rights-table" headers rows))

(defn action-table
  "A table whose last column holds each row's actions, as the admin's lists have it."
  [headers rows]
  (table-of "data-table table" headers rows))

(defn- code [x] [:code (group/permission-str x)])

;; =============================================================================
;; Scopes
;; =============================================================================

(defn- values-str [values]
  (str/join ", " (sort (map str values))))

(defn entity-lookup-url
  "Where the admin answers lookups of `entity`'s rows: by text, or one by ?id=."
  [entity]
  (str "/web/admin/" (name entity) "/lookup"))

(defn- scope-value
  "Fixed value `v`, replaced by its record's title when key's admin entity is
   `entity`: the admin's lookup answers it once the page has loaded."
  [entity v]
  [:strong.scope-value
   (when entity
     {:hx-get     (str (entity-lookup-url entity) "?" (table-ui/encode-query-params {"id" (str v)}))
      :hx-trigger "load"
      :hx-swap    "innerHTML"
      ;; Unanswered, the id stays: no error toast for that
      :data-quiet-failure true})
   (str v)])

(defn scope-view
  "A stored scope as text. `info`: {:labels {key label} :entities {key entity}
   :sources {source label} :resolved {[key source] values-or-:error}}, the
   last when the scope is a user's."
  [scope {:keys [labels entities sources resolved]}]
  (if (empty? scope)
    [:span.scope-everywhere [:t :user.rights/scope-everywhere]]
    (into [:span.scope]
          (interpose
           "; "
           (for [[k v] (sort-by key scope)
                 :let [label (or (get labels k) (name k))]]
             (if (scope/source? v)
               (let [r (get resolved [k v] ::none)]
                 [:span.scope-part
                  label ": " [:t :user.rights/scope-from-source {:source (get sources v (group/permission-str v))}]
                  (cond
                    (= ::none r)  nil
                    (= :error r)  (list " " [:span.scope-unresolved [:t :user.rights/scope-resolve-failed]])
                    (empty? r)    (list " " [:span.scope-unresolved [:t :user.rights/scope-resolved-none]])
                    :else         (list " = " [:strong.scope-value (values-str r)]))])
               [:span.scope-part label ": " (scope-value (get entities k) v)]))))))

;; =============================================================================
;; Permission checklist
;; =============================================================================

(defn permission-groups
  "`registry` permissions {p {:label :module}} by module, each
   [module-name [[p label checked?] ..]], sorted; `held` permissions the
   registry lacks come last under nil."
  [registry held]
  (let [held     (set held)
        declared (->> registry
                      (group-by (fn [[p {:keys [module]}]] (if module (name module) (namespace p))))
                      (sort-by key)
                      (mapv (fn [[m ps]]
                              [m (->> ps
                                      (sort-by (comp str key))
                                      (mapv (fn [[p {:keys [label]}]] [p label (contains? held p)])))])))
        unknown  (sort-by str (remove #(contains? registry %) held))]
    (cond-> declared
      (seq unknown) (conj [nil (mapv (fn [p] [p nil true]) unknown)]))))

(defn- checklist [registry held disabled?]
  [:div.permission-checklist {:class "space-y-3"}
   (for [[module ps] (permission-groups registry held)]
     [:fieldset.fieldset.permission-module
      [:legend.fieldset-legend (or module [:t :user.roles/undeclared-permissions])]
      ;; The label wraps its checkbox, so no id is needed (one made from the
      ;; name could collide)
      (for [[p label checked?] ps]
        [:label.permission-option {:class "flex items-center gap-2"}
         [:input {:type "checkbox" :class "checkbox checkbox-sm" :name "permissions"
                  :value (group/permission-str p) :checked checked? :disabled disabled?}]
         (code p)
         (when label [:span.permission-label label])])])])

;; =============================================================================
;; Holders
;; =============================================================================

(defn- holders-sections
  "Who holds a role or permission: the groups it is assigned to and their
   users. `holders` as `role-holders` gives them."
  [holders scope-info]
  (list
   (section [:t :user.roles/groups-heading]
            (if (seq holders)
              (table [[:t :user.groups/field-name] [:t :user.groups/field-role] [:t :user.groups/field-scope]]
                     (for [{:keys [group-id group-name role scope]} holders]
                       [:tr [:td [:a {:href (group-path group-id)} group-name]]
                        [:td [:a {:href (role-path role)} (code role)]]
                        [:td (scope-view scope scope-info)]]))
              [:p.empty-state [:t :user.roles/no-holders]]))
   (section [:t :user.roles/users-heading]
            [:p [:t :user.roles/users-provider-note]]
            (let [rows (for [{:keys [group-id group-name role scope everyone? members]} holders
                             m (if everyone? [::everyone] members)]
                         [:tr
                          [:td (if (= ::everyone m)
                                 [:em [:t :user.roles/every-user]]
                                 [:a {:href (user-rights-path (:id m))} (:email m)])]
                          [:td [:a {:href (group-path group-id)} group-name]]
                          [:td (code role)]
                          [:td (scope-view scope scope-info)]])]
              (if (seq rows)
                (table [[:t :user.rights/column-user] [:t :user.rights/column-group]
                        [:t :user.groups/field-role] [:t :user.groups/field-scope]]
                       rows)
                [:p.empty-state [:t :user.roles/no-holders]])))))

;; =============================================================================
;; Roles
;; =============================================================================

(defn- source-badge [source]
  (if (= :code source)
    (ui/badge [:t :user.roles/source-code] {:variant :neutral :class "role-locked gap-1"
                                            :icon (icons/icon :lock {:size 12})})
    (ui/badge [:t :user.roles/source-managed] {:variant :info})))

(defn- role-fields [registry form]
  (list
   [:label {:for "role-description"} [:t :user.groups/field-description]]
   [:input#role-description {:type "text" :name "description" :maxlength 255 :class "input"
                             :value (:description form)}]
   [:p [:t :user.roles/permissions-hint]]
   (checklist registry (:permissions form) false)))

(defn roles-page
  "Code roles, locked, and managed ones, with a form to compose a new one from
   the registry's permissions."
  [roles registry opts]
  (page
   [:t :user.roles/page-title]
   (list
    (header [[[:t :user.roles/page-title]]] [:t :user.roles/page-title])
    [:p [:t :user.roles/description]]
    (notice opts)
    (table [[:t :user.groups/field-role] [:t :user.groups/field-description]
            [:t :user.roles/column-permissions] [:t :user.roles/column-source]]
           (for [{:keys [name description permissions source]} roles]
             [:tr {:class (str "role-row role-" (clojure.core/name (or source :managed)))}
              [:td [:a {:href (role-path name)} (code name)]]
              [:td description]
              [:td (count permissions)]
              [:td (source-badge source)]]))
    [:form.role-create-form {:method "post" :action roles-path}
     (csrf/hidden-field)
     (section [:t :user.roles/create-heading]
              [:label {:for "role-name"} [:t :user.groups/field-name]]
              [:input#role-name {:type "text" :name "name" :required true :maxlength 255 :class "input"
                                 :placeholder "orders/clerk" :value (get-in opts [:form :name])}]
              (role-fields registry (:form opts))
              [:button.button.primary {:type "submit"} [:t :user.roles/button-create]])])
   opts))

(defn role-page
  "One role: a code role read-only with a form to duplicate it, a managed one
   editable; then who holds it."
  [{:keys [name description permissions source] :as role} registry holders opts]
  (let [code? (= :code source)
        n     (group/permission-str name)]
    (page
     [:t :user.roles/role-page-title {:name n}]
     (list
      (header [[[:t :user.roles/page-title] roles-path] [n]] (code name)
              (when-not code?
                [:form.role-delete-form {:method "post" :action (str roles-path "/delete")}
                 (csrf/hidden-field)
                 [:input {:type "hidden" :name "name" :value n}]
                 [:button.button.ghost.button-danger-text {:type "submit"} [:t :user.roles/button-delete]]]))
      [:div {:class "flex items-center gap-2"} (source-badge source)
       (when (:module role) [:span [:t :user.roles/from-module {:module (clojure.core/name (:module role))}]])]
      (notice opts)
      (if code?
        (list
         (section [:t :user.roles/permissions-heading]
                  (when description [:p description])
                  [:p [:t :user.roles/locked-note]]
                  (checklist (select-keys registry permissions) permissions true))
         [:form.role-duplicate-form {:method "post" :action (str roles-path "/duplicate")}
          (csrf/hidden-field)
          [:input {:type "hidden" :name "name" :value n}]
          (section [:t :user.roles/duplicate-heading]
                   [:p [:t :user.roles/duplicate-hint]]
                   [:label {:for "role-new-name"} [:t :user.roles/field-new-name]]
                   [:input#role-new-name {:type "text" :name "new-name" :required true :maxlength 255 :class "input"
                                          :value (get-in opts [:form :new-name])}]
                   [:button.button.primary {:type "submit"} [:t :user.roles/button-duplicate]])])
        [:form.role-edit-form {:method "post" :action (str roles-path "/save")}
         (csrf/hidden-field)
         [:input {:type "hidden" :name "name" :value n}]
         (section [:t :user.roles/permissions-heading]
                  (role-fields registry (merge role (:form opts)))
                  [:button.button.primary {:type "submit"} [:t :user.groups/button-save]])])
      (holders-sections holders (:scope-info opts)))
     opts)))

;; =============================================================================
;; Permissions
;; =============================================================================

(defn permissions-page
  "The registry by module, each permission with the roles holding it."
  [registry roles opts]
  (page
   [:t :user.permissions/page-title]
   (list
    (header [[[:t :user.permissions/page-title]]] [:t :user.permissions/page-title])
    [:p [:t :user.permissions/description]]
    (for [[module ps] (permission-groups registry nil)]
      (section module
               (table [[:t :user.permissions/column-permission] [:t :user.permissions/column-label]
                       [:t :user.permissions/column-roles]]
                      (for [[p label] ps]
                        [:tr
                         [:td [:a {:href (permission-path p)} (code p)]]
                         [:td label]
                         [:td (interpose " " (for [r roles :when (contains? (:permissions r) p)]
                                               [:a {:href (role-path (:name r))} (code (:name r))]))]])))))
   opts))

(defn permission-page
  "One permission: the roles holding it, and the groups and users holding those."
  [permission {:keys [label module]} roles holders opts]
  (let [p (group/permission-str permission)]
    (page
     [:t :user.permissions/permission-page-title {:name p}]
     (list
      (header [[[:t :user.permissions/page-title] permissions-path] [p]] (code permission))
      (when (or label module)
        [:p label (when module (list " · " [:t :user.roles/from-module {:module (name module)}]))])
      (section [:t :user.permissions/roles-heading]
               (if (seq roles)
                 (table [[:t :user.groups/field-role] [:t :user.roles/column-source]]
                        (for [{:keys [name source]} roles]
                          [:tr [:td [:a {:href (role-path name)} (code name)]] [:td (source-badge source)]]))
                 [:p.empty-state [:t :user.permissions/no-roles]]))
      (holders-sections holders (:scope-info opts)))
     opts)))

;; =============================================================================
;; A user's rights
;; =============================================================================

(def detail-tabs
  "This module's pages for a user, beside the admin's edit page. The admin's
   `:users` config names this var as its `:detail-tabs`; `{id}` is the user's."
  [{:key :sessions :label [:t :user/tab-sessions] :href "/web/users/{id}/sessions"}
   {:key :rights :label [:t :user.rights/tab] :href "/web/users/{id}/rights"}])

(defn user-tabs
  "A user's pages as tabs: the admin's edit page, then `detail-tabs`, the one
   whose :key is `active` marked."
  [user-id active]
  (ui/tabs (into [{:label [:t :admin/tab-details] :href (str "/web/admin/users/" user-id)}]
                 (for [{:keys [key label href]} detail-tabs]
                   {:label   label
                    :href    (str/replace href "{id}" (str user-id))
                    :active? (= active key)}))))

(defn- group-source-badge [source]
  (case source
    :everyone (ui/badge [:t :user.rights/source-everyone] {:variant :neutral})
    :provider (ui/badge [:t :user.rights/source-provider] {:variant :warning})
    (ui/badge [:t :user.rights/source-member] {:variant :info})))

(defn rights-page
  "`user`'s groups, each with where the membership comes from, and their
   effective permissions, each with the group, role and scope it comes from.
   `grants` as `user-grants` gives them; `(:scope-info opts)` as `scope-view`
   reads it. `(:platform-admin? opts)`: the user passes every check."
  [user groups grants opts]
  (let [n (or (not-empty (:name user)) (:email user))]
    (page
     [:t :user.rights/page-title {:name n}]
     (list
      (header [[[:t :user/page-users-title] "/web/admin/users"]
               [n (str "/web/admin/users/" (:id user))]
               [[:t :user.rights/tab]]]
              n)
      (user-tabs (:id user) :rights)
      (when (:platform-admin? opts)
        [:div {:class "alert alert-info" :role "status"} [:t :user.rights/platform-admin-note]])
      (section [:t :user.rights/groups-heading]
               (if (seq groups)
                 (table [[:t :user.rights/column-group] [:t :user.roles/column-source]]
                        (for [{:keys [id name source]} groups]
                          [:tr.rights-group [:td [:a {:href (group-path id)} name]] [:td (group-source-badge source)]]))
                 [:p.empty-state [:t :user.rights/groups-empty]]))
      (section [:t :user.rights/permissions-heading]
               (if (seq grants)
                 (table [[:t :user.permissions/column-permission] [:t :user.groups/field-role]
                         [:t :user.rights/column-group] [:t :user.groups/field-scope]]
                        (for [{:keys [permission role group-id group-name scope]}
                              (sort-by (juxt (comp str :permission) :group-name (comp str :role)) grants)]
                          [:tr.rights-permission
                           [:td [:a {:href (permission-path permission)} (code permission)]]
                           [:td [:a {:href (role-path role)} (code role)]]
                           [:td [:a {:href (group-path group-id)} group-name]]
                           [:td (scope-view scope (:scope-info opts))]]))
                 [:p.empty-state [:t :user.rights/permissions-empty]])))
     opts)))
