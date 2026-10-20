(ns wagoe.user.shell.landing
  "The `:login-redirect`, `:dashboard`, `:home`, `:landing` and `:registration`
   settings of `:wagoe/user`: checked and resolved at boot, read when a user signs in or a
   page renders."
  (:require [clojure.tools.logging :as log]
            [malli.error :as me]
            [ring.util.response :as response]
            [wagoe.core.utils.redirect :as redirect]
            [wagoe.config :as wagoe-config]
            [wagoe.core.validation :as cv]
            [wagoe.platform.shell.http.pages :as pages]
            [wagoe.shared.ui.core.home :as home]
            [wagoe.user.core.landing :as landing]
            [wagoe.user.shell.hooks :as hooks]
            [wagoe.user.schema :as user-schema]))

(defn- settings [config]
  (get-in config [:active :wagoe/user]))

(defn- resolve-fn
  "The function `x` names, or `x` itself when it is one already."
  [k x]
  (let [v (if (symbol? x)
            (try (wagoe-config/resolve-symbol x)
                 (catch clojure.lang.ExceptionInfo e
                   (throw (ex-info (str ":wagoe/user " k ": " (ex-message e))
                                   (assoc (ex-data e) :key k) e))))
            x)]
    (when-not (fn? v)
      (throw (ex-info (str ":wagoe/user " k " names " x ", which is not a function")
                      {:type :configuration-error :key k :symbol x})))
    v))

(defn prepare
  "`config` with the landing settings checked and the
   symbols resolved. Throws :configuration-error on a bad value, so boot fails. The
   module wiring calls it once; handlers expect its output."
  [config]
  (let [s (settings config)]
    (when-let [explanation (cv/explain user-schema/UserLandingSettings (or s {}))]
      (let [errors (me/humanize explanation)]
        (throw (ex-info (str "Invalid :wagoe/user settings: " (pr-str errors))
                        {:type :configuration-error :errors errors}))))
    (cond-> config
      (:login-redirect s)
      (update-in [:active :wagoe/user :login-redirect] #(if (map? %) % (hooks/as-fn :login-redirect %)))

      (:login-layout s)
      (update-in [:active :wagoe/user :login-layout] (partial hooks/as-fn :login-layout))

      (map? (:dashboard s))
      (update-in [:active :wagoe/user :dashboard :handler] #(resolve-fn :dashboard %)))))

(defn with-hooks
  "`config` with the `:login-redirect` and `:login-layout` the auth hooks
   settled on, which may be a module's rather than the one config set."
  [config hooks]
  (reduce (fn [config k]
            (if-some [hook (get hooks k)]
              (assoc-in config [:active :wagoe/user k] hook)
              config))
          config
          hooks/config-hooks))

(defn- dashboard
  "The admin's dashboard, or the profile in an application without the admin."
  [request]
  (if (pages/mounted? request home/default) home/default "/web/profile"))

(defn path-for
  "Where `user` lands after signing in. A function gets the user and
   `login-context` (`:ip-address`, `:user-agent`, `:request`). Its answer that
   is not a path on this site — nil included — falls back to the defaults, as
   does one that throws: by then the session exists, and a 500 would strand it."
  [config user login-context]
  (let [s       (settings config)
        r       (:login-redirect s)
        default (if (= :built-in (landing/dashboard-mode s))
                  (dashboard (:request login-context))
                  landing/app-dashboard-path)]
    (if (and r (not (map? r)))
      (let [path (try (r user login-context)
                      (catch Exception e
                        (log/warn e ":login-redirect threw; using the default")
                        nil))]
        (if (redirect/local-path? path)
          path
          (do (when (some? path)
                (log/warn ":login-redirect returned a path not on this site; using the default"
                          {:path path}))
              default)))
      (landing/role-path r default user))))

(defn front-page
  "Where `/` and `/web` send everyone, or nil to keep the defaults."
  [config]
  (:landing (settings config)))

(defn root-routes
  "The `/` route that sends everyone to `:landing`, for the module's
   `:static` contribution; the platform drops its own `/` for it. Empty when
   `:landing` is unset."
  [config]
  (if-let [path (front-page config)]
    [["/" {:no-doc             true
           :public             true
           :skip-interceptors? true
           :get    {:handler (constantly (response/redirect path))
                    :summary "Redirect to the application's landing page"}}]]
    []))

(defn registration?
  "Whether anyone may create their own account at /web/register. On unless
   `:registration false`."
  [config]
  (not (false? (:registration (settings config)))))

(defn dashboard-handler
  "The handler to mount at /web/dashboard: nil for `:dashboard false`, the
   configured one, or a redirect to the admin's dashboard (the profile without
   the admin)."
  [config]
  (let [s (settings config)]
    (case (landing/dashboard-mode s)
      :off      nil
      :app      (get-in s [:dashboard :handler])
      :built-in #(response/redirect (dashboard %)))))

(defn wrap-home
  "Middleware binding the layouts' home path for each request: the configured
   one, else the dashboard."
  [config]
  (let [path (landing/home-path (settings config))]
    (fn [handler]
      (fn [request]
        (binding [home/*path* (or path (dashboard request))]
          (handler request))))))
