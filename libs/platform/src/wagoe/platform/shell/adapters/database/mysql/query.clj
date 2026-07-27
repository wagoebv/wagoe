(ns wagoe.platform.shell.adapters.database.mysql.query
  "MySQL query building utilities."
  (:require [wagoe.core.utils.type-conversion :as tc]))

;; =============================================================================
;; Query Building
;; =============================================================================

(defn build-where-clause
  "Build MySQL-specific WHERE clause from filters.

   Uses MySQL LIKE for string matching (case-insensitive by default).

   Args:
     filters: Map of field -> value filters

   Returns:
     HoneySQL WHERE clause fragment or nil"
  [filters]
  (when (seq filters)
    (let [conditions (for [[field value] filters
                           :when (some? value)]
                       (cond
                         (string? value) [:like field (str "%" value "%")] ; MySQL LIKE is case-insensitive by default
                         (vector? value) [:in field value]
                         (boolean? value) [:= field (tc/boolean->int value)] ; MySQL uses TINYINT(1) for booleans
                         :else [:= field value]))]
      (when (seq conditions)
        (if (= 1 (count conditions))
          (first conditions)
          (cons :and conditions))))))

;; =============================================================================
;; Boolean Conversion
;; =============================================================================

(defn boolean->db
  "Convert boolean to MySQL database representation.

   MySQL typically uses TINYINT(1) for boolean values.

   Args:
     boolean-value: Boolean value

   Returns:
     Integer - 1 for true, 0 for false"
  [boolean-value]
  (tc/boolean->int boolean-value))

(defn db->boolean
  "Convert MySQL database boolean to Clojure boolean.

   Convert MySQL TINYINT back to boolean.

   Args:
     db-value: Database boolean value

   Returns:
     Boolean - Clojure boolean"
  [db-value]
  (tc/int->boolean db-value))
