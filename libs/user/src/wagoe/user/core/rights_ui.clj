(ns wagoe.user.core.rights-ui
  "Admin pages answering who may do what (ADR-041 §7): roles, permissions and
   a user's rights. Pure Hiccup in the admin shell; plain forms that post and
   redirect."
  (:require [clojure.string :as str]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.core.utils.text :as text]
            [wagoe.platform.core.scope :as scope]
            [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.htmx :as htmx]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.modal :as modal]
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

(defn explain-path
  "The explain page for `user-id`, asking `permission` on `record`: {scope-key
   value}, or `:everywhere` for a route without a scope."
  [user-id permission record]
  (str "/web/users/" user-id "/explain"
       (when permission
         (str "?" (table-ui/encode-query-params
                   (into {"permission" (group/permission-str permission)}
                         (if (= :everywhere record)
                           {"everywhere" "true"}
                           (for [[k v] record] [(str "scope." (group/permission-str k)) (str v)]))))))))

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

(defn- labelled
  "`row`'s [:td]s labelled with the header each sits under: a narrow screen
   hides the thead and heads each cell with its label. A cell's own label stays."
  [headers row]
  (let [[tag & more]  row
        [attrs cells] (if (map? (first more)) [(first more) (rest more)] [nil more])
        td?           #(and (vector? %) (keyword? (first %)) (str/starts-with? (name (first %)) "td"))]
    (into (cond-> [tag] attrs (conj attrs))
          (map (fn [cell h]
                 (if (and (td? cell) (some? h) (not= "" h))
                   (let [[_ own] cell]
                     (htmx/merge-attrs cell (merge {:data-label h} (when (map? own) own))))
                   cell))
               cells (concat headers (repeat nil))))))

(defn- table-of [class headers rows]
  (ui/table-wrapper
   [:table {:class class}
    [:thead (into [:tr] (map (fn [h] [:th h]) headers))]
    (into [:tbody] (map #(if (vector? %) (labelled headers %) %)) rows)]))

(defn table
  "A table of `rows` under `headers`."
  [headers rows]
  (table-of "data-table table rights-table" headers rows))

(defn action-table
  "A table whose last column holds each row's actions, as the admin's lists have it."
  [headers rows]
  (table-of "data-table table" headers rows))

(defn- code [x] [:code (group/permission-str x)])

;; =============================================================================
;; Lists: groups, roles and permissions in the shared data-table
;; =============================================================================

(defn module-of
  "The module `x` belongs to: its declared `module`, else its name's namespace."
  [x module]
  (cond module       (name module)
        (keyword? x) (namespace x)
        :else        (namespace (keyword (group/permission-str x)))))

(defn permission-module
  "The module `registry` places permission `p` in."
  [registry p]
  (module-of p (get-in registry [p :module])))

(defn- list-query
  "`table-query` sorted by `spec`'s default unless by one of its sort keys."
  [{:keys [sort-keys default-sort]} table-query]
  (let [tq (select-keys table-query [:sort :dir :search])]
    (if (contains? sort-keys (:sort tq))
      (merge {:dir :asc} tq)
      (assoc tq :sort default-sort :dir :asc))))

(defn list-row
  "A list row opening `href`: its `cells` under the `columns` labels, the
   title link first, a chevron last."
  [href attrs columns cells]
  (table-ui/list-row {:href href :attrs attrs :columns columns :cells cells
                      :open-label [:t :user.lists/button-open]}))

(defn- grouped [items group-of row n-cols]
  (table-ui/grouped-rows items {:group-of group-of :row row :n-cols n-cols
                                :none-label [:t :user.lists/group-none]}))

(defn- list-parts
  "`items` searched and sorted for `spec`: the table and the count."
  [{:keys [columns search-text sort-keys group-of row] :as spec} items table-query]
  (let [tq    (list-query spec table-query)
        items (table-ui/query-rows items tq {:search-text search-text :sort-keys sort-keys})
        rows  (if group-of
                (grouped items group-of row (inc (count columns)))
                (map row items))]
    {:table (table-ui/list-body {:id             (:id spec)
                                 :url            (:url spec)
                                 :columns        columns
                                 :rows           rows
                                 :table-query    tq
                                 :empty          (:empty-text spec)
                                 :empty-filtered [:t :user.lists/no-matches {:search (:search tq)}]
                                 :clear-label    [:t :admin/button-clear-search]})
     :badge (table-ui/count-badge (str (:id spec) "-count") (count items) (:count-label spec))}))

(defn list-fragment
  "What a list's own search and sort get back: the table, the count out of band."
  [spec items table-query]
  (let [{:keys [table badge]} (list-parts spec items table-query)]
    (list table (htmx/oob badge))))

(defn list-view
  "A list as the admin's entity lists have it: count, search and `actions`
   over the sortable table."
  [{:keys [id url placeholder] :as spec} items table-query & actions]
  (let [{:keys [table badge]} (list-parts spec items table-query)]
    (list
     [:div.table-toolbar-container
      [:div.toolbar-row-actions
       ;; Live here and not on the count, which a swap replaces
       [:div.record-meta {:aria-live "polite"} badge]
       (table-ui/toolbar-search {:id          (str id "-search")
                                 :name        "search"
                                 :value       (:search table-query)
                                 :placeholder placeholder
                                 :url         url
                                 :hx-target   (str "#" id)
                                 :hx-include  (str "#" id " .table-state input")})
       (into [:div.toolbar-actions] actions)]]
     table)))

(defn list-page
  "A list in the admin shell, `title` in its topbar, `intro` above it."
  [title intro content opts]
  (shell/shell-page title
                    [:div.entity-list-page.rights-list-page {:class "space-y-4"}
                     [:p.list-intro intro]
                     content]
                    (assoc opts :page-title title)))

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

(defn- resolution
  "What user source values `r` resolved to, as `resolved` holds them, between
   `open` and `close`; nil when not resolved here."
  [r open close]
  (cond
    (= ::none r) nil
    (= :error r) (list " " [:span.scope-unresolved [:t :user.rights/scope-resolve-failed]])
    (empty? r)   (list " " [:span.scope-unresolved [:t :user.rights/scope-resolved-none]])
    :else        (list open [:strong.scope-value (values-str r)] close)))

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
               [:span.scope-part
                label ": " [:t :user.rights/scope-from-source {:source (get sources v (group/permission-str v))}]
                (resolution (get resolved [k v] ::none) " = " nil)]
               [:span.scope-part label ": " (scope-value (get entities k) v)]))))))

;; =============================================================================
;; Assignments as sentences (BOU-854)
;; =============================================================================

(def level-labels
  "A code role's level, as `role-level` names it, as a badge says it."
  {"viewer" :user.access/level-viewer "editor" :user.access/level-editor "manager" :user.access/level-manager})

(def ^:private level-verbs
  {"viewer" :user.access/may-viewer "editor" :user.access/may-editor "manager" :user.access/may-manager})

(defn role-level
  "\"viewer\", \"editor\" or \"manager\" for one of a module's code roles,
   `role` as `list-roles` gives it; nil for any other role."
  [{:keys [name source]}]
  (when (and (= :code source) (keyword? name) (namespace name))
    ((set (keys level-labels)) (clojure.core/name name))))

(defn- scope-phrase
  "\"everywhere\", or where `scope` holds: each fixed value by its record's
   title, each user source by its label and, when resolved, its values."
  [scope {:keys [labels entities sources resolved]}]
  (if (empty? scope)
    [:t :user.access/everywhere]
    (interpose
     (list " " [:t :user.access/and] " ")
     (for [[k v] (sort-by key scope)
           :let [label (or (get labels k) (name k))]]
       (if (scope/source? v)
         (list [:t :user.access/within-source {:source (get sources v (group/permission-str v))}]
               (resolution (get resolved [k v] ::none) " (" ")"))
         (list [:t :user.access/within {:key label}] " " (scope-value (get entities k) v)))))))

(defn assignment-sentence
  "`assignment`, {:group-id :group-name :role :scope}, as a sentence: \"Sales
   may edit orders within Department Noord\". `roles`: {role-name role} as
   `list-roles` gives them, to read a code role's level."
  [{:keys [group-id group-name role scope]} roles scope-info]
  (let [level (role-level (get roles role))]
    [:span.access-sentence
     (if group-id [:a {:href (group-path group-id)} group-name] group-name)
     " "
     (if level
       [:a {:href (role-path role)} [:t (level-verbs level) {:module (namespace role)}]]
       (list [:t :user.access/holds-role] " " [:a {:href (role-path role)} (code role)]))
     " "
     (scope-phrase scope scope-info)]))

(defn role-index
  "`roles`, as `list-roles` gives them, by name."
  [roles]
  (into {} (map (juxt :name identity)) roles))

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
                      (group-by (fn [[p {:keys [module]}]] (module-of p module)))
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
    (ui/tone-badge [:t :user.roles/source-code] :neutral "role-locked")
    (ui/tone-badge [:t :user.roles/source-managed] :info)))

(defn- role-fields [registry form]
  (list
   [:label {:for "role-description"} [:t :user.groups/field-description]]
   [:input#role-description {:type "text" :name "description" :maxlength 255 :class "input"
                             :value (:description form)}]
   [:p [:t :user.roles/permissions-hint]]
   (checklist registry (:permissions form) false)))

(def roles-list
  "The roles list: by module, like the permissions."
  (let [columns [{:field :name :label [:t :user.groups/field-role] :class "c-title"}
                 {:field :description :label [:t :user.groups/field-description]}
                 {:field :permissions :label [:t :user.roles/column-permissions]}
                 {:field :source :label [:t :user.roles/column-source]}]]
    {:id           "roles-table-container"
     :url          roles-path
     :placeholder  [:t :user.roles/search-placeholder]
     :empty-text   [:t :user.roles/empty]
     :count-label  :user.roles/count
     :columns      columns
     :default-sort :name
     :sort-keys    {:name        (comp group/permission-str :name)
                    :description #(some-> (:description %) text/lower-case)
                    :permissions (comp count :permissions)
                    :source      #(name (or (:source %) :managed))}
     :search-text  #(str (group/permission-str (:name %)) " " (:description %))
     :group-of     #(module-of (:name %) (:module %))
     :row          (fn [{:keys [name description permissions source]}]
                     (list-row (role-path name)
                               {:class (str "role-row role-" (clojure.core/name (or source :managed)))}
                               columns
                               [[:a {:href (role-path name)} (code name)]
                                description
                                (count permissions)
                                (source-badge source)]))}))

(defn create-modal
  "The form a list's New button opens; open at once when it was refused."
  [id title opts form]
  (modal/modal {:id id :title title :open? (some? (:error opts)) :class "list-create-modal"}
               (notice opts)
               form))

(defn roles-page
  "Code roles, locked, and managed ones, by module, with a form to compose a
   new one from the registry's permissions. `(:table-query opts)` searches
   and sorts them."
  [roles registry opts]
  (list-page
   [:t :user.roles/page-title]
   [:t :user.roles/description]
   (list
    (list-view roles-list roles (:table-query opts)
               [:button.button.primary (merge (modal/open-attrs "role-create-modal") {:class "gap-2"})
                (icons/icon :plus {:size 18}) [:t :user.roles/create-heading]])
    (create-modal "role-create-modal" [:t :user.roles/create-heading] opts
                  [:form.role-create-form {:method "post" :action roles-path :class "space-y-3"}
                   (csrf/hidden-field)
                   [:label {:for "role-name"} [:t :user.groups/field-name]]
                   [:input#role-name {:type "text" :name "name" :required true :maxlength 255 :class "input w-full"
                                      :placeholder "orders/clerk" :value (get-in opts [:form :name])}]
                   (role-fields registry (:form opts))
                   [:button.button.primary {:type "submit"} [:t :user.roles/button-create]]]))
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

(def permissions-list
  "The permissions list, by module, each permission with the roles holding
   it. Its items are {:permission :label :module :roles}; see
   `permission-items`."
  (let [columns [{:field :permission :label [:t :user.permissions/column-permission] :class "c-title"}
                 {:field :label :label [:t :user.permissions/column-label]}
                 {:field :roles :label [:t :user.permissions/column-roles] :sortable? false}]]
    {:id           "permissions-table-container"
     :url          permissions-path
     :placeholder  [:t :user.permissions/search-placeholder]
     :empty-text   [:t :user.permissions/empty]
     :count-label  :user.permissions/count
     :columns      columns
     :default-sort :permission
     :sort-keys    {:permission (comp group/permission-str :permission)
                    :label      #(some-> (:label %) text/lower-case)}
     :search-text  (fn [{:keys [permission label roles]}]
                     (str/join " " (concat [(group/permission-str permission) label]
                                           (map group/permission-str roles))))
     :group-of     #(module-of (:permission %) (:module %))
     :row          (fn [{:keys [permission label roles]}]
                     (list-row (permission-path permission) {:class "permission-row"} columns
                               [[:a {:href (permission-path permission)} (code permission)]
                                label
                                (interpose " " (for [r roles] [:a {:href (role-path r)} (code r)]))]))}))

(defn permission-items
  "The registry's permissions as `permissions-list` lists them."
  [registry roles]
  (let [holders (reduce (fn [m {:keys [name permissions]}]
                          (reduce #(update %1 %2 (fnil conj []) name) m permissions))
                        {} (sort-by (comp group/permission-str :name) roles))]
    (for [[p {:keys [label module]}] registry]
      {:permission p :label label :module module :roles (get holders p [])})))

(defn permissions-page
  "The registry by module, each permission with the roles holding it.
   `(:table-query opts)` searches and sorts them."
  [registry roles opts]
  (list-page
   [:t :user.permissions/page-title]
   [:t :user.permissions/description]
   (list-view permissions-list (permission-items registry roles) (:table-query opts))
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
    :everyone (ui/tone-badge [:t :user.rights/source-everyone] :neutral)
    :provider (ui/tone-badge [:t :user.rights/source-provider] :warning)
    (ui/tone-badge [:t :user.rights/source-member] :info)))

(def ^:private rights-columns
  [{:field :permissions :label [:t :user.roles/column-permissions]}
   {:field :assignment :label [:t :user.access/column-assignment]}])

(defn- rights-rows
  "`grants` by module: a row per role a group gives, with the permissions it
   gives in that module, so the group shows once per role."
  [grants registry roles scope-info]
  (let [role-grants     (->> grants
                             (group-by (juxt #(permission-module registry (:permission %)) :role :group-id :group-name :scope))
                             (map (fn [[[m role group-id group-name scope] gs]]
                                    {:module m :role role :group-id group-id :group-name group-name :scope scope
                                     :permissions (sort-by group/permission-str (distinct (map :permission gs)))}))
                             (sort-by (juxt (comp group/permission-str :role) (comp str :group-name))))
        row             (fn [{:keys [permissions] :as a}]
                          (into [:tr.rights-permission]
                                (table-ui/column-cells
                                 rights-columns
                                 [(interpose " " (for [p permissions] [:a {:href (permission-path p)} (code p)]))
                                  (assignment-sentence a roles scope-info)])))]
    (grouped role-grants :module row (count rights-columns))))

(defn rights-page
  "`user`'s groups, each with where the membership comes from, and their
   effective permissions, each with the group, role and scope it comes from.
   `grants` as `user-grants` gives them; `(:scope-info opts)` as `scope-view`
   reads it; `(:registry opts)`, the declared permissions, places each in its
   module; `(:roles opts)`, as `role-index` gives them, words each assignment.
   `(:platform-admin? opts)`: the user passes every check."
  [user groups grants opts]
  (let [n (or (not-empty (:name user)) (:email user))]
    (page
     [:t :user.rights/page-title {:name n}]
     (list
      (header [[[:t :user/page-users-title] "/web/admin/users"]
               [n (str "/web/admin/users/" (:id user))]
               [[:t :user.rights/tab]]]
              n
              [:a {:href (explain-path (:id user) nil nil) :class "btn btn-sm"} [:t :user.explain/open]])
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
                 (table (map :label rights-columns)
                        (rights-rows grants (:registry opts) (:roles opts) (:scope-info opts)))
                 [:p.empty-state [:t :user.rights/permissions-empty]])))
     opts)))

;; =============================================================================
;; Explain: why may (or may not) a user do this (BOU-851)
;; =============================================================================

(defn- membership-note [{:keys [membership group-name]}]
  (case membership
    :everyone [:t :user.explain/via-everyone]
    :provider [:t :user.explain/via-provider {:group group-name}]
    :member   [:t :user.explain/via-member]
    nil))

(defn- right-scope
  "A right's stored scope, each source shown with what it resolved to."
  [{:keys [stored-scope scope]} unresolved? scope-info]
  (scope-view stored-scope
              (assoc scope-info :resolved
                     (into {} (for [[k v] stored-scope :when (scope/source? v)]
                                [[k v] (if unresolved? #{} (get scope k #{}))])))))

(defn- mismatch-text [by-key labels]
  (interpose "; "
             (for [[k {:keys [allowed value]}] (sort-by key by-key)
                   :let [label (or (get labels k) (name k))]]
               (if (nil? value)
                 [:t :user.explain/key-unchecked {:key label}]
                 [:t :user.explain/key-outside {:key label :value (str value) :allowed (values-str allowed)}]))))

(defn- rights-table
  "`rows`, rights or `:misses`, with why each misses when `why` is the record asked."
  [rows scope-info why]
  (table (cond-> [[:t :user.rights/column-group] [:t :user.explain/column-membership]
                  [:t :user.groups/field-role] [:t :user.groups/field-scope]]
           why (conj [:t :user.explain/column-why]))
         (for [{:keys [right reason] :as row} rows
               :let [r (or right row)]]
           (cond-> [:tr.explain-path
                    [:td [:a {:href (group-path (:group-id r))} (:group-name r)]]
                    [:td (group-source-badge (:membership r)) " " (membership-note r)]
                    [:td [:a {:href (role-path (:role r))} (code (:role r))]]
                    [:td (right-scope r (= :unresolved reason) scope-info)]]
             why (conj [:td (cond (= :unresolved reason) [:t :user.explain/unresolved]
                                  (= :everywhere why)    [:t :user.explain/not-everywhere]
                                  :else                  (mismatch-text (:keys row) (:labels scope-info)))])))))

(defn- explain-form [user-id registry scope-keys {:keys [permission record]}]
  [:form.explain-form {:method "get" :action (explain-path user-id nil nil) :class "space-y-3"}
   [:label {:for "explain-permission"} [:t :user.explain/field-permission]]
   [:select#explain-permission {:name "permission" :required true :class "select"}
    [:option {:value ""} [:t :user.explain/pick-permission]]
    (for [p (sort-by group/permission-str (keys registry))
          :let [v (group/permission-str p)]]
      [:option {:value v :selected (= p permission)} v])]
   [:p [:t :user.explain/record-hint]]
   (for [[k label] scope-keys
         :let [id (str "explain-scope-" (name k))]]
     (list [:label {:for id} label]
           [:input {:id id :type "text" :class "input" :name (str "scope." (group/permission-str k))
                    :value (when (map? record) (some-> (get record k) str))}]))
   [:label.explain-everywhere {:class "flex items-center gap-2"}
    [:input {:type "checkbox" :class "checkbox checkbox-sm" :name "everywhere" :value "true"
             :checked (= :everywhere record)}]
    [:t :user.explain/field-everywhere]]
   [:button {:type "submit" :class "btn btn-primary"} [:t :user.explain/submit]]])

(defn- verdict [{:keys [allowed? via]} record]
  (let [asked (cond (= :everywhere record) :everywhere (seq record) :record :else :anywhere)]
    [:div.explain-verdict {:class (if allowed? "alert alert-success" "alert alert-error") :role "status"}
     [:t (cond (= :platform-admin via) :user.explain/yes-platform-admin
               allowed?                ({:everywhere :user.explain/yes-everywhere :record :user.explain/yes-record
                                         :anywhere   :user.explain/yes-anywhere} asked)
               :else                   ({:everywhere :user.explain/no-everywhere :record :user.explain/no-record
                                         :anywhere   :user.explain/no} asked))]]))

(defn explain-page
  "Why `user` may or may not use the permission asked, `result` as
   `explain-access` gives it, nil before one is asked. `opts`: `:asked`
   {:permission :record}, `:registry` the permissions to pick from,
   `:scope-info` as `scope-view` reads it."
  [user result opts]
  (let [n          (or (not-empty (:name user)) (:email user))
        scope-info (:scope-info opts)
        asked      (:asked opts)]
    (page
     [:t :user.explain/page-title {:name n}]
     (list
      (header [[[:t :user/page-users-title] "/web/admin/users"]
               [n (str "/web/admin/users/" (:id user))]
               [[:t :user.rights/tab] (user-rights-path (:id user))]
               [[:t :user.explain/crumb]]]
              n)
      (user-tabs (:id user) :rights)
      (section [:t :user.explain/heading]
               (explain-form (:id user) (:registry opts) (sort-by key (:labels scope-info)) asked))
      (when result
        (list
         (verdict result (:record asked))
         (when (seq (:paths result))
           (section [:t :user.explain/paths-heading] (rights-table (:paths result) scope-info false)))
         (when (seq (:misses result))
           (section [:t :user.explain/misses-heading] (rights-table (:misses result) scope-info (or (:record asked) true))))
         (when-not (:allowed? result)
           (section [:t :user.explain/alternatives-heading]
                    [:p [:t :user.explain/alternatives-hint]]
                    (if (seq (:alternatives result))
                      (table [[:t :user.rights/column-group] [:t :user.groups/field-role] [:t :user.groups/field-scope]]
                             (for [{:keys [group-id group-name role scope everyone?]} (:alternatives result)]
                               [:tr.explain-alternative
                                [:td [:a {:href (group-path group-id)} group-name]
                                 (when everyone? (list " " (group-source-badge :everyone)))]
                                [:td [:a {:href (role-path role)} (code role)]]
                                [:td (scope-view scope scope-info)]]))
                      [:p.empty-state [:t :user.explain/no-alternatives]]))))))
     opts)))

;; =============================================================================
;; Change preview (BOU-852)
;; =============================================================================

(defn- effective-scope-view
  "A resolved scope, {key #{value}}, as text."
  [scope {:keys [labels]}]
  (if (empty? scope)
    [:span.scope-everywhere [:t :user.rights/scope-everywhere]]
    (into [:span.scope]
          (interpose "; " (for [[k vs] (sort-by key scope)]
                            [:span.scope-part (or (get labels k) (name k)) ": "
                             [:strong.scope-value (values-str vs)]])))))

(defn- hidden-fields
  "`params`, form params, as hidden inputs: a vector is one per value."
  [params]
  (for [[k v] (sort-by key params)
        v     (if (sequential? v) v [v])]
    [:input {:type "hidden" :name k :value (str v)}]))

(defn preview-page
  "What a change does to effective rights before it is made, `preview` as
   `preview-change` gives it, with a form posting `(:fields opts)` to
   `(:action opts)` again, confirmed. `(:back opts)`: where cancel goes."
  [{:keys [user permissions affected affected-count everyone?]} {:keys [action fields back scope-info] :as opts}]
  (page
   [:t :user.preview/page-title]
   (list
    (header [[[:t :user.preview/crumb-back] back] [[:t :user.preview/crumb]]] [:t :user.preview/heading])
    (when user
      (section [:t :user.preview/user-heading {:email (get-in user [:user :email])}]
               (if (or (seq (:added user)) (seq (:removed user)))
                 (table [[:t :user.permissions/column-permission] [:t :user.groups/field-scope] [:t :user.preview/column-change]]
                        (for [[k badge tone] [[:added :user.preview/gains :success] [:removed :user.preview/loses :danger]]
                              {:keys [permission scope]} (get user k)]
                          [:tr {:class (str "preview-" (name k))}
                           [:td [:a {:href (permission-path permission)} (code permission)]]
                           [:td (effective-scope-view scope scope-info)]
                           [:td (ui/tone-badge [:t badge] tone)]]))
                 [:p.preview-unchanged [:t :user.preview/user-unchanged]])))
    (section [:t :user.preview/others-heading]
             (when everyone? [:p [:t :user.preview/everyone-note]])
             (if (pos? affected-count)
               (list [:p.preview-affected-count [:t :user.preview/also-affects {:count affected-count} affected-count]]
                     [:ul.preview-affected
                      (for [{:keys [id email]} affected]
                        [:li [:a {:href (user-rights-path id)} email]])]
                     (when (> affected-count (count affected))
                       [:p [:t :user.preview/more {:count (- affected-count (count affected))}]]))
               [:p.empty-state [:t :user.preview/no-others]])
             (when (seq permissions)
               (table [[:t :user.permissions/column-permission] [:t :user.preview/column-gain] [:t :user.preview/column-lose]]
                      (for [{:keys [permission added removed]} permissions]
                        [:tr.preview-permission
                         [:td [:a {:href (permission-path permission)} (code permission)]]
                         [:td added]
                         [:td removed]]))))
    [:form.preview-confirm-form {:method "post" :action action :class "flex flex-wrap items-center gap-2"}
     (csrf/hidden-field)
     (hidden-fields fields)
     [:input {:type "hidden" :name "confirm" :value "true"}]
     [:button.button.primary {:type "submit"} [:t :user.preview/button-confirm]]
     [:a.button.secondary {:href back} [:t :common/button-cancel]]])
   opts))
