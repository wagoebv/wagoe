(ns wagoe.admin.core.ui.drawer
  "The record drawer: one record beside its list, opened by clicking its row.

   Read-only, rendered by the same display roles as the list cells (ADR-040),
   so a relation is a name, an enum a badge, money formatted. Editing stays on
   the detail page; the drawer links there. Pure Hiccup."
  (:require [wagoe.admin.core.ui.base :as base]
            [wagoe.admin.core.ui.cells :as cells]
            [wagoe.shared.ui.core.icons :as icons]
            [clojure.string :as str]))

(def slot-id
  "The element on the list page the drawer is swapped into."
  "record-drawer-slot")

(defn- record-url [entity-name id]
  (str "/web/admin/" (name entity-name) "/" id))

(defn fields
  "The fields the drawer lists: the detail fields (else the field order, else
   every field), without the hidden ones, the primary key and the title,
   which is the heading."
  [entity-config]
  (let [hidden (set (:hide-fields entity-config))
        skip   (conj hidden (:primary-key entity-config :id) (:title-field entity-config))]
    (->> (or (seq (:detail-fields entity-config))
             (seq (:field-order entity-config))
             (sort (keys (:fields entity-config))))
         (filter #(contains? (:fields entity-config) %))
         (remove skip)
         distinct)))

(defn- related-section
  "Each has-many as its label and count, linked to the filtered child list."
  [related-records]
  (when (seq related-records)
    [:section {:class "drawer-section"}
     [:h2 {:class "drawer-section-title"} [:t :admin/drawer-related]]
     [:ul {:class "drawer-related"}
      (for [[rel rows] related-records
            :let [n   (count rows)
                  fk  (str/replace (name (:foreign-key rel)) "_" "-")
                  url (str "/web/admin/" (name (:entity rel)) "?" fk "="
                           (base/url-encode (str (:parent-id rel))))]]
        [:li
         [:a {:href url} (or (:label rel) (name (:entity rel)))]
         [:span {:class (if (pos? n) "c-count" "c-count c-zero")}
          (if (:has-more? rel) (str (dec n) "+") (str n))]])]]))

(defn record-drawer
  "The drawer for `record`.

   opts:
     :display          zone, locale and patterns, with :overview for titles
     :related-records  [[rel rows] …] as the detail page builds them
     :return-to        the list the drawer was opened from, kept on the Edit link
     :permissions      the user's flags for this entity"
  [entity-name entity-config record {:keys [display related-records return-to permissions]}]
  (let [pk       (:primary-key entity-config :id)
        id       (get record pk)
        title-f  (:title-field entity-config pk)
        heading  (str (get record title-f id))
        facet    (:facet entity-config)
        edit-url (cond-> (record-url entity-name id)
                   return-to (str "?return_to=" (base/url-encode return-to)))
        ctx      {:entity-config entity-config
                  :display       display
                  :overview      (:overview display)}]
    [:aside {:class           "record-drawer"
             :id              "record-drawer"
             :aria-labelledby "record-drawer-title"
             :data-record-id  (str id)}
     [:header {:class "drawer-header"}
      [:div {:class "drawer-heading"}
       (when (and facet (some? (get record facet)))
         (cells/cell facet record (assoc ctx
                                         :role :enum
                                         :field-config (get-in entity-config [:fields facet]))))
       [:h2 {:id "record-drawer-title" :class "drawer-title"} heading]]
      [:div {:class "drawer-nav"}
       ;; Disabled at the ends of the page by admin-ux.js, which knows the rows
       [:button {:type "button" :class "icon-button ghost" :data-drawer "prev"
                 :aria-label [:t :admin/drawer-previous] :title "k"}
        (icons/icon :chevron-up {:size 16})]
       [:button {:type "button" :class "icon-button ghost" :data-drawer "next"
                 :aria-label [:t :admin/drawer-next] :title "j"}
        (icons/icon :chevron-down {:size 16})]
       [:button {:type        "button"
                 :class       "icon-button ghost drawer-close"
                 :data-drawer "close"
                 :aria-label  [:t :admin/drawer-close]
                 :title       "q"}
        (icons/icon :x {:size 16})]]]
     [:div {:class "drawer-actions"}
      (when (:can-edit permissions)
        [:a {:class "button primary" :href edit-url :data-action "edit"}
         (icons/icon :edit {:size 14})
         [:t :common/button-edit]
         [:kbd "E"]])]
     (let [columns        (remove #(= :count (:role %)) ; has-many: listed below
                                  (cells/columns entity-config (fields entity-config)))
           blank?         #(let [v (get record (:field %))] (or (nil? v) (= "" v)))
           [filled blanks] [(remove blank? columns) (filter blank? columns)]]
       (list
        [:dl {:class "drawer-fields"}
         (for [{:keys [field config role label relation] :as column} filled]
           (list
            [:dt label]
            [:dd {:class (:class column)}
             (cells/cell field record (assoc ctx :role role :field-config config :relation relation))]))]
        ;; Empty fields say little; they are listed, folded, so the filled
        ;; ones read as the record
        (when (seq blanks)
          [:details {:class "drawer-empty"}
           [:summary [:t :admin/drawer-empty-fields {:n (count blanks)}]]
           ;; A label may be an i18n vector, so interposed, not joined
           [:p (interpose ", " (map :label blanks))]])))
     (related-section related-records)]))
