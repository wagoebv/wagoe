(ns wagoe.platform.shell.system.virtual-threads-test
  "`:virtual-threads? true` on a listener serves each request on a virtual
   thread; without it Jetty's platform pool serves them."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.platform.shell.system.wiring :as wiring])
  (:import [org.eclipse.jetty.server Server ServerConnector]
           [org.eclipse.jetty.util.thread VirtualThreadPool]
           [java.net ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]))

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- served-on-virtual-thread? [opts]
  (let [server (ig/init-key :wagoe/http-server
                            (merge {:handler (fn [_] {:status 200
                                                      :body   (str (.isVirtual (Thread/currentThread)))})
                                    :port    (free-port)
                                    :host    "127.0.0.1"}
                                   opts))]
    (try
      (let [port (.getLocalPort ^ServerConnector (first (.getConnectors server)))
            resp (.send (HttpClient/newHttpClient)
                        (.build (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port "/"))))
                        (HttpResponse$BodyHandlers/ofString))]
        (is (= 200 (.statusCode resp)))
        (parse-boolean (.body resp)))
      (finally (ig/halt-key! :wagoe/http-server server)))))

(deftest ^:integration virtual-threads-serve-requests
  (testing "on"
    (is (true? (served-on-virtual-thread? {:virtual-threads? true}))))
  (testing "off by default"
    (is (false? (served-on-virtual-thread? {})))))

(defn- pool-max [opts]
  (let [server (ig/init-key :wagoe/http-server
                            (merge {:handler (fn [_] {:status 200}) :port (free-port) :host "127.0.0.1"}
                                   opts))]
    (try (.getMaxThreads ^VirtualThreadPool (.getThreadPool ^Server server))
         (finally (ig/halt-key! :wagoe/http-server server)))))

(deftest ^:integration the-pool-maximum-is-configurable
  ;; BOU-1056: Jetty's default of 200 also bounds SSE streams per node.
  (is (= 200 (pool-max {:virtual-threads? true})) "absent keeps Jetty's default")
  (is (= 2000 (pool-max {:virtual-threads? true :max-virtual-threads 2000}))))

(deftest ^:unit a-bad-pool-maximum-fails-the-boot
  (doseq [bad [0 -1 "300" 1.5 (inc Integer/MAX_VALUE)]
          vt? [true false]]
    (let [e (try (#'wiring/virtual-thread-pool vt? "test" bad)
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= ["WGE-108" :configuration-error] ((juxt :wagoe/error-code :type) (ex-data e)))
          (str (pr-str bad) " with virtual threads " vt?)))))

(deftest ^:unit a-maximum-without-virtual-threads-builds-no-pool
  ;; It warns instead: the platform pool ignores it.
  (is (nil? (#'wiring/virtual-thread-pool false "test" 500))))

(defprotocol IEcho (echo [this x]))

(deftest ^:integration the-rpc-listener-takes-the-flag-too
  (doseq [vt? [true false]]
    (let [server (ig/init-key :wagoe/rpc-server
                              {:protocol         `IEcho
                               :implementation   (reify IEcho (echo [_ x] x))
                               :port             0
                               :host             "127.0.0.1"
                               :service-key      "test-service-key-0123456789abcdef"
                               :virtual-threads? vt?
                               :max-virtual-threads 500})]
      (try
        (is (= vt? (instance? VirtualThreadPool (.getThreadPool ^Server server))))
        (when vt? (is (= 500 (.getMaxThreads ^VirtualThreadPool (.getThreadPool ^Server server)))))
        (finally (ig/halt-key! :wagoe/rpc-server server))))))
