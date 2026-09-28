(ns wagoe.platform.shell.database.seed
  "Loads a seed file into the active database.

   The pure parts — validation, kebab->snake conversion, plan building — live in
   `wagoe.platform.core.database.seed`. This namespace owns the I/O: reading the
   file, acquiring the datasource, and executing the inserts."
  (:require [wagoe.platform.core.database.seed :as core-seed]
            [wagoe.platform.shell.adapters.database.config :as db-config]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [wagoe.config :as config]
            [wagoe.core.utils.type-conversion :as type-conversion])
  (:import [java.sql Connection ResultSet SQLException]
           [java.time Instant]
           [java.util UUID]))

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

(defn hook-summary
  "The lines the seeder prints for what hook `k` returned: a string or strings
   saying what it did (BOU-591). A hook that returns neither is named."
  [k result]
  (cond
    (string? result)     [result]
    (sequential? result) (vec (filter string? result))
    :else                [(str "Ran seed hook " k)]))

(defn run-seed-hooks!
  "Hand `inserted` — {table-name rows} — to the application's seed hooks: the
   components of its system that derive from :wagoe/seed-hook. Only those, and
   what they depend on, are started, and they are halted again. A generated
   module's is how a seeded row with a workflow gets one (BOU-578).

   `system-ns` is the application's system-config namespace: its `ig-config`,
   and its `load-config` when it has one. Returns what the hooks did, as lines
   to print (see `hook-summary`)."
  [system-ns inserted]
  (let [resolve!    (fn [sym]
                      (try (requiring-resolve (symbol system-ns sym))
                           (catch Exception e
                             (throw (ex-info (str "Cannot load " system-ns ", named by --system: " (ex-message e))
                                             {:type :validation-error :system system-ns} e)))))
        ig-config   (or (resolve! "ig-config")
                        (throw (ex-info (str system-ns ", named by --system, defines no ig-config")
                                        {:type :validation-error :system system-ns})))
        load-config (or (resolve! "load-config") config/load-config)
        config      (ig-config (load-config))
        hook-keys   (keys (ig/find-derived config :wagoe/seed-hook))]
    (if (empty? hook-keys)
      []
      (let [system (ig/init config hook-keys)]
        (try
          (vec (mapcat (fn [[k hook]] (hook-summary k (hook inserted)))
                       (ig/find-derived system :wagoe/seed-hook)))
          (finally (ig/halt! system)))))))

(defn- metadata-rows [^ResultSet rs f]
  (with-open [rs rs]
    (loop [out []]
      (if (.next rs) (recur (conj out (f rs))) out))))

(defn- table-info
  "{:columns #{name} :uuid-id? bool} of `table`, or nil when the database does
   not know it. H2 keeps unquoted names upper-case, so that spelling is asked
   for too."
  [^Connection c table]
  (let [md     (.getMetaData c)
        catalog (try (.getCatalog c) (catch Exception _ nil))
        schema (try (.getSchema c) (catch Exception _ nil))
        lc     #(some-> ^String % str/lower-case)
        cols   (fn [t] (into {} (metadata-rows (.getColumns md catalog schema t "%")
                                               #(vector (lc (.getString ^ResultSet % "COLUMN_NAME"))
                                                        (lc (.getString ^ResultSet % "TYPE_NAME"))))))
        [t columns] (some #(let [c (cols %)] (when (seq c) [% c])) [table (str/upper-case table)])]
    (when t
      (let [pk (set (metadata-rows (.getPrimaryKeys md catalog schema t)
                                   #(lc (.getString ^ResultSet % "COLUMN_NAME"))))]
        {:columns  (set (keys columns))
         :uuid-id? (boolean (and (= #{"id"} pk) (some->> (get columns "id") (re-find #"uuid"))))}))))

(defn admin-missing?
  "Whether an application with the user module has no admin: its users table
   is not there (a reset dropped it and nothing has booted since) or has no row
   with the admin role."
  [ds]
  (boolean
   (and (io/resource "wagoe/boot-tables/user.edn")
        (try (nil? (jdbc/execute-one! ds ["SELECT 1 FROM users WHERE role = 'admin'"]))
             (catch SQLException _ true)))))

(defn seed!
  "Insert `data`, validated, into `ds` in one transaction, its symbolic ids
   resolved and its defaults filled in against the tables as they are.

   All-or-nothing on purpose: a half-applied seed leaves a database that looks
   populated but is not, which is harder to notice than an outright failure.

   Returns `{:ok {:tables n :rows n :detail [...] :inserted {...}}}` or
   `{:error {...}}`."
  [ds data]
  (if-let [err (:error (core-seed/validate-seed data))]
    {:error err}
    (try
      (jdbc/with-transaction [tx ds]
        (let [tables   (into {} (keep (fn [[t _]]
                                        (let [n (core-seed/table->name t)]
                                          (some->> (table-info tx n) (vector n)))))
                             (core-seed/entries data))
              resolved (core-seed/resolve-seed data {:tables tables
                                                     :now    (type-conversion/instant->string (Instant/now))
                                                     :new-id #(str (UUID/randomUUID))})]
          (if-let [err (:error resolved)]
            {:error err}
            (let [plan   (core-seed/seed-plan (:ok resolved))
                  detail (mapv (fn [{:keys [table] :as entry}]
                                 {:table table
                                  :rows  (insert-table! tx entry)})
                               plan)]
              {:ok {:tables   (count detail)
                    :rows     (reduce + (map :rows detail))
                    :detail   detail
                    ;; For the seed hooks, which run after the commit.
                    :inserted (into {} (map (juxt :table :rows)) plan)}}))))
      ;; log/debug, not log/error-with-exception: the common failures
      ;; here are user-facing and expected (missing table, bad column,
      ;; constraint violation), and logging the throwable dumps a full
      ;; stack trace over the readable message the CLI prints.
      (catch Exception e
        (log/debug e "Seeding failed")
        {:error {:type    :database-error
                 :message (ex-message e)}}))))

(defn run-seed!
  "Reads `path` and seeds the active database with it.

   Returns `{:ok {:tables n :rows n :detail [...] :admin-missing? bool}}` or
   `{:error {...}}`."
  ([] (run-seed! default-seed-path))
  ([path]
   (let [{:keys [ok error]} (read-seed-file path)]
     (if error
       {:error error}
       (let [ds     (:datasource (db-config/get-active-db-config))
             _      (log/info "Seeding database" {:path path})
             result (seed! ds ok)]
         (if (:error result)
           (assoc-in result [:error :path] path)
           (assoc-in result [:ok :admin-missing?] (admin-missing? ds))))))))
