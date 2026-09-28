(ns wagoe.platform.shell.database.reset
  "`db:reset`: drop what the application owns, then migrate.

   What the application owns: every migration's objects, through the down
   migrations; the tables libraries create at boot, as each declares in
   `wagoe/boot-tables/<lib>.edn`; `schema_migrations`; and the schema of each
   tenant in the `tenants` table. Nothing else in the database is touched, and
   when anything else depends on what would go, nothing is dropped (BOU-585)."
  (:require [wagoe.platform.shell.adapters.database.config :as db-config]
            [wagoe.platform.shell.database.migrations :as migrations]
            [migratus.core :as migratus]
            [migratus.migrations :as migratus-migrations]
            [migratus.utils :as migratus-utils]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.sql Connection DatabaseMetaData ResultSet SQLException]))

(def ^:private boot-table-dir "wagoe/boot-tables/")

(defn boot-tables
  "The tables libraries create at boot, outside any migration."
  []
  (->> (migrations/classpath-manifests boot-table-dir)
       (mapcat #(:tables (edn/read-string (slurp %))))
       distinct
       vec))

(defn slug->schema-name
  "A tenant's schema. Mirrors wagoe.tenant.core.tenant/slug->schema-name,
   which platform cannot require; a test pins the two together."
  [slug]
  (str "tenant_" (str/replace slug "-" "_")))

(defn- lc [s] (when-not (str/blank? s) (str/lower-case s)))

(defn- rows [^ResultSet rs f]
  (with-open [rs rs]
    (loop [out []]
      (if (.next rs) (recur (conj out (f rs))) out))))

(defn- table-names
  "{lower-case name → name} of the tables in `schema`."
  [^DatabaseMetaData md catalog schema]
  (into {} (rows (.getTables md catalog schema "%" (into-array String ["TABLE"]))
                 #(let [n (.getString ^ResultSet % "TABLE_NAME")] [(lc n) n]))))

(defn- schema-names [^DatabaseMetaData md]
  (try (into {} (rows (.getSchemas md) #(let [n (.getString ^ResultSet % "TABLE_SCHEM")] [(lc n) n])))
       (catch SQLException _ {})))

(defn- migration-objects
  "The lower-case names of the tables and views the migrations create: the
   down migrations drop them, so they do not block a reset."
  [config]
  (->> (migratus-migrations/find-migrations (migratus-utils/get-migration-dir config)
                                            (migratus-utils/get-exclude-scripts config)
                                            nil)
       (tree-seq coll? seq)
       (filter string?)
       (mapcat #(re-seq #"(?i)create\s+(?:or\s+replace\s+)?(?:table|view)\s+(?:if\s+not\s+exists\s+)?[\"`]?(?:\w+[\"`]?\.[\"`]?)?(\w+)" %))
       (map (comp lc second))
       set))

(defn- tenant-schemas
  "The schemas of the tenants in `tenants`, as they exist."
  [^Connection c tables schemas]
  (when-let [t (get tables "tenants")]
    (with-open [s (.createStatement c)]
      (->> (rows (.executeQuery s (str "SELECT slug FROM " t)) #(.getString ^ResultSet % 1))
           (keep #(get schemas (slug->schema-name %)))
           distinct
           vec))))

(defn- fk-dependents [^DatabaseMetaData md catalog schema table]
  (rows (.getExportedKeys md catalog schema table)
        #(vector (.getString ^ResultSet % "FKTABLE_SCHEM") (.getString ^ResultSet % "FKTABLE_NAME")
                 (str "foreign key from " (.getString ^ResultSet % "FKTABLE_SCHEM") "."
                      (.getString ^ResultSet % "FKTABLE_NAME")))))

(defn- view-dependents [^Connection c schema table]
  (try
    (with-open [ps (.prepareStatement c (str "SELECT view_schema, view_name FROM information_schema.view_table_usage"
                                             " WHERE table_schema = ? AND table_name = ?"))]
      (.setString ps 1 schema)
      (.setString ps 2 table)
      (rows (.executeQuery ps)
            #(vector (.getString ^ResultSet % 1) (.getString ^ResultSet % 2)
                     (str "view " (.getString ^ResultSet % 1) "." (.getString ^ResultSet % 2)))))
    ;; SQLite has no information_schema; nothing there can depend on a view.
    (catch SQLException _ [])))

(defn- blockers
  "What depends on `table` in `schema` and is not `owned?`."
  [c md catalog schema table owned?]
  (for [[s t why] (concat (fk-dependents md catalog schema table) (view-dependents c schema table))
        :when (not (owned? s t))]
    (str why " on " (when schema (str schema ".")) table)))

(defn- drop-order
  "`tables` with each one after every table among them that references it."
  [^DatabaseMetaData md catalog schema tables]
  (let [in?  (set (map lc tables))
        refs (into {} (for [t tables]
                        [t (set (filter #(and (in? %) (not= % (lc t)))
                                        (rows (.getImportedKeys md catalog schema t)
                                              #(lc (.getString ^ResultSet % "PKTABLE_NAME")))))]))]
    (loop [left tables out []]
      (if (empty? left)
        out
        (let [referenced (set (mapcat refs left))
              free       (remove #(referenced (lc %)) left)
              step       (if (seq free) free left)]
          (recur (remove (set step) left) (into out step)))))))

(defn- host [^String url]
  (when-let [[_ h] (re-find #"^jdbc:[a-z0-9]+(?::(?:tcp|ssl))?://(?:[^@/]*@)?(\[[^\]]+\]|[^:/;?,]+)" url)]
    (str/replace h #"^\[|\]$" "")))

(defn loopback?
  "Whether `host` is this machine. nil is a file or in-memory database."
  [host]
  (or (nil? host)
      (= "localhost" (lc host))
      (try (.isLoopbackAddress (java.net.InetAddress/getByName host))
           (catch Exception _ false))))

(defn- refuse! [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn plan
  "What a reset of the active database would drop. Throws :forbidden outside
   dev, test and acc, or on another machine without `allow-remote?`, and
   :conflict when something the reset does not own depends on what it would
   drop — before anything is dropped."
  [{:keys [allow-remote?]}]
  (let [env (db-config/detect-environment)]
    ;; Before the config is read or a connection opened.
    (when-not (db-config/resettable-environment? env)
      (refuse! :forbidden (str "Refusing to reset the " (pr-str env) " profile. " db-config/reset-refusal)
               {:env env}))
    (let [config (migrations/rollback-config)
          ^javax.sql.DataSource ds (get-in config [:db :datasource])]
      (with-open [c (.getConnection ds)]
        (let [md       (.getMetaData c)
              url      (.getURL md)
              h        (host url)
              catalog  (.getCatalog c)
              schema   (.getSchema c)
              _        (when-not (or allow-remote? (loopback? h))
                         (refuse! :forbidden (str "Refusing to reset a database on " h
                                                  ", which is not this machine. Pass --allow-remote if it is disposable.")
                                  {:host h}))
              existing (table-names md catalog schema)
              tables   (vec (keep existing (concat (map lc (boot-tables)) ["schema_migrations"])))
              tenants  (tenant-schemas c existing (schema-names md))
              tenant?  (set (map lc tenants))
              ours     (into (set (map lc tables)) (migration-objects config))
              owned?   (fn [s t] (or (tenant? (lc s))
                                     (and (= (lc s) (lc schema)) (contains? ours (lc t)))))
              outside? (fn [s _] (tenant? (lc s)))
              found    (concat
                        (mapcat #(blockers c md catalog schema % owned?) tables)
                        (for [ts tenants
                              t  (vals (table-names md catalog ts))
                              b  (blockers c md catalog ts t outside?)]
                          b))]
          (when (seq found)
            (refuse! :conflict (str "Nothing was dropped. These depend on what a reset would drop:\n  "
                                    (str/join "\n  " (distinct found)))
                     {:blockers (vec (distinct found))}))
          {:env            env
           :host           (or h "this machine")
           :database       (or catalog (last (str/split url #"[:/]")))
           :schema         schema
           :product        (.getDatabaseProductName md)
           :tables         (drop-order md catalog schema tables)
           :tenant-schemas tenants
           :config         config})))))

(defn- quote-ident [^String product ^String ident]
  (if (re-find #"(?i)mysql|mariadb" product)
    (str "`" (str/replace ident "`" "``") "`")
    (str "\"" (str/replace ident "\"" "\"\"") "\"")))

(defn execute!
  "Carry out `plan`: drop the tenant schemas, roll every migration back, drop
   the boot tables and schema_migrations, then migrate."
  [{:keys [config product tables tenant-schemas]}]
  (let [^javax.sql.DataSource ds (get-in config [:db :datasource])
        q #(quote-ident product %)]
    (with-open [c (.getConnection ds)
                s (.createStatement c)]
      ;; CASCADE reaches only into the tenant's own schema: `plan` refused
      ;; anything outside it that depends on what is inside.
      (doseq [ts tenant-schemas]
        (log/info "Dropping tenant schema" {:schema ts})
        (.execute s (str "DROP SCHEMA " (q ts) " CASCADE"))))
    (migratus/rollback-until-just-after config 0)
    (with-open [c (.getConnection ds)
                s (.createStatement c)]
      (doseq [t tables]
        (log/info "Dropping table" {:table t})
        (.execute s (str "DROP TABLE IF EXISTS " (q t)))))
    (migratus/migrate (migrations/get-migration-config))
    nil))

(defn reset-database!
  "Plan and carry out a reset without asking. The CLI shows the plan and asks
   first; this is for code that already has."
  ([] (reset-database! {}))
  ([opts] (execute! (plan opts))))
