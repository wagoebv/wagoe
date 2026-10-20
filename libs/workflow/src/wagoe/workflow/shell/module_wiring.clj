(ns wagoe.workflow.shell.module-wiring
  "Integrant lifecycle management for the workflow module.

   Config keys:

   :wagoe/workflow-db-schema
     {:ctx (ig/ref :wagoe/db-context) :profile :prod}   ; alters tables in :dev :test :acc only

   :wagoe/workflow
     Minimal config (no side-effects):
       {:db-ctx    (ig/ref :wagoe/db-context)
        :db-schema (ig/ref :wagoe/workflow-db-schema)}

     Full config (with jobs side-effects):
       {:db-ctx        (ig/ref :wagoe/db-context)
        :db-schema     (ig/ref :wagoe/workflow-db-schema)
        :job-queue     (ig/ref :wagoe/job-queue)
        :each-tenant   (ig/ref :wagoe/each-tenant)   ; with tenancy
        :guard-registry {}}

   :wagoe/workflow-routes
     {:workflow-service (ig/ref :wagoe/workflow)
      :user-service     (ig/ref :wagoe/user-service)
      :group-service    (ig/ref :wagoe/user-group-service)   ; for the :workflow/read grant
      ;; With the admin on, for the admin shell's brand:
      :admin            {:logo-url <the admin's :logo-url>}}

     Returns {:api [...] :web [...] :static []} for composition
     by the HTTP handler."
  (:require [integrant.core :as ig]
            [wagoe.platform.database :as db]
            [wagoe.workflow.shell.registry :as registry]
            [wagoe.workflow.shell.persistence :as persistence]
            [wagoe.workflow.shell.service :as service]
            [wagoe.workflow.shell.tables :as tables]
            [wagoe.workflow.shell.http :as workflow-http]
            [wagoe.workflow.shell.admin-adapter :as admin-adapter]
            [clojure.tools.logging :as log]))

(def ^:private altering-profiles
  "Where the boot may change an existing table: the profiles `db:reset` may
   reset. Anywhere else it changes only through `migrate up`."
  #{:dev :test :acc})

(defn- initialize-workflow-schema!
  "Create workflow's tables at boot, for installations that never ran
   `migrate up`. Changing a table that exists is left to `migrate up` outside
   dev, test and acc: it locks the table, and a production schema changes only
   through a migration. There the boot warns about what is missing and goes on."
  [ctx profile]
  (log/info "Initializing workflow schema")
  (db/execute-migration-ddl! ctx tables/migration-resource)
  (let [ds (:datasource ctx)]
    (doseq [{:keys [what present? ensure!]} tables/boot-changes]
      (cond
        (contains? altering-profiles profile) (ensure! ds)
        (present? ds)                         nil
        :else (log/warn (str "workflow_instances lacks " what ", and the " (pr-str profile)
                             " profile does not change tables at boot. Run `bb migrate up`.")
                        {:profile profile})))))

(defmethod ig/init-key :wagoe/workflow-db-schema
  [_ {:keys [ctx profile]}]
  (log/info "Initializing workflow database schema")
  (initialize-workflow-schema! ctx profile)
  {:status :initialized})

(defmethod ig/halt-key! :wagoe/workflow-db-schema
  [_ _]
  (log/info "Workflow database schema component halted"))

(defmethod ig/init-key :wagoe/workflow
  [_ {:keys [db-ctx db-schema job-queue guard-registry each-tenant]}]
  (log/info "Initializing workflow component")
  (when-not db-schema
    (log/warn "Workflow component started without :db-schema dependency"))
  (let [datasource (:datasource db-ctx)
        store      (persistence/create-workflow-store datasource)
        registry   (registry/create-workflow-registry)
        engine     (service/create-workflow-service store registry job-queue guard-registry each-tenant)]
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
  [_ {:keys [workflow-service user-service group-service admin]}]
  (log/info "Initializing workflow routes")
  {:api    (workflow-http/workflow-routes
            (:engine workflow-service) group-service)
   :web    (workflow-http/workflow-web-routes
            (:store workflow-service)
            (:registry workflow-service)
            user-service
            admin)
   :static []
   ;; Mounted alongside the admin UI rather than at /web, because that is where
   ;; a reader looks for it. Platform used to hold this fact (BOU-330).
   :web-prefix "/web/admin"
   :nav        {:workflow {:label [:t :workflow/nav-title]
                           :icon  :clipboard
                           :items [{:href "/web/admin/workflows" :icon :layout-list :access :admin
                                    :label [:t :workflow/page-instances-heading]}]}}})

(defmethod ig/halt-key! :wagoe/workflow-routes
  [_ _routes]
  ;; Routes are pure data — no cleanup needed
  nil)

;; =============================================================================
;; Admin port (BOU-563)
;; =============================================================================

(defmethod ig/init-key :wagoe/workflow-admin
  [_ {:keys [workflow]}]
  (admin-adapter/create-entity-workflows (:store workflow)))

(defmethod ig/halt-key! :wagoe/workflow-admin [_ _] nil)

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
  [_settings {:keys [enabled config]}]
  (cond->
   {:components
    {:wagoe/workflow-db-schema {:ctx     (ig/ref :wagoe/db-context)
                                :profile (:wagoe/profile config)}
     :wagoe/workflow           (cond-> {:db-ctx         (ig/ref :wagoe/db-context)
                                        :db-schema      (ig/ref :wagoe/workflow-db-schema)
                                        :guard-registry {}}
                                 (contains? (or enabled #{}) :wagoe/jobs)
                                 (assoc :job-queue (ig/ref :wagoe/job-queue))
                                 ;; So auto-transitions reach tenant instances.
                                 (contains? (or enabled #{}) :wagoe/tenant)
                                 (assoc :each-tenant (ig/ref :wagoe/each-tenant)))
     :wagoe/workflow-routes    {:workflow-service (ig/ref :wagoe/workflow)
                                :user-service     (ig/ref :wagoe/user-service)
                                :group-service    (ig/ref :wagoe/user-group-service)}}
    :routes [(ig/ref :wagoe/workflow-routes)]}
    ;; What the admin reads an entity's workflow state through and removes its
    ;; instances with (BOU-563); the admin's wiring refs it when workflow is on.
    (contains? (or enabled #{}) :wagoe/admin)
    (-> (assoc-in [:components :wagoe/workflow-admin] {:workflow (ig/ref :wagoe/workflow)})
        (assoc-in [:components :wagoe/workflow-routes :admin]
                  {:logo-url (get-in config [:active :wagoe/admin :logo-url])}))))
