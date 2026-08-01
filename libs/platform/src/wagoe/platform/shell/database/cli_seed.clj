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
    (println "   snake_case on the way into the database."))
  (println))

(defn -main
  [& args]
  (let [path   (or (first args) seed/default-seed-path)
        result (seed/run-seed! path)]
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
