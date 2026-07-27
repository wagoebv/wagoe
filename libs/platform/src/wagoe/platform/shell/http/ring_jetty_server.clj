(ns wagoe.platform.shell.http.ring-jetty-server
  "Ring+Jetty server adapter - manages HTTP server lifecycle using Ring and Jetty.
  
  This adapter implements the IHttpServer protocol to start and stop Jetty-based
  HTTP servers with Ring handlers."
  (:require [wagoe.platform.ports.http :as ports]
            [ring.adapter.jetty :as jetty]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Server Configuration
;; =============================================================================

(defn- build-jetty-options
  "Build Jetty server options from configuration.
  
  Args:
    config - Server configuration map with keys:
             :port - Port number (default 3000)
             :host - Host address (default \"0.0.0.0\")
             :join? - Whether to block thread (default false)
             :max-threads - Maximum thread pool size
             :min-threads - Minimum thread pool size
             :max-idle-time - Maximum idle time in ms
             :ssl-port - HTTPS port (optional)
             :keystore - SSL keystore path (optional)
             :key-password - SSL key password (optional)
             :truststore - SSL truststore path (optional)
             :trust-password - SSL trust password (optional)
             
  Returns:
    Map of Jetty adapter options"
  [config]
  (let [{:keys [port host join? max-threads min-threads max-idle-time
                ssl-port keystore key-password truststore trust-password]
         :or {port 3000
              host "0.0.0.0"
              join? false}} config]
    (cond-> {:port port
             :host host
             :join? join?}

      max-threads
      (assoc :max-threads max-threads)

      min-threads
      (assoc :min-threads min-threads)

      max-idle-time
      (assoc :max-idle-time max-idle-time)

      ssl-port
      (assoc :ssl-port ssl-port)

      keystore
      (assoc :keystore keystore)

      key-password
      (assoc :key-password key-password)

      truststore
      (assoc :truststore truststore)

      trust-password
      (assoc :trust-password trust-password))))

;; =============================================================================
;; IHttpServer Implementation
;; =============================================================================

(defrecord RingJettyServer []
  ports/IHttpServer

  (start! [_this handler config]
    (let [jetty-opts (build-jetty-options config)
          {:keys [port host ssl-port]} jetty-opts]

      ;; Log server startup
      (log/info "Starting Ring+Jetty HTTP server"
                {:port port
                 :host host
                 :ssl-port ssl-port
                 :join? (:join? jetty-opts)})

      (try
        ;; Start Jetty server
        (let [server (jetty/run-jetty handler jetty-opts)]
          (log/info "Ring+Jetty HTTP server started successfully"
                    {:port port :host host})
          server)

        (catch Exception e
          (log/error e "Failed to start Ring+Jetty HTTP server"
                     {:port port :host host})
          (throw (ex-info "Failed to start HTTP server"
                          {:type :server-start-failed
                           :config config}
                          e))))))

  (stop! [_this server]
    (when server
      (log/info "Stopping Ring+Jetty HTTP server")
      (try
        (.stop server)
        (log/info "Ring+Jetty HTTP server stopped successfully")
        (catch Exception e
          (log/error e "Error stopping Ring+Jetty HTTP server")
          (throw (ex-info "Failed to stop HTTP server"
                          {:type :server-stop-failed}
                          e)))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn create-ring-jetty-server
  "Create new Ring+Jetty server adapter instance.
  
  Returns:
    RingJettyServer instance implementing IHttpServer"
  []
  (->RingJettyServer))

(comment
  ;; Example usage:

  ;; Create server
  (def server-adapter (create-ring-jetty-server))

  ;; Create simple handler
  (def handler (fn [_request]
                 {:status 200
                  :headers {"Content-Type" "text/plain"}
                  :body "Hello World"}))

  ;; Start server
  (def server-instance
    (ports/start! server-adapter
                  handler
                  {:port 3000
                   :host "localhost"
                   :join? false
                   :max-threads 50
                   :min-threads 8}))

  ;; Server is now running on http://localhost:3000
  ;; Test: curl http://localhost:3000

  ;; Stop server
  (ports/stop! server-adapter server-instance)

  ;; Example with SSL
  (def ssl-server-instance
    (ports/start! server-adapter
                  handler
                  {:port 3000
                   :host "0.0.0.0"
                   :ssl-port 3443
                   :keystore "path/to/keystore.jks"
                   :key-password "changeit"
                   :join? false}))

  ;; Stop SSL server
  (ports/stop! server-adapter ssl-server-instance))
