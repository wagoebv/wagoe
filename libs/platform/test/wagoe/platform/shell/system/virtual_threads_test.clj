(ns wagoe.platform.shell.system.virtual-threads-test
  "`:virtual-threads? true` on :wagoe/http-server serves each request on a
   virtual thread; without it Jetty's platform pool serves them."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.platform.shell.system.wiring])
  (:import [org.eclipse.jetty.server ServerConnector]
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
