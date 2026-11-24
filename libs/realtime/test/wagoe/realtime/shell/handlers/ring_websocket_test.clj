(ns wagoe.realtime.shell.handlers.ring-websocket-test
  "Tests for the Ring 1.15 WebSocket upgrade handler."
  {:kaocha.testable/meta {:integration true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.shell.handlers.ring-websocket :as ws-handler]
            [wagoe.realtime.shell.service :as service]
            [wagoe.realtime.shell.connection-registry :as registry]
            [wagoe.realtime.shell.adapters.jwt-adapter :as jwt]
            [wagoe.realtime.ports :as ports]
            [ring.websocket.protocols :as ws-protocols]
            [wagoe.observability.errors.ports :as error-ports]
            [wagoe.observability.errors.shell.adapters.sentry :as sentry-adapter]
            [sentry-clj.core :as sentry]))

(def test-user-id #uuid "550e8400-e29b-41d4-a716-446655440000")

(defn- create-test-service []
  (let [reg (registry/create-in-memory-registry)
        jwt-adapter (jwt/create-test-jwt-adapter
                     {:expected-token "valid-token"
                      :user-id test-user-id
                      :email "test@example.com"
                      :roles #{:admin}})
        svc (service/create-realtime-service reg jwt-adapter)]
    {:service svc :registry reg}))

(defn- mock-ring-socket
  "Creates a mock Ring WebSocket socket implementing Socket protocol."
  []
  (let [sent  (atom [])
        open? (atom true)]
    {:socket (reify
               ws-protocols/Socket
               (-send [_ msg] (swap! sent conj msg))
               (-ping [_ _data] nil)
               (-pong [_ _data] nil)
               (-close [_ _code _reason] (reset! open? false))
               (-open? [_] @open?))
     :sent  sent
     :open? open?}))

(deftest ^:contract websocket-handler-returns-400-without-token
  (let [{:keys [service]} (create-test-service)
        handler (ws-handler/websocket-handler service)
        response (handler {:query-params {}})]
    (is (= 400 (:status response)))
    (is (string? (:body response)))))

(deftest ^:contract websocket-handler-returns-listener-with-token
  (let [{:keys [service]} (create-test-service)
        handler (ws-handler/websocket-handler service)
        response (handler {:query-params {"token" "valid-token"}})]
    (is (contains? response :ring.websocket/listener))
    (is (map? (:ring.websocket/listener response)))
    (is (fn? (get-in response [:ring.websocket/listener :on-open])))
    (is (fn? (get-in response [:ring.websocket/listener :on-close])))
    (is (fn? (get-in response [:ring.websocket/listener :on-error])))))

(deftest ^:contract websocket-handler-custom-token-param
  (testing "custom token-param name"
    (let [{:keys [service]} (create-test-service)
          handler (ws-handler/websocket-handler service :token-param "jwt")
          response-missing (handler {:query-params {}})
          response-ok (handler {:query-params {"jwt" "valid-token"}})]
      (is (= 400 (:status response-missing)))
      (is (contains? response-ok :ring.websocket/listener)))))

(deftest ^:contract websocket-handler-on-open-registers-connection
  (let [{:keys [service registry]} (create-test-service)
        handler (ws-handler/websocket-handler service)
        response (handler {:query-params {"token" "valid-token"}})
        listener (:ring.websocket/listener response)
        {:keys [socket]} (mock-ring-socket)]
    ((:on-open listener) socket)
    (is (= 1 (ports/connection-count registry)))))

(deftest ^:contract websocket-handler-on-open-callback-receives-connection-id
  (testing ":on-open opt is invoked with the established connection-id"
    (let [{:keys [service registry]} (create-test-service)
          seen    (atom nil)
          handler (ws-handler/websocket-handler
                   service :on-open (fn [cid] (reset! seen cid)))
          response (handler {:query-params {"token" "valid-token"}})
          listener (:ring.websocket/listener response)
          {:keys [socket]} (mock-ring-socket)]
      ((:on-open listener) socket)
      (is (some? @seen) "callback received a connection-id")
      (is (some? (ports/find-connection registry @seen))
          "connection-id resolves to the registered connection"))))

(deftest ^:contract websocket-handler-on-open-callback-errors-are-swallowed
  (testing "a throwing :on-open callback does not abort the connection"
    (let [{:keys [service registry]} (create-test-service)
          handler (ws-handler/websocket-handler
                   service :on-open (fn [_] (throw (ex-info "boom" {}))))
          response (handler {:query-params {"token" "valid-token"}})
          listener (:ring.websocket/listener response)
          {:keys [socket open?]} (mock-ring-socket)]
      ((:on-open listener) socket)
      (is (true? @open?) "socket stays open despite callback failure")
      (is (= 1 (ports/connection-count registry))
          "connection remains registered"))))

(deftest ^:contract websocket-handler-on-close-unregisters-connection
  (let [{:keys [service registry]} (create-test-service)
        handler (ws-handler/websocket-handler service)
        response (handler {:query-params {"token" "valid-token"}})
        listener (:ring.websocket/listener response)
        {:keys [socket open?]} (mock-ring-socket)]
    ((:on-open listener) socket)
    (is (= 1 (ports/connection-count registry)))
    ;; Simulate socket closing (Ring sets open? to false before on-close fires)
    (reset! open? false)
    ((:on-close listener) socket 1000 "Normal")
    ;; disconnect is best-effort, verify it attempted cleanup
    (is (= 0 (ports/connection-count registry))
        "connection should be unregistered after on-close")))

(deftest ^:contract websocket-handler-on-error-unregisters-connection
  (testing "on-error triggers disconnect"
    (let [{:keys [service registry]} (create-test-service)
          handler (ws-handler/websocket-handler service)
          response (handler {:query-params {"token" "valid-token"}})
          listener (:ring.websocket/listener response)
          {:keys [socket open?]} (mock-ring-socket)]
      ((:on-open listener) socket)
      (is (= 1 (ports/connection-count registry)))
      (reset! open? false)
      ((:on-error listener) socket (Exception. "test error"))
      (is (= 0 (ports/connection-count registry))))))

(deftest ^:contract a-message-reports-nothing-from-the-one-before-it
  ;; One socket thread runs both (BOU-848).
  (let [sent (atom [])]
    (with-redefs [sentry/init!      (fn [& _] nil)
                  sentry/send-event #(swap! sent conj %)]
      (let [reporter (sentry-adapter/create-sentry-error-reporting-component
                      {:dsn "https://key@sentry.invalid/1"})
            svc      (service/create-realtime-service
                      (registry/create-in-memory-registry)
                      (jwt/create-test-jwt-adapter {:expected-token "valid-token"
                                                    :user-id test-user-id
                                                    :roles #{:admin}})
                      :error-reporter reporter)
            handler  (ws-handler/websocket-handler
                      svc :on-message (fn [_ msg]
                                        (if (= "a" msg)
                                          (error-ports/set-user! reporter {:id "user-a"})
                                          (error-ports/capture-message reporter "from b" :error))))
            listener (:ring.websocket/listener (handler {:query-params {"token" "valid-token"}}))
            {:keys [socket]} (mock-ring-socket)]
        ((:on-open listener) socket)
        ((:on-message listener) socket "a")
        ((:on-message listener) socket "b")
        (is (empty? (error-ports/current-context reporter)) "the thread keeps nothing")))
    (is (= 1 (count @sent)))
    (is (not (.contains (pr-str (first @sent)) "user-a")))))

(deftest ^:contract ^:security websocket-connections-are-capped-per-node-and-per-user
  ;; BOU-1102: the WebSocket path took no slot, so one user could open
  ;; connections until the node ran out.
  (let [reg     (registry/create-in-memory-registry)
        jwt-a   (jwt/create-test-jwt-adapter {:expected-token "valid-token" :user-id test-user-id
                                              :email "test@example.com" :roles #{:admin}})
        svc     (service/create-realtime-service reg jwt-a :max-websockets 2)
        handler (ws-handler/websocket-handler svc :max-connections-per-user 1)
        req     (fn [user-id] {:query-params {"token" "valid-token"} :user {:id user-id}})
        open!   (fn [user-id]
                  (let [listener (:ring.websocket/listener (handler (req user-id)))
                        m        (mock-ring-socket)]
                    ((:on-open listener) (:socket m))
                    (assoc m :listener listener)))
        first   (open! test-user-id)]
    (is (= 1 (ports/connection-count reg)))
    (testing "one more for the same user is refused before the upgrade"
      (is (= 429 (:status (handler (req test-user-id))))))
    (testing "a burst that raced past that check is closed on open"
      (let [listener (:ring.websocket/listener
                      (with-redefs [service/websockets-full? (constantly false)] (handler (req test-user-id))))
            m        (mock-ring-socket)]
        ((:on-open listener) (:socket m))
        (is (false? @(:open? m)))
        (is (= 1 (ports/connection-count reg)))
        (testing "and closing it gives back nothing it did not take"
          ((:on-close listener) (:socket m) 1000 "")
          (is (= 429 (:status (handler (req test-user-id))))))))
    (testing "the node cap counts everyone"
      (let [second (open! (random-uuid))]
        (is (= 2 (ports/connection-count reg)))
        (is (= 429 (:status (handler (req (random-uuid))))))
        ((get-in second [:listener :on-close]) (:socket second) 1000 "")))
    (testing "close frees the slot, once, even with an error after it"
      ((get-in first [:listener :on-close]) (:socket first) 1000 "")
      ((get-in first [:listener :on-error]) (:socket first) (Exception. "late"))
      ;; Freed once: the node holds exactly its two again, not three.
      (let [a (open! (random-uuid))
            b (open! (random-uuid))]
        (is (= 429 (:status (handler (req (random-uuid))))) "the node did not go below zero")
        (doseq [x [a b]] ((get-in x [:listener :on-close]) (:socket x) 1000 ""))))
    (testing "an admin viewing as a member counts as the admin"
      (let [admin {:id (random-uuid)}
            as-a  #(assoc (req (random-uuid)) :viewing-as {:admin admin})
            l     (:ring.websocket/listener (handler (as-a)))]
        ((:on-open l) (:socket (mock-ring-socket)))
        (is (= 429 (:status (handler (as-a)))))))))
