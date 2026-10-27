(ns wagoe.user.core.access-ui
  "The access overview (BOU-854): groups by modules, each cell the level a
   group holds there, with Everyone and the superusers first, as they reach
   furthest."
  (:require [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.table :as table-ui]
            [wagoe.user.core.group :as group]
            [wagoe.user.core.rights-ui :as rights-ui]))

(def path "/web/admin/access")

(def matrix-id "access-matrix")

(defn overview
  "The matrix: `{:modules [m] :all-modules [m] :groups [group] :rows [{:group
   :cells {m [entry]}}] :everyone [assignment]}`, an entry {:assignment :permissions} for each
   assignment whose role gives permissions in that module. `holders` as
   `all-assignments` gives them; `filters` {:module :group}, strings, narrow it."
  [groups holders roles registry {:keys [module] group-filter :group}]
  (let [entries (for [{:keys [role] :as a} holders
                      :when (not (:everyone? a))
                      [m ps] (group-by (partial rights-ui/permission-module registry) (:permissions (get roles role)))]
                  [(:group-id a) m {:assignment a :permissions (sort-by group/permission-str ps)}])
        cells   (reduce (fn [acc [g m e]] (update-in acc [g m] (fnil conj []) e)) {} entries)
        groups  (remove (comp group/everyone? :id) (sort-by :name groups))
        all     (->> (concat (map (partial rights-ui/permission-module registry) (keys registry)) (map second entries))
                     (remove nil?) distinct sort)]
    {:all-modules all
     :modules  (filter #(or (empty? module) (= module %)) all)
     :groups   groups
     :rows     (for [g groups
                     :when (or (empty? group-filter) (= group-filter (str (:id g))))]
                 {:group g :cells (get cells (:id g) {})})
     :everyone (filter :everyone? holders)}))

(defn- level-badge [{:keys [assignment]} roles]
  (let [role (get roles (:role assignment))]
    (list
     (if-let [level (rights-ui/role-level role)]
       (ui/tone-badge [:t (rights-ui/level-labels level)] :info)
       (ui/tone-badge (group/permission-str (:role assignment)) :neutral))
     (when (seq (:scope assignment))
       (list " " (ui/tone-badge [:t :user.access/scoped] :warning))))))

(defn- cell [entries roles registry scope-info]
  (if (empty? entries)
    [:td.access-cell.access-none [:span {:aria-hidden "true"} "–"] [:span.sr-only [:t :user.access/none]]]
    [:td.access-cell
     [:details
      [:summary (interpose " " (for [e entries] [:span.access-level (level-badge e roles)]))]
      (for [{:keys [assignment permissions]} entries]
        [:div.access-entry {:class "space-y-1"}
         [:p (rights-ui/assignment-sentence assignment roles scope-info)]
         [:ul.access-permissions
          (for [p permissions]
            [:li [:a {:href (rights-ui/permission-path p)}
                  (or (get-in registry [p :label]) (group/permission-str p))]])]])]]))

(defn matrix
  "The matrix table, `overview` as `overview` gives it; what a filter swaps."
  [{:keys [modules rows]} roles registry scope-info]
  [:div {:id matrix-id}
   (if (and (seq modules) (seq rows))
     (rights-ui/table
      (cons [:t :user.rights/column-group] modules)
      (for [{:keys [group cells]} rows]
        (into [:tr.access-row
               [:th {:scope "row"} [:a {:href (rights-ui/group-path (:id group))} (:name group)]]]
              (for [m modules] (cell (get cells m) roles registry scope-info)))))
     [:p.empty-state [:t :user.access/empty]])])

(defn- filters [{:keys [module group]} modules groups]
  (let [opts {:url path :hx-target (str "#" matrix-id) :hx-include "#access-filters select"}]
    [:div#access-filters {:class "flex flex-wrap gap-2"}
     (table-ui/filter-input (assoc opts :name "module" :value module
                                   :label [:t :user.access/filter-module] :placeholder [:t :user.access/all-modules]
                                   :options (map (juxt identity identity) modules)))
     (table-ui/filter-input (assoc opts :name "group" :value group
                                   :label [:t :user.access/filter-group] :placeholder [:t :user.access/all-groups]
                                   :options (for [g groups] [(str (:id g)) (:name g)])))]))

(defn- widest-reach [everyone superusers roles scope-info]
  (rights-ui/section [:t :user.access/widest-heading] {:class "access-widest"}
                     [:div {:class "alert alert-warning" :role "note"} [:t :user.access/widest-note]]
                     [:h3 [:t :user.access/everyone-heading]]
                     (if (seq everyone)
                       [:ul.access-everyone
                        (for [a everyone] [:li (rights-ui/assignment-sentence a roles scope-info)])]
                       [:p.empty-state [:t :user.access/everyone-empty]])
                     [:h3 [:t :user.access/superusers-heading]]
                     [:ul.access-superusers
                      (for [{:keys [id email]} superusers]
                        [:li [:a {:href (rights-ui/user-rights-path id)} email] " "
                         [:t :user.access/passes-every-check]])]))

(defn page
  "The overview: Everyone and `superusers` first, then the filters and the
   matrix. `opts`: `:filters` {:module :group}, `:roles` as `rights-ui/role-index` gives them, `:registry`, `:scope-info`."
  [ov superusers {:keys [roles registry scope-info] :as opts}]
  (rights-ui/list-page
   [:t :user.access/page-title]
   [:t :user.access/description]
   (list
    (widest-reach (:everyone ov) superusers roles scope-info)
    (rights-ui/section [:t :user.access/matrix-heading] {:class "access-overview"}
                       (filters (:filters opts) (:all-modules ov) (:groups ov))
                       (matrix ov roles registry scope-info)))
   opts))
