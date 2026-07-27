(ns wagoe.platform.shell.adapters.database.postgresql.query
  "PostgreSQL query building utilities.")

;; =============================================================================
;; Query Building
;; =============================================================================

(defn build-where-clause
  "Build PostgreSQL-specific WHERE clause from filters.

   Uses PostgreSQL ILIKE for case-insensitive string matching.

   Args:
     filters: Map of field -> value filters

   Returns:
     HoneySQL WHERE clause fragment or nil"
  [filters]
  (when (seq filters)
    (let [conditions (for [[field value] filters
                           :when (some? value)]
                       (cond
                         (string? value) [:ilike field (str "%" value "%")]
                         (vector? value) [:in field value]
                         (boolean? value) [:= field value]
                         :else [:= field value]))]
      (when (seq conditions)
        (if (= 1 (count conditions))
          (first conditions)
          (cons :and conditions))))))

;; =============================================================================
;; Boolean Conversion
;; =============================================================================

(defn boolean->db
  "Convert boolean to PostgreSQL database representation.

   PostgreSQL supports native boolean values.

   Args:
     boolean-value: Boolean value

   Returns:
     Boolean - native PostgreSQL boolean"
  [boolean-value]
  boolean-value)

(defn db->boolean
  "Convert PostgreSQL database boolean to Clojure boolean.

   PostgreSQL returns native boolean values.

   Args:
     db-value: Database boolean value

   Returns:
     Boolean - Clojure boolean"
  [db-value]
  db-value)
