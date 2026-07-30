(ns wagoe.platform.shell.utils.port-manager
  "Port management utilities for development environment.
   
   Provides port conflict resolution, environment detection,
   and intelligent port allocation for different development scenarios."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn port-available?
  "Check if a port is available for binding.
   
   Args:
     port: Port number to check
   
   Returns:
     true if port is available, false otherwise"
  [port]
  (try
    (with-open [_socket (java.net.ServerSocket. port)]
      true)
    (catch java.io.IOException _
      false)))

(defn find-available-port
  "Find an available port within a specified range.
   
   Args:
     start-port: Starting port number (inclusive)
     end-port: Ending port number (inclusive), defaults to start-port + 99
   
   Returns:
     An available port number within the range
   
   Throws:
     ExceptionInfo if no port is available in the range"
  ([start-port]
   (find-available-port start-port (+ start-port 99)))
  ([start-port end-port]
   (loop [port start-port]
     (cond
       (> port end-port)
       (throw (ex-info "No available port found in range"
                       {:start-port start-port
                        :end-port end-port
                        :attempted-range (- end-port start-port)}))

       (port-available? port)
       port

       :else
       (recur (inc port))))))

(defn docker-environment?
  "Detect if the application is running in a Docker container.
   
   Returns:
     true if running in Docker, false otherwise"
  []
  (or
    ;; Check for Docker-specific files
   (.exists (io/file "/.dockerenv"))
    ;; Check for container init process
   (when-let [init-process (try
                             (slurp "/proc/1/comm")
                             (catch Exception _ nil))]
     (some #(str/includes? init-process %)
           ["docker" "container" "init"]))
    ;; Check environment variables commonly set in containers
   (some #(System/getenv %)
         ["CONTAINER" "DOCKER_CONTAINER" "KUBERNETES_SERVICE_HOST"])))

(defn development-environment?
  "Detect if the application is running in development mode.
   
   Returns:
     true if in development environment, false otherwise"
  []
  (let [env (or (System/getenv "ENVIRONMENT")
                (System/getenv "ENV")
                (System/getProperty "env")
                "development")]
    (contains? #{"development" "dev" "local"} (str/lower-case env))))

(defn suggest-port-strategy
  "Suggest port allocation strategy based on environment.
   
   Args:
     requested-port: The originally requested port
     config: Configuration map with potential port-range
   
   Returns:
     Map with :strategy, :port-range, and :message"
  [requested-port config]
  (let [docker? (docker-environment?)
        dev? (development-environment?)
        port-range (or (:port-range config) {:start requested-port :end (+ requested-port 99)})]

    (cond
      ;; Docker environment - use exact port or fail fast
      docker?
      {:strategy :exact-or-fail
       :port-range {:start requested-port :end requested-port}
       :message "Docker environment detected - using exact port or failing"}

      ;; Development environment - try port range
      dev?
      {:strategy :range-search
       :port-range port-range
       :message (format "Development environment - searching ports %d-%d"
                        (:start port-range) (:end port-range))}

      ;; Production-like environment - be conservative
      :else
      {:strategy :exact-or-fail
       :port-range {:start requested-port :end requested-port}
       :message "Production-like environment - using exact port only"})))

(defn allocate-port
  "Allocate a port using intelligent strategy based on environment.
   
   Args:
     requested-port: The preferred port number
     config: Configuration map (may contain :port-range)
   
   Returns:
     Map with :port (allocated port) and :message (allocation info)"
  [requested-port config]
  (let [strategy-info (suggest-port-strategy requested-port config)
        {:keys [strategy port-range message]} strategy-info]

    (log/debug "Port allocation strategy" {:strategy strategy :range port-range})

    (case strategy
      :exact-or-fail
      (if (port-available? requested-port)
        {:port requested-port
         :message (str message " - using requested port")}
        (throw (ex-info "Requested port not available in strict environment"
                        {:requested-port requested-port
                         :environment-type (if (docker-environment?) "docker" "production")
                         :suggestion "Use different port or stop conflicting process"})))

      :range-search
      (let [allocated-port (find-available-port (:start port-range) (:end port-range))]
        {:port allocated-port
         :message (str message (when (not= allocated-port requested-port)
                                 (format " - resolved conflict, using port %d" allocated-port)))}))))

(defn log-port-allocation
  "Log port allocation information for debugging.
   
   Args:
     requested-port: Originally requested port
     allocated-port: Actually allocated port  
     config: Configuration map
     service-name: Name of the service (e.g., 'HTTP Server')"
  [requested-port allocated-port config service-name]
  (let [docker? (docker-environment?)
        dev? (development-environment?)]

    (if (= requested-port allocated-port)
      (log/info (format "%s started on requested port" service-name)
                {:port allocated-port
                 :environment {:docker docker? :development dev?}})
      (log/warn (format "%s port conflict resolved" service-name)
                {:requested-port requested-port
                 :allocated-port allocated-port
                 :environment {:docker docker? :development dev?}
                 :config-range (get-in config [:port-range])}))))