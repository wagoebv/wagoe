(ns wagoe.tenant.shell.tenant-middleware
  "HTTP middleware for multi-tenant request handling (imperative shell).
   
   This namespace provides middleware for tenant resolution and database schema
   switching in multi-tenant applications following the schema-per-tenant pattern.
   
   Features:
   - Subdomain-based tenant resolution
   - JWT claim fallback for API access
   - Database schema switching per request
   - Tenant caching for performance
   - Error handling for missing/invalid tenants
   
   Based on: docs/adr/ADR-004-multi-tenancy-architecture.md"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.platform.database :as db]))

;; =============================================================================
;; Tenant Extraction
;; =============================================================================

(defn- extract-subdomain
  "Extract tenant slug from subdomain in request.
   
   Examples:
   - acme-corp.myapp.com → 'acme-corp'
   - widgets-inc.myapp.com → 'widgets-inc'
   - myapp.com → nil (main domain, no tenant)
   - localhost:3000 → nil (development, no tenant)
   
   Args:
     request: Ring request map
     
   Returns:
     Tenant slug string or nil"
  [request]
  (let [server-name (:server-name request)
        parts (str/split server-name #"\.")]
    ;; Only extract subdomain if there are 3+ parts (subdomain.domain.tld)
    ;; Skip localhost and single-domain names
    (when (and (>= (count parts) 3)
               (not (str/starts-with? server-name "localhost")))
      (first parts))))

(defn- extract-jwt-tenant
  "Extract tenant ID or slug from JWT claims.
   
   Looks for tenant information in JWT identity added by auth middleware.
   
   Priority:
   1. :tenant-slug (preferred - human-readable)
   2. :tenant-id (fallback - UUID)
   
   Args:
     request: Ring request map
     
   Returns:
     Tenant identifier string or nil"
  [request]
  (or (get-in request [:identity :tenant-slug])
      (when-let [tenant-id (get-in request [:identity :tenant-id])]
        (str tenant-id))))

(defn- extract-header-tenant
  "Extract tenant from HTTP header (X-Tenant-Slug or X-Tenant-Id).
   
   Useful for service-to-service communication or testing.
   
   Args:
     request: Ring request map
     
   Returns:
     Tenant identifier string or nil"
  [request]
  (or (get-in request [:headers "x-tenant-slug"])
      (get-in request [:headers "x-tenant-id"])))

(defn- resolve-tenant-identifier
  "Resolve tenant identifier from request using multiple strategies.
   
   Extraction priority:
   1. Subdomain (acme-corp.myapp.com)
   2. JWT claim (from authenticated user)
   3. HTTP header (X-Tenant-Slug or X-Tenant-Id)
   
   Args:
     request: Ring request map
     
   Returns:
     {:type :slug, :value \"acme-corp\"} or
     {:type :id, :value \"uuid-string\"} or
     nil"
  [request]
  (let [subdomain (extract-subdomain request)
        jwt-tenant (extract-jwt-tenant request)
        header-tenant (extract-header-tenant request)]
    (cond
      ;; Subdomain is always a slug
      subdomain {:type :slug :value subdomain}

      ;; JWT could be slug or ID - check format
      jwt-tenant (if (re-matches #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" jwt-tenant)
                   {:type :id :value jwt-tenant}
                   {:type :slug :value jwt-tenant})

      ;; Header could be slug or ID - check format
      header-tenant (if (re-matches #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" header-tenant)
                      {:type :id :value header-tenant}
                      {:type :slug :value header-tenant})

      :else nil)))

;; =============================================================================
;; Tenant Lookup
;; =============================================================================

(defn- lookup-tenant
  "Look up tenant entity from identifier.
   
   Uses tenant service to find tenant by slug or ID.
   
   Args:
     tenant-service: ITenantService implementation
     identifier: Map with :type (:slug or :id) and :value
     
   Returns:
     Tenant entity map or nil"
  [tenant-service identifier]
  (try
    (case (:type identifier)
      :slug (tenant-ports/get-tenant-by-slug tenant-service (:value identifier))
      :id (tenant-ports/get-tenant tenant-service (java.util.UUID/fromString (:value identifier)))
      nil)
    (catch Exception e
      (log/warn "Failed to lookup tenant"
                {:identifier identifier
                 :error (.getMessage e)})
      nil)))

;; =============================================================================
;; Caching
;; =============================================================================

(defn- create-tenant-cache
  "Create in-memory tenant cache with TTL.
   
   Simple atom-based cache for tenant lookups to reduce database queries.
   Cache entries expire after 1 hour.
   
   Returns:
     Atom containing cache map"
  []
  (atom {}))

(defn- cached-tenant-lookup
  "Look up tenant with caching.
   
   Cache key: [type value] (e.g., [:slug 'acme-corp'])
   Cache TTL: 1 hour
   
   Args:
     cache: Atom containing cache map
     tenant-service: ITenantService implementation (unused - for future cache invalidation)
     identifier: Map with :type and :value
     
   Returns:
     Tenant entity map or nil"
  [cache _tenant-service identifier]
  (let [cache-key [(:type identifier) (:value identifier)]
        now (System/currentTimeMillis)
        ttl-ms (* 60 60 1000)] ; 1 hour

    ;; Check cache
    (if-let [cached (get @cache cache-key)]
      (if (< (- now (:timestamp cached)) ttl-ms)
        ;; Cache hit and not expired
        (do
          (log/debug "Tenant cache hit" {:identifier identifier})
          (:tenant cached))
        ;; Cache expired
        (do
          (log/debug "Tenant cache expired" {:identifier identifier})
          (swap! cache dissoc cache-key)
          nil))
      ;; Cache miss
      nil)))

(defn- cache-tenant
  "Store tenant in cache.
   
   Args:
     cache: Atom containing cache map
     identifier: Map with :type and :value
     tenant: Tenant entity map"
  [cache identifier tenant]
  (let [cache-key [(:type identifier) (:value identifier)]]
    (swap! cache assoc cache-key
           {:tenant tenant
            :timestamp (System/currentTimeMillis)})))

;; =============================================================================
;; Schema Switching
;; =============================================================================

(defn- set-tenant-schema
  "Set database search_path to tenant schema.
   
   Executes SET search_path SQL command to switch to tenant schema.
   The search_path includes the tenant schema and public schema (for shared tables).
   
   Args:
     db-ctx: Database context with connection or transaction
     schema-name: Tenant schema name (e.g., 'tenant_acme_corp')
     
   Throws:
     Exception if schema switching fails"
  [db-ctx schema-name]
  (db/execute-ddl! db-ctx
                   (str "SET search_path TO " schema-name ", public")))

;; =============================================================================
;; Middleware - Tenant Resolution
;; =============================================================================

(defn wrap-tenant-resolution
  "Middleware to resolve tenant from request and add to request context.
   
   Extracts tenant information from subdomain, JWT, or headers and looks up
   the tenant entity from the database. Adds tenant to request map for use
   by downstream handlers.
   
   Tenant extraction priority:
   1. Subdomain (acme-corp.myapp.com → tenant_acme_corp schema)
   2. JWT claim (from authenticated user)
   3. HTTP header (X-Tenant-Slug or X-Tenant-Id)
   
   If no tenant is found and tenant is required, returns 404 response.
   If tenant is optional (require-tenant? = false), continues without tenant.
   
   Args:
     handler: Ring handler function
     tenant-service: ITenantService implementation
     options: Map with optional configuration:
       :require-tenant? - If true, return 404 when tenant not found (default: false)
       :cache - Atom for tenant cache (default: creates new cache)
       
   Returns:
     Ring middleware function
     
   Example:
     (wrap-tenant-resolution handler tenant-service {:require-tenant? true})"
  ([handler tenant-service]
   (wrap-tenant-resolution handler tenant-service {}))
  ([handler tenant-service {:keys [require-tenant? cache]
                            :or {require-tenant? false
                                 cache (create-tenant-cache)}}]
   (fn [request]
     (let [identifier (resolve-tenant-identifier request)]

       (if-not identifier
         ;; No tenant identifier found
         (if require-tenant?
           {:status 404
            :headers {"Content-Type" "application/json"}
            :body {:error "Tenant not found"
                   :message "No tenant identifier in request"
                   :correlation-id (:correlation-id request)}}
           ;; Tenant optional - continue without tenant
           (handler request))

         ;; Try to lookup tenant
         (let [cached-tenant (cached-tenant-lookup cache tenant-service identifier)
               tenant (or cached-tenant
                          (lookup-tenant tenant-service identifier))]

           (if-not tenant
             ;; Tenant not found in database
             (do
               (log/warn "Tenant not found"
                         {:identifier identifier
                          :uri (:uri request)
                          :method (:request-method request)})
               (if require-tenant?
                 {:status 404
                  :headers {"Content-Type" "application/json"}
                  :body {:error "Tenant not found"
                         :message (str "Tenant '" (:value identifier) "' does not exist")
                         :correlation-id (:correlation-id request)}}
                 ;; Tenant optional - continue without tenant
                 (handler request)))

             ;; Tenant found - cache and add to request
             (do
               (when-not cached-tenant
                 (cache-tenant cache identifier tenant))

               (log/debug "Tenant resolved"
                          {:tenant-id (:id tenant)
                           :tenant-slug (:slug tenant)
                           :schema-name (:schema-name tenant)
                           :uri (:uri request)})

               ;; Add tenant to request and continue
               (handler (assoc request :tenant tenant))))))))))

;; =============================================================================
;; Middleware - Schema Switching
;; =============================================================================

(defn wrap-tenant-schema
  "Middleware to switch database schema based on tenant in request.
   
   Uses the tenant entity in request (added by wrap-tenant-resolution) to
   switch the database search_path to the tenant's schema. This enables
   schema-per-tenant multi-tenancy where each tenant has isolated tables.
   
   IMPORTANT: This middleware must run AFTER wrap-tenant-resolution.
   
   The database search_path is set to:
   - Tenant schema (e.g., tenant_acme_corp) - for tenant-specific tables
   - public schema - for shared tables (tenants, auth_users)
   
   If no tenant is in request, the database schema is NOT changed (remains public).
   
   Args:
     handler: Ring handler function
     db-context: Database context with connection pool
     
   Returns:
     Ring middleware function
     
   Example:
     (-> handler
         (wrap-tenant-resolution tenant-service)
         (wrap-tenant-schema db-context))"
  [handler db-context]
  (fn [request]
    (if-let [tenant (:tenant request)]
      ;; Tenant present - switch schema
      (let [schema-name (:schema-name tenant)]
        (log/debug "Switching to tenant schema"
                   {:tenant-id (:id tenant)
                    :schema-name schema-name})

        (try
          (db/with-transaction* db-context
            (fn [tx]
              ;; Keep the same JDBC connection for the full request so the
              ;; tenant search_path actually applies to all downstream queries.
              (set-tenant-schema tx schema-name)
              (handler request)))

          (catch Exception e
            (log/error "Failed to execute request in tenant schema"
                       {:tenant-id (:id tenant)
                        :schema-name schema-name
                        :error (.getMessage e)
                        :uri (:uri request)})
            {:status 500
             :headers {"Content-Type" "application/json"}
             :body {:error "Internal server error"
                    :message "Failed to execute request"
                    :correlation-id (:correlation-id request)}})))

      ;; No tenant - continue without schema switching
      (handler request))))

;; =============================================================================
;; Combined Middleware
;; =============================================================================

(defn wrap-multi-tenant
  "Combined middleware for full multi-tenant support.
   
   Applies both tenant resolution and schema switching in correct order.
   Convenience function for common use case.
   
   Args:
     handler: Ring handler function
     tenant-service: ITenantService implementation
     db-context: Database context with connection pool
     options: Map with optional configuration (passed to wrap-tenant-resolution):
       :require-tenant? - If true, return 404 when tenant not found (default: false)
       :cache - Atom for tenant cache (default: creates new cache)
       
   Returns:
     Ring middleware function
     
   Example:
     (wrap-multi-tenant handler tenant-service db-context {:require-tenant? true})"
  ([handler tenant-service db-context]
   (wrap-multi-tenant handler tenant-service db-context {}))
  ([handler tenant-service db-context options]
   (-> handler
       (wrap-tenant-schema db-context)
       (wrap-tenant-resolution tenant-service options))))
