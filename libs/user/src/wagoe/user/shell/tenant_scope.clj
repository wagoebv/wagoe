(ns wagoe.user.shell.tenant-scope
  "Whose users a request may list and act on (BOU-948, BOU-953)."
  (:require [wagoe.core.http.access :as access]
            [wagoe.user.ports :as ports]))

(defn list-options
  "`opts` limited to the users `request` may list, or nil when none. The resolved
   tenant wins; without one a platform admin lists everyone, anyone else their own tenant (or none)."
  [request opts]
  (let [user      (:user request)
        tenant-id (get-in request [:tenant :id])]
    (cond
      tenant-id                     (when (access/in-tenant-scope? request)
                                      (assoc opts :filter-tenant-id tenant-id))
      (access/platform-admin? user) opts
      :else                         (assoc opts :filter-tenant-id (:tenant-id user)))))

(defn lists?
  "Whether `user` is one that `request` may list."
  [request user]
  (when-let [opts (list-options request {})]
    (or (not (contains? opts :filter-tenant-id))
        (= (some-> (:filter-tenant-id opts) str) (some-> (:tenant-id user) str)))))

(defn reaches?
  "Whether `request` may act on `user`: the caller themselves, or one it may list."
  [request user]
  (boolean (and user (or (access/same-id? (:id user) (get-in request [:user :id]))
                         (lists? request user)))))

(defn reachable-user
  "The user `id` names, when `request` may reach them; else nil."
  [user-service request id]
  (let [user (some->> id (ports/get-user-by-id user-service))]
    (when (reaches? request user) user)))

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

(defn wrap-target-user
  "Middleware for a route whose `:id` names a user: a user `request` may not
   reach answers 404, as one that does not exist. A caller who reaches everyone
   is not checked, so a soft-deleted user, whom no lookup finds, can still be erased."
  [user-service]
  (fn [handler]
    (fn [request]
      (let [id (parse-uuid (str (get-in request [:path-params :id])))]
        (if (or (nil? id)
                (reaches-everyone? request)
                (reachable-user user-service request id))
          (handler request)
          (access/refusal-response 404 "not-found" "User not found"
                                   (access/correlation-id request (str (random-uuid)))))))))
