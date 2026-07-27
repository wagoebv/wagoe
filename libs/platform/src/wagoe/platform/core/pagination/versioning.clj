(ns wagoe.platform.core.pagination.versioning
  "Pure functions for API versioning logic.
   
   This namespace provides pure functional implementations for API version
   management, following the Functional Core pattern. All functions are
   deterministic and side-effect free.
   
   Supports:
   - Version parsing and comparison
   - Version lifecycle management (experimental → stable → deprecated → sunset)
   - Version validation
   - Migration path tracking
   
   Pure: All functions return data, no side effects.")

;; =============================================================================
;; Version Parsing
;; =============================================================================

(defn parse-version
  "Parse version string to structured map.
   
   Args:
     version - Version string (e.g., 'v1', 'v2.1', 'v1.2.3')
     
   Returns:
     {:major int :minor int :patch int :original string}
     
   Examples:
     (parse-version \"v1\")     => {:major 1 :minor 0 :patch 0 :original \"v1\"}
     (parse-version \"v2.1\")   => {:major 2 :minor 1 :patch 0 :original \"v2.1\"}
     (parse-version \"v1.2.3\") => {:major 1 :minor 2 :patch 3 :original \"v1.2.3\"}
     (parse-version \"v0\")     => {:major 0 :minor 0 :patch 0 :original \"v0\"}
     
   Pure: true"
  [version]
  (when version
    (let [version-str (if (keyword? version) (name version) version)
          [_ major minor patch] (re-matches #"v?(\d+)(?:\.(\d+))?(?:\.(\d+))?" version-str)]
      (when major
        {:major    (Integer/parseInt major)
         :minor    (if minor (Integer/parseInt minor) 0)
         :patch    (if patch (Integer/parseInt patch) 0)
         :original version-str}))))

(defn version-string
  "Convert parsed version back to string.
   
   Args:
     version - Parsed version map
     
   Returns:
     Version string (e.g., \"v1\", \"v2.1\")
     
   Pure: true"
  [version]
  (let [{:keys [major minor patch]} version]
    (cond
      (and (zero? minor) (zero? patch)) (str "v" major)
      (zero? patch)                     (str "v" major "." minor)
      :else                             (str "v" major "." minor "." patch))))

;; =============================================================================
;; Version Comparison
;; =============================================================================

(defn compare-versions
  "Compare two version strings.
   
   Args:
     v1 - First version string
     v2 - Second version string
     
   Returns:
     -1 (v1 < v2), 0 (equal), 1 (v1 > v2), or nil (invalid version)
     
   Examples:
     (compare-versions \"v1\" \"v2\")     => -1
     (compare-versions \"v2\" \"v1\")     => 1
     (compare-versions \"v1\" \"v1\")     => 0
     (compare-versions \"v1.2\" \"v1.3\") => -1
     
   Pure: true"
  [v1 v2]
  (let [p1 (parse-version v1)
        p2 (parse-version v2)]
    (when (and p1 p2)
      (compare [(:major p1) (:minor p1) (:patch p1)]
               [(:major p2) (:minor p2) (:patch p2)]))))

(defn version-greater-than?
  "Check if v1 > v2.
   
   Pure: true"
  [v1 v2]
  (= 1 (compare-versions v1 v2)))

(defn version-less-than?
  "Check if v1 < v2.
   
   Pure: true"
  [v1 v2]
  (= -1 (compare-versions v1 v2)))

(defn version-equal?
  "Check if v1 == v2.
   
   Pure: true"
  [v1 v2]
  (= 0 (compare-versions v1 v2)))

;; =============================================================================
;; Version Lifecycle
;; =============================================================================

(defn is-experimental?
  "Check if version is experimental (v0).
   
   Args:
     version - Version string or keyword
     
   Returns:
     Boolean
     
   Pure: true"
  [version]
  (let [parsed (parse-version version)]
    (and parsed (zero? (:major parsed)))))

(defn is-stable?
  "Check if version is stable (v1+, not deprecated).
   
   Args:
     version - Version string or keyword
     config - Configuration with :deprecated-versions set
     
   Returns:
     Boolean
     
   Pure: true"
  [version config]
  (let [parsed (parse-version version)
        deprecated-set (set (map name (:deprecated-versions config)))]
    (and parsed
         (pos? (:major parsed))
         (not (contains? deprecated-set (name version))))))

(defn is-deprecated?
  "Check if version is deprecated.
   
   Args:
     version - Version string or keyword
     config - Configuration with :deprecated-versions set
     
   Returns:
     Boolean
     
   Pure: true"
  [version config]
  (let [deprecated-set (set (map name (:deprecated-versions config)))]
    (contains? deprecated-set (name version))))

(defn get-sunset-date
  "Get sunset date for version.
   
   Args:
     version - Version string or keyword
     config - Configuration with :sunset-dates map
     
   Returns:
     Date string or nil
     
   Pure: true"
  [version config]
  (get-in config [:sunset-dates (keyword version)]))

(defn is-sunset?
  "Check if version has passed sunset date.
   
   Args:
     version - Version string or keyword
     config - Configuration with :sunset-dates map
     current-date - ISO 8601 date string (e.g., \"2024-01-04\")
     
   Returns:
     Boolean
     
   Pure: true"
  [version config current-date]
  (when-let [sunset-date (get-sunset-date version config)]
    (>= (compare current-date sunset-date) 0)))

;; =============================================================================
;; Version Validation
;; =============================================================================

(defn is-valid-version?
  "Check if version string is valid and parseable.
   
   Args:
     version - Version string
     
   Returns:
     Boolean
     
   Pure: true"
  [version]
  (some? (parse-version version)))

(defn is-supported-version?
  "Check if version is supported.
   
   Args:
     version - Version string or keyword
     config - Configuration with :supported-versions set
     
   Returns:
     Boolean
     
   Pure: true"
  [version config]
  (contains? (set (:supported-versions config)) (keyword version)))

(defn validate-version*
  "Validate version against configuration using an explicit current date.
   
   Args:
     version - Version string or keyword
     config - Configuration with version settings
     current-date - ISO 8601 date string or comparable date value
     
   Returns:
     {:valid? bool
      :version string
      :errors vector of error messages}
      
   Examples:
     (validate-version \"v1\" {:supported-versions #{:v1 :v2}})
     => {:valid? true :version \"v1\" :errors []}
     
     (validate-version \"v3\" {:supported-versions #{:v1 :v2}})
     => {:valid? false :version \"v3\" :errors [\"Version v3 is not supported\"]}
     
   Pure: true"
  [version config current-date]
  (let [version-str (if (keyword? version) (name version) version)
        errors      (cond-> []
                      (not (is-valid-version? version-str))
                      (conj (str "Invalid version format: " version-str))

                      (and (is-valid-version? version-str)
                           (not (is-supported-version? version-str config)))
                      (conj (str "Version " version-str " is not supported"))

                      (is-sunset? version-str config current-date)
                      (conj (str "Version " version-str " has been sunset")))]
    {:valid?  (empty? errors)
     :version version-str
     :errors  errors}))

;; =============================================================================
;; Version Resolution
;; =============================================================================

(defn resolve-default-version
  "Resolve default version when none specified.
   
   Args:
     config - Configuration with :default-version
     
   Returns:
     Version keyword (e.g., :v1)
     
   Pure: true"
  [config]
  (or (:default-version config) :v1))

(defn resolve-latest-version
  "Resolve latest stable version.
   
   Args:
     config - Configuration with :latest-stable
     
   Returns:
     Version keyword (e.g., :v2)
     
   Pure: true"
  [config]
  (or (:latest-stable config)
      (:default-version config)
      :v1))

(defn extract-version-from-path
  "Extract version from request path.
   
   Args:
     path - Request path (e.g., \"/api/v1/users\", \"/api/users\")
     
   Returns:
     Version keyword or nil
     
   Examples:
     (extract-version-from-path \"/api/v1/users\") => :v1
     (extract-version-from-path \"/api/v2/items\") => :v2
     (extract-version-from-path \"/api/users\")    => nil
     
   Pure: true"
  [path]
  (when path
    (when-let [[_ version] (re-find #"/api/(v\d+(?:\.\d+)?(?:\.\d+)?)" path)]
      (keyword version))))

(defn extract-version-from-header
  "Extract version from custom header.
   
   Args:
     headers - Request headers map
     header-name - Header name (default: \"x-api-version\")
     
   Returns:
     Version keyword or nil
     
   Pure: true"
  [headers header-name]
  (when-let [version (get headers (or header-name "x-api-version"))]
    (keyword version)))

(defn resolve-version
  "Resolve API version from request.
   
   Priority:
   1. URL path (/api/v1/...)
   2. Custom header (X-API-Version)
   3. Default version from config
   
   Args:
     request - Request map with :uri and :headers
     config - Configuration
     
   Returns:
     Resolved version keyword
     
   Pure: true"
  [request config]
  (or (extract-version-from-path (:uri request))
      (extract-version-from-header (:headers request) "x-api-version")
      (resolve-default-version config)))

;; =============================================================================
;; Version Metadata
;; =============================================================================

(defn create-version-metadata
  "Create version metadata for response headers.
   
   Args:
     version - Current version
     config - Configuration
     
   Returns:
     Map of version metadata
     
   Pure: true"
  [version config]
  (let [latest       (resolve-latest-version config)
        deprecated?  (is-deprecated? version config)
        sunset-date  (get-sunset-date version config)]
    (cond-> {:version (name version)}
      ;; Add latest version if different
      (not= version latest)
      (assoc :latest-version (name latest))

      ;; Add deprecation warning
      deprecated?
      (assoc :deprecated true)

      ;; Add sunset date if exists
      sunset-date
      (assoc :sunset-date sunset-date))))

(defn version-headers
  "Generate version-related HTTP headers.
   
   Args:
     version - Current version
     config - Configuration
     
   Returns:
     Map of header name to value
     
   Pure: true"
  [version config]
  (let [metadata (create-version-metadata version config)]
    (cond-> {"X-API-Version" (:version metadata)}
      (:latest-version metadata)
      (assoc "X-API-Version-Latest" (:latest-version metadata))

      (:deprecated metadata)
      (assoc "X-API-Deprecated" "true")

      (:sunset-date metadata)
      (assoc "X-API-Sunset" (:sunset-date metadata)))))

;; =============================================================================
;; Migration Paths
;; =============================================================================

(defn get-migration-path
  "Get migration path from old version to new version.
   
   Args:
     from-version - Starting version
     to-version - Target version
     config - Configuration with :migration-paths
     
   Returns:
     Vector of intermediate versions, or nil if no path
     
   Pure: true"
  [from-version to-version config]
  (get-in config [:migration-paths (keyword from-version) (keyword to-version)]))

(defn requires-migration?
  "Check if migrating between versions requires data migration.
   
   Args:
     from-version - Starting version
     to-version - Target version
     config - Configuration
     
   Returns:
     Boolean
     
   Pure: true"
  [from-version to-version config]
  (some? (get-migration-path from-version to-version config)))
