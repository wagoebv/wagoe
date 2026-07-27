(ns wagoe.user.shell.auth-persistence-test
  "Unit tests for auth repository operations (auth_users table)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.user.shell.auth-persistence :as auth-persistence]
            [wagoe.user.shell.mfa-crypto :as mfa-crypto]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection])
  (:import [java.time Instant]
           [com.zaxxer.hikari HikariDataSource]))

(def test-db-context (atom nil))
(def test-auth-repository (atom nil))

(defn setup-test-db []
  (let [^HikariDataSource datasource (connection/->pool
                                      com.zaxxer.hikari.HikariDataSource
                                      {:jdbcUrl "jdbc:h2:mem:auth-repo-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                                       :username "sa"
                                       :password ""})
        adapter (h2/new-adapter)
        db-ctx {:datasource datasource :adapter adapter}]
    (reset! test-db-context db-ctx)

    ;; Create auth_users table
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

    (reset! test-auth-repository (auth-persistence/create-auth-user-repository db-ctx))))

(defn teardown-test-db []
  (when-let [db-ctx @test-db-context]
    (when-let [^HikariDataSource datasource (:datasource db-ctx)]
      (.close datasource)))
  (reset! test-db-context nil)
  (reset! test-auth-repository nil))

(use-fixtures :once (fn [f] (setup-test-db) (try (f) (finally (teardown-test-db)))))

(defn clean-test-database! []
  (when-let [db-ctx @test-db-context]
    (jdbc/execute! (:datasource db-ctx) ["DELETE FROM auth_users"])))

(use-fixtures :each (fn [f] (clean-test-database!) (f)))

(deftest ^:contract auth-user-crud-operations
  (testing "Create and find auth user"
    (let [repo @test-auth-repository
          auth-user {:email "test@example.com"
                     :password-hash "$2a$12$test.hash"
                     :active true
                     :mfa-enabled false
                     :failed-login-count 0}
          created (.create-auth-user repo auth-user)]

      (is (some? (:id created)) "Should generate ID")
      (is (= "test@example.com" (:email created)))
      (is (some? (:created-at created)) "Should have created-at timestamp")

      ;; Find by ID
      (let [found-by-id (.find-auth-user-by-id repo (:id created))]
        (is (some? found-by-id))
        (is (= (:email created) (:email found-by-id))))

      ;; Find by email
      (let [found-by-email (.find-auth-user-by-email repo "test@example.com")]
        (is (some? found-by-email))
        (is (= (:id created) (:id found-by-email)))))))

(deftest ^:contract auth-user-update-operations
  (testing "Update auth user fields"
    (let [repo @test-auth-repository
          auth-user {:email "update-test@example.com"
                     :password-hash "$2a$12$test.hash"
                     :active true
                     :mfa-enabled false
                     :failed-login-count 0}
          created (.create-auth-user repo auth-user)

          ;; Update some fields
          updated-data (assoc created
                              :active false
                              :failed-login-count 3
                              :updated-at (Instant/now))]

      (.update-auth-user repo updated-data)

      (let [found (.find-auth-user-by-id repo (:id created))]
        (is (= false (:active found)))
        (is (= 3 (:failed-login-count found)))))))

(deftest ^:contract auth-user-mfa-operations
  (testing "Enable and disable MFA"
    (let [repo @test-auth-repository
          auth-user {:email "mfa-test@example.com"
                     :password-hash "$2a$12$test.hash"
                     :active true
                     :mfa-enabled false
                     :failed-login-count 0}
          created (.create-auth-user repo auth-user)

          ;; Enable MFA
          mfa-secret "JBSWY3DPEHPK3PXP"
          backup-codes ["code1" "code2" "code3"]]

      (.enable-mfa repo (:id created) mfa-secret backup-codes)

      (let [found (.find-auth-user-by-id repo (:id created))]
        (is (= true (:mfa-enabled found)))
        ;; secret is encrypted at rest and decrypted back on read
        (is (= mfa-secret (:mfa-secret found)))
        ;; backup codes are stored bcrypt-hashed (not the plaintext), each hash
        ;; verifying against its plaintext code
        (is (not= backup-codes (:mfa-backup-codes found)))
        (is (every? (fn [[code hash]] (mfa-crypto/backup-code-matches? code hash))
                    (map vector backup-codes (:mfa-backup-codes found))))
        (is (some? (:mfa-enabled-at found))))

      ;; Disable MFA
      (.disable-mfa repo (:id created))

      (let [found (.find-auth-user-by-id repo (:id created))]
        (is (= false (:mfa-enabled found)))
        (is (nil? (:mfa-secret found)))))))

(deftest ^:contract auth-user-lockout-operations
  (testing "Increment failed logins and lockout operations"
    (let [repo @test-auth-repository
          auth-user {:email "lockout-test@example.com"
                     :password-hash "$2a$12$test.hash"
                     :active true
                     :mfa-enabled false
                     :failed-login-count 0}
          created (.create-auth-user repo auth-user)]

      ;; Increment failed login count
      (let [result1 (.increment-failed-login repo (:id created))
            result2 (.increment-failed-login repo (:id created))]
        (is (= true result1) "First increment should succeed")
        (is (= true result2) "Second increment should succeed"))

      (let [found (.find-auth-user-by-id repo (:id created))]
        (is (= 2 (:failed-login-count found)) "Failed login count should be 2"))

      ;; Set lockout (just verify operation succeeds)
      (let [lockout-until (.plusSeconds (Instant/now) 900)
            result (.set-lockout repo (:id created) lockout-until)]
        (is (= true result) "Set lockout should succeed"))

      ;; Clear lockout and reset failed logins
      (let [clear-result (.clear-lockout repo (:id created))
            reset-result (.reset-failed-login repo (:id created))]
        (is (= true clear-result) "Clear lockout should succeed")
        (is (= true reset-result) "Reset failed login should succeed"))

      (let [found (.find-auth-user-by-id repo (:id created))]
        (is (= 0 (:failed-login-count found)) "Failed login count should be reset to 0")))))

(deftest ^:contract auth-user-soft-delete
  (testing "Soft delete auth user"
    (let [repo @test-auth-repository
          auth-user {:email "delete-test@example.com"
                     :password-hash "$2a$12$test.hash"
                     :active true
                     :mfa-enabled false
                     :failed-login-count 0}
          created (.create-auth-user repo auth-user)
          user-id (:id created)]

      ;; Soft delete
      (let [deleted? (.soft-delete-auth-user repo user-id)]
        (is (= true deleted?)))

      ;; User should not be found (deleted_at is set)
      (let [found (.find-auth-user-by-id repo user-id)]
        (is (nil? found) "Soft-deleted users should not be returned by find methods")))))
