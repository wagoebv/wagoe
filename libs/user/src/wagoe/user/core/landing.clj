(ns wagoe.user.core.landing
  "Where a user lands after signing in.")

(def app-dashboard-path
  "The path `:dashboard` hands to the application."
  "/web/dashboard")

(defn dashboard-mode
  "`:off` when `:dashboard false` leaves /web/dashboard to the application,
   `:app` when a handler of its own serves it, else `:built-in`: a redirect to
   the admin's dashboard."
  [settings]
  (let [d (:dashboard settings)]
    (cond (false? d) :off
          (map? d)   :app
          :else      :built-in)))

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
      (case (dashboard-mode settings)
        :off      "/web"
        :app      app-dashboard-path
        :built-in nil)))
