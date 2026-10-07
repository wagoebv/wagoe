(ns wagoe.devtools.error-codes
  "Error code catalog for Wagoe. Loads from the shared EDN resource
   so the Babashka CLI and JVM runtime share one source of truth.

   Not under core/ deliberately — loading a resource requires I/O,
   which is forbidden in core namespaces by the FC/IS boundary check.

   Error code ranges:
     WGE-1xx  Configuration errors
     WGE-2xx  Validation errors
     WGE-3xx  Persistence errors
     WGE-4xx  Authentication/authorization errors
     WGE-5xx  Interceptor pipeline errors
     WGE-6xx  FC/IS boundary violations
     WGE-7xx  Tooling / build errors
     WGE-8xx  MCP guardrails (wagoe-mcp)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Error catalog — single source of truth
;; =============================================================================

(def catalog
  "Map of error code string to error definition.
   Loaded from resources/wagoe/devtools/error_catalog.edn."
  (let [r (io/resource "wagoe/devtools/error_catalog.edn")]
    (when-not r
      (throw (ex-info "error_catalog.edn not found on classpath" {})))
    (-> r slurp edn/read-string)))

;; =============================================================================
;; Lookup functions
;; =============================================================================

(defn canonical-code
  "`code` upper-cased, with the pre-1.0.1 BND- prefix read as WGE-, so a code
   from an old log or doc still resolves."
  [code]
  (when (string? code)
    (str/replace (str/upper-case code) #"^BND-" "WGE-")))

(defn lookup
  "Look up an error code. Returns the error definition map or nil."
  [code]
  (get catalog (canonical-code code)))

(defn by-category
  "Get all error codes for a category (:config, :validation, :persistence, :auth, :interceptor, :fcis, :tooling)."
  [category]
  (->> (vals catalog)
       (filter #(= category (:category %)))
       (sort-by :code)))

(defn all-codes
  "Get all error codes sorted."
  []
  (sort-by :code (vals catalog)))

(defn category-range
  "Get the human-readable range description for a category."
  [category]
  (case category
    :config      "WGE-1xx"
    :validation  "WGE-2xx"
    :persistence "WGE-3xx"
    :auth        "WGE-4xx"
    :interceptor "WGE-5xx"
    :fcis        "WGE-6xx"
    :tooling     "WGE-7xx"
    :mcp         "WGE-8xx"
    "WGE-???"))
