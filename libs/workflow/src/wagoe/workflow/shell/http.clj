(ns wagoe.workflow.shell.http
  "HTTP API routes and admin web UI handlers for workflow management.

   API Endpoints (canonical):
     GET  /api/v1/workflow/instances/:id            — current state + metadata
     GET  /api/v1/workflow/instances/:id/audit      — full audit log
     POST /api/v1/workflow/instances                — start a new workflow instance
     POST /api/v1/workflow/instances/:id/transition — execute a transition

   Compatibility:
     /api/workflow/* redirects to /api/v1/workflow/* via versioning middleware.

   Admin Web UI (mounted under /web/admin):
     GET  /workflows        — list all workflow instances
     GET  /workflows/:id    — instance detail with state viz + audit trail

   All routes require authentication (actor extracted from ring request).
   Caller is responsible for mounting under an authenticated router."
  (:require [wagoe.i18n.shell.middleware :as i18n-middleware]
            [wagoe.i18n.shell.render :as i18n]
            [wagoe.workflow.ports :as ports]
            [wagoe.workflow.core.ui :as workflow-ui]
            [wagoe.user.shell.middleware :as user-middleware]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- parse-uuid-param
  "Parse a UUID string, throwing :validation-error on failure."
  [s param-name]
  (try
    (UUID/fromString s)
    (catch IllegalArgumentException _
      (throw (ex-info (str "Invalid UUID for " param-name)
                      {:type    :validation-error
                       :field   param-name
                       :value   s
                       :message (str param-name " must be a valid UUID")})))))

(defn- actor-from-request
  "Extract actor identity from authenticated ring request.
   Returns {:actor-id uuid-or-nil :actor-roles [...]}"
  [request]
  (let [user (or (get-in request [:identity])
                 (get-in request [:session :user]))]
    {:actor-id    (:id user)
     :actor-roles (filterv identity [(keyword (:role user))])}))

(defn- instance->response
  "Render a WorkflowInstance as a JSON-friendly map.

   available-ts — vector of status maps from ports/available-transitions,
   or nil when not computed (start / transition responses)."
  [instance available-ts]
  {:id             (str (:id instance))
   :workflowId     (name (:workflow-id instance))
   :entityType     (name (:entity-type instance))
   :entityId       (str (:entity-id instance))
   :currentState   (name (:current-state instance))
   :availableTransitions
   (when available-ts
     (mapv (fn [t]
             (cond-> {:id      (name (:id t))
                      :to      (name (:to t))
                      :enabled (:enabled? t)}
               (:label t)  (assoc :label (:label t))
               (:reason t) (assoc :reason (name (:reason t)))))
           available-ts))
   :createdAt  (str (:created-at instance))
   :updatedAt  (str (:updated-at instance))})

(defn- audit-entry->response
  "Render an AuditEntry as a JSON-friendly map."
  [entry]
  {:id          (str (:id entry))
   :instanceId  (str (:instance-id entry))
   :transition  (name (:transition entry))
   :fromState   (name (:from-state entry))
   :toState     (name (:to-state entry))
   :actorId     (some-> (:actor-id entry) str)
   :actorRoles  (mapv name (or (:actor-roles entry) []))
   :occurredAt  (str (:occurred-at entry))})

;; =============================================================================
;; Handlers
;; =============================================================================

(defn handle-get-instance
  "GET /api/v1/workflow/instances/:id"
  [engine request]
  (let [id-str   (get-in request [:path-params :id])
        id       (parse-uuid-param id-str "id")
        instance (ports/find-instance (:store engine) id)]
    (if (nil? instance)
      {:status 404
       :body   {:error "Workflow instance not found" :id id-str}}
      (let [actor    (actor-from-request request)
            avail-ts (ports/available-transitions engine id (:actor-roles actor) nil)]
        {:status 200
         :body   (instance->response instance avail-ts)}))))

(defn handle-get-audit-log
  "GET /api/v1/workflow/instances/:id/audit"
  [engine request]
  (let [id-str (get-in request [:path-params :id])
        id     (parse-uuid-param id-str "id")
        log-entries (ports/audit-log engine id)]
    {:status 200
     :body   {:instanceId id-str
              :entries    (mapv audit-entry->response log-entries)}}))

(defn handle-start-workflow
  "POST /api/v1/workflow/instances"
  [engine request]
  (let [body        (get-in request [:parameters :body])
        workflow-id (keyword (:workflowId body))
        entity-type (keyword (:entityType body))
        entity-id   (parse-uuid-param (:entityId body) "entityId")
        metadata    (:metadata body {})]
    (log/info "Starting workflow via HTTP"
              {:workflow-id workflow-id :entity-type entity-type :entity-id entity-id})
    (let [instance (ports/start-workflow! engine
                                          {:workflow-id workflow-id
                                           :entity-type entity-type
                                           :entity-id   entity-id
                                           :metadata    metadata})]
      {:status 201
       :body   (instance->response instance nil)})))

(defn handle-transition
  "POST /api/v1/workflow/instances/:id/transition"
  [engine request]
  (let [id-str     (get-in request [:path-params :id])
        id         (parse-uuid-param id-str "id")
        body       (get-in request [:parameters :body])
        transition (keyword (:transition body))
        actor      (actor-from-request request)
        context    (:context body {})]
    (log/info "Executing workflow transition via HTTP"
              {:instance-id id :transition transition})
    (let [result (ports/transition! engine
                                    {:instance-id id
                                     :transition  transition
                                     :actor-id    (:actor-id actor)
                                     :actor-roles (:actor-roles actor)
                                     :context     context})]
      (if (:success? result)
        {:status 200
         :body   {:success    true
                  :instance   (instance->response (:instance result) nil)
                  :auditEntry (audit-entry->response (:audit-entry result))}}
        {:status 422
         :body   {:success false
                  :error   (:error result)}}))))

;; =============================================================================
;; Route definitions
;; =============================================================================

(defn workflow-routes
  "Reitit route data for the workflow API. Mounted under /api/v1.

   Args:
     engine - WorkflowService (IWorkflowEngine)"
  [engine]
  [["/workflow/instances"
    {:post {:handler (fn [req] (handle-start-workflow engine req))
            :summary "Start a new workflow instance"}}]
   ["/workflow/instances/:id"
    {:get {:handler (fn [req] (handle-get-instance engine req))
           :summary "Get current workflow state"}}]
   ["/workflow/instances/:id/audit"
    {:get {:handler (fn [req] (handle-get-audit-log engine req))
           :summary "Get workflow audit log"}}]
   ["/workflow/instances/:id/transition"
    {:post {:handler (fn [req] (handle-transition engine req))
            :summary "Execute a workflow transition"}}]])

;; =============================================================================
;; Admin web UI helpers
;; =============================================================================

(defn- resolve-t
  "Resolve an i18n translation function at render time.

   Prefers `resolve-t-fn`, which reads `:user` from the fully-enriched
   request (after auth middleware has run), so it respects the authenticated
   user's language preference. Falls back to the eager `:i18n/t` from
   `wrap-i18n` (tenant/default only), then to a safe identity fallback that
   renders the key name for `[:t ...]` markers."
  [request]
  (or (i18n-middleware/resolve-t-fn request)
      (get request :i18n/t)
      (fn
        ([k] (if (keyword? k) (name k) (str k)))
        ([k _params] (if (keyword? k) (name k) (str k)))
        ([k _params _n] (if (keyword? k) (name k) (str k))))))

(defn- html-response
  "Create a text/html Ring response, resolving [:t ...] i18n markers."
  ([request hiccup]
   (html-response request hiccup 200))
  ([request hiccup status]
   {:status  status
    :headers {"Content-Type" "text/html; charset=utf-8"}
    :body    (i18n/render hiccup (resolve-t request))}))

(defn- parse-list-opts
  "Extract list-instances filter options from query-params."
  [query-params]
  (cond-> {:limit 50 :offset 0}
    (not-empty (get query-params "workflow-id"))
    (assoc :workflow-id (keyword (get query-params "workflow-id")))

    (not-empty (get query-params "entity-type"))
    (assoc :entity-type (keyword (get query-params "entity-type")))

    (not-empty (get query-params "state"))
    (assoc :current-state (keyword (get query-params "state")))))

;; =============================================================================
;; Admin web UI handlers
;; =============================================================================

(defn handle-list-instances-web
  "GET /workflows — render the workflow instances list page."
  [store request]
  (try
    (let [qp        (or (:query-params request) {})
          opts      (parse-list-opts qp)
          instances (ports/list-instances store opts)
          page-opts {:user  (:user request)
                     :flash (:flash request)}]
      (html-response request (workflow-ui/instances-page instances qp page-opts)))
    (catch Exception e
      (log/error e "Error in handle-list-instances-web")
      (html-response request
                     [:div [:h2 "Error"] [:p (.getMessage e)]]
                     500))))

(defn handle-get-instance-web
  "GET /workflows/:id — render the workflow instance detail page."
  [store registry request]
  (try
    (let [id-str   (get-in request [:path-params :id])
          id       (parse-uuid-param id-str "id")
          instance (ports/find-instance store id)]
      (if (nil? instance)
        (html-response request
                       [:div [:h2 "Not Found"] [:p (str "Workflow instance " id-str " not found.")]]
                       404)
        (let [definition  (ports/get-workflow registry (:workflow-id instance))
              audit-log   (ports/find-audit-log store id)
              page-opts   {:user  (:user request)
                           :flash (:flash request)}]
          (html-response request
                         (workflow-ui/instance-detail-page instance definition audit-log page-opts)))))
    (catch Exception e
      (log/error e "Error in handle-get-instance-web")
      (html-response request
                     [:div [:h2 "Error"] [:p (.getMessage e)]]
                     500))))

;; =============================================================================
;; Normalized web route definitions
;; =============================================================================

(defn workflow-web-routes
  "Return normalized web route definitions for the workflow admin UI.

   Routes are mounted under /web/admin by the HTTP handler:
     GET /web/admin/workflows        — list all workflow instances
     GET /web/admin/workflows/:id    — instance detail + audit trail

   Route-level `:middleware` is Reitit's own and applies to every endpoint
   beneath it — the `:meta` wrapper this used to need is gone (ADR-037).

   Args:
     store        - IWorkflowStore
     registry     - IWorkflowRegistry
     user-service - IUserService (for authentication middleware)"
  [store registry user-service]
  (let [auth-mw (user-middleware/flexible-authentication-middleware user-service)]
    [["/workflows"
      {:middleware [auth-mw]
       :get {:handler (fn [req] (handle-list-instances-web store req))
             :summary "Workflow instances list"}}]
     ["/workflows/:id"
      {:middleware [auth-mw]
       :get {:handler (fn [req] (handle-get-instance-web store registry req))
             :summary "Workflow instance detail"}}]]))
