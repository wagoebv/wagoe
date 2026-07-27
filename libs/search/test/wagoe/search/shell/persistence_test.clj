(ns wagoe.search.shell.persistence-test
  "Integration tests for SearchStore persistence layer against H2.

   Uses the LIKE-based fallback path (db-type :h2), which validates:
   - upsert-document! (insert + conflict update)
   - delete-document!
   - search-documents (LIKE fallback)
   - count-results (LIKE fallback)
   - suggest-documents (LIKE fallback)
   - count-documents"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.search.ports :as ports]
            [wagoe.search.shell.persistence :as persistence]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection])
  (:import [java.util UUID]
           [java.time Instant]
           [com.zaxxer.hikari HikariDataSource]))

;; =============================================================================
;; Test database setup
;; =============================================================================

(def ^:private test-datasource (atom nil))
(def ^:private test-store      (atom nil))

(defn- setup-test-db []
  (let [^HikariDataSource ds
        (connection/->pool
         com.zaxxer.hikari.HikariDataSource
         {:jdbcUrl  "jdbc:h2:mem:search-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
          :username "sa"
          :password ""})]
    (reset! test-datasource ds)

    (jdbc/execute! ds
                   ["CREATE TABLE IF NOT EXISTS search_documents (
                      id          TEXT NOT NULL PRIMARY KEY,
                      index_id    TEXT NOT NULL,
                      entity_type TEXT NOT NULL,
                      entity_id   TEXT NOT NULL,
                      language    TEXT NOT NULL DEFAULT 'english',
                      weight_a    TEXT NOT NULL DEFAULT '',
                      weight_b    TEXT NOT NULL DEFAULT '',
                      weight_c    TEXT NOT NULL DEFAULT '',
                      weight_d    TEXT NOT NULL DEFAULT '',
                      content_all TEXT NOT NULL DEFAULT '',
                      metadata    TEXT,
                      filters     TEXT,
                      updated_at  TEXT NOT NULL,
                      UNIQUE (index_id, entity_id)
                    )"])

    (jdbc/execute! ds
                   ["CREATE INDEX IF NOT EXISTS idx_search_documents_index_id
                      ON search_documents (index_id)"])

    (reset! test-store (persistence/create-search-store ds :h2))))

(defn- teardown-test-db []
  (when-let [ds @test-datasource]
    (try
      (jdbc/execute! ds ["DROP ALL OBJECTS"])
      (.close ^HikariDataSource ds)
      (catch Exception _e nil))
    (reset! test-datasource nil)
    (reset! test-store nil)))

(defn- db-fixture [test-fn]
  (setup-test-db)
  (try
    (test-fn)
    (finally
      (teardown-test-db))))

(use-fixtures :each db-fixture)

;; =============================================================================
;; Test data helpers
;; =============================================================================

(defn- make-doc
  ([]
   (make-doc {}))
  ([overrides]
   (merge {:id          (str (UUID/randomUUID))
           :index-id    :product-search
           :entity-type :product
           :entity-id   (UUID/randomUUID)
           :language    "english"
           :weight-a    "Widget Pro"
           :weight-b    "A great widget for professionals"
           :weight-c    "tools hardware"
           :weight-d    ""
           :content-all "Widget Pro A great widget for professionals tools hardware"
           :updated-at  (Instant/now)}
          overrides)))

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
          doc1      (make-doc {:entity-id entity-id :weight-a "Original"
                               :content-all "Original"})
          doc2      (make-doc {:id (str (UUID/randomUUID))
                               :entity-id entity-id :weight-a "Updated"
                               :content-all "Updated"})]
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

  (testing "returns all documents for empty query (via service layer, store returns LIKE %%)"
    (ports/upsert-document! @test-store (make-doc {:content-all "Widget Pro"}))
    (let [results (ports/search-documents @test-store :product-search :product
                                          "" {:limit 10 :offset 0})]
      (is (pos? (count results)))))

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
;; suggest-documents (LIKE fallback)
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
                                                 "widget" {:limit 5})
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
