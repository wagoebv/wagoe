(ns wagoe.platform.shell.adapters.database.core-test
  "Comprehensive tests for the core database system functionality.

   Tests the database-agnostic core operations including:
   - Connection pool management
   - Query execution with proper dialect formatting
   - Transaction handling (commit/rollback)
   - Query building utilities (where, pagination, ordering)
   - Error handling and structured logging
   - Schema introspection

   Uses H2 in-memory database for fast, isolated tests."
  (:require [wagoe.platform.shell.adapters.database.common.core :as db]
            [wagoe.platform.shell.adapters.database.sqlite.core :as sqlite]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [wagoe.platform.ports.database :as protocols]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging :as log])
  (:import (java.time Instant)
           (java.util UUID)))

;; =============================================================================
;; Test Fixtures and Setup
;; =============================================================================

(def test-ctx (atom nil))

(defn setup-h2-test-db
  "Create in-memory H2 database for testing."
  []
  (let [adapter (h2/new-adapter)
        ;; Use unique database name to avoid conflicts between tests
        db-name (str "mem:testdb-" (System/currentTimeMillis) "-" (rand-int 10000))
        db-config {:adapter :h2
                   :database-path db-name
                   :connection-params {:DB_CLOSE_DELAY "-1" ; Keep DB alive during tests
                                       :MODE "PostgreSQL" ; Use PostgreSQL compatibility
                                       :DATABASE_TO_LOWER "TRUE"}}
        datasource (db/create-connection-pool adapter db-config)
        ctx {:adapter adapter :datasource datasource}]
    (reset! test-ctx ctx)

    ;; Create test tables
    (db/execute-ddl! ctx "CREATE TABLE IF NOT EXISTS test_users (
                            id UUID PRIMARY KEY,
                            email VARCHAR(255) NOT NULL,
                            name VARCHAR(255),
                            active BOOLEAN DEFAULT true,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                          )")

    (db/execute-ddl! ctx "CREATE TABLE IF NOT EXISTS test_posts (
                            id UUID PRIMARY KEY,
                            user_id UUID NOT NULL,
                            title VARCHAR(255) NOT NULL,
                            content TEXT,
                            published BOOLEAN DEFAULT false,
                            view_count INTEGER DEFAULT 0,
                            FOREIGN KEY (user_id) REFERENCES test_users(id)
                          )")

    (db/execute-ddl! ctx "CREATE TABLE IF NOT EXISTS test_events (
                            id UUID PRIMARY KEY,
                            occurred_at VARCHAR(255) NOT NULL,
                            updated_at VARCHAR(255)
                          )")
    ctx))

(defn cleanup-h2-test-db
  "Clean up test database."
  []
  (when-let [ctx @test-ctx]
    (try
      (.close (:datasource ctx))
      (catch java.lang.Exception e
        (log/warn "Error closing test datasource" {:error (.getMessage e)})))
    (reset! test-ctx nil)))

(use-fixtures :each
  (fn [test-fn]
    (setup-h2-test-db)
    (try
      (test-fn)
      (finally
        (cleanup-h2-test-db)))))

;; =============================================================================
;; Core Database Operations Tests
;; =============================================================================

(deftest ^:integration test-connection-pool-creation
  (testing "Connection pool creation with H2"
    (let [adapter (h2/new-adapter)
          db-config {:adapter :h2 :database-path "mem:test_pool"}
          datasource (db/create-connection-pool adapter db-config)]
      (is (some? datasource))
      (is (instance? javax.sql.DataSource datasource))
      (.close datasource))))

(deftest ^:integration test-execute-query
  (testing "Query execution returns correct results"
    (let [ctx @test-ctx
          user-id (UUID/randomUUID)
          ;; Insert test data
          insert-query {:insert-into :test_users
                        :values [{:id user-id
                                  :email "test@example.com"
                                  :name "Test User"
                                  :active true}]}
          _ (db/execute-update! ctx insert-query)

          ;; Query the data
          select-query {:select [:*] :from [:test_users] :where [:= :id user-id]}
          results (db/execute-query! ctx select-query)]

      (is (= 1 (count results)))
      (let [user (first results)]
        (is (= user-id (:id user)))
        (is (= "test@example.com" (:email user)))
        (is (= "Test User" (:name user)))
        (is (true? (:active user)))))))

(deftest ^:integration test-execute-one
  (testing "Execute-one returns single result or nil"
    (let [ctx @test-ctx
          user-id (UUID/randomUUID)]

      ;; Test nil result for non-existent record
      (is (nil? (db/execute-one! ctx {:select [:*] :from [:test_users] :where [:= :id user-id]})))

      ;; Insert test data
      (db/execute-update! ctx {:insert-into :test_users
                               :values [{:id user-id :email "single@test.com" :name "Single User"}]})

      ;; Test single result
      (let [result (db/execute-one! ctx {:select [:*] :from [:test_users] :where [:= :id user-id]})]
        (is (some? result))
        (is (= user-id (:id result)))
        (is (= "single@test.com" (:email result)))))))

(deftest ^:integration test-execute-query-with-jdbc-vector
  (testing "Raw JDBC vectors are accepted for direct SQL queries"
    (let [ctx @test-ctx
          result (db/execute-query! ctx ["SELECT 42 AS answer"])]
      (is (= [{:answer 42}] result)))))

(deftest ^:integration test-execute-update
  (testing "Execute-update returns affected row count"
    (let [ctx @test-ctx
          user-id (UUID/randomUUID)]

      ;; Insert
      (let [affected (db/execute-update! ctx {:insert-into :test_users
                                              :values [{:id user-id :email "update@test.com" :name "Update User"}]})]
        (is (= 1 affected)))

      ;; Update
      (let [affected (db/execute-update! ctx {:update :test_users
                                              :set {:name "Updated Name"}
                                              :where [:= :id user-id]})]
        (is (= 1 affected)))

      ;; Delete
      (let [affected (db/execute-update! ctx {:delete-from :test_users
                                              :where [:= :id user-id]})]
        (is (= 1 affected)))

      ;; Update non-existent (should return 0)
      (let [affected (db/execute-update! ctx {:update :test_users
                                              :set {:name "Won't work"}
                                              :where [:= :id user-id]})]
        (is (= 0 affected))))))

(deftest ^:integration test-instant-values-are-normalized-before-db-execution
  (testing "Instant values are converted to ISO strings for inserts, updates, and predicates"
    (let [ctx @test-ctx
          event-id (UUID/randomUUID)
          occurred-at (Instant/parse "2026-03-24T10:15:30Z")
          updated-at (Instant/parse "2026-03-24T11:15:30Z")]
      (is (= 1 (db/execute-update! ctx {:insert-into :test_events
                                        :values [{:id event-id
                                                  :occurred_at occurred-at}]})))
      (is (= [{:id event-id
               :occurred-at (.toString occurred-at)
               :updated-at nil}]
             (db/execute-query! ctx {:select [:*]
                                     :from [:test_events]
                                     :where [:= :occurred_at occurred-at]})))

      (is (= 1 (db/execute-update! ctx {:update :test_events
                                        :set {:updated_at updated-at}
                                        :where [:= :id event-id]})))
      (is (= [{:updated-at (.toString updated-at)}]
             (db/execute-query! ctx {:select [:updated_at]
                                     :from [:test_events]
                                     :where [:= :updated_at updated-at]}))))))

(deftest ^:integration test-transactions
  (testing "Transaction commit and rollback behavior"
    (let [ctx @test-ctx
          user-id-1 (UUID/randomUUID)
          user-id-2 (UUID/randomUUID)]

      ;; Ensure clean state
      (db/execute-update! ctx {:delete-from :test_users})

      ;; Test successful transaction
      (db/with-transaction [tx-ctx ctx]
        (db/execute-update! tx-ctx {:insert-into :test_users
                                    :values [{:id user-id-1 :email "tx1@test.com" :name "TX User 1"}]})
        (db/execute-update! tx-ctx {:insert-into :test_users
                                    :values [{:id user-id-2 :email "tx2@test.com" :name "TX User 2"}]}))

      ;; Verify both records were committed
      (is (= 2 (count (db/execute-query! ctx {:select [:*] :from [:test_users]}))))

      ;; Clean up for next test
      (db/execute-update! ctx {:delete-from :test_users})

      ;; Test rollback on exception
      (is (thrown? java.lang.Exception
                   (db/with-transaction [tx-ctx ctx]
                     (db/execute-update! tx-ctx {:insert-into :test_users
                                                 :values [{:id user-id-1 :email "rollback@test.com" :name "Rollback User"}]})
                     (throw (RuntimeException. "Intentional failure")))))

      ;; Verify no records were committed due to rollback
      (is (= 0 (count (db/execute-query! ctx {:select [:*] :from [:test_users]})))))))

;; =============================================================================
;; Query Building Tests
;; =============================================================================

(deftest ^:integration test-build-where-clause
  (testing "Where clause building with different filter types"
    (let [adapter (h2/new-adapter)
          ctx {:adapter adapter}]

      ;; Test simple equality filters
      (let [filters {:email "test@example.com" :active true}
            where-clause (db/build-where-clause ctx filters)]
        (is (sequential? where-clause)) ; Can be list or vector
        (is (= :and (first where-clause))))

      ;; Test with string pattern filter (H2 uses LIKE, not ILIKE)
      (let [filters {:name "John*"}
            where-clause (db/build-where-clause ctx filters)]
        (is (sequential? where-clause)) ; Can be list or vector
        (is (= :like (first where-clause)))) ; H2 uses LIKE

      ;; Test with vector filter (should use IN)
      (let [filters {:id [(UUID/randomUUID) (UUID/randomUUID)]}
            where-clause (db/build-where-clause ctx filters)]
        (is (vector? where-clause))
        (is (= :in (first where-clause))))

      ;; Test empty filters
      (is (nil? (db/build-where-clause ctx {})))
      (is (nil? (db/build-where-clause ctx nil))))))

(deftest ^:integration test-build-pagination
  (testing "Pagination building with safe limits"
    ;; Test default values
    (let [pagination (db/build-pagination {})]
      (is (= 20 (:limit pagination)))
      (is (= 0 (:offset pagination))))

    ;; Test custom values
    (let [pagination (db/build-pagination {:limit 50 :offset 100})]
      (is (= 50 (:limit pagination)))
      (is (= 100 (:offset pagination))))

    ;; Test limit clamping (max 1000)
    (let [pagination (db/build-pagination {:limit 2000})]
      (is (= 1000 (:limit pagination))))

    ;; Test minimum limit (min 1)
    (let [pagination (db/build-pagination {:limit 0})]
      (is (= 1 (:limit pagination))))

    ;; Test negative offset handling
    (let [pagination (db/build-pagination {:offset -10})]
      (is (= 0 (:offset pagination))))))

(deftest ^:integration test-build-ordering
  (testing "Order clause building with different options"
    ;; Test default ordering (defaults to :asc)
    (let [ordering (db/build-ordering {} :created_at)]
      (is (= [[:created_at :asc]] ordering)))

    ;; Test custom field
    (let [ordering (db/build-ordering {:sort-by :name} :created_at)]
      (is (= [[:name :asc]] ordering)))

    ;; Test custom direction
    (let [ordering (db/build-ordering {:sort-by :email :sort-direction :desc} :created_at)]
      (is (= [[:email :desc]] ordering)))

    ;; Test invalid direction defaults to :asc
    (let [ordering (db/build-ordering {:sort-by :name :sort-direction :invalid} :created_at)]
      (is (= [[:name :asc]] ordering)))))

;; =============================================================================
;; Schema Introspection Tests
;; =============================================================================

(deftest ^:integration test-table-exists
  (testing "Table existence checking"
    (let [ctx @test-ctx]
      ;; Test existing table
      (is (db/table-exists? ctx :test_users))
      (is (db/table-exists? ctx "test_users"))

      ;; Test non-existent table
      (is (not (db/table-exists? ctx :non_existent_table)))
      (is (not (db/table-exists? ctx "non_existent_table"))))))

(deftest ^:integration test-get-table-info
  (testing "Table information retrieval"
    (let [ctx @test-ctx
          table-info (db/get-table-info ctx :test_users)]

      (is (vector? table-info))
      (is (pos? (count table-info)))

      ;; Check that we have expected columns
      (let [column-names (set (map :name table-info))
            id-column (first (filter #(= "id" (:name %)) table-info))]
        (is (contains? column-names "id"))
        (is (contains? column-names "email"))
        (is (contains? column-names "name"))
        (is (contains? column-names "active"))
        (is (contains? column-names "created_at"))

        ;; Check id column properties
        (is (some? id-column))
        (is (:primary-key id-column))))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest ^:integration test-error-handling
  (testing "Proper error handling with context enrichment"
    (let [ctx @test-ctx]

      ;; Test SQL syntax error
      (is (thrown-with-msg? java.lang.Exception #"Database query failed"
                            (db/execute-query! ctx {:select [:*] :from [:non_existent_table]})))

      ;; Test constraint violation
      (let [user-id (UUID/randomUUID)]
        ;; Insert first record
        (db/execute-update! ctx {:insert-into :test_users
                                 :values [{:id user-id :email "unique@test.com" :name "Unique User"}]})

        ;; Try to insert duplicate (should fail on primary key)
        (is (thrown? java.lang.Exception
                     (db/execute-update! ctx {:insert-into :test_users
                                              :values [{:id user-id :email "duplicate@test.com" :name "Duplicate User"}]})))))))

;; =============================================================================
;; Database Dialect Tests
;; =============================================================================

(deftest ^:integration test-dialect-formatting
  (testing "Queries are formatted with correct dialect"
    (let [h2-adapter (h2/new-adapter)
          sqlite-adapter (sqlite/new-adapter)

          query-map {:select [:*] :from [:test_table] :where [:= :id 1]}]

      ;; Test H2 dialect
      (let [ctx {:adapter h2-adapter}
            formatted-sql (db/format-sql ctx query-map)]
        (is (vector? formatted-sql))
        (is (string? (first formatted-sql))))

      ;; Test SQLite dialect
      (let [ctx {:adapter sqlite-adapter}
            formatted-sql (db/format-sql ctx query-map)]
        (is (vector? formatted-sql))
        (is (string? (first formatted-sql)))))))

;; =============================================================================
;; Batch Operations Tests
;; =============================================================================

(deftest ^:integration test-execute-batch
  (testing "Batch execution in transaction"
    (let [ctx @test-ctx
          user-id-1 (UUID/randomUUID)
          user-id-2 (UUID/randomUUID)

          batch-queries [{:insert-into :test_users
                          :values [{:id user-id-1 :email "batch1@test.com" :name "Batch User 1"}]}
                         {:insert-into :test_users
                          :values [{:id user-id-2 :email "batch2@test.com" :name "Batch User 2"}]}
                         {:select [[:%count.* :count]] :from [:test_users]}]
          results (db/execute-batch! ctx batch-queries)]
      (is (= 3 (count results)))

      ;; First two should be update counts, last should be query result
      (is (= 1 (first results))) ; First insert affected 1 row
      (is (= 1 (second results))) ; Second insert affected 1 row
      (is (vector? (nth results 2))) ; Third is query result
      (is (= 2 (:count (first (nth results 2))))))))

;; =============================================================================
;; Performance and Logging Tests
;; =============================================================================

(deftest ^:integration test-query-logging
  (testing "Queries log execution time and row counts"
    ;; This is more of an integration test to ensure logging doesn't break
    ;; In a real scenario, you'd capture log output and verify it
    (let [ctx @test-ctx
          user-id (UUID/randomUUID)]

      ;; These should complete without throwing exceptions
      ;; and produce appropriate log messages
      (is (some? (db/execute-update! ctx {:insert-into :test_users
                                          :values [{:id user-id :email "log@test.com" :name "Log User"}]})))

      (is (some? (db/execute-one! ctx {:select [:*] :from [:test_users] :where [:= :id user-id]})))

      (is (vector? (db/execute-query! ctx {:select [:*] :from [:test_users]}))))))

;; =============================================================================
;; Integration Tests with Different Adapters
;; =============================================================================

(deftest ^:integration test-adapter-compatibility
  (testing "Core functions work with different adapters"
    ;; Test with H2 (already tested above)
    (let [h2-adapter (h2/new-adapter)]
      (is (= :ansi (protocols/dialect h2-adapter))) ; H2 uses ANSI SQL dialect
      (is (string? (protocols/jdbc-driver h2-adapter)))
      (is (map? (protocols/pool-defaults h2-adapter))))

    ;; Test with SQLite
    (let [sqlite-adapter (sqlite/new-adapter)]
      (is (nil? (protocols/dialect sqlite-adapter))) ; SQLite uses HoneySQL default (nil)
      (is (string? (protocols/jdbc-driver sqlite-adapter)))
      (is (map? (protocols/pool-defaults sqlite-adapter)))

      ;; Test boolean conversion differences
      (is (= 1 (protocols/boolean->db sqlite-adapter true)))
      (is (= 0 (protocols/boolean->db sqlite-adapter false)))
      (is (true? (protocols/db->boolean sqlite-adapter 1)))
      (is (false? (protocols/db->boolean sqlite-adapter 0))))))

(deftest ^:integration test-cross-database-queries
  (testing "Same query logic works across different database types"
    ;; This test demonstrates that the same application code
    ;; can work with different database backends
    (let [test-query-fn (fn [ctx]
                          (let [user-id (UUID/randomUUID)
                                email "cross@db.test"]
                            ;; Insert
                            (db/execute-update! ctx {:insert-into :test_users
                                                     :values [{:id user-id :email email :name "Cross DB User"}]})
                            ;; Query back
                            (db/execute-one! ctx {:select [:email] :from [:test_users] :where [:= :id user-id]})))]

      ;; Test with H2 (our test database)
      (is (= "cross@db.test" (:email (test-query-fn @test-ctx)))))))

(deftest ^:integration test-database-info
  (testing "Database info provides useful metadata"
    (let [ctx @test-ctx
          db-info (db/database-info ctx)]

      (is (map? db-info))
      ;; Should have at least basic information
      (is (contains? db-info :dialect))
      (is (= :ansi (:dialect db-info))))))

;; =============================================================================
;; Edge Cases and Error Conditions
;; =============================================================================

(deftest ^:integration test-edge-cases
  (testing "Handling of edge cases and invalid inputs"
    (let [ctx @test-ctx]

      ;; Empty query map should not crash
      (is (thrown? java.lang.Exception (db/execute-query! ctx {})))

      ;; Nil context should throw appropriate error
      (is (thrown? java.lang.Exception (db/execute-query! nil {:select [:*] :from [:test_users]})))

      ;; Very large limit should be clamped
      (let [pagination (db/build-pagination {:limit Integer/MAX_VALUE})]
        (is (<= (:limit pagination) 1000))))))
