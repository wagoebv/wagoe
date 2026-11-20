(ns wagoe.search.shell.query-sql-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [wagoe.platform.shell.adapters.database.common.adapter :as adapter]
            [wagoe.search.shell.query-sql :as query-sql]))

;; =============================================================================
;; build-postgres-search-sql
;; =============================================================================

(deftest ^:unit build-postgres-search-sql-test
  (testing "returns a JDBC SQL vector"
    (let [result (query-sql/build-postgres-search-sql
                  "product-search" "product" "english" "widget" 20 0 false nil)]
      (is (vector? result))
      (is (string? (first result)))))

  (testing "SQL contains key FTS clauses"
    (let [[sql & _params] (query-sql/build-postgres-search-sql
                           "product-search" "product" "english" "widget" 20 0 false nil)]
      (is (.contains sql "to_tsvector"))
      (is (.contains sql "plainto_tsquery"))
      (is (.contains sql "ts_rank"))
      (is (.contains sql "LIMIT"))
      (is (.contains sql "OFFSET"))))

  (testing "includes ts_headline when highlight? is true"
    (let [[sql] (query-sql/build-postgres-search-sql
                 "product-search" "product" "english" "widget" 20 0 true nil)]
      (is (.contains sql "ts_headline"))))

  (testing "does not include ts_headline when highlight? is false"
    (let [[sql] (query-sql/build-postgres-search-sql
                 "product-search" "product" "english" "widget" 20 0 false nil)]
      (is (.contains sql "NULL AS snippet"))))

  (testing "appends filter conditions to WHERE clause"
    (let [[sql & params] (query-sql/build-postgres-search-sql
                          "product-search" "product" "english" "widget" 20 0 false
                          {:tenant-id "abc"})]
      (is (.contains sql "d.filters::jsonb->>?::text = ?"))
      (is (= ["tenant_id" "abc" 20 0] (drop 4 params)))))

  (testing "a filter key is bound, never spliced into the SQL"
    (let [k (keyword "x' OR 'a'<>'b")
          [sql & params] (query-sql/build-postgres-search-sql
                          "i" "t" "english" "w" 20 0 false {k "v"})]
      (is (not (str/includes? sql "OR 'a'")))
      (is (some #{"x' OR 'a'<>'b"} params)))))

;; =============================================================================
;; build-fallback-search-sql
;; =============================================================================

(defn- match
  "The condition an H2 adapter's `like` builds on content_all for `q`."
  [q]
  (adapter/case-insensitive-like :like :content_all :contains q))

(deftest ^:unit build-fallback-search-sql-test
  (testing "the adapter's condition, then filters, limit and offset"
    (let [[sql & params] (query-sql/build-fallback-search-sql
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
    (let [[sql & params] (query-sql/build-fallback-count-sql
                          "product-search" "product" (match "test") nil)]
      (is (str/includes? sql "count"))
      (is (= ["product-search" "product" "%test%"] params))))

  (testing "appends filter conditions to SQL"
    (let [[sql & params] (query-sql/build-fallback-count-sql
                          "product-search" "product" (match "test") {:status "active"})]
      (is (str/includes? sql "INSTR"))
      (is (some #(= "\"status\":\"active\"" %) params))))

  (testing "a quote in a value is escaped as the stored JSON escapes it"
    (let [[_ & params] (query-sql/build-fallback-count-sql
                        "i" "t" (match "x") {:status "a\"b"})]
      (is (= "\"status\":\"a\\\"b\"" (last params))))))

(deftest ^:unit suggest-sql-carries-the-tenant-filter
  ;; Suggestions of a tenant-scoped index are filtered like searches (BOU-568).
  (let [[sql & params] (query-sql/build-postgres-suggest-sql "i" "t" "wid" 5 {:tenant-id "a"})]
    (is (re-find #"FROM search_documents d " sql))
    (is (re-find #"d\.filters::jsonb->>\?::text = \?" sql))
    (is (= ["wid" "i" "t" "wid" "tenant_id" "a" 5] params)))
  (let [[sql & params] (query-sql/build-fallback-suggest-sql "i" "t" (match "wid") 5 {:tenant-id "a"})]
    (is (re-find #"INSTR\(filters, \?\)" sql))
    (is (= ["i" "t" "%wid%" "\"tenant_id\":\"a\"" 5] params))))

(deftest ^:unit trigram-suggest-is-index-eligible
  ;; An operator the GIN index serves, not a word_similarity comparison (BOU-676).
  (let [[sql] (query-sql/build-postgres-suggest-sql "i" "t" "wid" 5 nil)]
    (is (re-find #"\? <% content_all" sql))
    (is (not (re-find #"word_similarity\([^)]*\) >" sql))))
  (is (= ["SELECT set_config('pg_trgm.word_similarity_threshold', ?, true)" "0.3"]
         (query-sql/build-trigram-threshold-sql 0.3))))

(deftest ^:unit fallback-builders-take-the-adapters-condition
  ;; Search, count and suggest share one builder (BOU-608).
  (doseq [build [#(query-sql/build-fallback-search-sql "i" "t" % 5 0 nil)
                 #(query-sql/build-fallback-count-sql "i" "t" % nil)
                 #(query-sql/build-fallback-suggest-sql "i" "t" % 5)
                 #(query-sql/build-postgres-like-suggest-sql "i" "t" % 5 nil)]]
    (let [[sql _ _ pattern] (build (match "A_b 50%"))]
      (is (re-find #"WHERE index_id = \? AND entity_type = \? AND LOWER\(content_all\) LIKE LOWER\(\?\) ESCAPE '!'" sql))
      (is (= "%A!_b 50!%%" pattern)))))

(deftest ^:unit a-set-filter-matches-any-of-its-values
  (testing "PostgreSQL: IN with the key and each value bound"
    (let [[sql & params] (query-sql/build-postgres-count-sql
                          "i" "t" "english" "w" {:tenant-id "t1" :conversation-id #{"c2" "c1"}})]
      (is (str/includes? sql "AND d.filters::jsonb->>?::text IN (?, ?) AND d.filters::jsonb->>?::text = ?"))
      (is (= ["conversation_id" "c1" "c2" "tenant_id" "t1"] (drop 4 params)))))
  (testing "fallback: an OR group of containment checks"
    (let [[sql & params] (query-sql/build-fallback-count-sql
                          "i" "t" (match "w") {:conversation-id ["c1" "c2"]})]
      (is (str/includes? sql "AND (INSTR(filters, ?) > 0 OR INSTR(filters, ?) > 0)"))
      (is (= ["\"conversation_id\":\"c1\"" "\"conversation_id\":\"c2\""] (take-last 2 params)))))
  (testing "an empty set matches nothing, on both paths, and binds no key"
    (let [[pg & pg-params] (query-sql/build-postgres-count-sql "i" "t" "english" "w" {:conversation-id #{}})
          [fb]             (query-sql/build-fallback-count-sql "i" "t" (match "w") {:conversation-id []})]
      (is (str/includes? pg "AND 1 = 0"))
      (is (not-any? #{"conversation_id"} pg-params))
      (is (str/includes? fb "AND 1 = 0")))))

(deftest ^:unit every-builder-binds-a-set-filter-in-place
  (let [f {:conversation-id #{"c2" "c1"}}
        pair (fn [c] (str "\"conversation_id\":\"" c "\""))]
    (testing "PostgreSQL search: filters before limit and offset"
      (is (= ["conversation_id" "c1" "c2" 20 0]
             (drop 4 (rest (query-sql/build-postgres-search-sql "i" "t" "english" "w" 20 0 false f))))))
    (testing "PostgreSQL suggest: filters before the limit"
      (is (= ["conversation_id" "c1" "c2" 5]
             (drop 4 (rest (query-sql/build-postgres-suggest-sql "i" "t" "w" 5 f))))))
    (testing "PostgreSQL LIKE suggest"
      (let [[sql & params] (query-sql/build-postgres-like-suggest-sql "i" "t" (match "w") 5 f)]
        (is (str/includes? sql "IN (?, ?)"))
        (is (= ["conversation_id" "c1" "c2" 5] (take-last 4 params)))))
    (testing "fallback search and suggest"
      (is (= [(pair "c1") (pair "c2") 20 0]
             (take-last 4 (rest (query-sql/build-fallback-search-sql "i" "t" (match "w") 20 0 f)))))
      (is (= [(pair "c1") (pair "c2") 5]
             (take-last 3 (rest (query-sql/build-fallback-suggest-sql "i" "t" (match "w") 5 f))))))))
