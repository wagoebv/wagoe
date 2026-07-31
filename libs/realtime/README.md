# Realtime Module

[![Status](https://img.shields.io/badge/status-in%20development-yellow)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-realtime.svg)](https://clojars.org/com.wagoe/wagoe-realtime)

**WebSocket-based real-time communication for Wagoe Framework**

JWT-authenticated WebSocket support with:

- ✅ JWT-based authentication
- ✅ Point-to-point messaging (send to specific user)
- ✅ Broadcast messaging (send to all connections)
- ✅ Role-based routing (send to users with specific role)
- ✅ Connection-specific messaging (for job progress tracking)
- ✅ Topic-based pub/sub (subscribe to arbitrary topics)
- ✅ Pure functional core (FC/IS pattern)
- ✅ Pluggable adapters (test and production)
- ✅ Integration with wagoe/user authentication

---

## Table of Contents

- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
- [Usage Examples](#usage-examples)
- [Client-Side Integration](#client-side-integration)
- [Configuration](#configuration)
- [Message Format](#message-format)
- [Production Deployment](#production-deployment)
- [API Reference](#api-reference)
- [Architecture](#architecture)
- [Limitations](#limitations)

---

## Quick Start

### 1. Add Dependency

```clojure
;; deps.edn
{:deps {com.wagoe/wagoe-realtime {:mvn/version "1.0.0-beta-1"}
        com.wagoe/wagoe-user {:mvn/version "1.0.0-beta-1"}}}
```

### 2. Create Realtime Service

```clojure
(require '[wagoe.realtime.shell.service :as realtime-service]
         '[wagoe.realtime.shell.connection-registry :as registry]
         '[wagoe.realtime.shell.adapters.jwt-adapter :as jwt]
         '[wagoe.realtime.ports :as ports])

;; Create connection registry
(def connection-registry (registry/create-in-memory-registry))

;; Create JWT verifier (integrates with wagoe/user)
(def jwt-verifier (jwt/create-user-jwt-adapter))

;; Create realtime service
(def realtime-svc (realtime-service/create-realtime-service
                    connection-registry
                    jwt-verifier))
```

### 3. Add WebSocket Endpoint

```clojure
(require '[wagoe.realtime.shell.adapters.websocket-adapter :as ws])

(defn websocket-handler
  "WebSocket upgrade handler for Ring."
  [request]
  (if-let [ws-channel (:websocket request)]  ; Ring 2.0 WebSocket
    (let [query-params (:query-params request)
          connection-id (java.util.UUID/randomUUID)
          ws-adapter (ws/create-ring-websocket-adapter connection-id ws-channel)]
      
      ;; Connect with JWT authentication
      (try
        (ports/connect realtime-svc ws-adapter query-params)
        
        ;; Handle disconnection
        ((:on-close ws-channel)
         (fn []
           (ports/disconnect realtime-svc connection-id)))
        
        {:status 101}  ; WebSocket upgrade
        
        (catch Exception e
          {:status 401 :body {:error "Unauthorized"}})))
    
    {:status 400 :body {:error "Not a WebSocket request"}}))

;; Add to routes
(def routes
  [["/ws" {:get {:handler websocket-handler}}]])
```

### 4. Send Messages

```clojure
;; Send to specific user (all their connections)
(ports/send-to-user realtime-svc user-id
  {:type "notification"
   :content "New message received"})

;; Broadcast to all connections
(ports/broadcast realtime-svc
  {:type "system-announcement"
   :content "Server maintenance in 10 minutes"})

;; Send to users with specific role
(ports/send-to-role realtime-svc :admin
  {:type "admin-alert"
   :content "High CPU usage detected"})

;; Send to specific connection (job progress)
(ports/send-to-connection realtime-svc connection-id
  {:type "job-progress"
   :progress 75
   :status "Processing..."})
```

### 5. Client-Side Connection

```javascript
// Get JWT token from login
const token = localStorage.getItem('jwt-token');

// Connect to WebSocket with JWT in query params
const ws = new WebSocket(`ws://localhost:3000/ws?token=${token}`);

ws.onopen = () => {
  console.log('Connected to realtime server');
};

ws.onmessage = (event) => {
  const message = JSON.parse(event.data);
  console.log('Received:', message);
  
  // Handle different message types
  switch (message.type) {
    case 'notification':
      showNotification(message.content);
      break;
    case 'job-progress':
      updateProgressBar(message.progress);
      break;
    default:
      console.log('Unknown message type:', message.type);
  }
};

ws.onerror = (error) => {
  console.error('WebSocket error:', error);
};

ws.onclose = () => {
  console.log('Disconnected from realtime server');
};
```

---

## Core Concepts

### Primary Use Cases

1. **Live Notifications**: Real-time alerts when events occur (new orders, messages, etc.)
2. **Dashboard Updates**: Live metrics and status updates without page refresh
3. **Job Progress**: Real-time progress updates for long-running operations
4. **Admin Broadcasts**: System announcements to all connected users

### Message Routing Strategies

| Strategy | Use Case | Example |
|----------|----------|---------|
| **send-to-user** | Notify specific user across all their devices | "You have a new message" |
| **send-to-role** | Admin-only alerts, moderator notifications | "New report requires review" |
| **broadcast** | System-wide announcements | "Server maintenance starting" |
| **send-to-connection** | Job-specific progress tracking | Upload progress for specific tab |
| **publish-to-topic** | Topic-based pub/sub messaging | Chat rooms, entity-specific updates |

### Topic-Based Pub/Sub

**NEW in v1.0.1-alpha-26**: Connections can subscribe to arbitrary topics and receive messages published to those topics.

**Use Cases:**
- **Chat rooms / Game lobbies**: `topic = "chat:general"`, `"lobby:game-123"`
- **Entity-specific updates**: `topic = "order:456"`, `"user:789:notifications"`
- **Dynamic routing**: No need to pre-define topics - create them on-the-fly

**Server-side setup:**

```clojure
(require '[wagoe.realtime.shell.pubsub-manager :as pubsub-mgr])

;; Create pub/sub manager
(def pubsub-manager (pubsub-mgr/create-pubsub-manager))

;; Create service with pub/sub support
(def realtime-svc (realtime-service/create-realtime-service
                    connection-registry
                    jwt-verifier
                    :pubsub-manager pubsub-manager))

;; Subscribe connection to topic (after connection established)
(ports/subscribe-to-topic pubsub-manager connection-id "order:123")

;; Publish message to all subscribers
(ports/publish-to-topic realtime-svc "order:123"
  {:type "order-updated"
   :status "shipped"
   :tracking-number "ABC123"})
;; => Returns count of connections that received the message
```

**Client-side subscription** (JavaScript):

```javascript
// After WebSocket connection established
ws.send(JSON.stringify({
  action: "subscribe",
  topic: "order:123"
}));

// Listen for topic messages
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.type === 'order-updated') {
    updateOrderUI(msg);
  }
};

// Unsubscribe when done
ws.send(JSON.stringify({
  action: "unsubscribe",
  topic: "order:123"
}));
```

**Note**: Client-side subscribe/unsubscribe commands require custom message handlers on the server (not included - implement as needed for your application).

**Automatic cleanup**: When a connection disconnects, it's automatically unsubscribed from all topics.

### Connection Lifecycle

```
1. Client requests JWT → 2. HTTP login → 3. Receive JWT token
                                              ↓
4. WebSocket connect ← 5. JWT validation ← 6. Extract token from query params
        ↓
7. Connection registered → 8. Receive messages → 9. Process events
        ↓
10. Disconnect (close/error) → 11. Connection removed from registry
```

---

## Usage Examples

### Example 1: Live Order Notifications

```clojure
(defn notify-new-order
  "Send real-time notification when new order is created."
  [realtime-svc order]
  (let [user-id (:user-id order)]
    (ports/send-to-user realtime-svc user-id
      {:type "order-created"
       :order-id (:id order)
       :total (:total order)
       :status (:status order)
       :message (str "Order #" (:id order) " created successfully")})))

;; Use in order creation handler
(defn create-order-handler
  [request]
  (let [order-data (:body request)
        new-order (order-service/create-order order-data)]
    
    ;; Send real-time notification
    (notify-new-order realtime-svc new-order)
    
    {:status 201 :body new-order}))
```

**Client-side:**
```javascript
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.type === 'order-created') {
    showToast(`Order #${msg['order-id']} created! Total: $${msg.total}`);
    refreshOrderList();
  }
};
```

### Example 2: Admin Dashboard Metrics

```clojure
(defn broadcast-system-metrics
  "Periodically broadcast system metrics to admins."
  [realtime-svc]
  (future
    (loop []
      (Thread/sleep 5000)  ; Every 5 seconds
      
      (let [metrics {:active-users (count-active-users)
                     :orders-today (count-todays-orders)
                     :cpu-usage (get-cpu-usage)
                     :memory-usage (get-memory-usage)}]
        
        (ports/send-to-role realtime-svc :admin
          {:type "metrics-update"
           :data metrics
           :timestamp (java.time.Instant/now)}))
      
      (recur))))

;; Start metrics broadcaster
(broadcast-system-metrics realtime-svc)
```

**Client-side (Admin Dashboard):**
```javascript
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.type === 'metrics-update') {
    document.getElementById('active-users').textContent = msg.data['active-users'];
    document.getElementById('orders-today').textContent = msg.data['orders-today'];
    updateCPUGauge(msg.data['cpu-usage']);
    updateMemoryGauge(msg.data['memory-usage']);
  }
};
```

### Example 3: File Upload Progress

```clojure
(defn handle-file-upload
  "Handle file upload with progress updates via WebSocket."
  [request connection-id]
  (let [file (:file request)
        total-size (.length file)]
    
    ;; Send initial progress
    (ports/send-to-connection realtime-svc connection-id
      {:type "upload-progress"
       :progress 0
       :status "Starting upload..."})
    
    ;; Simulate chunked upload with progress
    (doseq [chunk-idx (range 10)]
      (Thread/sleep 200)  ; Simulate processing time
      
      (ports/send-to-connection realtime-svc connection-id
        {:type "upload-progress"
         :progress (* (inc chunk-idx) 10)
         :status (str "Uploading... " (* (inc chunk-idx) 10) "%")}))
    
    ;; Send completion
    (ports/send-to-connection realtime-svc connection-id
      {:type "upload-complete"
       :progress 100
       :file-id (java.util.UUID/randomUUID)
       :message "Upload complete!"})))
```

**Client-side:**
```javascript
// Store connection ID for progress tracking
let connectionId = null;

ws.onopen = () => {
  // Extract connection ID from server response if needed
  console.log('Connected');
};

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  
  if (msg.type === 'upload-progress') {
    updateProgressBar(msg.progress);
    document.getElementById('status').textContent = msg.status;
  }
  
  if (msg.type === 'upload-complete') {
    showSuccess(msg.message);
    console.log('File ID:', msg['file-id']);
  }
};
```

### Example 4: Multi-Device Notifications

```clojure
(defn send-notification-to-all-devices
  "Send notification to all of user's connected devices."
  [realtime-svc user-id notification-text]
  (let [sent-count (ports/send-to-user realtime-svc user-id
                     {:type "notification"
                      :title "New Message"
                      :body notification-text
                      :icon "/icons/message.png"
                      :timestamp (java.time.Instant/now)})]
    
    (log/info "Notification sent to" sent-count "devices for user" user-id)))

;; Use case: User receives message
(defn on-new-message-received
  [message]
  (let [recipient-id (:recipient-id message)]
    (send-notification-to-all-devices
      realtime-svc
      recipient-id
      (str "New message from " (:sender-name message)))))
```

**Client-side (works on desktop, mobile, tablet simultaneously):**
```javascript
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.type === 'notification') {
    // Desktop notification
    if ('Notification' in window) {
      new Notification(msg.title, {
        body: msg.body,
        icon: msg.icon
      });
    }
    
    // Update UI badge
    updateNotificationBadge();
  }
};
```

---

## Client-Side Integration

### JavaScript/TypeScript

**Basic Client:**
```javascript
class RealtimeClient {
  constructor(baseUrl, token) {
    this.baseUrl = baseUrl;
    this.token = token;
    this.ws = null;
    this.handlers = {};
  }
  
  connect() {
    const wsUrl = `${this.baseUrl}/ws?token=${this.token}`;
    this.ws = new WebSocket(wsUrl);
    
    this.ws.onopen = () => {
      console.log('Connected to realtime server');
      this.emit('connected');
    };
    
    this.ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      this.handleMessage(message);
    };
    
    this.ws.onerror = (error) => {
      console.error('WebSocket error:', error);
      this.emit('error', error);
    };
    
    this.ws.onclose = () => {
      console.log('Disconnected');
      this.emit('disconnected');
    };
  }
  
  on(messageType, handler) {
    if (!this.handlers[messageType]) {
      this.handlers[messageType] = [];
    }
    this.handlers[messageType].push(handler);
  }
  
  handleMessage(message) {
    const handlers = this.handlers[message.type] || [];
    handlers.forEach(handler => handler(message));
  }
  
  emit(event, data) {
    const handlers = this.handlers[event] || [];
    handlers.forEach(handler => handler(data));
  }
  
  disconnect() {
    if (this.ws) {
      this.ws.close();
    }
  }
}

// Usage
const client = new RealtimeClient('ws://localhost:3000', jwtToken);

client.on('notification', (msg) => {
  console.log('Notification:', msg.content);
});

client.on('job-progress', (msg) => {
  console.log('Progress:', msg.progress);
});

client.connect();
```

### React Integration

```javascript
import { useEffect, useState } from 'react';

function useRealtime(token) {
  const [client, setClient] = useState(null);
  const [connected, setConnected] = useState(false);
  
  useEffect(() => {
    const rtClient = new RealtimeClient('ws://localhost:3000', token);
    
    rtClient.on('connected', () => setConnected(true));
    rtClient.on('disconnected', () => setConnected(false));
    
    rtClient.connect();
    setClient(rtClient);
    
    return () => rtClient.disconnect();
  }, [token]);
  
  return { client, connected };
}

// Use in component
function Dashboard() {
  const { client, connected } = useRealtime(authToken);
  const [metrics, setMetrics] = useState({});
  
  useEffect(() => {
    if (client) {
      client.on('metrics-update', (msg) => {
        setMetrics(msg.data);
      });
    }
  }, [client]);
  
  return (
    <div>
      <StatusBadge connected={connected} />
      <MetricsDashboard data={metrics} />
    </div>
  );
}
```

### Python Client

```python
import websocket
import json
import threading

class RealtimeClient:
    def __init__(self, base_url, token):
        self.base_url = base_url
        self.token = token
        self.ws = None
        self.handlers = {}
        
    def connect(self):
        ws_url = f"{self.base_url}/ws?token={self.token}"
        self.ws = websocket.WebSocketApp(
            ws_url,
            on_message=self._on_message,
            on_error=self._on_error,
            on_close=self._on_close,
            on_open=self._on_open
        )
        
        thread = threading.Thread(target=self.ws.run_forever)
        thread.daemon = True
        thread.start()
    
    def _on_message(self, ws, message):
        msg = json.loads(message)
        msg_type = msg.get('type')
        
        if msg_type in self.handlers:
            for handler in self.handlers[msg_type]:
                handler(msg)
    
    def on(self, message_type, handler):
        if message_type not in self.handlers:
            self.handlers[message_type] = []
        self.handlers[message_type].append(handler)
    
    def _on_open(self, ws):
        print("Connected to realtime server")
    
    def _on_error(self, ws, error):
        print(f"Error: {error}")
    
    def _on_close(self, ws, close_status_code, close_msg):
        print("Disconnected")

# Usage
client = RealtimeClient('ws://localhost:3000', jwt_token)

client.on('notification', lambda msg: print(f"Notification: {msg['content']}"))
client.on('job-progress', lambda msg: print(f"Progress: {msg['progress']}%"))

client.connect()
```

---

## Configuration

### Integrant Configuration

```clojure
;; resources/conf/config.edn
{:wagoe/realtime-service
 {:connection-registry #ig/ref :wagoe/connection-registry
  :jwt-verifier #ig/ref :wagoe/jwt-verifier
  :logger #ig/ref :wagoe/logger
  :error-reporter #ig/ref :wagoe/error-reporter}
 
 :wagoe/connection-registry {}
 
 :wagoe/jwt-verifier {}}
```

### Environment Variables

```bash
# No environment variables needed for basic setup
# JWT secret is managed by wagoe/user module
```

---

## Message Format

### Standard Message Structure

All messages sent via WebSocket follow this JSON structure:

```json
{
  "type": "message-type",
  "timestamp": "2025-01-01T12:00:00Z",
  "...additional fields..."
}
```

**Fields:**
- `type` (required): String identifying the message type
- `timestamp` (auto-added): ISO 8601 timestamp when message was sent
- Additional fields vary by message type

### Common Message Types

**Notification:**
```json
{
  "type": "notification",
  "content": "You have a new message",
  "severity": "info",
  "timestamp": "2025-01-01T12:00:00Z"
}
```

**Job Progress:**
```json
{
  "type": "job-progress",
  "job-id": "550e8400-e29b-41d4-a716-446655440000",
  "progress": 75,
  "status": "Processing...",
  "timestamp": "2025-01-01T12:00:00Z"
}
```

**Metrics Update:**
```json
{
  "type": "metrics-update",
  "data": {
    "active-users": 142,
    "orders-today": 37,
    "cpu-usage": 45.2
  },
  "timestamp": "2025-01-01T12:00:00Z"
}
```

---

## Production Deployment

### Health Checks

```clojure
(defn realtime-health-check
  "Health check endpoint for realtime service."
  [realtime-svc connection-registry]
  (fn [_request]
    (let [connection-count (ports/connection-count connection-registry)]
      {:status 200
       :body {:status "healthy"
              :active-connections connection-count
              :service "realtime"}})))

;; Add to routes
["/health/realtime" {:get {:handler (realtime-health-check realtime-svc registry)}}]
```

### Monitoring

```clojure
(defn log-connection-metrics
  "Periodically log connection metrics."
  [connection-registry]
  (future
    (loop []
      (Thread/sleep 60000)  ; Every minute
      
      (let [count (ports/connection-count connection-registry)]
        (log/info "Active WebSocket connections:" count))
      
      (recur))))
```

### Graceful Shutdown

```clojure
(defmethod ig/halt-key! :wagoe/realtime-service
  [_ {:keys [connection-registry]}]
  ;; Close all active connections gracefully
  (let [connections (ports/all-connections connection-registry)]
    (doseq [ws-adapter connections]
      (try
        (ports/send-message ws-adapter
          {:type "server-shutdown"
           :message "Server shutting down gracefully"})
        (Thread/sleep 100)  ; Give time for message to send
        (ports/close ws-adapter)
        (catch Exception e
          (log/warn e "Error closing connection during shutdown"))))))
```

### Load Balancing

**Current limitation:** Single-server deployment only.

For multi-server deployments (future feature), you'll need:
- Redis pub/sub for cross-server messaging
- Sticky sessions (route same user to same server)
- Or implement connection registry in Redis

---

## API Reference

### Ports (Protocols)

**IRealtimeService** - Main service interface

- `(connect service ws-connection query-params)` - Establish WebSocket connection
  - Returns: connection-id (UUID)
  - Throws: ExceptionInfo with :type :unauthorized if JWT invalid

- `(disconnect service connection-id)` - Close connection
  - Returns: nil

- `(send-to-user service user-id message)` - Send to all user's connections
  - Returns: count of connections message was sent to

- `(send-to-role service role message)` - Send to all connections with role
  - Returns: count of connections message was sent to

- `(broadcast service message)` - Send to all connections
  - Returns: count of connections message was sent to

- `(send-to-connection service connection-id message)` - Send to specific connection
  - Returns: true if sent, false if connection not found

**IConnectionRegistry** - Connection storage

- `(register registry connection-id connection ws-adapter)` - Register new connection
- `(unregister registry connection-id)` - Remove connection
- `(find-by-user registry user-id)` - Get all connections for user
- `(find-by-role registry role)` - Get all connections with role
- `(all-connections registry)` - Get all connections
- `(connection-count registry)` - Count active connections
- `(find-connection registry connection-id)` - Get specific connection

**IWebSocketConnection** - WebSocket adapter

- `(send-message adapter message)` - Send message over WebSocket
- `(close adapter)` - Close WebSocket connection
- `(connection-id adapter)` - Get connection ID
- `(open? adapter)` - Check if connection is open

**IJWTVerifier** - JWT verification

- `(verify-jwt verifier token)` - Verify JWT token
  - Returns: claims map with :user-id, :email, :roles, :permissions, :exp, :iat
  - Throws: ExceptionInfo with :type :unauthorized if invalid

---

## Architecture

### Module Structure

```
libs/realtime/
├── src/wagoe/realtime/
│   ├── core/                          # Pure business logic
│   │   ├── connection.clj             # Connection state management (pure)
│   │   ├── message.clj                # Message routing logic (pure)
│   │   ├── pubsub.clj                 # Topic subscription logic (pure)
│   │   └── auth.clj                   # JWT validation logic (pure)
│   ├── ports.clj                      # Protocol definitions
│   ├── schema.clj                     # Malli schemas
│   └── shell/                         # I/O adapters
│       ├── service.clj                # Shell orchestration
│       ├── connection_registry.clj    # Connection storage (atom)
│       ├── pubsub_manager.clj         # Topic subscription storage (atom)
│       └── adapters/
│           ├── websocket_adapter.clj  # WebSocket I/O
│           └── jwt_adapter.clj        # JWT verification
└── test/                              # Tests (134 tests, 409 assertions)
```

### Functional Core / Imperative Shell

**Core Responsibilities (Pure):**
- Connection state management (create, update, filter)
- Message routing logic (determine recipients)
- Topic subscription management (subscribe, unsubscribe, query)
- JWT token validation decisions
- No I/O, no side effects, fully testable

**Shell Responsibilities (I/O):**
- WebSocket send/receive operations
- Connection registry (stateful atom)
- Pub/sub manager (stateful atom)
- JWT verification (delegates to user module)
- Logging, error handling, time dependencies

### Data Flow

```
Client WebSocket Request
    ↓
[Shell] WebSocket Handler (extract token from query params)
    ↓
[Shell] JWT Adapter → [User Module] Validate JWT
    ↓
[Core] Create Connection Record (pure function)
    ↓
[Shell] Register in Connection Registry (atom swap)
    ↓
Connection Established
    ↓
Server Sends Message
    ↓
[Core] Route Message (determine recipients - pure function)
    ↓
[Shell] Send via WebSocket Adapters (I/O)
    ↓
Client Receives Message
```

---

## Limitations

### Current Limitations (v1.0.1-alpha-26)

**Single-Server Deployment:**
- ❌ **No multi-server support**: Connections tied to single server instance
- ❌ **No Redis pub/sub**: Cross-server messaging not implemented
- **Workaround**: Use sticky sessions or limit to single server
- **Future**: Multi-server support with Redis planned for v0.2.0

**No Presence Tracking:**
- ❌ **No "who's online" features**: Cannot query list of active users
- ❌ **No user activity status**: No typing indicators, last seen, etc.
- **Workaround**: Implement at application level if needed
- **Future**: Presence API planned for v0.3.0

**Single-Server Pub/Sub:**
- ✅ **Topic-based routing available**: Connections can subscribe to arbitrary topics
- ❌ **No multi-server pub/sub**: Topics are per-server instance only
- **Workaround**: Use sticky sessions or limit to single server
- **Future**: Redis-backed pub/sub for multi-server planned for v0.2.0

**Authentication:**
- ⚠️ **JWT only**: No support for session cookies or API keys
- **Workaround**: Issue short-lived JWT for WebSocket connections
- **Future**: Alternative auth methods considered for v0.4.0

### Known Limitations

**Connection Limits:**
- In-memory registry scales to ~10,000 concurrent connections per server
- Beyond that, consider implementing Redis-backed registry

**Message Size:**
- No enforced limit on message size
- Large messages (> 1MB) may cause performance issues
- Recommendation: Keep messages under 100KB

**Reconnection:**
- No automatic reconnection on client side (use library like reconnecting-websocket)
- No message buffering during disconnection

---

## Testing

### Unit Tests

Run core layer tests (pure functions):

```bash
cd libs/realtime
clojure -M:test --focus-meta :unit
```

**Coverage:**
- 58 unit tests (48 original + 10 pub/sub)
- Tests for connection management, message routing, JWT validation, pub/sub
- All tests pure (no mocks needed)

### Integration Tests

Run shell layer tests (I/O operations):

```bash
cd libs/realtime
clojure -M:test --focus-meta :integration
```

**Coverage:**
- 76 integration tests (56 original + 20 pub/sub)
- Tests for service orchestration, registry behavior, adapters, pub/sub manager
- Uses test adapters (no real WebSockets or JWT verification)

### All Tests

```bash
cd libs/realtime
clojure -M:test
```

**Result**: 134 tests, 409 assertions, 0 failures

---

## Troubleshooting

### Connection Fails with 401 Unauthorized

**Cause**: Invalid or missing JWT token

**Solutions:**
1. Check JWT token is included in query params: `?token=<jwt>`
2. Verify JWT is valid and not expired
3. Ensure wagoe/user module is properly configured
4. Check JWT secret matches between user module and realtime module

### Messages Not Being Received

**Cause**: Connection not registered or closed

**Solutions:**
1. Check connection is established: `(ports/connection-count registry)`
2. Verify WebSocket is open on client side
3. Check browser console for WebSocket errors
4. Verify routing logic (user-id, role, connection-id correct)

### High Memory Usage

**Cause**: Too many connections or large messages

**Solutions:**
1. Monitor connection count: `(ports/connection-count registry)`
2. Implement connection limits
3. Reduce message size (paginate large datasets)
4. Use connection-specific messaging for job progress (not broadcast)

---

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `malli` | 0.20.0 | Schema validation |
| `cheshire` | 6.1.0 | JSON encoding/decoding |
| `tools.logging` | 1.3.1 | Logging |
| `wagoe/user` | 1.0.0-beta-1 | JWT authentication (optional) |

---

## License

Part of Wagoe Framework

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.

---

## Next Steps

**Want to contribute?**
- See [CONTRIBUTING.md](../../CONTRIBUTING.md) for development guidelines
- Check [ADR-003](../../docs/adr/ADR-003-websocket-architecture.md) for architecture decisions
- Open issues on GitHub for feature requests

**Need help?**
- GitHub Issues: https://github.com/wagoebv/wagoe/issues
- Documentation: See [docs-site/](../../docs-site/) in the repository

**Coming in v0.2.0:**
- Multi-server support with Redis
- Presence tracking API
- Pub/sub channels
- Message persistence

---

**Last Updated**: 2026-02-04  
**Version**: 1.0.0-beta-1  
**Status**: Production Ready (Single-Server)
