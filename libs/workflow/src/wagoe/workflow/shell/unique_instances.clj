(ns wagoe.workflow.shell.unique-instances
  "One instance per workflow and entity (BOU-581). Without the unique index two
   lazy starts could both find none and both insert. Idempotent, and refuses by
   name a table that already holds duplicates."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.platform.database :as db])
  (:import [java.sql Connection]))

(def ^:private index-name "uq_workflow_instances_entity")

(defn- duplicates [connectable]
  (jdbc/execute! connectable
                 [(str "SELECT workflow_id, entity_type, entity_id, COUNT(*) AS n FROM workflow_instances"
                       " GROUP BY workflow_id, entity_type, entity_id HAVING COUNT(*) > 1")]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn- mysql? [connectable]
  (= :mysql (db/engine-of connectable)))

(defn- mysql-index? [connectable]
  (seq (jdbc/execute! connectable
                      [(str "SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()"
                            " AND table_name = 'workflow_instances' AND index_name = ?") index-name])))

(defn present?
  "Whether workflow_instances has the unique index."
  [datasource]
  (with-open [^Connection c (jdbc/get-connection datasource)]
    (let [md (.getMetaData c)]
      (boolean
       (some (fn [t]
               (with-open [rs (.getIndexInfo md nil nil t true false)]
                 (loop []
                   (when (.next rs)
                     (or (= index-name (some-> (.getString rs "INDEX_NAME") str/lower-case))
                         (recur))))))
             ["workflow_instances" "WORKFLOW_INSTANCES"])))))

(defn ensure-unique!
  "Put the unique index on workflow_instances (workflow_id, entity_type, entity_id)."
  [connectable]
  (when-let [dups (seq (duplicates connectable))]
    (throw (ex-info (str "workflow_instances has " (count dups) " entities with more than one instance of"
                         " the same workflow, so it cannot take the unique index " index-name ". Keep one"
                         " instance of each (delete the others and their workflow_audit rows), then run"
                         " this again. The first: " (pr-str (vec (take 5 dups))))
                    {:type :conflict :duplicates (vec dups)})))
  ;; MySQL has no IF NOT EXISTS on an index (BOU-544).
  (let [on-mysql? (mysql? connectable)]
    (when-not (and on-mysql? (mysql-index? connectable))
      (jdbc/execute! connectable [(str "CREATE UNIQUE INDEX " (when-not on-mysql? "IF NOT EXISTS ") index-name
                                       " ON workflow_instances (workflow_id, entity_type, entity_id)")]))))

(defn- connectable
  "Migratus's open connection, which SQLite needs used rather than a second one."
  [config]
  (let [conn (:conn config)]
    (if (instance? Connection conn) conn (:datasource (:db config)))))

(defn up
  "Migratus entry point."
  [config]
  (ensure-unique! (connectable config)))

(defn down
  "Migratus entry point."
  [config]
  (let [db (connectable config)]
    (if (mysql? db)
      (when (mysql-index? db)
        (jdbc/execute! db [(str "DROP INDEX " index-name " ON workflow_instances")]))
      (jdbc/execute! db [(str "DROP INDEX IF EXISTS " index-name)]))))
