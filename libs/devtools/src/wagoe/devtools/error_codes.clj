(ns wagoe.devtools.error-codes
  "Error code catalog for Boundary. Loads from the shared EDN resource
   so the Babashka CLI and JVM runtime share one source of truth.

   Not under core/ deliberately — loading a resource requires I/O,
   which is forbidden in core namespaces by the FC/IS boundary check.

   Error code ranges:
     BND-1xx  Configuration errors
     BND-2xx  Validation errors
     BND-3xx  Persistence errors
     BND-4xx  Authentication/authorization errors
     BND-5xx  Interceptor pipeline errors
     BND-6xx  FC/IS boundary violations
     BND-7xx  Tooling / build errors
     BND-8xx  MCP guardrails (wagoe-mcp)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; =============================================================================
;; Error catalog — single source of truth
;; =============================================================================

(def catalog
  "Map of error code string to error definition.
   Loaded from resources/boundary/devtools/error_catalog.edn."
  (let [r (io/resource "wagoe/devtools/error_catalog.edn")]
    (when-not r
      (throw (ex-info "error_catalog.edn not found on classpath" {})))
    (-> r slurp edn/read-string)))

;; =============================================================================
;; Lookup functions
;; =============================================================================

(defn lookup
  "Look up an error code. Returns the error definition map or nil."
  [code]
  (get catalog code))

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
    :config      "BND-1xx"
    :validation  "BND-2xx"
    :persistence "BND-3xx"
    :auth        "BND-4xx"
    :interceptor "BND-5xx"
    :fcis        "BND-6xx"
    :tooling     "BND-7xx"
    :mcp         "BND-8xx"
    "BND-???"))
