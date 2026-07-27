(ns wagoe.platform.shell.adapters.database.h2.query
  "H2 query building utilities.")

;; =============================================================================
;; Query Building
;; =============================================================================

(defn build-where-clause
  "Build H2-specific WHERE clause from filters.

   Uses H2 LIKE for case-insensitive string matching. H2 supports
   native boolean values so no conversion is needed.

   Args:
     filters: Map of field -> value filters

   Returns:
     HoneySQL WHERE clause fragment or nil"
  [filters]
  (when (seq filters)
    (let [conditions (for [[field value] filters
                           :when (some? value)]
                       (cond
                         (string? value) [:like field (str "%" value "%")]
                         (vector? value) [:in field value]
                         (boolean? value) [:= field value] ; H2 supports native booleans
                         :else [:= field value]))]
      (when (seq conditions)
        (if (= 1 (count conditions))
          (first conditions)
          (cons :and conditions))))))

;; =============================================================================
;; Boolean Conversion
;; =============================================================================

(defn boolean->db
  "Convert boolean to H2 database representation.

   H2 supports native boolean values.

   Args:
     boolean-value: Boolean value

   Returns:
     Boolean - native H2 boolean"
  [boolean-value]
  boolean-value)

(defn db->boolean
  "Convert H2 database boolean to Clojure boolean.

   H2 returns native boolean values.

   Args:
     db-value: Database boolean value

   Returns:
     Boolean - Clojure boolean"
  [db-value]
  db-value)
