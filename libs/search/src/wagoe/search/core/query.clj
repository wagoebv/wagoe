(ns wagoe.search.core.query
  "Pure query-building functions for full-text search.

   Two query paths:
   - PostgreSQL: to_tsvector/plainto_tsquery + ts_rank + ts_headline
   - Fallback (H2/SQLite/MySQL): LOWER/LIKE-based search

   Filter support: callers may pass a :filters map of keyword->string pairs.
   Filter values are matched against the JSON \"filters\" column using
   DB-appropriate JSON path syntax."
  (:require [wagoe.platform.ports.database :as db-ports]
            [wagoe.search.core.index :as index]
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
  ;; ::regconfig: the language is a text column, and to_tsvector takes a
  ;; regconfig; PostgreSQL refused the call (BOU-652).
  (str "setweight(to_tsvector(language::regconfig, coalesce(weight_a,'')), 'A') || "
       "setweight(to_tsvector(language::regconfig, coalesce(weight_b,'')), 'B') || "
       "setweight(to_tsvector(language::regconfig, coalesce(weight_c,'')), 'C') || "
       "setweight(to_tsvector(language::regconfig, coalesce(weight_d,'')), 'D')"))

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
                       (str "ts_headline(d.language::regconfig, d.content_all, q.tsq, "
                            "'MaxWords=20,MinWords=5,StartSel=<b>,StopSel=</b>') AS snippet")
                       "NULL AS snippet")
        [filter-sql & filter-params] (build-filter-sql-postgres (or filters {}))
        sql (str "WITH docs AS ("
                 "  SELECT entity_type, entity_id, metadata, content_all, language, filters, "
                 tsvector-sql " AS tsv "
                 "  FROM search_documents "
                 "  WHERE index_id = ? AND entity_type = ? "
                 "), "
                 "q AS (SELECT plainto_tsquery(?::regconfig, ?) AS tsq) "
                 "SELECT d.entity_type, d.entity_id, d.metadata, "
                 "  ts_rank(d.tsv, q.tsq) AS score, "
                 snippet-expr " "
                 "FROM docs d, q "
                 "WHERE d.tsv @@ q.tsq "
                 filter-sql " "
                 "ORDER BY score DESC "
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
                 "q AS (SELECT plainto_tsquery(?::regconfig, ?) AS tsq) "
                 "SELECT count(*) AS cnt "
                 "FROM docs d, q "
                 "WHERE d.tsv @@ q.tsq "
                 filter-sql)]
    (into [sql index-id entity-type language query] filter-params)))

(defn build-trigram-threshold-sql
  "Set pg_trgm.word_similarity_threshold, which `<%` compares against, for the
   current transaction. Run it in the transaction of build-postgres-suggest-sql."
  [threshold]
  ["SELECT set_config('pg_trgm.word_similarity_threshold', ?, true)" (str threshold)])

(defn build-postgres-suggest-sql
  "Build a parameterized SQL vector for trigram similarity suggestions.

   Requires pg_trgm. word_similarity, not similarity: a prefix scored against a
   whole document stays under any useful threshold (BOU-661). The `<%` operator
   rather than a word_similarity comparison, so the trigram index on content_all
   can serve it; its threshold comes from build-trigram-threshold-sql (BOU-676).

   Args:
     index-id    - string
     entity-type - string
     query       - string (partial user input)
     limit       - integer
     filters     - map of keyword->any (optional)

   Returns:
     JDBC SQL vector [sql & params]"
  [index-id entity-type query limit filters]
  (let [[filter-sql & filter-params] (build-filter-sql-postgres (or filters {}))
        sql (str "SELECT entity_type, entity_id, metadata, "
                 "  word_similarity(?, content_all) AS score, "
                 "  NULL AS snippet "
                 "FROM search_documents d "
                 "WHERE index_id = ? AND entity_type = ? "
                 "  AND ? <% content_all "
                 filter-sql " "
                 "ORDER BY score DESC "
                 "LIMIT ?")]
    (-> [sql query index-id entity-type query]
        (into filter-params)
        (conj limit))))

;; =============================================================================
;; Fallback SQL builders (H2 / SQLite)
;; =============================================================================

(def ^:private like-clause "LOWER(content_all) LIKE ? ESCAPE '!'")

(defn- like-pattern
  "A LIKE pattern containing `query`, lowercased, for like-clause (BOU-676)."
  [query]
  (db-ports/like-pattern :contains (str/lower-case (or (sanitize-query query) ""))))

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
  (let [pattern (like-pattern query)
        [filter-sql & filter-params] (build-filter-sql-fallback (or filters {}))
        sql (str "SELECT entity_type, entity_id, metadata, "
                 "  1.0 AS score, "
                 "  NULL AS snippet "
                 "FROM search_documents "
                 "WHERE index_id = ? AND entity_type = ? "
                 "  AND " like-clause " "
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
  (let [pattern (like-pattern query)
        [filter-sql & filter-params] (build-filter-sql-fallback (or filters {}))
        sql (str "SELECT count(*) AS cnt "
                 "FROM search_documents "
                 "WHERE index_id = ? AND entity_type = ? "
                 "  AND " like-clause " "
                 filter-sql)]
    (into [sql index-id entity-type pattern] filter-params)))

(defn- like-suggest-sql
  [build-filter-sql index-id entity-type query limit filters]
  (let [[filter-sql & filter-params] (build-filter-sql (or filters {}))
        pattern (like-pattern query)
        sql (str "SELECT entity_type, entity_id, metadata, "
                 "  0.5 AS score, "
                 "  NULL AS snippet "
                 "FROM search_documents d "
                 "WHERE index_id = ? AND entity_type = ? "
                 "  AND " like-clause " "
                 filter-sql " "
                 "ORDER BY updated_at DESC "
                 "LIMIT ?")]
    (-> [sql index-id entity-type pattern]
        (into filter-params)
        (conj limit))))

(defn build-fallback-suggest-sql
  "Build a LIKE-based fallback suggest query for H2/SQLite.

   Args:
     index-id    - string
     entity-type - string
     query       - string
     limit       - integer
     filters     - map of keyword->any (optional)

   Returns:
     JDBC SQL vector [sql & params]"
  ([index-id entity-type query limit]
   (build-fallback-suggest-sql index-id entity-type query limit nil))
  ([index-id entity-type query limit filters]
   (like-suggest-sql build-filter-sql-fallback index-id entity-type query limit filters)))

(defn build-postgres-like-suggest-sql
  "The LIKE suggest query with PostgreSQL's filter SQL, for a database without
   pg_trgm (BOU-661). Same arguments as build-fallback-suggest-sql."
  [index-id entity-type query limit filters]
  (like-suggest-sql build-filter-sql-postgres index-id entity-type query limit filters))
