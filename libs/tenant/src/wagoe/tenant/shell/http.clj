(ns wagoe.tenant.shell.http
  "HTTP routes and handlers for tenant management.
   
   Provides REST API endpoints for:
   - Tenant CRUD operations (list, get, create, update, delete)
   - Tenant provisioning (schema creation, data seeding)
   - Tenant activation/suspension
   
   All routes require admin authentication (to be integrated with auth system)."
  (:require [wagoe.tenant.ports :as tenant-ports]
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

(def ^:private tenant-input-explainer (m/explainer tenant-schema/TenantInput))
(def ^:private tenant-update-explainer (m/explainer tenant-schema/TenantUpdate))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn list-tenants-handler
  "List all tenants with optional filtering and pagination.
   
   Query params:
   - limit: Number of results (default 20, max 100)
   - offset: Pagination offset (default 0)
   - status: Filter by status (:active, :suspended)
   - search: Search by name or slug"
  [tenant-service]
  (fn [request]
    (try
      (let [params (:params request)
            limit (min (or (some-> (:limit params) parse-long) 20) 100)
            offset (or (some-> (:offset params) parse-long) 0)
            status (some-> (:status params) keyword)
            search (:search params)
            options (cond-> {:limit limit :offset offset}
                      status (assoc :status status)
                      search (assoc :search search))
            result (tenant-ports/list-tenants tenant-service options)]
        (json-response 200 result))
      (catch Exception e
        (log/error e "Failed to list tenants")
        (error-response 500 "Internal server error")))))

(defn get-tenant-handler
  "Get tenant by ID."
  [tenant-service]
  (fn [request]
    (try
      (let [tenant-id-str (get-in request [:path-params :id])
            tenant-id (parse-tenant-uuid tenant-id-str)]
        (if-not tenant-id
          (error-response 400 "Invalid tenant ID format")
          (if-let [tenant (tenant-ports/get-tenant tenant-service tenant-id)]
            (json-response 200 tenant)
            (error-response 404 "Tenant not found"))))
      (catch Exception e
        (log/error e "Failed to get tenant")
        (error-response 500 "Internal server error")))))

(defn create-tenant-handler
  "Create new tenant.
   
   Request body:
   - name: Tenant display name (required)
   - slug: Tenant slug for URLs (required, unique)
   - status: Initial status (:active or :suspended, default :active)"
  [tenant-service]
  (fn [request]
    (try
      (let [body (or (:body-params request)
                     (json/parse-string (slurp (:body request)) true))
            tenant-input {:name (:name body)
                          :slug (:slug body)
                          :status (or (some-> (:status body) keyword) :active)}]
        ;; Validate input
        (if-let [errors (tenant-input-explainer tenant-input)]
          (validation-error-response errors)
          ;; Create tenant
          (let [result (tenant-ports/create-new-tenant tenant-service tenant-input)]
            (if (:success? result)
              (json-response 201 (:tenant result))
              (error-response 400 (:error result))))))
      (catch Exception e
        (log/error e "Failed to create tenant")
        (error-response 500 "Internal server error")))))

(defn update-tenant-handler
  "Update existing tenant.
   
   Request body:
   - name: New display name (optional)
   - slug: New slug (optional, must be unique)
   - status: New status (optional)"
  [tenant-service]
  (fn [request]
    (try
      (let [tenant-id-str (get-in request [:path-params :id])
            tenant-id (parse-tenant-uuid tenant-id-str)]
        (if-not tenant-id
          (error-response 400 "Invalid tenant ID format")
          (let [body (or (:body-params request)
                         (json/parse-string (slurp (:body request)) true))
                update-data (cond-> {}
                              (:name body) (assoc :name (:name body))
                              (:slug body) (assoc :slug (:slug body))
                              (:status body) (assoc :status (keyword (:status body))))]
            ;; Validate update data
            (if-let [errors (tenant-update-explainer update-data)]
              (validation-error-response errors)
              ;; Update tenant
              (let [result (tenant-ports/update-existing-tenant tenant-service tenant-id update-data)]
                (if (:success? result)
                  (json-response 200 (:tenant result))
                  (error-response 400 (:error result))))))))
      (catch Exception e
        (log/error e "Failed to update tenant")
        (error-response 500 "Internal server error")))))

(defn delete-tenant-handler
  "Delete tenant (soft delete).
   
   This marks the tenant as deleted but preserves data for audit purposes."
  [tenant-service]
  (fn [request]
    (try
      (let [tenant-id-str (get-in request [:path-params :id])
            tenant-id (parse-tenant-uuid tenant-id-str)]
        (if-not tenant-id
          (error-response 400 "Invalid tenant ID format")
          (let [result (tenant-ports/delete-existing-tenant tenant-service tenant-id)]
            (if (:success? result)
              (json-response 200 {:message "Tenant deleted successfully"})
              (error-response 400 (:error result))))))
      (catch Exception e
        (log/error e "Failed to delete tenant")
        (error-response 500 "Internal server error")))))

(defn suspend-tenant-handler
  "Suspend tenant (prevents login and access)."
  [tenant-service]
  (fn [request]
    (try
      (let [tenant-id-str (get-in request [:path-params :id])
            tenant-id (parse-tenant-uuid tenant-id-str)]
        (if-not tenant-id
          (error-response 400 "Invalid tenant ID format")
          (let [result (tenant-ports/suspend-tenant tenant-service tenant-id)]
            (if (:success? result)
              (json-response 200 {:message "Tenant suspended successfully"})
              (error-response 400 (:error result))))))
      (catch Exception e
        (log/error e "Failed to suspend tenant")
        (error-response 500 "Internal server error")))))

(defn activate-tenant-handler
  "Activate suspended tenant."
  [tenant-service]
  (fn [request]
    (try
      (let [tenant-id-str (get-in request [:path-params :id])
            tenant-id (parse-tenant-uuid tenant-id-str)]
        (if-not tenant-id
          (error-response 400 "Invalid tenant ID format")
          (let [result (tenant-ports/activate-tenant tenant-service tenant-id)]
            (if (:success? result)
              (json-response 200 {:message "Tenant activated successfully"})
              (error-response 400 (:error result))))))
      (catch Exception e
        (log/error e "Failed to activate tenant")
        (error-response 500 "Internal server error")))))

(defn provision-tenant-handler
  "Provision tenant database schema and initial data.
   
   This creates:
   - PostgreSQL schema for tenant
   - Initial database tables (copies structure from public schema)
   
   Note: Only works with PostgreSQL multi-tenant setup.
   Provisioning is idempotent - safe to call multiple times."
  [tenant-service db-context]
  (fn [request]
    (try
      (let [tenant-id-str (get-in request [:path-params :id])
            tenant-id (parse-tenant-uuid tenant-id-str)]
        (cond
          (not tenant-id)
          (error-response 400 "Invalid tenant ID format")

          (not db-context)
          (error-response 500 "Database context not available")

          :else
          (let [tenant (tenant-ports/get-tenant tenant-service tenant-id)]
            (if-not tenant
              (error-response 404 "Tenant not found")
              (let [result (provisioning/provision-tenant! db-context tenant)]
                (json-response 200 result))))))

      (catch clojure.lang.ExceptionInfo e
        (let [ex-data (ex-data e)]
          (case (:type ex-data)
            :not-supported
            (error-response 501 (:message ex-data) ex-data)

            :not-found
            (error-response 404 "Tenant not found" ex-data)

            :provisioning-error
            (do
              (log/error e "Tenant provisioning failed")
              (error-response 500 "Tenant provisioning failed" ex-data))

            ;; Default case
            (do
              (log/error e "Unexpected error during provisioning")
              (error-response 500 "Internal server error")))))

      (catch Exception e
        (log/error e "Failed to provision tenant")
        (error-response 500 "Internal server error")))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn tenant-routes
  "This module's contribution to the route table: Reitit route data under
   :api, at paths relative to /api/v1.

   All routes require admin authentication (to be integrated)."
  [tenant-service db-context _config]
  {:api
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
                         501 {:description "Not supported (requires PostgreSQL)"}}}}]]})
