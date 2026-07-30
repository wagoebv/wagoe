(ns wagoe.test-support.shell.reset-test
  "Contract test for the H2 truncate + seed helpers used by the Playwright
   e2e suite.

   Uses a single HikariCP datasource in H2 PostgreSQL compatibility mode and
   lets the real production `initialize-*-schema!` helpers create the tables,
   so any schema drift between the seed spec and the production write path
   shows up as a test failure."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.test-support.shell.reset :as sut]
            [wagoe.platform.shell.adapters.database.h2.core :as h2]
            [wagoe.observability.errors.shell.adapters.no-op :as noop-errors]
            [wagoe.observability.logging.shell.adapters.no-op :as noop-logging]
            [wagoe.observability.metrics.shell.adapters.no-op :as noop-metrics]
            [wagoe.tenant.shell.persistence :as tenant-persistence]
            [wagoe.tenant.shell.service :as tenant-service]
            [wagoe.user.shell.persistence :as user-persistence]
            [wagoe.user.shell.service :as user-service]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.time Instant)
           (java.sql Timestamp)
           (java.util UUID)))

(def ^:dynamic *datasource* nil)

(defn- with-h2
  "Creates a fresh HikariCP H2 datasource in PostgreSQL mode, runs the real
   production `initialize-user-schema!` and `initialize-tenant-schema!` so
   both tests exercise the actual tables, binds `*datasource*`, and closes
   the pool in a `finally`."
  [f]
  (let [^HikariDataSource ds
        (connection/->pool
         HikariDataSource
         {:jdbcUrl (str "jdbc:h2:mem:test_support_reset_" (UUID/randomUUID)
                        ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                        ";DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
          :username "sa"
          :password ""})]
    (try
      (let [db-ctx {:datasource ds :adapter (h2/new-adapter)}]
        (user-persistence/initialize-user-schema! db-ctx)
        (tenant-persistence/initialize-tenant-schema! db-ctx))
      (binding [*datasource* ds]
        (f))
      (finally
        (.close ds)))))

(use-fixtures :each with-h2)

(defn- count-rows [ds table]
  (let [row (jdbc/execute-one! ds [(str "SELECT COUNT(*) AS c FROM " table)])]
    ;; H2 returns a qualified keyword like :PUBLIC/c (case depends on settings).
    ;; Grab the single value regardless of key name.
    (long (first (vals row)))))

(defn- build-services [ds]
  (let [db-ctx {:datasource ds :adapter (h2/new-adapter)}
        user-repo (user-persistence/create-user-repository db-ctx)
        session-repo (user-persistence/create-session-repository db-ctx)
        audit-repo (user-persistence/create-audit-repository db-ctx
                                                             {:default-limit 20 :max-limit 100})
        user-svc (user-service/create-user-service user-repo session-repo audit-repo {} nil)
        logger (noop-logging/create-logging-component {})
        metrics (noop-metrics/create-metrics-emitter {})
        errors (noop-errors/create-error-reporting-component)
        tenant-repo (tenant-persistence/create-tenant-repository db-ctx logger errors)
        tenant-svc (tenant-service/create-tenant-service tenant-repo {} logger metrics errors)]
    {:user-service user-svc
     :tenant-service tenant-svc}))

(deftest ^:integration truncate-all!-removes-all-rows-test
  (testing "truncate-all! empties a populated tenants table"
    (let [now (Timestamp/from (Instant/now))]
      (jdbc/execute! *datasource*
                     ["INSERT INTO tenants
                         (id, slug, schema_name, name, status, created_at)
                       VALUES (?, ?, ?, ?, ?, ?)"
                      (str (UUID/randomUUID)) "acme" "tenant_acme" "Acme" "active" now]))
    (is (= 1 (count-rows *datasource* "tenants"))
        "precondition: row was inserted")

    (sut/truncate-all! *datasource*)

    (is (zero? (count-rows *datasource* "tenants"))
        "truncate-all! removed the row from tenants")))

(deftest ^:integration seed-baseline!-creates-entities-test
  (testing "seed-baseline! persists baseline entities via production services"
    (let [services (build-services *datasource*)
          _ (sut/truncate-all! *datasource*)
          {:keys [tenant admin user] :as result} (sut/seed-baseline! services)]
      (testing "tenant created with an id"
        (is (some? (:id tenant)) "tenant has an :id")
        (is (= "acme" (:slug tenant))))
      (testing "admin user created with plaintext password re-attached"
        (is (= "admin@acme.test" (:email admin)))
        (is (= :admin (:role admin)))
        (is (string? (:password admin)) ":password re-attached for test consumers")
        (is (not (contains? admin :password-hash))
            ":password-hash must not be exposed by production register-user"))
      (testing "regular user created with plaintext password re-attached"
        (is (= "user@acme.test" (:email user)))
        (is (= :user (:role user)))
        (is (string? (:password user)) ":password re-attached for test consumers")
        (is (not (contains? user :password-hash))
            ":password-hash must not be exposed by production register-user"))
      (testing "exactly two user rows were written by the production service"
        (is (= 2 (count-rows *datasource* "users"))))
      (testing "return map has tenant/admin/user keys"
        (is (= #{:tenant :admin :user} (set (keys result))))))))
