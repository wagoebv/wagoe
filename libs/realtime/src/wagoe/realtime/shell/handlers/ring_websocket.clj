(ns wagoe.realtime.shell.handlers.ring-websocket
  "Ring 1.15 WebSocket upgrade handler for boundary-realtime.

   Bridges Ring's map-based Listener (::ring.websocket/listener response)
   to the IRealtimeService connect/disconnect lifecycle.

   Usage:
     (require '[wagoe.realtime.shell.handlers.ring-websocket :as ws-handler])

     ;; In your route definitions
     {:path \"/ws\"
      :methods {:get {:handler (ws-handler/websocket-handler realtime-service)}}}"
  (:require [wagoe.realtime.ports :as realtime-ports]
            [wagoe.realtime.shell.adapters.websocket-adapter :as ws-adapter]
            [clojure.tools.logging :as log]
            [ring.websocket :as ring-ws])
  (:import [java.util UUID]))

(defn websocket-handler
  "Returns a Ring handler that upgrades GET requests to WebSocket.

   Expects a `token` query parameter for JWT authentication.
   The token is verified via the realtime-service's JWT verifier
   during the connect handshake.

   Lifecycle:
     on-open    → creates adapter, calls realtime-ports/connect (JWT auth)
     on-message → no-op (override with :on-message opt for bidirectional)
     on-close   → calls realtime-ports/disconnect
     on-error   → calls realtime-ports/disconnect

   Args:
     realtime-service - IRealtimeService implementation

   Options (keyword args):
     :token-param  - query param name for JWT (default \"token\")
     :on-message   - optional (fn [ws-socket message]) for client→server
     :on-open      - optional (fn [connection-id]) invoked after a successful
                     connect. Use to subscribe the connection to topics based
                     on the authenticated user's roles. Exceptions thrown by
                     this callback are logged and swallowed — they do not abort
                     the connection.

   Returns:
     Ring handler fn that returns a ::ring.websocket/listener response"
  [realtime-service & {:keys [token-param on-message on-open]
                       :or   {token-param "token"}}]
  (fn [request]
    (let [token (get-in request [:query-params token-param])]
      (if (nil? token)
        {:status 400 :body (str "Missing " token-param " query parameter")}
        (let [conn-id-atom (atom nil)]
          {::ring-ws/listener
           {:on-open
            (fn [ws-socket]
              (try
                (let [adapter-id    (UUID/randomUUID)
                      ws-channel    {:send!  (fn [msg] (ring-ws/send ws-socket msg))
                                     :close! (fn [] (ring-ws/close ws-socket))
                                     :open?  (fn [] (ring-ws/open? ws-socket))}
                      adapter       (ws-adapter/create-ring-websocket-adapter adapter-id ws-channel)
                      connection-id (realtime-ports/connect realtime-service adapter {token-param token})]
                  (reset! conn-id-atom connection-id)
                  (log/debug "WebSocket connected" {:connection-id connection-id})
                  (when on-open
                    (try
                      (on-open connection-id)
                      (catch Exception e
                        (log/warn e "on-open callback failed"
                                  {:connection-id connection-id})))))
                (catch Exception e
                  (log/warn "WebSocket auth failed — closing" {:error (.getMessage e)})
                  (ring-ws/close ws-socket))))

            :on-message
            (if on-message
              (fn [ws-socket msg] (on-message ws-socket msg))
              (fn [_ws-socket _msg] nil))

            :on-close
            (fn [_ws-socket _code _reason]
              (when-let [cid @conn-id-atom]
                (try
                  (realtime-ports/disconnect realtime-service cid)
                  (catch Exception e
                    (log/warn e "Error during WebSocket disconnect")))
                (log/debug "WebSocket disconnected" {:connection-id cid})))

            :on-error
            (fn [_ws-socket err]
              (when-let [cid @conn-id-atom]
                (try
                  (realtime-ports/disconnect realtime-service cid)
                  (catch Exception e
                    (log/warn e "Error during WebSocket error cleanup")))
                (log/debug "WebSocket error" {:connection-id cid :error (.getMessage err)})))}})))))
