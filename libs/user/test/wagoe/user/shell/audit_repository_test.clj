(ns wagoe.user.shell.audit-repository-test
  "Integration tests for audit repository persistence layer.
   
   These tests verify database operations for audit logs:
   - Creating audit log entries
   - Querying audit logs by various filters
   - Pagination of audit results"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection])
  (:import (java.util UUID)
           (com.zaxxer.hikari HikariDataSource)))

;; =============================================================================
;; Test Database Setup
;; =============================================================================

(def test-db-context (atom nil))
(def test-audit-repo (atom nil))

(defn setup-test-db
  "Initialize test database with schema."
  []
  (let [^HikariDataSource datasource (connection/->pool
                                      com.zaxxer.hikari.HikariDataSource
                                      {:jdbcUrl "jdbc:h2:mem:audit-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                                       :username "sa"
                                       :password ""})
        adapter (h2/new-adapter)
        db-ctx {:datasource datasource :adapter adapter}]
    (reset! test-db-context db-ctx)

    ;; Create tables manually for testing
    (jdbc/execute! datasource
                   ["CREATE TABLE IF NOT EXISTS users (
                     id UUID PRIMARY KEY,
                     email VARCHAR(255) UNIQUE NOT NULL,
                     name VARCHAR(255),
                     role VARCHAR(50),
                     active BOOLEAN DEFAULT TRUE,
                     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                     deleted_at TIMESTAMP
                   )"])

    (jdbc/execute! datasource
                   ["CREATE TABLE IF NOT EXISTS user_audit_log (
                     id VARCHAR(36) PRIMARY KEY,
                     action TEXT NOT NULL CHECK (action IN (
                       'create', 'update', 'delete', 'activate', 'deactivate',
                       'role-change', 'bulk-action', 'login', 'logout'
                     )),
                     actor_id VARCHAR(36),
                     actor_email TEXT,
                     target_user_id VARCHAR(36) NOT NULL,
                     target_user_email TEXT NOT NULL,
                     changes TEXT,
                     metadata TEXT,
                     ip_address TEXT,
                     user_agent TEXT,
                     result TEXT NOT NULL CHECK (result IN ('success', 'failure')),
                     error_message TEXT,
                     created_at TEXT NOT NULL
                   )"])

    ;; Create indexes separately
    (jdbc/execute! datasource ["CREATE INDEX IF NOT EXISTS idx_audit_action ON user_audit_log (action)"])
    (jdbc/execute! datasource ["CREATE INDEX IF NOT EXISTS idx_audit_target_user ON user_audit_log (target_user_id)"])
    (jdbc/execute! datasource ["CREATE INDEX IF NOT EXISTS idx_audit_actor ON user_audit_log (actor_id)"])
    (jdbc/execute! datasource ["CREATE INDEX IF NOT EXISTS idx_audit_created_at ON user_audit_log (created_at)"])

    ;; Create audit repository with pagination config
    (reset! test-audit-repo (persistence/create-audit-repository db-ctx {:default-limit 20 :max-limit 100}))))

(defn teardown-test-db
  "Clean up test database."
  []
  (when-let [db-ctx @test-db-context]
    (try
      ;; Drop all tables
      (jdbc/execute! (:datasource db-ctx) ["DROP ALL OBJECTS"])
      ;; Close datasource
      (.close ^HikariDataSource (:datasource db-ctx))
      (catch Exception _e
        ;; Ignore errors during cleanup
        nil))
    (reset! test-db-context nil)
    (reset! test-audit-repo nil)))

(defn db-fixture
  "Test fixture that sets up and tears down database for each test."
  [test-fn]
  (setup-test-db)
  (try
    (test-fn)
    (finally
      (teardown-test-db))))

(use-fixtures :each db-fixture)

;; =============================================================================
;; Test Data Helpers
;; =============================================================================

(defn create-test-audit-entry
  "Create a test audit entry with default values."
  ([]
   (create-test-audit-entry {}))
  ([overrides]
   (merge {:action :create
           :actor-id (UUID/randomUUID)
           :actor-email "admin@example.com"
           :target-user-id (UUID/randomUUID)
           :target-user-email "user@example.com"
           :changes {:created true}
           :metadata {:role "user"}
           :ip-address "192.168.1.100"
           :user-agent "Test Browser"
           :result :success}
          overrides)))

;; =============================================================================
;; Create Audit Log Tests
;; =============================================================================

(deftest ^:contract create-audit-log-test
  (testing "Creates audit log entry in database"
    (let [entry (create-test-audit-entry)
          created (.create-audit-log @test-audit-repo entry)]

      (is (some? created))
      (is (uuid? (:id created)))
      (is (inst? (:created-at created)))
      (is (= (:action entry) (:action created)))
      (is (= (:actor-id entry) (:actor-id created)))
      (is (= (:target-user-id entry) (:target-user-id created)))))

  (testing "Creates audit log with nil optional fields"
    (let [entry (create-test-audit-entry {:ip-address nil
                                          :user-agent nil
                                          :metadata nil})
          created (.create-audit-log @test-audit-repo entry)]

      (is (some? created))
      (is (nil? (:ip-address created)))
      (is (nil? (:user-agent created)))
      (is (nil? (:metadata created)))))

  (testing "Creates multiple different action types"
    (let [actions [:create :update :delete :activate :deactivate :login :logout]
          entries (map #(create-test-audit-entry {:action %}) actions)]

      (doseq [entry entries]
        (let [created (.create-audit-log @test-audit-repo entry)]
          (is (some? created))
          (is (uuid? (:id created))))))))

;; =============================================================================
;; Find Audit Logs Tests
;; =============================================================================

(deftest ^:contract find-audit-logs-test
  (testing "Finds audit logs with default options"
    ;; Create test entries
    (dotimes [_ 5]
      (.create-audit-log @test-audit-repo (create-test-audit-entry)))

    (let [result (.find-audit-logs @test-audit-repo {})]
      (is (map? result))
      (is (contains? result :audit-logs))
      (is (contains? result :total-count))
      (is (= 5 (:total-count result)))
      (is (= 5 (count (:audit-logs result))))))

  (testing "Finds audit logs with pagination"
    ;; Create 15 test entries (5 already exist from previous test)
    (dotimes [_ 15]
      (.create-audit-log @test-audit-repo (create-test-audit-entry)))

    (let [page1 (.find-audit-logs @test-audit-repo {:limit 10 :offset 0})
          page2 (.find-audit-logs @test-audit-repo {:limit 10 :offset 10})]

      (is (= 20 (:total-count page1))) ;; 5 from previous test + 15 from this test
      (is (= 10 (count (:audit-logs page1))))
      (is (= 10 (count (:audit-logs page2))))))

  (testing "Filters audit logs by action"
    (let [actor-id (UUID/randomUUID)]
      ;; Create entries with different actions
      (.create-audit-log @test-audit-repo
                         (create-test-audit-entry {:action :create :actor-id actor-id}))
      (.create-audit-log @test-audit-repo
                         (create-test-audit-entry {:action :update :actor-id actor-id}))
      (.create-audit-log @test-audit-repo
                         (create-test-audit-entry {:action :delete :actor-id actor-id}))

      (let [create-logs (.find-audit-logs @test-audit-repo {:filter-action :create})]
        (is (>= (:total-count create-logs) 1))
        (is (every? #(= :create (:action %)) (:audit-logs create-logs))))))

  (testing "Filters audit logs by result"
    (.create-audit-log @test-audit-repo
                       (create-test-audit-entry {:action :login :result :success}))
    (.create-audit-log @test-audit-repo
                       (create-test-audit-entry {:action :login :result :failure}))

    (let [failed-logs (.find-audit-logs @test-audit-repo {:filter-result :failure})]
      (is (>= (:total-count failed-logs) 1))
      (is (every? #(= :failure (:result %)) (:audit-logs failed-logs))))))

;; =============================================================================
;; Find Audit Logs By User Tests
;; =============================================================================

(deftest ^:contract find-audit-logs-by-user-test
  (testing "Finds audit logs for specific target user"
    (let [target-user-id (UUID/randomUUID)
          other-user-id (UUID/randomUUID)]

      ;; Create entries for target user
      (dotimes [_ 3]
        (.create-audit-log @test-audit-repo
                           (create-test-audit-entry {:target-user-id target-user-id})))

      ;; Create entries for other user
      (dotimes [_ 2]
        (.create-audit-log @test-audit-repo
                           (create-test-audit-entry {:target-user-id other-user-id})))

      (let [user-logs (.find-audit-logs-by-user @test-audit-repo target-user-id {})]
        (is (= 3 (count user-logs)))
        (is (every? #(= target-user-id (:target-user-id %)) user-logs)))))

  (testing "Returns empty list for user with no audit logs"
    (let [user-id (UUID/randomUUID)
          logs (.find-audit-logs-by-user @test-audit-repo user-id {})]

      (is (empty? logs)))))

;; =============================================================================
;; Find Audit Logs By Actor Tests
;; =============================================================================

(deftest ^:contract find-audit-logs-by-actor-test
  (testing "Finds audit logs performed by specific actor"
    (let [actor-id (UUID/randomUUID)
          other-actor-id (UUID/randomUUID)]

      ;; Create entries by actor
      (dotimes [_ 4]
        (.create-audit-log @test-audit-repo
                           (create-test-audit-entry {:actor-id actor-id})))

      ;; Create entries by other actor
      (dotimes [_ 2]
        (.create-audit-log @test-audit-repo
                           (create-test-audit-entry {:actor-id other-actor-id})))

      (let [actor-logs (.find-audit-logs-by-actor @test-audit-repo actor-id {})]
        (is (= 4 (count actor-logs)))
        (is (every? #(= actor-id (:actor-id %)) actor-logs)))))

  (testing "Finds audit logs by actor with pagination"
    (let [actor-id (UUID/randomUUID)]
      ;; Create 10 entries
      (dotimes [_ 10]
        (.create-audit-log @test-audit-repo
                           (create-test-audit-entry {:actor-id actor-id})))

      (let [page1 (.find-audit-logs-by-actor @test-audit-repo actor-id {:limit 5 :offset 0})
            page2 (.find-audit-logs-by-actor @test-audit-repo actor-id {:limit 5 :offset 5})]

        (is (= 5 (count page1)))
        (is (= 5 (count page2)))))))

;; =============================================================================
;; Count Audit Logs Tests
;; =============================================================================

(deftest ^:contract count-audit-logs-test
  (testing "Counts all audit logs"
    ;; Create test entries
    (dotimes [_ 7]
      (.create-audit-log @test-audit-repo (create-test-audit-entry)))

    (let [count-result (.count-audit-logs @test-audit-repo {})]
      (is (= 7 count-result))))

  (testing "Counts audit logs with filters"
    (let [target-user-id (UUID/randomUUID)]
      ;; Create entries for specific user
      (dotimes [_ 3]
        (.create-audit-log @test-audit-repo
                           (create-test-audit-entry {:target-user-id target-user-id
                                                     :action :update})))

      ;; Create other entries
      (dotimes [_ 4]
        (.create-audit-log @test-audit-repo (create-test-audit-entry {:action :create})))

      (let [count-result (.count-audit-logs @test-audit-repo
                                            {:filter-target-user-id target-user-id})]
        (is (= 3 count-result))))))

;; =============================================================================
;; JSONB Metadata Tests
;; =============================================================================

(deftest ^:contract jsonb-metadata-test
  (testing "Stores and retrieves complex metadata"
    (let [complex-metadata {:field-count 3
                            :fields [{:field "role" :old "user" :new "admin"}
                                     {:field "name" :old "Old" :new "New"}]
                            :bulk-operation true
                            :affected-users 10}
          entry (create-test-audit-entry {:metadata complex-metadata})
          created (.create-audit-log @test-audit-repo entry)]

      (is (some? created))
      (is (map? (:metadata created)))
      (is (= 3 (get-in created [:metadata :field-count])))
      (is (= 10 (get-in created [:metadata :affected-users])))))

  (testing "Stores and retrieves complex changes"
    (let [complex-changes {:fields [{:field "email" :old "old@test.com" :new "new@test.com"}
                                    {:field "role" :old "user" :new "admin"}]}
          entry (create-test-audit-entry {:changes complex-changes})
          created (.create-audit-log @test-audit-repo entry)]

      (is (some? created))
      (is (map? (:changes created)))
      (is (vector? (get-in created [:changes :fields])))
      (is (= 2 (count (get-in created [:changes :fields])))))))
