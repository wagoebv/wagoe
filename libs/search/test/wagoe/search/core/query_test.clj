(ns wagoe.search.core.query-test
  "Unit tests for query sanitization and SQL builder functions."
  (:require [clojure.test :refer [deftest testing is]]
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

(deftest ^:unit build-fallback-search-sql-test
  (testing "returns a JDBC SQL vector"
    (let [result (query/build-fallback-search-sql
                  "product-search" "product" "widget" 20 0 nil)]
      (is (vector? result))
      (is (string? (first result)))))

  (testing "SQL uses LOWER/LIKE approach"
    (let [[sql] (query/build-fallback-search-sql
                 "product-search" "product" "widget" 20 0 nil)]
      (is (.contains sql "LOWER"))
      (is (.contains sql "LIKE"))
      (is (.contains sql "LIMIT"))
      (is (.contains sql "OFFSET"))))

  (testing "LIKE pattern wraps query with wildcards"
    (let [[_ _index-id _entity-type pattern] (query/build-fallback-search-sql
                                              "product-search" "product" "Widget" 20 0 nil)]
      ;; query is lowercased and wrapped in %
      (is (= "%widget%" pattern))))

  (testing "handles nil query gracefully"
    (let [[_ _index-id _entity-type pattern] (query/build-fallback-search-sql
                                              "product-search" "product" nil 20 0 nil)]
      (is (= "%%" pattern))))

  (testing "appends filter conditions to SQL"
    (let [[sql & params] (query/build-fallback-search-sql
                          "product-search" "product" "widget" 20 0 {:tenant-id "t1"})]
      (is (.contains sql "INSTR"))
      (is (some #(= "\"tenant_id\":\"t1\"" %) params)))))

;; =============================================================================
;; build-fallback-count-sql
;; =============================================================================

(deftest ^:unit build-fallback-count-sql-test
  (testing "returns correct param order without filters"
    (let [[sql index-id entity-type pattern] (query/build-fallback-count-sql
                                              "product-search" "product" "test" nil)]
      (is (.contains sql "count"))
      (is (= "product-search" index-id))
      (is (= "product" entity-type))
      (is (= "%test%" pattern))))

  (testing "appends filter conditions to SQL"
    (let [[sql & params] (query/build-fallback-count-sql
                          "product-search" "product" "test" {:status "active"})]
      (is (.contains sql "INSTR"))
      (is (some #(= "\"status\":\"active\"" %) params)))))
