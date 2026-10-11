(ns wagoe.workflow.shell.entity-uuid
  "workflow_instances.entity_uuid: entity_id as a UUID, NULL when it is not one
   (BOU-589). entity_id stays text, since not every entity is keyed by a UUID;
   this generated column is what an entity table keyed by one joins on:

     JOIN workflow_instances w ON w.entity_type = 'invoice' AND w.entity_uuid = i.id

   Idempotent. On PostgreSQL the column is stored, so adding it rewrites the
   table once; on SQLite, which has no uuid type, it is virtual and CHAR(36)."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as protocols])
  (:import [java.sql Connection]))

(def ^:private index-name "idx_workflow_instances_entity_uuid")

(def ^:private uuid-regex
  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

(def ^:private uuid-glob
  (let [hex "[0-9a-fA-F]"]
    (str/join "-" (for [n [8 4 4 4 12]] (apply str (repeat n hex))))))

(def ^:private generated-as
  "What follows the type: the expression is engine SQL no adapter method covers."
  {:postgresql (str "GENERATED ALWAYS AS (CASE WHEN entity_id ~ '" uuid-regex "'"
                    " THEN entity_id::uuid END) STORED")
   :h2         (str "GENERATED ALWAYS AS (CASE WHEN REGEXP_LIKE(entity_id, '" uuid-regex "')"
                    " THEN CAST(entity_id AS UUID) END)")
   :mysql      (str "GENERATED ALWAYS AS (CASE WHEN REGEXP_LIKE(entity_id, '" uuid-regex "')"
                    " THEN entity_id END) STORED")
   ;; Only a virtual column may be added on SQLite.
   :sqlite     (str "GENERATED ALWAYS AS (CASE WHEN entity_id GLOB '" uuid-glob "'"
                    " THEN entity_id END) VIRTUAL")})

(defn present?
  "Whether workflow_instances has entity_uuid."
  [datasource]
  (with-open [^Connection c (jdbc/get-connection datasource)]
    (let [md (.getMetaData c)]
      (boolean
       (some (fn [[t col]]
               (with-open [rs (.getColumns md nil nil t col)] (.next rs)))
             [["workflow_instances" "entity_uuid"] ["WORKFLOW_INSTANCES" "ENTITY_UUID"]])))))

(defn ensure-entity-uuid!
  "Add workflow_instances.entity_uuid and its index, unless they are there."
  [connectable]
  (let [ctx     (db/context-of connectable)
        adapter (:adapter ctx)]
    (db/add-column-if-not-exists! ctx :workflow_instances :entity_uuid
                                  (str (protocols/column-type adapter :uuid) " "
                                       (generated-as (protocols/engine adapter))))
    (db/create-index-if-not-exists! ctx index-name :workflow_instances [:entity_uuid])))

(defn up
  "Migratus entry point."
  [config]
  (ensure-entity-uuid! (db/migration-connectable config)))

(defn down
  "Migratus entry point."
  [config]
  (let [ctx (db/context-of (db/migration-connectable config))]
    (db/drop-index-if-exists! ctx index-name :workflow_instances)
    (db/drop-column-if-exists! ctx :workflow_instances :entity_uuid)))
