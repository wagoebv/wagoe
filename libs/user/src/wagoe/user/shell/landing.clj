(ns wagoe.user.shell.landing
  "The `:login-redirect` and `:dashboard` settings of `:wagoe/user`: checked
   and resolved at boot, read when a user signs in."
  (:require [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.error :as me]
            [wagoe.core.utils.redirect :as redirect]
            [wagoe.user.core.landing :as landing]
            [wagoe.user.schema :as user-schema]))

(defn- settings [config]
  (get-in config [:active :wagoe/user]))

(defn- resolve-fn
  "The function `x` names, or `x` itself when it is one already."
  [k x]
  (if (symbol? x)
    (let [v (try (requiring-resolve x) (catch Exception _ nil))]
      (when-not v
        (throw (ex-info (str ":wagoe/user " k " names " x ", which does not resolve")
                        {:type :configuration-error :key k :symbol x})))
      @v)
    x))

(defn prepare
  "`config` with `:login-redirect` and `:dashboard` checked and their symbols
   resolved. Throws :configuration-error on a bad value, so boot fails."
  [config]
  (let [s (settings config)]
    (when-let [explanation (m/explain user-schema/UserLandingSettings (or s {}))]
      (throw (ex-info (str "Invalid :wagoe/user settings: "
                           (pr-str (me/humanize explanation)))
                      {:type :configuration-error :errors (me/humanize explanation)})))
    (cond-> config
      (:login-redirect s)
      (update-in [:active :wagoe/user :login-redirect] #(resolve-fn :login-redirect %))

      (map? (:dashboard s))
      (update-in [:active :wagoe/user :dashboard :handler] #(resolve-fn :dashboard %)))))

(defn path-for
  "Where `user` lands after signing in. A function's answer that is not a path
   on this site — nil included — falls back to the defaults."
  [config user]
  (let [r (:login-redirect (settings config))]
    (if (or (fn? r) (symbol? r))
      (let [path ((resolve-fn :login-redirect r) user)]
        (if (redirect/local-path? path)
          path
          (do (when (some? path)
                (log/warn ":login-redirect returned a path not on this site; using the default"
                          {:path path}))
              (landing/role-path nil user))))
      (landing/role-path r user))))

(defn dashboard-handler
  "The handler to mount at /web/dashboard: nil for `:dashboard false`, the
   configured one, or what `default` builds."
  [config default]
  (let [d (:dashboard (settings config))]
    (cond
      (false? d) nil
      (map? d)   (resolve-fn :dashboard (:handler d))
      :else      (default))))
