(ns wagoe.fresh-migrate-test
  "`migrate up` on a database that has never booted, and on one that has.

   The first migration indexed `users`, which only boot creates, so a fresh
   database failed with \"Table users doesn't exist\" on every engine
   (BOU-576). Both orders are run against the full set of migrations the
   runner discovers, followed by the user and tenant boot DDL."
  (:require [clojure.test :refer [deftest is testing]]
            [migratus.core :as migratus]
            [support.embedded-pg :as epg]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.platform.shell.database.migrations :as mig]
            [wagoe.tenant.shell.persistence :as tenant-persistence]
            [wagoe.user.shell.persistence :as user-persistence]))

(defn- backends
  "[label open] — `open` returns [ctx close!] on a fresh, empty database."
  []
  [["h2" (fn []
           (let [ctx (factory/db-context
                      (factory/h2-config (str "mem:fresh_mig_" (System/nanoTime)
                                              ";DB_CLOSE_DELAY=-1")))]
             [ctx #(factory/close-db-context! ctx)]))]
   ["sqlite" (fn []
               (let [path (str (System/getProperty "java.io.tmpdir")
                               "/fresh-mig-" (System/nanoTime) ".db")
                     ctx (factory/db-context (factory/sqlite-config path))]
                 [ctx (fn []
                        (factory/close-db-context! ctx)
                        (.delete (java.io.File. path)))]))]
   ["postgresql" (fn []
                   (let [pg (epg/start!)
                         ctx (epg/db-context pg)]
                     [ctx (fn []
                            (factory/close-db-context! ctx)
                            (epg/stop! pg))]))]])

(defn- boot-ddl!
  "What `:wagoe/user-db-schema` and `:wagoe/tenant-db-schema` run at boot."
  [ctx]
  (user-persistence/initialize-user-schema! ctx)
  (tenant-persistence/initialize-tenant-schema! ctx))

(defn- pending [ctx]
  (migratus/pending-list (mig/migratus-config (:datasource ctx)
                                              (mig/discover-migration-dirs))))

(defn- attempt
  "nil when `f` succeeds, else the message of what it threw."
  [f]
  (try (f) nil
       (catch Throwable t
         (str (ex-message t) " " (some-> (ex-cause t) ex-message)))))

(deftest ^:integration migrate-before-the-first-boot
  (doseq [[label open] (backends)]
    (testing label
      (let [[ctx close!] (open)]
        (try
          (is (nil? (attempt #(mig/migrate-datasource! (:datasource ctx))))
              "migrate up on a database that never booted")
          (is (empty? (pending ctx)))
          (is (nil? (attempt #(boot-ddl! ctx)))
              "and the application then boots on it")
          (is (db/table-exists? ctx :users))
          (is (db/table-exists? ctx :tenants))
          (finally (close!)))))))

(deftest ^:integration migrate-after-boot
  (doseq [[label open] (backends)]
    (testing label
      (let [[ctx close!] (open)]
        (try
          (boot-ddl! ctx)
          (is (nil? (attempt #(mig/migrate-datasource! (:datasource ctx))))
              "migrate up on a database boot already created")
          (is (empty? (pending ctx)))
          (testing "and a second boot and migrate change nothing"
            (is (nil? (attempt #(boot-ddl! ctx))))
            (is (nil? (attempt #(mig/migrate-datasource! (:datasource ctx))))))
          (finally (close!)))))))
