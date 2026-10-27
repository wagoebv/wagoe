(ns wagoe.user.core.group-ui
  "Admin pages for groups, their members and their roles. Pure Hiccup; plain
   forms that post and redirect."
  (:require [wagoe.platform.core.csrf :as csrf]
            [wagoe.core.utils.text :as text]
            [wagoe.shared.ui.core.combobox :as combobox]
            [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.icons :as icons]
            [wagoe.shared.ui.core.modal :as modal]
            [wagoe.user.core.group :as group]
            [wagoe.user.core.rights-ui :as rights-ui]))

(def base-path rights-ui/groups-path)

(def group-path rights-ui/group-path)

(defn- member-options-path
  "Where the member combobox of a group's page reads its options."
  [group-id]
  (str (group-path group-id) "/members/options"))

(defn scope-lookup-field
  "The form field holding a fixed value of an assignment's scope key `k`."
  [k]
  (keyword (str "scope-" (name k))))

(defn- scope-label-field
  "The form field the lookup of scope key `k` submits its shown title as, so a
   refused assignment shows the title again rather than the id."
  [k]
  (keyword (str "scope-label-" (name k))))

(defn scope-source-field
  "The form field naming the user source an assignment's scope key `k` takes
   its value from instead."
  [k]
  (keyword (str "scope-source-" (name k))))

(defn- page [title content opts]
  (rights-ui/page title [:div.user-groups-page {:class "space-y-4"} content] opts))

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

(def groups-list
  "The groups list."
  (let [columns [{:field :name :label [:t :user.groups/field-name] :class "c-title"}
                 {:field :description :label [:t :user.groups/field-description]}
                 {:field :members :label [:t :user.groups/column-members]}
                 {:field :roles :label [:t :user.groups/column-roles]}]]
    {:id           "groups-table-container"
     :url          base-path
     :placeholder  [:t :user.groups/search-placeholder]
     :empty-text   [:t :user.groups/empty]
     :count-label  :user.groups/count
     :columns      columns
     :default-sort :name
     :sort-keys    {:name        #(some-> (:name %) text/lower-case)
                    :description #(some-> (:description %) text/lower-case)
                    :members     #(when-not (group/everyone? (:id %)) (:member-count %))
                    :roles       :role-count}
     :search-text  #(str (:name %) " " (:description %))
     :signals      [:empty :single-member :everyone]
     :row          (fn [{:keys [id name description member-count role-count] :as g}]
                     (rights-ui/list-row (group-path id) {:class "group-row"} columns
                                         [(list [:a {:href (group-path id)} name]
                                                (rights-ui/signal-badges (:signals g) g))
                                          description
                                          (if (group/everyone? id)
                                            [:t :user.groups/members-everyone]
                                            member-count)
                                          role-count]))}))

(defn groups-page
  "Every group, searchable and sortable by `(:table-query opts)`, and a form
   to create one. `groups` carry their `hygiene/group-signals` as `:signals`."
  [groups opts]
  (rights-ui/list-page
   [:t :user.groups/page-title]
   [:t :user.groups/description]
   (list
    (rights-ui/list-view groups-list groups (:table-query opts)
                         [:button.button.primary (merge (modal/open-attrs "group-create-modal") {:class "gap-2"})
                          (icons/icon :plus {:size 18}) [:t :user.groups/create-heading]])
    (rights-ui/create-modal "group-create-modal" [:t :user.groups/create-heading] opts
                            [:form.group-create-form {:method "post" :action base-path :class "space-y-3"}
                             (csrf/hidden-field)
                             (group-fields (:form opts))
                             [:button.button.primary {:type "submit"} [:t :user.groups/button-create]]]))
   opts))

(defn- role-input [roles chosen]
  [:select#group-role {:name "role" :required true :class "select"}
   (for [{:keys [name description]} roles]
     (let [v (group/permission-str name)] [:option {:value v :title description :selected (= v chosen)} v]))])

(defn- scope-key-input
  "A scope key's inputs: a fixed value, through a lookup when the key points at
   an admin entity, or a user source when the key takes one. `form`, the form
   params of a refused assignment, fills them in again."
  [form {:keys [key label url sources]}]
  (let [field  (scope-lookup-field key)
        source (scope-source-field key)
        value  (get form (name field))
        title  (get form (name (scope-label-field key)) value)
        picked (get form (name source))]
    [:div.scope-key {:class "space-y-1"}
     [:label {:for (name field)} (or label (name key))]
     (if url
       (combobox/combobox field {:url (str url "?field=" (name field)) :placeholder [:t :user.groups/scope-placeholder]
                                 :value value :label title
                                 :attrs {:name (name (scope-label-field key))}})
       [:input {:type "text" :id (name field) :name (name field) :class "input" :value value}])
     (when (seq sources)
       [:select {:id (name source) :name (name source) :class "select"
                 :aria-label [:t :user.groups/scope-source-label {:key (or label (name key))}]}
        [:option {:value ""} [:t :user.groups/scope-fixed-value]]
        (for [[s s-label] sources
              :let [v (group/permission-str s)]]
          [:option {:value v :selected (= v picked)} s-label])])]))

(defn- scope-edn-input [form]
  (list
   [:label {:for "group-scope"} [:t :user.groups/field-scope]]
   [:textarea#group-scope {:name "scope" :rows 3 :class "textarea" :placeholder "{:domain-id 42}"}
    (get form "scope")]
   [:small [:t :user.groups/scope-hint]]))

(defn- member-rows [members action]
  (for [m members]
    [:tr
     [:td {:data-label [:t :user.rights/column-user]} [:a {:href (rights-ui/user-rights-path (:id m))} (:email m)]]
     [:td {:data-label [:t :user.groups/field-name]} (:name m) (rights-ui/expiry-note (:expires-at m))]
     [:td.actions-cell (action m)]]))

(defn provider-members
  "The users an identity provider puts in a group, read-only."
  [members]
  (if (seq members)
    (rights-ui/table [[:t :user.rights/column-user] [:t :user.groups/field-name] [:t :user.roles/column-source]]
                     (member-rows members (constantly (ui/tone-badge [:t :user.rights/source-provider] :warning))))
    [:p.empty-state [:t :user.groups/provider-members-empty]]))

(defn group-page
  "One group: its name, members and roles, with the forms that change them.
   `roles` are those it may be assigned, as `list-roles` gives them.
   `(:scope-keys opts)`, [{:key :label :url :sources [[source label]]}], are
   the declared scope keys, each chosen through a lookup when it has a :url;
   the scope can be typed as EDN too. `(:form opts)`, the form params of a
   refused change, fills the role form in again."
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
                             (rights-ui/expiry-input "member-expires-at" nil)
                             [:button.button.primary {:type "submit"} [:t :user.groups/button-add-member]]])
         ;; Asked for: listing them runs the membership hook for every user
         (rights-ui/section [:t :user.groups/provider-members-heading] {:class "group-provider-members"}
                            [:p [:t :user.groups/provider-members-note]]
                            [:div#provider-members
                             [:button.button.secondary.small
                              {:type "button" :hx-get (str path "/provider-members")
                               :hx-target "#provider-members" :hx-swap "innerHTML"}
                              [:t :user.groups/button-show-provider-members]]])))
      (rights-ui/section [:t :user.groups/roles-heading] {:class "group-roles"}
                         (if (seq roles)
                           (rights-ui/action-table [[:t :user.access/column-assignment] ""]
                                            (let [index (rights-ui/role-index assignable)]
                                              (for [a roles]
                                                [:tr
                                                 [:td {:data-label [:t :user.access/column-assignment]}
                                                  ;; Its own page: the name, not a link
                                                  (rights-ui/assignment-sentence (-> a (dissoc :group-id) (assoc :group-name name))
                                                                                 index (:scope-info opts))]
                                                 [:td.actions-cell (remove-button (str path "/roles/" (:id a) "/remove"))]])))
                           [:p.empty-state [:t :user.groups/roles-empty]])
                         [:form.group-role-form {:method "post" :action (str path "/roles") :class "space-y-2"}
                          (csrf/hidden-field)
                          [:label {:for "group-role"} [:t :user.groups/field-role]]
                          (role-input assignable (get-in opts [:form "role"]))
                          (if-let [scope-keys (seq (:scope-keys opts))]
                            (list [:p [:t :user.groups/scope-keys-hint]]
                                  (map (partial scope-key-input (:form opts)) scope-keys)
                                  ;; For a key whose value a field cannot hold
                                  [:details {:open (boolean (seq (get-in opts [:form "scope"])))}
                                   [:summary [:t :user.groups/scope-as-edn]]
                                   (scope-edn-input (:form opts))])
                            (scope-edn-input (:form opts)))
                          (rights-ui/expiry-input "role-expires-at" (get-in opts [:form "expires-at"]))
                          [:button.button.primary {:type "submit"} [:t :user.groups/button-add-role]]]))
     opts)))
