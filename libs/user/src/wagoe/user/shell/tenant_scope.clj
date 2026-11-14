(ns wagoe.user.shell.tenant-scope
  "Whose users a request may list and act on (BOU-948, BOU-953)."
  (:require [wagoe.core.http.access :as access]
            [wagoe.user.ports :as ports]))

(defn list-options
  "`opts` limited to the users `request` may list, or nil when none. The resolved
   tenant wins; without one a platform admin lists everyone, anyone else their own tenant (or none)."
  [request opts]
  (when-let [reach (access/admin-reach request)]
    (cond-> opts (contains? reach :tenant-id) (assoc :filter-tenant-id (:tenant-id reach)))))

(defn lists?
  "Whether `user` is one that `request` may list: of the caller's tenant, by
   their own or, when `user` carries them, by any of their `:tenant-ids` (BOU-995)."
  [request user]
  (when-let [opts (list-options request {})]
    (or (not (contains? opts :filter-tenant-id))
        (contains? (or (:tenant-ids user) #{(some-> (:tenant-id user) str)})
                   (some-> (:filter-tenant-id opts) str)))))

(defn reaches?
  "Whether `request` may act on `user`: the caller themselves, or one it may list."
  [request user]
  (boolean (and user (or (access/same-id? (:id user) (get-in request [:user :id]))
                         (lists? request user)))))

(defn with-tenants
  "`user` with the `:tenant-ids` they belong to, when `user-service` knows them."
  [user-service user]
  (cond-> user
    (and user (satisfies? ports/ITenantMembers user-service))
    (assoc :tenant-ids (ports/tenant-ids user-service user))))

(defn reachable-user
  "The user `id` names, with their `:tenant-ids`, when `request` may reach them; else nil."
  [user-service request id]
  (let [user (with-tenants user-service (some->> id (ports/get-user-by-id user-service)))]
    (when (reaches? request user) user)))

(defn shared?
  "Whether `request` may not change `user`, nor see their sessions: they are in
   a tenant besides their own (or in one without having one), and the caller is
   neither them nor a platform admin. The user service refuses the same changes;
   a tenant's admin manages only the membership (BOU-995)."
  [request user]
  (and (boolean (seq (disj (set (:tenant-ids user)) (some-> (:tenant-id user) str))))
       (not (access/same-id? (:id user) (get-in request [:user :id])))
       (not (access/platform-admin? (:user request)))))

(defn new-user-tenant
  "What a user `request` creates is given: `{:tenant-id t}`, `{}` for no tenant, or nil
   when it may create none. A tenant's admin creates in their tenant, so a role it grants stays there."
  [request]
  (when-let [opts (list-options request {})]
    (if-let [t (:filter-tenant-id opts)] {:tenant-id t} {})))

(defn reaches-everyone?
  "Whether `request` may act on any user, so there is no one to look up."
  [request]
  (some-> (list-options request {}) (contains? :filter-tenant-id) not))

(def ^:private safe-methods #{:get :head :options})

(defn wrap-target-user
  "Middleware for a route whose `:id` names a user: a user `request` may not
   reach answers 404, as one that does not exist, and a change to a user
   `shared?` with another tenant 403, a read too with `{:sessions? true}`. A caller who reaches everyone is not
   checked, so a soft-deleted user, whom no lookup finds, can still be erased."
  [user-service & [opts]]
  (fn [handler]
    (fn [request]
      (let [id         (parse-uuid (str (get-in request [:path-params :id])))
            everyone?  (reaches-everyone? request)
            target     (when (and id (not everyone?))
                         (reachable-user user-service request id))
            refuse     #(access/refusal-response %1 %2 %3 (access/correlation-id request (str (random-uuid))))]
        (cond
          (or (nil? id) everyone?) (handler request)
          (nil? target) (refuse 404 "not-found" "User not found")
          (and (or (not (safe-methods (:request-method request))) (:sessions? opts))
               (shared? request target))
          (refuse 403 "forbidden" "This user belongs to another tenant too")
          :else (handler request))))))
