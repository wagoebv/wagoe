(ns wagoe.platform.ports.http
  "HTTP routing and server protocols for framework-agnostic route handling.
  
  These protocols define the abstraction for HTTP routing and server operations,
  allowing the framework to support multiple router implementations (Reitit,
  Pedestal, etc.) and server implementations (Ring+Jetty, Undertow, etc.)
  through the Ports & Adapters pattern.
  
  Modules provide normalized route specifications (pure EDN data) which are
  translated by router adapters into framework-specific route definitions.")

(defprotocol IRouter
  "Protocol for HTTP routing implementations.
  
  Router adapters translate normalized route specifications into
  framework-specific routing structures and produce Ring-compatible handlers.
  
  Example adapters:
  - ReititRouter: Converts normalized routes to Reitit format
  - PedestalRouter: Converts normalized routes to Pedestal routing table
  - MockRouter: In-memory router for testing"

  (compile-routes [this route-specs config]
    "Compile normalized route specifications into a Ring handler.
    
    Takes a vector of normalized route maps and produces a Ring handler
    function that can process HTTP requests. The router adapter is responsible
    for:
    - Resolving handler and middleware symbols to functions
    - Applying coercion based on Malli schemas
    - Setting up route matching and parameter extraction
    - Organizing routes according to framework conventions
    
    Args:
      route-specs - Vector of normalized route maps with structure:
                    {:path \"/api/users\"
                     :methods {:get {:handler 'ns/fn :coercion {...}}
                               :post {...}}
                     :children [...]}
      config - Router configuration map with keys:
               :middleware - Vector of middleware symbols (applied globally)
               :coercion - Coercion configuration
               :default-handlers - Map of default handlers (404, etc.)
      
    Returns:
      Ring handler function (request-map → response-map)
      
    Example:
      (compile-routes router
                      [{:path \"/api/users\"
                        :methods {:get {:handler 'user/list-users}}}]
                      {:middleware ['wrap-json 'wrap-cors]})"))

(defprotocol IHttpServer
  "Protocol for HTTP server implementations.
  
  Server adapters manage the lifecycle of HTTP servers, accepting Ring handlers
  and starting/stopping server instances.
  
  Example adapters:
  - RingJettyServer: Uses Ring's Jetty adapter
  - UndertowServer: Uses Undertow web server
  - NettyServer: Uses Netty for async HTTP
  - MockServer: In-memory server for testing"

  (start! [this handler config]
    "Start HTTP server with the given Ring handler.
    
    Launches an HTTP server that processes requests using the provided handler.
    The server adapter is responsible for:
    - Binding to host/port
    - Managing connection pooling and threading
    - Handling HTTP protocol details
    - Applying server-level configuration (timeouts, compression, etc.)
    
    Args:
      handler - Ring handler function (request-map → response-map)
      config - Server configuration map with keys:
               :port - Port number (default 3000)
               :host - Host address (default \"0.0.0.0\")
               :join? - Whether to block (default false)
               :max-threads - Thread pool size
               :ssl-port - HTTPS port (optional)
               :keystore - SSL keystore path (optional)
               Additional keys specific to server implementation
      
    Returns:
      Server instance (opaque value, passed to stop!)
      
    Example:
      (start! server handler {:port 3000 :host \"localhost\" :join? false})")

  (stop! [this server]
    "Stop the HTTP server.
    
    Gracefully shuts down the server instance, closing all connections and
    releasing resources.
    
    Args:
      server - Server instance returned from start!
      
    Returns:
      nil
      
    Example:
      (stop! server server-instance)"))

(defprotocol IRouteValidator
  "Protocol for route validation.
  
  Validates normalized route specifications to ensure they conform to the
  expected schema and contain all required information."

  (validate-routes [this route-specs]
    "Validate normalized route specifications.
    
    Checks that route specs conform to the normalized schema and contain
    all required fields. Returns validation result.
    
    Args:
      route-specs - Vector of normalized route maps
      
    Returns:
      Map with keys:
        :valid? - Boolean indicating if routes are valid
        :errors - Vector of error maps (empty if valid)
        :warnings - Vector of warning maps
      
    Example:
      (validate-routes validator [{:path \"/api/users\" ...}])
      ;; => {:valid? true :errors [] :warnings []}"))

#_:clj-kondo/ignore
(comment
  ;; Example usage:

  ;; Define routes in module
  (def user-routes
    [{:path "/api/users"
      :methods {:get {:handler 'wagoe.user.shell.handlers/list-users
                      :coercion {:query wagoe.user.schema/ListUsersQuery
                                 :response {:200 wagoe.user.schema/UserList}}}
                :post {:handler 'wagoe.user.shell.handlers/create-user
                       :coercion {:body wagoe.user.schema/CreateUserRequest}}}}])

  ;; Compile routes with selected router
  (let [router (reitit-adapter/->ReititRouter)
        route-config {:middleware ['wrap-json 'wrap-cors]}
        my-handler (compile-routes router user-routes route-config)]
    ;; my-handler is now a Ring function
    (my-handler {:request-method :get :uri "/api/users"}))

  ;; Start server with compiled handler
  (let [server-adapter (ring-jetty-adapter/->RingJettyServer)
        my-handler (fn [_req] {:status 200 :body "OK"})
        server-config {:port 3000 :host "0.0.0.0" :join? false}
        server-instance (start! server-adapter my-handler server-config)]
    ;; Server is now running
    ;; ... do work ...
    ;; Stop server
    (stop! server-adapter server-instance)))
