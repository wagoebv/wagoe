(ns wagoe.push-migrations-test
  "Do push's migrations actually run, and do its stores then work?

   The runner discovers library migrations from a `wagoe/migration-paths/<lib>.edn`
   resource on the classpath. Push shipped three migrations and no manifest, so
   its directory was never read: `bb migrate up` reported nothing pending and
   the first query failed on a missing table (BOU-423).

   Asserted on the tables and a query rather than on the manifest file — the
   file existing is the mechanism; the store working is the promise."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set]
            [wagoe.platform.shell.database.migrations :as mig]
            [wagoe.platform.shell.database.timestamp-tz :as timestamp-tz]
            [wagoe.push.ports :as push-ports]
            [wagoe.push.shell.persistence :as push-persistence])
  (:import [java.util UUID]))

(deftest ^:integration push-migrations-are-discovered-and-they-run
  (let [dirs (mig/discover-migration-dirs)
        push-dirs (filterv #(str/includes? % "push") dirs)]

    (testing "the runner can see push's migration directory"
      (is (seq push-dirs)
          (str "no push directory among " (pr-str dirs)
               " — the manifest is missing, so the runner never reads them")))

    (testing "and applying them creates the tables its stores query"
      (let [ds (jdbc/get-datasource
                {:dbtype "h2:mem"
                 :dbname (str "push_mig_" (System/nanoTime)
                              ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL")})]
        ;; Only push's directories: the application's own migrations expect a
        ;; users table that `:wagoe/user-db-schema` creates in code, and this is
        ;; a statement about push.
        (migratus/migrate {:store         :database
                           :migration-dir push-dirs
                           :db            {:datasource ds}})
        (let [tables (into #{}
                           (map (comp str/lower-case :table_name))
                           (jdbc/execute! ds ["SELECT table_name FROM information_schema.tables
                                                 WHERE table_schema = 'PUBLIC'"]
                                          {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))]
          (is (contains? tables "push_device_tokens"))
          (is (contains? tables "push_analytics_events")))

        (testing "and the device store can query one"
          (let [store (push-persistence/->DeviceTokenStore ds)]
            (is (= [] (push-ports/get-user-devices store (UUID/randomUUID)))
                "the table exists but the store cannot read it")))

        (testing "the EDN migration ran, so its timestamps carry a zone"
          ;; The repository's first code migration: migratus resolves the
          ;; namespace named in the .edn and calls it. Asserted on the column,
          ;; not on the file, because the file existing is the mechanism
          ;; (BOU-431).
          (with-open [c (jdbc/get-connection ds)]
            (doseq [[table column] [["push_device_tokens" "created_at"]
                                    ["push_device_tokens" "last_used_at"]
                                    ["push_send_log" "created_at"]
                                    ["push_send_log" "sent_at"]
                                    ["push_analytics_events" "timestamp"]]]
              (testing (str table "." column)
                (with-open [rs (.getColumns (.getMetaData c) nil nil
                                            (str/upper-case table) (str/upper-case column))]
                  (is (.next rs) "no such column")
                  (is (timestamp-tz/zone-aware? (.getString rs "TYPE_NAME"))))))))))))
