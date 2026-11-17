(ns wagoe.user.core.revoke-ui
  "The remove wizard (BOU-1001): take one permission away from a user, each
   way with its preview, the one taking least else first. Pure Hiccup; each
   option's form posts to the group page that makes that change."
  (:require [wagoe.user.core.group :as group]
            [wagoe.user.core.rights-ui :as rights-ui]))

(def ^:private code rights-ui/code)

(defn- apply-form [action fields label & inputs]
  (apply rights-ui/apply-form "revoke-apply" label action fields inputs))

(defn- what-else
  "Whether the option takes the permission away, and what else they lose."
  [{:keys [removes? also-loses]} scope-info]
  (list
   (when-not removes?
     [:p.revoke-still-held {:class "alert alert-warning"} [:t :user.revoke/still-held]])
   (if (seq also-loses)
     [:p.revoke-also-loses [:t :user.revoke/also-loses] " "
      (interpose ", " (for [{:keys [permission scope]} also-loses]
                        (list (code permission) " (" (rights-ui/effective-scope-view scope scope-info) ")")))]
     [:p.revoke-nothing-else [:t :user.revoke/nothing-else]])))

(defn- title [{:keys [kind group assignment]}]
  (case kind
    :leave          [:t :user.revoke/leave-heading {:group (:name group)}]
    :end-assignment [:t :user.revoke/end-heading {:group (:name group)
                                                  :role  (group/permission-str (:role assignment))}]))

(defn- member-path [group-id user-id]
  (str (rights-ui/group-path group-id) "/members/" user-id))

(defn- end-on-form
  "Leaving on a day instead: nothing changes until then. Refused, the wizard
   for `permission` says why."
  [n group-id user-id permission expires-at]
  (let [id (str "revoke-expires-at-" n)]
    (apply-form (str (rights-ui/revoke-path user-id nil) "/expire")
                {"group-id" group-id "permission" (group/permission-str permission)}
                :user.revoke/end-on
                [:p [:t :user.revoke/end-on-hint]]
                [:label {:for id} [:t :user.revoke/field-ends-on]]
                [:input {:type "date" :id id :name "expires-at" :class "input" :required true
                         :value (some-> expires-at rights-ui/expiry-date)}])))

(defn- option
  "One ranked option, `n` its place. A refused one says why and offers
   nothing to apply. `expiry?`: a membership's end can be set."
  [n {:keys [kind group assignment preview refused expires-at] :as o} user-id permission scope-info expiry?]
  (rights-ui/section
   [:span (str n ". ") (title o)]
   {:class (str "revoke-option revoke-" (name kind))}
   (if refused
     [:p.revoke-refused {:class "alert alert-warning"} [:t :user.grant/refused] " " refused]
     (list
      (when (= :end-assignment kind)
        (rights-ui/assignment-line assignment scope-info))
      (what-else o scope-info)
      (rights-ui/preview-sections preview scope-info)
      (case kind
        :leave          (list (apply-form (str (member-path (:id group) user-id) "/remove")
                                          {"confirm" "true"} :user.revoke/remove-now)
                              (when expiry? (end-on-form n (:id group) user-id permission expires-at)))
        :end-assignment (apply-form (str (rights-ui/group-path (:id group)) "/roles/" (:id assignment) "/remove")
                                    {"confirm" "true"} :user.revoke/end-now))))))

(defn- blocked
  "What gives the permission but cannot be ended here, and where to change it."
  [{:keys [explain blocked]} user-id]
  (let [admin? (= :platform-admin (:via explain))]
    (when (or admin? (seq blocked))
      (rights-ui/section [:t :user.revoke/blocked-heading] {:class "revoke-blocked"}
                         (when admin?
                           [:p.revoke-platform-admin [:t :user.revoke/platform-admin] " "
                            [:a {:href (str "/web/admin/users/" user-id)} [:t :admin/tab-details]]])
                         (for [{:keys [group-id group-name membership]} blocked]
                           [:p {:class (str "revoke-via-" (name membership))}
                            [:t (if (= :everyone membership) :user.revoke/via-everyone :user.revoke/via-provider)
                             {:group group-name}]
                            " " [:a {:href (rights-ui/group-path group-id)} group-name]])))))

(defn revoke-page
  "Ways to take the permission asked away from `user`, `result` as the shell's
   `revoke-options` gives it, nil before one is asked. `opts`: `:asked`
   {:permission}, `:registry`, `:scope-info`, `:expiry?` and `:error`."
  [user {:keys [explain options] :as result} opts]
  (let [n          (or (not-empty (:name user)) (:email user))
        scope-info (:scope-info opts)
        permission (get-in opts [:asked :permission])]
    (rights-ui/page
     [:t :user.revoke/page-title {:name n}]
     (list
      (rights-ui/header [[[:t :user/page-users-title] "/web/admin/users"]
                         [n (str "/web/admin/users/" (:id user))]
                         [[:t :user.rights/tab] (rights-ui/user-rights-path (:id user))]
                         [[:t :user.revoke/crumb]]]
                        n)
      (rights-ui/user-tabs (:id user) :rights)
      (rights-ui/notice opts)
      (rights-ui/section [:t :user.revoke/heading]
                         [:p [:t :user.revoke/hint]]
                         (rights-ui/asking-form :revoke (rights-ui/revoke-path (:id user) nil) (:registry opts) []
                                                (:asked opts)))
      (cond
        (nil? explain) nil

        (not (:allowed? explain))
        [:div.revoke-not-held {:class "alert alert-success" :role "status"}
         [:t :user.revoke/not-held] " "
         [:a {:href (rights-ui/explain-path (:id user) permission nil)} [:t :user.explain/open]]]

        :else
        (list (blocked result (:id user))
              (map-indexed (fn [i o] (option (inc i) o (:id user) permission scope-info (:expiry? opts))) options))))
     opts)))
