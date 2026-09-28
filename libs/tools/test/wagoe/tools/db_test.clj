(ns wagoe.tools.db-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [babashka.process]
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

(deftest ^:unit seeding-goes-through-the-application
  ;; BOU-578: its seed hooks start each seeded row's workflow.
  (let [root (temp-project {"src/shop/main.clj" "(ns shop.main)"
                            "src/shop/system_config.clj" "(ns shop.system-config)"})]
    (is (= ["--system" "shop.system-config"] (db/seed-args root [])))
    (is (= ["--force" "--system" "shop.system-config"] (db/seed-args root ["--force"])))
    (is (= ["--system" "mine"] (db/seed-args root ["--system" "mine"]))
        "one named on the command line wins"))
  (testing "a project named with a hyphen: the ns its file declares, not one derived from the directory"
    (let [root (temp-project {"src/my_app/main.clj" "(ns my_app.main)"
                              "src/my_app/system_config.clj" ";; mine\n(ns my_app.system-config\n  \"doc\")"})]
      (is (= ["--system" "my_app.system-config"] (db/seed-args root [])))))
  (testing "a project without a system-config seeds as before"
    (is (= [] (db/seed-args (temp-project {}) [])))))

(defn- run-reset
  "Run `bb db:reset args` with `env` as the environment variables. Returns
   {:exit :out :cmds}."
  [args env]
  (let [exit (atom nil)
        cmds (atom [])
        f    db/reset-profile
        out  (with-redefs [babashka.process/shell (fn [_opts & cmd] (swap! cmds conj (vec cmd)) {:exit 0})
                           db/reset-profile       (fn [a _] (f a #(get env %)))]
               (binding [db/*exit!* #(reset! exit %)]
                 (with-out-str (with-in-str "yes\nyes\n" (apply db/db-reset args)))))]
    {:exit @exit :out out :cmds @cmds}))

(deftest ^:unit reset-runs-in-dev-test-and-acc-only
  ;; A reset drops what no migration brings back; prod changes through
  ;; migrations, and piping yes does not change that (BOU-585).
  (doseq [[args env] [[["--env" "prod"] {}]
                      [[] {"WAG_ENV" "prod"}]
                      [[] {"ENV" "staging"}]
                      [[] {"WAG_ENV" "local"}]
                      [[] {"WAG_ENV" "production"}]
                      [["--env" ""] {}]
                      [[] {}]
                      [["--env" "dev"] {"WAG_ENV" "prod"}]
                      [["--env" "test"] {"WAG_ENV" "prod"}]]]
    (testing (pr-str args env)
      (let [{:keys [exit out cmds]} (run-reset args env)]
        (is (= 1 exit) out)
        (is (empty? cmds) "no JVM is started")
        (is (str/includes? out "bb migrate up") out))))
  (doseq [[args env profile] [[[] {"WAG_ENV" "dev"} "dev"]
                              [[] {"WAG_ENV" "development"} "dev"]
                              [["--env" "Development"] {} "dev"]
                              [[] {"WAG_ENV" "acceptance"} "acc"]
                              [["--env" "test"] {"WAG_ENV" "dev"} "test"]
                              [[] {"WAG_ENV" "acc"} "acc"]]]
    (testing profile
      (let [{:keys [exit cmds]} (run-reset args env)]
        (is (nil? exit))
        (is (= [["clojure" (str "-J-Denv=" profile) "-M:migrate" "reset"]] cmds)
            "the platform resolves the same profile"))))
  (testing "--allow-remote reaches the platform"
    (is (= [["clojure" "-J-Denv=dev" "-M:migrate" "reset" "--allow-remote"]]
           (:cmds (run-reset ["--allow-remote"] {"WAG_ENV" "dev"}))))))

(deftest ^:unit env-aliases-match-the-config-loader
  (let [src (slurp (str (fs/path (repo-root) "libs" "config" "src" "wagoe" "config.clj")))
        m   (second (re-find #"(?s)\(def \^:private env-aliases.*?(\{[^}]*\})" src))]
    (is (= (read-string m) db/env-aliases))))
