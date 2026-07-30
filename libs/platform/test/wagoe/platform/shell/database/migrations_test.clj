(ns wagoe.platform.shell.database.migrations-test
  (:require [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.platform.shell.adapters.database.config :as db-config]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [migratus.core :as migratus]))

(defn- with-temp-dir [f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "wagoe-migrations-test"
                                                              (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest ^:unit nested-sql-subdirs-detects-sql-in-subdirectories
  (testing "flags subdirectory that contains SQL files, not direct children"
    (with-temp-dir
      (fn [root]
        (let [tenant-dir (io/file root "tenant")]
          (.mkdirs tenant-dir)
          (spit (io/file root "001.up.sql") "")
          (spit (io/file tenant-dir "001-tenants.up.sql") "")
          (let [dir-path (str (.getCanonicalPath root) "/")
                subdirs  (#'migrations/nested-sql-subdirs dir-path)]
            (is (= 1 (count subdirs)))
            (is (.endsWith (first subdirs) "tenant/")))))))

  (testing "returns empty when no SQL files are nested"
    (with-temp-dir
      (fn [root]
        (spit (io/file root "001.up.sql") "")
        (is (empty? (#'migrations/nested-sql-subdirs
                     (str (.getCanonicalPath root) "/")))))))

  (testing "returns empty for non-existent directory"
    (is (nil? (#'migrations/nested-sql-subdirs "nonexistent-migrations-xyz/"))))

  (testing "deeply nested file uses leaf dir as display path"
    (with-temp-dir
      (fn [root]
        (let [v2-dir (io/file root "tenant" "v2")]
          (.mkdirs v2-dir)
          (spit (io/file v2-dir "001.up.sql") "")
          (let [dir-path (str (.getCanonicalPath root) "/")
                subdirs  (#'migrations/nested-sql-subdirs dir-path)]
            (is (= 1 (count subdirs)))
            (is (.endsWith (first subdirs) "tenant/v2/")))))))

  (testing "flags multiple distinct subdirectories"
    (with-temp-dir
      (fn [root]
        (let [tenant-dir (io/file root "tenant")
              archive-dir (io/file root "archive")]
          (.mkdirs tenant-dir)
          (.mkdirs archive-dir)
          (spit (io/file tenant-dir "001.up.sql") "")
          (spit (io/file archive-dir "001.up.sql") "")
          (let [dir-path (str (.getCanonicalPath root) "/")
                subdirs  (set (#'migrations/nested-sql-subdirs dir-path))]
            (is (= 2 (count subdirs)))
            (is (some #(.endsWith % "tenant/") subdirs))
            (is (some #(.endsWith % "archive/") subdirs))))))))

(deftest ^:unit discover-migration-dirs-includes-library-manifests
  (testing "root migrations and library manifests are merged and de-duplicated"
    (with-temp-dir
      (fn [dir]
        (let [manifest-a (doto (io/file dir "manifest-a.edn")
                           (spit "{:paths [\"wagoe/geo/migrations/\" \"migrations/\"]}"))
              manifest-b (doto (io/file dir "manifest-b.edn")
                           (spit "[\"wagoe/search/migrations/\"]"))]
          (with-redefs [migrations/manifest-urls (fn []
                                                   [(io/as-url manifest-a)
                                                    (io/as-url manifest-b)])]
            (is (= ["migrations/"
                    "wagoe/geo/migrations/"
                    "wagoe/search/migrations/"]
                   (migrations/discover-migration-dirs)))))))))

(deftest ^:unit discover-migration-dirs-rejects-invalid-manifests
  (testing "invalid manifest shapes fail fast with a clear error"
    (with-temp-dir
      (fn [dir]
        (let [bad-manifest (doto (io/file dir "bad-manifest.edn")
                             (spit "\"wagoe/geo/migrations/\""))]
          (with-redefs [migrations/manifest-urls (fn [] [(io/as-url bad-manifest)])]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"Invalid migration manifest"
                 (migrations/discover-migration-dirs)))))))))

(deftest ^:unit create-migratus-config-includes-discovered-dirs-and-datasource
  (testing "migratus config keeps datasource and merged migration directories"
    (with-redefs [migrations/discover-migration-dirs (fn [] ["migrations/" "wagoe/geo/migrations/"])]
      (is (= {:store :database
              :migration-dir ["migrations/" "wagoe/geo/migrations/"]
              :init-script nil
              :init-in-transaction? false
              :migration-table-name "schema_migrations"
              :db {:datasource ::datasource}}
             (migrations/create-migratus-config {:datasource ::datasource}))))))

(deftest ^:unit get-migration-config-wraps-config-loading-errors
  (testing "configuration failures are rethrown with migration context"
    (with-redefs [db-config/get-active-db-config
                  (fn []
                    (throw (ex-info "db config boom" {:type :config-error})))]
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (migrations/get-migration-config)))]
        (is (= "Migration configuration failed" (ex-message ex)))
        (is (= "db config boom" (:error (ex-data ex))))))))

(deftest ^:unit migration-operations-delegate-to-migratus
  (testing "successful operations use the resolved migratus config"
    (let [config {:migration-dir ["migrations/"]}
          calls (atom [])]
      (with-redefs [migrations/get-migration-config (fn [] config)
                    migratus/migrate (fn [arg] (swap! calls conj [:migrate arg]))
                    migratus/rollback (fn [arg] (swap! calls conj [:rollback arg]))
                    migratus/rollback-until-just-after (fn [arg migration-id]
                                                         (swap! calls conj [:rollback-until arg migration-id]))
                    migratus/completed-list (fn [arg]
                                              (swap! calls conj [:completed-list arg])
                                              ["20260324090101-bootstrap"])
                    migratus/pending-list (fn [arg]
                                            (swap! calls conj [:pending-list arg])
                                            ["20260325010101-example"])
                    migratus/create (fn [arg name]
                                      (swap! calls conj [:create arg name]))
                    migratus/reset (fn [arg] (swap! calls conj [:reset arg]))
                    migratus/init (fn [arg] (swap! calls conj [:init arg]))]
        (is (nil? (migrations/migrate)))
        (is (nil? (migrations/rollback)))
        (is (nil? (migrations/rollback-until-just-after 20260325010101)))
        (is (= ["20260325010101-example"] (migrations/pending-list)))
        (is (= {:applied ["20260324090101-bootstrap"]
                :total-applied 1
                :pending ["20260325010101-example"]
                :total-pending 1}
               (migrations/migration-status)))
        (is (= {:success true
                :message "Created migration files for: add-users"
                :directory "migrations/"}
               (migrations/create-migration "add-users")))
        (is (nil? (migrations/reset)))
        (is (nil? (migrations/init)))
        (is (= [[:migrate config]
                [:rollback config]
                [:rollback-until config 20260325010101]
                [:pending-list config]
                [:completed-list config]
                [:pending-list config]
                [:create config "add-users"]
                [:reset config]
                [:init config]]
               @calls))))))

(deftest ^:unit migration-operations-wrap-failures-consistently
  (testing "migration operations keep useful ex-data on failure"
    (let [config {:migration-dir ["migrations/"]}]
      (with-redefs [migrations/get-migration-config (fn [] config)
                    migratus/migrate (fn [_] (throw (ex-info "migrate boom" {})))
                    migratus/rollback (fn [_] (throw (ex-info "rollback last boom" {})))
                    migratus/rollback-until-just-after (fn [_ _] (throw (ex-info "rollback boom" {})))
                    migratus/create (fn [_ _] (throw (ex-info "create boom" {})))
                    migratus/reset (fn [_] (throw (ex-info "reset boom" {})))
                    migratus/init (fn [_] (throw (ex-info "init boom" {})))
                    migratus/completed-list (fn [_] (throw (ex-info "completed boom" {})))
                    migratus/pending-list (fn [_] (throw (ex-info "pending boom" {})))]
        (let [migrate-ex (is (thrown? clojure.lang.ExceptionInfo
                                      (migrations/migrate)))
              rollback-last-ex (is (thrown? clojure.lang.ExceptionInfo
                                            (migrations/rollback)))
              rollback-ex (is (thrown? clojure.lang.ExceptionInfo
                                       (migrations/rollback-until-just-after 20260325020202)))
              create-ex (is (thrown? clojure.lang.ExceptionInfo
                                     (migrations/create-migration "broken")))
              reset-ex (is (thrown? clojure.lang.ExceptionInfo
                                    (migrations/reset)))
              init-ex (is (thrown? clojure.lang.ExceptionInfo
                                   (migrations/init)))]
          (is (= "Migration failed" (ex-message migrate-ex)))
          (is (= "migrate boom" (:error (ex-data migrate-ex))))
          (is (= "Rollback failed" (ex-message rollback-last-ex)))
          (is (= "rollback last boom" (:error (ex-data rollback-last-ex))))
          (is (= "Rollback to migration failed" (ex-message rollback-ex)))
          (is (= 20260325020202 (:migration-id (ex-data rollback-ex))))
          (is (= "Migration creation failed" (ex-message create-ex)))
          (is (= "broken" (:name (ex-data create-ex))))
          (is (= "Database reset failed" (ex-message reset-ex)))
          (is (= "Migration init failed" (ex-message init-ex)))
          (is (= [] (migrations/pending-list)))
          (is (= {:applied []
                  :total-applied 0
                  :pending []
                  :total-pending 0
                  :error "completed boom"}
                 (migrations/migration-status))))))))

(deftest ^:unit print-status-and-auto-migrate-cover-human-facing-branches
  (testing "print-status renders applied, pending, and error sections"
    (with-redefs [migrations/migration-status (fn []
                                                {:applied ["20260324090101-bootstrap"]
                                                 :total-applied 1
                                                 :pending ["20260325010101-example"]
                                                 :total-pending 1
                                                 :error "status boom"})]
      (let [output (with-out-str (migrations/print-status))]
        (is (.contains output "Applied migrations: 1"))
        (is (.contains output "Pending migrations: 1"))
        (is (.contains output "20260324090101-bootstrap"))
        (is (.contains output "20260325010101-example"))
        (is (.contains output "status boom")))))

  (testing "auto-migrate runs only when pending migrations exist and swallows failures"
    (let [calls (atom [])]
      (with-redefs [migrations/migration-status (fn []
                                                  {:pending ["20260325010101-example"]
                                                   :total-pending 1})
                    migrations/migrate (fn []
                                         (swap! calls conj :migrate))]
        (is (true? (migrations/auto-migrate)))
        (is (= [:migrate] @calls))))

    (with-redefs [migrations/migration-status (fn []
                                                {:pending []
                                                 :total-pending 0})
                  migrations/migrate (fn []
                                       (throw (ex-info "should not run" {})))]
      (is (true? (migrations/auto-migrate))))

    (with-redefs [migrations/migration-status (fn []
                                                (throw (ex-info "auto boom" {})))]
      (is (false? (migrations/auto-migrate))))))
