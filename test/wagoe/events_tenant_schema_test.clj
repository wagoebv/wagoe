(ns wagoe.events-tenant-schema-test
  "A handler subscribed at boot writes into its event's tenant schema, through
   tenant's real `:wagoe/in-tenant` (BOU-985)."
  (:require [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [support.embedded-pg :as epg]
            [wagoe.events.ports :as events]
            [wagoe.events.shell.adapters.in-memory :as in-memory]
            [wagoe.events.shell.publisher :as publisher]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.tenant.shell.module-wiring]))

(defn- notes [ctx schema]
  (mapv :body (db/execute-query! ctx [(str "SELECT body FROM " schema ".notes ORDER BY body")])))

(deftest ^:integration ^:security a-boot-handler-writes-into-its-events-tenant-schema
  ;; It wrote into public: the handler ran with no tenant and no schema
  (let [pg  (epg/start!)
        ctx (epg/db-context pg)]
    (try
      (doseq [s ["public" "tenant_a"]]
        (db/execute-ddl! ctx (str "CREATE SCHEMA IF NOT EXISTS " s))
        (db/execute-ddl! ctx (str "CREATE TABLE " s ".notes (body VARCHAR(20))")))
      (let [service #_{:clj-kondo/ignore [:missing-protocol-method]}
            (reify tenant-ports/ITenantService
              (get-tenant [_ id] (when (= "a" id) {:id id :schema-name "tenant_a"})))
            runner  (ig/init-key :wagoe/in-tenant {:tenant-service service :db-context ctx})
            bus     (in-memory/create-in-memory-bus {:tenant-runner runner})]
        (try
          (events/subscribe! bus :notes #(db/execute-update! ctx {:insert-into :notes
                                                                  :values [{:body (:body (:payload %))}]}))
          (publisher/emit! bus :notes :note/added :test {:body "in-a"} {:tenant-id "a"})
          (publisher/emit! bus :notes :note/added :test {:body "system"} {:tenant-id nil})
          (in-memory/drain! bus :notes)
          (is (= ["in-a"] (notes ctx "tenant_a")))
          (is (= ["system"] (notes ctx "public")) "a system event stays in public")
          (finally (in-memory/stop! bus))))
      (finally
        (db-factory/close-db-context! ctx)
        (epg/stop! pg)))))
