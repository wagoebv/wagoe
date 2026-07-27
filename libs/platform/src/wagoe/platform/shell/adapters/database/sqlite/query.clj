(ns wagoe.platform.shell.adapters.database.sqlite.query
  "SQLite query building utilities."
  (:require [wagoe.core.utils.type-conversion :as tc]))

;; =============================================================================
;; Query Building
;; =============================================================================

(defn build-where-clause
  "Build SQLite-specific WHERE clause from filters.

   Uses SQLite LIKE for case-insensitive string matching and converts
   booleans to integers for proper SQLite comparison.

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
                         (boolean? value) [:= field (tc/boolean->int value)]
                         :else [:= field value]))]
      (when (seq conditions)
        (if (= 1 (count conditions))
          (first conditions)
          (cons :and conditions))))))

;; =============================================================================
;; Boolean Conversion
;; =============================================================================

(defn boolean->db
  "Convert boolean to SQLite database representation.

   SQLite stores booleans as integers (0/1).

   Args:
     boolean-value: Boolean value

   Returns:
     Integer - 0 for false, 1 for true"
  [boolean-value]
  (tc/boolean->int boolean-value))

(defn db->boolean
  "Convert SQLite database integer to Clojure boolean.

   SQLite stores booleans as integers (0/1).

   Args:
     db-value: Database integer value

   Returns:
     Boolean - Clojure boolean"
  [db-value]
  (tc/int->boolean db-value))