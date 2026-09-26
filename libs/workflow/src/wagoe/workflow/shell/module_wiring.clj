(ns wagoe.workflow.shell.module-wiring
  "Integrant lifecycle management for the workflow module.

   Config keys:

   :wagoe/workflow-db-schema
     {:ctx (ig/ref :wagoe/db-context)}

   :wagoe/workflow
     Minimal config (no side-effects):
       {:db-ctx    (ig/ref :wagoe/db-context)
        :db-schema (ig/ref :wagoe/workflow-db-schema)}

     Full config (with jobs side-effects):
       {:db-ctx        (ig/ref :wagoe/db-context)
        :db-schema     (ig/ref :wagoe/workflow-db-schema)
        :job-queue     (ig/ref :wagoe/job-queue)
        :guard-registry {}}

   :wagoe/workflow-routes
     {:workflow-service (ig/ref :wagoe/workflow)
      :user-service     (ig/ref :wagoe/user-service)}

     Returns {:api [...] :web [...] :static []} for composition
     by the HTTP handler."
  (:require [integrant.core :as ig]
            [wagoe.platform.database :as db]
            [wagoe.workflow.shell.registry :as registry]
            [wagoe.workflow.shell.persistence :as persistence]
            [wagoe.workflow.shell.service :as service]
            [wagoe.workflow.shell.http :as workflow-http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private migration-dir "wagoe/workflow/migrations/")

(defn- migration-resource [file-name]
  (or (io/resource (str migration-dir file-name))
      (throw (ex-info "Workflow migration missing from the classpath"
                      {:type :internal-error :file file-name}))))

(defn- migration-statements
  "The statements of the migration that creates workflow's tables."
  []
  (->> (str/split (slurp (migration-resource "20260926100000-workflow-tables.up.sql"))
                  #"--;;")
       (map str/trim)
       (remove str/blank?)))

(defn- initialize-workflow-schema!
  "Create workflow's tables at boot, for installations that never ran
   `migrate up`. Converting a pre-BOU-502 TEXT table is left to `migrate up`:
   it locks the table and breaks replicas still running the old version."
  [ctx]
  (log/info "Initializing workflow schema")
  (doseq [statement (migration-statements)]
    (db/execute-ddl! ctx statement)))

(defmethod ig/init-key :wagoe/workflow-db-schema
  [_ {:keys [ctx]}]
  (log/info "Initializing workflow database schema")
  (initialize-workflow-schema! ctx)
  {:status :initialized})

(defmethod ig/halt-key! :wagoe/workflow-db-schema
  [_ _]
  (log/info "Workflow database schema component halted"))

(defmethod ig/init-key :wagoe/workflow
  [_ {:keys [db-ctx db-schema job-queue guard-registry]}]
  (log/info "Initializing workflow component")
  (when-not db-schema
    (log/warn "Workflow component started without :db-schema dependency"))
  (let [datasource (:datasource db-ctx)
        store      (persistence/create-workflow-store datasource)
        registry   (registry/create-workflow-registry)
        engine     (service/create-workflow-service store registry job-queue guard-registry)]
    (log/info "Workflow component initialized")
    {:store    store
     :registry registry
     :engine   engine}))

(defmethod ig/halt-key! :wagoe/workflow
  [_ _component]
  (log/info "Halting workflow component")
  nil)

;; =============================================================================
;; Workflow Routes Component
;; =============================================================================

(defmethod ig/init-key :wagoe/workflow-routes
  [_ {:keys [workflow-service user-service]}]
  (log/info "Initializing workflow routes")
  {:api    (workflow-http/workflow-routes
            (:engine workflow-service))
   :web    (workflow-http/workflow-web-routes
            (:store workflow-service)
            (:registry workflow-service)
            user-service)
   :static []
   ;; Mounted alongside the admin UI rather than at /web, because that is where
   ;; a reader looks for it. Platform used to hold this fact (BOU-330).
   :web-prefix "/web/admin"})

(defmethod ig/halt-key! :wagoe/workflow-routes
  [_ _routes]
  ;; Routes are pure data — no cleanup needed
  nil)

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   :wagoe/workflow-db-schema creates the module's tables. Applications used to
   enumerate this graph by hand and none of them wired it, so `wagoe add
   workflow` gave you a service whose tables did not exist (BOU-326).

   `:job-queue` is passed only when the jobs module is enabled — the docstring
   above has documented it since this module shipped, and nothing supplied it
   (BOU-418)."
  [_settings {:keys [enabled]}]
  {:components
   {:wagoe/workflow-db-schema {:ctx (ig/ref :wagoe/db-context)}
    :wagoe/workflow           (cond-> {:db-ctx         (ig/ref :wagoe/db-context)
                                       :db-schema      (ig/ref :wagoe/workflow-db-schema)
                                       :guard-registry {}}
                                (contains? (or enabled #{}) :wagoe/jobs)
                                (assoc :job-queue (ig/ref :wagoe/job-queue)))
    :wagoe/workflow-routes    {:workflow-service (ig/ref :wagoe/workflow)
                               :user-service     (ig/ref :wagoe/user-service)}}
   :routes [(ig/ref :wagoe/workflow-routes)]})
