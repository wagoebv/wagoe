(ns wagoe.tenant.shell.http
  "HTTP routes and handlers for tenant management.
   
   Provides REST API endpoints for:
   - Tenant CRUD operations (list, get, create, update, delete)
   - Tenant provisioning (schema creation, data seeding)
   - Tenant activation/suspension
   
   All routes require the global admin role (BOU-568)."
  (:require [wagoe.platform.core.http.access :as access]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.tenant.schema :as tenant-schema]
            [wagoe.tenant.shell.provisioning :as provisioning]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.error :as me]))

;; =============================================================================
;; Utilities
;; =============================================================================

(defn- json-response
  "Create JSON HTTP response."
  ([data] (json-response 200 data))
  ([status data]
   {:status status
    :headers {"Content-Type" "application/json"}
    :body (json/generate-string data)}))

(defn- error-response
  "Create error HTTP response."
  [status message & [details]]
  (json-response status
                 (cond-> {:error message}
                   details (assoc :details details))))

(defn- validation-error-response
  "Create validation error response from Malli errors."
  [errors]
  (error-response 400 "Validation failed"
                  {:validation-errors (me/humanize errors)}))

(defn- parse-tenant-uuid
  "Parse UUID from string, return nil if invalid."
  [uuid-str]
  (try
    (java.util.UUID/fromString uuid-str)
    (catch IllegalArgumentException _
      nil)))

(defn- require-admin
  "Tenant management is for global admins. The platform has already refused
   anyone not signed in."
  [handler]
  (fn [request]
    (if (access/admin? request)
      (handler request)
      (access/forbidden-response "Admin role required" request
                                 (access/correlation-id request (str (random-uuid)))))))

(def ^:private tenant-input-explainer (m/explainer tenant-schema/TenantInput))
(def ^:private tenant-update-explainer (m/explainer tenant-schema/TenantUpdate))

;; =============================================================================
;; Handlers
;;
;; The service returns the tenant or throws a typed ex-info; it never returns a
;; {:success? ...} map. Each handler lets `with-typed-errors` answer the throw.
;; =============================================================================

(defn- typed-error-response
  "The response for a thrown service error: its message and a status from its
   :type. Anything untyped is logged and answered with a generic 500."
  [e action]
  (if-let [status (get {:validation-error 400
                        :not-found        404
                        :conflict         409
                        :not-supported    501}
                       (:type (ex-data e)))]
    (error-response status (ex-message e))
    (do (log/error e (str "Failed to " action))
        (error-response 500 "Internal server error"))))

(defn- with-typed-errors [action f]
  (try
    (f)
    (catch Exception e
      (typed-error-response e action))))

(defn- with-tenant-id
  "Call `f` with the path's tenant id, or answer 400 when it is not a UUID."
  [request f]
  (if-let [tenant-id (some-> (get-in request [:path-params :id]) parse-tenant-uuid)]
    (f tenant-id)
    (error-response 400 "Invalid tenant ID format")))

(defn- request-body [request]
  (or (:body-params request)
      (some-> (:body request) slurp (json/parse-string true))))

(defn list-tenants-handler
  "List tenants, newest first, as a JSON array like the membership list.

   Query params:
   - limit: Number of results (default 20, max 100)
   - offset: Pagination offset (default 0)
   - status: Filter by status (active, suspended)
   - search: Match against name or slug"
  [tenant-service]
  (fn [request]
    (with-typed-errors "list tenants"
      #(let [params  (:params request)
             limit   (min (or (some-> (:limit params) parse-long) 20) 100)
             offset  (or (some-> (:offset params) parse-long) 0)
             options (cond-> {:limit limit :offset offset}
                       (:status params) (assoc :status (keyword (:status params)))
                       (:search params) (assoc :search (:search params)))]
         (json-response 200 (tenant-ports/list-tenants tenant-service options))))))

(defn get-tenant-handler
  "Get tenant by ID."
  [tenant-service]
  (fn [request]
    (with-tenant-id request
      (fn [tenant-id]
        (with-typed-errors "get tenant"
          #(json-response 200 (tenant-ports/get-tenant tenant-service tenant-id)))))))

(defn create-tenant-handler
  "Create new tenant.

   Request body:
   - name: Tenant display name (required)
   - slug: Tenant slug for URLs (required, unique)
   - status: Initial status (:active or :suspended, default :active)"
  [tenant-service]
  (fn [request]
    (with-typed-errors "create tenant"
      #(let [body         (request-body request)
             tenant-input {:name   (:name body)
                           :slug   (:slug body)
                           :status (or (some-> (:status body) keyword) :active)}]
         (if-let [errors (tenant-input-explainer tenant-input)]
           (validation-error-response errors)
           (json-response 201 (tenant-ports/create-new-tenant tenant-service tenant-input)))))))

(defn update-tenant-handler
  "Update a tenant's name or status.

   The slug names the tenant's schema, so it cannot change; sending the
   current one is accepted."
  [tenant-service]
  (fn [request]
    (with-tenant-id request
      (fn [tenant-id]
        (with-typed-errors "update tenant"
          #(let [body        (request-body request)
                 update-data (cond-> {}
                               (:name body)   (assoc :name (:name body))
                               (:status body) (assoc :status (keyword (:status body))))]
             (cond
               (tenant-update-explainer update-data)
               (validation-error-response (tenant-update-explainer update-data))

               (and (:slug body)
                    (not= (:slug body) (:slug (tenant-ports/get-tenant tenant-service tenant-id))))
               (error-response 400 "The slug cannot change: it names the tenant's schema")

               :else
               (json-response 200 (tenant-ports/update-existing-tenant
                                   tenant-service tenant-id update-data)))))))))

(defn delete-tenant-handler
  "Delete tenant (soft delete).

   This marks the tenant as deleted but preserves data for audit purposes."
  [tenant-service]
  (fn [request]
    (with-tenant-id request
      (fn [tenant-id]
        (with-typed-errors "delete tenant"
          #(do (tenant-ports/delete-existing-tenant tenant-service tenant-id)
               (json-response 200 {:message "Tenant deleted successfully"})))))))

(defn suspend-tenant-handler
  "Suspend tenant (prevents login and access)."
  [tenant-service]
  (fn [request]
    (with-tenant-id request
      (fn [tenant-id]
        (with-typed-errors "suspend tenant"
          #(json-response 200 (tenant-ports/suspend-tenant tenant-service tenant-id)))))))

(defn activate-tenant-handler
  "Activate suspended tenant."
  [tenant-service]
  (fn [request]
    (with-tenant-id request
      (fn [tenant-id]
        (with-typed-errors "activate tenant"
          #(json-response 200 (tenant-ports/activate-tenant tenant-service tenant-id)))))))

(defn provision-tenant-handler
  "Provision tenant database schema.

   Creates the tenant's PostgreSQL schema and its tables. Idempotent. Any other
   database answers 501."
  [tenant-service db-context]
  (fn [request]
    (with-tenant-id request
      (fn [tenant-id]
        (if-not db-context
          (error-response 500 "Database context not available")
          (with-typed-errors "provision tenant"
            #(json-response 200 (provisioning/provision-tenant!
                                 db-context
                                 (tenant-ports/get-tenant tenant-service tenant-id)))))))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn tenant-routes
  "This module's contribution to the route table: Reitit route data under
   :api, at paths relative to /api/v1. Every route requires the admin role."
  [tenant-service db-context _config]
  {:api
   (mapv
    (fn [[path data]] [path (update data :middleware (fnil conj []) require-admin)])
    [;; Tenant collection endpoint
     ["/tenants"
      {:get {:handler (list-tenants-handler tenant-service)
             :summary "List all tenants"
             :description "List tenants with optional filtering and pagination"
             :tags ["tenants"]
             :responses {200 {:description "List of tenants"}
                         400 {:description "Bad request"}}}
       :post {:handler (create-tenant-handler tenant-service)
              :summary "Create new tenant"
              :description "Create a new tenant with name and slug"
              :tags ["tenants"]
              :responses {201 {:description "Tenant created successfully"}
                          400 {:description "Validation error"}}}}]

    ;; Tenant resource endpoints
     ["/tenants/:id"
      {:get {:handler (get-tenant-handler tenant-service)
             :summary "Get tenant by ID"
             :description "Retrieve tenant details by UUID"
             :tags ["tenants"]
             :responses {200 {:description "Tenant details"}
                         404 {:description "Tenant not found"}}}
       :put {:handler (update-tenant-handler tenant-service)
             :summary "Update tenant"
             :description "Update tenant name, slug, or status"
             :tags ["tenants"]
             :responses {200 {:description "Tenant updated successfully"}
                         400 {:description "Validation error"}}}
       :delete {:handler (delete-tenant-handler tenant-service)
                :summary "Delete tenant"
                :description "Soft delete tenant (marks as deleted)"
                :tags ["tenants"]
                :responses {200 {:description "Tenant deleted successfully"}
                            404 {:description "Tenant not found"}}}}]

    ;; Tenant action endpoints
     ["/tenants/:id/suspend"
      {:post {:handler (suspend-tenant-handler tenant-service)
              :summary "Suspend tenant"
              :description "Suspend tenant access (prevents login)"
              :tags ["tenants"]
              :responses {200 {:description "Tenant suspended successfully"}
                          404 {:description "Tenant not found"}}}}]

     ["/tenants/:id/activate"
      {:post {:handler (activate-tenant-handler tenant-service)
              :summary "Activate tenant"
              :description "Activate suspended tenant"
              :tags ["tenants"]
              :responses {200 {:description "Tenant activated successfully"}
                          404 {:description "Tenant not found"}}}}]

     ["/tenants/:id/provision"
      {:post {:handler (provision-tenant-handler tenant-service db-context)
              :summary "Provision tenant schema"
              :description "Create database schema for tenant (PostgreSQL only). Idempotent operation."
              :tags ["tenants"]
              :responses {200 {:description "Tenant provisioned successfully"}
                          404 {:description "Tenant not found"}
                          501 {:description "Not supported (requires PostgreSQL)"}}}}]])})
