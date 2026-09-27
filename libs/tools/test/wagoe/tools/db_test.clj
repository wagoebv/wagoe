(ns wagoe.tools.db-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.db :as db]))

(defn- repo-root []
  (let [cwd (fs/cwd)]
    (if (fs/exists? (fs/path cwd "libs"))
      cwd
      (fs/path cwd ".." ".."))))

(defn- temp-project
  "A project with a dev config and the given files, relative to its root."
  [files]
  (let [root (fs/create-temp-dir {:prefix "db-status-test"})]
    (doseq [[path content] (assoc files "resources/conf/dev/config.edn"
                                  "{:active {:wagoe/h2 {:db-name \"x\"}}}")]
      (fs/create-dirs (fs/parent (fs/path root path)))
      (spit (str (fs/path root path)) content))
    (str root)))

(deftest ^:unit db-status-reads-the-migrators-directory
  ;; The migrator and the scaffolder use `migrations/`; `db:status` looked only
  ;; in resources/migrations and told such a project it had none (BOU-489).
  (testing "a project keeping its migrations in migrations/ has them counted"
    (let [root (temp-project {"migrations/20260101000000-create-x.up.sql"   "CREATE TABLE x (id INT);"
                              "migrations/20260101000000-create-x.down.sql" "DROP TABLE x;"})
          out  (with-out-str (db/db-status root))]
      (is (str/includes? out "20260101000000-create-x"))
      (is (not (str/includes? out "No migrations")) out)))

  (testing "with none, it names migrations/ — not resources/migrations"
    (let [out (with-out-str (db/db-status (temp-project {})))]
      (is (str/includes? out "/migrations") out)
      (is (not (str/includes? out "resources/migrations")) out)))

  (testing "a split the migrator refuses is reported, not counted as fine"
    (let [root (temp-project {"migrations/20260101000000-a.up.sql"           "SELECT 1;"
                              "resources/migrations/20260102000000-b.up.sql" "SELECT 1;"})
          out  (with-out-str (db/db-status root))]
      (is (str/includes? out "Never read") out)
      (is (str/includes? out "20260101000000-a.up.sql") out)
      (is (not (str/includes? out "Migrations:")) out)))

  (testing "what counts is what migratus reads: nested and .edn, not notes"
    ;; The platform guard walks subdirectories and uses migratus's parse-name;
    ;; counting only top-level .sql passed a split that `migrate up` refuses.
    (doseq [file ["migrations/tenant/20260101000000-a.up.sql"
                  "migrations/20260101000000-a.edn"]]
      (let [root (temp-project {file                                           "{}"
                                "resources/migrations/20260102000000-b.up.sql" "SELECT 1;"})]
        (is (seq (:shadowed (db/migration-layout root))) file)))
    (let [root (temp-project {"migrations/notes.sql"                          "-- x"
                              "resources/migrations/20260102000000-b.up.sql" "SELECT 1;"})]
      (is (nil? (:shadowed (db/migration-layout root))) "notes.sql is not a migration"))))

(deftest ^:unit project-migration-dir-matches-the-platform
  ;; Babashka cannot load the platform namespace, so the value is copied. This
  ;; is what keeps the copy honest.
  (let [src (slurp (str (fs/path (repo-root) "libs/platform/src/wagoe/platform/shell/database/migrations.clj")))
        [_ platform-dir] (re-find #"\(def project-migration-dir\s+\"(?:[^\"\\]|\\.)*\"\s+\"([^\"]+)\"\)" src)]
    (is (some? platform-dir) "the platform definition was not found")
    (is (= platform-dir db/project-migration-dir))))
