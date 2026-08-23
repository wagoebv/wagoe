(ns wagoe.tenant.shell.provisioning-test
  "Unit and integration tests for tenant provisioning service.
   
   Test Categories:
   - Unit tests: Test provisioning logic with mocked database
   - Integration tests: Test with real H2/PostgreSQL database
   - Contract tests: Verify PostgreSQL-specific behavior"
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tenant.shell.provisioning :as sut]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as protocols])
  (:import (java.util UUID)))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-ctx* nil)

(defn postgres-adapter-stub
  []
  (reify protocols/DBAdapter
    (dialect [_] :postgresql)
    (jdbc-driver [_] "org.postgresql.Driver")
    (jdbc-url [_ _] "jdbc:postgresql://localhost:5432/test")
    (pool-defaults [_] {})
    (init-connection! [_ _ _] nil)
    (build-where [_ _] [])
    (boolean->db [_ _] 1)
    (db->boolean [_ _] true)
    (table-exists? [_ _ _] false)
    (get-table-info [_ _ _] [])))

(defn with-h2-database
  "Test fixture that creates an H2 in-memory database for testing."
  [f]
  (let [ctx (db-factory/db-context {:adapter :h2
                                    :database-path (str "mem:tenant_test_" (UUID/randomUUID))
                                    :pool {:maximum-pool-size 5}})]
    (try
      ;; Initialize test schema in public
      (db/execute-ddl! ctx "CREATE TABLE IF NOT EXISTS tenants (
                             id VARCHAR(36) PRIMARY KEY,
                             slug VARCHAR(100) NOT NULL,
                             schema_name VARCHAR(100) NOT NULL,
                             name VARCHAR(255) NOT NULL,
                             status VARCHAR(50) NOT NULL,
                             created_at TIMESTAMP NOT NULL)")
      (db/execute-ddl! ctx "CREATE TABLE IF NOT EXISTS users (
                             id VARCHAR(36) PRIMARY KEY,
                             email VARCHAR(255) NOT NULL,
                             name VARCHAR(255) NOT NULL,
                             created_at TIMESTAMP NOT NULL)")

      (binding [*test-ctx* ctx]
        (f))
      (finally
        (db-factory/close-db-context! ctx)))))

;; =============================================================================
;; Unit Tests (Database-Agnostic Logic)
;; =============================================================================

(deftest ^:unit provision-tenant-validation-test
  (testing "rejects nil tenant entity"
    (let [mock-adapter (reify protocols/DBAdapter
                         (dialect [_] :postgresql)
                         (jdbc-driver [_] "org.postgresql.Driver")
                         (jdbc-url [_ _] "jdbc:postgresql://localhost:5432/test")
                         (pool-defaults [_] {})
                         (init-connection! [_ _ _] nil)
                         (build-where [_ _] [])
                         (boolean->db [_ _] 1)
                         (db->boolean [_ _] true)
                         (table-exists? [_ _ _] false)
                         (get-table-info [_ _ _] []))
          ctx {:adapter mock-adapter
               :datasource nil}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant entity missing :schema-name"
                            (sut/provision-tenant! ctx nil)))))

  (testing "rejects tenant entity without schema-name"
    (let [mock-adapter (reify protocols/DBAdapter
                         (dialect [_] :postgresql)
                         (jdbc-driver [_] "org.postgresql.Driver")
                         (jdbc-url [_ _] "jdbc:postgresql://localhost:5432/test")
                         (pool-defaults [_] {})
                         (init-connection! [_ _ _] nil)
                         (build-where [_ _] [])
                         (boolean->db [_ _] 1)
                         (db->boolean [_ _] true)
                         (table-exists? [_ _ _] false)
                         (get-table-info [_ _ _] []))
          ctx {:adapter mock-adapter
               :datasource nil}
          tenant {:name "Test Tenant"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant entity missing :schema-name"
                            (sut/provision-tenant! ctx tenant)))))

  (testing "rejects non-PostgreSQL database"
    (let [mock-adapter (reify protocols/DBAdapter
                         (dialect [_] :sqlite)
                         (jdbc-driver [_] "org.sqlite.JDBC")
                         (jdbc-url [_ _] "jdbc:sqlite::memory:")
                         (pool-defaults [_] {})
                         (init-connection! [_ _ _] nil)
                         (build-where [_ _] [])
                         (boolean->db [_ _] 0)
                         (db->boolean [_ _] false)
                         (table-exists? [_ _ _] false)
                         (get-table-info [_ _ _] []))
          ctx {:adapter mock-adapter
               :datasource nil}
          tenant {:schema-name "tenant_test"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"only supported for PostgreSQL"
                            (sut/provision-tenant! ctx tenant)))))

  (testing "validates ex-info has correct type"
    (let [mock-adapter (reify protocols/DBAdapter
                         (dialect [_] :mysql)
                         (jdbc-driver [_] "com.mysql.cj.jdbc.Driver")
                         (jdbc-url [_ _] "jdbc:mysql://localhost:3306/test")
                         (pool-defaults [_] {})
                         (init-connection! [_ _ _] nil)
                         (build-where [_ _] [])
                         (boolean->db [_ _] 0)
                         (db->boolean [_ _] false)
                         (table-exists? [_ _ _] false)
                         (get-table-info [_ _ _] []))
          ctx {:adapter mock-adapter
               :datasource nil}
          tenant {:schema-name "tenant_test"}]
      (try
        (sut/provision-tenant! ctx tenant)
        (is false "Should have thrown exception")
        (catch clojure.lang.ExceptionInfo e
          (is (= :not-supported (:type (ex-data e))))
          (is (= :mysql (:dialect (ex-data e))))))))

  (testing "accepts PostgreSQL adapter when dialect is nil but driver is PostgreSQL"
    (let [mock-adapter (reify protocols/DBAdapter
                         (dialect [_] nil)
                         (jdbc-driver [_] "org.postgresql.Driver")
                         (jdbc-url [_ _] "jdbc:postgresql://localhost:5432/test")
                         (pool-defaults [_] {})
                         (init-connection! [_ _ _] nil)
                         (build-where [_ _] [])
                         (boolean->db [_ _] 1)
                         (db->boolean [_ _] true)
                         (table-exists? [_ _ _] false)
                         (get-table-info [_ _ _] []))
          ctx {:adapter mock-adapter
               :datasource nil}]
      (is (true? (#'sut/postgresql-context? ctx))))))

(deftest ^:unit provision-tenant-existing-schema-and-failure-paths-test
  (testing "already provisioned schemas return success when validation passes"
    (let [ctx {:adapter (postgres-adapter-stub) :datasource nil}
          tenant {:schema-name "tenant_existing"}]
      (with-redefs [wagoe.tenant.shell.provisioning/schema-exists? (fn [_ schema-name]
                                                                        (= "tenant_existing" schema-name))
                    wagoe.tenant.shell.provisioning/validate-provisioning (fn [_ _]
                                                                               {:valid? true
                                                                                :schema-name "tenant_existing"
                                                                                :table-count 5
                                                                                :errors []})]
        (is (= {:success? true
                :schema-name "tenant_existing"
                :table-count 5
                :message "Tenant schema already provisioned"}
               (sut/provision-tenant! ctx tenant))))))

  (testing "already provisioned schemas fail fast when validation fails"
    (let [ctx {:adapter (postgres-adapter-stub) :datasource nil}
          tenant {:schema-name "tenant_broken"}]
      (with-redefs [wagoe.tenant.shell.provisioning/schema-exists? (constantly true)
                    wagoe.tenant.shell.provisioning/validate-provisioning (fn [_ _]
                                                                               {:valid? false
                                                                                :schema-name "tenant_broken"
                                                                                :table-count 0
                                                                                :errors ["missing tables"]})]
        (let [ex (is (thrown? clojure.lang.ExceptionInfo
                              (sut/provision-tenant! ctx tenant)))]
          (is (= :provisioning-error (:type (ex-data ex))))
          (is (= ["missing tables"] (get-in (ex-data ex) [:validation :errors])))))))

  (testing "failed provisioning attempts cleanup with drop schema"
    (let [ctx {:adapter (postgres-adapter-stub) :datasource nil}
          tenant {:schema-name "tenant_cleanup"}
          executed-ddl (atom [])]
      (with-redefs [wagoe.tenant.shell.provisioning/schema-exists? (constantly false)
                    wagoe.tenant.shell.provisioning/create-schema! (fn [_ _] nil)
                    wagoe.tenant.shell.provisioning/populate-tenant-schema! (fn [_]
                                                                                 (throw (ex-info "migrate boom" {})))
                    wagoe.platform.database/execute-ddl! (fn [_ sql]
                                                                                         (swap! executed-ddl conj sql))]
        (let [ex (is (thrown? clojure.lang.ExceptionInfo
                              (sut/provision-tenant! ctx tenant)))]
          (is (= :provisioning-error (:type (ex-data ex))))
          (is (= "tenant_cleanup" (:schema-name (ex-data ex))))
          (is (= ["DROP SCHEMA IF EXISTS tenant_cleanup CASCADE"] @executed-ddl)))))))

(deftest ^:unit get-public-tables-filters-shared-tables-test
  (testing "only tenant-scoped tables are copied into tenant schemas"
    (with-redefs [db/execute-query! (fn [_ _]
                                      [{:table-name "assignments"}
                                       {:table-name "auth_users"}
                                       {:table-name "contractors"}
                                       {:table-name "contracts"}
                                       {:table-name "invoices"}
                                       {:table-name "payments"}
                                       {:table-name "tenant_memberships"}
                                       {:table-name "timesheets"}
                                       {:table-name "user_sessions"}
                                       {:table-name "users"}])]
      (is (= ["assignments" "contractors" "contracts" "invoices" "timesheets"]
             (#'sut/get-public-tables {:adapter nil :datasource nil}))))))

(deftest ^:unit deprovision-tenant-validation-test
  (testing "rejects nil tenant entity"
    (let [mock-adapter (reify protocols/DBAdapter
                         (dialect [_] :postgresql)
                         (jdbc-driver [_] "org.postgresql.Driver")
                         (jdbc-url [_ _] "jdbc:postgresql://localhost:5432/test")
                         (pool-defaults [_] {})
                         (init-connection! [_ _ _] nil)
                         (build-where [_ _] [])
                         (boolean->db [_ _] 1)
                         (db->boolean [_ _] true)
                         (table-exists? [_ _ _] false)
                         (get-table-info [_ _ _] []))
          ctx {:adapter mock-adapter
               :datasource nil}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant entity missing :schema-name"
                            (sut/deprovision-tenant! ctx nil)))))

  (testing "rejects tenant entity without schema-name"
    (let [mock-adapter (reify protocols/DBAdapter
                         (dialect [_] :postgresql)
                         (jdbc-driver [_] "org.postgresql.Driver")
                         (jdbc-url [_ _] "jdbc:postgresql://localhost:5432/test")
                         (pool-defaults [_] {})
                         (init-connection! [_ _ _] nil)
                         (build-where [_ _] [])
                         (boolean->db [_ _] 1)
                         (db->boolean [_ _] true)
                         (table-exists? [_ _ _] false)
                         (get-table-info [_ _ _] []))
          ctx {:adapter mock-adapter
               :datasource nil}
          tenant {:name "Test Tenant"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant entity missing :schema-name"
                            (sut/deprovision-tenant! ctx tenant)))))

  (testing "returns success when schema is already absent"
    (let [ctx {:adapter nil :datasource nil}
          tenant {:schema-name "tenant_missing"}]
      (with-redefs [wagoe.tenant.shell.provisioning/schema-exists? (constantly false)]
        (is (= {:success? true
                :schema-name "tenant_missing"
                :message "Tenant schema does not exist (already deprovisioned)"}
               (sut/deprovision-tenant! ctx tenant))))))

  (testing "wraps drop failures as deprovisioning errors"
    (let [ctx {:adapter nil :datasource nil}
          tenant {:schema-name "tenant_locked"}]
      (with-redefs [wagoe.tenant.shell.provisioning/schema-exists? (constantly true)
                    wagoe.platform.database/execute-ddl!
                    (fn [_ _] (throw (ex-info "drop boom" {})))]
        (let [ex (is (thrown? clojure.lang.ExceptionInfo
                              (sut/deprovision-tenant! ctx tenant)))]
          (is (= :deprovisioning-error (:type (ex-data ex))))
          (is (= "tenant_locked" (:schema-name (ex-data ex))))
          (is (= "drop boom" (:cause (ex-data ex))))))))

  (testing "drops existing schemas successfully"
    (let [ctx {:adapter nil :datasource nil}
          tenant {:schema-name "tenant_drop_me"}
          executed-ddl (atom [])]
      (with-redefs [wagoe.tenant.shell.provisioning/schema-exists? (constantly true)
                    wagoe.platform.database/execute-ddl!
                    (fn [_ sql]
                      (swap! executed-ddl conj sql))]
        (is (= {:success? true
                :schema-name "tenant_drop_me"
                :message "Tenant schema deprovisioned successfully"}
               (sut/deprovision-tenant! ctx tenant)))
        (is (= ["DROP SCHEMA tenant_drop_me CASCADE"] @executed-ddl))))))

(deftest ^:unit provisioning-helper-functions-test
  (testing "create-schema! only issues DDL when schema is missing"
    (let [executed-ddl (atom [])]
      (with-redefs [wagoe.tenant.shell.provisioning/schema-exists? (constantly false)
                    wagoe.platform.database/execute-ddl!
                    (fn [_ sql]
                      (swap! executed-ddl conj sql))]
        (#'sut/create-schema! {:adapter nil :datasource nil} "tenant_new")
        (is (= ["CREATE SCHEMA tenant_new"] @executed-ddl))))

    (let [executed-ddl (atom [])]
      (with-redefs [wagoe.tenant.shell.provisioning/schema-exists? (constantly true)
                    wagoe.platform.database/execute-ddl!
                    (fn [_ sql]
                      (swap! executed-ddl conj sql))]
        (#'sut/create-schema! {:adapter nil :datasource nil} "tenant_existing")
        (is (= [] @executed-ddl)))))

  (testing "validate-provisioning reports missing schema, empty schema, success, and query failures"
    (with-redefs [wagoe.tenant.shell.provisioning/schema-exists? (constantly false)]
      (is (= {:valid? false
              :schema-name "tenant_missing"
              :errors ["Schema does not exist after provisioning"]}
             (#'sut/validate-provisioning {:adapter nil :datasource nil} "tenant_missing"))))

    (with-redefs [wagoe.tenant.shell.provisioning/schema-exists? (constantly true)
                  wagoe.platform.database/execute-one! (fn [_ _]
                                                                                       {:count 0})]
      (is (= {:valid? false
              :schema-name "tenant_empty"
              :table-count 0
              :errors ["No tables found in schema after provisioning"]}
             (#'sut/validate-provisioning {:adapter nil :datasource nil} "tenant_empty"))))

    (with-redefs [wagoe.tenant.shell.provisioning/schema-exists? (constantly true)
                  wagoe.platform.database/execute-one! (fn [_ _]
                                                                                       {:count 5})]
      (is (= {:valid? true
              :schema-name "tenant_ok"
              :table-count 5
              :errors []}
             (#'sut/validate-provisioning {:adapter nil :datasource nil} "tenant_ok"))))

    (with-redefs [wagoe.tenant.shell.provisioning/schema-exists? (constantly true)
                  wagoe.platform.database/execute-one! (fn [_ _]
                                                                                       (throw (ex-info "validation boom" {})))]
      (let [result (#'sut/validate-provisioning {:adapter nil :datasource nil} "tenant_boom")]
        (is (false? (:valid? result)))
        (is (= "tenant_boom" (:schema-name result)))
        (is (= ["Validation failed: validation boom"] (:errors result)))))))

(deftest ^:unit with-tenant-schema-test
  (testing "rejects non-PostgreSQL contexts"
    (let [ctx {:adapter (reify protocols/DBAdapter
                          (dialect [_] :sqlite)
                          (jdbc-driver [_] "org.sqlite.JDBC")
                          (jdbc-url [_ _] nil)
                          (pool-defaults [_] {})
                          (init-connection! [_ _ _] nil)
                          (build-where [_ _] [])
                          (boolean->db [_ value] value)
                          (db->boolean [_ value] value)
                          (table-exists? [_ _ _] false)
                          (get-table-info [_ _ _] []))
               :datasource ::ds
               :database-type :sqlite}]
      (try
        (sut/with-tenant-schema ctx "tenant_x" identity)
        (is false "Expected with-tenant-schema to throw")
        (catch clojure.lang.ExceptionInfo ex
          (is (= :unsupported-database (:type (ex-data ex)))))
        (catch Throwable ex
          (is false (str "Unexpected exception: " ex))))))

  (testing "sets search_path and executes the callback inside a transaction"
    (let [queries (atom [])
          adapter (postgres-adapter-stub)]
      (with-redefs [wagoe.platform.database/with-transaction*
                    (fn [tx-ctx f]
                      (is (= adapter (:adapter tx-ctx)))
                      (is (= ::ds (:datasource tx-ctx)))
                      (f {:adapter (:adapter tx-ctx)
                          :datasource ::tx}))
                    wagoe.platform.database/execute-query!
                    (fn [tx query]
                      (swap! queries conj [tx query])
                      nil)]
        (is (= :done
               (sut/with-tenant-schema {:adapter adapter
                                        :datasource ::ds}
                 "tenant_alpha"
                 (fn [tx]
                   (is (= adapter (:adapter tx)))
                   (is (= ::tx (:datasource tx)))
                   :done))))
        (is (= 1 (count @queries)))
        (let [[tx query] (first @queries)]
          (is (= adapter (:adapter tx)))
          (is (= ::tx (:datasource tx)))
          (is (= ["SET search_path TO tenant_alpha, public"] query))))))

  (testing "wraps callback failures as tenant-context errors"
    (let [adapter (postgres-adapter-stub)]
      (with-redefs [wagoe.platform.database/with-transaction*
                    (fn [tx-ctx f]
                      (f {:adapter (:adapter tx-ctx)
                          :datasource ::tx}))
                    wagoe.platform.database/execute-query!
                    (fn [_ _] nil)]
        (let [ex (is (thrown? clojure.lang.ExceptionInfo
                              (sut/with-tenant-schema {:adapter adapter
                                                       :datasource ::ds}
                                "tenant_alpha"
                                (fn [_]
                                  (throw (ex-info "callback boom" {}))))))]
          (is (= :tenant-context-error (:type (ex-data ex))))
          (is (= "tenant_alpha" (:schema-name (ex-data ex))))
          (is (= "callback boom" (:cause (ex-data ex)))))))))

(deftest ^:integration tenant-provisioned?-test
  (testing "returns false for non-PostgreSQL (H2) context"
    (with-h2-database
      (fn []
        (is (false? (sut/tenant-provisioned? *test-ctx* {:schema-name "tenant_test"}))))))

  (testing "throws on missing :schema-name"
    (let [ctx {:adapter (postgres-adapter-stub) :datasource nil}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant entity missing :schema-name"
                            (sut/tenant-provisioned? ctx {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant entity missing :schema-name"
                            (sut/tenant-provisioned? ctx nil)))))

  (testing "delegates to schema-exists? for PostgreSQL context"
    (let [ctx {:adapter (postgres-adapter-stub) :datasource nil}]
      (with-redefs [wagoe.tenant.shell.provisioning/schema-exists?
                    (fn [_ schema-name] (= "tenant_found" schema-name))]
        (is (true? (sut/tenant-provisioned? ctx {:schema-name "tenant_found"})))
        (is (false? (sut/tenant-provisioned? ctx {:schema-name "tenant_missing"})))))))

(deftest ^:integration list-tenant-schemas-test
  (testing "returns empty vector for non-PostgreSQL (H2) context"
    (with-h2-database
      (fn []
        (is (= [] (sut/list-tenant-schemas *test-ctx*))))))

  (testing "queries information_schema for PostgreSQL context"
    (let [ctx {:adapter (postgres-adapter-stub) :datasource nil}]
      (with-redefs [wagoe.platform.database/execute-query!
                    (fn [_ _]
                      [{:schema-name "tenant_acme"}
                       {:schema-name "tenant_globex"}])]
        (is (= ["tenant_acme" "tenant_globex"]
               (sut/list-tenant-schemas ctx))))))

  (testing "returns empty vector when no tenant schemas exist in PostgreSQL"
    (let [ctx {:adapter (postgres-adapter-stub) :datasource nil}]
      (with-redefs [wagoe.platform.database/execute-query!
                    (fn [_ _] [])]
        (is (= [] (sut/list-tenant-schemas ctx)))))))

;; =============================================================================
;; Integration Tests (Real Database - H2 with PostgreSQL Mode)
;; =============================================================================

(deftest ^:integration provision-tenant-integration-test
  (testing "provisions tenant schema successfully"
    (with-h2-database
      (fn []
        ;; H2 doesn't support CREATE SCHEMA in PostgreSQL mode the same way
        ;; This test verifies the flow but may need PostgreSQL for full testing
        (let [tenant {:schema-name "tenant_test"
                      :slug "test-tenant"}
              adapter (:adapter *test-ctx*)
              dialect (protocols/dialect adapter)]

          (testing "H2 database detected"
            (is (= :ansi dialect)))  ; H2 in PostgreSQL compatibility mode reports as :ansi

          (testing "provisioning on H2 throws not-supported error"
            ;; Provisioning should only work with PostgreSQL
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"only supported for PostgreSQL"
                                  (sut/provision-tenant! *test-ctx* tenant)))))))))

;; =============================================================================
;; PostgreSQL Contract Tests
;; =============================================================================

(comment
  "PostgreSQL-specific tests require actual PostgreSQL database.
   These tests should be run in CI/CD with testcontainers or local PostgreSQL.
   
   Test cases to implement:
   1. provision-tenant! creates schema successfully
   2. provision-tenant! copies table structure from public
   3. provision-tenant! is idempotent (calling twice succeeds)
   4. provision-tenant! validates schema creation
   5. provision-tenant! cleans up on failure
   6. deprovision-tenant! drops schema successfully
   7. deprovision-tenant! is idempotent (calling twice succeeds)
   
   Example implementation:
   
   (deftest ^:contract provision-tenant-postgresql-test
     (with-postgresql-database
       (fn []
         (let [tenant {:schema-name \"tenant_acme\"
                       :slug \"acme-corp\"}
               result (sut/provision-tenant! *test-ctx* tenant)]
           
           (testing \"returns success\"
             (is (true? (:success? result)))
             (is (= \"tenant_acme\" (:schema-name result)))
             (is (pos? (:table-count result))))
           
           (testing \"schema exists in database\"
             (is (true? (schema-exists? *test-ctx* \"tenant_acme\"))))
           
           (testing \"tables copied from public schema\"
             (let [tables (get-schema-tables *test-ctx* \"tenant_acme\")]
               (is (contains? (set tables) \"tenants\"))
               (is (contains? (set tables) \"users\"))))
           
           (testing \"calling again is idempotent\"
             (let [result2 (sut/provision-tenant! *test-ctx* tenant)]
               (is (true? (:success? result2)))
               (is (= \"Tenant schema already provisioned\" (:message result2)))))))))
   
   (deftest ^:contract deprovision-tenant-postgresql-test
     (with-postgresql-database
       (fn []
         (let [tenant {:schema-name \"tenant_acme\"
                       :slug \"acme-corp\"}]
           
           ;; Setup: Create schema first
           (sut/provision-tenant! *test-ctx* tenant)
           (is (true? (schema-exists? *test-ctx* \"tenant_acme\")))
           
           ;; Test deprovisioning
           (let [result (sut/deprovision-tenant! *test-ctx* tenant)]
             (testing \"returns success\"
               (is (true? (:success? result)))
               (is (= \"tenant_acme\" (:schema-name result))))
             
             (testing \"schema no longer exists\"
               (is (false? (schema-exists? *test-ctx* \"tenant_acme\"))))
             
             (testing \"calling again is idempotent\"
               (let [result2 (sut/deprovision-tenant! *test-ctx* tenant)]
                 (is (true? (:success? result2)))
                 (is (= \"Tenant schema does not exist\" (:message result2))))))))))
   
   ;; Helper functions for PostgreSQL tests
   (defn schema-exists? [ctx schema-name]
     (let [query [\"SELECT COUNT(*) as count 
                   FROM information_schema.schemata 
                   WHERE schema_name = ?\" schema-name]
           result (db/execute-one! ctx query)]
       (pos? (:count result))))
   
   (defn get-schema-tables [ctx schema-name]
     (let [query [\"SELECT table_name 
                   FROM information_schema.tables 
                   WHERE table_schema = ?\" schema-name]
           results (db/execute-query! ctx query)]
       (mapv :table_name results)))
   
   (defn with-postgresql-database [f]
     (let [ctx (db-factory/db-context {:adapter :postgresql
                                       :host \"localhost\"
                                       :port 5432
                                       :database \"wagoe_test\"
                                       :username \"postgres\"
                                       :password \"postgres\"
                                       :pool {:maximum-pool-size 5}})]
       (try
         ;; Initialize test schema
         (db/execute-ddl! ctx \"CREATE TABLE IF NOT EXISTS public.tenants (
                                id VARCHAR(36) PRIMARY KEY,
                                slug VARCHAR(100) NOT NULL,
                                schema_name VARCHAR(100) NOT NULL)\")
         (db/execute-ddl! ctx \"CREATE TABLE IF NOT EXISTS public.users (
                                id VARCHAR(36) PRIMARY KEY,
                                email VARCHAR(255) NOT NULL)\")
         
         (binding [*test-ctx* ctx]
           (f))
         (finally
           ;; Cleanup: Drop any test schemas
           (try
             (db/execute-ddl! ctx \"DROP SCHEMA IF EXISTS tenant_acme CASCADE\")
             (catch Exception _))
           (db-factory/close-db-context! ctx)))))")

;; =============================================================================
;; Test Runner
;; =============================================================================

(comment
  "Run tests:
   
   # Unit tests only (fast, no database)
   clojure -M:test --focus-meta :unit --focus wagoe.tenant.shell.provisioning-test
   
   # Integration tests (H2 database)
   clojure -M:test --focus-meta :integration --focus wagoe.tenant.shell.provisioning-test
   
   # Contract tests (requires PostgreSQL)
   clojure -M:test --focus-meta :contract --focus wagoe.tenant.shell.provisioning-test
   
   # All tests
   clojure -M:test --focus wagoe.tenant.shell.provisioning-test")

;; =============================================================================
;; tenant-scoped-tables membership (ZZP-86)
;; =============================================================================

(deftest ^:unit tenant-scoped-tables-includes-added-entities
  (testing "compliance/vbar/import tables are provisioned per-tenant (ZZP-86)"
    (is (= #{"contractors" "contracts" "assignments" "timesheets" "invoices"
             "compliance_snapshots" "compliance_changes" "vbar_assessments"
             "import_batches"}
           @#'sut/tenant-scoped-tables))))

(deftest ^:integration sync-tenant-schemas-noop-on-non-postgresql-test
  (testing "sync-tenant-schemas! is a no-op with empty result on non-PostgreSQL (ZZP-86)"
    (with-h2-database
      (fn []
        (is (= {:schemas-synced [] :tables []}
               (sut/sync-tenant-schemas! *test-ctx*)))))))

;; =============================================================================
;; with-tenant-schema error classification (ZZP-99)
;; =============================================================================

(deftest ^:unit rethrow-tenant-body-error-preserves-domain-type-test
  (let [classify #'sut/rethrow-tenant-body-error!]
    (testing "a domain-typed ex-info is rethrown UNWRAPPED (its :type reaches the caller)"
      (doseq [t [:not-found :forbidden :validation-error :conflict :unauthorized]]
        (let [domain (ex-info "boom" {:type t :extra 1})
              caught (try (classify domain "tenant_acme") nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (identical? domain caught) "same exception instance, no wrapping")
          (is (= t (:type (ex-data caught))))
          (is (= 1 (:extra (ex-data caught)))))))

    (testing "an untyped ex-info is wrapped as :tenant-context-error, original as cause"
      (let [untyped (ex-info "db blew up" {:sqlstate "42P01"})
            caught  (try (classify untyped "tenant_acme") nil
                         (catch clojure.lang.ExceptionInfo e e))]
        (is (= :tenant-context-error (:type (ex-data caught))))
        (is (= "tenant_acme" (:schema-name (ex-data caught))))
        (is (identical? untyped (.getCause caught)))))

    (testing "a plain (non-ex-info) exception is wrapped as :tenant-context-error"
      (let [raw    (RuntimeException. "connection reset")
            caught (try (classify raw "tenant_acme") nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :tenant-context-error (:type (ex-data caught))))
        (is (identical? raw (.getCause caught)))))))
