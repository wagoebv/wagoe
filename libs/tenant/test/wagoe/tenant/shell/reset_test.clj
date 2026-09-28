(ns wagoe.tenant.shell.reset-test
  "BOU-585: the first `db:reset` fix dropped every table in the schema, every
   tenant_* schema and, through CASCADE, what depended on them. A reset drops
   what the application owns, and nothing when something else depends on it."
  (:require [wagoe.platform.shell.database.reset :as sut]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [wagoe.tenant.core.tenant :as tenant]
            [support.embedded-pg :as embedded-pg]
            [support.reset-app :refer [tables with-app]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit tenant-schemas-are-named-as-tenant-names-them
  (doseq [slug ["acme" "acme-corp" "a-b-c"]]
    (is (= (tenant/slug->schema-name slug) (sut/slug->schema-name slug)))))

(defn- schemas [ctx]
  (set (map (some-fn :schema-name :schema_name)
            (db/execute-query! ctx {:select [:schema_name] :from [:information_schema.schemata]}))))

(defn- with-tenant-app
  "An app on a fresh database of `pg` with one tenant, tenant_acme_corp, and a
   schema of someone else's, reporting. Calls (f ctx ddl run-reset app-dir)."
  [pg f]
  (let [db   (str "reset_" (System/nanoTime))
        _    (with-open [c (.getConnection (embedded-pg/datasource pg))
                         s (.createStatement c)]
               (.execute s (str "CREATE DATABASE " db)))
        ctx  (embedded-pg/db-context pg {:name db})
        ddl  #(db/execute-ddl! ctx %)]
    (try
      (with-app ctx "dev"
        (fn [run-reset app]
          (ddl "CREATE TABLE tenants (id INT, slug VARCHAR(100))")
          (ddl "INSERT INTO tenants VALUES (1, 'acme-corp')")
          (ddl "CREATE SCHEMA tenant_acme_corp")
          (ddl "CREATE TABLE tenant_acme_corp.users (id INT PRIMARY KEY, auth_id INT REFERENCES public.auth_users (id))")
          (ddl "CREATE SCHEMA reporting")
          (f ctx ddl run-reset app)))
      (finally (db-factory/close-db-context! ctx)))))

(def ^:private foreign-dependents
  "Objects in someone else's schema that depend on what a reset would drop.
   DROP SCHEMA … CASCADE took each of them along (BOU-585 review)."
  {"a materialised view on a tenant table"
   ["CREATE MATERIALIZED VIEW reporting.mv AS SELECT id FROM tenant_acme_corp.users"]

   "a column typed with a tenant enum"
   ["CREATE TYPE tenant_acme_corp.mood AS ENUM ('ok')"
    "CREATE TABLE reporting.moods (m tenant_acme_corp.mood)"
    "INSERT INTO reporting.moods VALUES ('ok')"]

   "a BEGIN ATOMIC function on a tenant table"
   ["CREATE FUNCTION reporting.user_count() RETURNS bigint LANGUAGE sql BEGIN ATOMIC SELECT count(*) FROM tenant_acme_corp.users; END"]

   "a default drawing from a tenant sequence"
   ["CREATE SEQUENCE tenant_acme_corp.ids"
    "CREATE TABLE reporting.numbered (id bigint DEFAULT nextval('tenant_acme_corp.ids'))"]

   "a trigger calling a tenant function"
   ["CREATE FUNCTION tenant_acme_corp.touch() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RETURN NEW; END$$"
    "CREATE TABLE reporting.touched (id INT)"
    "CREATE TRIGGER touch BEFORE INSERT ON reporting.touched FOR EACH ROW EXECUTE FUNCTION tenant_acme_corp.touch()"]

   "a row-level security policy on a tenant table"
   ["CREATE TABLE reporting.secured (id INT)"
    "ALTER TABLE reporting.secured ENABLE ROW LEVEL SECURITY"
    "CREATE POLICY only_users ON reporting.secured USING (id IN (SELECT id FROM tenant_acme_corp.users))"]

   "a view over a tenant view"
   ["CREATE VIEW tenant_acme_corp.active AS SELECT id FROM tenant_acme_corp.users"
    "CREATE VIEW reporting.active AS SELECT id FROM tenant_acme_corp.active"]

   "a materialised view on auth_users"
   ["CREATE MATERIALIZED VIEW reporting.user_ids AS SELECT id FROM public.auth_users"]

   "a view on auth_users"
   ["CREATE VIEW reporting.user_count AS SELECT count(*) AS n FROM public.auth_users"]

   "a foreign key to auth_users"
   ["CREATE TABLE reporting.logins (user_id INT REFERENCES public.auth_users (id))"]})

(deftest ^:integration a-foreign-dependent-refuses-the-reset
  (let [pg (embedded-pg/start!)]
    (try
      (doseq [[label statements] foreign-dependents]
        (testing label
          (with-tenant-app pg
            (fn [ctx ddl run-reset _]
              (run! ddl statements)
              (let [refusal (run-reset)]
                (is (= :conflict (:type refusal)) (pr-str refusal))
                (is (some #(str/includes? % "reporting") (:blockers refusal)) (pr-str refusal)))
              (is (contains? (schemas ctx) "tenant_acme_corp") "nothing is dropped")
              (is (contains? (tables ctx) "auth_users"))
              (is (contains? (tables ctx) "app_table") "not even what a migration made")))))
      (finally (embedded-pg/stop! pg)))))

(deftest ^:integration a-reset-leaves-what-it-does-not-own
  (let [pg (embedded-pg/start!)]
    (try
      (with-tenant-app pg
        (fn [ctx ddl run-reset _]
          (ddl "CREATE SCHEMA tenant_foreign_app")
          (ddl "CREATE TABLE tenant_foreign_app.things (id INT)")
          (ddl "CREATE TABLE reporting.facts (id INT)")
          (ddl "INSERT INTO reporting.facts VALUES (1)")
          ;; Stands in for an extension's table, such as PostGIS's.
          (ddl "CREATE TABLE spatial_ref_sys (srid INT)")
          (is (nil? (run-reset)))
          (is (= #{"app_table" "schema_migrations" "spatial_ref_sys"} (tables ctx)))
          (is (not (contains? (schemas ctx) "tenant_acme_corp")) "a tenant in tenants")
          (is (contains? (schemas ctx) "tenant_foreign_app") "a tenant_ name no tenant has")
          (is (seq (db/execute-query! ctx {:select [:*] :from [:reporting.facts]}))
              "a table in another schema")))
      (finally (embedded-pg/stop! pg)))))

(deftest ^:integration a-failed-drop-leaves-the-database-as-it-was
  ;; A drop that failed halfway left some tables gone and others not, and said
  ;; only what the database said (BOU-585 review).
  (let [pg (embedded-pg/start!)]
    (try
      (with-tenant-app pg
        (fn [ctx _ run-reset _]
          (let [f sut/drop-table-sql]
            (with-redefs [sut/drop-table-sql (fn [q t] (if (= "auth_users" t) "DROP TABLE no_such_table" (f q t)))]
              (let [failure (run-reset)]
                (is (= :reset-failed (:type failure)) (pr-str failure))
                (is (= ["roll back every migration"] (:done failure)))
                (is (str/includes? (:recovery failure) "bb migrate up")))))
          (is (contains? (schemas ctx) "tenant_acme_corp") "the tenant drop was rolled back")
          (is (contains? (tables ctx) "user_sessions") "and so was every table drop")
          (is (contains? (tables ctx) "auth_users"))))
      (finally (embedded-pg/stop! pg)))))
