(ns wagoe.core.config.feature-flags
  "Feature flag management for gradual rollout of new functionality.
  
   This namespace provides pure functions for checking feature flags based on
   environment variables and configuration.
   
   Design Principles:
   - Pure functions: No side effects, configuration passed as parameter
   - Explicit defaults: Clear behavior when flag not set
   - Type-safe: Boolean coercion with validation
   - Backward compatible: Defaults to false for new features")

;; =============================================================================
;; Feature Flag Definitions
;; =============================================================================

(def known-flags
  "Registry of known feature flags with metadata.
  
   Each flag includes:
   - :env-var - Environment variable name
   - :default - Default value if not set
   - :description - Human-readable description"
  {:devex-validation
   {:env-var "WAG_DEVEX_VALIDATION"
    :default false
    :description "Enable enhanced validation error messages and developer experience features"}

   :structured-logging
   {:env-var "WAG_STRUCTURED_LOGGING"
    :default false
    :description "Enable structured logging with detailed context"}})

;; =============================================================================
;; Flag Parsing
;; =============================================================================

(defn parse-bool
  "Parse string value to boolean.
  
   Args:
     value: String value from environment
   
   Returns:
     Boolean value
   
   Truthy values: \"true\", \"1\", \"yes\", \"on\" (case-insensitive)
   Falsy values: Everything else"
  [value]
  (when value
    (boolean
     (re-matches #"(?i)true|1|yes|on" (str value)))))

(defn get-env-value
  "Get environment variable value.
  
   Args:
     env-var: Environment variable name
     env-map: Map of environment variables (defaults to System/getenv)
   
   Returns:
     String value or nil"
  ([env-var]
   (get-env-value env-var (System/getenv)))
  ([env-var env-map]
   (get env-map env-var)))

;; =============================================================================
;; Feature Flag Checking
;; =============================================================================

(defn enabled?
  "Check if a feature flag is enabled.
  
   Args:
     flag-key: Feature flag keyword (from known-flags)
     env-map: Optional environment map (defaults to System/getenv)
   
   Returns:
     Boolean indicating if feature is enabled
   
   Example:
     (enabled? :devex-validation)
     => true  ; if WAG_DEVEX_VALIDATION=true in environment"
  ([flag-key]
   (enabled? flag-key (System/getenv)))
  ([flag-key env-map]
   (if-let [flag-config (get known-flags flag-key)]
     (let [env-value (get-env-value (:env-var flag-config) env-map)
           parsed (parse-bool env-value)]
       (if (nil? parsed)
         (:default flag-config)
         parsed))
     ;; Unknown flag defaults to false
     false)))

(defn all-flags
  "Get status of all known feature flags.
  
   Args:
     env-map: Optional environment map
   
   Returns:
     Map of flag-key -> boolean status
   
   Example:
     (all-flags)
     => {:devex-validation true, :structured-logging false}"
  ([]
   (all-flags (System/getenv)))
  ([env-map]
   (into {}
         (map (fn [[k _v]]
                [k (enabled? k env-map)])
              known-flags))))

(defn flag-info
  "Get information about a specific flag.
  
   Args:
     flag-key: Feature flag keyword
     env-map: Optional environment map
   
   Returns:
     Map with :enabled?, :env-var, :description"
  ([flag-key]
   (flag-info flag-key (System/getenv)))
  ([flag-key env-map]
   (if-let [flag-config (get known-flags flag-key)]
     (assoc flag-config
            :enabled? (enabled? flag-key env-map)
            :current-value (get-env-value (:env-var flag-config) env-map))
     {:error "Unknown feature flag"
      :flag-key flag-key})))

;; =============================================================================
;; Configuration Integration
;; =============================================================================

(defn add-flags-to-config
  "Add feature flags to application configuration.
  
   Args:
     config: Application configuration map
     env-map: Optional environment map
   
   Returns:
     Configuration map with :feature-flags added"
  ([config]
   (add-flags-to-config config (System/getenv)))
  ([config env-map]
   (assoc config :feature-flags (all-flags env-map))))
