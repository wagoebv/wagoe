(ns wagoe.tenant.shell.tenant-migrations-test
  "Integration tests for per-tenant migration fan-out against embedded PostgreSQL.

   Proves the BOU-159 acceptance: provision two tenant schemas, run a tenant
   migration, and confirm each schema gets the change with its own ledger."
  (:require [wagoe.platform.database :as db-config]
            [wagoe.tenant.shell.provisioning :as provisioning]
            [wagoe.tenant.shell.tenant-migrations :as tmig]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [support.embedded-pg :as epg]))

(def ^:private test-migration-dir "migrations-tenant-test")

(def ^:private pg-instance (atom nil))

(defn- with-pg [f]
  (let [pg (epg/start!)]
    (reset! pg-instance pg)
    (try (f) (finally (epg/stop! pg) (reset! pg-instance nil)))))

(use-fixtures :once with-pg)

(defn- ds [] (epg/datasource @pg-instance))

(defn- create-schema! [schema]
  (jdbc/execute! (ds) [(str "CREATE SCHEMA IF NOT EXISTS " schema)]))

(defn- table-in-schema? [schema table]
  (some? (jdbc/execute-one!
          (ds)
          ["SELECT 1 FROM information_schema.tables
            WHERE table_schema = ? AND table_name = ?" schema table])))

(defn- ledger-count [schema]
  (-> (jdbc/execute-one! (ds) [(str "SELECT count(*) AS c FROM " schema ".schema_migrations")])
      :c))

(deftest ^:integration migrate-all-tenants-fans-out-test
  (testing "each tenant schema independently gets the migration and its own ledger"
    (create-schema! "tenant_alpha")
    (create-schema! "tenant_beta")

    (let [result (tmig/migrate-all-tenants! (epg/db-config @pg-instance)
                                            ["tenant_alpha" "tenant_beta"]
                                            test-migration-dir)]
      (is (= #{"tenant_alpha" "tenant_beta"} (set (:schemas-migrated result))))
      (is (empty? (:errors result))))

    (testing "the tenant-scoped table exists in every tenant schema"
      (is (table-in-schema? "tenant_alpha" "widgets"))
      (is (table-in-schema? "tenant_beta" "widgets")))

    (testing "each schema keeps its own ledger (no shared public ledger)"
      (is (= 1 (ledger-count "tenant_alpha")))
      (is (= 1 (ledger-count "tenant_beta")))
      (is (not (table-in-schema? "public" "widgets"))
          "tenant migration must not leak into public"))

    (testing "re-running is idempotent (already-applied migrations are skipped)"
      (let [again (tmig/migrate-all-tenants! (epg/db-config @pg-instance)
                                             ["tenant_alpha" "tenant_beta"]
                                             test-migration-dir)]
        (is (empty? (:errors again)))
        (is (= 1 (ledger-count "tenant_alpha")))
        (is (= 1 (ledger-count "tenant_beta")))))))

(deftest ^:integration provision-tenant-runs-migrations-test
  (testing "provisioning a new tenant runs the tenant migration set into its schema"
    (with-redefs [db-config/get-active-db-config (constantly (epg/db-config @pg-instance))
                  tmig/default-tenant-migration-dir test-migration-dir]
      (let [ctx    (epg/db-context @pg-instance)
            result (provisioning/provision-tenant! ctx {:schema-name "tenant_gamma"})]
        (is (:success? result))
        (is (= "tenant_gamma" (:schema-name result)))
        (is (table-in-schema? "tenant_gamma" "widgets")
            "the tenant-scoped table from the migration set exists in the new schema")))))
