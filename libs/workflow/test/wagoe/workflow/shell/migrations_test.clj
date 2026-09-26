(ns wagoe.workflow.shell.migrations-test
  "Workflow's tables come from its migrations, with no system boot.

   They used to be created only by the `:wagoe/workflow-db-schema` component,
   so `bb migrate up` followed by `bb db:seed` on a project that had never
   booted failed on a missing `workflow_instances` (BOU-502)."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [support.embedded-pg :as epg]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.platform.shell.database.migrations :as mig]
            [wagoe.platform.shell.database.timestamp-tz :as timestamp-tz]
            [wagoe.workflow.ports :as ports]
            [wagoe.workflow.shell.module-wiring]
            [wagoe.workflow.shell.persistence :as persistence])
  (:import [java.io File]
           [java.sql Connection]
           [java.time Instant]
           [java.time.temporal ChronoUnit]
           [java.util UUID]
           [javax.sql DataSource]))

(defn- workflow-dirs []
  (filterv #(str/includes? % "workflow") (mig/discover-migration-dirs)))

(defn- h2
  "A fresh in-memory H2 with the options the framework's H2 adapter uses."
  []
  (jdbc/get-datasource
   {:jdbcUrl (str "jdbc:h2:mem:wf_mig_" (System/nanoTime)
                  ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")}))

(defn- sqlite []
  (let [f (File/createTempFile "wf_mig" ".db")]
    (.deleteOnExit f)
    (jdbc/get-datasource {:dbtype "sqlite" :dbname (.getPath f)})))

(def ^:private pg-server (atom nil))

(use-fixtures :once
  (fn [f]
    (reset! pg-server (epg/start!))
    (try (f) (finally (epg/stop! @pg-server)))))

(def ^:private pg-session-zone
  "Not UTC, so a conversion that read the session zone would move the value."
  "America/New_York")

(defn- in-session-zone
  "`ds`, with every connection's session zone set to `pg-session-zone`. pgjdbc
   sends the JVM's zone at startup, which overrides a `-c TimeZone` option."
  ^DataSource [^DataSource ds]
  (reify DataSource
    (getConnection [_]
      (let [c (.getConnection ds)]
        (jdbc/execute! c [(str "SET TIME ZONE '" pg-session-zone "'")])
        c))))

(defn- postgres
  "A fresh database on the embedded PostgreSQL."
  []
  (let [db (str "wf_mig_" (System/nanoTime))]
    (jdbc/execute! (epg/datasource @pg-server) [(str "CREATE DATABASE " db)])
    (in-session-zone
     (jdbc/get-datasource {:dbtype   "postgresql"
                           :host     "localhost"
                           :port     (epg/port @pg-server)
                           :dbname   db
                           :user     "postgres"
                           :password "postgres"}))))

(defn- engines []
  [[:h2 (h2)] [:sqlite (sqlite)] [:postgresql (postgres)]])

(defn- migrate! [ds]
  (migratus/migrate {:store         :database
                     :migration-dir (workflow-dirs)
                     :db            {:datasource ds}}))

(defn- column-type
  "The engine's type name for `table.column`, or nil when there is no such column."
  [ds table column]
  (with-open [^Connection c (jdbc/get-connection ds)]
    (let [md (.getMetaData c)]
      (some (fn [[t col]]
              (with-open [rs (.getColumns md nil nil t col)]
                (when (.next rs) (.getString rs "TYPE_NAME"))))
            [[table column] [(str/upper-case table) (str/upper-case column)]]))))

(def ^:private timestamp-columns
  [["workflow_instances" "created_at"]
   ["workflow_instances" "updated_at"]
   ["workflow_audit" "occurred_at"]])

(defn- now-ms
  "Now, at the millisecond precision every engine here keeps."
  []
  (.truncatedTo (Instant/now) ChronoUnit/MILLIS))

(defn- instance [created]
  {:id            (UUID/randomUUID)
   :workflow-id   :order-flow
   :entity-type   :order
   :entity-id     (UUID/randomUUID)
   :current-state :pending
   :created-at    created
   :updated-at    created
   :metadata      {:source "test"}})

(defn- audit-entry [inst occurred]
  {:id          (UUID/randomUUID)
   :instance-id (:id inst)
   :workflow-id :order-flow
   :entity-type :order
   :entity-id   (:entity-id inst)
   :transition  :ship
   :from-state  :pending
   :to-state    :shipped
   :actor-id    (UUID/randomUUID)
   :actor-roles [:admin]
   :context     {:note "x"}
   :occurred-at occurred})

(defn- assert-store-round-trips [ds]
  (let [store (persistence/create-workflow-store ds)
        inst  (instance (now-ms))
        entry (audit-entry inst (now-ms))]
    (ports/save-instance! store inst)
    (ports/save-audit-entry! store entry)
    (is (= inst (ports/find-instance store (:id inst))))
    (is (= [entry] (ports/find-audit-log store (:id inst))))
    (let [later (ports/update-instance-state! store (:id inst) :shipped)]
      (is (= :shipped (:current-state later)))
      (is (inst? (:updated-at later))))))

(deftest ^:integration migrations-alone-create-the-tables
  (testing "the runner sees workflow's migration directory"
    (is (seq (workflow-dirs))
        "no workflow directory — the manifest is missing, so the runner never reads it"))

  (doseq [[engine ds] (engines)]
    (testing (str (name engine) ", fresh database, migrations only")
      (migrate! ds)
      (doseq [table ["workflow_instances" "workflow_audit"]]
        (is (some? (column-type ds table "id")) (str table " was not created")))
      (when-not (= :sqlite engine)
        (doseq [[table column] timestamp-columns]
          (is (timestamp-tz/zone-aware? (column-type ds table column))
              (str table "." column))))
      (assert-store-round-trips ds))))

(def ^:private legacy-boot-ddl
  "What `:wagoe/workflow-db-schema` created before BOU-502: timestamps as TEXT."
  ["CREATE TABLE workflow_instances (
      id TEXT PRIMARY KEY, workflow_id TEXT NOT NULL, entity_type TEXT NOT NULL,
      entity_id TEXT NOT NULL, current_state TEXT NOT NULL,
      created_at TEXT NOT NULL, updated_at TEXT NOT NULL, metadata TEXT)"
   "CREATE TABLE workflow_audit (
      id TEXT PRIMARY KEY, instance_id TEXT NOT NULL REFERENCES workflow_instances(id),
      workflow_id TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL,
      transition TEXT NOT NULL, from_state TEXT NOT NULL, to_state TEXT NOT NULL,
      actor_id TEXT, actor_roles TEXT, context TEXT, occurred_at TEXT NOT NULL)"])

(deftest ^:integration tables-an-older-boot-created-are-converted
  (doseq [[engine ds] (engines)]
    (testing (str (name engine) ": a row written as TEXT survives the migration")
      (doseq [ddl legacy-boot-ddl] (jdbc/execute! ds [ddl]))
      (when (= :postgresql engine)
        (is (= pg-session-zone (:zone (jdbc/execute-one! ds ["SELECT current_setting('TimeZone') AS zone"])))))
      (let [id      (UUID/randomUUID)
            entity  (str (UUID/randomUUID))
            written (Instant/parse "2026-03-11T10:00:00.123Z")
            store   (persistence/create-workflow-store ds)]
        (jdbc/execute! ds ["INSERT INTO workflow_instances
                              (id, workflow_id, entity_type, entity_id, current_state, created_at, updated_at)
                            VALUES (?, 'order-flow', 'order', ?, 'pending', ?, ?)"
                           (str id) entity (str written) (str written)])
        (jdbc/execute! ds ["INSERT INTO workflow_audit
                              (id, instance_id, workflow_id, entity_type, entity_id, transition, from_state, to_state, occurred_at)
                            VALUES (?, ?, 'order-flow', 'order', ?, 'ship', 'pending', 'shipped', ?)"
                           (str (UUID/randomUUID)) (str id) entity (str written)])
        (migrate! ds)
        (when-not (= :sqlite engine)
          (doseq [[table column] timestamp-columns]
            (is (timestamp-tz/zone-aware? (column-type ds table column))
                (str table "." column))))
        (is (= written (:created-at (ports/find-instance store id))))
        (is (= [written] (map :occurred-at (ports/find-audit-log store id))))
        (assert-store-round-trips ds)
        (testing "and converting again changes nothing"
          (is (zero? (timestamp-tz/widen-columns! ds timestamp-columns)))
          (is (nil? (migrate! ds)))
          (is (= written (:created-at (ports/find-instance store id)))))))))

(defn- h2-ctx []
  (factory/db-context
   (factory/h2-config (str "mem:wf_boot_" (System/nanoTime) ";DB_CLOSE_DELAY=-1"))))

(deftest ^:integration boot-creates-the-tables
  (testing ":wagoe/workflow-db-schema, for an installation that boots without migrating"
    (let [ctx (h2-ctx)
          ds  (:datasource ctx)]
      (try
        (ig/init-key :wagoe/workflow-db-schema {:ctx ctx})
        (doseq [[table column] timestamp-columns]
          (is (timestamp-tz/zone-aware? (column-type ds table column))
              (str table "." column)))
        (assert-store-round-trips ds)
        (testing "and a later migrate up has nothing left to change"
          (migrate! ds)
          (assert-store-round-trips ds))
        (finally (factory/close-db-context! ctx))))))

(deftest ^:integration boot-leaves-a-legacy-table-to-migrate-up
  ;; Converting takes an exclusive lock and changes the column type under
  ;; replicas still running the old version, so only `migrate up` does it.
  (let [ctx (h2-ctx)
        ds  (:datasource ctx)]
    (try
      (doseq [ddl legacy-boot-ddl] (jdbc/execute! ds [ddl]))
      (ig/init-key :wagoe/workflow-db-schema {:ctx ctx})
      (doseq [[table column] timestamp-columns]
        (is (not (timestamp-tz/zone-aware? (column-type ds table column)))
            (str table "." column " was converted at boot")))
      (migrate! ds)
      (doseq [[table column] timestamp-columns]
        (is (timestamp-tz/zone-aware? (column-type ds table column))
            (str table "." column)))
      (finally (factory/close-db-context! ctx)))))
