(ns wagoe.sse-streams-per-node-test
  "With :max-virtual-threads raised, one node holds more SSE streams than
   Jetty's default of 200 and every one of them gets the event (BOU-1056)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [wagoe.platform.shell.system.wiring]
            [wagoe.realtime.ports :as ports]
            [wagoe.realtime.shell.bus.in-memory :as in-memory-bus]
            [wagoe.realtime.shell.connection-registry :as registry]
            [wagoe.realtime.shell.handlers.sse :as sse]
            [wagoe.realtime.shell.pubsub-manager :as pubsub]
            [wagoe.realtime.shell.service :as service])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.util.concurrent CountDownLatch TimeUnit]
           [org.eclipse.jetty.server ServerConnector]))

(def ^:private streams 250)

(defn- await-until [pred]
  (loop [n 0]
    (cond (pred)     true
          (> n 1500) false
          :else      (do (Thread/sleep 20) (recur (inc n))))))

(defn- read-until-event!
  "Read `in` on a virtual thread; count down `got` when the event arrives."
  [in ^CountDownLatch got]
  (Thread/startVirtualThread
   (fn []
     (try
       (with-open [r (BufferedReader. (InputStreamReader. in "UTF-8"))]
         (loop []
           (when-let [line (.readLine r)]
             (if (str/includes? line "<b>hello</b>")
               (.countDown got)
               (recur)))))
       (catch java.io.IOException _ nil)))))

(deftest ^:integration more-streams-than-jettys-default-all-get-the-event
  (let [reg     (registry/create-in-memory-registry)
        ps      (pubsub/create-pubsub-manager)
        svc     (service/create-realtime-service reg nil :pubsub-manager ps
                                                 :bus (in-memory-bus/create-in-memory-bus)
                                                 :max-streams 1000)
        stream  (sse/sse-handler svc :authorize-topic (constantly true) :heartbeat-ms 1000)
        server  (ig/init-key :wagoe/http-server
                             {:handler             (fn [req]
                                                     (stream (assoc req
                                                                    :user {:id (random-uuid) :role :member}
                                                                    :query-params {"topic" "room:1"})))
                              :port                0
                              :host                "127.0.0.1"
                              :virtual-threads?    true
                              :max-virtual-threads 1000})
        port    (.getLocalPort ^ServerConnector (first (.getConnectors server)))
        client  (HttpClient/newHttpClient)
        got     (CountDownLatch. streams)
        opened  (atom [])
        open!   #(.sendAsync client
                             (.build (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port "/events"))))
                             (HttpResponse$BodyHandlers/ofInputStream))]
    (try
      ;; In batches: hundreds of connects at once overflow the listen backlog.
      (doseq [batch (partition-all 50 (range streams))]
        (doseq [b (mapv (fn [_] (open!)) batch)]
          (let [in (.body (.get b 10 TimeUnit/SECONDS))]
            (swap! opened conj in)
            (read-until-event! in got))))
      (is (await-until #(= streams (count (ports/get-topic-subscribers ps "room:1"))))
          "every stream is open and subscribed at once")
      (sse/push-fragment! svc "room:1" [:b "hello"])
      (is (.await got 30 TimeUnit/SECONDS)
          (str (.getCount got) " of " streams " streams did not get the event"))
      (finally
        ;; Close the streams first, so a failure ends instead of hanging the stop.
        (run! #(.close ^java.io.InputStream %) @opened)
        (ig/halt-key! :wagoe/http-server server)))))
