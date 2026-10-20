(ns wagoe.user.core.group-ui
  "Admin pages for groups, their members and their roles. Pure Hiccup; plain
   forms that post and redirect."
  (:require [wagoe.platform.core.csrf :as csrf]
            [wagoe.shared.ui.core.combobox :as combobox]
            [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.shell :as shell]
            [wagoe.user.core.group :as group]
            [wagoe.user.core.rights-ui :as rights-ui]))

(def base-path "/web/admin/groups")

(defn group-path [group-id] (str base-path "/" group-id))

(defn- member-options-path
  "Where the member combobox of a group's page reads its options."
  [group-id]
  (str (group-path group-id) "/members/options"))

(defn scope-lookup-field
  "The form field holding a fixed value of an assignment's scope key `k`."
  [k]
  (keyword (str "scope-" (name k))))

(defn scope-source-field
  "The form field naming the user source an assignment's scope key `k` takes
   its value from instead."
  [k]
  (keyword (str "scope-source-" (name k))))

(defn- page [title content opts]
  (shell/shell-page title [:div.entity-detail-page.user-groups-page {:class "space-y-4"} content]
                    (assoc opts :page-title title)))

(defn- remove-button [action]
  [:form {:method "post" :action action}
   (csrf/hidden-field)
   [:button.button.secondary.small {:type "submit"} [:t :user.groups/button-remove]]])

(defn- group-fields [group]
  (list
   [:label {:for "group-name"} [:t :user.groups/field-name]]
   [:input#group-name {:type "text" :name "name" :required true :maxlength 255 :class "input"
                       :value (:name group)}]
   [:label {:for "group-description"} [:t :user.groups/field-description]]
   [:input#group-description {:type "text" :name "description" :maxlength 255 :class "input"
                              :value (:description group)}]))

(defn groups-page
  "Every group, and a form to create one."
  [groups opts]
  (page
   [:t :user.groups/page-title]
   (list
    (rights-ui/header [[[:t :user.groups/breadcrumb]]] [:t :user.groups/heading])
    [:p [:t :user.groups/description]]
    (rights-ui/notice opts)
    (if (seq groups)
      (rights-ui/table [[:t :user.groups/field-name] [:t :user.groups/field-description]
                        [:t :user.groups/column-members] [:t :user.groups/column-roles]]
                       (for [{:keys [id name description member-count role-count]} groups]
                         [:tr
                          [:td [:a {:href (group-path id)} name]]
                          [:td description]
                          [:td (when-not (group/everyone? id) member-count)]
                          [:td role-count]]))
      [:p.empty-state [:t :user.groups/empty]])
    [:form.group-create-form {:method "post" :action base-path}
     (csrf/hidden-field)
     (rights-ui/section [:t :user.groups/create-heading]
                        (group-fields (:form opts))
                        [:button.button.primary {:type "submit"} [:t :user.groups/button-create]])])
   opts))

(defn- role-input [roles]
  [:select#group-role {:name "role" :required true :class "select"}
   (for [{:keys [name description]} roles]
     (let [v (group/permission-str name)] [:option {:value v :title description} v]))])

(defn- scope-key-input
  "A scope key's inputs: a fixed value, through a lookup when the key points at
   an admin entity, or a user source when the key takes one."
  [{:keys [key label url sources]}]
  (let [field  (scope-lookup-field key)
        source (scope-source-field key)]
    [:div.scope-key {:class "space-y-1"}
     [:label {:for (name field)} (or label (name key))]
     (if url
       (combobox/combobox field {:url (str url "?field=" (name field)) :placeholder [:t :user.groups/scope-placeholder]})
       [:input {:type "text" :id (name field) :name (name field) :class "input"}])
     (when (seq sources)
       [:select {:id (name source) :name (name source) :class "select"
                 :aria-label [:t :user.groups/scope-source-label {:key (or label (name key))}]}
        [:option {:value ""} [:t :user.groups/scope-fixed-value]]
        (for [[s s-label] sources]
          [:option {:value (group/permission-str s)} s-label])])]))

(defn- member-rows [members action]
  (for [m members]
    [:tr
     [:td [:a {:href (rights-ui/user-rights-path (:id m))} (:email m)]]
     [:td (:name m)]
     [:td.actions-cell (action m)]]))

(defn group-page
  "One group: its name, members and roles, with the forms that change them.
   `roles` are those it may be assigned, as `list-roles` gives them.
   `(:scope-keys opts)`, [{:key :label :url :sources [[source label]]}], are
   the declared scope keys, each chosen through a lookup when it has a :url;
   without any the scope is typed as EDN. `(:provider-members opts)` are the
   users an identity provider puts in it."
  [{:keys [id name members roles everyone?] :as group} assignable opts]
  (let [path (group-path id)]
    (page
     [:t :user.groups/group-page-title {:name name}]
     (list
      (rights-ui/header [[[:t :user.groups/breadcrumb] base-path] [name]] name
                        (when-not everyone?
                          [:form.group-delete-form {:method "post" :action (str path "/delete")}
                           (csrf/hidden-field)
                           [:button.button.ghost.button-danger-text {:type "submit"} [:t :user.groups/button-delete]]]))
      (rights-ui/notice opts)
      (if everyone?
        [:p [:t :user.groups/everyone-note]]
        (list
         [:form.group-edit-form {:method "post" :action path}
          (csrf/hidden-field)
          (rights-ui/section [:t :user.groups/details-heading]
                             (group-fields group)
                             [:button.button.primary {:type "submit"} [:t :user.groups/button-save]])]
         (rights-ui/section [:t :user.groups/members-heading] {:class "group-members"}
                            (if (seq members)
                              (rights-ui/action-table [[:t :user.rights/column-user] [:t :user.groups/field-name] ""]
                                               (member-rows members #(remove-button (str path "/members/" (:id %) "/remove"))))
                              [:p.empty-state [:t :user.groups/members-empty]])
                            [:form.group-member-form {:method "post" :action (str path "/members") :class "space-y-2"}
                             (csrf/hidden-field)
                             [:label {:for "user-id"} [:t :user.groups/field-member]]
                             (combobox/combobox :user-id {:url         (member-options-path id)
                                                          :required    true
                                                          :placeholder [:t :user.groups/member-placeholder]})
                             [:button.button.primary {:type "submit"} [:t :user.groups/button-add-member]]])
         (when-let [provided (seq (:provider-members opts))]
           (rights-ui/section [:t :user.groups/provider-members-heading] {:class "group-provider-members"}
                              [:p [:t :user.groups/provider-members-note]]
                              (rights-ui/table [[:t :user.rights/column-user] [:t :user.groups/field-name]
                                                [:t :user.roles/column-source]]
                                               (member-rows provided (constantly (ui/badge [:t :user.rights/source-provider]
                                                                                           {:variant :warning}))))))))
      (rights-ui/section [:t :user.groups/roles-heading] {:class "group-roles"}
                         (if (seq roles)
                           (rights-ui/action-table [[:t :user.groups/field-role] [:t :user.groups/field-scope] ""]
                                            (for [a roles]
                                              [:tr
                                               [:td [:a {:href (rights-ui/role-path (:role a))} [:code (group/permission-str (:role a))]]]
                                               [:td (rights-ui/scope-view (:scope a) (:scope-info opts))]
                                               [:td.actions-cell (remove-button (str path "/roles/" (:id a) "/remove"))]]))
                           [:p.empty-state [:t :user.groups/roles-empty]])
                         [:form.group-role-form {:method "post" :action (str path "/roles") :class "space-y-2"}
                          (csrf/hidden-field)
                          [:label {:for "group-role"} [:t :user.groups/field-role]]
                          (role-input assignable)
                          (if-let [scope-keys (seq (:scope-keys opts))]
                            (list [:p [:t :user.groups/scope-keys-hint]]
                                  (map scope-key-input scope-keys))
                            (list
                             [:label {:for "group-scope"} [:t :user.groups/field-scope]]
                             [:textarea#group-scope {:name "scope" :rows 3 :class "textarea" :placeholder "{:domain-id 42}"}
                              (get-in opts [:form :scope])]
                             [:small [:t :user.groups/scope-hint]]))
                          [:button.button.primary {:type "submit"} [:t :user.groups/button-add-role]]]))
     opts)))
