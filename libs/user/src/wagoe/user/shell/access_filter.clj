(ns wagoe.user.shell.access-filter
  "Who the user list's access filters admit (BOU-1002): the users holding a
   permission, in a group or holding a role, read through the group service
   in the tenant it is bound to."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [wagoe.platform.core.http.access :as access]
            [wagoe.platform.ports.authorization :as authz]
            [wagoe.user.core.group :as group-core]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.group-service :as group-service]))

(defn platform-admins
  "The platform admins, who pass every check."
  [user-service]
  ;; Paged: tenant admins share the role and would push platform admins past one page
  (->> (iterate #(+ % 100) 0)
       (map #(:users (ports/list-users user-service {:filter-role :admin :filter-active true
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
   group runs the membership hook for every active user."
  [group-service assignments]
  (let [groups   (distinct (map :group-id (remove :everyone? assignments)))
        provided (ids (mapcat #(ports/provider-members group-service %) groups))]
    {:everyone? (boolean (some :everyone? assignments))
     :provided  provided
     :members   (into provided (ids (mapcat :members assignments)))}))

(defn- by-group [group-service group-id]
  (when-let [g (some-> (parse-uuid (str group-id)) (->> (ports/get-group group-service)))]
    (if (group-core/everyone? (:id g))
      {:everyone? true}
      (let [provided (ids (ports/provider-members group-service (:id g)))]
        {:provided provided :members (into provided (ids (:members g)))}))))

(defn- by-permission
  "Who holds `permission`, through a group or as a platform admin."
  [user-service group-service permission]
  (let [roles (group-core/roles-holding (into {} (map (juxt :name identity)) (ports/list-roles group-service))
                                        permission)
        given (given-by group-service (ports/role-holders group-service roles))
        admins (ids (platform-admins user-service))]
    (-> given
        (update :members into admins)
        (assoc :platform-admins admins))))

(defn admitted
  "For `filters`, {:permission :group :role} as parsed, the user ids they all
   admit as `:ids`, nil when any user may be, and per filter what it found:
   {filter {:everyone? :provided :platform-admins :members}}. A group or role
   that does not exist admits nobody."
  [user-service group-service {:keys [permission group role]}]
  (let [found (cond-> {}
                permission (assoc :permission (by-permission user-service group-service permission))
                group      (assoc :group (or (by-group group-service group) {:members #{}}))
                role       (assoc :role (given-by group-service (ports/role-holders group-service #{role}))))
        sets  (keep #(when-not (:everyone? %) (:members %)) (vals found))]
    {:found found
     :ids   (when (seq sets) (apply set/intersection sets))}))

(defn parse
  "The access filters `query-params` give, {:permission :group :has-role},
   blank ones left out, as the URL holds them: `role` is already the user's
   admin role, so a role from the Roles page is `has-role`."
  [query-params]
  (into {} (for [k [:permission :group :has-role]
                 :let [v (some-> (get query-params (name k)) str str/trim not-empty)]
                 :when v]
             [k v])))

(defn find-users
  "`admitted` for `filters` as `parse` gives them, in the groups of
   `tenant-id`."
  [user-service group-service tenant-id {:keys [permission group has-role]}]
  (group-service/with-tenant tenant-id
    #(admitted user-service group-service
               {:permission (group-core/parse-name permission)
                :group      group
                :role       (group-core/parse-name has-role)})))

(defn choices
  "What the filters offer in `tenant-id`: the declared permissions, the
   groups and the roles, by name."
  [group-service tenant-id]
  (group-service/with-tenant tenant-id
    (fn []
      (when tenant-id (group-service/ensure-everyone! group-service))
      {:permissions (sort (map group-core/permission-str (keys (some-> (:registry group-service) authz/permissions))))
       :groups      (sort-by :name (map #(select-keys % [:id :name]) (ports/list-groups group-service)))
       :roles       (sort (map (comp group-core/permission-str :name) (ports/list-roles group-service)))})))

(defn with-groups
  "`users`, each with `:groups`, those they are in, read in their own tenant."
  [group-service users]
  (mapv (fn [u] (assoc u :groups (group-service/with-tenant (:tenant-id u) #(ports/user-groups group-service u))))
        users))
