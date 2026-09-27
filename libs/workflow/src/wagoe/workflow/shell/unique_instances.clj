(ns wagoe.workflow.shell.unique-instances
  "One instance per workflow and entity (BOU-581). Without the unique index two
   lazy starts could both find none and both insert. Idempotent, and refuses by
   name a table that already holds duplicates."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.sql Connection]))

(def ^:private index-name "uq_workflow_instances_entity")

(defn- duplicates [connectable]
  (jdbc/execute! connectable
                 [(str "SELECT workflow_id, entity_type, entity_id, COUNT(*) AS n FROM workflow_instances"
                       " GROUP BY workflow_id, entity_type, entity_id HAVING COUNT(*) > 1")]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn ensure-unique!
  "Put the unique index on workflow_instances (workflow_id, entity_type, entity_id)."
  [connectable]
  (when-let [dups (seq (duplicates connectable))]
    (throw (ex-info (str "workflow_instances has " (count dups) " entities with more than one instance of"
                         " the same workflow, so it cannot take the unique index " index-name ". Keep one"
                         " instance of each (delete the others and their workflow_audit rows), then run"
                         " this again. The first: " (pr-str (vec (take 5 dups))))
                    {:type :conflict :duplicates (vec dups)})))
  (jdbc/execute! connectable [(str "CREATE UNIQUE INDEX IF NOT EXISTS " index-name
                                   " ON workflow_instances (workflow_id, entity_type, entity_id)")]))

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
  (jdbc/execute! (connectable config) [(str "DROP INDEX IF EXISTS " index-name)]))
