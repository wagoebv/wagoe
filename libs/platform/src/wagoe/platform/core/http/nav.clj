(ns wagoe.platform.core.http.nav
  "The sidebar navigation modules contribute, as data.

   A module's route contribution may carry `:nav`, groups by id:

     {:user {:label    [:t :user/nav-title]
             :icon     :users
             :entities [:users]          ; admin entities this module owns
             :items    [{:href \"/web/audit\" :label .. :icon :file-text
                         :access :platform-admin
                         :description ..}]}}  ; optional, for dashboard cards

   An item with `:entity` (the admin contributes one per entity) is placed by
   module: its `:module`, else the group whose `:entities` names it, else
   `:other`. Other items stay in the group that declared them.

   The platform binds `*nav*` around each request, so a page renders the whole
   sidebar without depending on the modules in it."
  (:require [clojure.string :as str]
            [wagoe.platform.core.http.access :as access]))

(def ^:dynamic *nav*
  "`{:groups [..] :uri \"..\"}` for the request being handled: every module's
   groups, ordered (or a delay of them), and the path asked for. nil outside a
   request."
  nil)

(def other-group
  "Where an entity no module claims goes."
  {:id :other :label [:t :common/nav-other] :icon :folder})

(defn- merge-group
  "Two contributions under one id: the first label and icon win, items and
   entities add up."
  [a b]
  (-> (merge b a)
      (assoc :items (into (vec (:items a)) (:items b))
             :entities (into (vec (:entities a)) (:entities b)))))

(defn- group-of
  "The group id an entity item belongs in. A `:module` no group has is ignored."
  [groups claims {:keys [module entity]}]
  (or (when (contains? groups module) module) (get claims entity) :other))

(defn collect
  "One ordered vector of groups from `contributions`, each a module's `:nav`
   map or a function returning one, in module order. `order` lists group ids that come first; the rest
   keep contribution order, and `:other` comes last. Items whose page
   `mounted?` denies are dropped, and then empty groups. An entity item is not
   asked: its page is a parameter of the admin's route, and the admin
   contributes it only when mounted."
  [contributions order mounted?]
  (let [contributions (map #(if (fn? %) (%) %) contributions)
        declared (distinct (mapcat keys contributions))
        groups   (apply merge-with merge-group contributions)
        claims   (into {} (for [[id group] groups, e (:entities group)] [e id]))
        entity?  (comp some? :entity)
        placed   (group-by #(group-of groups claims %) (filter entity? (mapcat :items (vals groups))))
        ids      (distinct (concat (filter (conj (set declared) :other) order)
                                   (remove #{:other} declared)
                                   [:other]))]
    (into []
          (keep (fn [id]
                  (let [group (merge (when (= :other id) other-group)
                                     (dissoc (get groups id) :entities)
                                     {:id id})
                        items (into (vec (get placed id))
                                    (filter #(and (not (entity? %)) (mounted? (:href %))))
                                    (:items group))]
                    (when (seq items)
                      (assoc group :items items)))))
          ids)))

(defn allowed?
  "Whether `user` may open a page guarded by `access`. The one place nav asks
   who may see what: `:platform-admin`, `:admin`, or nil for anyone signed in.
   Permission checks (ADR-041) replace these keywords here."
  [access user]
  (case access
    :platform-admin (access/platform-admin? user)
    :admin          (access/admin? {:user user})
    nil             (some? user)
    false))

(defn- prefix-of? [href uri]
  (and (some? href)
       (or (= href uri) (str/starts-with? (str uri) (str href "/")))))

(defn for-user
  "`groups` with only the items `user` may open. The item whose href is the
   longest prefix of `uri` is `:active?`, and its group `:open?`."
  [groups uri user]
  (let [groups (into []
                     (keep (fn [group]
                             (when-let [items (seq (filter #(allowed? (:access %) user) (:items group)))]
                               (assoc group :items (vec items)))))
                     groups)
        active (->> (mapcat :items groups)
                    (map :href)
                    (filter #(prefix-of? % uri))
                    (sort-by count >)
                    first)
        mark   (fn [item] (cond-> item (= active (:href item)) (assoc :active? true)))]
    (mapv (fn [group]
            (let [items (mapv mark (:items group))]
              (assoc group :items items :open? (boolean (some :active? items)))))
          groups)))
