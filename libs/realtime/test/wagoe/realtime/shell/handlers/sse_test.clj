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
            [wagoe.realtime.shell.handlers.sse :as sse])
  (:import (java.io ByteArrayOutputStream IOException OutputStream)))

(def user {:id #uuid "550e8400-e29b-41d4-a716-446655440000" :role :member})

(defn- system
  ([] (system (in-memory-bus/create-in-memory-bus)))
  ([bus]
   (let [reg (registry/create-in-memory-registry)
         ps  (pubsub/create-pubsub-manager)]
     {:service (service/create-realtime-service reg nil :pubsub-manager ps :bus bus)
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

(deftest ^:unit a-client-that-stops-reading-is-dropped
  (let [conn (sse-adapter/create-sse-connection (random-uuid) nil :queue-size 1)]
    (ports/send-message conn {:type :fragment :fragment "a"})
    (is (ports/open? conn))
    (ports/send-message conn {:type :fragment :fragment "b"})
    (is (not (ports/open? conn)))
    (is (sse-adapter/closed? (sse-adapter/next-event conn 10)))))
