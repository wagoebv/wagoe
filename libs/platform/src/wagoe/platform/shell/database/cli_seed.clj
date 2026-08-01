(ns wagoe.platform.shell.database.cli-seed
  "CLI entry point for database seeding.

   Usage:
     clojure -M -m wagoe.platform.shell.database.cli-seed [path]

   Defaults to resources/seeds/dev.edn. Invoked by `bb db:seed`, which cannot
   open a JDBC connection itself (libs/tools is pure Babashka), so it shells out
   here — the same arrangement `bb migrate` uses for cli-migrations."
  (:require [wagoe.platform.shell.database.seed :as seed])
  (:gen-class))

(defn- print-error
  [{:keys [type message path]}]
  (println)
  (println "❌ Seeding failed")
  (println (str "   " message))
  (when (= type :not-found)
    (println)
    (println "   Create one to get started, for example:")
    (println (str "     " (or path seed/default-seed-path)))
    (println)
    (println "     {:tasks [{:title \"Try the admin UI\" :done false}")
    (println "              {:title \"Read AGENTS.md\"   :done true}]}")
    (println)
    (println "   Table and column names are kebab-case; they are converted to")
    (println "   snake_case on the way into the database.")
    (println)
    (println "   Tables are inserted in the order written, so list parents first.")
    (println "   Past 8 tables an EDN map stops preserving that order — use the")
    (println "   ordered form for anything larger:")
    (println)
    (println "     [[:users [{:email \"admin@example.com\"}]]")
    (println "      [:tasks [{:title \"Owned by that user\" :user-id 1}]]]"))
  (println))

(def ^:private seedable-envs
  "Environments where inserting seed data is safe by default.

   An allowlist, not a denylist. `bb db:reset` refuses a fixed set of names
   (prod/acc/production), which lets an unrecognised environment like
   \"staging\" through. Seeding writes rows into whatever database the active
   config resolves to, so anything not known to be disposable is refused."
  #{"dev" "development" "test" "local"})

(defn- refuse-environment
  [env]
  (println)
  (println "❌ Refusing to seed the" env "environment")
  (println)
  (println "   Seeding inserts rows into the database the active config resolves")
  (println "   to. In" env "that is not a disposable database.")
  (println)
  (println "   If this is genuinely intended, be explicit:")
  (println "     clojure -M:seed <path-to-seed-file> --force")
  (println))

(defn- seed-and-report
  [path]
  (let [result (seed/run-seed! path)]
    (if-let [err (:error result)]
      (do (print-error err)
          (System/exit 1))
      (let [{:keys [tables rows detail]} (:ok result)]
        (println)
        (println "✅ Seeded" rows "row(s) across" tables "table(s)")
        (doseq [{:keys [table rows]} detail]
          (println (str "   " table ": " rows)))
        (println)
        (System/exit 0)))))

(defn -main
  [& args]
  (let [force?  (some #{"--force"} args)
        path    (or (first (remove #{"--force"} args)) seed/default-seed-path)
        env     (or (System/getenv "WAG_ENV") "dev")]
    ;; Guard here rather than only in `bb db:seed`: this entry point is reachable
    ;; directly via `clojure -M:seed`, which bypasses the bb task entirely.
    (when-not (or force? (contains? seedable-envs env))
      (refuse-environment env)
      (System/exit 1))
    (when (and force? (not (contains? seedable-envs env)))
      (println)
      (println "⚠  --force given: seeding the" env "environment on purpose."))
    (seed-and-report path)))
