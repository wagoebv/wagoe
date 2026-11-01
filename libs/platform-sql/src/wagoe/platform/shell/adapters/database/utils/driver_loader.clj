(ns wagoe.platform.shell.adapters.database.utils.driver-loader
  "Dynamic JDBC driver loading based on active database configurations.

   This namespace provides configuration-driven driver loading that eliminates
   the need to coordinate between configuration files and command-line aliases.
   Drivers are loaded dynamically based on which databases are marked as :active
   in the configuration files.

   Key Features:
   - Single source of truth: configuration files control everything
   - Clear error messages when drivers are missing
   - Automatic driver detection from active databases
   - No command-line alias coordination required

   Usage:
     (require '[wagoe.platform.shell.adapters.database.utils.driver-loader :as dl])

     ;; Load drivers for active databases in current environment
     (dl/load-required-drivers!)

     ;; Load drivers for specific environment
     (dl/load-drivers-for-environment! \"dev\")"
  (:require [wagoe.platform.shell.adapters.database.config :as db-config]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Database Adapter to Driver Mapping
;; =============================================================================

(def ^:private adapter-driver-mapping
  "Mapping from database adapter keywords to JDBC driver class names."
  {:sqlite     "org.sqlite.JDBC"
   :postgresql "org.postgresql.Driver"
   :mysql      "com.mysql.cj.jdbc.Driver"
   :h2         "org.h2.Driver"})

(def ^:private adapter-dependency-mapping
  "Mapping from database adapter keywords to Maven dependency coordinates."
  {:sqlite     {:group-id "org.xerial" :artifact-id "sqlite-jdbc" :version "3.47.1.0"}
   :postgresql {:group-id "org.postgresql" :artifact-id "postgresql" :version "42.7.4"}
   :mysql      {:group-id "com.mysql" :artifact-id "mysql-connector-j" :version "9.1.0"}
   :h2         {:group-id "com.h2database" :artifact-id "h2" :version "2.3.232"}})

;; =============================================================================
;; Driver Detection and Loading
;; =============================================================================

(defn adapter-key->driver-class
  "Get JDBC driver class name for database adapter key.

   Args:
     adapter-key: Database adapter keyword (:sqlite, :postgresql, :mysql, :h2)

   Returns:
     String - JDBC driver class name

   Example:
     (adapter-key->driver-class :sqlite) ;; => \"org.sqlite.JDBC\""
  [adapter-key]
  (get adapter-driver-mapping adapter-key))

(defn config-key->adapter-key
  "Extract adapter type from configuration key.

   Args:
     config-key: Configuration key like :wagoe/sqlite

   Returns:
     Keyword - adapter key like :sqlite

   Example:
     (config-key->adapter-key :wagoe/sqlite) ;; => :sqlite"
  [config-key]
  (when (and (keyword? config-key)
             (= "wagoe" (namespace config-key)))
    (keyword (name config-key))))

(defn determine-required-drivers
  "Determine which JDBC drivers are required for active databases.

   Args:
     config: Configuration map with :active section

   Returns:
     Set of JDBC driver class names required

   Example:
     (determine-required-drivers config)
     ;; => #{\"org.sqlite.JDBC\" \"org.h2.Driver\"}"
  [config]
  (let [active-db-keys (keys (:active config))
        adapter-keys (keep config-key->adapter-key active-db-keys)
        driver-classes (keep adapter-key->driver-class adapter-keys)]
    (set driver-classes)))

(defn format-dependency-coordinate
  "Format Maven dependency coordinate for display.

   Args:
     dep-info: Map with :group-id, :artifact-id, :version

   Returns:
     String - formatted dependency coordinate"
  [{:keys [group-id artifact-id version]}]
  (str group-id "/" artifact-id " {:mvn/version \"" version "\"}"))

(defn get-dependency-suggestion
  "Get dependency suggestion for missing driver.

   Args:
     driver-class: JDBC driver class name

   Returns:
     String - formatted dependency suggestion or nil if unknown"
  [driver-class]
  (let [adapter-key (->> adapter-driver-mapping
                         (filter #(= (val %) driver-class))
                         first
                         key)]
    (when-let [dep-info (get adapter-dependency-mapping adapter-key)]
      (format-dependency-coordinate dep-info))))

(defn attempt-driver-load
  "Attempt to load a JDBC driver class.

   Args:
     driver-class: JDBC driver class name string

   Returns:
     Map with :success boolean and :error string if failed

   Example:
     (attempt-driver-load \"org.sqlite.JDBC\")
     ;; => {:success true} or {:success false :error \"ClassNotFoundException\"}"
  [driver-class]
  (try
    (Class/forName driver-class)
    (log/debug "Successfully loaded JDBC driver" {:driver driver-class})
    {:success true}
    (catch ClassNotFoundException e
      (log/debug "Failed to load JDBC driver" {:driver driver-class :error (.getMessage e)})
      {:success false :error "ClassNotFoundException" :exception e})
    (catch Exception e
      (log/warn "Unexpected error loading JDBC driver" {:driver driver-class :error (.getMessage e)})
      {:success false :error (.getMessage e) :exception e})))

(defn load-required-drivers!
  "Load all JDBC drivers required by active databases in configuration.

   Args:
     config: Configuration map with :active section

   Returns:
     Map with :success boolean, :loaded vector of loaded drivers,
     :failed vector of failed drivers with error info

   Throws:
     ExceptionInfo if any required drivers cannot be loaded"
  [config]
  (let [required-drivers (determine-required-drivers config)
        results (map (fn [driver-class]
                       (let [result (attempt-driver-load driver-class)]
                         (assoc result :driver driver-class)))
                     required-drivers)
        loaded (filter :success results)
        failed (filter #(not (:success %)) results)]

    (log/info "Loading JDBC drivers for active databases"
              {:required-count (count required-drivers)
               :loaded-count (count loaded)
               :failed-count (count failed)})

    (when (seq loaded)
      (log/info "Successfully loaded JDBC drivers"
                {:drivers (mapv :driver loaded)}))

    (when (seq failed)
      (let [error-details (map (fn [{:keys [driver error]}]
                                 (let [suggestion (get-dependency-suggestion driver)]
                                   {:driver driver
                                    :error error
                                    :suggestion suggestion}))
                               failed)
            error-message (str "Failed to load required JDBC drivers:\\n"
                               (str/join "\\n"
                                         (map (fn [{:keys [driver error suggestion]}]
                                                (str "- " driver " (" error ")"
                                                     (when suggestion
                                                       (str "\\n  Add to deps.edn: " suggestion))))
                                              error-details))
                               "\\n\\nEither add the missing dependencies to deps.edn or move the corresponding databases to :inactive in your configuration.")]
        (log/error "Required JDBC drivers not available" {:failed-drivers error-details})
        (throw (ex-info error-message
                        {:type :missing-jdbc-drivers
                         :failed-drivers error-details
                         :required-drivers required-drivers}))))

    {:success (empty? failed)
     :loaded (mapv :driver loaded)
     :failed (mapv (fn [{:keys [driver error]}]
                     {:driver driver
                      :error error
                      :suggestion (get-dependency-suggestion driver)})
                   failed)}))

;; =============================================================================
;; Environment-Aware Driver Loading
;; =============================================================================

(defn load-drivers-for-environment!
  "Load JDBC drivers required for active databases in specified environment.

   Args:
     env: Environment string (e.g. \"dev\", \"test\", \"prod\")

   Returns:
     Result map from load-required-drivers!

   Example:
     (load-drivers-for-environment! \"dev\")"
  [env]
  (log/info "Loading JDBC drivers for environment" {:env env})
  (let [config (db-config/load-config env)
        result (load-required-drivers! config)]
    (log/info "JDBC driver loading completed" {:env env :success (:success result)})
    result))

(defn load-drivers-for-current-environment!
  "Load JDBC drivers for active databases in current environment.

   Returns:
     Result map from load-required-drivers!"
  []
  (let [env (db-config/detect-environment)]
    (load-drivers-for-environment! env)))

;; =============================================================================
;; Validation and Introspection
;; =============================================================================

(defn validate-drivers-available
  "Validate that all required drivers are available without loading them.

   Args:
     config: Configuration map with :active section

   Returns:
     Map with :valid boolean and :missing vector of missing driver info"
  [config]
  (let [required-drivers (determine-required-drivers config)
        results (map (fn [driver-class]
                       (let [result (attempt-driver-load driver-class)]
                         (assoc result :driver driver-class)))
                     required-drivers)
        missing (filter #(not (:success %)) results)]

    {:valid (empty? missing)
     :required (vec required-drivers)
     :missing (mapv (fn [{:keys [driver error]}]
                      {:driver driver
                       :error error
                       :suggestion (get-dependency-suggestion driver)})
                    missing)}))

(defn get-active-database-summary
  "Get summary of active databases and their driver requirements.

   Args:
     env: Environment string

   Returns:
     Map with environment info, active databases, and driver requirements"
  [env]
  (let [config (db-config/load-config env)
        active-configs (:active config)
        db-keys (keys active-configs)
        adapter-keys (keep config-key->adapter-key db-keys)
        required-drivers (determine-required-drivers config)]

    {:environment env
     :active-databases (vec db-keys)
     :adapter-types (vec adapter-keys)
     :required-drivers (vec required-drivers)
     :driver-validation (validate-drivers-available config)}))

