(ns wagoe.platform.shell.interfaces.http.routes
  "Common HTTP routing infrastructure and standard endpoints.

   This namespace provides the foundational routing structure for the application,
   including standard endpoints like health checks and API documentation, along
   with infrastructure for injecting module-specific routes.

   Features:
   - Health check endpoints
   - OpenAPI/Swagger documentation routes
   - Module route injection system
   - Common route middleware configuration
   - Standardized route structure"
  (:require [wagoe.platform.shell.interfaces.http.common :as http-common]
            [wagoe.platform.shell.interfaces.http.middleware :as http-middleware]
            [clojure.string]
            [muuntaja.core :as m]
            [reitit.coercion.malli :as reitit-malli]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.resource :refer [wrap-resource]]))

;; =============================================================================
;; Common Route Configuration
;; =============================================================================

(def ^:private default-middleware
  "Default middleware stack for all routes."
  [http-middleware/wrap-correlation-id
   http-middleware/wrap-request-logging
   wrap-cookies
   parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   muuntaja/format-request-middleware
   coercion/coerce-request-middleware
   coercion/coerce-response-middleware])

(def ^:private route-data
  "Default route data configuration."
  {:coercion reitit-malli/coercion
   :muuntaja m/instance
   :middleware default-middleware})

;; =============================================================================
;; Standard Health Check Routes
;; =============================================================================

(defn health-routes
  "Create standard health check routes.

       Args:
         config: Application configuration
         additional-checks: Optional function for additional health checks

       Returns:
         Vector of health check route definitions"
  ([config]
   (health-routes config nil))
  ([config additional-checks]
   [["/health"
     {:no-doc true
      :get {:summary "Health check endpoint"
            :description "Returns service health status and basic information"
            :responses {200 {:body [:map
                                    [:status :string]
                                    [:service :string]
                                    [:version :string]
                                    [:timestamp :string]]}}
            :handler (http-common/health-check-handler
                      (get-in config [:active :wagoe/settings :name] "wagoe")
                      (get-in config [:active :wagoe/settings :version] "unknown")
                      (when (and additional-checks (fn? additional-checks))
                        additional-checks))}}]

    ["/health/ready"
     {:no-doc true
      :get {:summary "Readiness check"
            :description "Returns 200 when service is ready to accept traffic"
            :responses {200 {:body [:map [:status [:enum "ready"]]]}}
            :handler (fn [_] {:status 200 :body {:status "ready"}})}}]

    ["/health/live"
     {:no-doc true
      :get {:summary "Liveness check"
            :description "Returns 200 when service is alive"
            :responses {200 {:body [:map [:status [:enum "alive"]]]}}
            :handler (fn [_] {:status 200 :body {:status "alive"}})}}]]))

;; =============================================================================
;; API Documentation Routes
;; =============================================================================

(defn api-docs-routes
  "Create OpenAPI/Swagger documentation routes with CSS fix for path rendering.

       Args:
         config: Application configuration

       Returns:
         Vector of API documentation route definitions"
  [config]
  [["/swagger.json"
    {:get {:no-doc true
           :swagger {:info {:title (get-in config [:active :wagoe/settings :name] "Wagoe API")
                            :description "RESTful API for Wagoe Application. Most business endpoints require authentication. Use POST /api/v1/auth/login or POST /api/v1/sessions to obtain a token, then click the Authorize button in Swagger UI and provide either 'Bearer <JWT>' or 'X-Session-Token'."
                            :version (get-in config [:active :wagoe/settings :version] "1.0.0")
                            :contact {:name "Support"
                                      :email "support@wagoe.example.com"}}
                     :tags [{:name "health" :description "Health check endpoints"}
                            {:name "users" :description "User management"}
                            {:name "sessions" :description "Session management"}]
                     :securityDefinitions {:BearerAuth {:type "apiKey"
                                                        :name "Authorization"
                                                        :in "header"
                                                        :description "JWT access token. Use format: 'Bearer <token>'"}
                                           :SessionToken {:type "apiKey"
                                                          :name "X-Session-Token"
                                                          :in "header"
                                                          :description "Session token. Optional alternative to Bearer auth."}}
                     :security [{:BearerAuth []}
                                {:SessionToken []}]}
           :handler (swagger/create-swagger-handler)}}]

   ["/api-docs"
    {:get {:no-doc true
           :handler (fn [_] {:status 302 :headers {"Location" "/api-docs/"}})}}]

   ["/api-docs/"
    {:get {:no-doc true
           :handler (swagger-ui/create-swagger-ui-handler
                     {:url "/swagger.json"
                      :config {:validatorUrl nil
                               :tryItOutEnabled true
                               :supportedSubmitMethods ["get" "post" "put" "patch" "delete"]}
                      :options {:custom-css ".opblock-summary-path { min-width: 200px !important; width: auto !important; } .opblock-summary-path span { word-break: normal !important; white-space: normal !important; display: inline !important; }"}})}}]

   ["/api-docs/*"
    {:get {:no-doc true
           :handler (swagger-ui/create-swagger-ui-handler
                     {:url "/swagger.json"
                      :config {:validatorUrl nil
                               :tryItOutEnabled true
                               :supportedSubmitMethods ["get" "post" "put" "patch" "delete"]}
                      :options {:custom-css ".opblock-summary-path { min-width: 200px !important; width: auto !important; } .opblock-summary-path span { word-break: normal !important; white-space: normal !important; display: inline !important; }"}})}}]])

;; =============================================================================
;; Module Route Injection System
;; =============================================================================

;; NOTE: The older module route injector helper has been removed in favor of
;; using `create-router` and `create-app` directly. If you need a module
;; injector, you can easily build one on top of those functions.

;; =============================================================================
;; Route Builder Functions
;; =============================================================================

(defn create-router
  "Create a complete router with common routes and injected module routes.

       Args:
         config: Application configuration
         module-routes: Vector of module-specific route definitions
         options: Optional configuration map with:
           - :additional-health-checks - Function for additional health checks
           - :error-mappings - Custom error type mappings
           - :middleware - Additional middleware to include

       Returns:
         Reitit router with all routes configured

       Example:
         (create-router config user-routes
                        {:error-mappings {:user-not-found [404 \"User Not Found\"]}
                         :additional-health-checks (fn [] {:database \"connected\"})})"
  [config module-routes & {:keys [additional-health-checks error-mappings extra-middleware]
                           :or {additional-health-checks nil
                                error-mappings {}
                                extra-middleware []}}]
  (let [common-routes (concat (health-routes config additional-health-checks)
                              (api-docs-routes config))
        ;; In dev mode, inject devtools error enrichment INSIDE the exception handler.
        ;; Reitit applies middleware last=outermost, so dev middleware goes before
        ;; wrap-exception-handling: it catches exceptions, enriches ex-data with
        ;; :dev-info, re-throws, then exception handler includes :dev-info in RFC 7807.
        dev-error-middleware (when (= :dev (:wagoe/profile config))
                               (try
                                 (require 'wagoe.devtools.shell.http-error-middleware)
                                 (ns-resolve 'wagoe.devtools.shell.http-error-middleware
                                             'wrap-dev-error-enrichment)
                                 (catch Exception _ nil)))
        enhanced-middleware (concat default-middleware
                                    extra-middleware
                                    (when dev-error-middleware [dev-error-middleware])
                                    [(http-middleware/wrap-exception-handling error-mappings)])
        enhanced-route-data (assoc route-data :middleware enhanced-middleware)
        ;; Separate routes that should be under /api from root-level routes
        ;; Root-level routes: static assets (/css, /js, /modules), web UI (/web), docs
        ;; API routes: all other routes go under /api prefix
        {api-routes true root-routes false}
        (group-by (fn [[path _]]
                    (not (or (clojure.string/starts-with? path "/css")
                             (clojure.string/starts-with? path "/js")
                             (clojure.string/starts-with? path "/modules")
                             (clojure.string/starts-with? path "/docs")
                             (clojure.string/starts-with? path "/web"))))
                  module-routes)]

    (ring/router
     [["" {:middleware [http-middleware/wrap-correlation-id
                        http-middleware/wrap-request-logging
                        wrap-cookies]}
       (concat common-routes root-routes)]
      ["/api/v1" {:data enhanced-route-data}
       api-routes]]
     {:data route-data
      :conflicts nil})))

(defn create-handler
  "Create a complete Ring handler with routes and fallback handlers.
       
       Serves static resources from /public directory BEFORE routing,
       bypassing content negotiation middleware that causes 406 errors.

       Args:
         router: Reitit router instance
         options: Optional configuration map

       Returns:
         Ring handler function

       Example:
         (create-handler (create-router config module-routes))"
  [router & {:keys [not-found-handler method-not-allowed-handler]
             :or {not-found-handler (http-common/create-not-found-handler)
                  method-not-allowed-handler nil}}]
  ;; Create the base Reitit handler
  (let [reitit-handler (ring/ring-handler
                        router
                        (ring/create-default-handler
                         (cond-> {:not-found not-found-handler}
                           method-not-allowed-handler (assoc :method-not-allowed method-not-allowed-handler))))]
    ;; Wrap with resource middleware to serve static files from public/ directory
    ;; This intercepts requests for static files BEFORE they hit Reitit routing,
    ;; bypassing content negotiation middleware that causes 406 errors
    (-> reitit-handler
        (wrap-resource "public")
        wrap-content-type)))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn create-app
  "Create a complete application with routes and handlers.

       This is a convenience function that combines router creation and handler
       setup into a single step for simple applications.

       Args:
         config: Application configuration
         module-routes: Vector of module-specific route definitions
         options: Same options as create-router plus handler options

       Returns:
         Complete Ring application handler

       Example:
         (def app (create-app config user-routes
                             {:error-mappings user-errors
                              :additional-health-checks health-fn}))"
  [config module-routes & options]
  (let [router (apply create-router config module-routes options)]
    (apply create-handler router options)))

(defn wrap-common-middleware
  "Wrap a handler with the standard common middleware stack.

       This is useful when you need to apply the same middleware to custom
       handlers that aren't part of the main route structure.

       Args:
         handler: Ring handler function
         error-mappings: Optional custom error mappings

       Returns:
         Handler wrapped with common middleware"
  ([handler]
   (wrap-common-middleware handler {}))
  ([handler error-mappings]
   (-> handler
       (http-middleware/wrap-exception-handling error-mappings)
       coercion/coerce-response-middleware
       coercion/coerce-request-middleware
       muuntaja/format-request-middleware
       muuntaja/format-response-middleware
       muuntaja/format-negotiate-middleware
       parameters/parameters-middleware
       wrap-cookies
       http-middleware/wrap-request-logging
       http-middleware/wrap-correlation-id)))

