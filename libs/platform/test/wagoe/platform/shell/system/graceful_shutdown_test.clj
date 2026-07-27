(ns wagoe.platform.shell.system.graceful-shutdown-test
  "Integration tests for BOU-86 graceful connection draining.

   Exercises the real `configure-graceful-shutdown!` helper used by the
   `:boundary/http-server` Integrant component: with a drain timeout configured,
   `.stop` must let in-flight requests finish before halting, and new requests
   must be rejected once draining has started.

   These tests bind local server sockets; in sandboxed environments that
   disallow that they skip rather than fail (see `socket-bind-supported?`)."
  (:require [wagoe.platform.shell.system.wiring :as wiring]
            [clj-http.client :as http]
            [clojure.test :refer [deftest testing is]]
            [ring.adapter.jetty :as jetty])
  (:import [java.net ServerSocket]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private configure-graceful-shutdown!
  "Private wiring helper under test."
  #'wiring/configure-graceful-shutdown!)

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

(defn- start-server
  [handler drain-timeout-ms]
  (let [port (free-port)
        server (jetty/run-jetty handler
                                {:port         port
                                 :host         "127.0.0.1"
                                 :join?        false
                                 :configurator (fn [s]
                                                 (configure-graceful-shutdown! s drain-timeout-ms))})]
    [server (str "http://127.0.0.1:" port "/")]))

(deftest ^:integration graceful-drain-completes-in-flight-request
  (if (socket-bind-supported?)
    (testing "stop blocks until the in-flight request drains, and it completes 200"
      (let [entered  (CountDownLatch. 1)
            handler  (fn [_req]
                       (.countDown entered)
                       (Thread/sleep 700)
                       {:status 200 :headers {} :body "done"})
            [server url] (start-server handler 5000)
            result   (promise)
            req      (Thread.
                      (fn []
                        (deliver result
                                 (try
                                   (:status (http/get url {:throw-exceptions false}))
                                   (catch Exception e (str "err:" (.getMessage e)))))))]
        (try
          (.start req)
          ;; Wait until the request is genuinely being handled before stopping.
          (is (.await entered 3 TimeUnit/SECONDS)
              "request reached the handler")
          (let [t0 (System/currentTimeMillis)]
            (.stop server)
            (let [elapsed (- (System/currentTimeMillis) t0)]
              (is (>= elapsed 400)
                  "stop() waited for the in-flight request to drain")))
          (.join req 3000)
          (is (= 200 @result)
              "in-flight request completed successfully during drain")
          (finally
            (try (.stop server) (catch Exception _))))))
    (is (not (socket-bind-supported?))
        "Skipping socket-bind dependent graceful-drain test in sandbox")))

(deftest ^:integration drained-server-rejects-new-requests
  (if (socket-bind-supported?)
    (testing "after graceful stop the server accepts no new requests"
      (let [[server url] (start-server (fn [_req] {:status 200 :headers {} :body "ok"}) 2000)]
        (try
          (Thread/sleep 200)
          (is (= 200 (:status (http/get url {:throw-exceptions false})))
              "server serves requests while running")
          (.stop server)
          (Thread/sleep 200)
          (is (thrown? Exception
                       (http/get url {:throw-exceptions false :connection-timeout 500}))
              "new request after drain is rejected")
          (finally
            (try (.stop server) (catch Exception _))))))
    (is (not (socket-bind-supported?))
        "Skipping socket-bind dependent drain-rejection test in sandbox")))

(deftest ^:integration disabled-drain-is-a-noop
  (if (socket-bind-supported?)
    (testing "zero/nil drain timeout leaves the server runnable and stoppable"
      (doseq [timeout [0 nil]]
        (let [[server url] (start-server (fn [_req] {:status 200 :headers {} :body "ok"}) timeout)]
          (try
            (Thread/sleep 200)
            (is (= 200 (:status (http/get url {:throw-exceptions false})))
                (str "server serves requests with drain timeout " timeout))
            (finally
              (.stop server))))))
    (is (not (socket-bind-supported?))
        "Skipping socket-bind dependent disabled-drain test in sandbox")))
