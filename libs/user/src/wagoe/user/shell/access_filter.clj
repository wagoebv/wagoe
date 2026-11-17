(ns wagoe.user.shell.access-filter
  "Who the user list's access filters admit (BOU-1002): the users holding a
   permission, in a group or holding a role, read through the group service
   in the tenant it is bound to."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [wagoe.platform.core.http.access :as access]
            [wagoe.user.core.group :as group-core]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.group-service :as group-service]))

(defn platform-admins
  "The platform admins, who pass every check: admins of no tenant."
  [user-service]
  (->> (iterate #(+ % 100) 0)
       (map #(:users (ports/list-users user-service {:filter-role :admin :filter-active true :filter-tenant-id nil
                                                     :sort-by :email :sort-direction :asc
                                                     :limit 100 :offset %})))
       (take-while seq)
       (apply concat)
       (filter access/platform-admin?)))

(defn- ids [users] (into #{} (map (comp str :id)) users))

(defn- given-by
  "Who the groups of `assignments`, as role-holders gives them, put the
   permission or role in: `:members`, stored and from the identity provider,
   `:provided` the latter, and `:everyone?` when Everyone gives it. A provider
   group runs the membership hook for every active user of the tenant."
  [group-service assignments]
  (let [groups   (distinct (map :group-id (remove :everyone? assignments)))
        provided (ids (mapcat #(ports/provider-members group-service %) groups))]
    {:everyone? (boolean (some :everyone? assignments))
     :provided  provided
     :members   (into provided (ids (mapcat :members assignments)))}))

(defn- by-group [group-service group-id]
  (when-let [g (some-> (parse-uuid (str group-id)) (->> (ports/get-group group-service)))]
    (given-by group-service [{:group-id (:id g) :members (:members g) :everyone? (group-core/everyone? (:id g))}])))

(defn- by-permission
  "Who holds `permission` through a group, in any scope, and, outside a
   tenant, as a platform admin."
  [user-service group-service permission platform?]
  (let [roles  (group-core/roles-holding (into {} (map (juxt :name identity)) (ports/list-roles group-service))
                                         permission)
        given  (given-by group-service (ports/role-holders group-service roles))
        admins (when platform? (ids (platform-admins user-service)))]
    (-> given
        (update :members into admins)
        (assoc :platform-admins admins))))

(defn admitted
  "For `filters`, {:permission :group :role} as parsed, the user ids they all
   admit as `:ids`, nil when any user may be, and what the permission filter
   found among them: `:found` {:permission {:everyone? :provided
   :platform-admins}}. Platform admins count only when `platform?`: a tenant
   lists none. A group or role that does not exist admits nobody."
  [user-service group-service {:keys [permission group role]} platform?]
  (let [;; A provider group asks the membership hook for every user: once each
        group-service (group-service/memoized-membership group-service)
        found (cond-> {}
                permission (assoc :permission (by-permission user-service group-service permission platform?))
                group      (assoc :group (or (by-group group-service group) {:members #{}}))
                role       (assoc :role (given-by group-service (ports/role-holders group-service #{role}))))
        sets  (keep #(when-not (:everyone? %) (:members %)) (vals found))
        ids   (when (seq sets) (apply set/intersection sets))
        among #(when % (cond-> % ids (set/intersection ids)))]
    {:found (some-> (:permission found)
                    (select-keys [:everyone? :provided :platform-admins])
                    (update :provided among)
                    (update :platform-admins among)
                    (->> (hash-map :permission)))
     :ids   ids}))

(def params
  "The query parameters the access filters read: `role` is already the
   user's admin role, so a role from the Roles page is `has-role`."
  ["permission" "group" "has-role"])

(defn parse
  "The access filters `query-params` give, {:permission :group :has-role},
   blank ones left out, as the URL holds them."
  [query-params]
  (into {} (for [k (map keyword params)
                 :let [v (some-> (get query-params (name k)) str str/trim not-empty)]
                 :when v]
             [k v])))

(defn offered?
  "Whether the caller may filter on groups here: their groups are kept per
   tenant, or they are a platform admin, as the group pages decide."
  [group-service user]
  (or (access/platform-admin? user) (group-service/tenant-groups? group-service)))

(defn find-users
  "`admitted` for `filters` as `parse` gives them, in the groups
   `group-service/in-tenant` binds; `tenant-id` nil is a platform admin
   outside any tenant."
  [user-service group-service tenant-id {:keys [permission group has-role]}]
  (admitted user-service group-service
            {:permission (group-core/parse-name permission)
             :group      group
             :role       (group-core/parse-name has-role)}
            (nil? tenant-id)))

(defn choices
  "What the filters offer in the groups `group-service/in-tenant` binds: the
   declared permissions, the groups and the roles, by name."
  [group-service]
  {:permissions (sort (map group-core/permission-str (keys (group-service/registry-permissions group-service))))
   :groups      (sort-by :name (map #(select-keys % [:id :name]) (ports/list-groups group-service)))
   :roles       (sort (map (comp group-core/permission-str :name) (ports/list-roles group-service)))})

(defn with-groups
  "`users`, each with `:groups`, those they are in, read in their own tenant;
   a group of `tenant-id`, the one the caller works in, links to its page."
  [group-service tenant-id users]
  (let [group-service (group-service/memoized-membership group-service)]
    (mapv (fn [u]
            (let [here? (= (some-> (:tenant-id u) str) (some-> tenant-id str))]
              (assoc u :groups (mapv #(assoc % :linked? here?)
                                     (group-service/with-tenant (:tenant-id u) #(ports/user-groups group-service u))))))
          users)))
