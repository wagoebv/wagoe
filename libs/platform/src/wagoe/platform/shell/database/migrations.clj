(ns wagoe.platform.shell.database.migrations
  "Database migration management using Migratus.

   This namespace provides functions to manage database schema migrations:
   - Run pending migrations (up)
   - Rollback migrations (down)
   - Check migration status
   - Create new migrations

   Migrations are discovered from the application's `migrations/` directory and
   from any library manifests published on the classpath."
  (:require [migratus.core :as migratus]
            ;; The resolution rule for `:migration-dir` lives here, and three
            ;; successive versions of the guard below reimplemented one branch
            ;; of it and missed the others. Calling it is the only way to stay
            ;; in agreement with what actually reads the migrations.
            [migratus.utils :as migratus-utils]
            ;; And what counts as a migration file lives here. Hardcoding
            ;; ".sql" missed EDN migrations, which migratus reads just as
            ;; happily — `get-all-supported-extensions` returns ["sql" "edn"].
            [migratus.migrations :as migratus-migrations]
            [wagoe.platform.shell.adapters.database.config :as db-config]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Migration Configuration
;; =============================================================================

(def ^:private migration-manifest-resource "wagoe/migration-paths.edn")

(defn- context-classloader
  []
  (or (.getContextClassLoader (Thread/currentThread))
      (clojure.lang.RT/baseLoader)))

(defn manifest-urls
  []
  (enumeration-seq (.getResources (context-classloader) migration-manifest-resource)))

(defn- parse-migration-manifest [manifest-url]
  (let [manifest-data (-> manifest-url slurp edn/read-string)
        paths (cond
                (vector? manifest-data) manifest-data
                (map? manifest-data) (:paths manifest-data)
                :else nil)]
    (when-not (sequential? paths)
      (throw (ex-info "Invalid migration manifest"
                      {:type :configuration-error
                       :resource migration-manifest-resource
                       :url (str manifest-url)
                       :expected "vector or map with :paths vector"})))
    (->> paths
         (filter string?)
         (remove empty?))))

(defn- nested-sql-subdirs
  "Returns display paths for any subdirectory of dir-path that contains .sql files.
   Reports the immediate parent of each flagged file (leaf-only). A file at
   migrations/tenant/v2/001.sql appears as 'migrations/tenant/v2/' — the
   intermediate 'migrations/tenant/' is not separately reported."
  [dir-path]
  (let [dir-file (or (let [f (io/file dir-path)]
                       (when (.isDirectory f) f))
                     (when-let [url (io/resource dir-path)]
                       (when (= "file" (.getProtocol url))
                         (io/file (.toURI url)))))]
    (when dir-file
      (let [canonical-dir (.getCanonicalPath dir-file)]
        (->> (file-seq dir-file)
             (filter #(and (.isFile %) (.endsWith (.getName %) ".sql")))
             (map #(.getParentFile %))
             (remove #(= canonical-dir (.getCanonicalPath %)))
             (map (fn [parent]
                    (str dir-path
                         (subs (.getCanonicalPath parent) (inc (count canonical-dir)))
                         "/")))
             distinct)))))

(def project-migration-dir
  "Where new migrations are written.

   Library-contributed directories are read from, never written to: a migration
   you author belongs to your project, not to a dependency. Kept as a single
   string because Migratus wants one directory when creating, and because
   `create-migration` must not be handed the whole discovered vector (BOU-271)."
  "migrations/")

;; There is deliberately no `resource-migration-dir` constant here any more.
;; Naming one place that can capture `migrations/` is what made the first three
;; versions of this guard wrong: `resources/migrations` is only one of four
;; candidates migratus tries, and hardcoding it missed both an empty directory
;; and a jar. `resolved-migration-dir` below asks migratus instead.

(defn- migration-files
  "Every migration file under `dir`, at any depth, or nil if it is not a directory.

   What counts as one is `migratus.migrations/parse-name`, not an extension
   test of our own. Two reasons it has to be theirs:

   - migratus reads EDN migrations as well as SQL, and a hardcoded \".sql\"
     filter reported no conflict for a shadowed `migrations/x.edn` — the guard
     found no files, so nothing threw and the migration was skipped in silence,
     which is the whole failure this is meant to catch.
   - `parse-name` also rejects files that merely have the right extension:
     `notes.sql` is not a migration, and reporting it as one would send the
     user looking for a problem that is not there.

   Recursive because migratus reads with `file-seq`. Measured: a migration at
   `migrations/tenant/…up.sql` is applied exactly like a top-level one, and is
   shadowed exactly like one — with an empty `resources/migrations` present,
   `up` exited 0 and the nested table was never created."
  [dir]
  (let [f (io/file dir)]
    (when (.isDirectory f)
      (seq (filter #(and (.isFile %)
                         (some? (migratus-migrations/parse-name (.getName %))))
                   (file-seq f))))))

(defn- display-name
  "Path of `file` relative to `dir`, so nested migrations are identifiable.

   `.getName` alone would report `001.up.sql` for a file the user has to find
   under `migrations/tenant/v2/`."
  [dir file]
  (let [base (.getCanonicalPath (io/file dir))
        path (.getCanonicalPath file)]
    (if (str/starts-with? path (str base java.io.File/separator))
      (subs path (inc (count base)))
      (.getName file))))

(defn resolved-migration-dir
  "What migratus will actually read `migrations/` from — a File, JarFile, or nil.

   Delegates to `migratus.utils/find-migration-dir` instead of reimplementing
   it. That function tries, in order: the system classloader, the context
   classloader, `resources/migrations` (its `default-migration-parent` is
   \"resources/\"), and finally `migrations/`. Only the last of those is the
   project directory.

   Measured: with a jar containing `migrations/` on the classpath and a
   populated `migrations/` on disk, this returns the JarFile — so a check that
   only looked for a `resources/migrations` directory on the filesystem saw no
   conflict while the on-disk migrations were skipped."
  ([] (resolved-migration-dir project-migration-dir))
  ([dir] (migratus-utils/find-migration-dir dir)))

(defn- with-resolved-dir
  "Call `f` with the resolved source, closing it if it is a JarFile.

   `find-migration-dir` opens the jar to hand it back, and every command here
   resolves at least once. Callers only ever read a name or a file list out of
   it, so nothing needs the handle afterwards."
  [f]
  (let [resolved (resolved-migration-dir)]
    (try
      (f resolved)
      (finally
        (when (instance? java.util.jar.JarFile resolved)
          (.close ^java.util.jar.JarFile resolved))))))

(defn- readable-path
  "Path relative to the working directory when it sits under it.

   `find-migration-dir` resolves through the classpath, which hands back
   absolute paths: the CLI printed '/private/tmp/app/resources/migrations/'
   where the user had typed 'resources/migrations'. Anything outside the working
   directory stays absolute, because a relative path would not locate it."
  [^java.io.File f]
  (let [cwd (.getCanonicalPath (io/file "."))
        p   (.getCanonicalPath f)]
    (if (str/starts-with? p (str cwd java.io.File/separator))
      (subs p (inc (count cwd)))
      p)))

(defn capturing-source
  "How to describe what captures `migrations/`, or nil when it is the project.

   nil means the project directory is what gets read, which is the only layout
   with nothing at risk."
  [resolved project-dir]
  (cond
    (nil? resolved) nil
    (instance? java.util.jar.JarFile resolved)
    (str "the jar '" (.getName ^java.util.jar.JarFile resolved) "'")

    (= (.getCanonicalPath ^java.io.File resolved)
       (.getCanonicalPath (io/file project-dir)))
    nil

    :else (str "'" (readable-path resolved) "'")))

(defn create-destination
  "The directory `migratus/create` will actually write to.

   Not the one `create-config` names, when they differ: a `resources/migrations`
   takes the file while the config still says `migrations/`. Reporting the
   config value is why `bb migrate create` printed \"Migration files created in:
   migrations/\" for files it had written to resources/.

   A jar cannot be written to, so it falls back to the project directory — the
   create path is a development command, and migratus fails on its own there."
  ([] (with-resolved-dir create-destination))
  ([resolved]
   (if (instance? java.io.File resolved)
     (str (readable-path resolved) "/")
     project-migration-dir)))

(defn shadowed-migration-dirs
  "Migrations in `migrations/` that no command will read — otherwise nil.

   `migrations/` is a name that resolves to exactly one place, and it is not
   always the project directory: a `resources/migrations` directory or a jar on
   the classpath carrying `migrations/` both take it. When that happens the
   project directory is skipped entirely — not applied, not listed as pending,
   not counted. A green `migrate up` and a clean `status` are both consistent
   with a table that was never created (BOU-274).

   Only this direction is a hazard. If the winning source is the one holding the
   migrations, nothing is lost — `resources/migrations` alone is an
   unconventional but working layout, and it is what this repository uses. It is
   SQL under `migrations/` that silently goes nowhere, so that is what this
   reports.

   Reachable without doing anything unusual: `bb migrate create` used to write
   to resources/ in a project with no `migrations/` directory, while the
   scaffolder always writes to the project root — so one project's migrations
   could end up split across both without the user choosing anything.

   Both the directory and the resolved source are parameters as well as
   defaults: they depend on the working directory and the classpath, neither of
   which a test can change from inside the JVM."
  ([] (with-resolved-dir #(shadowed-migration-dirs project-migration-dir %)))
  ([project-dir resolved]
   (when-let [captured-by (capturing-source resolved project-dir)]
     (when-let [ignored (migration-files project-dir)]
       {:root       (mapv #(display-name project-dir %) ignored)
        :read-from  captured-by
        :resources  (when (instance? java.io.File resolved)
                      (mapv #(display-name resolved %) (migration-files resolved)))}))))

(defn ensure-project-migration-dir!
  "Create `migrations/` if absent, so migratus resolves the name to it.

   Without this, creating the first migration in a fresh project lands it under
   resources/ — a different directory from the one the scaffolder uses, and the
   start of the shadowing above.

   Takes the directory for the same reason as `shadowed-migration-dirs`: the
   default is relative to the working directory, so the no-arg form can only be
   exercised against the repository itself."
  ([] (ensure-project-migration-dir! project-migration-dir))
  ([dir] (.mkdirs (io/file dir))))

(defn discover-migration-dirs
  "Return the complete set of migration directories visible to the application.

   The root application keeps using `migrations/`. Libraries can contribute
   additional Migratus-compatible directories by publishing a
   `wagoe/migration-paths.edn` resource on the classpath."
  []
  (let [library-dirs (mapcat parse-migration-manifest (manifest-urls))
        migration-dirs (->> (concat [project-migration-dir] library-dirs)
                            distinct
                            vec)]
    (log/info "Discovered migration directories"
              {:count (count migration-dirs)
               :dirs migration-dirs})
    (doseq [dir-path migration-dirs
            subdir   (nested-sql-subdirs dir-path)]
      (log/warn (str "Found SQL files in subdirectory '" subdir
                     "' — these will be applied to the public schema."
                     " If they are tenant-scoped migrations, move them"
                     " to a separate classpath resource.")))
    migration-dirs))

(defn create-migratus-config
  "Creates Migratus configuration from database config.

   Args:
     db-config: Database configuration map with :datasource

  Returns:
     Migratus configuration map"
  [db-config]
  {:store                :database
   :migration-dir        (discover-migration-dirs)
   :init-script          nil  ; No init script needed
   :init-in-transaction? false
   :migration-table-name "schema_migrations"
   :db                   {:datasource (:datasource db-config)}})

(defn create-config
  "Narrow a read config to one suitable for *creating* a migration.

   `:migration-dir` must be a single directory string here. The read config
   carries the discovered vector of every directory on the classpath, which
   `up`, `status` and `rollback` accept — but `migratus/create` casts it to
   String, so `bb migrate create` died with a ClassCastException and a stack
   trace for everyone who followed `bb migrate --help` (BOU-271).

   The override is the project directory rather than the first element of the
   discovered list: a migration you author belongs to your project, and a
   dependency reordering that list must not decide where your files land."
  [read-config]
  (assoc read-config :migration-dir project-migration-dir))

(defn get-migration-config
  "Gets migration configuration for the active database.

   Returns:
     Migratus configuration map

   Throws:
     Exception if database configuration cannot be loaded"
  []
  ;; Outside the try below: that handler rewraps everything as "Migration
  ;; configuration failed" with the real message demoted into ex-data, and the
  ;; whole point of this check is the message.
  (when-let [{:keys [root resources read-from]} (shadowed-migration-dirs)]
      ;; Refusing here rather than warning: the failure this replaces is a
      ;; migration that never runs while every command reports success, so a
      ;; message on stderr is exactly what the user already missed.
    (throw (ex-info
            (str "These migrations are never read:\n"
                 ;; One per line, each fully prefixed. Joining them with ", "
                 ;; after a single prefix made every file but the first look
                 ;; like a path relative to nothing.
                 (str/join "\n" (map #(str "  " project-migration-dir %) root))
                 "\n"
                 "':migration-dir' is a name, not a path, and it resolved to "
                 read-from
                 (cond
                   (seq resources) (str " (holding " (str/join ", " resources) ")")
                   (some? resources) " (which is empty)"
                   :else "")
                 " — so everything above is skipped: not applied, and not"
                 " reported as pending.\n"
                 "Keep migrations in one place. '" project-migration-dir "' is the"
                 " conventional home; if you keep " read-from ", it must hold"
                 " all of them.")
            {:type                :migration-dir-conflict
             :read-from           read-from
             :ignored-migrations  root
             :resource-migrations resources})))
  (try
    (let [db-config (db-config/get-active-db-config)]
      (log/info "Loading migration configuration" {:database (:database-type db-config)})
      (create-migratus-config db-config))
    (catch Exception e
      (log/error e "Failed to load database configuration for migrations")
      (throw (ex-info "Migration configuration failed"
                      {:type :configuration-error
                       :error (.getMessage e)}
                      e)))))

;; =============================================================================
;; Migration Operations
;; =============================================================================

(defn- rethrow-config-conflict!
  "Re-throw a migration-directory conflict unchanged; return nil otherwise.

   Every operation below catches, logs a stack trace, and rewraps its cause as
   \"Migration failed\" with the real message demoted into ex-data. For a crash
   that is fine. For this one it is not: the message *is* the fix, and burying
   it under a trace reproduces the original complaint — a user who cannot tell
   what happened to their migration."
  [e]
  (when (= :migration-dir-conflict (:type (ex-data e)))
    (throw e)))

(defn migrate
  "Runs all pending database migrations.

   Returns:
     nil

   Throws:
     Exception if migration fails"
  []
  (log/info "Running database migrations...")
  (try
    (let [config (get-migration-config)]
      (migratus/migrate config)
      (log/info "Database migrations completed successfully"))
    (catch Exception e
      (rethrow-config-conflict! e)
      (log/error e "Database migration failed")
      (throw (ex-info "Migration failed"
                      {:type :migration-failed
                       :error (.getMessage e)}
                      e)))))

(defn rollback
  "Rolls back the last applied migration.

   Returns:
     nil

   Throws:
     Exception if rollback fails"
  []
  (log/info "Rolling back last database migration...")
  (try
    (let [config (get-migration-config)]
      (migratus/rollback config)
      (log/info "Database rollback completed successfully"))
    (catch Exception e
      (rethrow-config-conflict! e)
      (log/error e "Database rollback failed")
      (throw (ex-info "Rollback failed"
                      {:type :migration-failed
                       :error (.getMessage e)}
                      e)))))

(defn rollback-until-just-after
  "Rolls back to specific migration (exclusive).

   Args:
     migration-id: Migration ID (e.g., 20241203120000)

   Returns:
     nil"
  [migration-id]
  (log/info "Rolling back to migration" {:migration-id migration-id})
  (try
    (let [config (get-migration-config)]
      (migratus/rollback-until-just-after config migration-id)
      (log/info "Database rollback to migration completed" {:migration-id migration-id}))
    (catch Exception e
      (rethrow-config-conflict! e)
      (log/error e "Database rollback to migration failed" {:migration-id migration-id})
      (throw (ex-info "Rollback to migration failed"
                      {:type :migration-failed
                       :error (.getMessage e)
                       :migration-id migration-id}
                      e)))))

(defn pending-list
  "Lists all pending migrations.

   Returns:
     Vector of migration IDs"
  []
  (try
    (let [config (get-migration-config)
          pending (migratus/pending-list config)]
      (log/info "Found pending migrations" {:count (count pending)})
      pending)
    (catch Exception e
      (rethrow-config-conflict! e)
      (log/error e "Failed to list pending migrations")
      [])))

(defn migration-status
  "Gets the current migration status.

   Returns:
     Map with:
     - :applied - List of applied migration IDs
     - :total-applied - Count of applied migrations
     - :pending - List of pending migration IDs
     - :total-pending - Count of pending migrations"
  []
  (try
    (let [config (get-migration-config)
          applied (migratus/completed-list config)
          pending (migratus/pending-list config)]
      {:applied (vec applied)
       :total-applied (count applied)
       :pending (vec pending)
       :total-pending (count pending)})
    (catch Exception e
      (rethrow-config-conflict! e)
      (log/error e "Failed to get migration status")
      {:applied []
       :total-applied 0
       :pending []
       :total-pending 0
       :error (.getMessage e)})))

(defn create-migration
  "Creates a new migration file pair (up and down).

   Args:
     name: Migration name (e.g., 'add-user-table')

   Returns:
     Map with :up and :down file paths

   Note: This creates timestamped migration files in migrations/ directory"
  [name]
  (log/info "Creating new migration" {:name name})
  (try
    ;; Only when nothing already claims the name. If something does, it wins
    ;; regardless, so creating an empty `migrations/` beside it would change
    ;; nothing and leave a stray directory in a layout that is working — this
    ;; repository is exactly that layout, with 12 migrations under resources/
    ;; and no SQL in the project root.
    ;;
    ;; Where it does apply, it is what stops a fresh project from acquiring the
    ;; split: migratus falls back to `resources/migrations` before `migrations/`
    ;; (its `default-migration-parent` is "resources/"), so without this the
    ;; first migration created that directory and landed there, while the
    ;; scaffolder kept writing to the project root (BOU-274).
    ;;
    ;; A split that already exists is refused before this, by the check in
    ;; `get-migration-config` below.
    (when-not (with-resolved-dir #(capturing-source % project-migration-dir))
      (ensure-project-migration-dir!))
    (let [config (create-config (get-migration-config))]
      (migratus/create config name)
      (log/info "Migration files created" {:name name})
      {:success true
       :message (format "Created migration files for: %s" name)
       ;; Computed from the resolved source, not the literal "migrations/" this
       ;; used to return: the CLI prints this value as "Migration files created
       ;; in: …" and then tells the user to go edit the files there. Resolved
       ;; after the mkdir above, which may have just created the directory that
       ;; now answers to the name.
       :directory (create-destination)})
    (catch Exception e
      (rethrow-config-conflict! e)
      (log/error e "Failed to create migration" {:name name})
      (throw (ex-info "Migration creation failed"
                      {:type :migration-failed
                       :error (.getMessage e)
                       :name name}
                      e)))))

(defn reset
  "Resets the database by rolling back all migrations and re-applying them.

   WARNING: This is destructive! Use only in development.

   Returns:
     nil"
  []
  (log/warn "Resetting database - rolling back all migrations and re-applying")
  (try
    (let [config (get-migration-config)]
      (migratus/reset config)
      (log/info "Database reset completed"))
    (catch Exception e
      (rethrow-config-conflict! e)
      (log/error e "Database reset failed")
      (throw (ex-info "Database reset failed"
                      {:type :migration-failed
                       :error (.getMessage e)}
                      e)))))

(defn init
  "Initializes the migration system by creating the schema_migrations table.

   Returns:
     nil"
  []
  (log/info "Initializing migration system")
  (try
    (let [config (get-migration-config)]
      (migratus/init config)
      (log/info "Migration system initialized"))
    (catch Exception e
      (rethrow-config-conflict! e)
      (log/error e "Migration system initialization failed")
      (throw (ex-info "Migration init failed"
                      {:type :migration-failed
                       :error (.getMessage e)}
                      e)))))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn print-status
  "Prints the current migration status in a human-readable format.

   Returns:
     nil (prints to stdout)"
  []
  (let [status (migration-status)]
    (println "\n=== Database Migration Status ===")
    (println (format "Applied migrations: %d" (:total-applied status)))
    (println (format "Pending migrations: %d" (:total-pending status)))

    (when (seq (:applied status))
      (println "\nApplied:")
      (doseq [id (:applied status)]
        (println (format "  ✓ %s" id))))

    (when (seq (:pending status))
      (println "\nPending:")
      (doseq [id (:pending status)]
        (println (format "  ○ %s" id))))

    (when (:error status)
      (println "\nError:" (:error status)))

    (println "================================\n")))

(defn auto-migrate
  "Automatically runs pending migrations on application startup.

   This function is safe to call on every startup - it only runs
   pending migrations and is idempotent.

   Returns:
     true if migrations ran successfully, false otherwise"
  []
  (try
    (let [status (migration-status)
          pending-count (:total-pending status)]
      (if (pos? pending-count)
        (do
          (log/info "Auto-migration: Running pending migrations" {:count pending-count})
          (migrate)
          true)
        (do
          (log/info "Auto-migration: No pending migrations")
          true)))
    (catch Exception e
      (rethrow-config-conflict! e)
      (log/error e "Auto-migration failed")
      false)))
