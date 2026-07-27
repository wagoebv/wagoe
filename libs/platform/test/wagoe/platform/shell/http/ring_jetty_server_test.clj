(ns wagoe.platform.shell.http.ring-jetty-server-test
  "Tests for the Ring+Jetty `IHttpServer` adapter.

   Pure tests (protocol satisfaction, nil-stop) run in the default suite. The
   lifecycle tests bind a real, OS-assigned ephemeral port and issue live HTTP
   requests, so they are tagged `^:integration` and skip (rather than fail) in
   sandboxes that disallow socket binding. Ports come from the OS (port 0) and
   readiness is polled instead of slept-on to keep the suite fast and flake-free
   (BOU-172)."
  (:require [wagoe.platform.ports.http :as ports]
            [wagoe.platform.shell.http.ring-jetty-server :as jetty-server]
            [clj-http.client :as http]
            [clojure.test :refer [deftest testing is]])
  (:import [java.net ServerSocket]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(def test-handler
  "Simple handler echoing the request path and method as JSON."
  (fn [request]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (str "{\"path\":\"" (:uri request) "\","
                   "\"method\":\"" (name (:request-method request)) "\"}")}))

(defn- socket-bind-supported?
  "Return true when this environment allows binding a local server socket."
  []
  (try
    (with-open [_socket (ServerSocket. 0)]
      true)
    (catch java.net.SocketException _ false)
    (catch Exception _ false)))

(defn- free-port
  "Grab an ephemeral port the OS hands back, then release it for Jetty to bind."
  []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(defn- wait-until-ready
  "Poll `url` until it answers HTTP or a 5s deadline passes. Returns true when
   the server is accepting requests, false on timeout."
  [url]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (if (try
            (some? (:status (http/get url {:throw-exceptions  false
                                           :connection-timeout 200
                                           :socket-timeout     200})))
            (catch Exception _ false))
        true
        (when (< (System/currentTimeMillis) deadline)
          (Thread/sleep 25)
          (recur))))))

(defn- with-server
  "Start `handler` on an OS-assigned ephemeral port via the adapter, wait until
   it is ready, call `f` with the base URL (no trailing slash), and always stop
   the server afterwards."
  [handler f]
  (let [adapter (jetty-server/create-ring-jetty-server)
        port    (free-port)
        server  (ports/start! adapter handler
                              {:port port :host "127.0.0.1" :join? false})
        url     (str "http://127.0.0.1:" port)]
    (try
      (is (wait-until-ready (str url "/__ready")) "server became ready")
      (f url)
      (finally
        (ports/stop! adapter server)))))

;; =============================================================================
;; Pure Adapter Tests (no socket)
;; =============================================================================

(deftest ^:unit create-server-test
  (testing "Can create Ring+Jetty server instance implementing IHttpServer"
    (let [server (jetty-server/create-ring-jetty-server)]
      (is (some? server))
      (is (satisfies? ports/IHttpServer server)))))

(deftest ^:unit server-stop-without-start-test
  (testing "Stopping nil server does not throw"
    (let [server-adapter (jetty-server/create-ring-jetty-server)]
      (is (nil? (ports/stop! server-adapter nil))))))

;; =============================================================================
;; Lifecycle Tests (bind a real ephemeral port)
;; =============================================================================

(deftest ^:integration start-and-stop-server-test
  (if-not (socket-bind-supported?)
    (is (not (socket-bind-supported?))
        "Skipping socket-bind dependent start/stop test in sandbox")
    (testing "Server starts, serves requests, then stops"
      (let [adapter (jetty-server/create-ring-jetty-server)
            port    (free-port)
            url     (str "http://127.0.0.1:" port)
            server  (ports/start! adapter test-handler
                                  {:port port :host "127.0.0.1" :join? false})]
        (is (wait-until-ready (str url "/__ready")) "server became ready")
        (testing "Server responds to requests"
          (let [response (http/get (str url "/test") {:throw-exceptions false})]
            (is (= 200 (:status response)))
            (is (= "application/json" (get-in response [:headers "Content-Type"])))
            (is (.contains (:body response) "/test"))))
        (testing "Server no longer responds after stop"
          (ports/stop! adapter server)
          (is (thrown? Exception
                       (http/get (str url "/test")
                                 {:throw-exceptions   true
                                  :connection-timeout 500
                                  :socket-timeout     500}))
              "Connection refused as expected"))))))

(deftest ^:integration server-with-different-ports-test
  (if-not (socket-bind-supported?)
    (is (not (socket-bind-supported?))
        "Skipping socket-bind dependent multi-port test in sandbox")
    (testing "Adapter runs independent servers on different ports concurrently"
      (with-server test-handler
        (fn [url1]
          (with-server test-handler
            (fn [url2]
              (let [response1 (http/get (str url1 "/test1") {:throw-exceptions false})
                    response2 (http/get (str url2 "/test2") {:throw-exceptions false})]
                (is (= 200 (:status response1)))
                (is (= 200 (:status response2)))
                (is (.contains (:body response1) "/test1"))
                (is (.contains (:body response2) "/test2"))))))))))

(deftest ^:integration server-configuration-test
  (if-not (socket-bind-supported?)
    (is (not (socket-bind-supported?))
        "Skipping socket-bind dependent configuration test in sandbox")
    (testing "Server respects thread-pool configuration options"
      (let [adapter (jetty-server/create-ring-jetty-server)
            port    (free-port)
            url     (str "http://127.0.0.1:" port)
            server  (ports/start! adapter test-handler
                                  {:port        port
                                   :host        "127.0.0.1"
                                   :join?       false
                                   :max-threads 10
                                   :min-threads 2})]
        (try
          (is (wait-until-ready (str url "/__ready")) "server became ready")
          (is (= 200 (:status (http/get (str url "/")
                                        {:throw-exceptions false}))))
          (finally
            (ports/stop! adapter server)))))))

(deftest ^:integration server-status-codes-test
  (if-not (socket-bind-supported?)
    (is (not (socket-bind-supported?))
        "Skipping socket-bind dependent status-code test in sandbox")
    (testing "Server correctly returns the handler's status codes"
      (let [multi-status-handler (fn [request]
                                   (case (:uri request)
                                     "/ok"        {:status 200 :body "OK"}
                                     "/created"   {:status 201 :body "Created"}
                                     "/no-content" {:status 204}
                                     "/not-found" {:status 404 :body "Not Found"}
                                     "/error"     {:status 500 :body "Error"}
                                     {:status 200 :body "Default"}))]
        (with-server multi-status-handler
          (fn [url]
            (doseq [[path status body] [["/ok" 200 "OK"]
                                        ["/created" 201 "Created"]
                                        ["/no-content" 204 nil]
                                        ["/not-found" 404 "Not Found"]
                                        ["/error" 500 "Error"]]]
              (testing (str status " " path)
                (let [response (http/get (str url path) {:throw-exceptions false})]
                  (is (= status (:status response)))
                  (when body
                    (is (= body (:body response)))))))))))))

(deftest ^:integration start-on-occupied-port-throws-test
  (if-not (socket-bind-supported?)
    (is (not (socket-bind-supported?))
        "Skipping socket-bind dependent bind-failure test in sandbox")
    (testing "start! on an already-bound port throws :server-start-failed"
      ;; Occupy the port with a real running server on the same host, so the
      ;; second bind genuinely conflicts (a wildcard-bound socket would not).
      (let [adapter (jetty-server/create-ring-jetty-server)
            port    (free-port)
            running (ports/start! adapter test-handler
                                  {:port port :host "127.0.0.1" :join? false})]
        (try
          (is (wait-until-ready (str "http://127.0.0.1:" port "/__ready"))
              "first server bound the port")
          (let [ex (try
                     (ports/start! adapter test-handler
                                   {:port port :host "127.0.0.1" :join? false})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
            (is (some? ex) "second start! on the occupied port threw")
            (is (= :server-start-failed (:type (ex-data ex)))
                "adapter maps the bind failure to a typed :server-start-failed error"))
          (finally
            (ports/stop! adapter running)))))))
