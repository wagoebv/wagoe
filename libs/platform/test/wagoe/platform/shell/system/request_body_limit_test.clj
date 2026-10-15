(ns wagoe.platform.shell.system.request-body-limit-test
  "The HTTP server refuses an oversized body before any handler reads it
   (BOU-644)."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [wagoe.platform.shell.system.wiring])
  (:import [java.io ByteArrayInputStream]
           [org.eclipse.jetty.server ServerConnector]
           [java.net ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(def ^:private client (HttpClient/newHttpClient))

(defn- post
  "Status of a POST of `body` to `port`; chunked when `chunked?`, so the
   server learns the size only by reading."
  ([port body chunked?] (post port "/" body chunked?))
  ([port path ^String body chunked?]
  (let [bytes     (.getBytes body "UTF-8")
        publisher (if chunked?
                    (HttpRequest$BodyPublishers/ofInputStream
                     (reify java.util.function.Supplier
                       (get [_] (ByteArrayInputStream. bytes))))
                    (HttpRequest$BodyPublishers/ofByteArray bytes))
        request   (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port path)))
                      (.POST publisher)
                      (.build))]
    (.statusCode (.send client request (HttpResponse$BodyHandlers/discarding))))))

(defn- with-server [opts f]
  (let [port   (free-port)
        server (ig/init-key :wagoe/http-server
                            (merge {:handler (fn [req] (slurp (:body req)) {:status 200 :body "ok"})
                                    :port    port
                                    :host    "127.0.0.1"}
                                   opts))]
    (try (f port) (finally (ig/halt-key! :wagoe/http-server server)))))

(deftest ^:integration an-oversized-body-is-a-413
  (with-server {:max-request-body 16}
    (fn [port]
      (testing "a body within the limit reaches the handler"
        (is (= 200 (post port "small" false))))
      (let [over (apply str (repeat 64 "x"))]
        (testing "one over it is refused, whether its length is declared"
          (is (= 413 (post port over false))))
        (testing "or found out by reading"
          (is (= 413 (post port over true))))))))

(deftest ^:integration nil-lifts-the-limit
  (with-server {:max-request-body nil}
    (fn [port]
      (is (= 200 (post port (apply str (repeat (* 64 1024) "x")) false))))))

(defprotocol IEcho (echo [this x]))

(deftest ^:integration the-rpc-listener-is-limited-too
  ;; It decodes the body before it checks the service key.
  (let [server (ig/init-key :wagoe/rpc-server
                            {:protocol         `IEcho
                             :implementation   (reify IEcho (echo [_ x] x))
                             :port             0
                             :host             "127.0.0.1"
                             :service-key      "test-service-key-0123456789abcdef"
                             :max-request-body 16})]
    (try
      (is (= 413 (post (.getLocalPort ^ServerConnector (first (.getConnectors server)))
                       "/rpc" (apply str (repeat 64 "x")) false)))
      (finally (ig/halt-key! :wagoe/rpc-server server)))))
