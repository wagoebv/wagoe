(ns wagoe.search.shell.persistence-test
  "Integration tests for SearchStore persistence layer, on the engine
   WAGOE_TEST_DB names (H2 by default).

   On H2 this is the LIKE-based fallback path (H2 claims neither :full-text nor
   :on-conflict); on PostgreSQL the FTS path. Validates:
   - upsert-document! (insert + conflict update)
   - delete-document!
   - search-documents (LIKE fallback)
   - count-results (LIKE fallback)
   - suggest-documents (pg_trgm on PostgreSQL, LIKE without it)
   - count-documents"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.search.test.support :refer [find-ID-under-a-turkish-locale
                                                create-search-documents-table! make-doc]]
            [wagoe.search.ports :as ports]
            [wagoe.search.shell.persistence :as persistence]
            [wagoe.search.shell.trigram :as trigram]
            [wagoe.search.core.query :as qry]
            [clojure.string :as str]
            [support.test-db :as test-db]
            [next.jdbc :as jdbc])
  (:import [java.util UUID]))

;; =============================================================================
;; Test database setup
;; =============================================================================

(def ^:private test-store      (atom nil))
(def ^:private test-adapter    (atom nil))

(defn- db-fixture [test-fn]
  (test-db/with-db "search"
    (fn [{:keys [datasource adapter]}]
      (create-search-documents-table! datasource)
      (reset! test-adapter adapter)
      (reset! test-store (persistence/create-search-store datasource adapter))
      (try (test-fn)
           (finally (reset! test-store nil))))))

(use-fixtures :each db-fixture)

(defn- full-text? [] (:full-text? @test-store))

;; =============================================================================
;; upsert-document! / count-documents
;; =============================================================================

(deftest ^:integration upsert-insert-test
  (testing "inserts a new document and count reflects it"
    (let [doc (make-doc)]
      (ports/upsert-document! @test-store doc)
      (is (= 1 (ports/count-documents @test-store :product-search))))))

(deftest ^:integration upsert-conflict-test
  (testing "updates existing document on conflict (same entity-id)"
    (let [entity-id (UUID/randomUUID)
          doc1      (make-doc {:entity-id entity-id :content-all "Original"})
          doc2      (make-doc {:id (str (UUID/randomUUID))
                               :entity-id entity-id :content-all "Updated"})]
      (ports/upsert-document! @test-store doc1)
      (ports/upsert-document! @test-store doc2)
      ;; Should be exactly 1: upsert deduplicates on (index_id, entity_id)
      (is (= 1 (ports/count-documents @test-store :product-search))))))

(deftest ^:integration count-documents-unknown-index-test
  (testing "count-documents is 0 for unknown index"
    (is (= 0 (ports/count-documents @test-store :unknown-index)))))

;; =============================================================================
;; delete-document!
;; =============================================================================

(deftest ^:integration delete-document-test
  (testing "deletes an existing document"
    (let [doc (make-doc)]
      (ports/upsert-document! @test-store doc)
      (ports/delete-document! @test-store :product-search (:entity-id doc))
      (is (= 0 (ports/count-documents @test-store :product-search)))))

  (testing "delete of non-existent document is a no-op"
    (is (nil? (ports/delete-document! @test-store :product-search (UUID/randomUUID))))))

;; =============================================================================
;; search-documents (LIKE fallback)
;; =============================================================================

(deftest ^:integration search-documents-test
  (testing "finds documents matching the query"
    (ports/upsert-document! @test-store (make-doc {:content-all "Widget Pro tools"}))
    (ports/upsert-document! @test-store (make-doc {:content-all "Gadget Plus electronics"}))
    (let [results (ports/search-documents @test-store :product-search :product
                                          "widget" {:limit 10 :offset 0})]
      (is (= 1 (count results)))
      (is (= :product (:entity-type (first results))))))

  (testing "an empty query: LIKE '%%' matches everything, an empty tsquery nothing"
    ;; The service answers an empty query itself; this is the store alone.
    (ports/upsert-document! @test-store (make-doc {:content-all "Widget Pro"}))
    (let [results (ports/search-documents @test-store :product-search :product
                                          "" {:limit 10 :offset 0})]
      (if (full-text?)
        (is (empty? results))
        (is (pos? (count results))))))

  (testing "respects limit and offset"
    (dotimes [n 5]
      (ports/upsert-document! @test-store
                              (make-doc {:entity-id   (UUID/randomUUID)
                                         :content-all (str "Widget " n)})))
    (let [page1 (ports/search-documents @test-store :product-search :product
                                        "widget" {:limit 2 :offset 0})
          page2 (ports/search-documents @test-store :product-search :product
                                        "widget" {:limit 2 :offset 2})]
      (is (= 2 (count page1)))
      (is (= 2 (count page2)))
      (is (not= (:entity-id (first page1)) (:entity-id (first page2))))))

  (testing "search results include entity-type and entity-id"
    (let [entity-id (UUID/randomUUID)]
      (ports/upsert-document! @test-store
                              (make-doc {:entity-id   entity-id
                                         :content-all "Unique keyword xyzzy"}))
      (let [results (ports/search-documents @test-store :product-search :product
                                            "xyzzy" {:limit 10 :offset 0})]
        (is (= 1 (count results)))
        (is (= entity-id (:entity-id (first results))))
        (is (= :product (:entity-type (first results))))))))

;; =============================================================================
;; count-results (LIKE fallback)
;; =============================================================================

(deftest ^:integration count-results-test
  (testing "counts matching documents"
    (dotimes [_ 3]
      (ports/upsert-document! @test-store
                              (make-doc {:entity-id   (UUID/randomUUID)
                                         :content-all "Widget Pro special"})))
    (ports/upsert-document! @test-store
                            (make-doc {:entity-id   (UUID/randomUUID)
                                       :content-all "Gadget Plus other"}))
    (let [count (ports/count-results @test-store :product "widget"
                                     {:index-id :product-search})]
      (is (= 3 count))))

  (testing "returns 0 for no matches"
    (is (= 0 (ports/count-results @test-store :product "xyzzy-no-match"
                                  {:index-id :product-search})))))

;; =============================================================================
;; suggest-documents
;; =============================================================================

(deftest ^:integration suggest-documents-test
  (testing "returns suggestions matching partial query"
    (let [entity-id (UUID/randomUUID)]
      (ports/upsert-document! @test-store
                              (make-doc {:entity-id   entity-id
                                         :content-all "Widget Pro Max Special"}))
      (let [suggestions (ports/suggest-documents @test-store :product-search :product
                                                 "wid" {:limit 5})]
        (is (seq suggestions))
        (is (= entity-id (:entity-id (first suggestions)))))))

  (testing "respects limit"
    (dotimes [_ 5]
      (ports/upsert-document! @test-store
                              (make-doc {:entity-id   (UUID/randomUUID)
                                         :content-all "Widget Something"})))
    (let [suggestions (ports/suggest-documents @test-store :product-search :product
                                               "widget" {:limit 2})]
      (is (<= (count suggestions) 2))))

  (testing "returns metadata if present"
    (let [entity-id (UUID/randomUUID)]
      (ports/upsert-document! @test-store
                              (make-doc {:entity-id   entity-id
                                         :content-all "Widget Meta"
                                         :metadata    {:sku "WGT-001"}}))
      (let [suggestions (ports/suggest-documents @test-store :product-search :product
                                                 "widget meta" {:limit 5})
            found       (first (filter #(= entity-id (:entity-id %)) suggestions))]
        (is (some? found))
        (is (= "WGT-001" (get-in found [:metadata :sku])))))))

;; =============================================================================
;; Filter-based search
;; =============================================================================

(deftest ^:integration filter-search-test
  (testing "only returns documents whose filters match"
    (let [entity-a (UUID/randomUUID)
          entity-b (UUID/randomUUID)]
      (ports/upsert-document!
       @test-store
       (make-doc {:entity-id   entity-a
                  :content-all "Filterware Alpha"
                  :filters     {:tenant-id "tenant-1" :status "active"}}))
      (ports/upsert-document!
       @test-store
       (make-doc {:entity-id   entity-b
                  :content-all "Filterware Beta"
                  :filters     {:tenant-id "tenant-2" :status "active"}}))
      (let [results (ports/search-documents
                     @test-store :product-search :product
                     "filterware" {:limit 10 :offset 0
                                   :filters {:tenant-id "tenant-1"}})]
        (is (= 1 (count results)))
        (is (= entity-a (:entity-id (first results)))))

      (testing "count-results also respects filters"
        (is (= 1 (ports/count-results
                  @test-store :product "filterware"
                  {:index-id :product-search
                   :filters  {:tenant-id "tenant-1"}}))))))

  (testing "no filter returns all matching documents"
    (let [entity-c (UUID/randomUUID)
          entity-d (UUID/randomUUID)]
      (ports/upsert-document!
       @test-store
       (make-doc {:entity-id entity-c :content-all "Unfiltered Content One"}))
      (ports/upsert-document!
       @test-store
       (make-doc {:entity-id entity-d :content-all "Unfiltered Content Two"}))
      (let [results (ports/search-documents
                     @test-store :product-search :product
                     "unfiltered" {:limit 10 :offset 0})]
        (is (= 2 (count results)))))))

(defn- tenant-1-suggestions
  "Entity ids `store` suggests for \"wid\" under tenant-1, after indexing one
   matching document per tenant. Returns [expected actual]."
  [store]
  (let [entity-a (UUID/randomUUID)]
    (ports/upsert-document! store (make-doc {:entity-id entity-a :content-all "Widget Alpha"
                                             :filters {:tenant-id "tenant-1"}}))
    (ports/upsert-document! store (make-doc {:content-all "Widget Beta"
                                             :filters {:tenant-id "tenant-2"}}))
    [[entity-a]
     (map :entity-id (ports/suggest-documents store :product-search :product "wid"
                                              {:limit 5 :filters {:tenant-id "tenant-1"}}))]))

(deftest ^:integration suggest-filters-test
  (testing "suggestions keep to the filter"
    (let [[expected actual] (tenant-1-suggestions @test-store)]
      (is (= expected actual)))))

(deftest ^:integration suggest-without-pg-trgm-test
  (testing "PostgreSQL without pg_trgm suggests by LIKE (BOU-661)"
    (let [{:keys [datasource]} @test-store]
      (when (full-text?) (jdbc/execute! datasource ["DROP EXTENSION pg_trgm CASCADE"]))
      (let [[expected actual] (tenant-1-suggestions
                               (persistence/create-search-store datasource @test-adapter))]
        (is (= expected actual))))))

(deftest ^:integration suggest-typo-test
  (testing "pg_trgm suggests through a typo, LIKE does not: the trigram path is the one taken"
    (ports/upsert-document! @test-store (make-doc {:content-all "Widget Alpha"}))
    (is (= (full-text?)
           (boolean (seq (ports/suggest-documents @test-store :product-search :product
                                                  "widgit" {:limit 5})))))))

(deftest ^:integration suggest-with-pg-trgm-off-the-search-path-test
  (testing "pg_trgm outside the search_path: LIKE, and trigram is not tried again (BOU-676)"
    (let [{:keys [datasource]} @test-store]
      (when (full-text?)
        (jdbc/execute! datasource ["DROP EXTENSION pg_trgm CASCADE"])
        (jdbc/execute! datasource ["CREATE SCHEMA trgm"])
        (jdbc/execute! datasource ["CREATE EXTENSION pg_trgm SCHEMA trgm"]))
      (let [store             (persistence/create-search-store datasource @test-adapter)
            [expected actual] (tenant-1-suggestions store)]
        (is (= expected actual))
        (is (= (when (full-text?) :unusable) @(:trigram-state store)))))))

(deftest ^:integration trigram-suggest-uses-the-index-test
  (testing "the suggest query can be served by the trigram index (BOU-676)"
    (if-not (full-text?)
      (testing "elsewhere pg_trgm is not even looked for"
        (ports/suggest-documents @test-store :product-search :product "wid" {:limit 5})
        (is (nil? @(:trigram-state @test-store))))
      (let [{:keys [datasource]} @test-store
            [sql & params]       (qry/build-postgres-suggest-sql "product-search" "product" "wid" 5 nil)
            plan                 (jdbc/with-transaction [tx datasource {:rollback-only true}]
                                   ;; On a test-sized table the planner prefers the btrees on
                                   ;; index_id and entity_type, or reads it whole. Without
                                   ;; them, a plan that avoids the trigram index has none to use.
                                   (jdbc/execute! tx ["DROP INDEX idx_search_documents_index_id, idx_search_documents_entity"])
                                   (jdbc/execute! tx ["ALTER TABLE search_documents DROP CONSTRAINT search_documents_index_id_entity_id_key"])
                                   (jdbc/execute! tx ["SET LOCAL enable_seqscan = off"])
                                   (jdbc/execute! tx (qry/build-trigram-threshold-sql 0.3))
                                   (->> (jdbc/execute! tx (into [(str "EXPLAIN " sql)] params))
                                        (map (comp str first vals))
                                        (str/join "\n")))]
        (is (str/includes? plan trigram/index-name) plan)))))

(deftest ^:integration like-wildcards-are-literal-test
  (testing "a_b finds a_b, not axb (BOU-676)"
    (let [a-b        (UUID/randomUUID)
          like-store (assoc @test-store :trigram-state (atom :unusable))]
      (ports/upsert-document! @test-store (make-doc {:entity-id a-b :content-all "a_b"}))
      (ports/upsert-document! @test-store (make-doc {:content-all "axb"}))
      (is (= [a-b] (map :entity-id (ports/suggest-documents like-store :product-search :product
                                                            "a_b" {:limit 5}))))
      (when-not (full-text?)
        (is (= [a-b] (map :entity-id (ports/search-documents @test-store :product-search :product
                                                             "a_b" {:limit 5 :offset 0}))))
        (is (= 1 (ports/count-results @test-store :product "a_b" {:index-id :product-search})))))))

(deftest ^:integration fallback-search-under-a-turkish-locale-test
  (let [[id found] (find-ID-under-a-turkish-locale
                    @test-store (assoc @test-store :trigram-state (atom :unusable)))]
    (is (= {:search [id] :count 1 :suggest [id]} found))))
