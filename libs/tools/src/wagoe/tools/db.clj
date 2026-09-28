#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/db.clj
;;
;; Database workflow commands for Wagoe projects.
;;
;; Usage (via bb.edn task):
;;   bb db:status    # Show database config and migration info
;;   bb db:reset     # Drop the app's tables and migrate (dev, test, acc)
;;   bb db:seed      # Seed database from dev seed file

(ns wagoe.tools.db
  (:require [wagoe.tools.ansi :refer [bold green red yellow dim]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :as process]
            [wagoe.tools.project :as project]))

;; =============================================================================
;; Pure helpers
;; =============================================================================

(defn- root-dir [] (System/getProperty "user.dir"))

(def project-migration-dir
  "Where a project's migrations live. Copies
   wagoe.platform.shell.database.migrations/project-migration-dir, which
   Babashka cannot load; db-test pins the two together."
  "migrations/")

(defn- config-path [root]
  (str root "/resources/conf/dev/config.edn"))

(defn- seed-path []
  (str (root-dir) "/resources/seeds/dev.edn"))

(defn seed-args
  "The arguments `clojure -M:seed` gets after the path: `args`, and the
   application's system-config when it has one, so the application's seed
   hooks run — a seeded row with a workflow gets one (BOU-578)."
  [root args]
  (let [f  (io/file root "src" (project/base-ns root) "system_config.clj")
        ;; What the file declares: `wagoe new my-app` writes my_app.*, and a
        ;; name derived from the directory cannot tell which it is.
        ns (when (.isFile f) (second (re-find #"\(ns\s+([^\s()]+)" (slurp f))))]
    (cond-> (vec args)
      (and ns (not (some #{"--system"} args)))
      (into ["--system" ns]))))

(defn- parse-config-minimal
  "Parse config.edn with a minimal reader that replaces Aero tags with placeholders."
  [config-text]
  (let [readers {'env      (fn [v] (str "ENV:" v))
                 'or       (fn [v] (if (vector? v) (last v) v))
                 'long     (fn [v] (if (number? v) v 0))
                 'merge    (fn [v] (if (vector? v) (apply merge v) v))
                 'include  (fn [_v] {})
                 'str      (fn [v] (str v))
                 'join     (fn [v] (if (vector? v) (str/join v) (str v)))
                 'keyword  (fn [v] (keyword v))
                 'ref      (fn [v] v)
                 'ig/ref   (fn [v] v)
                 'profile  (fn [v] v)}]
    (try
      (edn/read-string {:readers readers} config-text)
      (catch Exception e
        (println (yellow (str "  Warning: could not parse config.edn: " (.getMessage e))))
        nil))))

(defn- detect-db-type
  "Detect the database type from the active config map.
   Returns a map with :type and :info keys."
  [active-config]
  (let [db-keys     (filter (fn [k]
                              (and (keyword? k)
                                   (= (namespace k) "wagoe")
                                   (contains? #{"postgresql" "sqlite" "mysql" "h2"}
                                              (name k))))
                            (keys active-config))
        db-key      (first db-keys)
        db-type     (when db-key (name db-key))
        db-config   (when db-key (get active-config db-key))]
    {:type   (or db-type "unknown")
     :config db-config}))

(def ^:private migration-name
  "What migratus's parse-name accepts; anything else in the directory is ignored."
  #"^\d+-.+\.(up|down)\.sql$|^\d+-.+\.edn$")

(defn- list-migration-files
  "Migration files under `dir` at any depth, as paths relative to it, sorted by
   file name — or nil when `dir` does not exist. Recursive because migratus
   reads with file-seq."
  [dir]
  (let [d (io/file dir)]
    (when (.isDirectory d)
      (let [base (.toPath d)]
        (->> (file-seq d)
             (filter #(and (.isFile %) (re-matches migration-name (.getName %))))
             (sort-by #(.getName %))
             (mapv #(str (.relativize base (.toPath %)))))))))

(defn migration-layout
  "Where `root`'s migrations are read from.

   migratus resolves the name `migrations/` to `resources/migrations` when that
   exists, so files in the project directory are then never read, and the
   platform refuses to migrate (BOU-274). Those are returned as `:shadowed`.

   Babashka cannot see the JVM classpath, so a `migrations/` on it (another
   resource directory, a jar) is not modelled; `bb migrate status` is
   authoritative."
  [root]
  (let [resource (io/file root "resources" "migrations")
        project  (io/file root project-migration-dir)]
    (if (.isDirectory resource)
      {:dir      "resources/migrations/"
       :path     (str resource)
       :files    (list-migration-files resource)
       :shadowed (seq (list-migration-files project))}
      {:dir   project-migration-dir
       :path  (str project)
       :files (list-migration-files project)})))

;; =============================================================================
;; Subcommands
;; =============================================================================

(defn db-status
  "Show database config and migration info."
  ([] (db-status (root-dir)))
  ([root]
   (println)
   (println (bold "Wagoe Database Status"))
   (println)

   ;; Read and parse config
   (let [config-file (io/file (config-path root))]
     (if-not (.exists config-file)
       (println (red (str "  Config not found: " (config-path root))))
       (let [config-text  (slurp config-file)
             parsed       (parse-config-minimal config-text)
             active       (or (:active parsed) {})
             {:keys [type config]} (detect-db-type active)]

         ;; Database type
         (println (str "  " (bold "Database type: ") (green type)))

         ;; Connection info (show what we can extract without connecting)
         (when config
           (let [jdbc-url  (:jdbc-url config)
                 db-name   (:db-name config)
                 host      (:host config)]
             (when jdbc-url
               (println (str "  " (bold "JDBC URL:      ") (dim (str jdbc-url)))))
             (when host
               (println (str "  " (bold "Host:          ") (dim (str host)))))
             (when db-name
               (println (str "  " (bold "Database:      ") (dim (str db-name)))))))

         (println)

         ;; Migration files
         (let [{:keys [dir path files shadowed]} (migration-layout root)]
           (cond
             shadowed
             (do
               (println (red (str "  Never read — " dir " captures the name " project-migration-dir ":")))
               (doseq [f shadowed]
                 (println (red (str "    " project-migration-dir f))))
               (println (dim "  Keep every migration in one directory; `bb migrate up` refuses this split.")))

             (nil? files)
             (println (yellow (str "  No migrations directory found at " path)))

             :else
             (let [count-files (count files)]
               (println (str "  " (bold "Migrations:    ") (green (str count-files))
                             (dim (str " file" (when (not= count-files 1) "s")
                                       " in " dir))))
               (when (pos? count-files)
                 (println (dim (str "  Latest:        " (last files))))))))

         (println (dim "  From the files on disk; `bb migrate status` is authoritative."))

         (println))))))

(def ^:private disposable-envs
  "Environments whose database may be seeded. Allowlist, not denylist: a
   denylist lets an unrecognised environment such as \"staging\" through.
   Seeding writes rows into whatever database the active config resolves to,
   so refuse anything not known to be disposable.

   Mirrors wagoe.platform.shell.adapters.database.config/disposable-envs — that
   one is authoritative; this copy exists because Babashka cannot load it."
  #{"dev" "development" "test" "local"})

(def resettable-envs
  "The profiles db:reset runs in. Mirrors
   wagoe.platform.shell.adapters.database.config/resettable-envs, which is
   authoritative; Babashka cannot load it."
  #{"dev" "test" "acc"})

(def env-aliases
  "Mirrors wagoe.config's env-aliases, which Babashka cannot load; db-test
   pins the two together."
  {"development" "dev" "production" "prod" "acceptance" "acc" "testing" "test"})

(def ^:dynamic *exit!* (fn [code] (System/exit code)))

(defn- flag-value [args flag]
  (second (drop-while #(not= flag %) args)))

(defn- normalize-profile [s]
  (let [s (some-> s str/trim str/lower-case)]
    (get env-aliases s s)))

(defn reset-profile
  "{:profile p} for `bb db:reset args`, or {:refused why}. Every place that
   names a profile must name dev, test or acc — one prod anywhere refuses —
   and one must: the default is not a choice anybody made."
  [args getenv]
  (let [sources (filter second [["--env" (flag-value args "--env")]
                                ["WAG_ENV" (getenv "WAG_ENV")]
                                ["ENV" (getenv "ENV")]
                                ["ENVIRONMENT" (getenv "ENVIRONMENT")]])
        bad     (remove #(contains? resettable-envs (normalize-profile (second %))) sources)]
    (cond
      (empty? sources)
      {:refused "No profile is named. Set WAG_ENV to dev, test or acc, or pass --env dev."}

      (seq bad)
      {:refused (str (str/join ", " (map (fn [[k v]] (str k "=" (pr-str v))) bad))
                     " is not dev, test or acc.")}

      :else {:profile (normalize-profile (second (first sources)))})))

(defn db-reset
  "Drop the application's tables and migrate, in dev, test or acc only.

   The profile goes to the JVM as -Denv, which outranks every variable, so
   the platform resolves the same one. It checks again there, shows what it
   will drop, and asks; this refusal only saves starting a JVM."
  [& args]
  (let [{env :profile why :refused} (reset-profile args #(System/getenv %))]
    (if why
      (do (println (red (str "  REFUSED: " why)))
          (println (dim "  Production changes go through migrations: `bb migrate up`, with a down"))
          (println (dim "  migration or a conversion migration for what must change or go. Never a reset."))
          (println (dim "  Reset runs only in: acc, dev, test."))
          (*exit!* 1))
      (let [cmd (cond-> ["clojure" (str "-J-Denv=" env) "-M:migrate" "reset"]
                  (some #{"--allow-remote"} args) (conj "--allow-remote"))
            {:keys [exit]} (apply process/shell {:continue true} cmd)]
        (when-not (zero? exit)
          (println (red "  Reset refused, cancelled or failed."))
          (*exit!* exit))))))

(defn db-seed
  "Seed the database from the dev seed file.

   Refuses outside a development-like environment: the seed path defaults to
   resources/seeds/dev.edn while the *database* comes from the active config,
   so without this a WAG_ENV=prod shell would insert demo rows into
   production. Pass --force to override deliberately."
  [& args]
  (println)
  (println (bold "Wagoe Database Seed"))
  (println)
  ;; Advisory only. libs/tools is Babashka in its own process, so it cannot see
  ;; the -Denv JVM property the :prod/:dev aliases set — meaning it cannot fully
  ;; reproduce the platform's detect-environment. The authoritative guard lives
  ;; in wagoe.platform.shell.database.cli-seed, which runs in the same JVM as
  ;; the database connection and uses detect-environment directly. This check
  ;; exists to fail fast with a friendly message in the common case.
  (let [force? (boolean (some #{"--force"} args))
        env    (or (System/getenv "WAG_ENV")
                   (System/getenv "ENV")
                   (System/getenv "ENVIRONMENT")
                   "dev")]
    (when-not (or force? (contains? disposable-envs env))
      (println (red (str "  REFUSED: bb db:seed cannot run in the " env " environment.")))
      (println (dim "  Seeding inserts rows into the database the active config resolves to."))
      (println)
      (println (dim "  If this is genuinely intended:"))
      (println (dim (str "    clojure -M:seed " (seed-path) " --force")))
      (println)
      (System/exit 1)))
  (let [seed-file (io/file (seed-path))]
    (if-not (.exists seed-file)
      (do
        (println (yellow "  Seed file not found."))
        (println)
        (println (dim "  To get started, create a seed file at:"))
        (println (dim (str "    " (seed-path))))
        (println)
        (println (dim "  Example content:"))
        (println (dim "    [[:projects [{:id :project/demo :name \"Demo\"}]]"))
        (println (dim "     [:tasks [{:title \"First\" :project-id :project/demo}]]]"))
        (println)
        (println (dim "  `bb scaffold` writes one example per entity there; `bb guide seed` explains the file."))
        (println))
      ;; Pass through to the JVM side. libs/tools is pure Babashka with no
      ;; Maven deps at runtime, so it cannot open a JDBC connection itself —
      ;; the same reason `bb migrate` shells out to `clojure -M:migrate`.
      (let [{:keys [exit]} (apply process/shell
                                  {:out :inherit :err :inherit :continue true}
                                  "clojure" "-M:seed" (seed-path) (seed-args (root-dir) args))]
        (when-not (zero? exit)
          (System/exit exit))))))

;; =============================================================================
;; Help
;; =============================================================================

(defn- print-help []
  (println (bold "bb db:<command>") " — Database workflow commands")
  (println)
  (println "Usage:")
  (println "  bb db:status     Show database type, connection info, and migration count")
  (println "  bb db:reset      Drop the app's tables and migrate; dev, test, acc only [--env E] [--allow-remote]")
  (println "  bb db:seed       Seed database from resources/seeds/dev.edn")
  (println)
  (println (dim "  bb db:<command> --help for one command.")))

(def ^:private usage
  {"status" ["Usage: bb db:status"
             ""
             "Show the database type, connection info and migration files, read"
             "from resources/conf/dev/config.edn. Connects to nothing."]
   "reset"  ["Usage: bb db:reset [--env dev|test|acc] [--allow-remote]"
             ""
             "Roll back every migration, drop the tables the framework creates at"
             "boot, and migrate again. Runs in dev, test and acc only, and asks for"
             "the database name on a terminal first."
             ""
             "  --env E           The profile to reset; else WAG_ENV. One must be named."
             "  --allow-remote    Allow a database on another machine."
             ""
             "It drops the user tables: run `bb create-admin` afterwards."]
   "seed"   ["Usage: bb db:seed [path] [--force]"
             ""
             "Insert resources/seeds/dev.edn (or path) in one transaction. Dev-like"
             "environments only; --force overrides that. `bb guide seed` explains"
             "the file."]})

(defn- help? [args] (boolean (some #{"--help" "-h"} args)))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& args]
  (let [[subcmd & rest-args] args]
    ;; Before any command runs: `bb db:reset --help` used to reset (BOU-588).
    (if (and (contains? usage subcmd) (help? rest-args))
      (run! println (usage subcmd))
      (case subcmd
        "status" (db-status)
        "reset"  (apply db-reset rest-args)
        "seed"   (apply db-seed rest-args)
        (print-help)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
