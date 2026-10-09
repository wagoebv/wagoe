(ns wagoe.tenant-jobs-test
  "A tenant job runs in its tenant's schema, with the db-context the system
   builds. It compared a :database-type that context never has, so every tenant
   job ran in the public schema (BOU-605)."
  (:require [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [support.embedded-pg :as epg]
            [wagoe.jobs.shell.tenant-context :as tenant-jobs]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.tenant.shell.provisioning :as provisioning]))

(deftest ^:integration a-tenant-job-reads-its-tenants-schema
  (let [pg  (epg/start!)
        ctx (epg/db-context pg)
        tenant-id (random-uuid)]
    (try
      (doseq [sql ["CREATE SCHEMA tenant_acme"
                   "CREATE TABLE public.notes (v TEXT)"
                   "INSERT INTO public.notes VALUES ('public')"
                   "CREATE TABLE tenant_acme.notes (v TEXT)"
                   "INSERT INTO tenant_acme.notes VALUES ('tenant')"]]
        (jdbc/execute! (:datasource ctx) [sql]))
      (with-redefs [tenant-ports/get-tenant (fn [_ id] (when (= id tenant-id)
                                                         {:id id :schema-name "tenant_acme"}))]
        (let [seen (tenant-jobs/process-tenant-job!
                    {:id (random-uuid) :job-type :read-note :args {}
                     :metadata {:tenant-id tenant-id}}
                    (fn [_args db-ctx]
                      (:v (jdbc/execute-one! (or (:tx db-ctx) (:datasource db-ctx))
                                             ["SELECT v FROM notes"]
                                             {:builder-fn rs/as-unqualified-lower-maps})))
                    ctx
                    ::tenant-service
                    (provisioning/create-tenant-schema-provider))]
          (is (= "tenant" seen))))
      (finally
        (factory/close-db-context! ctx)
        (epg/stop! pg)))))
