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
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [wagoe.core.http.errors :as errors]
            [wagoe.platform.database :as db]
            [wagoe.tenant.core.tenant :as tenant-core]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.tenant.shell.provisioning :as provisioning]))

(defn- error-response
  "The one error body, encoded here: this middleware may sit outside content
   negotiation."
  [status type message request]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string
             (errors/body type message {:correlation-id (:correlation-id request)}))})

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

(defn- as-identifier
  ([v] (as-identifier v (if (parse-uuid (str v)) :id :slug)))
  ([v type] (when v {:type type :value v})))

(defn- tenant-identifiers
  "Where the request may name its tenant, first first: the subdomain (always a
   slug), a JWT claim, the X-Tenant-Slug/X-Tenant-Id header, then the signed-in
   user's own tenant. The user's comes last so a tenant named by header still
   reaches the scope gate, which refuses it or admits a member (BOU-982)."
  [request]
  (keep identity
        [(as-identifier (extract-subdomain request) :slug)
         (as-identifier (extract-jwt-tenant request))
         (as-identifier (extract-header-tenant request))
         (as-identifier (some-> (get-in request [:user :tenant-id]) str))]))

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
   Cache entries expire after `default-cache-ttl-ms` unless told otherwise.
   
   Returns:
     Atom containing cache map"
  []
  (atom {}))

(def ^:private default-cache-ttl-ms
  "How long a looked-up tenant is trusted: a suspension takes effect within it
   (BOU-992). It was an hour."
  ;; ponytail: a TTL, not invalidation; evict on suspend if a minute is too long.
  (* 60 1000))

(defn- cached-tenant-lookup
  "Look up tenant with caching.
   
   Cache key: [type value] (e.g., [:slug 'acme-corp'])
   Cache TTL: `ttl-ms`
   
   Args:
     cache: Atom containing cache map
     identifier: Map with :type and :value
     
   Returns:
     Tenant entity map or nil"
  [cache identifier ttl-ms]
  (let [cache-key [(:type identifier) (:value identifier)]
        now (System/currentTimeMillis)]

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
     
   A tenant that is not active answers 403, whether or not one is required:
   a suspended tenant served on (BOU-992). `:cache-ttl-ms` is how long a
   cached tenant is trusted (default: one minute).

   Example:
     (wrap-tenant-resolution handler tenant-service {:require-tenant? true})"
  ([handler tenant-service]
   (wrap-tenant-resolution handler tenant-service {}))
  ([handler tenant-service {:keys [require-tenant? cache cache-ttl-ms]
                            :or {require-tenant? false
                                 cache (create-tenant-cache)
                                 cache-ttl-ms default-cache-ttl-ms}}]
   (fn [request]
     (let [identifiers (tenant-identifiers request)]

       (if-not (seq identifiers)
         ;; No tenant identifier found
         (if require-tenant?
           (error-response 404 :not-found "No tenant identifier in request" request)
           ;; Tenant optional - continue without tenant
           (handler request))

         ;; Try to lookup tenant
         ;; The first source naming a tenant that exists: a host like app.example.com
         ;; names none, and its users then run in their own (BOU-982)
         (let [[identifier cached-tenant tenant]
               (or (some (fn [id]
                           (let [cached (cached-tenant-lookup cache id cache-ttl-ms)]
                             (when-let [t (or cached (lookup-tenant tenant-service id))]
                               [id cached t])))
                         identifiers)
                   [(first identifiers) nil nil])]

           (if-not tenant
             ;; Tenant not found in database
             (do
               (log/warn "Tenant not found"
                         {:identifier identifier
                          :uri (:uri request)
                          :method (:request-method request)})
               (if require-tenant?
                 (error-response 404 :not-found
                                 (str "Tenant '" (:value identifier) "' does not exist")
                                 request)
                 ;; Tenant optional - continue without tenant
                 (handler request)))

             ;; Tenant found - cache and add to request
             (do
               (when-not cached-tenant
                 (cache-tenant cache identifier tenant))

               ;; Another ITenantService may hand the status over as a string
               (if (tenant-core/tenant-active? (update tenant :status #(some-> % keyword)))
                 (do
                   (log/debug "Tenant resolved"
                              {:tenant-id (:id tenant)
                               :tenant-slug (:slug tenant)
                               :schema-name (:schema-name tenant)
                               :uri (:uri request)})

                   ;; Add tenant to request and continue
                   (handler (assoc request :tenant tenant)))
                 (error-response 403 :forbidden "Tenant is not active" request))))))))))

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
          ;; One connection for the full request, so the tenant search_path
          ;; applies to every query downstream.
          (binding [db/*tenant-id* (:id tenant)]
            (provisioning/call-in-schema db-context schema-name (fn [_] (handler request))))

          (catch Exception e
            (log/error "Failed to execute request in tenant schema"
                       {:tenant-id (:id tenant)
                        :schema-name schema-name
                        :error (.getMessage e)
                        :uri (:uri request)})
            (error-response 500 :internal-error "Failed to execute request" request))))

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
