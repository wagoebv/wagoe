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

(deftest ^:integration a-reset-leaves-what-it-does-not-own
  ;; The first fix dropped every table in the schema, every tenant_* schema
  ;; and, through CASCADE, views and keys that were not the app's (BOU-585).
  (let [pg  (embedded-pg/start!)
        ctx (embedded-pg/db-context pg)
        ddl #(db/execute-ddl! ctx %)]
    (try
      (with-app ctx "dev"
        (fn [run-reset]
          (ddl "CREATE TABLE tenants (id INT, slug VARCHAR(100))")
          (ddl "INSERT INTO tenants VALUES (1, 'acme-corp')")
          (ddl "CREATE SCHEMA tenant_acme_corp")
          (ddl "CREATE TABLE tenant_acme_corp.users (id INT, auth_id INT REFERENCES public.auth_users (id))")
          (ddl "CREATE SCHEMA tenant_foreign_app")
          (ddl "CREATE TABLE tenant_foreign_app.things (id INT)")
          (ddl "CREATE SCHEMA reporting")
          (ddl "CREATE TABLE reporting.facts (id INT)")
          (ddl "INSERT INTO reporting.facts VALUES (1)")
          ;; Stands in for an extension's table, such as PostGIS's.
          (ddl "CREATE TABLE spatial_ref_sys (srid INT)")
          (testing "something the app does not own depends on its tables"
            (ddl "CREATE VIEW reporting.user_count AS SELECT count(*) AS n FROM public.auth_users")
            (ddl "CREATE TABLE reporting.logins (user_id INT REFERENCES public.auth_users (id))")
            (let [refusal (run-reset)]
              (is (= :conflict (:type refusal)))
              (is (some #(str/includes? % "reporting.user_count") (:blockers refusal)) refusal)
              (is (some #(str/includes? % "reporting.logins") (:blockers refusal)) refusal))
            (is (contains? (tables ctx) "auth_users") "nothing is dropped")
            (is (contains? (tables ctx) "app_table") "not even what a migration made"))
          (ddl "DROP VIEW reporting.user_count")
          (ddl "DROP TABLE reporting.logins")
          (testing "without it, only the app's go"
            (is (nil? (run-reset)))
            (is (= #{"app_table" "schema_migrations" "spatial_ref_sys"} (tables ctx)))
            (let [schemas (set (map (some-fn :schema-name :schema_name) (db/execute-query!
                                                                         ctx {:select [:schema_name]
                                                                              :from   [:information_schema.schemata]})))]
              (is (not (contains? schemas "tenant_acme_corp")) "a tenant in tenants")
              (is (contains? schemas "tenant_foreign_app") "a tenant_ name no tenant has")
              (is (contains? schemas "reporting"))
              (is (seq (db/execute-query! ctx {:select [:*] :from [:reporting.facts]}))
                  "a table in another schema")))))
      (finally
        (db-factory/close-db-context! ctx)
        (embedded-pg/stop! pg)))))
