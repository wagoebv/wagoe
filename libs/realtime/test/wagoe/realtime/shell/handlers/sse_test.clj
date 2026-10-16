(ns wagoe.realtime.shell.handlers.sse-test
  {:kaocha.testable/meta {:integration true :realtime true}}
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [ring.core.protocols :as ring-protocols]
            [wagoe.realtime.ports :as ports]
            [wagoe.realtime.shell.connection-registry :as registry]
            [wagoe.realtime.shell.pubsub-manager :as pubsub]
            [wagoe.realtime.shell.bus.in-memory :as in-memory-bus]
            [wagoe.realtime.shell.service :as service]
            [wagoe.realtime.shell.adapters.sse-adapter :as sse-adapter]
            [wagoe.realtime.shell.handlers.sse :as sse]
            [wagoe.realtime.shell.module-wiring]
            [integrant.core :as ig])
  (:import (java.io ByteArrayOutputStream IOException OutputStream)))

(def user {:id #uuid "550e8400-e29b-41d4-a716-446655440000" :role :member})

(defn- system
  ([] (system (in-memory-bus/create-in-memory-bus)))
  ([bus & opts]
   (let [reg (registry/create-in-memory-registry)
         ps  (pubsub/create-pubsub-manager)]
     {:service (apply service/create-realtime-service reg nil :pubsub-manager ps :bus bus opts)
      :registry reg
      :pubsub ps})))

(defn- allow-orders [_request topic] (str/starts-with? topic "order:"))

(defn- request [query-params]
  {:request-method :get
   :user (assoc user :language "nl")
   :query-params query-params
   :i18n/catalogue {:nl {:order/count "{n} bestellingen"}}})

(defn- client-stream
  "An output stream a test can read back, and break to simulate the client
   going away."
  []
  (let [buf     (ByteArrayOutputStream.)
        broken? (atom false)]
    {:broken? broken?
     :text    #(locking buf (.toString buf "UTF-8"))
     :out     (proxy [OutputStream] []
                (write
                  ([b] (cond @broken? (throw (IOException. "gone"))
                             (bytes? b) (locking buf (.write buf ^bytes b 0 (alength ^bytes b)))
                             :else      (locking buf (.write buf (int b)))))
                  ([b off len]
                   (if @broken?
                     (throw (IOException. "gone"))
                     (locking buf (.write buf ^bytes b (int off) (int len))))))
                (flush [] (when @broken? (throw (IOException. "gone")))))}))

(defn- open-stream [svc req & opts]
  (let [resp   ((apply sse/sse-handler svc opts) req)
        client (client-stream)
        done   (future (ring-protocols/write-body-to-stream (:body resp) resp (:out client)))]
    (assoc client :response resp :done done)))

(defn- await-until [pred]
  (loop [n 0]
    (cond (pred)    true
          (> n 200) false
          :else     (do (Thread/sleep 10) (recur (inc n))))))

(deftest ^:integration streams-a-pushed-fragment-to-a-subscriber
  (let [{:keys [service registry]} (system)
        {:keys [response text done broken?]}
        (open-stream service (request {"topic" "order:1"}) :authorize-topic allow-orders :heartbeat-ms 20)]
    (is (= 200 (:status response)))
    (is (str/starts-with? (get-in response [:headers "Content-Type"]) "text/event-stream"))
    (is (await-until #(= 1 (ports/connection-count registry))))
    (sse/push-fragment! service "order:1" [:tr#row [:td "Shipped"]])
    (sse/push-fragment! service "order:2" [:p "not mine"])
    (sse/push-fragment! service "order:1" [:span [:t :order/count {:n 3}]] {:event "badge"})
    (is (await-until #(str/includes? (text) "event: badge")))
    (is (str/includes? (text) "event: message\ndata: <tr id=\"row\"><td>Shipped</td></tr>\n\n"))
    (is (str/includes? (text) "event: badge\ndata: <span>3 bestellingen</span>")
        "markers resolve in the subscriber's locale")
    (is (not (str/includes? (text) "not mine")))
    (reset! broken? true)
    (is (not= ::timeout (deref done 2000 ::timeout)))))

(deftest ^:integration fragments-reach-subscribers-on-another-node
  (let [bus      (in-memory-bus/create-in-memory-bus)
        node-a   (system bus)
        node-b   (system bus)
        {:keys [text broken?]} (open-stream (:service node-a) (request {"topic" "order:1"})
                                            :authorize-topic allow-orders :heartbeat-ms 50)]
    (is (await-until #(= 1 (ports/connection-count (:registry node-a)))))
    (sse/push-fragment! (:service node-b) "order:1" [:b "from b"])
    (is (await-until #(str/includes? (text) "data: <b>from b</b>")))
    (reset! broken? true)))

(deftest ^:integration refuses-what-it-should
  (let [{:keys [service registry]} (system)
        handler (sse/sse-handler service :authorize-topic allow-orders)]
    (testing "no signed-in user"
      (is (= 401 (:status (handler (dissoc (request {"topic" "order:1"}) :user)))))
      (is (= 401 (:status (handler (assoc (request {"topic" "order:1"}) :user {:id "not-a-uuid"}))))))
    (testing "a topic the authorizer refuses, alone or among allowed ones"
      (is (= 403 (:status (handler (request {"topic" "admin:all"})))))
      (is (= 403 (:status (handler (request {"topic" ["order:1" "admin:all"]}))))))
    (testing "no topic"
      (is (= 400 (:status (handler (request {}))))))
    (testing "without an authorizer every topic is refused"
      (is (= 403 (:status ((sse/sse-handler service) (request {"topic" "order:1"}))))))
    (is (zero? (ports/connection-count registry)) "nothing refused was registered")))

(deftest ^:integration disconnect-removes-the-subscription
  (let [{:keys [service registry pubsub]} (system)
        {:keys [broken? done]}
        (open-stream service (request {"topic" ["order:1" "order:2"]})
                     :authorize-topic allow-orders :heartbeat-ms 20)]
    (is (await-until #(= 1 (ports/connection-count registry))))
    (is (= 1 (count (ports/get-topic-subscribers pubsub "order:2"))))
    (reset! broken? true)
    (is (not= ::timeout (deref done 2000 ::timeout)) "the stream ends at the next heartbeat")
    (is (zero? (ports/connection-count registry)))
    (is (empty? (ports/get-topic-subscribers pubsub "order:1")))
    (is (empty? (ports/get-topic-subscribers pubsub "order:2")))))

(defn- stream-on [handler req]
  (let [resp   (handler req)
        client (client-stream)]
    (assoc client :response resp
           :done (future (ring-protocols/write-body-to-stream (:body resp) resp (:out client))))))

(deftest ^:integration the-stream-cap-is-per-node-not-per-handler
  (let [{:keys [service registry]} (system (in-memory-bus/create-in-memory-bus) :max-streams 1)
        req   (request {"topic" "order:1"})
        h1    (sse/sse-handler service :authorize-topic allow-orders :heartbeat-ms 20)
        h2    (sse/sse-handler service :authorize-topic allow-orders :heartbeat-ms 20)
        held  (stream-on h1 req)
        _     (await-until #(= 1 (ports/connection-count registry)))
        over  (stream-on h2 req)]
    (is (not= ::timeout (deref (:done over) 2000 ::timeout)) "over the cap the body ends at once")
    (is (= "retry: 10000\n\n" ((:text over))))
    (is (= 1 (ports/connection-count registry)))
    (reset! (:broken? held) true)
    (deref (:done held) 2000 ::timeout)
    (let [again (stream-on h2 req)]
      (is (await-until #(= 1 (ports/connection-count registry))) "a closed stream frees its slot")
      (reset! (:broken? again) true))))

(deftest ^:integration a-response-never-written-holds-no-slot
  (let [{:keys [service registry]} (system (in-memory-bus/create-in-memory-bus) :max-streams 1)
        handler (sse/sse-handler service :authorize-topic allow-orders :heartbeat-ms 20)
        req     (request {"topic" "order:1"})]
    (dotimes [_ 3] (is (= 200 (:status (handler req)))))
    (let [s (stream-on handler req)]
      (is (await-until #(= 1 (ports/connection-count registry))))
      (reset! (:broken? s) true))))

(deftest ^:integration a-stream-closes-when-its-session-goes
  (let [{:keys [service registry]} (system)
        valid?  (atom true)
        handler (sse/sse-handler service :authorize-topic allow-orders :heartbeat-ms 20
                                 :revalidate (fn [_request] @valid?))
        {:keys [done]} (stream-on handler (request {"topic" "order:1"}))]
    (is (await-until #(= 1 (ports/connection-count registry))))
    (Thread/sleep 60)
    (is (not (realized? done)) "still open while the session is good")
    (reset! valid? false)
    (is (not= ::timeout (deref done 2000 ::timeout)))
    (is (zero? (ports/connection-count registry)))))

(deftest ^:unit too-many-topics-is-a-400
  (let [{:keys [service]} (system)
        handler (sse/sse-handler service :authorize-topic allow-orders :max-topics 2)]
    (is (= 200 (:status (handler (request {"topic" ["order:1" "order:2"]})))))
    (is (= 400 (:status (handler (request {"topic" ["order:1" "order:2" "order:3"]})))))))

(deftest ^:integration halting-realtime-closes-open-streams
  (let [component (ig/init-key :wagoe/realtime {:provider :memory})
        handler   (sse/sse-handler (:service component) :authorize-topic allow-orders)
        {:keys [done]} (stream-on handler (request {"topic" "order:1"}))]
    (is (await-until #(= 1 (ports/connection-count (:registry component)))))
    (ig/halt-key! :wagoe/realtime component)
    (is (not= ::timeout (deref done 2000 ::timeout)) "ends without waiting for a heartbeat")
    (is (zero? (ports/connection-count (:registry component))))))

(deftest ^:unit event-names
  (let [conn (sse-adapter/create-sse-connection (random-uuid) nil)]
    (ports/send-message conn {:type :fragment :event :badge :fragment "a"})
    (ports/send-message conn {:payload 1})
    (is (str/starts-with? (sse-adapter/next-event conn 10) "event: badge\n"))
    (is (str/starts-with? (sse-adapter/next-event conn 10) "event: realtime\n")
        "untyped JSON stays off the event sse-swap renders as HTML")))

(deftest ^:unit a-client-that-stops-reading-is-dropped
  (let [conn (sse-adapter/create-sse-connection (random-uuid) nil :queue-size 1)]
    (ports/send-message conn {:type :fragment :fragment "a"})
    (is (ports/open? conn))
    (ports/send-message conn {:type :fragment :fragment "b"})
    (is (not (ports/open? conn)))
    (is (sse-adapter/closed? (sse-adapter/next-event conn 10)))))
