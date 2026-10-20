(ns wagoe.user.core.group-ui
  "Admin pages for groups, their members and their roles. Pure Hiccup; plain
   forms that post and redirect."
  (:require [wagoe.platform.core.csrf :as csrf]
            [wagoe.shared.ui.core.combobox :as combobox]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.user.core.group :as group]))

(def base-path "/web/admin/groups")

(defn group-path [group-id] (str base-path "/" group-id))

(defn- member-options-path
  "Where the member combobox of a group's page reads its options."
  [group-id]
  (str (group-path group-id) "/members/options"))

(defn scope-lookup-field
  "The form field an assignment's scope key `k` is chosen in, when the application
   says which entity the key points at."
  [k]
  (keyword (str "scope-" (name k))))

(defn- notice [{:keys [error]}]
  (when error
    [:div {:class "alert alert-error" :role "alert"} error]))

(defn- page [title content opts]
  (layout/admin-pilot-page-layout title [:div.user-groups-page content] opts))

(defn- remove-button [action]
  [:form {:method "post" :action action}
   (csrf/hidden-field)
   [:button.secondary {:type "submit"} [:t :user.groups/button-remove]]])

(defn- group-fields [group]
  (list
   [:label {:for "group-name"} [:t :user.groups/field-name]]
   [:input#group-name {:type "text" :name "name" :required true :maxlength 255
                       :value (:name group)}]
   [:label {:for "group-description"} [:t :user.groups/field-description]]
   [:input#group-description {:type "text" :name "description" :maxlength 255
                              :value (:description group)}]))

(defn groups-page
  "Every group, and a form to create one."
  [groups opts]
  (page
   [:t :user.groups/page-title]
   (list
    [:div.page-header
     [:div
      [:h1 [:t :user.groups/heading]]
      [:p [:t :user.groups/description]]]]
    (notice opts)
    (if (seq groups)
      [:table.table
       [:thead [:tr
                [:th [:t :user.groups/field-name]]
                [:th [:t :user.groups/field-description]]
                [:th [:t :user.groups/column-members]]
                [:th [:t :user.groups/column-roles]]]]
       [:tbody
        (for [{:keys [id name description member-count role-count]} groups]
          [:tr
           [:td [:a {:href (group-path id)} name]]
           [:td description]
           [:td (when-not (group/everyone? id) member-count)]
           [:td role-count]])]]
      [:p.empty-state [:t :user.groups/empty]])
    [:form.group-create-form {:method "post" :action base-path}
     (csrf/hidden-field)
     [:h2 [:t :user.groups/create-heading]]
     (group-fields (:form opts))
     [:button {:type "submit"} [:t :user.groups/button-create]]])
   opts))

(defn- role-input [roles]
  [:select#group-role {:name "role" :required true}
   (for [{:keys [name description]} roles]
     (let [v (group/permission-str name)] [:option {:value v :title description} v]))])

(defn- scope-lookup-input [{:keys [key url]}]
  (let [field (scope-lookup-field key)]
    (list [:label {:for (name field)} (name key)]
          (combobox/combobox field {:url (str url "?field=" (name field)) :placeholder [:t :user.groups/scope-placeholder]}))))

(defn group-page
  "One group: its name, members and roles, with the forms that change them.
   `roles` are those it may be assigned, as `list-roles` gives them.
   `(:scope-lookups opts)`, [{:key :url}], are the scope keys an assignment
   chooses through a lookup, beside the EDN."
  [{:keys [id name members roles everyone?] :as group} assignable opts]
  (let [path (group-path id)]
    (page
     [:t :user.groups/group-page-title {:name name}]
     (list
      [:nav {:aria-label [:t :common/aria-breadcrumb]}
       [:a {:href base-path} [:t :user.groups/breadcrumb]] " / " name]
      (notice opts)
      (if everyone?
        [:p [:t :user.groups/everyone-note]]
        (list
         [:section
          [:form.group-edit-form {:method "post" :action path}
           (csrf/hidden-field)
           (group-fields group)
           [:button {:type "submit"} [:t :user.groups/button-save]]]
          [:form.group-delete-form {:method "post" :action (str path "/delete")}
           (csrf/hidden-field)
           [:button.danger {:type "submit"} [:t :user.groups/button-delete]]]]

         [:section.group-members
          [:h2 [:t :user.groups/members-heading]]
          (if (seq members)
            [:table.table
             [:tbody
              (for [m members]
                [:tr
                 [:td (:email m)]
                 [:td (:name m)]
                 [:td (remove-button (str path "/members/" (:id m) "/remove"))]])]]
            [:p.empty-state [:t :user.groups/members-empty]])
          [:form.group-member-form {:method "post" :action (str path "/members")}
           (csrf/hidden-field)
           [:label {:for "user-id"} [:t :user.groups/field-member]]
           (combobox/combobox :user-id {:url         (member-options-path id)
                                        :required    true
                                        :placeholder [:t :user.groups/member-placeholder]})
           [:button {:type "submit"} [:t :user.groups/button-add-member]]]]))

      [:section.group-roles
       [:h2 [:t :user.groups/roles-heading]]
       (if (seq roles)
         [:table.table
          [:thead [:tr
                   [:th [:t :user.groups/field-role]]
                   [:th [:t :user.groups/field-scope]]
                   [:th]]]
          [:tbody
           (for [a roles]
             [:tr
              [:td [:code (group/permission-str (:role a))]]
              [:td [:code (pr-str (:scope a))]]
              [:td (remove-button (str path "/roles/" (:id a) "/remove"))]])]]
         [:p.empty-state [:t :user.groups/roles-empty]])
       [:form.group-role-form {:method "post" :action (str path "/roles")}
        (csrf/hidden-field)
        [:label {:for "group-role"} [:t :user.groups/field-role]]
        (role-input assignable)
        (map scope-lookup-input (:scope-lookups opts))
        [:label {:for "group-scope"} [:t :user.groups/field-scope]]
        [:textarea#group-scope {:name "scope" :rows 3 :placeholder "{:domain-id 42}"}
         (get-in opts [:form :scope])]
        [:small [:t :user.groups/scope-hint]]
        [:button {:type "submit"} [:t :user.groups/button-add-role]]]])
     opts)))
