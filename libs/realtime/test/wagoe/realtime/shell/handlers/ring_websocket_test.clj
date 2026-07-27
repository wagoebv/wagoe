(ns wagoe.realtime.shell.handlers.ring-websocket-test
  "Tests for the Ring 1.15 WebSocket upgrade handler."
  {:kaocha.testable/meta {:integration true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.shell.handlers.ring-websocket :as ws-handler]
            [wagoe.realtime.shell.service :as service]
            [wagoe.realtime.shell.connection-registry :as registry]
            [wagoe.realtime.shell.adapters.jwt-adapter :as jwt]
            [wagoe.realtime.ports :as ports]
            [ring.websocket.protocols :as ws-protocols]))

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
