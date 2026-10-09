(ns wagoe.platform.shell.database.reset
  "`db:reset`: drop what the application owns, then migrate.

   What the application owns: every migration's objects, through the down
   migrations; the tables libraries create at boot, as each declares in
   `wagoe/boot-tables/<lib>.edn`; `schema_migrations`; and the schema of each
   tenant in the `tenants` table. Nothing else in the database is touched, and
   when anything else depends on what would go, nothing is dropped (BOU-585)."
  (:require [wagoe.platform.shell.adapters.database.config :as db-config]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.shell.database.migrations :as migrations]
            [migratus.core :as migratus]
            [migratus.migrations :as migratus-migrations]
            [migratus.utils :as migratus-utils]
            [wagoe.config :as config]
            [aero.core :as aero]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
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

(defn- applied-ids
  "The ids in schema_migrations, as strings; empty when it does not exist."
  [^Connection c tables]
  (if-let [t (get tables "schema_migrations")]
    (with-open [s (.createStatement c)]
      (set (rows (.executeQuery s (str "SELECT id FROM " t)) #(str (.getObject ^ResultSet % 1)))))
    #{}))

(defn- migration-objects
  "The lower-case names of the tables and views the applied migrations
   create: their down migrations drop them, so they do not block a reset. A
   migration not yet applied owns nothing, whatever it would create."
  [config applied]
  (->> (migratus-migrations/find-migrations (migratus-utils/get-migration-dir config)
                                            (migratus-utils/get-exclude-scripts config)
                                            nil)
       (filter #(contains? applied (str (key %))))
       (map val)
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

(defn- profile-sources
  "[source value] for each place a profile can be named that is set."
  []
  (filter second [["-Denv" (System/getProperty "env")]
                  ["WAG_ENV" (db-config/getenv "WAG_ENV")]
                  ["ENV" (db-config/getenv "ENV")]
                  ["ENVIRONMENT" (db-config/getenv "ENVIRONMENT")]]))

(defn- check-profile!
  "The profile to reset, or a refusal. Every source that names one must name
   dev, test or acc — one prod anywhere refuses — and one must be named: the
   built-in default is not a choice anybody made."
  []
  (let [sources (profile-sources)
        bad     (remove #(db-config/resettable-environment? (config/normalize-env (second %))) sources)]
    (cond
      (empty? sources)
      (refuse! :forbidden "No profile is named. Set WAG_ENV to dev, test or acc, or pass --env dev." {})

      (seq bad)
      (refuse! :forbidden (str "Refusing to reset: " (str/join ", " (map (fn [[k v]] (str k "=" (pr-str v))) bad))
                               " is not dev, test or acc. " db-config/reset-refusal)
               {:sources (into {} bad)})

      :else (db-config/detect-environment))))

(defn prod-config-resource []
  (io/resource "conf/prod/config.edn"))

(defn- identity-of
  "Where the database of db-config `c` is: adapter, host, port and name. Every
   loopback name is one host, so localhost and 127.0.0.1 compare equal."
  [c]
  (when c
    (let [a (:adapter c)
          h (some-> (:host c) str lc)]
      {:adapter a
       :host    (if (and h (loopback? h)) :loopback h)
       :port    (str (or (:port c) (case a :postgresql 5432 :mysql 3306 nil)))
       :db      (or (:name c)
                    (some-> (:database-path c) str io/file .getCanonicalPath))})))

(defn- active-db-config
  "The db-config of the first database in `config`'s :active."
  [config]
  (when-let [[k v] (first (filter (fn [[k _]] (and (keyword? k) (= "wagoe" (namespace k))
                                                   (#{"postgresql" "sqlite" "mysql" "h2"} (name k))))
                                  (:active config)))]
    (db-config/config->db-config k v)))

(defn- read-leniently
  "`url` read with each #env unset — a developer's machine has no prod
   secrets — and #long of nothing nothing, for when Aero cannot."
  [url]
  (edn/read-string {:readers {'env  (constantly nil)
                              'or   #(first (remove nil? %))
                              'long #(cond (number? %) (long %) (string? %) (parse-long %) :else nil)}
                    :default (fn [_ v] v)}
                   (slurp url)))

(defn- prod-identity [url]
  (identity-of
   (active-db-config
    (try (aero/read-config url)
         (catch Exception _
           (try (read-leniently url)
                (catch Exception e
                  (refuse! :forbidden (str "Cannot read conf/prod/config.edn, so cannot tell prod's database from"
                                           " this one: " (ex-message e) ". Fix the file, or move it aside to reset.")
                           {}))))))))

(defn- check-not-prod!
  "Refuse when `env` resolves to the database prod's config does."
  [env]
  (when-let [url (and (not= "prod" env) (prod-config-resource))]
    (let [prod (prod-identity url)]
      (when (and prod (= prod (identity-of (some-> (db-config/get-active-db-configs env) first val))))
        (refuse! :forbidden (str "Refusing to reset: the " env " profile resolves to prod's database. "
                                 db-config/reset-refusal)
                 {:same-as "prod"})))))

(def ^:private pg-dependents-sql
  "Objects that depend on a to-be-dropped tenant schema, anything in one, or a
   to-be-dropped table, and are neither in those schemas nor on those tables
   or on a table an applied migration made. pg_depend records every kind —
   keys, views, rules, column types, defaults, functions, triggers, policies —
   where the JDBC metadata knows keys and views only."
  "WITH ns AS (SELECT oid FROM pg_namespace WHERE nspname = ANY(?)),
        here AS (SELECT oid FROM pg_namespace WHERE nspname = current_schema()),
        rel AS (SELECT oid FROM pg_class WHERE relnamespace IN (SELECT oid FROM here) AND relname = ANY(?)),
        ours AS (SELECT oid FROM rel
                 UNION SELECT oid FROM pg_class WHERE relnamespace IN (SELECT oid FROM here) AND relname = ANY(?)),
        refs AS (SELECT DISTINCT d.classid, d.objid, d.objsubid FROM pg_depend d
                 WHERE d.deptype = 'n' AND (
                   (d.refclassid = 'pg_namespace'::regclass AND d.refobjid IN (SELECT oid FROM ns))
                   OR (d.refclassid = 'pg_class'::regclass
                       AND (d.refobjid IN (SELECT oid FROM rel)
                            OR d.refobjid IN (SELECT oid FROM pg_class WHERE relnamespace IN (SELECT oid FROM ns))))
                   OR (d.refclassid = 'pg_type'::regclass
                       AND d.refobjid IN (SELECT oid FROM pg_type WHERE typnamespace IN (SELECT oid FROM ns)))
                   OR (d.refclassid = 'pg_proc'::regclass
                       AND d.refobjid IN (SELECT oid FROM pg_proc WHERE pronamespace IN (SELECT oid FROM ns))))),
        owned AS (SELECT r.*, CASE r.classid
                    WHEN 'pg_rewrite'::regclass THEN (SELECT ev_class FROM pg_rewrite WHERE oid = r.objid)
                    WHEN 'pg_constraint'::regclass THEN (SELECT NULLIF(conrelid, 0) FROM pg_constraint WHERE oid = r.objid)
                    WHEN 'pg_trigger'::regclass THEN (SELECT tgrelid FROM pg_trigger WHERE oid = r.objid)
                    WHEN 'pg_attrdef'::regclass THEN (SELECT adrelid FROM pg_attrdef WHERE oid = r.objid)
                    WHEN 'pg_policy'::regclass THEN (SELECT polrelid FROM pg_policy WHERE oid = r.objid)
                    WHEN 'pg_class'::regclass THEN r.objid END AS rel
                  FROM refs r),
        located AS (SELECT o.*, COALESCE(
                      (SELECT relnamespace FROM pg_class WHERE oid = o.rel),
                      CASE o.classid
                        WHEN 'pg_proc'::regclass THEN (SELECT pronamespace FROM pg_proc WHERE oid = o.objid)
                        WHEN 'pg_type'::regclass THEN (SELECT typnamespace FROM pg_type WHERE oid = o.objid)
                        WHEN 'pg_constraint'::regclass THEN (SELECT connamespace FROM pg_constraint WHERE oid = o.objid)
                      END) AS nsp
                    FROM owned o)
   SELECT DISTINCT pg_describe_object(classid, objid, objsubid) FROM located
   WHERE NOT COALESCE(nsp IN (SELECT oid FROM ns), false)
     AND NOT COALESCE(rel IN (SELECT oid FROM ours), false)")

(defn- pg-blockers [^Connection c tenants tables ours]
  (with-open [ps (.prepareStatement c pg-dependents-sql)]
    (.setArray ps 1 (.createArrayOf c "text" (into-array String tenants)))
    (.setArray ps 2 (.createArrayOf c "text" (into-array String tables)))
    (.setArray ps 3 (.createArrayOf c "text" (into-array String ours)))
    (rows (.executeQuery ps) #(.getString ^ResultSet % 1))))

(defn plan
  "What a reset of the active database would drop. Throws :forbidden outside
   dev, test and acc, or on another machine without `allow-remote?`, and
   :conflict when something the reset does not own depends on what it would
   drop — before anything is dropped."
  [{:keys [allow-remote?]}]
  ;; Before the config is read or a connection opened.
  (let [env (check-profile!)]
    (check-not-prod! env)
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
              migrated (migration-objects config (applied-ids c existing))
              ours     (into (set (map lc tables)) migrated)
              owned?   (fn [s t] (or (tenant? (lc s))
                                     (and (= (lc s) (lc schema)) (contains? ours (lc t)))))
              outside? (fn [s _] (tenant? (lc s)))
              engine   (db-factory/engine-of-product (.getDatabaseProductName md))
              found    (if (= :postgresql engine)
                         (pg-blockers c tenants tables (keep existing migrated))
                         (concat
                          (mapcat #(blockers c md catalog schema % owned?) tables)
                          (for [ts tenants
                                t  (vals (table-names md catalog ts))
                                b  (blockers c md catalog ts t outside?)]
                            b)))]
          (when (seq found)
            (refuse! :conflict (str "Nothing was dropped. These depend on what a reset would drop:\n  "
                                    (str/join "\n  " (distinct found)))
                     {:blockers (vec (distinct found))}))
          {:env            env
           :host           (or h "this machine")
           :database       (or catalog (last (str/split url #"[:/]")))
           :schema         schema
           :engine         engine
           :tables         (drop-order md catalog schema tables)
           :tenant-schemas tenants
           :config         config})))))

(defn- quote-ident [engine ^String ident]
  (if (= :mysql engine)
    (str "`" (str/replace ident "`" "``") "`")
    (str "\"" (str/replace ident "\"" "\"\"") "\"")))

(defn drop-table-sql [q t] (str "DROP TABLE IF EXISTS " (q t)))

(defn- drop-owned!
  "Drop the tenant schemas, then the tables, in one transaction where DDL is
   transactional (PostgreSQL, SQLite): a failure leaves all of them."
  [^javax.sql.DataSource ds engine tx? tables tenant-schemas]
  (let [q #(quote-ident engine %)]
    (with-open [c (.getConnection ds)]
      (when tx? (.setAutoCommit c false))
      (try
        (with-open [s (.createStatement c)]
          ;; CASCADE reaches only into the tenant's own schema: `plan` refused
          ;; anything outside it that depends on anything inside.
          (doseq [ts tenant-schemas]
            (log/info "Dropping tenant schema" {:schema ts})
            (.execute s (str "DROP SCHEMA " (q ts) " CASCADE")))
          (doseq [t tables]
            (log/info "Dropping table" {:table t})
            (.execute s (drop-table-sql q t))))
        (when tx? (.commit c))
        (catch Exception e
          (when tx? (.rollback c))
          (throw e))
        (finally
          (when tx? (.setAutoCommit c true)))))))

(defn execute!
  "Carry out `plan`: roll every migration back, drop the tenant schemas, the
   boot tables and schema_migrations, then migrate. A failure names the steps
   that finished and how to recover."
  [{:keys [config engine tables tenant-schemas]}]
  (let [ds       (get-in config [:db :datasource])
        tx?      (some-> engine db-factory/transactional-ddl?)
        done     (atom [])
        recovery "Run `bb migrate up` to put the schema back."
        step     (fn [label f]
                   (try (f) (swap! done conj label)
                        (catch Exception e
                          (throw (ex-info (str "Reset stopped at: " label ". " (ex-message e)
                                               "\n   Finished: " (if (seq @done) (str/join ", " @done) "nothing")
                                               (when tx?
                                                 "\n   The drops ran in one transaction, so none of them happened.")
                                               "\n   " recovery)
                                          {:type :reset-failed :done @done :failed label :recovery recovery}
                                          e)))))]
    (step "roll back every migration" #(migratus/rollback-until-just-after config 0))
    (step "drop the tenant schemas and tables" #(drop-owned! ds engine tx? tables tenant-schemas))
    (step "apply the migrations" #(migratus/migrate (migrations/get-migration-config)))
    nil))

(defn reset-database!
  "Plan and carry out a reset without asking. The CLI shows the plan and asks
   first; this is for code that already has."
  ([] (reset-database! {}))
  ([opts] (execute! (plan opts))))
