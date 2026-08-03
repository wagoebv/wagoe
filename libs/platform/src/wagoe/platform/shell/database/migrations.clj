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
            [wagoe.platform.shell.adapters.database.config :as db-config]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
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
                      {:resource migration-manifest-resource
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
  (try
    (let [db-config (db-config/get-active-db-config)]
      (log/info "Loading migration configuration" {:database (:database-type db-config)})
      (create-migratus-config db-config))
    (catch Exception e
      (log/error e "Failed to load database configuration for migrations")
      (throw (ex-info "Migration configuration failed"
                      {:error (.getMessage e)}
                      e)))))

;; =============================================================================
;; Migration Operations
;; =============================================================================

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
      (log/error e "Database migration failed")
      (throw (ex-info "Migration failed"
                      {:error (.getMessage e)}
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
      (log/error e "Database rollback failed")
      (throw (ex-info "Rollback failed"
                      {:error (.getMessage e)}
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
      (log/error e "Database rollback to migration failed" {:migration-id migration-id})
      (throw (ex-info "Rollback to migration failed"
                      {:error (.getMessage e)
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
    (let [config (create-config (get-migration-config))]
      (migratus/create config name)
      (log/info "Migration files created" {:name name})
      {:success true
       :message (format "Created migration files for: %s" name)
       :directory "migrations/"})
    (catch Exception e
      (log/error e "Failed to create migration" {:name name})
      (throw (ex-info "Migration creation failed"
                      {:error (.getMessage e)
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
      (log/error e "Database reset failed")
      (throw (ex-info "Database reset failed"
                      {:error (.getMessage e)}
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
      (log/error e "Migration system initialization failed")
      (throw (ex-info "Migration init failed"
                      {:error (.getMessage e)}
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
      (log/error e "Auto-migration failed")
      false)))
