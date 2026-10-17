(ns wagoe.user.core.group-ui
  "Admin pages for groups, their members and their grants. Pure Hiccup; plain
   forms that post and redirect."
  (:require [wagoe.platform.core.csrf :as csrf]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.user.core.group :as group]))

(def base-path "/web/admin/groups")

(defn group-path [group-id] (str base-path "/" group-id))

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
                [:th [:t :user.groups/column-grants]]]]
       [:tbody
        (for [{:keys [id name description member-count grant-count]} groups]
          [:tr
           [:td [:a {:href (group-path id)} name]]
           [:td description]
           [:td member-count]
           [:td grant-count]])]]
      [:p.empty-state [:t :user.groups/empty]])
    [:form.group-create-form {:method "post" :action base-path}
     (csrf/hidden-field)
     [:h2 [:t :user.groups/create-heading]]
     (group-fields (:form opts))
     [:button {:type "submit"} [:t :user.groups/button-create]]])
   opts))

(defn- permission-input
  "A select over the declared permissions, or a text input when the
   application declares no fixed set."
  [options]
  (if (seq options)
    [:select#grant-permission {:name "permission" :required true}
     (for [p options]
       (let [v (group/permission-str p)] [:option {:value v} v]))]
    [:input#grant-permission {:type "text" :name "permission" :required true
                              :placeholder "invoice/approve"}]))

(defn group-page
  "One group: its name, members and grants, with the forms that change them.
   `permission-options` lists the declared permissions, or is nil."
  [{:keys [id name members grants] :as group} permission-options opts]
  (let [path (group-path id)]
    (page
     [:t :user.groups/group-page-title {:name name}]
     (list
      [:nav {:aria-label [:t :common/aria-breadcrumb]}
       [:a {:href base-path} [:t :user.groups/breadcrumb]] " / " name]
      (notice opts)
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
        [:label {:for "member-email"} [:t :user.groups/field-member-email]]
        [:input#member-email {:type "email" :name "email" :required true}]
        [:button {:type "submit"} [:t :user.groups/button-add-member]]]]

      [:section.group-grants
       [:h2 [:t :user.groups/grants-heading]]
       (if (seq grants)
         [:table.table
          [:thead [:tr
                   [:th [:t :user.groups/field-permission]]
                   [:th [:t :user.groups/field-scope]]
                   [:th]]]
          [:tbody
           (for [g grants]
             [:tr
              [:td [:code (group/permission-str (:permission g))]]
              [:td [:code (pr-str (:scope g))]]
              [:td (remove-button (str path "/grants/" (:id g) "/remove"))]])]]
         [:p.empty-state [:t :user.groups/grants-empty]])
       [:form.group-grant-form {:method "post" :action (str path "/grants")}
        (csrf/hidden-field)
        [:label {:for "grant-permission"} [:t :user.groups/field-permission]]
        (permission-input permission-options)
        [:label {:for "grant-scope"} [:t :user.groups/field-scope]]
        [:textarea#grant-scope {:name "scope" :rows 3 :placeholder "{:domain-id 42}"}
         (get-in opts [:form :scope])]
        [:small [:t :user.groups/scope-hint]]
        [:button {:type "submit"} [:t :user.groups/button-add-grant]]]])
     opts)))
