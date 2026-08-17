(ns wagoe.config
  "Configuration loading and typed accessors.

   Reads `resources/conf/<env>/config.edn` through Aero and exposes the
   settings libraries need. Assembling an Integrant system from those settings
   is the application's job — see `wagoe.system-config` in the monorepo, or the
   `config.clj` that `wagoe new` generates.

   This lives in a library because four published libraries used to reach for
   it with `requiring-resolve`, which worked in the monorepo and in generated
   projects and nowhere else — and `wagoe.user`'s CLI resolved a `db-spec` that
   generated projects never had (BOU-306).

   Usage:
     (def config (load-config))
     (db-spec config)"
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Configuration Loading
;; =============================================================================

(def ^:private env-aliases
  "Map long-form environment names to the short directory names under resources/conf/."
  {"development" "dev"
   "production"  "prod"
   "acceptance"  "acc"
   "testing"     "test"})

(defn- normalize-env
  "Normalize a WAG_ENV value to one of the short config directory names (dev, test, prod, acc)."
  [env]
  (let [s (some-> env str .trim .toLowerCase)]
    (get env-aliases s s)))

(defn load-config
  "Load configuration from resources/conf/dev/config.edn using Aero.

   Args:
     opts: Optional map with :profile key (defaults to :dev)

   Returns:
     Configuration map with resolved environment variables and profile selection

   Example:
     (load-config)
     (load-config {:profile :test})"
  ([] (load-config {}))
  ([{:keys [profile] :or {profile (keyword (or (System/getenv "WAG_ENV") "dev"))}}]
   (let [profile (keyword (normalize-env (name profile)))
         config-path (str "conf/" (name profile) "/config.edn")
         config-resource (io/resource config-path)]
     (if config-resource
       (do
         (log/info "Loading configuration" {:profile profile :path config-path})
         (assoc (aero/read-config config-resource {:profile profile})
                :wagoe/profile profile))
       (throw (ex-info "Configuration file not found"
                       {:profile profile
                        :path config-path
                        :available-profiles [:dev :test :prod]}))))))

;; =============================================================================
;; Configuration Helpers
;; =============================================================================

(defn- active-database-adapter
  "Determine which database adapter is active from config.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Keyword adapter (:sqlite, :h2, :postgresql, :mysql) or nil"
  [config]
  (let [active-config (:active config)]
    (cond
      (:wagoe/sqlite active-config) :sqlite
      (:wagoe/h2 active-config) :h2
      (:wagoe/postgresql active-config) :postgresql
      (:wagoe/mysql active-config) :mysql
      :else nil)))

(defn db-adapter
  "Extract database adapter keyword from config.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Keyword adapter (:sqlite, :h2, :postgresql, :mysql)"
  [config]
  (or (active-database-adapter config)
      (throw (ex-info "No active database adapter found in configuration"
                      {:active-keys (keys (:active config))}))))

(defn db-spec
  "Extract database specification from config for the active adapter.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Database spec map appropriate for the adapter
   
   Example:
     {:adapter :sqlite :database-path \"dev-database.db\"}"
  [config]
  (let [adapter (db-adapter config)
        adapter-key (keyword "wagoe" (name adapter))
        slash-key (keyword (str "wagoe/" (name adapter)))
        adapter-config (or (get-in config [:active adapter-key])
                           (get-in config [:active slash-key]))]

    (when-not adapter-config
      (throw (ex-info "No configuration found for active adapter"
                      {:adapter adapter
                       :adapter-key adapter-key
                       :slash-key slash-key})))

    (case adapter
      :sqlite
      {:adapter :sqlite
       :database-path (:db adapter-config)
       :pool (:pool adapter-config)}

      :h2
      {:adapter :h2
       :database-path (if (:memory adapter-config)
                        "mem:wagoe;DB_CLOSE_DELAY=-1"
                        (:db adapter-config))
       :pool (:pool adapter-config)}

      :postgresql
      {:adapter :postgresql
       :host (:host adapter-config)
       :port (:port adapter-config)
       :name (:dbname adapter-config)
       :username (:user adapter-config)
       :password (:password adapter-config)
       :pool (:pool adapter-config)}

      :mysql
      {:adapter :mysql
       :host (:host adapter-config)
       :port (:port adapter-config)
       :name (:dbname adapter-config)
       :username (:user adapter-config)
       :password (:password adapter-config)
       :pool (:pool adapter-config)}

      (throw (ex-info "Unsupported database adapter"
                      {:adapter adapter
                       :supported [:sqlite :h2 :postgresql :mysql]})))))

(defn http-config
  "Extract HTTP server configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with :port, :host, :join?, :port-range, and :drain-timeout-ms keys

   :drain-timeout-ms controls graceful shutdown: on stop the server stops
   accepting new connections, lets in-flight requests finish (bounded by this
   timeout, in milliseconds), then halts. 0 or nil disables graceful draining."
  [config]
  (let [http-cfg (get-in config [:active :wagoe/http])]
    {:port (or (:port http-cfg) 3000)
     :host (or (:host http-cfg) "0.0.0.0")
     :join? (or (:join? http-cfg) false)
     :port-range (:port-range http-cfg)
     ;; default only when the key is absent — an explicit nil disables draining
     :drain-timeout-ms (get http-cfg :drain-timeout-ms 30000)}))

(defn app-config
  "Extract application-level configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with application settings"
  [config]
  (get-in config [:active :wagoe/settings] {}))

(defn default-tenant-id
  "Extract default tenant ID for development/testing.
   
   This provides a consistent tenant context for:
   - REPL development and testing
   - CLI operations without explicit tenant specification  
   - Default test fixtures
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     UUID string of default tenant ID
   
   Note:
     Production systems should NOT rely on defaults and must
     always specify tenant-id explicitly in requests."
  [config]
  (get-in config [:active :wagoe/settings :default-tenant-id]))

(defn user-validation-config
  "Extract user validation configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with user validation settings including:
     - :email-domain-allowlist - Set of allowed email domains
     - :password-policy - Password complexity requirements
     - :name-restrictions - Name validation rules
     - :role-restrictions - Role assignment rules
     - :tenant-limits - Per-tenant user limits
     - :cross-field-validation - Cross-field validation rules"
  [config]
  (get-in config [:active :wagoe/settings :user-validation] {}))

(defn logging-config
  "Extract logging configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with logging provider and settings"
  [config]
  (get-in config [:active :wagoe/logging] {:provider :no-op}))

(defn metrics-config
  "Extract metrics configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with metrics provider and settings"
  [config]
  (get-in config [:active :wagoe/metrics] {:provider :no-op}))

(defn tracing-config
  "Extract tracing configuration (defaults to the inert no-op tracer)."
  [config]
  (get-in config [:active :wagoe/tracing] {:provider :no-op}))

(defn error-reporting-config
  "Extract error reporting configuration.
   
   Args:
     config: Configuration map from load-config
   
   Returns:
     Map with error reporting provider and settings"
  [config]
  (get-in config [:active :wagoe/error-reporting] {:provider :no-op}))
