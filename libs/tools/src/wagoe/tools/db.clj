#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/db.clj
;;
;; Database workflow commands for Boundary projects.
;;
;; Usage (via bb.edn task):
;;   bb db:status    # Show database config and migration info
;;   bb db:reset     # Drop and recreate the database
;;   bb db:seed      # Seed database from dev seed file

(ns wagoe.tools.db
  (:require [wagoe.tools.ansi :refer [bold green red yellow dim]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :as process]))

;; =============================================================================
;; Pure helpers
;; =============================================================================

(defn- root-dir [] (System/getProperty "user.dir"))

(defn- config-path []
  (str (root-dir) "/resources/conf/dev/config.edn"))

(defn- migrations-dir []
  (str (root-dir) "/resources/migrations"))

(defn- seed-path []
  (str (root-dir) "/resources/seeds/dev.edn"))

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

(defn- list-migration-files
  "List .sql migration files from the migrations directory, sorted by name."
  [dir]
  (let [d (io/file dir)]
    (when (.exists d)
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName %) ".sql"))
           (sort-by #(.getName %))
           vec))))

;; =============================================================================
;; Subcommands
;; =============================================================================

(defn db-status
  "Show database config and migration info."
  []
  (println)
  (println (bold "Boundary Database Status"))
  (println)

  ;; Read and parse config
  (let [config-file (io/file (config-path))]
    (if-not (.exists config-file)
      (println (red (str "  Config not found: " (config-path))))
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
        (let [mig-dir    (migrations-dir)
              mig-files  (list-migration-files mig-dir)]
          (if-not mig-files
            (println (yellow (str "  No migrations directory found at " mig-dir)))
            (let [count-files (count mig-files)]
              (println (str "  " (bold "Migrations:    ") (green (str count-files))
                            (dim (str " file" (when (not= count-files 1) "s")
                                      " in resources/migrations/"))))
              (when (pos? count-files)
                (println (dim (str "  Latest:        " (.getName (last mig-files)))))))))

        (println)))))

(defn db-reset
  "Drop and recreate the database after confirmation.
   Only operates on the dev environment."
  []
  (let [env (or (System/getenv "WAG_ENV") "dev")]
    (when (contains? #{"prod" "acc" "production"} env)
      (println (red (str "  REFUSED: bb db:reset cannot run in " env " environment.")))
      (println (dim "  This command is only for development use."))
      (System/exit 1)))
  (println)
  (println (bold "Boundary Database Reset"))
  (println (dim "  Environment: dev"))
  (println)
  (println (yellow "  WARNING: This will DROP and recreate the dev database."))
  (println (yellow "  All data will be lost."))
  (println)
  (print "  Continue? [y/N] ")
  (flush)
  (let [answer (str/trim (or (read-line) ""))]
    (if (contains? #{"y" "Y" "yes" "Yes"} answer)
      (do
        (println)
        (println (dim "  Running: clojure -M:migrate reset"))
        (try
          (process/shell "clojure" "-M:migrate" "reset")
          (println (green "  Reset complete."))
          (println)
          (println (dim "  Running: clojure -M:migrate up"))
          (process/shell "clojure" "-M:migrate" "up")
          (println (green "  Migrations applied successfully."))
          (println)
          (catch Exception e
            (println (red (str "  Migration failed: " (.getMessage e))))
            (System/exit 1))))
      (do
        (println)
        (println (dim "  Aborted."))
        (println)))))

(defn db-seed
  "Seed the database from the dev seed file."
  []
  (println)
  (println (bold "Boundary Database Seed"))
  (println)
  (let [seed-file (io/file (seed-path))]
    (if-not (.exists seed-file)
      (do
        (println (yellow "  Seed file not found."))
        (println)
        (println (dim "  To get started, create a seed file at:"))
        (println (dim (str "    " (seed-path))))
        (println)
        (println (dim "  Example content:"))
        (println (dim "    {:users [{:email \"admin@example.com\" :name \"Admin\"}]}"))
        (println))
      (do
        (println (yellow "  Seed file found but seeding is not yet implemented."))
        (println (dim (str "  File: " (seed-path))))
        (println)
        (println (dim "  This feature will be added in a future release."))
        (println (dim "  For now, load seed data manually via the REPL."))
        (println)
        (System/exit 1)))))

;; =============================================================================
;; Help
;; =============================================================================

(defn- print-help []
  (println (bold "bb db:<command>") " — Database workflow commands")
  (println)
  (println "Usage:")
  (println "  bb db:status     Show database type, connection info, and migration count")
  (println "  bb db:reset      Drop and recreate database (with confirmation)")
  (println "  bb db:seed       Seed database from resources/seeds/dev.edn")
  (println))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& args]
  (let [[subcmd & _rest-args] args]
    (case subcmd
      "status" (db-status)
      "reset"  (db-reset)
      "seed"   (db-seed)
      (print-help))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
