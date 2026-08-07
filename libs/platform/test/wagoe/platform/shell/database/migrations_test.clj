(ns wagoe.platform.shell.database.migrations-test
  (:require [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.platform.shell.adapters.database.config :as db-config]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [migratus.core :as migratus]
            [migratus.utils :as migratus-utils]
            [migratus.protocols]))

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
    ;; shadowed-migration-dirs is stubbed because it reads the working
    ;; directory, and this test is about error wrapping. Without the stub it
    ;; asserts on the state of whatever tree the suite runs in: a stray
    ;; migrations/ in the repo root — a scaffolder run from the wrong directory
    ;; leaves one — makes the conflict guard fire first and this fail with a
    ;; message about migration directories. That happened twice, and cost more
    ;; to diagnose than the stub costs to keep.
    (with-redefs [migrations/shadowed-migration-dirs (fn ([] nil) ([_ _] nil))
                  db-config/get-active-db-config
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
        ;; resource-dir-wins? is pinned false: this repository keeps its
        ;; migrations under resources/, so without it :directory reports that
        ;; instead — correctly, but it would make this assertion depend on the
        ;; layout of whatever tree the suite happens to run in.
        (is (= {:success true
                :message "Created migration files for: add-users"
                :directory "migrations/"}
               (with-redefs [migrations/resolved-migration-dir (fn [& _] (io/file migrations/project-migration-dir))]
                 (migrations/create-migration "add-users"))))
        (is (nil? (migrations/reset)))
        (is (nil? (migrations/init)))
        ;; Every read operation gets the discovered config verbatim. `create` is
        ;; the exception: migratus/create casts :migration-dir to String, so it
        ;; receives the narrowed config instead.
        ;;
        ;; This assertion previously expected `[:create config …]` — the vector
        ;; — so it pinned the ClassCastException in place as though it were the
        ;; intended behaviour (BOU-271). A test can hold a bug still as firmly
        ;; as it can catch one.
        (is (= [[:migrate config]
                [:rollback config]
                [:rollback-until config 20260325010101]
                [:pending-list config]
                [:completed-list config]
                [:pending-list config]
                [:create (migrations/create-config config) "add-users"]
                [:reset config]
                [:init config]]
               @calls))
        (is (string? (:migration-dir (second (first (filter #(= :create (first %)) @calls)))))
            "create must receive a directory string, not the discovered vector")))))

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

;; =============================================================================
;; create-config — the shape migratus/create actually receives (BOU-271)
;; =============================================================================

;; `bb migrate create <name>` threw for every user who followed `bb migrate
;; --help`:
;;
;;   java.lang.ClassCastException: class clojure.lang.PersistentVector cannot be
;;   cast to class java.lang.String
;;
;; get-migration-config sets :migration-dir to the discovered *vector* of every
;; migration directory on the classpath. up, status and rollback accept that;
;; migratus/create casts it to String. So the defect was in the value handed to
;; one call site, not in building the config — and a test that only exercised
;; get-migration-config would pass while create stayed broken, which is what
;; happened.

(deftest ^:unit create-config-gives-migratus-a-single-directory-string
  (testing "the vector the read config carries is narrowed to one string"
    (let [read-config {:store :database
                       :migration-dir ["migrations/" "wagoe/user/migrations/"]
                       :db {:datasource ::fake}}
          created     (migrations/create-config read-config)]
      (is (string? (:migration-dir created))
          "migratus/create casts :migration-dir to String — a vector throws")
      (is (= migrations/project-migration-dir (:migration-dir created))
          "new migrations belong in the project, not in a library directory")))

  (testing "everything else is carried through untouched"
    (let [read-config {:store :database
                       :migration-dir ["migrations/"]
                       :migration-table-name "schema_migrations"
                       :db {:datasource ::fake}}
          created     (migrations/create-config read-config)]
      (is (= (dissoc read-config :migration-dir)
             (dissoc created :migration-dir))
          "create-config must only narrow the directory, not reshape the config")))

  (testing "the project directory does not depend on discovery order"
    ;; A dependency contributing a migration directory must not change where a
    ;; user's own migration is written.
    (is (= migrations/project-migration-dir
           (:migration-dir (migrations/create-config
                            {:migration-dir ["some/library/migrations/" "migrations/"]}))))))

;; BOU-274: migrations in two directories, only one of them read.
;;
;; `:migration-dir` is a name, not a path, and both reading and creating resolve
;; it through the classpath first, where `resources/` is a root. So whenever
;; `resources/migrations` exists it captures the name, and anything under
;; `migrations/` goes nowhere — measured in a generated project, `migrate up`
;; exited 0, `status` reported no pending migrations, and the table was never
;; created.
;;
;; Existence is the trigger, not contents. An earlier version of this fix keyed
;; on SQL files and an empty `resources/migrations` walked straight through it,
;; still shadowing a populated `migrations/`. The same empty directory also
;; captured `bb migrate create`, which wrote there while printing "Migration
;; files created in: migrations/".
;;
;; Nothing failed, so no test could have caught this by asserting on a return
;; value. These assert the two halves of the fix instead: creation refuses to
;; add to a split, and a split that already exists stops the run.

(defn- touch-migration! [dir filename]
  (.mkdirs (io/file dir))
  (spit (io/file dir filename) "SELECT 1;"))

(deftest ^:unit shadowed-migration-dirs-detects-the-split
  ;; The second argument is what migratus resolved the name to, not a directory
  ;; to go looking in. Earlier versions of this guard took a candidate path and
  ;; decided for themselves whether it won, and were wrong three times: on an
  ;; empty directory, on nested files, and on a jar.
  (testing "a directory that is not the project one captures everything"
    (with-temp-dir
      (fn [root]
        (let [project  (io/file root "migrations")
              resource (io/file root "resources/migrations")]
          (touch-migration! project "20260101000000-in-project.up.sql")
          (touch-migration! resource "20260202000000-in-resources.up.sql")
          (let [conflict (migrations/shadowed-migration-dirs project resource)]
            (is (some? conflict))
            (is (= ["20260101000000-in-project.up.sql"] (:root conflict)))
            (is (= ["20260202000000-in-resources.up.sql"] (:resources conflict))
                "naming what won lets the user see which set is live")
            (is (re-find #"resources" (:read-from conflict))))))))

  (testing "resolving to the project directory is the safe case"
    (with-temp-dir
      (fn [root]
        (let [project (io/file root "migrations")]
          (touch-migration! project "20260101000000-in-project.up.sql")
          (is (nil? (migrations/shadowed-migration-dirs project project))
              "the directory being read is the one holding the migrations")))))

  (testing "resolving to nothing is the safe case"
    ;; find-migration-dir returns nil when no candidate exists at all.
    (with-temp-dir
      (fn [root]
        (let [project (io/file root "migrations")]
          (touch-migration! project "20260101000000-in-project.up.sql")
          (is (nil? (migrations/shadowed-migration-dirs project nil)))))))

  (testing "an EMPTY winning directory still shadows the project directory"
    ;; The case a content-based check missed. Measured end to end: with this
    ;; layout `migrate up` exited 0 and the project migration's table was never
    ;; created, because the empty directory is what the name resolves to.
    (with-temp-dir
      (fn [root]
        (let [project  (io/file root "migrations")
              resource (io/file root "resources/migrations")]
          (touch-migration! project "20260101000000-in-project.up.sql")
          (.mkdirs resource)
          (let [conflict (migrations/shadowed-migration-dirs project resource)]
            (is (some? conflict)
                "an empty directory captures the name just as a full one does")
            (is (= ["20260101000000-in-project.up.sql"] (:root conflict)))
            (is (= [] (:resources conflict))
                "nothing to list on the winning side, which is the whole problem"))))))

  (testing "a jar on the classpath shadows the project directory"
    ;; Measured: with a jar containing migrations/ on the classpath and no
    ;; resources/migrations on disk, migratus/find-migration-dir returned the
    ;; JarFile — so an on-disk migrations/ was skipped while a check that looked
    ;; only for a resources/migrations directory saw nothing wrong. This is the
    ;; uberjar case: the code is packaged, the migrations directory is not.
    (with-temp-dir
      (fn [root]
        (let [project (io/file root "migrations")
              jar     (io/file root "app.jar")]
          (touch-migration! project "20260101000000-in-project.up.sql")
          (with-open [out (java.util.jar.JarOutputStream.
                           (io/output-stream jar))]
            (.putNextEntry out (java.util.jar.JarEntry. "migrations/"))
            (.closeEntry out))
          (with-open [jf (java.util.jar.JarFile. jar)]
            (let [conflict (migrations/shadowed-migration-dirs project jf)]
              (is (some? conflict) "a jar captures the name like any other source")
              (is (= ["20260101000000-in-project.up.sql"] (:root conflict)))
              (is (re-find #"app\.jar" (:read-from conflict))
                  "the user cannot act on this without being told it is the jar")
              (is (nil? (:resources conflict))
                  "there is no directory to list files from")))))))

  (testing "nested SQL under migrations/ is shadowed too, and reported by path"
    ;; migratus reads migration directories with file-seq, so a migration in a
    ;; subdirectory is applied like any other — measured: migrations/tenant/…
    ;; created its table with no resources/ present, and silently did not with
    ;; an empty resources/migrations there. A .listFiles version of this guard
    ;; returned nil for exactly that layout.
    (with-temp-dir
      (fn [root]
        (let [project  (io/file root "migrations")
              resource (io/file root "resources/migrations")
              nested   (io/file project "tenant" "v2")]
          (.mkdirs nested)
          (spit (io/file nested "20260101000000-nested.up.sql") "SELECT 1;")
          (.mkdirs resource)
          (let [conflict (migrations/shadowed-migration-dirs project resource)]
            (is (some? conflict) "a nested migration is still a migration")
            (is (= [(str "tenant" java.io.File/separator "v2"
                         java.io.File/separator "20260101000000-nested.up.sql")]
                   (:root conflict))
                "reported by path — the bare filename would not locate it"))))))

  (testing "EDN migrations are shadowed like SQL ones"
    ;; migratus reads both: proto/get-all-supported-extensions returns
    ;; ["sql" "edn"]. A hardcoded ".sql" filter found no files here, so the
    ;; guard stayed silent and the EDN migration was skipped anyway — the exact
    ;; failure this check exists to stop, in the one file type it did not cover.
    (with-temp-dir
      (fn [root]
        (let [project  (io/file root "migrations")
              resource (io/file root "resources/migrations")]
          (touch-migration! project "20260101000000-in-project.edn")
          (.mkdirs resource)
          (let [conflict (migrations/shadowed-migration-dirs project resource)]
            (is (some? conflict) "an EDN migration is a migration")
            (is (= ["20260101000000-in-project.edn"] (:root conflict))))))))

  (testing "files that only look like migrations are not reported"
    ;; parse-name rejects these, an extension test would not. Reporting
    ;; 'notes.sql' as a shadowed migration sends the user hunting for a problem
    ;; that does not exist.
    (with-temp-dir
      (fn [root]
        (let [project  (io/file root "migrations")
              resource (io/file root "resources/migrations")]
          (.mkdirs resource)
          (.mkdirs project)
          (doseq [n ["README.md" "notes.sql" "scratch.edn"]]
            (spit (io/file project n) "not a migration"))
          (is (nil? (migrations/shadowed-migration-dirs project resource))
              "nothing here has a migration id, so nothing is being skipped")))))

  (testing "the file types come from migratus, not from a list of our own"
    ;; If migratus gains a type, this guard has to cover it without an edit
    ;; here — the previous version silently did not.
    (is (= #{"sql" "edn"} (set (migratus.protocols/get-all-supported-extensions)))
        "if this changes, the guard follows automatically via parse-name")))

(deftest ^:unit resolved-migration-dir-delegates-to-migratus
  ;; Not reimplemented: find-migration-dir tries the system classloader, the
  ;; context classloader, resources/migrations (its default-migration-parent is
  ;; "resources/") and finally migrations/. Every version of this guard that
  ;; picked one of those four and hardcoded it was wrong.
  (testing "the resolver is migratus's own"
    (is (= (migratus-utils/find-migration-dir "migrations/")
           (migrations/resolved-migration-dir "migrations/")))))

(deftest ^:unit get-migration-config-refuses-a-split
  (testing "the conflict stops the run before any database work"
    ;; db-config/get-active-db-config is redefined to throw: if the check ran
    ;; after it, this test would see that exception instead, so this also pins
    ;; the ordering.
    (with-redefs [migrations/shadowed-migration-dirs
                  (fn ([] {:root ["a.up.sql"] :resources ["b.up.sql"]})
                    ([_ _] {:root ["a.up.sql"] :resources ["b.up.sql"]}))
                  db-config/get-active-db-config
                  (fn [] (throw (ex-info "should not be reached" {})))]
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (migrations/get-migration-config)))]
        (is (= :migration-dir-conflict (:type (ex-data ex))))
        (is (re-find #"never read" (ex-message ex))
            "the message has to say what went wrong, since nothing else will")
        (is (re-find #"a\.up\.sql" (ex-message ex))
            "and name the files, so the user can act on it")
        (is (not= "Migration configuration failed" (ex-message ex))
            "the generic wrapper would demote the message into ex-data")))))

(deftest ^:unit conflicts-survive-the-operation-error-wrappers
  (testing "migrate reports the conflict rather than 'Migration failed'"
    ;; The operation handlers log a stack trace and rewrap their cause. Applied
    ;; to this error that reproduces the original complaint: the user is told
    ;; something failed but not that their migrations are in two places.
    (with-redefs [migrations/get-migration-config
                  (fn [] (throw (ex-info "Migrations exist in two directories, and only one is read."
                                         {:type :migration-dir-conflict})))]
      (doseq [[label op] [["migrate"  migrations/migrate]
                          ["rollback" migrations/rollback]]]
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (op)) label)]
          (is (= :migration-dir-conflict (:type (ex-data ex)))
              (str label ": the conflict type must survive"))
          (is (re-find #"two directories" (ex-message ex))
              (str label ": and so must the message"))))))

  (testing "unrelated failures are still wrapped as before"
    ;; The passthrough must be narrow: everything else keeps the existing
    ;; handling, which the surrounding tests already pin.
    (with-redefs [migrations/get-migration-config
                  (fn [] (throw (ex-info "connection refused" {:type :db-error})))]
      (let [ex (is (thrown? clojure.lang.ExceptionInfo (migrations/migrate)))]
        (is (= "Migration failed" (ex-message ex)))
        (is (= "connection refused" (:error (ex-data ex))))))))

(deftest ^:unit ensure-project-migration-dir-creates-the-target
  (testing "the directory exists afterwards"
    ;; migratus resolves the directory name against the filesystem only when it
    ;; already exists, so creating it is what decides where `bb migrate create`
    ;; writes. Verified end to end in a generated project: before this, the
    ;; first migration landed in resources/ while the CLI printed "Edit the
    ;; generated SQL files in migrations/".
    (with-temp-dir
      (fn [root]
        (let [target (io/file root "migrations")]
          (is (not (.exists target)) "precondition: a fresh project has no migrations/")
          (migrations/ensure-project-migration-dir! target)
          (is (.isDirectory target)
              "migratus writes to the filesystem only when the directory exists")))))

  (testing "an existing directory with migrations in it is left alone"
    (with-temp-dir
      (fn [root]
        (let [target (io/file root "migrations")]
          (touch-migration! target "20260101000000-existing.up.sql")
          (migrations/ensure-project-migration-dir! target)
          (is (= ["20260101000000-existing.up.sql"]
                 (mapv #(.getName %) (.listFiles target)))
              "creating the directory must never disturb what is in it")))))

  (testing "the default is the directory the CLI tells the user to edit"
    (is (= "migrations/" migrations/project-migration-dir))))

(deftest ^:unit create-migration-reports-where-it-actually-wrote
  ;; The reviewed version of this fix refused to create at all when
  ;; resources/migrations existed. That would have broken this repository, which
  ;; keeps all 12 of its migrations there with an empty migrations/ — a working
  ;; layout, because the directory that captures the name is the one being read.
  ;;
  ;; Measured in a generated project with resources/migrations populated and
  ;; migrations/ empty: create exited 0 and wrote both files to resources/, so
  ;; no split was produced. What was wrong is that it printed "Migration files
  ;; created in: migrations/" and told the user to edit files that were not
  ;; there.
  (testing "resources/migrations wins, and is what gets reported"
    (let [calls (atom [])]
      (with-redefs [migrations/resolved-migration-dir (fn [& _] (io/file "resources/migrations"))
                    migrations/get-migration-config (fn [] {:migration-dir ["migrations/"]})
                    migrations/ensure-project-migration-dir!
                    (fn [& _] (swap! calls conj :made-dir))
                    migratus/create (fn [& _] (swap! calls conj :created))]
        (let [result (migrations/create-migration "add-widgets")]
          (is (:success result))
          (is (= "resources/migrations/" (:directory result))
              "the CLI prints this and points the user at it")
          (is (= [:created] @calls)
              "no empty migrations/ beside a resources layout that already works")))))

  (testing "without a resources directory the project directory is used and reported"
    (let [calls (atom [])]
      (with-redefs [migrations/resolved-migration-dir (fn [& _] (io/file migrations/project-migration-dir))
                    migrations/get-migration-config (fn [] {:migration-dir ["migrations/"]})
                    migrations/ensure-project-migration-dir!
                    (fn [& _] (swap! calls conj :made-dir))
                    migratus/create (fn [config n] (swap! calls conj [:created (:migration-dir config) n]))]
        (let [result (migrations/create-migration "add-widgets")]
          (is (= "migrations/" (:directory result)))
          (is (= [:made-dir [:created "migrations/" "add-widgets"]] @calls)
              "the directory must exist before migratus resolves the name to it")))))

  (testing "an existing split refuses before anything is written"
    ;; This is the case the review was aiming at. It is caught by the read
    ;; guard, which create goes through: migratus/create throws here, so if the
    ;; refusal did not happen first this test would report that instead.
    (with-redefs [migrations/resolved-migration-dir (fn [& _] (io/file "resources/migrations"))
                  migrations/shadowed-migration-dirs
                  (fn ([] {:root ["a.up.sql"] :resources []})
                    ([_ _] {:root ["a.up.sql"] :resources []}))
                  migratus/create (fn [& _] (throw (ex-info "must not write" {})))]
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (migrations/create-migration "add-widgets")))]
        (is (= :migration-dir-conflict (:type (ex-data ex)))
            "creating must not add to a set of migrations nothing will read"))))

  (testing "create-destination reports the resolved source, not a fixed string"
    (with-temp-dir
      (fn [root]
        (let [resource (io/file root "resources/migrations")]
          (is (= "migrations/" (migrations/create-destination nil))
              "nothing resolved: the project directory is used and reported")
          (.mkdirs resource)
          ;; Canonical: readable-path canonicalises, and on macOS the temp
          ;; directory is reached through a /var -> /private/var symlink.
          (is (= (str (.getCanonicalPath resource) "/")
                 (migrations/create-destination resource))
              "a directory that wins is where the files actually land")))))

  (testing "a jar cannot be written to, so the project directory is reported"
    ;; migratus/create fails on its own here; what matters is that this does not
    ;; hand the CLI a jar path to tell the user to go and edit.
    (with-temp-dir
      (fn [root]
        (let [jar (io/file root "app.jar")]
          (with-open [out (java.util.jar.JarOutputStream. (io/output-stream jar))]
            (.putNextEntry out (java.util.jar.JarEntry. "migrations/"))
            (.closeEntry out))
          (with-open [jf (java.util.jar.JarFile. jar)]
            (is (= "migrations/" (migrations/create-destination jf)))))))))
