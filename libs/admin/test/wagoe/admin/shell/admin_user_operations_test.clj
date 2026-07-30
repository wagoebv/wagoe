(ns wagoe.admin.shell.admin-user-operations-test
  "Integration tests for admin user operations against the real auth_users/users schema.

   This namespace tests the complete admin service behavior for the split-table
   user entity: updates route fields to the correct table, soft-delete targets
   auth_users, bulk-delete uses auth_users, and list queries exclude deleted rows.

   Uses embedded PostgreSQL (io.zonky.test/embedded-postgres) instead of H2 so that
   tenant_id, tsvector, and other PG-specific columns are supported natively.

   Contrast with split-table-update-test which uses synthetic tables (test_auth /
   test_profiles). These tests use the actual production schema."
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.test.embedded-pg :as epg]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.shell.adapters.database.common.execution :as db]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.test.logging :refer [with-silent-logging]]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.util UUID]
           [java.time Instant]))

^{:kaocha.testable/meta {:integration true :admin true}}

;; =============================================================================
;; Config
;; =============================================================================

;; Entity config mirrors resources/conf/dev/admin/users.edn (and test).
;; Uses :soft-delete true so the service enables deleted_at filtering and
;; routes soft-deletes to :auth_users via :soft-delete-table.
(def ^:private admin-config
  {:enabled? true
   :base-path "/web/admin"
   :require-role :admin
   :entity-discovery {:mode :allowlist
                      :allowlist #{:admin-users}}
   :entities
   {:admin-users
    {:label           "Admin Users"
     :list-fields     [:email :name :role :active :created-at]
     :search-fields   [:email :name]
     :hide-fields     #{:deleted-at}
     :readonly-fields #{:id :mfa-enabled :created-at :updated-at}
     :table-name      :users
     :soft-delete     true
     :fields          {:email      {:type :string  :label "Email"}
                       :name       {:type :string  :label "Name"}
                       :role       {:type :enum    :label "Role"
                                    :options [[:admin "Admin"] [:user "User"]]}
                       :active     {:type :boolean :label "Active"}
                       :created-at {:type :instant :label "Created"}}
     :split-table-update {:secondary-table  :auth_users
                          :secondary-fields #{:email :active}}
     :query-overrides
     {:from          [[:auth_users :a]]
      :join          [[:users :u] [:= :a.id :u.id]]
      :select        [:a.id :a.email :a.active
                      :a.created_at :a.updated_at :a.deleted_at
                      :u.name :u.role]
      :field-aliases {:id         :a.id
                      :email      :a.email
                      :active     :a.active
                      :deleted-at :a.deleted_at
                      :created-at :a.created_at
                      :name       :u.name
                      :role       :u.role}
      :soft-delete-table :auth_users}}}
   :pagination {:default-page-size 50
                :max-page-size 200}})

;; =============================================================================
;; Setup / Teardown (Embedded PostgreSQL)
;; =============================================================================

(defonce ^:dynamic *pg* nil)
(defonce ^:dynamic *db-ctx* nil)
(defonce ^:dynamic *admin-service* nil)

(defn- create-tables! [db-ctx]
  ;; auth_users is the global identity table (owns email, active, deleted_at)
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS auth_users (
           id           UUID         PRIMARY KEY,
           email        VARCHAR(255) NOT NULL UNIQUE,
           active       BOOLEAN      NOT NULL DEFAULT TRUE,
           mfa_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
           created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
           updated_at   TIMESTAMP,
           deleted_at   TIMESTAMP,
           password_hash VARCHAR(255))"})
  ;; users is the tenant-profile table (owns name, role, tenant_id)
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS users (
           id         UUID         PRIMARY KEY,
           name       VARCHAR(255) NOT NULL,
           role       VARCHAR(50)  NOT NULL DEFAULT 'user',
           tenant_id  UUID,
           updated_at TIMESTAMP,
           deleted_at TIMESTAMP,
           FOREIGN KEY (id) REFERENCES auth_users(id))"}))

(defn- drop-tables! [db-ctx]
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS users"})
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS auth_users"}))

(defn- setup! []
  (let [pg     (epg/start!)
        db-ctx (epg/db-context pg)
        logger (logging-no-op/create-logging-component {})
        errors (error-reporting-no-op/create-error-reporting-component {})]
    (alter-var-root #'*pg* (constantly pg))
    (alter-var-root #'*db-ctx* (constantly db-ctx))
    (create-tables! db-ctx)
    (let [schema (schema-repo/create-schema-repository db-ctx admin-config)
          svc    (service/create-admin-service db-ctx schema logger errors admin-config)]
      (alter-var-root #'*admin-service* (constantly svc)))))

(defn- teardown! []
  (when *db-ctx*
    (drop-tables! *db-ctx*)
    (db-factory/close-db-context! *db-ctx*)
    (alter-var-root #'*db-ctx*       (constantly nil))
    (alter-var-root #'*admin-service* (constantly nil)))
  (when *pg*
    (epg/stop! *pg*)
    (alter-var-root #'*pg* (constantly nil))))

(defn- clean-tables [f]
  (when *db-ctx*
    (db/execute-update! *db-ctx* {:raw "DELETE FROM users"})
    (db/execute-update! *db-ctx* {:raw "DELETE FROM auth_users"}))
  (f))

(use-fixtures :once (fn [f] (setup!) (f) (teardown!)))
(use-fixtures :each clean-tables)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- insert-user!
  "Insert matching rows into auth_users and users. Returns UUID."
  ([email name] (insert-user! email name true "user"))
  ([email name active role]
   (let [id      (UUID/randomUUID)
         now-str (.toString (Instant/now))]
     (db/execute-one! *db-ctx*
                      {:insert-into :auth_users
                       :values      [{:id id :email email :active active :created_at now-str}]})
     (db/execute-one! *db-ctx*
                      {:insert-into :users
                       :values      [{:id id :name name :role role}]})
     id)))

(defn- fetch-auth [id]
  (db/execute-one! *db-ctx* {:select [:*] :from [:auth_users] :where [:= :id id]}))

(defn- fetch-profile [id]
  (db/execute-one! *db-ctx* {:select [:*] :from [:users] :where [:= :id id]}))

;; =============================================================================
;; Update tests
;; =============================================================================

(deftest ^:integration update-primary-field-persists-to-users-table
  (testing "Updating :name (primary field) writes to users table"
    (let [id (insert-user! "primary@test.com" "OldName")]
      (ports/update-entity *admin-service* :admin-users id {:name "NewName"})
      (is (= "NewName" (:name (fetch-profile id)))
          "name must be updated in users table")))

  (testing "Updating :role (primary field) writes to users table"
    (let [id (insert-user! "role@test.com" "RoleUser" true "user")]
      (ports/update-entity *admin-service* :admin-users id {:role "admin"})
      (is (= "admin" (:role (fetch-profile id)))
          "role must be updated in users table"))))

(deftest ^:integration update-secondary-field-persists-to-auth-users-table
  (testing "Updating :email (secondary field) writes to auth_users table"
    (let [id (insert-user! "old@test.com" "EmailUser")]
      (ports/update-entity *admin-service* :admin-users id {:email "new@test.com"})
      (is (= "new@test.com" (:email (fetch-auth id)))
          "email must be updated in auth_users table")))

  (testing "Updating :active (secondary field) writes to auth_users table"
    (let [id (insert-user! "active@test.com" "ActiveUser" true "user")]
      (ports/update-entity *admin-service* :admin-users id {:active false})
      (is (false? (:active (fetch-auth id)))
          "active must be updated in auth_users table"))))

(deftest ^:integration update-mixed-fields-updates-both-tables-atomically
  (testing "Updating fields across both tables in one call"
    (let [id (insert-user! "mixed@test.com" "MixedUser")]
      (ports/update-entity *admin-service* :admin-users id
                           {:name "Updated" :email "mixed-new@test.com" :active false})
      (is (= "Updated" (:name (fetch-profile id)))
          "name must persist to users")
      (is (= "mixed-new@test.com" (:email (fetch-auth id)))
          "email must persist to auth_users")
      (is (false? (:active (fetch-auth id)))
          "active must persist to auth_users"))))

(deftest ^:integration update-returns-full-joined-record
  (testing "update-entity return value includes fields from both tables"
    (let [id     (insert-user! "return@test.com" "ReturnUser")
          result (ports/update-entity *admin-service* :admin-users id {:name "ReturnNew"})]
      (is (some? result) "must return a record")
      (is (= "ReturnNew" (:name result)) "returned record must reflect updated name")
      (is (some? (:email result)) "returned record must include email from auth_users"))))

(deftest ^:integration inline-edit-secondary-field-routes-to-auth-users
  (testing "update-entity-field for :email writes to auth_users"
    (let [id (insert-user! "inline-email@test.com" "InlineUser")]
      (ports/update-entity-field *admin-service* :admin-users id :email "inline-new@test.com")
      (is (= "inline-new@test.com" (:email (fetch-auth id)))
          "email must persist to auth_users via inline edit")))

  (testing "update-entity-field for :active writes to auth_users"
    (let [id (insert-user! "inline-active@test.com" "InlineActive" true "user")]
      (ports/update-entity-field *admin-service* :admin-users id :active false)
      (is (false? (:active (fetch-auth id)))
          "active must persist to auth_users via inline edit"))))

(deftest ^:integration inline-edit-primary-field-routes-to-users
  (testing "update-entity-field for :name writes to users"
    (let [id (insert-user! "inline-name@test.com" "Before")]
      (ports/update-entity-field *admin-service* :admin-users id :name "After")
      (is (= "After" (:name (fetch-profile id)))
          "name must persist to users via inline edit"))))

(deftest ^:integration transaction-rollback-on-secondary-failure
  (with-silent-logging
    (testing "When secondary UPDATE fails, primary UPDATE rolls back"
      (let [id-a (insert-user! "tx-a@test.com" "User A")
            _    (insert-user! "tx-b@test.com" "User B")]
        ;; Try to set A's email to B's (UNIQUE constraint violation)
        (is (thrown? Exception
                     (ports/update-entity *admin-service* :admin-users id-a
                                          {:email "tx-b@test.com" :name "Collided"})))
        ;; A's name must still be "User A" — primary UPDATE rolled back
        (is (= "User A" (:name (fetch-profile id-a))))
        (is (= "tx-a@test.com" (:email (fetch-auth id-a))))))))

;; =============================================================================
;; Soft-delete and list tests
;; =============================================================================

(deftest ^:integration list-excludes-soft-deleted-users
  (testing "Soft-deleted users do not appear in list"
    (let [live-id    (insert-user! "live@test.com" "Live User")
          deleted-id (insert-user! "deleted@test.com" "Deleted User")]
      ;; Soft-delete one user via the service (goes to auth_users.deleted_at)
      (ports/delete-entity *admin-service* :admin-users deleted-id)
      (let [result  (ports/list-entities *admin-service* :admin-users {})
            records (:records result)
            ids     (set (map :id records))]
        (is (contains? ids live-id)
            "live user must appear in list")
        (is (not (contains? ids deleted-id))
            "soft-deleted user must NOT appear in list")))))

(deftest ^:integration delete-entity-soft-deletes-on-auth-users
  (testing "delete-entity sets deleted_at on auth_users, not users"
    (let [id (insert-user! "softdel@test.com" "SoftDel")]
      (ports/delete-entity *admin-service* :admin-users id)
      ;; auth_users.deleted_at must be set
      (let [auth-row (fetch-auth id)]
        (is (some? (:deleted-at auth-row))
            "auth_users.deleted_at must be set after soft-delete"))
      ;; users row must still exist (not hard-deleted)
      (let [profile-row (fetch-profile id)]
        (is (some? profile-row)
            "users row must still exist after soft-delete")))))

(deftest ^:integration delete-entity-sets-active-false-on-soft-delete
  (testing "delete-entity sets active=false on auth_users during soft-delete"
    (let [id (insert-user! "deactivate@test.com" "ActiveUser" true "user")]
      (ports/delete-entity *admin-service* :admin-users id)
      (let [auth-row (fetch-auth id)]
        (is (false? (:active auth-row))
            "active must be false after soft-delete")))))

;; =============================================================================
;; Bulk-delete tests
;; =============================================================================

(deftest ^:integration bulk-delete-soft-deletes-on-auth-users
  (testing "bulk-delete-entities sets deleted_at on auth_users rows"
    (let [id-1 (insert-user! "bulk1@test.com" "Bulk One")
          id-2 (insert-user! "bulk2@test.com" "Bulk Two")
          id-3 (insert-user! "bulk3@test.com" "Bulk Three - keep")]
      (ports/bulk-delete-entities *admin-service* :admin-users [id-1 id-2])
      ;; deleted_at must be set on auth_users for id-1 and id-2
      (let [auth-1 (fetch-auth id-1)
            auth-2 (fetch-auth id-2)
            auth-3 (fetch-auth id-3)]
        (is (some? (:deleted-at auth-1))
            "auth_users.deleted_at must be set for bulk-deleted user 1")
        (is (some? (:deleted-at auth-2))
            "auth_users.deleted_at must be set for bulk-deleted user 2")
        (is (nil? (:deleted-at auth-3))
            "auth_users.deleted_at must NOT be set for kept user")))))

(deftest ^:integration bulk-delete-does-not-delete-users-rows
  (testing "bulk-delete-entities does not hard-delete users table rows"
    (let [id-1 (insert-user! "nodelbulk1@test.com" "NoDel One")
          id-2 (insert-user! "nodelbulk2@test.com" "NoDel Two")]
      (ports/bulk-delete-entities *admin-service* :admin-users [id-1 id-2])
      ;; users rows must still exist (FK integrity)
      (is (some? (fetch-profile id-1))
          "users row must still exist after bulk soft-delete")
      (is (some? (fetch-profile id-2))
          "users row must still exist after bulk soft-delete"))))

(deftest ^:integration bulk-deleted-users-excluded-from-list
  (testing "Users bulk-deleted do not appear in list"
    (let [id-1 (insert-user! "bulklist1@test.com" "BulkList One")
          id-2 (insert-user! "bulklist2@test.com" "BulkList Two")
          id-3 (insert-user! "bulklist3@test.com" "BulkList Three - keep")]
      (ports/bulk-delete-entities *admin-service* :admin-users [id-1 id-2])
      (let [result  (ports/list-entities *admin-service* :admin-users {})
            ids     (set (map :id (:records result)))]
        (is (not (contains? ids id-1)) "bulk-deleted user must not appear in list")
        (is (not (contains? ids id-2)) "bulk-deleted user must not appear in list")
        (is (contains? ids id-3)       "non-deleted user must appear in list")))))
