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
            [wagoe.workflow.shell.registry :as registry]
            [wagoe.workflow.shell.persistence :as persistence]
            [wagoe.workflow.shell.service :as service]
            [wagoe.workflow.shell.http :as workflow-http]
            [clojure.tools.logging :as log]))

(defmethod ig/init-key :wagoe/workflow-db-schema
  [_ {:keys [ctx]}]
  (log/info "Initializing workflow database schema")
  (persistence/initialize-workflow-schema! ctx)
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
   :static []})

(defmethod ig/halt-key! :wagoe/workflow-routes
  [_ _routes]
  ;; Routes are pure data — no cleanup needed
  nil)
