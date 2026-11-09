(ns wagoe.user.shell.tenant-scope
  "Whose users a request may list (BOU-948)."
  (:require [wagoe.core.http.access :as access]))

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
