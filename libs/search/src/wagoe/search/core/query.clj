(ns wagoe.search.core.query
  "Pure query-building functions for full-text search.

   Two query paths:
   - PostgreSQL: to_tsvector/plainto_tsquery + ts_rank + ts_headline
   - Fallback (H2/SQLite): LOWER/LIKE-based search

   Filter support: callers may pass a :filters map of keyword->string pairs.
   Filter values are matched against the JSON \"filters\" column using
   DB-appropriate JSON path syntax."
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
;; Filter SQL helpers
;; =============================================================================

(defn- build-filter-sql-postgres
  "Build PostgreSQL WHERE conditions for a filter map.

   Each filter is rendered as:
     AND d.filters::jsonb->>'<key>' = ?

   A NULL filters column or absent key causes the condition to evaluate to
   NULL (which is falsy), so documents without that filter value are excluded.

   Entries are sorted by key name to guarantee parameter order is stable.

   Returns [sql-fragment & param-values] or [\"\" ] when filters is empty."
  [filters]
  (if (empty? filters)
    [""]
    (let [sorted (sort-by (comp name first) (seq filters))
          sql    (str/join " "
                           (map (fn [[k _]]
                                  (str "AND d.filters::jsonb->>'"
                                       (index/filter-key->json-key k) "' = ?"))
                                sorted))
          params (mapv (fn [[_ v]] (str v)) sorted)]
      (into [sql] params))))

(defn- build-filter-sql-fallback
  "Build H2/SQLite WHERE conditions for a filter map.

   Each filter is rendered as:
     AND INSTR(filters, '\"<key>\":\"<val>\"') > 0

   Uses string containment rather than JSON functions because H2 2.4 does not
   expose JSON_VALUE, JSON_EXTRACT, or the PostgreSQL ->> operator via JDBC
   prepared statements. The approach is reliable because `filters->json` always
   emits compact JSON with no spaces:
     {\"tenant_id\":\"abc\",\"status\":\"active\"}
   so searching for \"key\":\"value\" will not produce false positives within a
   realistic set of filter keys and string values.

   Returns [sql-fragment & param-values] or [\"\" ] when filters is empty."
  [filters]
  (if (empty? filters)
    [""]
    (let [sorted (sort-by (comp name first) (seq filters))
          sql    (str/join " " (map (fn [_] "AND INSTR(filters, ?) > 0") sorted))
          params (mapv (fn [[k v]]
                         (str "\"" (index/filter-key->json-key k) "\":\"" v "\""))
                       sorted)]
      (into [sql] params))))

;; =============================================================================
;; PostgreSQL FTS SQL builders
;; =============================================================================

(def ^:private tsvector-sql
  "SQL fragment that builds a weighted tsvector from the four weight columns."
  (str "setweight(to_tsvector(language, coalesce(weight_a,'')), 'A') || "
       "setweight(to_tsvector(language, coalesce(weight_b,'')), 'B') || "
       "setweight(to_tsvector(language, coalesce(weight_c,'')), 'C') || "
       "setweight(to_tsvector(language, coalesce(weight_d,'')), 'D')"))

(defn build-postgres-search-sql
  "Build a parameterized JDBC SQL vector for PostgreSQL FTS search.

   Uses a CTE to compute the tsvector and tsquery once, then ranks
   and optionally highlights results. Uses plainto_tsquery for safe
   handling of arbitrary user input (no FTS syntax required).

   Args:
     index-id    - string (e.g. \"product-search\")
     entity-type - string (e.g. \"product\")
     language    - string FTS config (e.g. \"english\")
     query       - string (user search query)
     limit       - integer
     offset      - integer
     highlight?  - boolean
     filters     - map of keyword->any (optional, nil treated as empty)

   Returns:
     JDBC SQL vector [sql & params]"
  [index-id entity-type language query limit offset highlight? filters]
  (let [snippet-expr (if highlight?
                       (str "ts_headline(d.language, d.content_all, q.tsq, "
                            "'MaxWords=20,MinWords=5,StartSel=<b>,StopSel=</b>') AS snippet")
                       "NULL AS snippet")
        [filter-sql & filter-params] (build-filter-sql-postgres (or filters {}))
        sql (str "WITH docs AS ("
                 "  SELECT entity_type, entity_id, metadata, content_all, language, filters, "
                 tsvector-sql " AS tsv "
                 "  FROM search_documents "
                 "  WHERE index_id = ? AND entity_type = ? "
                 "), "
                 "q AS (SELECT plainto_tsquery(?, ?) AS tsq) "
                 "SELECT d.entity_type, d.entity_id, d.metadata, "
                 "  ts_rank(d.tsv, q.tsq) AS rank, "
                 snippet-expr " "
                 "FROM docs d, q "
                 "WHERE d.tsv @@ q.tsq "
                 filter-sql " "
                 "ORDER BY rank DESC "
                 "LIMIT ? OFFSET ?")]
    (into [sql index-id entity-type language query] (concat filter-params [limit offset]))))

(defn build-postgres-count-sql
  "Build a parameterized JDBC SQL vector for counting PostgreSQL FTS results.

   Args:
     index-id    - string
     entity-type - string
     language    - string
     query       - string
     filters     - map of keyword->any (optional, nil treated as empty)

   Returns:
     JDBC SQL vector [sql & params]"
  [index-id entity-type language query filters]
  (let [[filter-sql & filter-params] (build-filter-sql-postgres (or filters {}))
        sql (str "WITH docs AS ("
                 "  SELECT language, filters, " tsvector-sql " AS tsv "
                 "  FROM search_documents "
                 "  WHERE index_id = ? AND entity_type = ? "
                 "), "
                 "q AS (SELECT plainto_tsquery(?, ?) AS tsq) "
                 "SELECT count(*) AS cnt "
                 "FROM docs d, q "
                 "WHERE d.tsv @@ q.tsq "
                 filter-sql)]
    (into [sql index-id entity-type language query] filter-params)))

(defn build-postgres-suggest-sql
  "Build a parameterized SQL vector for trigram similarity suggestions.

   Requires: CREATE EXTENSION IF NOT EXISTS pg_trgm;

   Args:
     index-id    - string
     entity-type - string
     query       - string (partial user input)
     limit       - integer
     threshold   - number (similarity threshold, e.g. 0.15)

   Returns:
     JDBC SQL vector [sql & params]"
  [index-id entity-type query limit threshold]
  (let [sql (str "SELECT entity_type, entity_id, metadata, "
                 "  similarity(content_all, ?) AS rank, "
                 "  NULL AS snippet "
                 "FROM search_documents "
                 "WHERE index_id = ? AND entity_type = ? "
                 "  AND similarity(content_all, ?) > ? "
                 "ORDER BY rank DESC "
                 "LIMIT ?")]
    [sql query index-id entity-type query threshold limit]))

;; =============================================================================
;; Fallback SQL builders (H2 / SQLite)
;; =============================================================================

(defn build-fallback-search-sql
  "Build a parameterized JDBC SQL vector for LIKE-based fallback search.

   Used for H2 (tests) and SQLite. Returns a static rank of 1.0, no snippet.

   Args:
     index-id    - string
     entity-type - string
     query       - string (user search query)
     limit       - integer
     offset      - integer
     filters     - map of keyword->any (optional, nil treated as empty)

   Returns:
     JDBC SQL vector [sql & params]"
  [index-id entity-type query limit offset filters]
  (let [pattern (str "%" (str/lower-case (or (sanitize-query query) "")) "%")
        [filter-sql & filter-params] (build-filter-sql-fallback (or filters {}))
        sql (str "SELECT entity_type, entity_id, metadata, "
                 "  1.0 AS rank, "
                 "  NULL AS snippet "
                 "FROM search_documents "
                 "WHERE index_id = ? AND entity_type = ? "
                 "  AND LOWER(content_all) LIKE ? "
                 filter-sql " "
                 "ORDER BY updated_at DESC "
                 "LIMIT ? OFFSET ?")]
    (into [sql index-id entity-type pattern] (concat filter-params [limit offset]))))

(defn build-fallback-count-sql
  "Build a parameterized count SQL for fallback search.

   Args:
     index-id    - string
     entity-type - string
     query       - string
     filters     - map of keyword->any (optional, nil treated as empty)

   Returns:
     JDBC SQL vector [sql & params]"
  [index-id entity-type query filters]
  (let [pattern (str "%" (str/lower-case (or (sanitize-query query) "")) "%")
        [filter-sql & filter-params] (build-filter-sql-fallback (or filters {}))
        sql (str "SELECT count(*) AS cnt "
                 "FROM search_documents "
                 "WHERE index_id = ? AND entity_type = ? "
                 "  AND LOWER(content_all) LIKE ? "
                 filter-sql)]
    (into [sql index-id entity-type pattern] filter-params)))

(defn build-fallback-suggest-sql
  "Build a LIKE-based fallback suggest query for H2/SQLite.

   Args:
     index-id    - string
     entity-type - string
     query       - string
     limit       - integer

   Returns:
     JDBC SQL vector [sql & params]"
  [index-id entity-type query limit]
  (let [pattern (str "%" (str/lower-case (or (sanitize-query query) "")) "%")
        sql (str "SELECT entity_type, entity_id, metadata, "
                 "  0.5 AS rank, "
                 "  NULL AS snippet "
                 "FROM search_documents "
                 "WHERE index_id = ? AND entity_type = ? "
                 "  AND LOWER(content_all) LIKE ? "
                 "ORDER BY updated_at DESC "
                 "LIMIT ?")]
    [sql index-id entity-type pattern limit]))
