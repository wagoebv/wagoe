# Realtime Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

WebSocket-based real-time communication with JWT authentication, message routing, and topic-based pub/sub. Supports point-to-point, role-based, broadcast, and connection-specific messaging.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.realtime.core.connection` | Pure: connection records, authorization, filtering |
| `wagoe.realtime.core.message` | Pure: message creation, routing logic (4 types) |
| `wagoe.realtime.core.auth` | Pure: JWT extraction, claims validation, permission checks |
| `wagoe.realtime.core.pubsub` | Pure: topic subscription management |
| `wagoe.realtime.ports` | Protocols: IRealtimeService, IConnectionRegistry, IWebSocketConnection, IJWTVerifier, IPubSubManager |
| `wagoe.realtime.schema` | Malli schemas: Connection, Message, JWT Claims, Topic |
| `wagoe.realtime.shell.service` | Service orchestrating core + adapters |
| `wagoe.realtime.shell.connection-registry` | In-memory registry (atom-backed) |
| `wagoe.realtime.shell.pubsub-manager` | Atom-backed pub/sub state management |
| `wagoe.realtime.shell.adapters.websocket-adapter` | Ring/Jetty WebSocket adapter + TestWebSocketAdapter |
| `wagoe.realtime.shell.adapters.jwt-adapter` | JWT verifier delegating to boundary/user module + TestJWTAdapter |

## Message Routing Types

| Type | Target | Example |
|------|--------|---------|
| `:broadcast` | All connections | System announcement |
| `:user` | Specific user-id | Direct message |
| `:role` | Users with specific role | Admin notification |
| `:connection` | Specific connection-id | Job progress update |

## Connection Lifecycle

1. Client connects with JWT: `ws://host/ws?token=<jwt>`
2. Server verifies JWT via `IJWTVerifier` adapter
3. Connection record created, registered in connection registry
4. `:on-open` callback invoked (if provided) with the `connection-id`
5. Client can send/receive messages
6. On disconnect: cleanup registry + unsubscribe from all pub/sub topics

## WebSocket Handler Options

The `websocket-handler` accepts keyword options:

```clojure
(require '[wagoe.realtime.shell.handlers.ring-websocket :as ws-handler])

(ws-handler/websocket-handler realtime-service
  :token-param "token"        ;; query param name for JWT (default "token")
  :on-message  (fn [ws msg]   ;; client→server message handler
                 (handle-incoming ws msg))
  :on-open     (fn [conn-id]  ;; called after successful connect + JWT auth
                 (subscribe-to-user-topics! pubsub conn-id)))
```

The `:on-open` callback runs after a successful `realtime-ports/connect`. Use it to subscribe the new connection to topics based on the authenticated user's roles. Exceptions are logged and swallowed — they do not abort the connection.

## Topic-Based Pub/Sub

```clojure
;; Subscribe connection to topic
(ports/subscribe-to-topic pubsub-mgr conn-id "order:123")

;; Publish to all subscribers of a topic
(ports/publish-to-topic service "order:123" {:type :order-updated :payload {...}})

;; Unsubscribe (automatic on disconnect)
(ports/unsubscribe-from-topic pubsub-mgr conn-id "order:123")
```

## Scaling & providers

`:wagoe/realtime` accepts a `:provider` key that selects the pub/sub backend:

| Provider | Default | Replica-safe |
|----------|---------|--------------|
| `:in-memory` | yes | No — single process only; use sticky sessions |
| `:redis` | no | Yes — scales across replicas via Redis pub/sub |

### Relay model (`:redis` provider)

Every replica keeps its own in-memory connection registry (live WebSocket sockets are always node-local). When a message is sent, the node publishes a routing envelope onto a shared Redis pub/sub channel. Every replica subscribes to that channel and delivers the message to any matching local sockets. Topic subscriptions are stored in Redis sets, making them visible cluster-wide so `publish-to-topic` fans out correctly regardless of which replica holds a given socket.

### Redis config keys

```clojure
:wagoe/realtime {:provider    :redis
                    :host        "localhost"   ; Redis host
                    :port        6379          ; Redis port
                    :password    "..."         ; AUTH password (production; optional)
                    :database    0             ; Redis db index (optional)
                    :timeout     2000          ; socket timeout ms (optional)
                    :max-total   8             ; publish-pool sizing (optional)
                    :max-idle    8             ; (optional)
                    :min-idle    0             ; (optional)
                    :channel     "boundary:realtime:bus" ; pub/sub channel (default if omitted)
                    :key-prefix  "realtime"    ; prefix for Redis set keys (default if omitted)
                    :subscribe-timeout-ms 5000 ; await window for the subscription to go live;
                                               ; if Redis is down at startup, init does NOT fail —
                                               ; the subscriber retries in the background and the
                                               ; node becomes live once Redis is reachable (default 5000)
                    :jwt-verifier <ref>}       ; IJWTVerifier component ref
```

Under `:redis` the component opens **two** Jedis pools — one for topic
subscriptions (pub/sub manager) and one inside the bus for publish — both
configured from the same keys above and both closed on halt. The subscriber
uses its own dedicated connection (not the publish pool), so a blocking
`SUBSCRIBE` never starves publishers.

### Return-value caveat

Under `:redis`, `send-to-user`, `send-to-role`, `broadcast`, `publish-to-topic`, and `send-to-connection` all return `nil` — delivery is async (fire-and-forget via the pub/sub relay). Under `:in-memory` these functions return recipient counts or booleans. Do not branch on the return value in application code.

### Wiring note

The web / WS server component **must declare `:wagoe/realtime` as a dependency** so the Redis subscriber is active before the first WebSocket connection is accepted. Without this ordering, messages published during startup may be missed.

See [ADR-035](../../dev-docs/adr/ADR-035-realtime-redis-scaling.adoc) for the full design rationale.

## Gotchas

1. **Provider determines replica-safety** — the default `:in-memory` provider stores the connection registry and pub/sub state in process-local atoms; it is single-server only and requires sticky sessions when load-balanced. The `:redis` provider (shipped in BOU-85) scales across replicas — use it for any multi-instance deployment.
2. **JWT adapter uses optional dependency** on boundary/user - `requiring-resolve` at load time. Throws `:type :internal-error` if user module unavailable
3. **Messages get JSON-encoded** via Cheshire before sending over WebSocket
4. **Topic subscriptions are server-side only** - no client-side subscribe/unsubscribe protocol messages
5. **Shell checks `ports/open?`** before sending to prevent errors on closed connections

## Test Adapters

```clojure
;; TestWebSocketAdapter - collects sent messages in atom
(def ws (websocket-adapter/create-test-websocket-adapter conn-id))
;; @(:sent-messages ws) => [{:type :welcome ...} ...]

;; TestJWTAdapter - returns mock claims
(def jwt (jwt-adapter/create-test-jwt-adapter
           (atom {:user-id user-id :expected-token "test-token" ...})))
```

## Testing

```bash
clojure -M:test:db/h2 :realtime
```

## Links

- [Library README](README.md)
- [Root AGENTS Guide](../../AGENTS.md)
