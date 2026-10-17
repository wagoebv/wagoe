(ns wagoe.user.core.landing
  "Where a user lands after signing in.")

(def default-paths
  {:admin   "/web/admin/users"
   :default "/web/dashboard"})

(defn role-path
  "The landing path for `user` from a role map, merged over the defaults."
  [paths user]
  (let [paths (merge default-paths paths)]
    (get paths (:role user) (:default paths))))
