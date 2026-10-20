(ns wagoe.user.core.landing
  "Where a user lands after signing in.")

(def dashboard-path
  "The one dashboard: the admin's home, open to every signed-in user."
  "/web/admin/")

(defn default-path
  "Where a user lands when no role path names them: the dashboard, or
   /web/dashboard when `:dashboard` gives that path to the application."
  [settings]
  (let [d (:dashboard settings)]
    (if (or (false? d) (map? d)) "/web/dashboard" dashboard-path)))

(defn role-path
  "The landing path for `user` from a role map, `:default` falling back to
   `default`."
  [paths default user]
  (get paths (:role user) (get paths :default default)))

(defn home-path
  "The path the layouts link to as home, or nil for their default, the
   dashboard. With the dashboard switched off it is /web, which sends a
   signed-in user on to their landing path."
  [settings]
  (or (:home settings)
      (let [d (:dashboard settings)]
        (cond (false? d) "/web"
              (map? d)   "/web/dashboard"))))
