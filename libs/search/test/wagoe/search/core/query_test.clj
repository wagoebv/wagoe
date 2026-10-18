(ns wagoe.search.core.query-test
  "Unit tests for query sanitization and SQL builder functions."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [wagoe.platform.shell.adapters.database.common.adapter :as adapter]
            [wagoe.search.core.query :as query]))

;; =============================================================================
;; sanitize-query
;; =============================================================================

(deftest ^:unit sanitize-query-test
  (testing "passes through normal query strings unchanged"
    (is (= "widget pro" (query/sanitize-query "widget pro"))))

  (testing "removes FTS-unsafe characters"
    (is (= "hello world" (query/sanitize-query "hello | world")))
    (is (= "hello world" (query/sanitize-query "hello & world")))
    (is (= "hello world" (query/sanitize-query "hello (world)")))
    (is (= "hello world" (query/sanitize-query "hello 'world'"))))

  (testing "trims leading and trailing whitespace"
    (is (= "widget" (query/sanitize-query "  widget  "))))

  (testing "collapses multiple spaces"
    (is (= "widget pro max" (query/sanitize-query "widget   pro  max"))))

  (testing "returns nil for nil input"
    (is (nil? (query/sanitize-query nil))))

  (testing "returns trimmed string for whitespace-only input"
    (is (= "" (query/sanitize-query "   ")))))

;; =============================================================================
;; empty-query?
;; =============================================================================

(deftest ^:unit empty-query-test
  (testing "nil is empty"
    (is (query/empty-query? nil)))

  (testing "blank string is empty"
    (is (query/empty-query? "   ")))

  (testing "only unsafe chars is effectively empty"
    (is (query/empty-query? "| & ! < > ( ) ' \" @ \\")))

  (testing "non-empty query is not empty"
    (is (not (query/empty-query? "widget"))))

  (testing "whitespace-padded query is not empty"
    (is (not (query/empty-query? "  widget  ")))))

;; =============================================================================
;; build-postgres-search-sql
;; =============================================================================

(deftest ^:unit build-postgres-search-sql-test
  (testing "returns a JDBC SQL vector"
    (let [result (query/build-postgres-search-sql
                  "product-search" "product" "english" "widget" 20 0 false nil)]
      (is (vector? result))
      (is (string? (first result)))))

  (testing "SQL contains key FTS clauses"
    (let [[sql & _params] (query/build-postgres-search-sql
                           "product-search" "product" "english" "widget" 20 0 false nil)]
      (is (.contains sql "to_tsvector"))
      (is (.contains sql "plainto_tsquery"))
      (is (.contains sql "ts_rank"))
      (is (.contains sql "LIMIT"))
      (is (.contains sql "OFFSET"))))

  (testing "includes ts_headline when highlight? is true"
    (let [[sql] (query/build-postgres-search-sql
                 "product-search" "product" "english" "widget" 20 0 true nil)]
      (is (.contains sql "ts_headline"))))

  (testing "does not include ts_headline when highlight? is false"
    (let [[sql] (query/build-postgres-search-sql
                 "product-search" "product" "english" "widget" 20 0 false nil)]
      (is (.contains sql "NULL AS snippet"))))

  (testing "appends filter conditions to WHERE clause"
    (let [[sql & params] (query/build-postgres-search-sql
                          "product-search" "product" "english" "widget" 20 0 false
                          {:tenant-id "abc"})]
      (is (.contains sql "d.filters::jsonb->>'tenant_id' = ?"))
      (is (some #(= "abc" %) params)))))

;; =============================================================================
;; build-fallback-search-sql
;; =============================================================================

(defn- match
  "The condition an H2 adapter's `like` builds on content_all for `q`."
  [q]
  (adapter/case-insensitive-like :like :content_all :contains q))

(deftest ^:unit build-fallback-search-sql-test
  (testing "the adapter's condition, then filters, limit and offset"
    (let [[sql & params] (query/build-fallback-search-sql
                          "product-search" "product" (match "Widget") 20 0 {:tenant-id "t1"})]
      (is (str/includes? sql "LOWER(content_all) LIKE LOWER(?) ESCAPE '!'"))
      (is (str/includes? sql "INSTR"))
      (is (str/includes? sql "LIMIT ? OFFSET ?"))
      ;; Lowered by the database, not the JVM (BOU-608).
      (is (= ["product-search" "product" "%Widget%" "\"tenant_id\":\"t1\"" 20 0] params)))))

;; =============================================================================
;; build-fallback-count-sql
;; =============================================================================

(deftest ^:unit build-fallback-count-sql-test
  (testing "returns correct param order without filters"
    (let [[sql & params] (query/build-fallback-count-sql
                          "product-search" "product" (match "test") nil)]
      (is (str/includes? sql "count"))
      (is (= ["product-search" "product" "%test%"] params))))

  (testing "appends filter conditions to SQL"
    (let [[sql & params] (query/build-fallback-count-sql
                          "product-search" "product" (match "test") {:status "active"})]
      (is (str/includes? sql "INSTR"))
      (is (some #(= "\"status\":\"active\"" %) params)))))

(deftest ^:unit suggest-sql-carries-the-tenant-filter
  ;; Suggestions of a tenant-scoped index are filtered like searches (BOU-568).
  (let [[sql & params] (query/build-postgres-suggest-sql "i" "t" "wid" 5 {:tenant-id "a"})]
    (is (re-find #"FROM search_documents d " sql))
    (is (re-find #"d\.filters::jsonb->>'tenant_id' = \?" sql))
    (is (= ["wid" "i" "t" "wid" "a" 5] params)))
  (let [[sql & params] (query/build-fallback-suggest-sql "i" "t" (match "wid") 5 {:tenant-id "a"})]
    (is (re-find #"INSTR\(filters, \?\)" sql))
    (is (= ["i" "t" "%wid%" "\"tenant_id\":\"a\"" 5] params))))

(deftest ^:unit trigram-suggest-is-index-eligible
  ;; An operator the GIN index serves, not a word_similarity comparison (BOU-676).
  (let [[sql] (query/build-postgres-suggest-sql "i" "t" "wid" 5 nil)]
    (is (re-find #"\? <% content_all" sql))
    (is (not (re-find #"word_similarity\([^)]*\) >" sql))))
  (is (= ["SELECT set_config('pg_trgm.word_similarity_threshold', ?, true)" "0.3"]
         (query/build-trigram-threshold-sql 0.3))))

(deftest ^:unit fallback-builders-take-the-adapters-condition
  ;; Search, count and suggest share one builder (BOU-608).
  (doseq [build [#(query/build-fallback-search-sql "i" "t" % 5 0 nil)
                 #(query/build-fallback-count-sql "i" "t" % nil)
                 #(query/build-fallback-suggest-sql "i" "t" % 5)
                 #(query/build-postgres-like-suggest-sql "i" "t" % 5 nil)]]
    (let [[sql _ _ pattern] (build (match "A_b 50%"))]
      (is (re-find #"WHERE index_id = \? AND entity_type = \? AND LOWER\(content_all\) LIKE LOWER\(\?\) ESCAPE '!'" sql))
      (is (= "%A!_b 50!%%" pattern)))))
