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

(defn home-path
  "The path the layouts link to as home, or nil for their default. With the
   dashboard switched off it is /web, which sends a signed-in user on to
   their landing path."
  [settings]
  (or (:home settings)
      (when (false? (:dashboard settings)) "/web")))
