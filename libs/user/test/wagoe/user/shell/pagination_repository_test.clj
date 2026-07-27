(ns wagoe.user.shell.pagination-repository-test
  "Direct repository tests for pagination (bypassing service layer)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.persistence :as user-persistence]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection])
  (:import [java.time Instant]
           [java.util UUID]
           [com.zaxxer.hikari HikariDataSource]))

(def test-db-context (atom nil))
(def test-repository (atom nil))

(defn setup-test-db []
  (let [^HikariDataSource datasource (connection/->pool
                                      com.zaxxer.hikari.HikariDataSource
                                      {:jdbcUrl "jdbc:h2:mem:pagination-repo-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                                       :username "sa"
                                       :password ""})
        adapter (h2/new-adapter)
        db-ctx {:datasource datasource :adapter adapter}]
    (reset! test-db-context db-ctx)

    (jdbc/execute! datasource
                   ["CREATE TABLE IF NOT EXISTS auth_users (
                       id UUID PRIMARY KEY,
                       email VARCHAR(255) NOT NULL UNIQUE,
                       password_hash VARCHAR(255),
                       active BOOLEAN NOT NULL DEFAULT true,
                       mfa_enabled BOOLEAN NOT NULL DEFAULT false,
                       mfa_secret VARCHAR(255),
                       mfa_backup_codes TEXT,
                       mfa_backup_codes_used TEXT,
                       mfa_enabled_at TIMESTAMP,
                       failed_login_count INTEGER NOT NULL DEFAULT 0,
                       lockout_until TIMESTAMP,
                       created_at TIMESTAMP NOT NULL,
                       updated_at TIMESTAMP,
                       deleted_at TIMESTAMP
                     )"])
    
    (jdbc/execute! datasource
                   ["CREATE TABLE IF NOT EXISTS users (
                       id UUID PRIMARY KEY,
                       tenant_id UUID,
                       name VARCHAR(255) NOT NULL,
                       role VARCHAR(50) NOT NULL,
                       avatar_url VARCHAR(500),
                       login_count INTEGER NOT NULL DEFAULT 0,
                       last_login TIMESTAMP,
                       date_format VARCHAR(50),
                       time_format VARCHAR(50),
                       FOREIGN KEY (id) REFERENCES auth_users(id)
                     )"])

    (reset! test-repository (user-persistence/create-user-repository db-ctx))))

(defn teardown-test-db []
  (when-let [db-ctx @test-db-context]
    (when-let [^HikariDataSource datasource (:datasource db-ctx)]
      (.close datasource)))
  (reset! test-db-context nil)
  (reset! test-repository nil))

(use-fixtures :once (fn [f] (setup-test-db) (try (f) (finally (teardown-test-db)))))

(defn clean-test-database! []
  (when-let [db-ctx @test-db-context]
    (jdbc/execute! (:datasource db-ctx) ["DELETE FROM users"])
    (jdbc/execute! (:datasource db-ctx) ["DELETE FROM auth_users"])))

(use-fixtures :each (fn [f] (clean-test-database!) (f)))

(defn create-test-user! [user-data]
  (let [db-ctx @test-db-context
        user-id (UUID/randomUUID)
        user (merge {:id user-id
                     :email (str "user-" (UUID/randomUUID) "@example.com")
                     :name "Test User"
                     :password-hash "$2a$12$test.hash"
                     :role "user"
                     :active true
                     :created-at (Instant/now)
                     :updated-at (Instant/now)
                     :login-count 0
                     :failed-login-count 0
                     :mfa-enabled false
                     :tenant-id nil}
                    user-data)]
    ;; Insert into auth_users first
    (jdbc/execute-one!
     (:datasource db-ctx)
     ["INSERT INTO auth_users (id, email, password_hash, active,
                                mfa_enabled, failed_login_count,
                                created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
      (:id user) (:email user) (:password-hash user) (:active user)
      (:mfa-enabled user) (:failed-login-count user)
      (:created-at user) (:updated-at user)])
    
    ;; Insert into users (profile)
    (jdbc/execute-one!
     (:datasource db-ctx)
     ["INSERT INTO users (id, tenant_id, name, role, login_count)
       VALUES (?, ?, ?, ?, ?)"
      (:id user) (:tenant-id user) (:name user) (:role user)
      (:login-count user)])
    user))

(deftest ^:contract repository-pagination-test
  (testing "Repository returns paginated results"
    (let [repo @test-repository]
      ;; Create 25 test users
      (dotimes [i 25]
        (create-test-user! {:email (format "user-%03d@example.com" i)
                            :name (format "User %03d" i)}))

      ;; Test default pagination
      (let [result (ports/find-users repo {})
            users (:users result)
            total (:total-count result)]

        (is (= 20 (count users)) "Should return 20 users (default limit)")
        (is (= 25 total) "Should have total count of 25"))

      ;; Test custom limit
      (let [result (ports/find-users repo {:limit 10})
            users (:users result)]

        (is (= 10 (count users)) "Should return 10 users (custom limit"))

      ;; Test with offset
      (let [result (ports/find-users repo {:limit 10 :offset 10})
            users (:users result)
            total (:total-count result)]

        (is (= 10 (count users)) "Should return 10 users from offset 10")
        (is (= 25 total) "Total should still be 25")))))
