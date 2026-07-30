(ns wagoe.platform.shell.system.port-integration-test
  "Integration tests for HTTP server port allocation and system startup"
  (:require [wagoe.config :as config]
            [wagoe.platform.shell.system.wiring]
            [wagoe.user.shell.module-wiring]
            ;; The full-system boot here includes tenant/membership keys; load their
            ;; Integrant methods (platform no longer requires tenant — BOU-198).
            [wagoe.tenant.shell.module-wiring]
            [wagoe.platform.shell.utils.port-manager :as port-manager]
            [clojure.test :refer [deftest testing is]]
            [integrant.core :as ig]
            [clojure.tools.logging :as log])
  (:import [java.net ServerSocket BindException]))

;; =============================================================================
;; Test Fixtures and Helpers
;; =============================================================================

(defn- occupy-port
  "Start a server socket on a port to simulate port conflict"
  [port]
  (try
    (ServerSocket. port)
    (catch Exception _
      nil)))

(defn- release-port
  "Close a server socket to free up a port"
  [socket]
  (when socket
    (try
      (.close socket)
      (catch Exception _))))

(defn- find-free-port
  "Find a currently free ephemeral port."
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- socket-bind-supported?
  "Return true when this environment allows binding a local server socket."
  []
  (try
    (with-open [_socket (ServerSocket. 0)]
      true)
    (catch java.net.SocketException _
      false)
    (catch Exception _
      false)))

(defn- find-free-port-range
  "Find a contiguous free port range of the requested size."
  [size]
  (loop [candidate (max 20000 (find-free-port))]
    (let [end (+ candidate (dec size))]
      (if (every? port-manager/port-available? (range candidate (inc end)))
        {:start candidate :end end}
        (recur (inc candidate))))))

(defn- create-test-config
  "Create test configuration with specific port settings"
  [& {:keys [port host port-range]}]
  {:active
   {:wagoe/settings
    {:name "boundary-test"
     :version "0.1.0-test"}

    :wagoe/http
    (merge {:port (or port 59990)
            :host (or host "127.0.0.1")
            :join? false}
           (when port-range {:port-range port-range}))

    :wagoe/sqlite
    {:db ":memory:"
     :pool {:minimum-idle 1
            :maximum-pool-size 3}}

    :wagoe/logging
    {:provider :no-op}

    :wagoe/metrics
    {:provider :no-op}

    :wagoe/error-reporting
    {:provider :no-op}}})

(defn- start-system-with-port
  "Start a minimal system with HTTP server on specified port"
  [port & {:keys [port-range]}]
  (let [test-config (create-test-config :port port
                                        :port-range port-range)
        ig-config (config/ig-config test-config)]
    (try
      (ig/init ig-config)
      (catch Exception e
        {:error e}))))

(defn- stop-system
  "Stop a system safely"
  [system]
  (when (and system (not (:error system)))
    (try
      (ig/halt! system)
      (catch Exception e
        (log/warn "Error stopping test system" {:error (str e)})))))

;; =============================================================================
;; System Startup Tests
;; =============================================================================

(deftest ^:integration test-http-server-startup-with-available-port
  (testing "HTTP server starts successfully when port is available"
    (if (socket-bind-supported?)
      (let [available-port (find-free-port)
            system (start-system-with-port available-port)]
        (try
          (is (not (:error system)) "System should start without errors")
          (when (not (:error system))
            (is (contains? system :wagoe/http-server))
            (let [server (:wagoe/http-server system)]
              (is (some? server))
              ;; Verify server is actually running by checking if it's started
              (is (.isStarted server))))
          (finally
            (stop-system system))))
      (is (not (socket-bind-supported?)) "Skipping socket-bind dependent HTTP startup test in sandbox"))))

(deftest ^:integration test-http-server-port-conflict-resolution
  (testing "HTTP server resolves port conflicts using port-range fallback"
    (if (socket-bind-supported?)
      (with-redefs [port-manager/development-environment? (constantly true)]
        (let [{:keys [start] :as port-range} (find-free-port-range 8)
              blocked-port start
              blocking-socket (occupy-port blocked-port)]
          (try
            (let [system (start-system-with-port blocked-port
                                                 :port-range port-range)]
              (try
                (is (not (:error system)))
                (is (contains? system :wagoe/http-server))
                (let [server (:wagoe/http-server system)]
                  (is (some? server))
                  (is (.isStarted server))
                  ;; Server should be running on a different port than requested
                  (let [actual-port (.getPort (first (.getConnectors server)))]
                    (is (not= blocked-port actual-port))
                    (is (>= actual-port (:start port-range)))
                    (is (<= actual-port (:end port-range)))))
                (finally
                  (stop-system system))))
            (finally
              (release-port blocking-socket)))))
      (is (not (socket-bind-supported?)) "Skipping socket-bind dependent conflict-resolution test in sandbox"))))

(deftest ^:unit test-http-server-config-integration
  (testing "HTTP server receives complete configuration including port-range"
    (let [test-config (create-test-config :port 59975
                                          :port-range {:start 59970 :end 59979})
          ig-config (config/ig-config test-config)
          http-server-config (:wagoe/http-server ig-config)]

      ;; Verify the HTTP server config includes all expected keys
      (is (= 59975 (:port http-server-config)))
      (is (= "127.0.0.1" (:host http-server-config)))
      (is (false? (:join? http-server-config)))
      (is (= {:start 59970 :end 59979} (get-in http-server-config [:config :port-range])))
      (is (contains? http-server-config :handler)))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest ^:integration test-system-startup-failure-handling
  (testing "System handles startup failures gracefully"
    (if (socket-bind-supported?)
      (let [{:keys [start end] :as port-range} (find-free-port-range 3)
            sockets (mapv occupy-port (range start (inc end)))]
        (try
          ;; With all ports blocked and no random fallback in this test scenario,
          ;; the system should either fail gracefully or find an alternative port
          (let [system (start-system-with-port start :port-range port-range)]
            (if (:error system)
              ;; If system failed to start, verify it's due to port unavailability
              (is (or (instance? BindException (:error system))
                      (instance? RuntimeException (:error system))))
              ;; If system started, it should be on a different port (random fallback)
              (do
                (is (some? (:wagoe/http-server system)))
                (stop-system system))))
          (finally
            (doseq [socket sockets]
              (release-port socket)))))
      (is (not (socket-bind-supported?)) "Skipping socket-bind dependent startup-failure test in sandbox"))))

;; =============================================================================
;; Docker Environment Simulation Tests
;; =============================================================================

(deftest ^:integration test-docker-environment-behavior
  (testing "System behavior in Docker environment"
    (if (socket-bind-supported?)
      (with-redefs [port-manager/docker-environment? (constantly true)]
        (let [requested-port (find-free-port)
              system (start-system-with-port requested-port)]
          (try
            (is (not (:error system)))
            ;; In Docker mode, should use the requested port directly
            (let [server (:wagoe/http-server system)
                  actual-port (.getPort (first (.getConnectors server)))]
              (is (= requested-port actual-port)))
            (finally
              (stop-system system)))))
      (is (not (socket-bind-supported?)) "Skipping socket-bind dependent Docker behavior test in sandbox"))))

;; =============================================================================
;; Configuration Validation Tests  
;; =============================================================================

(deftest ^:unit test-config-loading-and-validation
  (testing "Configuration loading includes port management settings"
    (let [test-config (create-test-config :port 59950
                                          :port-range {:start 59950 :end 59959})
          http-config (config/http-config test-config)]

      (is (= 59950 (:port http-config)))
      (is (= "127.0.0.1" (:host http-config)))
      (is (false? (:join? http-config)))
      (is (= {:start 59950 :end 59959} (:port-range http-config))))))

(deftest ^:unit test-config-without-port-range
  (testing "Configuration works without port-range specified"
    (let [test-config (create-test-config :port 59945)
          http-config (config/http-config test-config)]

      (is (= 59945 (:port http-config)))
      (is (nil? (:port-range http-config))))))

;; =============================================================================
;; Performance and Resource Tests
;; =============================================================================

(deftest ^:integration test-multiple-system-startups
  (testing "Multiple system startups with port allocation"
    (if (socket-bind-supported?)
      (with-redefs [port-manager/development-environment? (constantly true)]
        ;; Start multiple systems to verify port allocation works correctly
        (let [systems (atom [])]
          (try
            (doseq [i (range 3)]
              (let [{:keys [start end] :as port-range} (find-free-port-range 5)
                    base-port (+ start (min i (- end start)))
                    system (start-system-with-port base-port :port-range port-range)]
                (when (not (:error system))
                  (swap! systems conj system))))

            ;; Verify at least some systems started successfully
            (is (pos? (count @systems)))

            ;; Verify systems are using different ports
            (let [ports (map #(.getPort (first (.getConnectors (:wagoe/http-server %)))) @systems)]
              (is (= (count ports) (count (distinct ports)))))

            (finally
              (doseq [system @systems]
                (stop-system system))))))
      (is (not (socket-bind-supported?)) "Skipping socket-bind dependent multiple-startup test in sandbox"))))
