(ns wagoe.realtime.ports
  "Port definitions for real-time WebSocket communication.
  
   This module defines protocols for WebSocket-based messaging, similar to
   Phoenix Channels (Elixir) or Socket.io (Node.js). Supports point-to-point,
   broadcast, and role-based messaging with JWT authentication.
   
   Key Features:
   - WebSocket connection management
   - Message routing (user, role, broadcast, connection-specific)
   - JWT-based authentication
   - Connection registry (in-memory or Redis)")

;; =============================================================================
;; Real-time Service Ports
;; =============================================================================

(defprotocol IRealtimeService
  "Service orchestration for WebSocket real-time communication.
  
  Shell layer implementation coordinates between core logic (pure functions)
  and adapters (WebSocket I/O, connection registry). Implements the imperative
  shell in the FC/IS architecture pattern."

  (connect [this ws-connection query-params]
    "Establish WebSocket connection with JWT authentication.
    
    Validates JWT token from query parameters, creates connection record,
    and registers connection in registry. Token extraction and validation
    delegated to auth module (wagoe/user).
    
    Args:
      ws-connection - WebSocket connection adapter (IWebSocketConnection)
      query-params - Map of query parameters (expects 'token' key)
    
    Returns:
      Connection ID (UUID) if successful
    
    Throws:
      ExceptionInfo with {:type :unauthorized :message ...} if:
        - Token missing from query params
        - Token invalid (expired, malformed, wrong signature)
        - JWT verification fails")

  (disconnect [this connection-id]
    "Close WebSocket connection and clean up.
    
    Removes connection from registry, closes WebSocket, and logs disconnection.
    Idempotent - safe to call multiple times for same connection.
    
    Args:
      connection-id - UUID of connection to close
    
    Returns:
      nil
    
    Side Effects:
      - Removes connection from registry
      - Closes WebSocket connection
      - Logs disconnection event")

  (send-to-user [this user-id message]
    "Send message to all WebSocket connections for a user.
    
    Routes message to all active connections belonging to the specified user.
    User may have multiple connections (e.g., multiple browser tabs, mobile app).
    Uses core/message routing logic to determine target connections.
    
    Args:
      user-id - Target user UUID
      message - Message map with:
                :type - Message type keyword (e.g., :notification, :update)
                :payload - Message data (will be JSON-encoded)
                :timestamp - Optional timestamp (auto-added if missing)
    
    Returns:
      Number of connections message was sent to (integer >= 0) under the
      :in-memory provider. Returns nil under the :redis provider (async
      fan-out; count not known synchronously).

    Side Effects:
      - Sends message over WebSocket to matching connections
      - Logs send event (debug level)")

  (send-to-role [this role message]
    "Send message to all WebSocket connections with a specific role.
    
    Routes message to all connections where user has the specified role.
    Useful for admin notifications, moderator alerts, etc.
    Uses core/message routing logic with role-based filtering.
    
    Args:
      role - Target role keyword (e.g., :admin, :moderator)
      message - Message map (same structure as send-to-user)
    
    Returns:
      Number of connections message was sent to (integer >= 0) under the
      :in-memory provider. Returns nil under the :redis provider (async
      fan-out; count not known synchronously).

    Side Effects:
      - Sends message over WebSocket to matching connections
      - Logs send event (debug level)")

  (broadcast [this message]
    "Send message to all active WebSocket connections.
    
    Routes message to every connection in the registry. Use sparingly -
    consider send-to-role or send-to-user for more targeted messaging.
    
    Args:
      message - Message map (same structure as send-to-user)
    
    Returns:
      Number of connections message was sent to (integer >= 0) under the
      :in-memory provider. Returns nil under the :redis provider (async
      fan-out; count not known synchronously).

    Side Effects:
      - Sends message over WebSocket to all connections
      - Logs broadcast event (info level)")

  (send-to-connection [this connection-id message]
    "Send message to specific WebSocket connection.
    
    Direct send to individual connection by ID. Useful for connection-specific
    messages like job progress updates (tied to connection, not user).
    
    Args:
      connection-id - Target connection UUID
      message - Message map (same structure as send-to-user)
    
    Returns:
      true if sent, false if connection not found (under :in-memory provider).
      Returns nil under the :redis provider (async fan-out; result not known
      synchronously).

    Side Effects:
      - Sends message over WebSocket to specific connection
      - Logs send event (debug level)")

  (publish-to-topic [this topic message]
    "Publish message to all connections subscribed to topic.
    
    Topic-based pub/sub messaging. Connections must subscribe to topic first
    via IPubSubManager. Used for:
      - Room-based messaging (e.g., chat rooms, game lobbies)
      - Entity-specific updates (e.g., 'order:123', 'user:456:notifications')
      - Dynamic routing without pre-defined roles
    
    Args:
      topic - Topic name string (e.g., 'order:123', 'chat:general')
      message - Message map with:
                :type - Message type keyword
                :payload - Message data
                :timestamp - Optional timestamp (auto-added if missing)
    
    Returns:
      Number of connections message was sent to (integer >= 0) under the
      :in-memory provider. Returns nil under the :redis provider (async
      fan-out; count not known synchronously).

    Side Effects:
      - Sends message over WebSocket to all topic subscribers
      - Logs publish event (debug level)
    
    Example:
      (publish-to-topic service \"order:123\"
        {:type \"order-updated\"
         :payload {:status \"shipped\"}})"))

;; =============================================================================
;; Connection Registry Ports
;; =============================================================================

(defprotocol IConnectionRegistry
  "Storage for active WebSocket connections.
  
  Implemented by in-memory registry (atom) or Redis-backed registry for
  multi-server scaling. Stores mapping of connection-id -> (connection, ws-adapter).
  
  Registry Structure:
    {connection-id {:connection <Connection record from core>
                    :ws-adapter <IWebSocketConnection adapter>}}"

  (register [this connection-id connection ws-connection]
    "Register new WebSocket connection in registry.
    
    Stores connection record and WebSocket adapter for later message routing.
    Connection record is pure data (from core layer), ws-adapter handles I/O.
    
    Args:
      connection-id - UUID for connection (from Connection record)
      connection - Connection record from core/connection.clj with:
                   :id - Connection UUID (same as connection-id)
                   :user-id - User UUID
                   :roles - Set of role keywords
                   :metadata - Optional metadata map
                   :created-at - Instant
      ws-connection - WebSocket adapter (IWebSocketConnection)
    
    Returns:
      nil
    
    Side Effects:
      - Adds entry to registry (atom or Redis)
      - May evict old connections if max-connections limit reached")

  (unregister [this connection-id]
    "Remove connection from registry.
    
    Called when WebSocket closes (client disconnect, timeout, error).
    Idempotent - safe to call multiple times.
    
    Args:
      connection-id - UUID of connection to remove
    
    Returns:
      nil
    
    Side Effects:
      - Removes entry from registry
      - Does NOT close WebSocket (caller's responsibility)")

  (find-by-user [this user-id]
    "Find all WebSocket adapters for a user.
    
    Returns WebSocket adapters (not Connection records) for immediate sending.
    User may have multiple active connections (tabs, devices).
    
    Args:
      user-id - User UUID
    
    Returns:
      Vector of IWebSocketConnection adapters (may be empty)")

  (find-by-role [this role]
    "Find all WebSocket adapters with a specific role.
    
    Returns adapters for connections where user has the specified role.
    Uses Connection record's :roles set for filtering.
    
    Args:
      role - Role keyword (e.g., :admin)
    
    Returns:
      Vector of IWebSocketConnection adapters (may be empty)")

  (all-connections [this]
    "Get all active WebSocket adapters.
    
    Used for broadcast messages. Returns adapters (not Connection records)
    for immediate sending.
    
    Returns:
      Vector of IWebSocketConnection adapters (may be empty)")

  (connection-count [this]
    "Get number of active connections.
    
    Used for metrics and monitoring.
    
    Returns:
      Integer count (>= 0)")

  (find-connection [this connection-id]
    "Find Connection record by ID.

    Returns the pure Connection record (not ws-adapter) for inspection.
    Used for debugging and monitoring.

    Args:
      connection-id - Connection UUID

    Returns:
      Connection record or nil if not found")

  (find-adapters-by-ids [this connection-ids]
    "Return a vector of IWebSocketConnection adapters for the given connection
     ids that are present in THIS node's registry. Missing ids are skipped.

     Args:
       connection-ids - seq of connection UUIDs

     Returns:
       Vector of IWebSocketConnection adapters (may be empty)"))

;; =============================================================================
;; WebSocket Connection Ports
;; =============================================================================

(defprotocol IWebSocketConnection
  "WebSocket connection adapter for sending messages.
  
  Wraps actual WebSocket implementation (Ring/Reitit/http-kit). Adapters
  translate generic message maps to WebSocket frames (JSON encoding).
  
  Implemented by:
    - RingWebSocketAdapter (Ring 2.0 WebSocket)
    - HttpKitWebSocketAdapter (http-kit WebSocket)
    - TestWebSocketAdapter (in-memory for testing)"

  (send-message [this message]
    "Send message over WebSocket connection.
    
    Encodes message map to JSON and sends as WebSocket text frame.
    Non-blocking - returns immediately, actual send is asynchronous.
    
    Args:
      message - Message map with:
                :type - Message type keyword
                :payload - Message data (must be JSON-serializable)
                :timestamp - Instant (auto-added if missing)
    
    Returns:
      nil
    
    Side Effects:
      - Sends JSON-encoded message over WebSocket
      - May fail silently if connection closed (logged by adapter)
    
    Throws:
      Exception if JSON encoding fails (invalid payload)")

  (close [this]
    "Close WebSocket connection.
    
    Graceful close - sends close frame to client and releases resources.
    Idempotent - safe to call multiple times.
    
    Returns:
      nil
    
    Side Effects:
      - Sends WebSocket close frame
      - Releases connection resources")

  (connection-id [this]
    "Get connection ID for this WebSocket.
    
    Returns the UUID that ties this adapter to its Connection record
    in the registry.
    
    Returns:
      Connection UUID")

  (open? [this]
    "Check if WebSocket connection is open.
    
    Used to avoid sending to closed connections.
    
    Returns:
      Boolean - true if connection is open and ready"))

;; =============================================================================
;; JWT Verification Port
;; =============================================================================

(defprotocol IJWTVerifier
  "JWT token verification.
  
  Delegates to boundary/user module for actual verification. This port
  exists to avoid direct dependency on user module from core layer.
  
  Implemented by:
    - UserJWTAdapter (delegates to wagoe.user.shell.jwt)
    - TestJWTAdapter (returns mock claims for testing)"

  (verify-jwt [this token]
    "Verify JWT token and extract claims.
    
    Validates token signature, expiry, and structure. Returns claims
    map for creating Connection record.
    
    Args:
      token - JWT token string
    
    Returns:
      Claims map with:
        :user-id - User UUID
        :email - User email
        :roles - Set of role keywords
        :permissions - Set of permission keywords
        :exp - Expiry timestamp (seconds since epoch)
        :iat - Issued-at timestamp
    
    Throws:
      ExceptionInfo with {:type :unauthorized :message ...} if:
        - Token expired
        - Invalid signature
        - Malformed token
        - Missing required claims"))

;; =============================================================================
;; Pub/Sub Port
;; =============================================================================

(defprotocol IPubSubManager
  "Pub/sub topic management for WebSocket connections.
  
   Enables topic-based message routing where connections can subscribe to
   arbitrary topics and receive messages published to those topics. Useful
   for:
     - Room-based messaging (e.g., chat rooms, game lobbies)
     - Entity-specific updates (e.g., 'order:123', 'user:456:notifications')
     - Dynamic routing without pre-defined roles
   
   Single-server implementation using in-memory atom. Multi-server support
   would require Redis pub/sub (deferred to v0.2.0).
   
   Implemented by:
     - AtomPubSubManager (in-memory subscriptions using atom)
     - TestPubSubManager (mock for testing)"

  (subscribe-to-topic [this connection-id topic]
    "Subscribe connection to topic.
    
     Adds connection to topic's subscriber set. Connection will receive all
     messages published to this topic until unsubscribed or disconnected.
     
     Same connection can subscribe to multiple topics. Subscribing twice to
     same topic is idempotent (no duplicate subscriptions).
     
     Args:
       connection-id - UUID of WebSocket connection
       topic - Topic name string (e.g., 'order:123', 'chat:general')
     
     Returns:
       nil
     
     Side Effects:
       - Updates subscriptions atom
       - Logs subscription event
     
     Example:
       (subscribe-to-topic mgr #uuid \"123...\" \"order:456\")
       ; Connection 123 now receives messages published to 'order:456'")

  (unsubscribe-from-topic [this connection-id topic]
    "Unsubscribe connection from topic.
    
     Removes connection from topic's subscriber set. Connection will no
     longer receive messages published to this topic.
     
     Idempotent - safe to call even if connection not subscribed.
     
     Args:
       connection-id - UUID of WebSocket connection
       topic - Topic name string
     
     Returns:
       nil
     
     Side Effects:
       - Updates subscriptions atom
       - Logs unsubscription event")

  (unsubscribe-from-all-topics [this connection-id]
    "Unsubscribe connection from all topics.
    
     Removes connection from all topic subscriptions. Called automatically
     when connection disconnects to clean up resources.
     
     Args:
       connection-id - UUID of WebSocket connection
     
     Returns:
       nil
     
     Side Effects:
       - Updates subscriptions atom (removes from all topics)
       - Logs cleanup event")

  (get-topic-subscribers [this topic]
    "Get all connection IDs subscribed to topic.
    
     Used by publish-to-topic to determine which connections should receive
     the message.
     
     Args:
       topic - Topic name string
     
     Returns:
       Set of connection UUIDs (empty set if topic has no subscribers)
     
     Example:
       (get-topic-subscribers mgr \"order:123\")
       => #{#uuid \"111...\" #uuid \"222...\"}")

  (get-connection-subscriptions [this connection-id]
    "Get all topics connection is subscribed to.
    
     Useful for debugging and admin interfaces to see what topics a
     connection is listening to.
     
     Args:
       connection-id - UUID of WebSocket connection
     
     Returns:
       Set of topic name strings
     
     Example:
       (get-connection-subscriptions mgr #uuid \"123...\")
       => #{\"order:456\" \"user:123:notifications\"}")

  (topic-count [this]
    "Count number of active topics.
    
     Topics with zero subscribers are automatically cleaned up, so this
     returns count of topics with at least one subscriber.
     
     Returns:
       Integer count of active topics")

  (subscription-count [this]
    "Count total number of subscriptions.

     Note: Same connection can be subscribed to multiple topics, so this
     may be greater than connection count.

     Returns:
       Integer count of total subscriptions")

  ;; ---------------------------------------------------------------------------
  ;; Server-side subscribers
  ;;
  ;; Every subscriber above is a WebSocket connection, so an application that
  ;; wants an event to reach in-process code had to run a second pub/sub — the
  ;; notification-service example carried its own core.async bus for exactly
  ;; that, and used realtime only to push to browsers (BOU-233).
  ;;
  ;; A service subscription is node-local: the handler is a function in this
  ;; JVM, so it cannot be relayed. Registering it here means the node-local
  ;; delivery-fn invokes it, which is the same path a socket takes — so under
  ;; the :redis provider a handler registered on one node fires once, on that
  ;; node, for a message published from any node.
  ;; ---------------------------------------------------------------------------

  (subscribe-service [this topic handler-fn]
    "Subscribe an in-process handler to a topic.

     `handler-fn` is called with the message map for every message published
     to `topic`, on the node where it was registered. It runs on the delivery
     thread, so it should be quick — hand slow work to jobs.

     A handler that throws is logged and skipped; it cannot stop delivery to
     other handlers or to sockets.

     Args:
       topic       - Topic name string, same namespace as connection subscribers
       handler-fn  - (fn [message] ...); the return value is ignored

     Returns:
       A subscription id (UUID) for unsubscribe-service

     Example:
       (subscribe-service mgr \"order:events\"
         (fn [msg] (create-notification! (:payload msg))))")

  (unsubscribe-service [this subscription-id]
    "Remove a service subscription.

     Idempotent — an unknown id is not an error.

     Args:
       subscription-id - id returned by subscribe-service

     Returns:
       true if a subscription was removed, false if the id was unknown")

  (get-topic-service-handlers [this topic]
    "Handler functions subscribed to `topic` on this node.

     Used by the delivery-fn; applications publish rather than call this.

     Returns:
       Vector of functions (may be empty)")

  (service-subscription-count [this]
    "Number of service subscriptions on this node.

     Returns:
       Integer count (>= 0)"))

;; =============================================================================
;; Message Bus Port (cross-instance routing transport)
;; =============================================================================

(defprotocol IMessageBus
  "Cross-instance routing transport for realtime messages.

   Publishes routing envelopes to all nodes; each node delivers to its local
   sockets via a registered delivery-fn. In-memory = synchronous loopback;
   Redis = asynchronous PUB/SUB fan-out. Delivery happens exclusively through
   the registered delivery-fn (never inline on the origin), so each connection
   — living on exactly one node — is delivered to exactly once.

   Envelope shape (pure data):
     {:route   :user | :role | :broadcast | :connection | :topic
      :target  <user-uuid | role-kw | conn-uuid | topic-str | nil>
      :message {:type ... :payload ... :timestamp <Instant>}}"

  (publish [this envelope]
    "Publish a routing envelope to all nodes.

     Returns the local recipient count (in-memory, synchronous) or nil
     (redis, asynchronous fire-and-forget).")

  (start-subscriber! [this delivery-fn]
    "Register the node-local delivery-fn and begin receiving envelopes.
     delivery-fn is (fn [envelope] -> int) performing local delivery and
     returning the number of local sockets it sent to. Blocks until the
     subscription is live. Idempotent: a second call is a no-op.")

  (stop-subscriber! [this]
    "Stop receiving envelopes and release resources. Idempotent."))
