(ns wagoe.workflow.shell.http
  "HTTP API routes and admin web UI handlers for workflow management.

   API Endpoints (canonical):
     GET  /api/v1/workflow/instances?entity-type=&entity-id= — an entity's instances
     GET  /api/v1/workflow/instances/:id            — current state + metadata
     GET  /api/v1/workflow/instances/:id/audit      — full audit log
     POST /api/v1/workflow/instances                — start a new workflow instance
     POST /api/v1/workflow/instances/:id/transition — execute a transition

   Compatibility:
     /api/workflow/* redirects to /api/v1/workflow/* via versioning middleware.

   Admin Web UI (mounted under /web/admin):
     GET  /workflows        — list all workflow instances
     GET  /workflows/:id    — instance detail with state viz + audit trail

   Every route requires authentication and answers 401 without it."
  (:require [wagoe.i18n.shell.middleware :as i18n-middleware]
            [wagoe.i18n.shell.render :as i18n]
            [wagoe.platform.core.http.errors :as errors]
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
   Returns {:actor-id uuid-or-nil :actor-roles [...]}

   `:user` is where the authentication middleware puts the caller. This read
   `:identity` and `[:session :user]`, which nothing has set since BOU-373 —
   so every request arrived with no actor and no roles, and any transition
   with `:required-permissions` was permanently refused (BOU-478)."
  [request]
  (let [user (:user request)]
    {:actor-id    (:id user)
     :actor-roles (filterv identity [(keyword (:role user))])}))

(defn- instance->response
  "Render a WorkflowInstance as a JSON-friendly map.

   available-ts — vector of status maps from ports/available-transitions,
   or nil when not computed (start / transition responses)."
  [instance available-ts]
  {:id             (str (:id instance))
   :workflow-id    (name (:workflow-id instance))
   :entity-type    (name (:entity-type instance))
   :entity-id      (str (:entity-id instance))
   :current-state  (name (:current-state instance))
   :available-transitions
   (when available-ts
     (mapv (fn [t]
             (cond-> {:id      (name (:id t))
                      :to      (name (:to t))
                      :enabled (:enabled? t)}
               (:label t)  (assoc :label (:label t))
               (:reason t) (assoc :reason (name (:reason t)))))
           available-ts))
   :created-at (str (:created-at instance))
   :updated-at (str (:updated-at instance))})

(defn- audit-entry->response
  "Render an AuditEntry as a JSON-friendly map."
  [entry]
  {:id          (str (:id entry))
   :instance-id (str (:instance-id entry))
   :transition  (name (:transition entry))
   :from-state  (name (:from-state entry))
   :to-state    (name (:to-state entry))
   :actor-id    (some-> (:actor-id entry) str)
   :actor-roles (mapv name (or (:actor-roles entry) []))
   :occurred-at (str (:occurred-at entry))})

;; =============================================================================
;; Handlers
;; =============================================================================

(defn handle-get-instance
  "GET /api/v1/workflow/instances/:id"
  [engine request]
  (let [id-str   (get-in request [:path-params :id])
        id       (parse-uuid-param id-str "id")
        instance (ports/find-instance (:store engine) id)]
    (when (nil? instance)
      (throw (ex-info "Workflow instance not found"
                      {:type :not-found :message "Workflow instance not found" :id id-str})))
    (let [actor    (actor-from-request request)
          avail-ts (ports/available-transitions engine id (:actor-roles actor) nil)]
      {:status 200
       :body   (instance->response instance avail-ts)})))

(defn handle-get-audit-log
  "GET /api/v1/workflow/instances/:id/audit"
  [engine request]
  (let [id-str (get-in request [:path-params :id])
        id     (parse-uuid-param id-str "id")
        log-entries (ports/audit-log engine id)]
    {:status 200
     :body   {:instance-id id-str
              :entries     (mapv audit-entry->response log-entries)}}))

(defn handle-find-instances
  "GET /api/v1/workflow/instances?entity-type=…&entity-id=…

   The entity's instances, one per workflow it is in; [] for none. Without
   their available transitions: GET /instances/:id has those."
  [engine request]
  (let [{:keys [entity-type entity-id]} (get-in request [:parameters :query])]
    {:status 200
     :body   (mapv #(dissoc (instance->response % nil) :available-transitions)
                   (ports/list-instances (:store engine)
                                         {:entity-type (keyword entity-type)
                                          :entity-id   entity-id}))}))

(defn handle-start-workflow
  "POST /api/v1/workflow/instances"
  [engine request]
  (let [body        (get-in request [:parameters :body])
        workflow-id (keyword (:workflow-id body))
        entity-type (keyword (:entity-type body))
        entity-id   (parse-uuid-param (:entity-id body) "entity-id")
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
      ;; The scaffolded APIs' shape: the result, or {:error {:type :message}}.
      (if (:success? result)
        {:status 200
         :body   {:instance    (instance->response (:instance result) nil)
                  :audit-entry (audit-entry->response (:audit-entry result))}}
        {:status 422
         :body   (errors/body (get-in result [:error :type])
                              (get-in result [:error :message]))}))))

;; =============================================================================
;; Route definitions
;; =============================================================================

(def ^:private instance-id-path
  "The `:id` every instance route carries."
  [:map [:id :string]])

(def ^:private signed-in ['wagoe.user.shell.http-interceptors/require-authenticated])

(defn workflow-routes
  "Reitit route data for the workflow API. Mounted under /api/v1.

   The POST routes declare their body schema, which is what makes
   `[:parameters :body]` exist. Without it the handlers read nil whatever the
   caller sent, and a POST was answered from an empty body rather than
   refused (BOU-478).

   Every route requires a signed-in user. The global `authenticate-if-present`
   sets `:user` from a bearer token or a session; the interceptor refuses the
   request without one, with the same 401 the scaffolded APIs give.

   Args:
     engine - WorkflowService (IWorkflowEngine)"
  [engine]
  [["/workflow/instances"
    {:get  {:handler (fn [req] (handle-find-instances engine req))
            :summary "Find an entity's workflow instances"
            :interceptors signed-in
            :parameters {:query [:map
                                 [:entity-type :string]
                                 [:entity-id :uuid]]}}
     :post {:handler (fn [req] (handle-start-workflow engine req))
            :summary "Start a new workflow instance"
            :interceptors signed-in
            :parameters {:body [:map {:closed true}
                                [:workflow-id :string]
                                [:entity-type :string]
                                [:entity-id :string]
                                ;; map-of, not :map: the metadata is the
                                ;; caller's own and has no schema here, and a
                                ;; closed :map would reject all of it.
                                [:metadata {:optional true} [:map-of :keyword :any]]]}}}]
   ["/workflow/instances/:id"
    {:get {:handler (fn [req] (handle-get-instance engine req))
           :summary "Get current workflow state"
           :interceptors signed-in
           :parameters {:path instance-id-path}}}]
   ["/workflow/instances/:id/audit"
    {:get {:handler (fn [req] (handle-get-audit-log engine req))
           :summary "Get workflow audit log"
           :interceptors signed-in
           :parameters {:path instance-id-path}}}]
   ["/workflow/instances/:id/transition"
    {:post {:handler (fn [req] (handle-transition engine req))
            :summary "Execute a workflow transition"
            :interceptors signed-in
            :parameters {:path instance-id-path
                         :body [:map {:closed true}
                                [:transition :string]
                                [:context {:optional true} [:map-of :keyword :any]]]}}}]])

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
                     [:div [:h2 "Error"] [:p [:t :common/error-generic]]]
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
                     [:div [:h2 "Error"] [:p [:t :common/error-generic]]]
                     500))))

;; =============================================================================
;; Web route definitions
;; =============================================================================

(defn workflow-web-routes
  "Web routes for the workflow admin UI, as Reitit data.

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
