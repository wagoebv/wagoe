(ns wagoe.workflow.shell.persistence
  "Database persistence for workflow instances and audit trail.

   Implements IWorkflowStore using next.jdbc + HoneySQL.
   Applies snake_case <-> kebab-case conversion at the DB boundary only."
  (:require [wagoe.platform.database :as db]
            [wagoe.workflow.ports :as ports]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn])
  (:import [java.util UUID]
           [java.time Instant]))

(def ^:private workflow-ddl
  ["CREATE TABLE IF NOT EXISTS workflow_instances (
      id TEXT PRIMARY KEY,
      workflow_id TEXT NOT NULL,
      entity_type TEXT NOT NULL,
      entity_id TEXT NOT NULL,
      current_state TEXT NOT NULL,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      metadata TEXT
    )"
   "CREATE INDEX IF NOT EXISTS idx_workflow_instances_entity
      ON workflow_instances (entity_type, entity_id)"
   "CREATE TABLE IF NOT EXISTS workflow_audit (
      id TEXT PRIMARY KEY,
      instance_id TEXT NOT NULL REFERENCES workflow_instances(id),
      workflow_id TEXT NOT NULL,
      entity_type TEXT NOT NULL,
      entity_id TEXT NOT NULL,
      transition TEXT NOT NULL,
      from_state TEXT NOT NULL,
      to_state TEXT NOT NULL,
      actor_id TEXT,
      actor_roles TEXT,
      context TEXT,
      occurred_at TEXT NOT NULL
    )"
   "CREATE INDEX IF NOT EXISTS idx_workflow_audit_instance_id
      ON workflow_audit (instance_id)"])

(defn initialize-workflow-schema!
  "Initialize database schema for workflow persistence."
  [ctx]
  (log/info "Initializing workflow schema")
  (doseq [statement workflow-ddl]
    (db/execute-ddl! ctx statement)))

;; =============================================================================
;; Type conversion helpers
;; =============================================================================

(defn- str->uuid [s] (when s (UUID/fromString s)))
(defn- str->instant [s] (when s (Instant/parse s)))
(defn- uuid->str [u] (when u (str u)))
(defn- instant->str [i] (when i (str i)))
(defn- kw->str [k] (when k (name k)))
(defn- str->kw [s] (when s (keyword s)))

;; =============================================================================
;; workflow_instances  — row <-> entity mapping
;; =============================================================================

(defn- instance->db
  "Convert a WorkflowInstance map to a DB row map (snake_case strings)."
  [instance]
  {:id           (uuid->str (:id instance))
   :workflow_id  (kw->str (:workflow-id instance))
   :entity_type  (kw->str (:entity-type instance))
   :entity_id    (uuid->str (:entity-id instance))
   :current_state (kw->str (:current-state instance))
   :created_at   (instant->str (:created-at instance))
   :updated_at   (instant->str (:updated-at instance))
   :metadata     (when-let [m (:metadata instance)]
                   (pr-str m))})

(defn- db->instance
  "Convert a DB row map to a WorkflowInstance map (kebab-case keywords)."
  [row]
  (when row
    (cond-> {:id            (str->uuid (:id row))
             :workflow-id   (str->kw (:workflow_id row))
             :entity-type   (str->kw (:entity_type row))
             :entity-id     (str->uuid (:entity_id row))
             :current-state (str->kw (:current_state row))
             :created-at    (str->instant (:created_at row))
             :updated-at    (str->instant (:updated_at row))}
      (:metadata row)
      (assoc :metadata (edn/read-string (:metadata row))))))

;; =============================================================================
;; workflow_audit  — row <-> entry mapping
;; =============================================================================

(defn- entry->db
  "Convert an AuditEntry map to a DB row map."
  [entry]
  {:id           (uuid->str (:id entry))
   :instance_id  (uuid->str (:instance-id entry))
   :workflow_id  (kw->str (:workflow-id entry))
   :entity_type  (kw->str (:entity-type entry))
   :entity_id    (uuid->str (:entity-id entry))
   :transition   (kw->str (:transition entry))
   :from_state   (kw->str (:from-state entry))
   :to_state     (kw->str (:to-state entry))
   :actor_id     (uuid->str (:actor-id entry))
   :actor_roles  (when-let [r (:actor-roles entry)] (pr-str r))
   :context      (when-let [c (:context entry)] (pr-str c))
   :occurred_at  (instant->str (:occurred-at entry))})

(defn- db->entry
  "Convert a DB row map to an AuditEntry map."
  [row]
  (when row
    (cond-> {:id          (str->uuid (:id row))
             :instance-id (str->uuid (:instance_id row))
             :workflow-id (str->kw (:workflow_id row))
             :entity-type (str->kw (:entity_type row))
             :entity-id   (str->uuid (:entity_id row))
             :transition  (str->kw (:transition row))
             :from-state  (str->kw (:from_state row))
             :to-state    (str->kw (:to_state row))
             :occurred-at (str->instant (:occurred_at row))}
      (:actor_id row)    (assoc :actor-id (str->uuid (:actor_id row)))
      (:actor_roles row) (assoc :actor-roles (edn/read-string (:actor_roles row)))
      (:context row)     (assoc :context (edn/read-string (:context row))))))

;; =============================================================================
;; IWorkflowStore implementation
;; =============================================================================

(defrecord WorkflowStore [datasource]
  ports/IWorkflowStore

  (save-instance! [_ instance]
    (log/debug "Saving workflow instance" {:id (:id instance)})
    (let [row (instance->db instance)]
      (jdbc/execute-one! datasource
                         (sql/format {:insert-into :workflow_instances
                                      :values      [row]})
                         {:return-keys true
                          :builder-fn  rs/as-unqualified-lower-maps}))
    instance)

  (find-instance [_ instance-id]
    (log/debug "Finding workflow instance" {:id instance-id})
    (let [row (jdbc/execute-one! datasource
                                 (sql/format {:select [:*]
                                              :from   [:workflow_instances]
                                              :where  [:= :id (uuid->str instance-id)]})
                                 {:builder-fn rs/as-unqualified-lower-maps})]
      (db->instance row)))

  (find-instance-by-entity [_ entity-type entity-id]
    (log/debug "Finding workflow instance by entity"
               {:entity-type entity-type :entity-id entity-id})
    (let [row (jdbc/execute-one! datasource
                                 (sql/format {:select   [:*]
                                              :from     [:workflow_instances]
                                              :where    [:and
                                                         [:= :entity_type (kw->str entity-type)]
                                                         [:= :entity_id   (uuid->str entity-id)]]
                                              :order-by [[:created_at :desc]]
                                              :limit    1})
                                 {:builder-fn rs/as-unqualified-lower-maps})]
      (db->instance row)))

  (update-instance-state! [_ instance-id new-state]
    (log/debug "Updating workflow instance state"
               {:id instance-id :new-state new-state})
    (let [now (instant->str (Instant/now))]
      (jdbc/execute-one! datasource
                         (sql/format {:update :workflow_instances
                                      :set    {:current_state (kw->str new-state)
                                               :updated_at    now}
                                      :where  [:= :id (uuid->str instance-id)]})
                         {:builder-fn rs/as-unqualified-lower-maps}))
    ;; Re-fetch and return updated instance
    (let [row (jdbc/execute-one! datasource
                                 (sql/format {:select [:*]
                                              :from   [:workflow_instances]
                                              :where  [:= :id (uuid->str instance-id)]})
                                 {:builder-fn rs/as-unqualified-lower-maps})]
      (db->instance row)))

  (save-audit-entry! [_ entry]
    (log/debug "Saving audit entry" {:id (:id entry)})
    (let [row (entry->db entry)]
      (jdbc/execute-one! datasource
                         (sql/format {:insert-into :workflow_audit
                                      :values      [row]})
                         {:return-keys true
                          :builder-fn  rs/as-unqualified-lower-maps}))
    entry)

  (find-audit-log [_ instance-id]
    (log/debug "Fetching audit log" {:instance-id instance-id})
    (let [rows (jdbc/execute! datasource
                              (sql/format {:select   [:*]
                                           :from     [:workflow_audit]
                                           :where    [:= :instance_id (uuid->str instance-id)]
                                           :order-by [[:occurred_at :asc]]})
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv db->entry rows)))

  (list-instances [_ opts]
    (log/debug "Listing workflow instances" opts)
    (let [{:keys [workflow-id entity-type current-state limit offset]
           :or {limit 50 offset 0}} opts
          conditions (cond-> []
                       workflow-id   (conj [:= :workflow_id (kw->str workflow-id)])
                       entity-type   (conj [:= :entity_type (kw->str entity-type)])
                       current-state (conj [:= :current_state (kw->str current-state)]))
          query (cond-> {:select   [:*]
                         :from     [:workflow_instances]
                         :order-by [[:updated_at :desc]]
                         :limit    limit
                         :offset   offset}
                  (seq conditions)
                  (assoc :where (if (= 1 (count conditions))
                                  (first conditions)
                                  (into [:and] conditions))))
          rows (jdbc/execute! datasource
                              (sql/format query)
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv db->instance rows))))

(defn create-workflow-store
  "Create a WorkflowStore backed by a JDBC datasource.

   Args:
     datasource - javax.sql.DataSource (from db-context)

   Returns:
     WorkflowStore implementing IWorkflowStore"
  [datasource]
  (->WorkflowStore datasource))
