(ns wagoe.platform.shell.utils.port-manager-test
  "Tests for port allocation and conflict resolution"
  (:require [wagoe.platform.shell.utils.port-manager :as pm]
            [clojure.test :refer [deftest testing is]])
  (:import [java.net ServerSocket]))

;; =============================================================================
;; Test Helpers
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

(defn- with-occupied-port
  "Execute test function with a port temporarily occupied"
  [port test-fn]
  (let [socket (occupy-port port)]
    (try
      (test-fn)
      (finally
        (release-port socket)))))

(defn- reserve-free-port
  "Reserve an ephemeral port and return its number."
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

;; =============================================================================
;; Environment Detection Tests
;; =============================================================================

(deftest ^:unit test-docker-environment-detection
  (testing "Docker environment detection"
    (with-redefs [pm/docker-environment? (constantly true)]
      (is (pm/docker-environment?)))

    (with-redefs [pm/docker-environment? (constantly false)]
      (is (not (pm/docker-environment?))))))

;; =============================================================================
;; Port Availability Tests
;; =============================================================================

(deftest ^:integration test-port-available?
  (testing "Port availability checking"
    (if (socket-bind-supported?)
      (do
        (testing "Available port returns true"
          (let [port (reserve-free-port)]
            (is (pm/port-available? port))))

        (testing "Occupied port returns false"
          (let [port (reserve-free-port)]
            (with-occupied-port port
              #(is (not (pm/port-available? port)))))))
      (is (not (socket-bind-supported?)) "Skipping socket-bind dependent port-availability assertions in sandbox"))))

(deftest ^:unit test-find-available-port
  (testing "Find available port in range"
    (testing "Returns available port in range"
      (with-redefs [pm/port-available? (fn [port] (>= port 42003))]
        (let [port (pm/find-available-port 42000 42005)]
          (is (number? port))
          (is (= 42003 port)))))

    (testing "Throws exception when no ports available in range"
      ;; Mock all ports as occupied
      (with-redefs [pm/port-available? (constantly false)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"No available port found in range"
             (pm/find-available-port 60000 60005)))))))

;; =============================================================================
;; Port Allocation Tests - Docker Environment
;; =============================================================================

(deftest ^:unit test-allocate-port-docker-environment
  (testing "Port allocation in Docker environment"
    (with-redefs [pm/docker-environment? (constantly true)]

      (testing "Uses exact port in Docker (exact-or-fail strategy)"
        (with-redefs [pm/port-available? (constantly true)]
          (let [result (pm/allocate-port 3000 {})]
            (is (= 3000 (:port result)))
            (is (string? (:message result)))
            (is (re-find #"Docker environment" (:message result))))))

      (testing "Fails when port not available in Docker"
        (with-redefs [pm/port-available? (constantly false)]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Requested port not available in strict environment"
               (pm/allocate-port 8080 {}))))))))

;; =============================================================================
;; Port Allocation Tests - Development Environment
;; =============================================================================

(deftest ^:unit test-allocate-port-development-environment
  (testing "Port allocation in development environment"
    (with-redefs [pm/docker-environment? (constantly false)
                  pm/development-environment? (constantly true)]

      (testing "Uses requested port when available"
        (with-redefs [pm/port-available? (constantly true)]
          (let [result (pm/allocate-port 3000 {})]
            (is (= 3000 (:port result)))
            (is (string? (:message result)))
            (is (re-find #"Development environment" (:message result))))))

      (testing "Uses the requested port even when a :port-range is configured"
        ;; Regression (BOU-251). The existing "uses requested port" case above
        ;; passes {} as config, so suggest-port-strategy defaults the range to
        ;; {:start requested-port ...} and a scan from the range start happens to
        ;; return the requested port anyway. The bug only appears with a REAL
        ;; :port-range, which every shipped config has: allocation jumped
        ;; straight to (:start port-range), so HTTP_PORT=3195 silently bound
        ;; 3100 and anything probing 3195 found nothing listening.
        (with-redefs [pm/port-available? (constantly true)
                      pm/find-available-port (constantly 3100)]
          (let [config {:port-range {:start 3100 :end 3199}}
                result (pm/allocate-port 3195 config)]
            (is (= 3195 (:port result))
                "an available requested port must win over the range start")
            (is (not (re-find #"resolved conflict" (:message result)))
                "reporting a conflict that did not happen is its own bug"))))

      (testing "A string port from #env is coerced, not passed through"
        ;; aero's #env yields a String. While the requested port was unused this
        ;; was invisible; the moment it reached port-available? it surfaced as a
        ;; bare ClassCastException. Configs now carry #long, and this pins the
        ;; defensive coercion so a missing tag cannot crash startup again.
        (with-redefs [pm/port-available? (constantly true)]
          (let [result (pm/allocate-port "3195" {:port-range {:start 3100 :end 3199}})]
            (is (= 3195 (:port result))))))

      (testing "Falls back to range when requested port occupied"
        (with-redefs [pm/port-available? (fn [port]
                                           (if (= port 3000)
                                             false ; Default port occupied
                                             true)) ; Range ports available
                      pm/find-available-port (constantly 3001)]
          (let [config {:port-range {:start 3000 :end 3099}}
                result (pm/allocate-port 3000 config)]
            (is (= 3001 (:port result)))
            (is (string? (:message result)))
            (is (re-find #"resolved conflict" (:message result))))))

      (testing "Throws exception when no port available in range"
        (with-redefs [pm/port-available? (constantly false)
                      pm/find-available-port (fn [start end]
                                               (throw (ex-info "No available port found in range"
                                                               {:start-port start
                                                                :end-port end})))]
          (let [config {:port-range {:start 3000 :end 3099}}]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"No available port found in range"
                 (pm/allocate-port 3000 config)))))))))

;; =============================================================================
;; Configuration Tests
;; =============================================================================

(deftest ^:unit test-port-allocation-with-different-configs
  (testing "Port allocation with different configuration scenarios"
    (with-redefs [pm/docker-environment? (constantly false)
                  pm/development-environment? (constantly true)
                  pm/port-available? (constantly true)]

      (testing "Works without port-range config"
        (let [result (pm/allocate-port 3000 {})]
          (is (= 3000 (:port result)))
          (is (string? (:message result)))))

      (testing "Works with nil config"
        (let [result (pm/allocate-port 3000 nil)]
          (is (= 3000 (:port result)))
          (is (string? (:message result)))))

      (testing "Uses custom port range when default port occupied"
        (with-redefs [pm/port-available? (fn [port]
                                           (if (= port 8080)
                                             false ; Default port occupied
                                             true)) ; Range ports available
                      pm/find-available-port (fn [start end]
                                               (when (and (= start 8000) (= end 8099))
                                                 8001))]
          (let [config {:port-range {:start 8000 :end 8099}}
                result (pm/allocate-port 8080 config)]
            (is (= 8001 (:port result)))
            (is (string? (:message result)))
            (is (re-find #"resolved conflict" (:message result)))))))))

;; =============================================================================
;; Random Port Tests
;; =============================================================================

(deftest ^:unit test-find-available-port-range
  (testing "Random port allocation in ephemeral range"
    (testing "Returns available port in valid range"
      (with-redefs [pm/port-available? (constantly true)]
        (let [port (pm/find-available-port 49152 65535)]
          (is (number? port))
          (is (>= port 49152)) ; Ephemeral port range start
          (is (<= port 65535))))) ; Max port number

    (testing "Finds first available port in range"
      (let [attempt-count (atom 0)]
        (with-redefs [pm/port-available? (fn [port]
                                           (swap! attempt-count inc)
                                           (>= port 50003))] ; First 3 ports busy, 4th available
          (let [port (pm/find-available-port 50000 50010)]
            (is (= 50003 port))
            (is (= 4 @attempt-count))))))

    (testing "Throws exception when no port available in range"
      (with-redefs [pm/port-available? (constantly false)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"No available port found in range"
             (pm/find-available-port 50000 50005)))))))

;; =============================================================================
;; Logging Tests
;; =============================================================================

(deftest ^:unit test-log-port-allocation
  (testing "Port allocation logging"
    (testing "Function runs without error for port conflict resolution"
      ;; Just verify the function runs without throwing exceptions
      (is (nil? (pm/log-port-allocation 3000 3001 {} "Test Service"))))

    (testing "Function runs without error for successful same-port allocation"
      ;; Just verify the function runs without throwing exceptions
      (is (nil? (pm/log-port-allocation 4000 4000 {} "Another Service"))))

    (testing "Function handles various configurations"
      ;; Test with different config formats
      (is (nil? (pm/log-port-allocation 5000 5001 {:port-range {:start 5000 :end 5099}} "Service 1")))
      (is (nil? (pm/log-port-allocation 6000 6000 nil "Service 2")))
      (is (nil? (pm/log-port-allocation 7000 7001 {} "Service 3"))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest ^:unit test-full-port-allocation-workflow
  (testing "Complete port allocation workflow"
    (with-redefs [pm/docker-environment? (constantly false)
                  pm/development-environment? (constantly true)]

      (testing "Successful allocation with fallback"
        (with-redefs [pm/port-available? (fn [port] (not= port 43000))]
          (let [config {:port-range {:start 43000 :end 43004}}
                result (pm/allocate-port 43000 config)]
            (is (number? (:port result)))
            (is (= 43001 (:port result)))
            (is (not= 43000 (:port result)))
            (is (string? (:message result)))
            (is (re-find #"resolved conflict" (:message result)))))))))

;; =============================================================================
;; Edge Cases and Error Handling
;; =============================================================================

(deftest ^:unit test-edge-cases
  (testing "Edge cases and error conditions"
    (with-redefs [pm/docker-environment? (constantly false)
                  pm/development-environment? (constantly true)]

      (testing "Invalid port range - gracefully uses port range search"
        (let [config {:port-range {:start 8099 :end 8000}}] ; Invalid: start > end
          (with-redefs [pm/port-available? (constantly false)
                        pm/find-available-port (fn [start end]
                                                 (throw (ex-info "No available port found in range"
                                                                 {:start-port start
                                                                  :end-port end})))]
            ;; Should still try to use the range and throw exception
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"No available port found in range"
                 (pm/allocate-port 8080 config))))))

      (testing "Negative port parameter with explicit range"
        (with-redefs [pm/port-available? (constantly true)]
          (let [config {:port-range {:start 3000 :end 3099}}
                result (pm/allocate-port -1 config)]
            (is (number? (:port result)))))))))
