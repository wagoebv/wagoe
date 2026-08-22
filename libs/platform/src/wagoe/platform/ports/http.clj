(ns wagoe.platform.ports.http
  "The HTTP server port.

  Routing is not a port: modules emit Reitit route data and the platform
  compiles it (ADR-037). The server is — a Ring handler is handed to whatever
  implements this protocol.")

(defprotocol IHttpServer
  "Protocol for HTTP server implementations.
  
  Server adapters manage the lifecycle of HTTP servers, accepting Ring handlers
  and starting/stopping server instances.

  One implementation today: Ring's Jetty adapter. The protocol earns its keep
  by keeping the server choice out of the wiring, not by predicting a second
  one — IRouter made that prediction and was removed unimplemented."

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

#_:clj-kondo/ignore
(comment
  ;; Start a server with a compiled handler, and stop it again.
  (let [adapter  (ring-jetty-adapter/->RingJettyServer)
        handler  (fn [_req] {:status 200 :body "OK"})
        instance (start! adapter handler {:port 3000 :host "0.0.0.0" :join? false})]
    (stop! adapter instance)))
