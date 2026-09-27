(ns wagoe.platform.shell.database.seed
  "Loads a seed file into the active database.

   The pure parts — validation, kebab->snake conversion, plan building — live in
   `wagoe.platform.core.database.seed`. This namespace owns the I/O: reading the
   file, acquiring the datasource, and executing the inserts."
  (:require [wagoe.platform.core.database.seed :as core-seed]
            [wagoe.platform.shell.adapters.database.config :as db-config]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [wagoe.config :as config]))

(def default-seed-path "resources/seeds/dev.edn")

(defn read-seed-file
  "Reads and parses a seed file.

   Returns `{:ok data}`, or `{:error {...}}` when the file is missing or is not
   readable EDN. A malformed seed file is a user-facing mistake, so the parse
   failure is caught here and turned into a value rather than a stack trace."
  [path]
  (let [f (io/file path)]
    (cond
      (not (.exists f))
      {:error {:type :not-found
               :path path
               :message (str "Seed file not found: " path)}}

      :else
      (try
        {:ok (edn/read-string (slurp f))}
        (catch Exception e
          {:error {:type    :validation-error
                   :path    path
                   :message (str "Seed file is not valid EDN: " (ex-message e))}})))))

(defn- insert-table!
  "Inserts one table's rows inside the caller's transaction. Returns the count."
  [tx {:keys [table rows]}]
  (let [stmt (sql/format {:insert-into (keyword table)
                          :values      rows})]
    (jdbc/execute! tx stmt)
    (count rows)))

(defn run-seed-hooks!
  "Hand `inserted` — {table-name rows} — to the application's seed hooks: the
   components of its system that derive from :wagoe/seed-hook. Only those, and
   what they depend on, are started, and they are halted again. A generated
   module's is how a seeded row with a workflow gets one (BOU-578).

   `system-ns` is the application's system-config namespace: its `ig-config`,
   and its `load-config` when it has one. Returns the number of hooks run."
  [system-ns inserted]
  (let [ig-config   (requiring-resolve (symbol system-ns "ig-config"))
        load-config (or (requiring-resolve (symbol system-ns "load-config")) config/load-config)
        config      (ig-config (load-config))
        hook-keys   (keys (ig/find-derived config :wagoe/seed-hook))]
    (if (empty? hook-keys)
      0
      (let [system (ig/init config hook-keys)]
        (try
          (doseq [[_ hook] (ig/find-derived system :wagoe/seed-hook)]
            (hook inserted))
          (count hook-keys)
          (finally (ig/halt! system)))))))

(defn run-seed!
  "Reads `path`, validates it, and inserts every row in one transaction.

   All-or-nothing on purpose: a half-applied seed leaves a database that looks
   populated but is not, which is harder to notice than an outright failure.

   Returns `{:ok {:tables n :rows n :detail [...]}}` or `{:error {...}}`."
  ([] (run-seed! default-seed-path))
  ([path]
   (let [{:keys [ok error]} (read-seed-file path)]
     (if error
       {:error error}
       (let [{valid :ok verr :error} (core-seed/validate-seed ok)]
         (if verr
           {:error verr}
           (let [plan      (core-seed/seed-plan valid)
                 db-config (db-config/get-active-db-config)
                 ds        (:datasource db-config)]
             (log/info "Seeding database" {:path path :tables (count plan)})
             (try
               (jdbc/with-transaction [tx ds]
                 (let [detail (mapv (fn [{:keys [table] :as entry}]
                                      {:table table
                                       :rows  (insert-table! tx entry)})
                                    plan)]
                   {:ok {:tables   (count detail)
                         :rows     (reduce + (map :rows detail))
                         :detail   detail
                         ;; For the seed hooks, which run after the commit.
                         :inserted (into {} (map (juxt :table :rows)) plan)}}))
               ;; log/debug, not log/error-with-exception: the common failures
               ;; here are user-facing and expected (missing table, bad column,
               ;; constraint violation), and logging the throwable dumps a full
               ;; stack trace over the readable message the CLI prints.
               (catch Exception e
                 (log/debug e "Seeding failed" {:path path})
                 {:error {:type    :database-error
                          :path    path
                          :message (ex-message e)}})))))))))
