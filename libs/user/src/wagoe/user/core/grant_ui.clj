(ns wagoe.user.core.grant-ui
  "The grant wizard (BOU-853): give a user one permission through a group,
   each option with its preview, the one giving least else first. Pure
   Hiccup; each option's form posts to the page that makes that change."
  (:require [wagoe.platform.core.csrf :as csrf]
            [wagoe.user.core.group :as group]
            [wagoe.user.core.group-ui :as group-ui]
            [wagoe.user.core.rights-ui :as rights-ui]))

(def ^:private code rights-ui/code)

(defn- assignment-line [{:keys [role scope]} scope-info]
  [:p [:t :user.grant/assign] " " (code role) " " (rights-ui/scope-view scope scope-info)])

(defn- apply-form
  "A form posting `fields`, {name value}, to `action`: the option shows its
   preview, so a change is posted confirmed."
  [action fields & inputs]
  [:form.grant-apply {:method "post" :action action :class "space-y-3"}
   (csrf/hidden-field)
   (rights-ui/hidden-fields fields)
   inputs
   [:button.button.primary {:type "submit"} [:t :user.grant/apply]]])

(defn- extra [{:keys [extra]} scope-info]
  (if (seq extra)
    [:p.grant-extra [:t :user.grant/also-gets] " "
     (interpose ", " (for [{:keys [permission scope]} extra]
                       (list (code permission) " (" (rights-ui/effective-scope-view scope scope-info) ")")))]
    [:p.grant-nothing-else [:t :user.grant/nothing-else]]))

(defn- title [{:keys [kind group]}]
  (case kind
    :join      [:t :user.grant/join-heading {:group (:name group)}]
    :narrow    [:t :user.grant/narrow-heading {:group (:name group)}]
    :new-role  [:t :user.grant/new-role-heading]
    :new-group [:t :user.grant/new-group-heading]))

(defn- option
  "One ranked option, `n` its place. A refused one says why, its `:refused`
   as the shell put it into words, and offers nothing to apply (BOU-863)."
  [n {:keys [kind group assignment preview refused] :as o} user-id permission scope scope-info]
  (let [{:keys [role]} assignment
        required       {:required true :class "input"}
        ;; A stand-in's access ends by itself (BOU-856)
        expiry         (rights-ui/expiry-input (str "grant-expires-at-" n) nil)]
    (rights-ui/section
     [:span (str n ". ") (title o)]
     {:class (str "grant-option grant-" (name kind))}
     (if refused
       [:p.grant-refused {:class "alert alert-warning"} [:t :user.grant/refused] " " refused]
       (case kind
         :join      (list (extra o scope-info)
                          (rights-ui/preview-sections preview scope-info)
                          (apply-form (str (group-ui/group-path (:id group)) "/members")
                                      {"user-id" user-id "confirm" "true"}
                                      expiry))
         :narrow    (list (assignment-line assignment scope-info)
                          (extra o scope-info)
                          (rights-ui/preview-sections preview scope-info)
                          (apply-form (str (group-ui/group-path (:id group)) "/roles")
                                      (into {"role" (group/permission-str role) "confirm" "true"}
                                            (for [[k v] scope] [(name (group-ui/scope-lookup-field k)) v]))
                                      expiry))
         :new-role  (list [:p [:t :user.grant/new-role-hint {:permission (group/permission-str permission)}]]
                          (apply-form (str (rights-ui/grant-path user-id nil nil) "/role") (rights-ui/asking-params permission scope)
                                      [:label {:for "grant-role-name"} [:t :user.groups/field-name]]
                                      [:input (assoc required :id "grant-role-name" :name "name"
                                                     :value (group/permission-str permission))]))
         :new-group (list [:p [:t :user.grant/new-group-hint]]
                          (assignment-line assignment scope-info)
                          (rights-ui/preview-sections preview scope-info)
                          (apply-form (str (rights-ui/grant-path user-id nil nil) "/group")
                                      (assoc (rights-ui/asking-params permission scope)
                                             "role" (group/permission-str role) "confirm" "true")
                                      [:label {:for "grant-group-name"} [:t :user.groups/field-name]]
                                      [:input (assoc required :id "grant-group-name" :name "name")]
                                      [:label {:for "grant-group-purpose"} [:t :user.grant/field-purpose]]
                                      [:textarea (assoc required :id "grant-group-purpose" :name "description"
                                                        :class "textarea")]
                                      expiry)))))))

(defn grant-page
  "Ways to give `user` the permission asked, `result` as `grant-options`
   gives it, nil before one is asked. `opts`: `:asked` {:permission :record},
   `:registry` the permissions to pick from, `:scope-info` as
   `rights-ui/scope-view` reads it, `:error`."
  [user {:keys [explain options]} opts]
  (let [n          (or (not-empty (:name user)) (:email user))
        scope-info (:scope-info opts)
        {:keys [permission record]} (:asked opts)]
    (rights-ui/page
     [:t :user.grant/page-title {:name n}]
     (list
      (rights-ui/header [[[:t :user/page-users-title] "/web/admin/users"]
                         [n (str "/web/admin/users/" (:id user))]
                         [[:t :user.rights/tab] (rights-ui/user-rights-path (:id user))]
                         [[:t :user.grant/crumb]]]
                        n)
      (rights-ui/user-tabs (:id user) :rights)
      (rights-ui/notice opts)
      (rights-ui/section [:t :user.grant/heading]
                         [:p [:t :user.grant/hint]]
                         (rights-ui/asking-form :grant (rights-ui/grant-path (:id user) nil nil) (:registry opts) (sort-by key (:labels scope-info))
                                                (:asked opts)))
      (cond
        (nil? explain) nil

        (:allowed? explain)
        [:div.grant-allowed {:class "alert alert-success" :role "status"}
         [:t :user.grant/already] " "
         [:a {:href (rights-ui/explain-path (:id user) permission (if (empty? record) :everywhere record))}
          [:t :user.explain/open]]]

        :else
        (map-indexed (fn [i o] (option (inc i) o (:id user) permission record scope-info)) options)))
     opts)))

(defn bulk-page
  "Of the users `rows` names, those the permission asked refuses, each with
   the option giving least else. `rows`: [{:user :result}], `result` as
   `grant-options` gives it; `unknown`, what named nobody. Applying stays one
   user at a time, on their own wizard. `opts` as `grant-page`, and `:users`,
   the text asked."
  [rows unknown opts]
  (let [scope-info (:scope-info opts)
        {:keys [permission record]} (:asked opts)
        refused    (remove (comp :allowed? :explain :result) rows)]
    (rights-ui/page
     [:t :user.grant/bulk-title]
     (list
      (rights-ui/header [[[:t :user.permissions/page-title] rights-ui/permissions-path] [[:t :user.grant/bulk-title]]]
                        [:t :user.grant/bulk-title])
      (rights-ui/notice opts)
      (rights-ui/section [:t :user.grant/bulk-heading]
                         [:p [:t :user.grant/bulk-hint]]
                         (rights-ui/asking-form :grant rights-ui/grant-bulk-path (:registry opts) (sort-by key (:labels scope-info))
                                                (:asked opts)
                                                [:label {:for "grant-users"} [:t :user.grant/field-users]]
                                                [:textarea#grant-users {:name "users" :class "textarea" :required true}
                                                 (:users opts)]))
      (when permission
        (rights-ui/section [:t :user.grant/bulk-refused {:count (count refused) :total (count rows)}]
                           (when (seq unknown)
                             [:p.grant-unknown [:t :user.grant/bulk-unknown] " " (interpose ", " unknown)])
                           (rights-ui/table
                            [[:t :user.grant/column-user] [:t :user.grant/column-best] [:t :user.grant/column-radius]]
                            (for [{:keys [user result]} refused
                                  :let [best (first (:options result))]]
                              [:tr.grant-bulk-row
                               [:td (:email user)]
                               [:td [:a {:href (rights-ui/grant-path (:id user) permission record)} (title best)]]
                               [:td (str (:radius best ""))]])))))
     opts)))
