(ns wagoe.admin.shell.split-table-update-test
  "Integration tests for split-table update support in the admin service.

   These tests verify that :split-table-update in an entity config correctly
   routes field updates to the right table, wraps them in a transaction, and
   skips tables whose fields were not touched by the submitted form.

   Schema layout:
     test_profiles ← primary table (entity name :test-profiles maps here)
     test_auth     ← secondary table (:email :active live here)

   The schema repository discovers column metadata from test_profiles (the
   entity name table).  :split-table-update then routes :email/:active writes
   to test_auth while profile field writes stay in test_profiles."
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.test.logging :refer [with-silent-logging]]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.util UUID]
           [java.time Instant]))

^{:kaocha.testable/meta {:integration true :admin true}}

;; =============================================================================
;; Test Database Setup
;; =============================================================================

(def test-db-config
  {:adapter :h2
   :database-path nil
   :pool {:minimum-idle 1
          :maximum-pool-size 3}})

;; Entity name :test-profiles → schema repo discovers columns from test_profiles.
;; :split-table-update routes :email/:active writes to test_auth.
(def admin-config
  {:enabled? true
   :base-path "/web/admin"
   :require-role :admin
   :entity-discovery {:mode :allowlist
                      :allowlist #{:test-profiles :plain-items}}
   :entities
   {:test-profiles
    {:label           "Test Profiles"
     :list-fields     [:email :name :active]
     :search-fields   [:email :name]
     :hide-fields     #{}
     :readonly-fields #{:id :created-at :updated-at}
     :table-name      :test_profiles
     ;; Explicitly declare cross-table fields so merge-fields-config includes them
     ;; in :fields and update-entity-field validation can find them.
     :fields          {:email  {:type :string  :label "Email"}
                       :active {:type :boolean :label "Active"}}
     :split-table-update {:secondary-table :test_auth
                          :secondary-fields #{:email :active}}
     :query-overrides
     {:from          [[:test_auth :a]]
      :join          [[:test_profiles :p] [:= :a.id :p.id]]
      :select        [:a.id :a.email :a.active
                      :a.updated_at :a.created_at
                      :p.name]
      :field-aliases {:id         :a.id
                      :email      :a.email
                      :active     :a.active
                      :updated-at :a.updated_at
                      :created-at :a.created_at
                      :name       :p.name}}}

    :plain-items
    {:label           "Plain Items"
     :list-fields     [:name :quantity]
     :search-fields   [:name]
     :hide-fields     #{}
     :readonly-fields #{:id :created-at}}}
   :pagination {:default-page-size 50
                :max-page-size 200}})

(defonce ^:dynamic *db-ctx* nil)
(defonce ^:dynamic *admin-service* nil)

(defn create-test-tables! [db-ctx]
  ;; Secondary table (auth-like, owns :email and :active)
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS test_auth (
           id         UUID         PRIMARY KEY,
           email      VARCHAR(255) NOT NULL UNIQUE,
           active     BOOLEAN      NOT NULL DEFAULT TRUE,
           updated_at TIMESTAMP,
           created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP)"})
  ;; Primary table (profile-like, entity name maps here for schema discovery)
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS test_profiles (
           id         UUID         PRIMARY KEY,
           name       VARCHAR(255) NOT NULL,
           updated_at TIMESTAMP,
           created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP)"})
  ;; Single-table regression guard entity
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS plain_items (
           id         UUID         PRIMARY KEY,
           name       VARCHAR(255) NOT NULL,
           quantity   INTEGER      NOT NULL DEFAULT 0,
           created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
           updated_at TIMESTAMP)"}))

(defn drop-test-tables! [db-ctx]
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS test_profiles"})
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS test_auth"})
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS plain_items"}))

(defn setup-test-system! []
  (let [db-config       (assoc test-db-config
                               :database-path
                               (str "mem:admin_split_table_test_" (UUID/randomUUID)
                                    ";DB_CLOSE_DELAY=-1"))
        db-ctx          (db-factory/db-context db-config)
        logger          (logging-no-op/create-logging-component {})
        error-reporter  (error-reporting-no-op/create-error-reporting-component {})
        schema-provider (schema-repo/create-schema-repository db-ctx admin-config)
        admin-service   (service/create-admin-service db-ctx schema-provider logger error-reporter admin-config)]
    (create-test-tables! db-ctx)
    (alter-var-root #'*db-ctx*       (constantly db-ctx))
    (alter-var-root #'*admin-service* (constantly admin-service))))

(defn teardown-test-system! []
  (when *db-ctx*
    (drop-test-tables! *db-ctx*)
    (db-factory/close-db-context! *db-ctx*)
    (alter-var-root #'*db-ctx*       (constantly nil))
    (alter-var-root #'*admin-service* (constantly nil))))

(defn clean-tables [f]
  (when *db-ctx*
    (db/execute-update! *db-ctx* {:raw "DELETE FROM test_profiles"})
    (db/execute-update! *db-ctx* {:raw "DELETE FROM test_auth"})
    (db/execute-update! *db-ctx* {:raw "DELETE FROM plain_items"}))
  (f))

(use-fixtures :once
  (fn [f]
    (setup-test-system!)
    (f)
    (teardown-test-system!)))

(use-fixtures :each clean-tables)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn insert-split-user!
  "Insert matching rows into both test_auth and test_profiles."
  ([email name] (insert-split-user! email name true))
  ([email name active]
   (let [id      (UUID/randomUUID)
         now-str (.toString (Instant/now))]
     (db/execute-one! *db-ctx*
                      {:insert-into :test_auth
                       :values      [{:id id :email email :active active :created_at now-str}]})
     (db/execute-one! *db-ctx*
                      {:insert-into :test_profiles
                       :values      [{:id id :name name :created_at now-str}]})
     id)))

(defn fetch-auth-row [id]
  (db/execute-one! *db-ctx* {:select [:*] :from [:test_auth] :where [:= :id id]}))

(defn fetch-profile-row [id]
  (db/execute-one! *db-ctx* {:select [:*] :from [:test_profiles] :where [:= :id id]}))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest ^:integration split-update-updates-both-tables
  (testing "Updating email (auth) and name (profile) touches both tables"
    (let [id (insert-split-user! "original@example.com" "OriginalName")
          _  (ports/update-entity *admin-service* :test-profiles id
                                  {:email "new@example.com" :name "Alice"})]
      (is (= "new@example.com" (:email (fetch-auth-row id))))
      (is (= "Alice" (:name (fetch-profile-row id)))))))

(deftest ^:integration primary-only-update-leaves-auth-untouched
  (testing "Updating only name (profile) leaves test_auth row unchanged"
    (let [id (insert-split-user! "keep@example.com" "OldName")]
      (ports/update-entity *admin-service* :test-profiles id {:name "Bob"})
      (is (= "keep@example.com" (:email (fetch-auth-row id))))
      (is (= "Bob" (:name (fetch-profile-row id)))))))

(deftest ^:integration secondary-only-update-leaves-profile-untouched
  (testing "Updating only email (auth) leaves test_profiles row unchanged"
    (let [id (insert-split-user! "change-me@example.com" "Unchanged")]
      (ports/update-entity *admin-service* :test-profiles id {:email "changed@example.com"})
      (is (= "changed@example.com" (:email (fetch-auth-row id))))
      (is (= "Unchanged" (:name (fetch-profile-row id)))))))

(deftest ^:integration transaction-rolls-back-primary-on-secondary-failure
  (with-silent-logging
    (testing "When secondary UPDATE fails, primary UPDATE is rolled back"
      ;; Arrange: two users; B's email will be the duplicate target
      (let [id-a (insert-split-user! "user-a@example.com" "User A")
            _    (insert-split-user! "user-b@example.com" "User B")]
        ;; Act: try to set A's email to B's (UNIQUE violation) while also changing A's name.
        ;; The secondary UPDATE (test_auth.email) should fail, rolling back the profile UPDATE.
        (is (thrown? Exception
                     (ports/update-entity *admin-service* :test-profiles id-a
                                          {:email "user-b@example.com" :name "Collided"})))
        ;; Assert: A's name must still be "User A" — the primary UPDATE was rolled back
        (is (= "User A" (:name (fetch-profile-row id-a))))
        ;; Assert: A's email is still its original value
        (is (= "user-a@example.com" (:email (fetch-auth-row id-a))))))))

(deftest ^:integration inline-edit-secondary-field-routes-to-correct-table
  (testing "update-entity-field for a secondary field writes to the secondary table"
    (let [id (insert-split-user! "inline@example.com" "Inline User")]
      (ports/update-entity-field *admin-service* :test-profiles id :email "inline-new@example.com")
      ;; email lives in test_auth — must be updated there
      (is (= "inline-new@example.com" (:email (fetch-auth-row id))))
      ;; test_profiles must be untouched
      (is (= "Inline User" (:name (fetch-profile-row id))))))

  (testing "update-entity-field for a primary field writes to the primary table"
    (let [id (insert-split-user! "inline2@example.com" "Before")]
      (ports/update-entity-field *admin-service* :test-profiles id :name "After")
      ;; name lives in test_profiles
      (is (= "After" (:name (fetch-profile-row id))))
      ;; test_auth.email must be untouched
      (is (= "inline2@example.com" (:email (fetch-auth-row id)))))))

(deftest ^:integration non-split-entity-unchanged
  (testing "Single-table entities still work after split-table code path was added"
    (let [id  (UUID/randomUUID)
          now (.toString (Instant/now))]
      (db/execute-one! *db-ctx*
                       {:insert-into :plain_items
                        :values      [{:id id :name "Widget" :quantity 5 :created_at now}]})
      (ports/update-entity *admin-service* :plain-items id {:name "Gadget" :quantity 10})
      (let [row (db/execute-one! *db-ctx*
                                 {:select [:*] :from [:plain_items] :where [:= :id id]})]
        (is (= "Gadget" (:name row)))
        (is (= 10 (:quantity row)))))))
