(ns wagoe.platform.shell.http.reitit-router
  "Reitit router adapter - converts normalized route specs to Reitit routing.

   This adapter implements the IRouter protocol to translate framework-agnostic
   normalized route specifications into Reitit-specific route definitions."
  (:require             [wagoe.platform.ports.http :as ports]
                        [wagoe.platform.shell.http.interceptors :as http-interceptors]
                        [cheshire.core :as json]
                        [malli.error :as me]
                        [clojure.tools.logging :as log]
                        [clojure.string :as str]
                        [reitit.coercion.malli :as malli-coercion]
                        [reitit.ring :as ring]
                        [reitit.ring.coercion :as coercion]
                        [reitit.ring.middleware.exception :as exception]
                        [reitit.ring.middleware.muuntaja :as muuntaja]
                        [reitit.ring.middleware.parameters :as parameters]
                        [reitit.swagger :as swagger]
                        [reitit.swagger-ui :as swagger-ui]
                        [ring.middleware.resource :refer [wrap-resource]]
                        [ring.middleware.cookies :refer [wrap-cookies]]
                        [muuntaja.core :as m])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util.zip GZIPOutputStream]))

;; =============================================================================
;; Symbol Resolution
;; =============================================================================

(defn- resolve-handler-fn
  "Resolve qualified symbol to handler function.
  
  Args:
    handler-sym - Either a function (returned as-is) or qualified symbol
    
  Returns:
    Function
    
  Throws:
    Exception if symbol cannot be resolved"
  [handler-sym]
  (if (symbol? handler-sym)
    (or (requiring-resolve handler-sym)
        (throw (ex-info "Could not resolve handler function"
                        {:type :configuration-error
                         :handler handler-sym})))
    handler-sym))

(defn- resolve-middleware-fns
  "Resolve vector of qualified symbols to middleware functions.
   
   Args:
     middleware-syms - Vector of qualified symbols or functions
     
   Returns:
     Vector of middleware functions"
  [middleware-syms]
  (mapv resolve-handler-fn (or middleware-syms [])))

(defn- resolve-interceptors
  "Resolve vector of interceptor specs to actual interceptor instances.
   
   Interceptor specs can be:
   - Qualified symbols referencing interceptor definitions
   - Functions that return interceptors
   - Interceptor maps directly
   
   Args:
     interceptor-specs - Vector of interceptor specs
     
   Returns:
     Vector of interceptor maps"
  [interceptor-specs]
  (when (seq interceptor-specs)
    (mapv (fn [spec]
            (cond
              ;; Symbol - resolve and call if function
              (symbol? spec)
              (let [resolved (resolve-handler-fn spec)]
                (if (fn? resolved)
                  (resolved)  ; Call function to get interceptor
                  resolved))  ; Already an interceptor map

              ;; Function - call to get interceptor
              (fn? spec)
              (spec)

              ;; Map - assume it's an interceptor
              (map? spec)
              spec

              :else
              (throw (ex-info "Invalid interceptor spec"
                              {:spec spec
                               :type (type spec)}))))
          interceptor-specs)))

(defn- interceptors->middleware
  "Convert vector of HTTP interceptors to a Ring middleware function.
   
   This adapter allows interceptors to be used in Reitit's :middleware chain.
   The middleware function runs the interceptor pipeline and extracts the response.
   
   Args:
     interceptors - Vector of interceptor maps
     system - Observability services map {:logger :metrics-emitter :error-reporter}
     
   Returns:
     Ring middleware function"
  [interceptors system]
  (fn [handler]
    (fn [request]
      ;; Use run-http-interceptors which handles the full pipeline
      (http-interceptors/run-http-interceptors handler interceptors request system))))

;; =============================================================================
;; Route Conversion
;; =============================================================================

(defn- convert-coercion
  "Convert normalized coercion spec to Reitit parameters format.
  
  Normalized format:
    {:query SomeSchema
     :body SomeSchema
     :path SomeSchema
     :response {200 SuccessSchema 400 ErrorSchema}}
     
  Reitit format:
    {:parameters {:query [...]
                  :body [...]
                  :path [...]}
     :responses {200 {:body [...]}
                 400 {:body [...]}}}
  
  Args:
    coercion-spec - Normalized coercion map
    
  Returns:
    Map with :parameters and :responses for Reitit"
  [coercion-spec]
  (when coercion-spec
    (let [params (select-keys coercion-spec [:query :body :path])
          responses (:response coercion-spec)]
      (cond-> {}
        (seq params)
        (assoc :parameters params)

        (seq responses)
        (assoc :responses (into {}
                                (map (fn [[status schema]]
                                       [status {:body schema}]))
                                responses))))))

(defn- convert-handler-config
  "Convert normalized handler config to Reitit handler data.
   
   Normalized format:
     {:handler 'ns/fn
      :middleware ['ns/mw1 'ns/mw2]
      :interceptors ['ns/int1 'ns/int2]
      :coercion {:query ... :response ...}
      :summary \"Description\"
      :tags [\"tag1\"]
      :produces [\"application/json\"]
      :consumes [\"application/json\"]}
      
   Reitit format:
     {:handler (fn ...)
      :middleware [(fn ...) (fn ...)]
      :parameters {:query ...}
      :responses {200 {:body ...}}
      :summary \"Description\"
      :tags [\"tag1\"]}
   
   Notes:
   - HTTP interceptors run for every matched endpoint.
   - The framework default stack is always applied.
   - Route-specific interceptors (via :interceptors) are appended after defaults.
   - :system is optional; if omitted, it defaults to {}.
   
   Args:
     handler-config - Normalized handler config map
     system - Optional observability services map {:logger :metrics-emitter :error-reporter}
     
   Returns:
     Reitit handler data map"
  ([handler-config]
   (convert-handler-config handler-config nil))

  ([{:keys [handler middleware interceptors coercion parameters summary tags produces consumes no-doc responses skip-interceptors?]} system]
   (let [resolved-handler (resolve-handler-fn handler)
         resolved-middleware (resolve-middleware-fns middleware)

         ;; Treat system services as optional; interceptors can run with {}.
         system (or system {})

         ;; Skip the default HTTP interceptor stack ONLY for routes that explicitly
         ;; opt out via :skip-interceptors? (genuinely-internal endpoints such as
         ;; health checks). This is decoupled from :no-doc — :no-doc only controls
         ;; Swagger visibility. All /web routes are :no-doc but MUST keep the default
         ;; stack so security interceptors (CSRF, security headers) apply to them.

         ;; Always apply default HTTP interceptors UNLESS route explicitly opts out;
         ;; append any route-specific interceptors.
         resolved-route-interceptors (or (resolve-interceptors interceptors) [])
         all-interceptors (if skip-interceptors?
                            resolved-route-interceptors  ; Only route-specific interceptors
                            (vec (concat http-interceptors/default-http-interceptors
                                         resolved-route-interceptors)))
         interceptor-middleware (when (seq all-interceptors)
                                  [(interceptors->middleware all-interceptors system)])

         ;; Combine regular middleware with interceptor-generated middleware.
         ;; We append interceptors last so they are closest to the resolved handler,
         ;; while still seeing a fully prepared request (session/body/coercions).
         all-middleware (concat resolved-middleware interceptor-middleware)

         ;; Support both :coercion (normalized) and :parameters (Reitit native) formats
         coercion-data (convert-coercion coercion)]
     (cond-> {:handler resolved-handler
              :middleware (vec all-middleware)}
       ;; Merge coercion data if present
       coercion-data
       (merge coercion-data)

       ;; Pass through :parameters if provided (Reitit native format)
       parameters
       (assoc :parameters parameters)

       ;; Pass through :responses if provided (Reitit native format)  
       responses
       (assoc :responses responses)

       summary
       (assoc :summary summary)

       (seq tags)
       (assoc :tags tags)

       (seq produces)
       (assoc :produces produces)

       (seq consumes)
       (assoc :consumes consumes)

       no-doc
       (assoc :no-doc no-doc)))))

(defn- convert-methods
  "Convert normalized methods map to Reitit format.
   
   Normalized format:
     {:get {:handler 'ns/fn ...}
      :post {:handler 'ns/fn ...}}
      
   Reitit format:
     {:get {:handler (fn ...) ...}
      :post {:handler (fn ...) ...}}
   
   Args:
     methods-map - Map of HTTP method keyword to handler config
     system - Optional observability services map {:logger :metrics-emitter :error-reporter} (defaults to {})
     
   Returns:
     Map of HTTP method keyword to Reitit handler data"
  ([methods-map]
   (convert-methods methods-map nil))

  ([methods-map system]
   (into {}
         (map (fn [[method handler-cfg]]
                [method (convert-handler-config handler-cfg system)]))
         methods-map)))

(defn- convert-route
  "Convert single normalized route entry to Reitit format.
   
   Handles nested routes via :children.
   
   IMPORTANT: When a route has both methods AND children, Reitit requires
   the parent methods to be defined as an empty string child route.
   
   Normalized format:
     {:path \"/users\"
      :methods {:get {...} :post {...}}
      :children [{:path \"/:id\" :methods {...}}]
      :meta {:middleware [...] :auth true}}
      
   Reitit format (when route has children):
     [\"/users\"
      {:middleware [...] :auth true}
      [\"\" {:get {...} :post {...}}]     ; Parent methods as empty string child
      [\"/:id\" {:get {...}}]]             ; Actual children
   
   Reitit format (when route has NO children):
     [\"/users\"
      {:middleware [...] :auth true
       :get {...}
       :post {...}}]
   
   Args:
     route-entry - Normalized route map
     system - Optional observability services map {:logger :metrics-emitter :error-reporter} (defaults to {})
     
   Returns:
     Reitit route vector [path data & children]"
  ([route-entry]
   (convert-route route-entry nil))

  ([{:keys [path methods children meta] :as route-entry} system]
   (let [;; Extract route-level data (everything except path, methods, children)
          ;; This includes :middleware, :auth, :name, etc.
         route-data (dissoc route-entry :path :methods :children)

          ;; Route-level middleware defined in :meta should run before
          ;; handler-level middleware and before HTTP interceptors so that
          ;; interceptors can observe the modified request.
         route-middleware (get-in meta [:middleware])
         methods-with-route-middleware (if (seq route-middleware)
                                         (into {}
                                               (map (fn [[method handler-cfg]]
                                                      [method (update handler-cfg :middleware
                                                                      (fn [mw]
                                                                        (vec (concat route-middleware
                                                                                     (or mw [])))))]))
                                               methods)
                                         methods)

          ;; Recursively convert children
         reitit-children (when (seq children)
                           (mapv #(convert-route % system) children))]

     (if (seq reitit-children)
        ;; Route HAS children: parent methods must be empty string child
       (let [reitit-methods (convert-methods methods-with-route-middleware system)
              ;; Create empty string child route for parent methods (if any)
             parent-child (when (seq reitit-methods)
                            ["" reitit-methods])]
          ;; Build: [path route-data empty-string-child ...children]
         (into [path route-data]
               (if parent-child
                 (into [parent-child] reitit-children)
                 reitit-children)))

        ;; Route has NO children: methods can be on route data directly
       (let [reitit-methods (convert-methods methods-with-route-middleware system)
             merged-data (merge route-data reitit-methods)]
         [path merged-data])))))


;; =============================================================================
;; Router Creation
;; =============================================================================

(defn- field-names-only
  "`{:email [\"invalid\"]}` — which fields, and nothing about why.

   What production returns. `me/humanize` renders the constraints of the schema into
   its messages: an `[:enum \"admin\" \"superuser\" \"internal-auditor\"]` comes back
   as \"should be either admin, superuser or internal-auditor\", so POSTing junk
   to an open endpoint enumerates roles nobody was told about. Bounds and
   regexes go the same way."
  [humanized]
  (when (map? humanized)
    (into {} (map (fn [[k _]] [k ["invalid"]])) humanized)))

(defn- humanize-coercion-errors
  "Field-level messages for a coercion failure — `{:email [\"missing required key\"]}`.

   Built here rather than read off the exception: reitit hands us `:errors`,
   `:schema` and `:value`, and `:errors` carries schema fragments. Malli turns
   the three into a map keyed by the fields the caller got wrong.

   `dev?` decides how much of the why is returned. The keys are what the caller sent
   either way; the messages describe the schema, and that is ours."
  [data dev?]
  (let [humanized (try
                    (me/humanize (select-keys data [:schema :value :errors]))
                    (catch Throwable _ nil))]
    (or (if dev? humanized (field-names-only humanized))
        {})))

(defn- coercion-error-response
  "400 for a request the client got wrong, naming the fields.

   Which fields went wrong is what the caller sent. Why they are wrong is the schema
   talking, so the full message is dev-only — the same gate the BND block uses."
  [system e request]
  (let [dev  (http-interceptors/dev-error-info system e)
        ;; This response never reaches the interceptor stack — it is produced
        ;; outside it — so the correlation header every other response carries
        ;; has to be set here. Without it a client reporting "your API 400s me"
        ;; has no id to hand to whoever reads the log.
        cid  (or (get-in request [:headers "x-correlation-id"])
                 (str (java.util.UUID/randomUUID)))
        body (cond-> {:error         "validation-error"
                      :message       "Request validation failed"
                      :details       (humanize-coercion-errors (ex-data e) (some? dev))
                      :correlationId cid}
               dev (assoc :dev dev))]
    {:status  400
     :headers {"X-Correlation-ID" cid}
     :body    body}))

(defn- server-error-response
  "A 500 that says nothing. What went wrong goes to the log."
  []
  {:status 500
   :body   {:error   "internal-error"
            :message "Internal Server Error"}})

(defn- create-exception-middleware
  "Reitit exception middleware, placed between response formatting and request
   decoding.

   Position is the whole point, and it was wrong twice over. Reitit applies a
   `:middleware` vector first-to-outermost, and this sat last — inside
   `coerce-request` — so a request that failed coercion threw past every handler
   and the app answered 500 to a malformed request (BOU-321).

   Outermost would fix that and break something else: outside
   `format-response`, an error body can only be a pre-encoded string, and a
   client asking for transit or EDN would get JSON it cannot read. Sitting just
   inside `format-response` catches request decoding and coercion while its map
   bodies are still negotiated like any other response.

   The typed errors the framework raises are handled by the interceptor stack
   closer to the handler; what reaches here is what that stack cannot see."
  [config]
  (let [system (or (:system config) {})
        log-error (fn [e request msg]
                    ;; Not the throwable: reitit puts the whole Ring request in
                    ;; the ex-data of a coercion failure — headers, cookies,
                    ;; the match tree — and logging it writes the Authorization
                    ;; header of the caller into the log on every occurrence.
                    (log/error msg
                               {:uri            (:uri request)
                                :method         (some-> (:request-method request) name)
                                :correlation-id (get-in request [:headers "x-correlation-id"])
                                :exception      (some-> ^Throwable e class .getName)
                                :message        (ex-message e)}))]
    (exception/create-exception-middleware
     (merge
      exception/default-handlers
      {:reitit.coercion/request-coercion
       (fn [e request] (coercion-error-response system e request))

       ;; A response that does not match its own schema is a bug in the app, not
       ;; in the request: generic 500, details in the log only.
       :reitit.coercion/response-coercion
       (fn [e request]
         (log-error e request "Response coercion failed")
         (server-error-response))

       ::exception/default
       (fn [e request]
         (log-error e request "Unhandled exception at the HTTP boundary")
         (server-error-response))}))))

(defn- create-default-middleware
  "Create default middleware stack for Reitit router.

  Returns:
    Vector of middleware for Reitit router"
  [config]
  [;; Query params & form params
   parameters/parameters-middleware
   ;; Content negotiation
   muuntaja/format-negotiate-middleware
   ;; Encoding response body
   muuntaja/format-response-middleware
   ;; Exception handling — after response formatting so its bodies are
   ;; negotiated, before request decoding and coercion so their failures are
   ;; caught rather than thrown past everything.
   (create-exception-middleware config)
   ;; Decoding request body
   muuntaja/format-request-middleware
   ;; Coercing request parameters
   coercion/coerce-request-middleware
   ;; Coercing response bodies
   coercion/coerce-response-middleware])

(defn- create-router-options
  "Create Reitit router options from config.
   
   Args:
     config - Router configuration map with keys:
              :middleware - Additional middleware vector (symbols resolved)
              :coercion - Coercion configuration (defaults to Malli)
              :muuntaja - Muuntaja configuration (defaults to json/edn/transit)
              :conflicts - Conflict resolution (defaults to nil = disabled)
              
   Returns:
     Map of Reitit router options"
  [config]
  (let [default-middleware (create-default-middleware config)
        custom-middleware (resolve-middleware-fns (:middleware config))
        all-middleware (into default-middleware custom-middleware)]
    {:data {:coercion (or (:coercion config) malli-coercion/coercion)
            :muuntaja (or (:muuntaja config) m/instance)
            :middleware all-middleware}
     ;; Disable conflict checking by default - allows routes like /users/new and /users/:id
     ;; to coexist. Reitit will match specific paths before parameterized ones.
     :conflicts (get config :conflicts nil)}))

(defn- create-default-handler
  "Create default handler for routes not matched by router.
  
  Returns:
    Ring handler function"
  []
  (ring/create-default-handler
   {:not-found (constantly {:status 404
                            :headers {"Content-Type" "application/json"}
                            :body (json/generate-string
                                   {:error "Not Found"
                                    :message "The requested resource was not found"})})
    :method-not-allowed (constantly {:status 405
                                     :headers {"Content-Type" "application/json"}
                                     :body (json/generate-string
                                            {:error "Method Not Allowed"
                                             :message "The HTTP method is not allowed for this resource"})})
    :not-acceptable (constantly {:status 406
                                 :headers {"Content-Type" "application/json"}
                                 :body (json/generate-string
                                        {:error "Not Acceptable"
                                         :message "The requested content type is not supported"})})}))

;; =============================================================================
;; Swagger Documentation Routes
;; =============================================================================

(defn- create-swagger-routes
  "Create Swagger documentation routes.

   Returns:
     Vector of Swagger route specs:
     - /swagger.json - OpenAPI/Swagger specification
     - /api-docs/* - Swagger UI interface

   Swagger UI will be available at:
     http://localhost:PORT/api-docs/index.html

   Configuration:
     Swagger metadata (title, version, description) is configured via :swagger-data
     in the router config passed to compile-routes.

   Example swagger-data:
     {:info {:title \"Wagoe API\"
             :description \"Wagoe Framework REST API\"
             :version \"0.1.0\"}
      :tags [{:name \"users\" :description \"User management\"}
             {:name \"inventory\" :description \"Inventory tracking\"}]}"
  [swagger-data]
  [["/swagger.json"
    {:get {:no-doc true
           :swagger swagger-data
           :handler (swagger/create-swagger-handler)}}]

   ["/api-docs/*"
    {:get {:no-doc true
           :handler (swagger-ui/create-swagger-ui-handler
                     {:url "/swagger.json"
                      :config {:validatorUrl nil}})}}]])

;; =============================================================================
;; Gzip Compression Middleware
;; =============================================================================

(defn- accepts-gzip?
  "Returns true when the client advertises gzip support via Accept-Encoding."
  [request]
  (str/includes? (get-in request [:headers "accept-encoding"] "") "gzip"))

(defn- gzip-body
  "Compress a byte-array or String body using gzip.
   Returns a ByteArrayInputStream wrapping the compressed bytes."
  [body]
  (let [out     (ByteArrayOutputStream.)
        bytes   (cond
                  (string? body) (.getBytes ^String body "UTF-8")
                  (bytes? body)  body
                  :else          nil)]
    (when bytes
      (with-open [gzip (GZIPOutputStream. out)]
        (.write gzip ^bytes bytes))
      (ByteArrayInputStream. (.toByteArray out)))))

(defn- wrap-gzip
  "Gzip-compress responses for clients that send Accept-Encoding: gzip.
   Only compresses String or byte-array bodies; other body types pass through."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (and response (accepts-gzip? request))
        (if-let [compressed (gzip-body (:body response))]
          (-> response
              (assoc :body compressed)
              (assoc-in [:headers "Content-Encoding"] "gzip")
              (assoc-in [:headers "Vary"] "Accept-Encoding"))
          response)
        response))))

;; =============================================================================
;; Static Asset Caching Middleware
;; =============================================================================

(defn- wrap-static-resources
  "Serve classpath resources from resources/public/, but only for requests
   that can actually be static assets: GET/HEAD with a file extension in the
   URI. Plain ring.middleware.resource/wrap-resource does a classloader
   lookup on EVERY request (including all API calls) before routing."
  [handler]
  (let [with-resources (wrap-resource handler "public")]
    (fn [request]
      (if (and (contains? #{:get :head} (:request-method request))
               (str/includes? (:uri request) "."))
        (with-resources request)
        (handler request)))))

(defn- wrap-static-cache
  "Adds Cache-Control headers to static asset responses.

   Versioned/hashed assets under /public/ receive a 1-year immutable header.
   All other responses are passed through unchanged."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (and response (str/includes? (or (:uri request) "") "/public/"))
        (assoc-in response [:headers "Cache-Control"] "public, max-age=31536000, immutable")
        response))))

(def ^:private http-methods
  "The endpoint keys Reitit route data can carry."
  #{:get :post :put :delete :patch :head :options :trace})

(defn- decorate-endpoint
  "Add the framework's default HTTP interceptor stack to one Reitit endpoint.

   This is the one thing the normalized format did that Reitit does not do for
   us: every endpoint gets `default-http-interceptors` — security headers, CSRF,
   rate limiting, metrics, error handling — unless it says `:skip-interceptors?`,
   which genuinely-internal endpoints like the health checks do.

   Appended last, so interceptors sit closest to the handler and still see a
   fully prepared request (session, body, coercions) (BOU-331)."
  [{:keys [middleware interceptors skip-interceptors?] :as endpoint} system]
  (let [route-interceptors (or (resolve-interceptors interceptors) [])
        all-interceptors   (if skip-interceptors?
                             route-interceptors
                             (vec (concat http-interceptors/default-http-interceptors
                                          route-interceptors)))]
    (cond-> (dissoc endpoint :interceptors :skip-interceptors?)
      (seq all-interceptors)
      (assoc :middleware (vec (concat (or middleware [])
                                      [(interceptors->middleware all-interceptors system)]))))))

(defn- decorate-reitit-route
  "Walk a Reitit route vector, decorating every endpoint it contains.

   `[path data & children]`, where `data` may carry endpoints directly and each
   child is another route vector. Route-level `:middleware`, `:name`, coercion
   and nesting are Reitit's own and pass through untouched."
  [route system]
  (if-not (vector? route)
    route
    (let [[path data & children] route
          data'    (if (map? data)
                     (reduce-kv (fn [m k v]
                                  (assoc m k (if (and (http-methods k) (map? v))
                                               (decorate-endpoint v system)
                                               v)))
                                {} data)
                     data)
          children' (mapv #(decorate-reitit-route % system) (if (map? data) children
                                                               (cons data children)))]
      (into (if (map? data) [path data'] [path]) children'))))

(defn- reitit-route?
  "Whether `route` is Reitit data rather than a normalized map.

   Both formats travel through `compile-routes` while the migration runs: a
   module that has moved emits `[\"/path\" {...}]`, one that has not emits
   `{:path \"/path\" :methods {...}}` (BOU-331)."
  [route]
  (vector? route))

(defn- prepare-routes
  "Every route as Reitit data, whichever format it arrived in."
  [route-specs system]
  (mapv (fn [route]
          (if (reitit-route? route)
            (decorate-reitit-route route system)
            (convert-route route system)))
        route-specs))

;; =============================================================================
;; IRouter Implementation
;; =============================================================================

(defrecord ReititRouter []
  ports/IRouter
  (compile-routes [_this route-specs config]
    (let [;; Extract observability services from config (if provided)
          system (:system config)

          ;; Extract Swagger configuration (optional)
          swagger-enabled? (get config :swagger-enabled true)  ; Enabled by default
          swagger-data (or (:swagger-data config)
                           {:info {:title "Wagoe API"
                                   :description "Wagoe Framework REST API"
                                   :version "0.1.0"}})

          ;; Reitit data passes through; normalized maps are still converted
          ;; while modules migrate (BOU-331/ADR-037).
          reitit-routes (prepare-routes route-specs system)

          ;; Add Swagger routes if enabled
          all-routes (if swagger-enabled?
                       (into (create-swagger-routes swagger-data) reitit-routes)
                       reitit-routes)

          ;; Create router options
          router-opts (create-router-options config)

          ;; Create Reitit router
          router (ring/router all-routes router-opts)

          ;; Create default handler for unmatched routes
          default-handler (create-default-handler)

          ;; Wrap handler with middlewares (outermost last):
          ;; 1. Cookies middleware - parse and set cookies
          ;; 2. Static resource middleware - asset-shaped GET/HEAD requests only
          ;; Query/form params are parsed by reitit's parameters-middleware
          ;; (create-default-middleware) — a global wrap-params here would
          ;; parse them a second time for every request.
          handler (-> (ring/ring-handler router default-handler)
                      (wrap-gzip)
                      (wrap-static-cache)
                      (wrap-static-resources)
                      (wrap-cookies))]

      ;; Store the Reitit router in metadata so devtools can extract route info
      ;; from the wrapped handler without needing to unwrap middleware layers.
      (with-meta handler {:reitit/router router}))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn create-reitit-router
  "Create new Reitit router adapter instance.
  
  Returns:
    ReititRouter instance implementing IRouter"
  []
  (->ReititRouter))

(comment
  ;; Example usage:

  ;; Define normalized routes
  (def example-routes
    [{:path "/api/users"
      :methods {:get {:handler 'my.app.handlers/list-users
                      :summary "List users"
                      :tags ["users"]
                      :coercion {:query [:map
                                         [:limit {:optional true} :int]
                                         [:offset {:optional true} :int]]
                                 :response {200 [:vector [:map [:id :uuid] [:name :string]]]
                                            400 [:map [:error :string]]}}}
                :post {:handler 'my.app.handlers/create-user
                       :summary "Create user"
                       :tags ["users"]
                       :coercion {:body [:map
                                         [:name :string]
                                         [:email :string]]
                                  :response {201 [:map [:id :uuid]]
                                             400 [:map [:error :string]]}}}}
      :children [{:path "/:id"
                  :methods {:get {:handler 'my.app.handlers/get-user
                                  :coercion {:path [:map [:id :uuid]]
                                             :response {200 [:map [:id :uuid] [:name :string]]
                                                        404 [:map [:error :string]]}}}
                            :delete {:handler 'my.app.handlers/delete-user
                                     :coercion {:path [:map [:id :uuid]]
                                                :response {204 nil
                                                           404 [:map [:error :string]]}}}}}]}])

  ;; Create router and compile routes
  (let [router (create-reitit-router)
        config {:middleware ['my.app.middleware/wrap-auth]}
        handler (ports/compile-routes router example-routes config)]

    ;; Use handler as Ring handler
    (handler {:request-method :get
              :uri "/api/users"
              :query-params {"limit" "10" "offset" "0"}})))
