(ns wagoe.user.core.landing
  "Where a user lands after signing in.")

(def dashboard-path
  "The one dashboard: the admin's home, open to every signed-in user."
  "/web/admin/")

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
   `default`. A user without a role is looked up as `:user`."
  [paths default user]
  (get paths (or (:role user) :user) (get paths :default default)))

(defn home-path
  "The path the layouts link to as home, or nil for their default,
   /web/dashboard, which redirects to the dashboard. With the dashboard
   switched off it is /web, which sends a signed-in user on to their landing
   path."
  [settings]
  (or (:home settings)
      (when (= :off (dashboard-mode settings)) "/web")))
