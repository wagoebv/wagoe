(ns wagoe.workflow.shell.entity-uuid
  "workflow_instances.entity_uuid: entity_id as a UUID, NULL when it is not one
   (BOU-589). entity_id stays text, since not every entity is keyed by a UUID;
   this generated column is what an entity table keyed by one joins on:

     JOIN workflow_instances w ON w.entity_type = 'invoice' AND w.entity_uuid = i.id

   Idempotent. On PostgreSQL the column is stored, so adding it rewrites the
   table once; on SQLite, which has no uuid type, it is virtual text."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as protocols])
  (:import [java.sql Connection]))

(def ^:private index-name "idx_workflow_instances_entity_uuid")

(def ^:private uuid-regex
  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

(def ^:private uuid-glob
  (let [hex "[0-9a-fA-F]"]
    (str/join "-" (for [n [8 4 4 4 12]] (apply str (repeat n hex))))))

(def ^:private add-column
  {:postgresql (str "ALTER TABLE workflow_instances ADD COLUMN IF NOT EXISTS entity_uuid UUID"
                    " GENERATED ALWAYS AS (CASE WHEN entity_id ~ '" uuid-regex "'"
                    " THEN entity_id::uuid END) STORED")
   :h2         (str "ALTER TABLE workflow_instances ADD COLUMN IF NOT EXISTS entity_uuid UUID"
                    " GENERATED ALWAYS AS (CASE WHEN REGEXP_LIKE(entity_id, '" uuid-regex "')"
                    " THEN CAST(entity_id AS UUID) END)")
   ;; CHAR(36), as MySQL migrations store a UUID (BOU-544).
   :mysql      (str "ALTER TABLE workflow_instances ADD COLUMN entity_uuid CHAR(36)"
                    " GENERATED ALWAYS AS (CASE WHEN REGEXP_LIKE(entity_id, '" uuid-regex "')"
                    " THEN entity_id END) STORED")
   ;; No IF NOT EXISTS for a column, and only a virtual one may be added.
   :sqlite     (str "ALTER TABLE workflow_instances ADD COLUMN entity_uuid TEXT"
                    " GENERATED ALWAYS AS (CASE WHEN entity_id GLOB '" uuid-glob "'"
                    " THEN entity_id END) VIRTUAL")})

(defn- sqlite-has-column? [connectable]
  (some #(= "entity_uuid" (:name %))
        (jdbc/execute! connectable ["SELECT name FROM pragma_table_xinfo('workflow_instances')"]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn present?
  "Whether workflow_instances has entity_uuid."
  [datasource]
  (with-open [^Connection c (jdbc/get-connection datasource)]
    (let [md (.getMetaData c)]
      (boolean
       (some (fn [[t col]]
               (with-open [rs (.getColumns md nil nil t col)] (.next rs)))
             [["workflow_instances" "entity_uuid"] ["WORKFLOW_INSTANCES" "ENTITY_UUID"]])))))

(defn- mysql-has? [connectable table-sql]
  (seq (jdbc/execute! connectable [(str "SELECT 1 FROM information_schema." table-sql
                                        " AND table_schema = DATABASE()"
                                        " AND table_name = 'workflow_instances'")])))

(defn ensure-entity-uuid!
  "Add workflow_instances.entity_uuid and its index, unless they are there."
  [connectable]
  (let [ctx  (db/context-of connectable)
        e    (protocols/engine (:adapter ctx))
        ;; MySQL and SQLite have no IF NOT EXISTS on a column.
        has? (case e
               :mysql  (mysql-has? connectable "columns WHERE column_name = 'entity_uuid'")
               :sqlite (sqlite-has-column? connectable)
               false)]
    (when-not has?
      (jdbc/execute! connectable [(add-column e)]))
    (db/create-index-if-not-exists! ctx index-name :workflow_instances [:entity_uuid])))

(defn up
  "Migratus entry point."
  [config]
  (ensure-entity-uuid! (db/migration-connectable config)))

(defn down
  "Migratus entry point."
  [config]
  (let [db (db/migration-connectable config)]
    (if (= :mysql (db/engine-of db))
      ;; MySQL drops a column's index with the column.
      (when (mysql-has? db "columns WHERE column_name = 'entity_uuid'")
        (jdbc/execute! db ["ALTER TABLE workflow_instances DROP COLUMN entity_uuid"]))
      (do (jdbc/execute! db [(str "DROP INDEX IF EXISTS " index-name)])
          (jdbc/execute! db ["ALTER TABLE workflow_instances DROP COLUMN entity_uuid"])))))
