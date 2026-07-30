(ns wagoe.devtools.core.introspection
  "Pure formatting functions for REPL display of routes, config, and modules.
   No I/O, no side effects — data in, formatted strings out."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private secret-patterns
  "Substrings that indicate a key holds sensitive data.
   Uses substring matching on the key name so compound keys like
   :auth-token, :webhook-secret, :stripe-api-key are caught."
  ["password" "secret" "api-key" "token" "private-key"
   "access-key" "credentials" "apikey"])

(defn- secret-key?
  "Return true if the key name contains any secret pattern."
  [k]
  (let [kn (name k)]
    (some #(str/includes? kn %) secret-patterns)))

(def ^:private section-aliases
  {:database     ["postgresql" "sqlite" "mysql" "h2" "db-context"]
   :http         ["http" "http-server"]
   :auth         ["auth-service" "mfa-service" "session-repository"]
   :user         ["user-service" "user-repository" "user-routes"]
   :admin        ["admin-service" "admin-routes" "admin-schema-provider"]
   :observability ["logging" "metrics" "error-reporting"]})

;; =============================================================================
;; Route helpers
;; =============================================================================

(defn- handler-short-name
  "Return the last segment after the final dot, e.g. 'http/list-users'."
  [handler-str]
  (if (string? handler-str)
    (let [parts (str/split handler-str #"\.")]
      (last parts))
    (str handler-str)))

(defn filter-routes
  "Filter a seq of route maps.
   - keyword  -> filter by :module field matching (name filter-key)
   - string   -> filter by substring match on :path
   - otherwise return routes unchanged"
  [routes filter-key]
  (cond
    (keyword? filter-key)
    (filter #(= (:module %) (name filter-key)) routes)

    (string? filter-key)
    (filter #(str/includes? (str (:path %)) filter-key) routes)

    :else routes))

(defn format-route-table
  "Format a seq of route maps as an aligned table with METHOD, PATH, HANDLER columns.
   Each route: {:method :get :path \"/api/users\" :handler \"wagoe.user.shell.http/list-users\" :module \"user\"}.
   Handler column shows just the last dot-segment (e.g. \"http/list-users\").
   Returns \"No routes found.\" for empty or nil input."
  [routes]
  (if (empty? routes)
    "No routes found."
    (let [header     ["METHOD" "PATH" "HANDLER"]
          rows       (mapv (fn [{:keys [method path handler]}]
                             [(str/upper-case (name (or method "")))
                              (str path)
                              (handler-short-name (str handler))])
                           routes)
          all-rows   (into [header] rows)
          col-widths (mapv (fn [i]
                             (apply max (map #(count (nth % i)) all-rows)))
                           [0 1 2])
          pad        (fn [s w] (str s (apply str (repeat (- w (count s)) " "))))
          fmt-row    (fn [[m p h]]
                       (str "  " (pad m (nth col-widths 0))
                            "  " (pad p (nth col-widths 1))
                            "  " h))
          sep        (str "  " (str/join "  " (map #(apply str (repeat % "-")) col-widths)))]
      (str/join "\n"
                (concat [(fmt-row header) sep]
                        (map fmt-row rows))))))

;; =============================================================================
;; Config tree helpers
;; =============================================================================

(defn- redact-map
  "Recursively redact secret values in a map at any nesting depth."
  [m]
  (into {}
        (map (fn [[k v]]
               [k (cond
                    (secret-key? k) "****"
                    (map? v) (redact-map v)
                    :else v)]))
        m))

(defn- format-map-entry
  "Format a single map entry as indented lines, recursing into nested maps."
  [indent k v]
  (let [spaces (apply str (repeat indent " "))
        redacted (cond
                   (secret-key? k) "****"
                   (map? v) (redact-map v)
                   :else v)]
    (if (map? redacted)
      (str/join "\n"
                (into [(str spaces (pr-str k) ":")]
                      (map (fn [[ik iv]]
                             (if (map? iv)
                               (format-map-entry (+ indent 2) ik iv)
                               (str spaces "  " (pr-str ik) ": " (pr-str iv))))
                           redacted)))
      (str spaces (pr-str k) ": " (pr-str redacted)))))

(defn- section-matches?
  "Return true if the string representation of config-key contains any alias substring."
  [config-key aliases]
  (let [ks (str config-key)]
    (some #(str/includes? ks %) aliases)))

(defn format-config-tree
  "Format an Integrant config map as an indented tree with secrets redacted.
   When `section` keyword is provided, drill into matching keys only."
  ([config]
   (if (empty? config)
     "Empty configuration."
     (str/join "\n\n"
               (map (fn [[k v]]
                      (format-map-entry 0 k v))
                    config))))
  ([config section]
   (if-let [aliases (get section-aliases section)]
     (let [filtered (into {}
                          (filter (fn [[k _]]
                                    (section-matches? k aliases))
                                  config))]
       (if (empty? filtered)
         (str "No config keys found for section: " (name section))
         (format-config-tree filtered)))
     (str "Unknown section: " (name section)))))

;; =============================================================================
;; Module summary
;; =============================================================================

(defn format-module-summary
  "Format a seq of module maps as a summary table.
   Each module: {:name \"user\" :components 5}.
   Name column width adapts to longest name."
  [modules]
  (if (empty? modules)
    "No modules found."
    (let [name-strs (mapv #(str (:name %)) modules)
          name-w    (apply max (count "MODULE") (map count name-strs))
          pad       (fn [s] (let [s (str s)] (str s (apply str (repeat (- name-w (count s)) " ")))))
          header    (str "  " (pad "MODULE") "  COMPONENTS")]
      (str/join "\n"
                (into [header]
                      (map (fn [{:keys [name components]}]
                             (str "  " (pad (str name)) "  " components))
                           modules))))))
