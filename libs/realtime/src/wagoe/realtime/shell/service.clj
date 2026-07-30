(ns wagoe.realtime.shell.service
  "Realtime service implementation (Shell layer).

  Orchestrates WebSocket messaging between core logic and adapters.
  Implements the imperative shell in the FC/IS architecture pattern.

  Responsibilities (Shell/I/O):
  - WebSocket connection lifecycle (open, close)
  - JWT authentication (delegates to user module)
  - Message routing (publishes envelopes onto the message bus)
  - Connection registry management
  - Pub/sub topic management
  - Logging and error handling

  Does NOT contain:
  - Business logic (lives in core.*)
  - Database operations (no persistence needed for WebSockets)
  - Message validation logic (lives in core.message)"
  (:require [wagoe.realtime.ports :as ports]
            [wagoe.realtime.core.bus :as bus]
            [wagoe.realtime.core.connection :as conn]
            [wagoe.realtime.core.auth :as auth]
            [wagoe.realtime.shell.bus.in-memory :as in-memory-bus]
            [wagoe.realtime.shell.delivery :as delivery]
            [clojure.tools.logging :as log])
  (:import (java.time Instant)))

;; =============================================================================
;; Helper Functions (External Dependencies)
;; =============================================================================

(defn- current-timestamp
  "Get current timestamp. Shell layer responsibility for time dependency."
  []
  (Instant/now))

(defn- stamp
  "Add :timestamp if absent (shell owns the clock)."
  [message]
  (if (:timestamp message)
    message
    (assoc message :timestamp (current-timestamp))))

;; =============================================================================
;; Realtime Service (Shell Layer)
;; =============================================================================

(defrecord RealtimeService [connection-registry jwt-verifier pubsub-manager logger error-reporter bus]
  ports/IRealtimeService

  (connect [_this ws-connection query-params]
    ;; Shell: I/O and side effects for connection establishment
    (try
      ;; 1. Extract token from query params (pure core function)
      (let [token (auth/extract-token-from-query query-params)]
        (when-not token
          (throw (ex-info "Unauthorized: Missing JWT token in query params"
                          {:type :unauthorized
                           :message "WebSocket connection requires 'token' query parameter"})))

        ;; 2. Verify JWT token (delegates to user module via adapter)
        (let [claims (ports/verify-jwt jwt-verifier token)
              now (current-timestamp)
              connection-id (java.util.UUID/randomUUID)

              ;; 3. Create connection record (pure core function)
              connection (conn/create-connection
                          (:user-id claims)
                          (:roles claims)
                          {:email (:email claims)
                           :connected-at now}
                          connection-id
                          now)]

          ;; 4. Register connection in registry (I/O - stateful atom)
          (ports/register connection-registry connection-id connection ws-connection)

          ;; 5. Log connection event
          (log/info "WebSocket connection established"
                    {:connection-id connection-id
                     :user-id (:user-id claims)
                     :email (:email claims)
                     :roles (:roles claims)})

          ;; Return connection ID
          connection-id))
      (catch clojure.lang.ExceptionInfo e
        ;; Re-throw with proper logging
        (log/warn "WebSocket connection failed"
                  {:error-type (:type (ex-data e))
                   :message (.getMessage e)})
        (throw e))
      (catch Exception e
        ;; Unexpected error - log and wrap
        (log/error e "Unexpected error during WebSocket connection")
        (throw (ex-info "Internal error during WebSocket connection"
                        {:type :internal-error
                         :message (.getMessage e)})))))

  (disconnect [_this connection-id]
    ;; Shell: Clean up connection
    (try
      ;; 1. Get connection for logging
      (let [connection (ports/find-connection connection-registry connection-id)]

        ;; 2. Clean up pub/sub subscriptions (if pubsub-manager exists)
        (when pubsub-manager
          (ports/unsubscribe-from-all-topics pubsub-manager connection-id))

        ;; 3. Remove from registry
        (ports/unregister connection-registry connection-id)

        ;; 4. Log disconnection
        (when connection
          (log/info "WebSocket connection closed"
                    {:connection-id connection-id
                     :user-id (:user-id connection)
                     :duration-seconds (conn/connection-age connection (current-timestamp))}))

        nil)
      (catch Exception e
        ;; Log error but don't throw - disconnection is best-effort
        (log/warn e "Error during WebSocket disconnection"
                  {:connection-id connection-id}))))

  (send-to-user [_this user-id message]
    (ports/publish bus (bus/user-envelope user-id (stamp message))))

  (send-to-role [_this role message]
    (ports/publish bus (bus/role-envelope role (stamp message))))

  (broadcast [_this message]
    (ports/publish bus (bus/broadcast-envelope (stamp message))))

  (send-to-connection [_this connection-id message]
    (let [n (ports/publish bus (bus/connection-envelope connection-id (stamp message)))]
      (when (some? n) (pos? n))))

  (publish-to-topic [_this topic message]
    (ports/publish bus (bus/topic-envelope topic (stamp message)))))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-realtime-service
  "Create realtime service for WebSocket messaging.

   Options:
     :pubsub-manager  IPubSubManager (optional, for topic support)
     :logger          logger instance (optional)
     :error-reporter  error reporter (optional)
     :bus             IMessageBus (optional; defaults to a fresh
                      InMemoryMessageBus for single-node use)

   On construction the service registers a node-local delivery-fn with the bus
   (start-subscriber!). Pass a shared bus to two services to relay between them."
  [connection-registry jwt-verifier
   & {:keys [pubsub-manager logger error-reporter bus]}]
  (let [bus (or bus (in-memory-bus/create-in-memory-bus))
        svc (->RealtimeService connection-registry jwt-verifier
                               pubsub-manager logger error-reporter bus)]
    (ports/start-subscriber! bus (delivery/make-delivery-fn connection-registry pubsub-manager))
    svc))
