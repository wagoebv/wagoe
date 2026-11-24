(ns wagoe.realtime.shell.handlers.ring-websocket
  "Ring 1.15 WebSocket upgrade handler for wagoe-realtime.

   Bridges Ring's map-based Listener (::ring.websocket/listener response)
   to the IRealtimeService connect/disconnect lifecycle.

   Usage:
     (require '[wagoe.realtime.shell.handlers.ring-websocket :as ws-handler])

     ;; In your route definitions
     [\"/ws\" {:get {:handler (ws-handler/websocket-handler realtime-service)}}]"
  (:require [wagoe.realtime.core.sse :as sse]
            [wagoe.realtime.ports :as realtime-ports]
            [wagoe.realtime.shell.service :as service]
            [wagoe.realtime.shell.adapters.websocket-adapter :as ws-adapter]
            [clojure.tools.logging :as log]
            [ring.websocket :as ring-ws])
  (:import [java.util UUID]
           [java.util.concurrent.atomic AtomicBoolean]))

(defn websocket-handler
  "Returns a Ring handler that upgrades GET requests to WebSocket.

   Expects a `token` query parameter for JWT authentication.
   The token is verified via the realtime-service's JWT verifier
   during the connect handshake.

   Mounted in a Wagoe application, the route is behind the platform's
   default-deny: the user module reads `?token=` on an upgrade request, so a
   missing or bad token is refused with 401 before this handler runs (BOU-568).
   Leave the route un-`:public`; a custom `:token-param` is not read there.

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
     :max-connections-per-user - open connections per user on this node; nil,
                     the default, caps only the node (`:max-websockets` on
                     `:wagoe/realtime`). Over either cap the upgrade is a 429; a
                     burst past that check is closed on open. An admin viewing
                     as a member counts as the admin. It needs `:user` on the
                     request, as the platform's auth sets (BOU-1102).

   Returns:
     Ring handler fn that returns a ::ring.websocket/listener response"
  [realtime-service & {:keys [token-param on-message on-open max-connections-per-user]
                       :or   {token-param "token"}}]
  (fn [request]
    (let [token (get-in request [:query-params token-param])
          owner (sse/stream-owner request)
          cap   max-connections-per-user]
      (cond
        (nil? token)
        {:status 400 :body (str "Missing " token-param " query parameter")}

        ;; Before the upgrade, so a refused connection costs no socket.
        (service/websockets-full? realtime-service owner cap)
        {:status 429 :headers {"Retry-After" "10"} :body "Too many open connections"}

        :else
        (let [conn-id-atom (atom nil)
              ;; Taken on open, not here: an upgrade that never completes
              ;; would hold it. Released once, by whichever of close and
              ;; error comes first.
              held?        (AtomicBoolean. false)
              release!     #(when (.compareAndSet held? true false)
                              (service/release-websocket! realtime-service owner cap))]
          {::ring-ws/listener
           {:on-open
            (fn [ws-socket]
              (if-not (service/acquire-websocket! realtime-service owner cap)
                (do (log/warn "WebSocket connection limit reached")
                    (ring-ws/close ws-socket))
                (do (.set held? true)
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
                            (service/with-clean-error-context realtime-service #(on-open connection-id))
                            (catch Exception e
                              (log/warn e "on-open callback failed"
                                        {:connection-id connection-id})))))
                      (catch Exception e
                        (log/warn "WebSocket auth failed — closing" {:error (.getMessage e)})
                        (release!)
                        (ring-ws/close ws-socket))))))

            :on-message
            (if on-message
              ;; A socket thread holds the previous message's error context (BOU-848).
              (fn [ws-socket msg]
                (service/with-clean-error-context realtime-service #(on-message ws-socket msg)))
              (fn [_ws-socket _msg] nil))

            :on-close
            (fn [_ws-socket _code _reason]
              (release!)
              (when-let [cid @conn-id-atom]
                (try
                  (realtime-ports/disconnect realtime-service cid)
                  (catch Exception e
                    (log/warn e "Error during WebSocket disconnect")))
                (log/debug "WebSocket disconnected" {:connection-id cid})))

            :on-error
            (fn [_ws-socket err]
              (release!)
              (when-let [cid @conn-id-atom]
                (try
                  (realtime-ports/disconnect realtime-service cid)
                  (catch Exception e
                    (log/warn e "Error during WebSocket error cleanup")))
                (log/debug "WebSocket error" {:connection-id cid :error (.getMessage err)})))}})))))
