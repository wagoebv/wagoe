(ns wagoe.search.core.query
  "What a search asks for, before any store sees it: the sanitized query and
   the filter terms. `wagoe.search.shell.query-sql` turns them into SQL."
  (:require [wagoe.search.core.index :as index]
            [clojure.string :as str]))

;; =============================================================================
;; Query sanitization
;; =============================================================================

(defn sanitize-query
  "Remove FTS-unsafe characters from a user query string.

   Args:
     query - raw user input string

   Returns:
     Sanitized string safe for use in FTS functions, or nil"
  [query]
  (when query
    (-> query
        (str/replace #"[|&!<>()'\"@\\]" " ")
        str/trim
        (str/replace #"\s+" " "))))

(defn empty-query?
  "Returns true if the query is effectively empty after sanitization."
  [query]
  (or (nil? query)
      (str/blank? (sanitize-query query))))

;; =============================================================================
;; Filters
;; =============================================================================

(defn filter-terms
  "A :filters map as [json-key value] pairs, each value a string, sorted by key
   so that query parameters come out in a stable order. Every term must match."
  [filters]
  (->> (seq filters)
       (sort-by (comp name first))
       (mapv (fn [[k v]] [(index/filter-key->json-key k) (str v)]))))
