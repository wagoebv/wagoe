# BOU-85 Realtime Redis Adapter Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `libs/realtime` deliver WebSocket messages across multiple replicas via a Redis pub/sub message bus, selectable with `:provider :in-memory | :redis`.

**Architecture:** Relay model. Live sockets stay node-local in `InMemoryConnectionRegistry` on every replica. A new `IMessageBus` port carries routing *envelopes* between replicas; each replica's registered `delivery-fn` resolves an envelope against its local registry and sends to its own sockets. Delivery happens exclusively through that callback (never inline on the origin), so each socket — living on exactly one node — is delivered to exactly once with no dedup. In-memory bus = synchronous loopback (returns a count); Redis bus = binary Nippy pub/sub (async, returns nil). Topic subscriptions move to Redis sets so they are cluster-wide.

**Tech Stack:** Clojure, Integrant, Jedis 7.5.2 (`BinaryJedisPubSub`), Nippy 3.6.2, Malli, Kaocha. FC/IS pattern.

**Spec:** `docs/superpowers/specs/2026-06-19-bou-85-realtime-redis-adapter-design.md`
**ADR:** `dev-docs/adr/ADR-035-realtime-redis-scaling.adoc`

---

## Conventions for every task

- Run a single realtime namespace: `clojure -M:test:db/h2 --focus <ns-symbol>` (e.g. `--focus boundary.realtime.core.bus-test`).
- Run the whole realtime suite: `clojure -M:test:db/h2 :realtime`.
- Redis-touching tests skip themselves at runtime when Redis is not on `localhost:6379` (same pattern as `libs/cache/test/boundary/cache/shell/adapters/redis_test.clj`). They must still load and "pass" (no-op) without Redis.
- Lint touched files: `clojure -M:clj-kondo --lint libs/realtime/src libs/realtime/test`.
- Use `clj-paren-repair <file>` if delimiters break — never hand-fix parens.
- **Do not commit until the user approves** (project policy in `AGENTS.md`). Each "Commit" step below stages and writes the message but MUST be gated on explicit user approval; if executing in a session, batch the commits and ask before running them.
- After all tasks: `bb check:fcis` and `bb check:ports` must pass.

## File structure

| File | Create/Modify | Responsibility |
|---|---|---|
| `libs/realtime/deps.edn` | Modify | Add `jedis` 7.5.2 + `nippy` 3.6.2. |
| `libs/realtime/src/boundary/realtime/ports.clj` | Modify | Add `IMessageBus`; add `find-adapters-by-ids` to `IConnectionRegistry`; note `nil` returns under `:redis` in `IRealtimeService` docstrings. |
| `libs/realtime/src/boundary/realtime/core/bus.clj` | Create | Pure envelope constructors. |
| `libs/realtime/src/boundary/realtime/shell/connection_registry.clj` | Modify | Implement `find-adapters-by-ids`. |
| `libs/realtime/src/boundary/realtime/shell/bus/in_memory.clj` | Create | `InMemoryMessageBus`. |
| `libs/realtime/src/boundary/realtime/shell/delivery.clj` | Create | `make-delivery-fn` (node-local delivery closure). |
| `libs/realtime/src/boundary/realtime/shell/service.clj` | Modify | Publish envelopes; default in-memory bus; `find-adapters-by-ids` instead of atom-poke. |
| `libs/realtime/src/boundary/realtime/shell/adapters/redis_pubsub.clj` | Create | `RedisPubSubManager` over Redis sets. |
| `libs/realtime/src/boundary/realtime/shell/bus/redis.clj` | Create | `RedisMessageBus`. |
| `libs/realtime/src/boundary/realtime/shell/module_wiring.clj` | Create | Integrant `:boundary/realtime`, provider select. |
| `libs/realtime/test/boundary/realtime/core/bus_test.clj` | Create | Envelope constructor units. |
| `libs/realtime/test/boundary/realtime/shell/bus/in_memory_test.clj` | Create | In-memory bus units. |
| `libs/realtime/test/boundary/realtime/shell/delivery_test.clj` | Create | Delivery-fn units. |
| `libs/realtime/test/boundary/realtime/shell/cross_instance_test.clj` | Create | 2-node relay over a shared in-memory bus. |
| `libs/realtime/test/boundary/realtime/shell/adapters/redis_pubsub_test.clj` | Create | Redis pubsub integration (skip-if-unavailable). |
| `libs/realtime/test/boundary/realtime/shell/bus/redis_test.clj` | Create | Redis bus integration (skip-if-unavailable). |
| `libs/realtime/AGENTS.md` | Modify | Provider section + `nil`-count caveat. |
| `docs/modules/architecture/pages/scaling.adoc` | Modify | Realtime now replica-safe via `:redis`. |

---

### Task 1: Add `IMessageBus` port + `find-adapters-by-ids` to registry port

**Files:**
- Modify: `libs/realtime/src/boundary/realtime/ports.clj`

- [ ] **Step 1: Add `find-adapters-by-ids` to the `IConnectionRegistry` protocol.**

In `ports.clj`, inside `defprotocol IConnectionRegistry`, after `find-connection`, add:

```clojure
  (find-adapters-by-ids [this connection-ids]
    "Return a vector of IWebSocketConnection adapters for the given connection
     ids that are present in THIS node's registry. Missing ids are skipped.

     Args:
       connection-ids - seq of connection UUIDs

     Returns:
       Vector of IWebSocketConnection adapters (may be empty)")
```

- [ ] **Step 2: Append the `IMessageBus` protocol at the end of `ports.clj`.**

```clojure
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
```

- [ ] **Step 3: Add `nil`-under-redis note to `IRealtimeService` docstrings.**

In each of `send-to-user`, `send-to-role`, `broadcast`, `send-to-connection`, `publish-to-topic`, append a line to the existing "Returns:" block, e.g. for `send-to-user`:

```
    Returns:
      Number of connections message was sent to (integer >= 0) under the
      :in-memory provider. Returns nil under the :redis provider, where
      fan-out is asynchronous and the global count is not known synchronously.
```

(For `send-to-connection`: "true/false under :in-memory; nil under :redis.")

- [ ] **Step 4: Lint.**

Run: `clojure -M:clj-kondo --lint libs/realtime/src/boundary/realtime/ports.clj`
Expected: no errors (warnings about unused bindings are fine; protocol method names resolve).

- [ ] **Step 5: Commit (gated on approval).**

```bash
git add libs/realtime/src/boundary/realtime/ports.clj
git commit -m "feat(realtime): add IMessageBus port + find-adapters-by-ids (BOU-85)"
```

---

### Task 2: Pure envelope constructors (`core/bus.clj`)

**Files:**
- Create: `libs/realtime/src/boundary/realtime/core/bus.clj`
- Test: `libs/realtime/test/boundary/realtime/core/bus_test.clj`

- [ ] **Step 1: Write the failing test.**

```clojure
(ns boundary.realtime.core.bus-test
  {:kaocha.testable/meta {:unit true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.realtime.core.bus :as bus]))

(def msg {:type :notification :payload {:x 1}})

(deftest envelope-constructors-test
  (testing "user envelope"
    (is (= {:route :user :target #uuid "00000000-0000-0000-0000-000000000001" :message msg}
           (bus/user-envelope #uuid "00000000-0000-0000-0000-000000000001" msg))))
  (testing "role envelope"
    (is (= {:route :role :target :admin :message msg}
           (bus/role-envelope :admin msg))))
  (testing "broadcast envelope has nil target"
    (is (= {:route :broadcast :target nil :message msg}
           (bus/broadcast-envelope msg))))
  (testing "connection envelope"
    (is (= {:route :connection :target #uuid "00000000-0000-0000-0000-000000000002" :message msg}
           (bus/connection-envelope #uuid "00000000-0000-0000-0000-000000000002" msg))))
  (testing "topic envelope"
    (is (= {:route :topic :target "order:123" :message msg}
           (bus/topic-envelope "order:123" msg)))))
```

- [ ] **Step 2: Run it to verify it fails.**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.core.bus-test`
Expected: FAIL — namespace `boundary.realtime.core.bus` not found.

- [ ] **Step 3: Write the minimal implementation.**

```clojure
(ns boundary.realtime.core.bus
  "Pure constructors for cross-instance routing envelopes.

   An envelope is plain data describing WHERE a message should go; the bus
   transports it and a node-local delivery-fn resolves :route against the
   local registry. No I/O here.")

(defn user-envelope       [user-id message] {:route :user       :target user-id   :message message})
(defn role-envelope       [role message]    {:route :role       :target role      :message message})
(defn broadcast-envelope  [message]         {:route :broadcast  :target nil       :message message})
(defn connection-envelope [conn-id message] {:route :connection :target conn-id   :message message})
(defn topic-envelope      [topic message]   {:route :topic      :target topic     :message message})
```

- [ ] **Step 4: Run it to verify it passes.**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.core.bus-test`
Expected: PASS.

- [ ] **Step 5: Commit (gated).**

```bash
git add libs/realtime/src/boundary/realtime/core/bus.clj \
        libs/realtime/test/boundary/realtime/core/bus_test.clj
git commit -m "feat(realtime): pure routing-envelope constructors (BOU-85)"
```

---

### Task 3: Implement `find-adapters-by-ids` on `InMemoryConnectionRegistry`

**Files:**
- Modify: `libs/realtime/src/boundary/realtime/shell/connection_registry.clj`
- Test: `libs/realtime/test/boundary/realtime/shell/connection_registry_test.clj`

- [ ] **Step 1: Write the failing test (append to the existing test ns).**

```clojure
(deftest find-adapters-by-ids-test
  (testing "returns adapters for present ids, skips missing"
    (let [reg (registry/create-in-memory-registry)
          id-1 (java.util.UUID/randomUUID)
          id-2 (java.util.UUID/randomUUID)
          missing (java.util.UUID/randomUUID)
          conn-1 (conn/create-connection user-id #{:user} {} id-1 (java.time.Instant/now))
          conn-2 (conn/create-connection user-id #{:user} {} id-2 (java.time.Instant/now))
          ws-1 (ws/create-test-websocket-adapter id-1)
          ws-2 (ws/create-test-websocket-adapter id-2)]
      (ports/register reg id-1 conn-1 ws-1)
      (ports/register reg id-2 conn-2 ws-2)
      (let [found (ports/find-adapters-by-ids reg [id-1 missing id-2])]
        (is (= 2 (count found)))
        (is (= #{ws-1 ws-2} (set found))))
      (testing "empty input → empty vector"
        (is (= [] (ports/find-adapters-by-ids reg [])))))))
```

**Required before the test body:** the existing `connection_registry_test.clj`
defines `test-user-id-1`/`test-user-id-2`, NOT `user-id`. Add these to the ns:
- to the `:require`: `[boundary.realtime.core.connection :as conn]` and
  `[boundary.realtime.shell.adapters.websocket-adapter :as ws]` (if not already present);
- a top-level `(def user-id (java.util.UUID/randomUUID))` (unconditional — it does not exist yet).

- [ ] **Step 2: Run it to verify it fails.**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.connection-registry-test`
Expected: FAIL — `find-adapters-by-ids` not implemented (IllegalArgumentException / no method).

- [ ] **Step 3: Implement the method.**

In `connection_registry.clj`, inside the `InMemoryConnectionRegistry` record, after `find-connection`:

```clojure
  (find-adapters-by-ids [_this connection-ids]
    (let [snapshot @state]
      (into []
            (keep (fn [cid] (get-in snapshot [cid :ws-adapter])))
            connection-ids))))
```

(Note: this replaces the closing paren of `find-connection`'s form — make sure the record's closing paren placement stays correct; run `clj-paren-repair` if needed.)

- [ ] **Step 4: Run it to verify it passes.**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.connection-registry-test`
Expected: PASS (all existing registry tests + the new one).

- [ ] **Step 5: Commit (gated).**

```bash
git add libs/realtime/src/boundary/realtime/shell/connection_registry.clj \
        libs/realtime/test/boundary/realtime/shell/connection_registry_test.clj
git commit -m "feat(realtime): registry find-adapters-by-ids lookup (BOU-85)"
```

---

### Task 4: `InMemoryMessageBus`

**Files:**
- Create: `libs/realtime/src/boundary/realtime/shell/bus/in_memory.clj`
- Test: `libs/realtime/test/boundary/realtime/shell/bus/in_memory_test.clj`

- [ ] **Step 1: Write the failing test.**

```clojure
(ns boundary.realtime.shell.bus.in-memory-test
  {:kaocha.testable/meta {:unit true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.realtime.ports :as ports]
            [boundary.realtime.shell.bus.in-memory :as bus]))

(deftest publish-sums-delivery-fn-counts-test
  (testing "publish invokes every registered delivery-fn and sums counts"
    (let [b (bus/create-in-memory-bus)
          seen (atom [])]
      (ports/start-subscriber! b (fn [env] (swap! seen conj [:a env]) 2))
      (ports/start-subscriber! b (fn [env] (swap! seen conj [:b env]) 3))
      (is (= 5 (ports/publish b {:route :broadcast :target nil :message {:type :x}})))
      (is (= 2 (count @seen)))))
  (testing "no subscribers → 0"
    (is (= 0 (ports/publish (bus/create-in-memory-bus) {:route :broadcast :message {}}))))
  (testing "stop-subscriber! clears delivery"
    (let [b (bus/create-in-memory-bus)]
      (ports/start-subscriber! b (constantly 1))
      (ports/stop-subscriber! b)
      (is (= 0 (ports/publish b {:route :broadcast :message {}}))))))
```

- [ ] **Step 2: Run it to verify it fails.**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.bus.in-memory-test`
Expected: FAIL — namespace not found.

- [ ] **Step 3: Write the implementation.**

```clojure
(ns boundary.realtime.shell.bus.in-memory
  "In-memory message bus (single-process / test).

   Holds an atom vector of registered delivery-fns. `publish` invokes each
   synchronously and sums their returned counts. This is the default bus for
   single-node deployments and the vehicle for the 2-node cross-instance test
   (two services sharing one bus instance via the :bus option)."
  (:require [boundary.realtime.ports :as ports]))

(defrecord InMemoryMessageBus [subscribers] ; subscribers: atom of [delivery-fn ...]
  ports/IMessageBus

  (publish [_this envelope]
    (reduce (fn [acc f] (+ acc (long (or (f envelope) 0)))) 0 @subscribers))

  (start-subscriber! [_this delivery-fn]
    (swap! subscribers conj delivery-fn)
    nil)

  (stop-subscriber! [_this]
    (reset! subscribers [])
    nil))

(defn create-in-memory-bus
  "Create a fresh in-memory bus. Pass the same instance to two services (via
   their :bus option) to simulate a 2-node relay."
  []
  (->InMemoryMessageBus (atom [])))
```

- [ ] **Step 4: Run it to verify it passes.**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.bus.in-memory-test`
Expected: PASS.

- [ ] **Step 5: Commit (gated).**

```bash
git add libs/realtime/src/boundary/realtime/shell/bus/in_memory.clj \
        libs/realtime/test/boundary/realtime/shell/bus/in_memory_test.clj
git commit -m "feat(realtime): in-memory message bus (BOU-85)"
```

---

### Task 5: Node-local delivery-fn (`shell/delivery.clj`)

**Files:**
- Create: `libs/realtime/src/boundary/realtime/shell/delivery.clj`
- Test: `libs/realtime/test/boundary/realtime/shell/delivery_test.clj`

- [ ] **Step 1: Write the failing test.**

```clojure
(ns boundary.realtime.shell.delivery-test
  {:kaocha.testable/meta {:unit true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.realtime.ports :as ports]
            [boundary.realtime.core.connection :as conn]
            [boundary.realtime.shell.connection-registry :as registry]
            [boundary.realtime.shell.pubsub-manager :as pubsub-mgr]
            [boundary.realtime.shell.adapters.websocket-adapter :as ws]
            [boundary.realtime.shell.delivery :as delivery]))

(def user-id #uuid "550e8400-e29b-41d4-a716-446655440000")

(defn- register! [reg id roles]
  (let [c (conn/create-connection user-id roles {} id (java.time.Instant/now))
        a (ws/create-test-websocket-adapter id)]
    (ports/register reg id c a)
    a))

(deftest delivery-routes-test
  (let [reg (registry/create-in-memory-registry)
        pubsub (pubsub-mgr/create-pubsub-manager)
        id-1 (java.util.UUID/randomUUID)
        id-2 (java.util.UUID/randomUUID)
        a1 (register! reg id-1 #{:user :admin})
        a2 (register! reg id-2 #{:user})
        f  (delivery/make-delivery-fn reg pubsub)
        msg {:type :x :payload {}}]
    (testing "broadcast hits all, returns count"
      (is (= 2 (f {:route :broadcast :target nil :message msg}))))
    (testing "role filters"
      (is (= 1 (f {:route :role :target :admin :message msg}))))
    (testing "connection targets one"
      (is (= 1 (f {:route :connection :target id-1 :message msg}))))
    (testing "topic uses pubsub-manager ∩ local registry"
      (ports/subscribe-to-topic pubsub id-2 "order:1")
      (is (= 1 (f {:route :topic :target "order:1" :message msg}))))
    (testing "topic with nil pubsub-manager → 0"
      (let [f0 (delivery/make-delivery-fn reg nil)]
        (is (= 0 (f0 {:route :topic :target "order:1" :message msg})))))
    (testing "messages actually delivered to adapters"
      (is (pos? (count @(:sent-messages a1)))))))
```

- [ ] **Step 2: Run it to verify it fails.**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.delivery-test`
Expected: FAIL — namespace not found.

- [ ] **Step 3: Write the implementation.**

```clojure
(ns boundary.realtime.shell.delivery
  "Node-local delivery: resolve a routing envelope to the local node's ws
   adapters and send. Built as a closure over the local registry + pubsub
   manager; registered with a message bus via start-subscriber!. Never calls
   service send methods (no re-publish recursion)."
  (:require [boundary.realtime.ports :as ports]))

(defn- adapters-for
  [registry pubsub-manager {:keys [route target]}]
  (case route
    :user       (ports/find-by-user registry target)
    :role       (ports/find-by-role registry target)
    :broadcast  (ports/all-connections registry)
    :connection (ports/find-adapters-by-ids registry [target])
    :topic      (if pubsub-manager
                  (ports/find-adapters-by-ids
                    registry
                    (ports/get-topic-subscribers pubsub-manager target))
                  [])
    []))

(defn make-delivery-fn
  "Return (fn [envelope] -> int): send the envelope's :message to every open
   local adapter the envelope resolves to; return how many were sent to."
  [registry pubsub-manager]
  (fn [{:keys [message] :as envelope}]
    (let [adapters (adapters-for registry pubsub-manager envelope)]
      (reduce
       (fn [n a]
         (if (ports/open? a)
           (do (ports/send-message a message) (inc n))
           n))
       0
       adapters))))
```

- [ ] **Step 4: Run it to verify it passes.**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.delivery-test`
Expected: PASS.

- [ ] **Step 5: Commit (gated).**

```bash
git add libs/realtime/src/boundary/realtime/shell/delivery.clj \
        libs/realtime/test/boundary/realtime/shell/delivery_test.clj
git commit -m "feat(realtime): node-local delivery-fn (BOU-85)"
```

---

### Task 6: Refactor `service.clj` to publish envelopes (keep existing tests green)

**Files:**
- Modify: `libs/realtime/src/boundary/realtime/shell/service.clj`
- Test (must stay green): `libs/realtime/test/boundary/realtime/shell/service_test.clj`

This is the behavioural keystone. The existing `service_test.clj` builds the
service with **no bus** and asserts synchronous counts; `create-realtime-service`
must default to an in-memory bus and self-register a delivery-fn so those
assertions still pass.

- [ ] **Step 1: Run the existing suite first to capture the baseline (must pass before refactor).**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.service-test`
Expected: PASS (current implementation).

- [ ] **Step 2: Update the namespace + record + constructor.**

Edit the `ns` `:require` to add:

```clojure
            [boundary.realtime.core.bus :as bus]
            [boundary.realtime.shell.bus.in-memory :as in-memory-bus]
            [boundary.realtime.shell.delivery :as delivery]
```

Add `bus` as the last record field:

```clojure
(defrecord RealtimeService [connection-registry jwt-verifier pubsub-manager logger error-reporter bus]
  ports/IRealtimeService
  ...)
```

- [ ] **Step 3: Replace the five send methods.**

Keep the existing `stamp`/timestamp helper (rename inline if needed). Define a private helper above the record (forward-reference rule: helper before record):

```clojure
(defn- stamp
  "Add :timestamp if absent (shell owns the clock)."
  [message]
  (if (:timestamp message)
    message
    (assoc message :timestamp (current-timestamp))))
```

Then the methods become:

```clojure
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
    (ports/publish bus (bus/topic-envelope topic (stamp message))))
```

Remove the old inline `find-* → doseq → send-message` bodies and the
`@(:state connection-registry)` atom-poke in `send-to-connection`. `connect` and
`disconnect` stay unchanged.

- [ ] **Step 4: Update `create-realtime-service` to default the bus + start the subscriber.**

```clojure
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
```

- [ ] **Step 5: Run the existing service suite — must stay green unchanged.**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.service-test`
Expected: PASS. Specifically `send-to-user`=2, `send-to-role`=1, `broadcast`=3,
`send-to-connection` true/false, `publish-to-topic`=2/1/0, and the
timestamp-stamping assertions all hold (in-memory bus delivers synchronously).

If any count assertion fails, debug the delivery-fn/registry wiring — do NOT
weaken the assertions.

- [ ] **Step 6: Run the full realtime suite + ring-websocket handler tests.**

Run: `clojure -M:test:db/h2 :realtime`
Expected: PASS (handler tests build the service via the same constructor).

- [ ] **Step 7: FC/IS + ports gates.**

Run: `bb check:fcis && bb check:ports`
Expected: PASS. (Service requires only `shell.bus.in-memory` + `shell.delivery` — shell→shell, allowed. Core `core/bus.clj` has no I/O.)

- [ ] **Step 8: Commit (gated).**

```bash
git add libs/realtime/src/boundary/realtime/shell/service.clj
git commit -m "refactor(realtime): service publishes via message bus (BOU-85)"
```

---

### Task 7: 2-node cross-instance relay test (in-memory, no Redis)

**Files:**
- Create: `libs/realtime/test/boundary/realtime/shell/cross_instance_test.clj`

This satisfies the acceptance criterion "cross-instance broadcast verified with
2 nodes" deterministically in CI without Redis.

- [ ] **Step 1: Write the test.**

```clojure
(ns boundary.realtime.shell.cross-instance-test
  "Two RealtimeService instances (= two replicas) share ONE in-memory bus.
   A publish on node A must reach node B's local sockets — proving the relay."
  {:kaocha.testable/meta {:integration true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.realtime.ports :as ports]
            [boundary.realtime.core.connection :as conn]
            [boundary.realtime.shell.service :as service]
            [boundary.realtime.shell.connection-registry :as registry]
            [boundary.realtime.shell.pubsub-manager :as pubsub-mgr]
            [boundary.realtime.shell.adapters.websocket-adapter :as ws]
            [boundary.realtime.shell.adapters.jwt-adapter :as jwt]
            [boundary.realtime.shell.bus.in-memory :as in-memory-bus]))

(def user-a #uuid "550e8400-e29b-41d4-a716-446655440000")
(def user-b #uuid "660e8400-e29b-41d4-a716-446655440001")

(defn- node [shared-bus]
  (let [reg (registry/create-in-memory-registry)
        pubsub (pubsub-mgr/create-pubsub-manager)
        jwt-adapter (jwt/create-test-jwt-adapter
                     {:expected-token "valid-token" :user-id user-a
                      :email "a@example.com" :roles #{:user}})
        svc (service/create-realtime-service reg jwt-adapter
                                             :pubsub-manager pubsub :bus shared-bus)]
    {:reg reg :pubsub pubsub :svc svc}))

(defn- register! [{:keys [reg]} id user-id roles]
  (let [c (conn/create-connection user-id roles {} id (java.time.Instant/now))
        a (ws/create-test-websocket-adapter id)]
    (ports/register reg id c a)
    a))

(deftest broadcast-crosses-instances-test
  (let [shared (in-memory-bus/create-in-memory-bus)
        a (node shared)
        b (node shared)
        ws-a (register! a (java.util.UUID/randomUUID) user-a #{:user})
        ws-b (register! b (java.util.UUID/randomUUID) user-b #{:user})]
    (testing "broadcast from node A reaches a socket on node B"
      (ports/broadcast (:svc a) {:type :announce :payload {:m "hi"}})
      (is (= 1 (count @(:sent-messages ws-a))) "node A delivered locally")
      (is (= 1 (count @(:sent-messages ws-b))) "node B received via the relay"))))

(deftest send-to-user-crosses-instances-test
  (let [shared (in-memory-bus/create-in-memory-bus)
        a (node shared)
        b (node shared)
        _ws-a (register! a (java.util.UUID/randomUUID) user-a #{:user})
        ws-b (register! b (java.util.UUID/randomUUID) user-b #{:user})]
    (testing "send-to-user reaches the right node"
      (ports/send-to-user (:svc a) user-b {:type :dm :payload {}})
      (is (= 1 (count @(:sent-messages ws-b))) "user-b socket on node B got it"))))

(deftest publish-to-topic-crosses-instances-test
  (let [shared (in-memory-bus/create-in-memory-bus)
        a (node shared)
        b (node shared)
        id-b (java.util.UUID/randomUUID)
        ws-b (register! b id-b user-b #{:user})]
    (ports/subscribe-to-topic (:pubsub b) id-b "order:9")
    (testing "topic publish on A reaches subscriber on B"
      ;; node A must also see the subscription for its delivery-fn to resolve it;
      ;; with the in-memory pubsub manager each node has its own — so subscribe on
      ;; A's pubsub too (mirrors what RedisPubSubManager makes global automatically).
      (ports/subscribe-to-topic (:pubsub a) id-b "order:9")
      (ports/publish-to-topic (:svc a) "order:9" {:type :upd :payload {}})
      (is (= 1 (count @(:sent-messages ws-b)))))))
```

> Note: the topic test subscribes on both nodes' in-memory pubsub managers
> because the in-memory `AtomPubSubManager` is per-node. Under `:redis` the
> `RedisPubSubManager` makes subscriptions global, so a single subscribe suffices
> — covered by Task 8's Redis test. This is documented, not a hack.

- [ ] **Step 2: Run it.**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.cross-instance-test`
Expected: PASS.

- [ ] **Step 3: Commit (gated).**

```bash
git add libs/realtime/test/boundary/realtime/shell/cross_instance_test.clj
git commit -m "test(realtime): 2-node cross-instance relay over shared bus (BOU-85)"
```

---

### Task 8: Add Redis + Nippy deps

**Files:**
- Modify: `libs/realtime/deps.edn`

- [ ] **Step 1: Add the two deps to the `:deps` map.**

```clojure
         redis.clients/jedis       {:mvn/version "7.5.2"}
         com.taoensso/nippy        {:mvn/version "3.6.2"}
```

- [ ] **Step 2: Verify deps resolve.**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.core.bus-test`
Expected: PASS (resolution succeeds; no new classpath errors).

- [ ] **Step 3: Commit (gated).**

```bash
git add libs/realtime/deps.edn
git commit -m "build(realtime): add jedis + nippy deps (BOU-85)"
```

---

### Task 9: `RedisPubSubManager` (Redis sets, MULTI/EXEC, no DEL)

**Files:**
- Create: `libs/realtime/src/boundary/realtime/shell/adapters/redis_pubsub.clj`
- Test: `libs/realtime/test/boundary/realtime/shell/adapters/redis_pubsub_test.clj`

- [ ] **Step 1: Write the integration test (skip-if-unavailable).**

```clojure
(ns boundary.realtime.shell.adapters.redis-pubsub-test
  "Integration tests for the Redis pub/sub manager. Require Redis on
   localhost:6379; skipped (no-op pass) when unavailable."
  {:kaocha.testable/meta {:integration true :redis true :realtime true}}
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.realtime.ports :as ports]
            [boundary.realtime.shell.adapters.redis-pubsub :as rpub])
  (:import [redis.clients.jedis JedisPool Jedis]))

(defonce ^:private availability (atom nil))

(defn redis-available? []
  (if (some? @availability)
    @availability
    (reset! availability
            (try
              (let [pool (JedisPool. "localhost" 6379)]
                (with-open [^Jedis j (.getResource pool)]
                  (= "PONG" (.ping j)))
                (.close pool)
                true)
              (catch Exception _ false)))))

(def ^:dynamic *mgr* nil)
(def ^:dynamic *pool* nil)

(defn with-mgr [f]
  (if (redis-available?)
    (let [pool (JedisPool. "localhost" 6379)
          mgr (rpub/create-redis-pubsub-manager pool {:prefix "rt-test"})]
      (binding [*mgr* mgr *pool* pool]
        (try (f)
             (finally
               (with-open [^Jedis j (.getResource pool)]
                 (.flushDB j))
               (.close pool)))))
    (f)))

(use-fixtures :each with-mgr)

(defmacro when-redis [& body] `(when (redis-available?) ~@body))

(deftest subscribe-get-unsubscribe-roundtrip-test
  (when-redis
    (let [c1 (java.util.UUID/randomUUID)
          c2 (java.util.UUID/randomUUID)]
      (ports/subscribe-to-topic *mgr* c1 "order:1")
      (ports/subscribe-to-topic *mgr* c2 "order:1")
      (testing "subscribers parsed back to UUIDs"
        (is (= #{c1 c2} (ports/get-topic-subscribers *mgr* "order:1"))))
      (testing "reverse index"
        (is (= #{"order:1"} (ports/get-connection-subscriptions *mgr* c1))))
      (testing "missing topic → empty set"
        (is (= #{} (ports/get-topic-subscribers *mgr* "nope"))))
      (testing "last-member unsubscribe auto-removes the key (no manual DEL)"
        (ports/unsubscribe-from-topic *mgr* c1 "order:1")
        (ports/unsubscribe-from-topic *mgr* c2 "order:1")
        (is (= #{} (ports/get-topic-subscribers *mgr* "order:1")))
        (with-open [^Jedis j (.getResource *pool*)]
          (is (false? (.exists j "rt-test:topic:order:1"))))))))

(deftest unsubscribe-all-test
  (when-redis
    (let [c (java.util.UUID/randomUUID)]
      (ports/subscribe-to-topic *mgr* c "a")
      (ports/subscribe-to-topic *mgr* c "b")
      (ports/unsubscribe-from-all-topics *mgr* c)
      (is (= #{} (ports/get-connection-subscriptions *mgr* c)))
      (is (= #{} (ports/get-topic-subscribers *mgr* "a"))))))
```

- [ ] **Step 2: Run it (fails to load — ns missing).**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.adapters.redis-pubsub-test`
Expected: FAIL — namespace `...redis-pubsub` not found.

- [ ] **Step 3: Implement `RedisPubSubManager`.**

Key naming: `{prefix:}topic:{t}` and `{prefix:}conn:{id}`. Conn-ids stored as
`(str uuid)`. Use `MULTI/EXEC` (`.multi`/`.exec` on a borrowed `Jedis`) for the
two-key writes. No explicit DEL — Redis removes empty sets automatically.

```clojure
(ns boundary.realtime.shell.adapters.redis-pubsub
  "Redis-backed IPubSubManager: topic subscriptions in Redis sets so they are
   visible cluster-wide.

   Keys (optionally prefixed):
     topic:{t}  -> SET of connection-id strings
     conn:{id}  -> SET of topic strings   (reverse index)

   subscribe / unsubscribe apply both SADD/SREM atomically in a MULTI/EXEC.
   No explicit DEL: Redis auto-removes a set key when its last member is
   removed, so empty topics disappear and the check-then-act DEL race cannot
   occur."
  (:require [boundary.realtime.ports :as ports]
            [boundary.realtime.schema :as schema])
  (:import [redis.clients.jedis JedisPool Jedis]
           [java.util UUID]))

(defn- k [prefix kind v] (str (when prefix (str prefix ":")) kind ":" v))
(defn- topic-key [prefix t]  (k prefix "topic" t))
(defn- conn-key  [prefix id] (k prefix "conn" (str id)))

(defn- with-redis [^JedisPool pool f]
  (with-open [^Jedis j (.getResource pool)] (f j)))

(defrecord RedisPubSubManager [^JedisPool pool prefix]
  ports/IPubSubManager

  (subscribe-to-topic [_ connection-id topic]
    (when-not (schema/valid-topic? topic)
      (throw (ex-info "Invalid topic name"
                      {:type :validation-error :topic topic
                       :errors (schema/explain-topic topic)})))
    (with-redis pool
      (fn [^Jedis j]
        (let [tx (.multi j)]
          (.sadd tx (topic-key prefix topic) (into-array String [(str connection-id)]))
          (.sadd tx (conn-key prefix connection-id) (into-array String [topic]))
          (.exec tx))))
    nil)

  (unsubscribe-from-topic [_ connection-id topic]
    (with-redis pool
      (fn [^Jedis j]
        (let [tx (.multi j)]
          (.srem tx (topic-key prefix topic) (into-array String [(str connection-id)]))
          (.srem tx (conn-key prefix connection-id) (into-array String [topic]))
          (.exec tx))))
    nil)

  (unsubscribe-from-all-topics [_ connection-id]
    (with-redis pool
      (fn [^Jedis j]
        (let [topics (.smembers j (conn-key prefix connection-id))]
          (when (seq topics)
            (let [tx (.multi j)]
              (doseq [t topics]
                (.srem tx (topic-key prefix t) (into-array String [(str connection-id)])))
              (.del tx (into-array String [(conn-key prefix connection-id)]))
              (.exec tx))))))
    nil)

  (get-topic-subscribers [_ topic]
    (with-redis pool
      (fn [^Jedis j]
        (into #{} (map #(UUID/fromString %)) (.smembers j (topic-key prefix topic))))))

  (get-connection-subscriptions [_ connection-id]
    (with-redis pool
      (fn [^Jedis j] (set (.smembers j (conn-key prefix connection-id))))))

  (topic-count [_]
    (with-redis pool
      (fn [^Jedis j] (count (.keys j (str (when prefix (str prefix ":")) "topic:*"))))))

  (subscription-count [_]
    (with-redis pool
      (fn [^Jedis j]
        (reduce + 0 (map #(.scard j %)
                         (.keys j (str (when prefix (str prefix ":")) "topic:*"))))))))

(defn create-redis-pubsub-manager
  ([pool] (create-redis-pubsub-manager pool {}))
  ([pool {:keys [prefix]}] (->RedisPubSubManager pool prefix)))
```

> Note: `unsubscribe-from-all-topics` DELs the now-orphan `conn:{id}` key
> explicitly (it would otherwise become empty via SREM of its members too, but
> we already hold the topic list — DELing the reverse-index key is safe and not
> a check-then-act on a shared key). The topic sets self-clean via SREM.
> `topic-count`/`subscription-count` use `KEYS` for simplicity (admin/metrics
> only); acceptable here, switch to `SCAN` if it ever runs hot.

- [ ] **Step 4: Run it.**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.adapters.redis-pubsub-test`
Expected: with Redis running, PASS; without Redis, PASS (tests no-op via `when-redis`). Start Redis locally to truly exercise: `docker run -p 6379:6379 redis` (or `redis-server`).

- [ ] **Step 5: Lint + commit (gated).**

```bash
clojure -M:clj-kondo --lint libs/realtime/src/boundary/realtime/shell/adapters/redis_pubsub.clj
git add libs/realtime/src/boundary/realtime/shell/adapters/redis_pubsub.clj \
        libs/realtime/test/boundary/realtime/shell/adapters/redis_pubsub_test.clj
git commit -m "feat(realtime): Redis-backed pub/sub manager (BOU-85)"
```

---

### Task 10: `RedisMessageBus` (binary Nippy pub/sub, idempotent singleton, reconnect)

**Files:**
- Create: `libs/realtime/src/boundary/realtime/shell/bus/redis.clj`
- Test: `libs/realtime/test/boundary/realtime/shell/bus/redis_test.clj`

- [ ] **Step 1: Write the integration test (skip-if-unavailable).**

```clojure
(ns boundary.realtime.shell.bus.redis-test
  {:kaocha.testable/meta {:integration true :redis true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.realtime.ports :as ports]
            [boundary.realtime.shell.bus.redis :as rbus])
  (:import [redis.clients.jedis JedisPool Jedis]))

(defn redis-available? []
  (try
    (let [pool (JedisPool. "localhost" 6379)]
      (with-open [^Jedis j (.getResource pool)] (= "PONG" (.ping j)))
      (.close pool) true)
    (catch Exception _ false)))

(defn- await-count [a n ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (>= (count @a) n) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(deftest cross-bus-delivery-test
  (when (redis-available?)
    (let [chan "rt-test:bus"
          received (atom [])
          pub (rbus/create-redis-bus {:host "localhost" :port 6379 :channel chan})
          sub (rbus/create-redis-bus {:host "localhost" :port 6379 :channel chan})]
      (try
        (ports/start-subscriber! sub (fn [env] (swap! received conj env) 1))
        (testing "envelope published on one bus reaches a subscriber on another"
          (ports/publish pub {:route :broadcast :target nil
                              :message {:type :x :payload {:n 1}}})
          (is (await-count received 1 2000))
          (is (= :broadcast (:route (first @received))))
          (is (= {:type :x :payload {:n 1}} (:message (first @received)))))
        (finally
          (ports/stop-subscriber! sub)
          (ports/stop-subscriber! pub))))))

(deftest idempotent-subscriber-test
  (when (redis-available?)
    (let [chan "rt-test:bus2"
          received (atom [])
          sub (rbus/create-redis-bus {:host "localhost" :port 6379 :channel chan})
          pub (rbus/create-redis-bus {:host "localhost" :port 6379 :channel chan})]
      (try
        (ports/start-subscriber! sub (fn [_] (swap! received conj :got) 1))
        (ports/start-subscriber! sub (fn [_] (swap! received conj :got) 1)) ; no-op
        (ports/publish pub {:route :broadcast :message {:type :y}})
        (Thread/sleep 500)
        (testing "second start-subscriber! did not create a second subscription"
          (is (= 1 (count @received))))
        (finally
          (ports/stop-subscriber! sub)
          (ports/stop-subscriber! pub))))))
```

- [ ] **Step 2: Run it (fails to load).**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.bus.redis-test`
Expected: FAIL — namespace not found.

- [ ] **Step 3: Implement `RedisMessageBus`.**

```clojure
(ns boundary.realtime.shell.bus.redis
  "Redis-backed IMessageBus: routing envelopes travel as Nippy-frozen bytes over
   a binary Redis pub/sub channel. publish borrows a pooled connection; the
   subscriber owns one dedicated blocking connection on a daemon thread.

   Concurrency:
   - Singleton subscriber: start-subscriber! is idempotent (running? guard) so a
     node never holds two channel subscriptions (which would double-deliver).
   - Reconnect: the daemon loops with backoff, fully tearing down the old
     BinaryJedisPubSub before re-subscribing. Gap messages are missed
     (at-most-once, accepted)."
  (:require [boundary.realtime.ports :as ports]
            [clojure.tools.logging :as log]
            [taoensso.nippy :as nippy])
  (:import [redis.clients.jedis JedisPool JedisPoolConfig Jedis BinaryJedisPubSub]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.nio.charset StandardCharsets]))

(defn- ->bytes ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn- make-pool ^JedisPool [{:keys [host port]}]
  (JedisPool. (JedisPoolConfig.) (or host "localhost") (int (or port 6379))))

(defrecord RedisMessageBus [^JedisPool pool channel-bytes state]
  ;; state: atom of {:running? bool :pubsub BinaryJedisPubSub :thread Thread
  ;;                 :sub-conn Jedis :delivery-fn fn}
  ports/IMessageBus

  (publish [_this envelope]
    (try
      (with-open [^Jedis j (.getResource pool)]
        (.publish j channel-bytes (nippy/freeze envelope)))
      (catch Exception e
        (log/error e "Redis bus publish failed"))
      (finally nil))
    nil)

  (start-subscriber! [_this delivery-fn]
    (when-not (:running? @state)
      (let [latch (CountDownLatch. 1)
            pubsub (proxy [BinaryJedisPubSub] []
                     (onSubscribe [_chan _cnt] (.countDown latch))
                     (onMessage [_chan ^bytes message]
                       (try
                         (delivery-fn (nippy/thaw message))
                         (catch Exception e
                           (log/error e "Redis bus delivery failed")))))
            thread (Thread.
                     (fn []
                       (loop [backoff 100]
                         (when (:running? @state)
                           (let [conn (.getResource pool)]
                             (swap! state assoc :sub-conn conn)
                             (try
                               (.subscribe conn pubsub (into-array (Class/forName "[B") [channel-bytes]))
                               (catch Exception e
                                 (when (:running? @state)
                                   (log/warn e "Redis subscriber dropped; reconnecting")))
                               (finally
                                 (try (.close conn) (catch Exception _ nil))))
                             (when (:running? @state)
                               (Thread/sleep backoff)
                               (recur (min 5000 (* 2 backoff)))))))))]
        (swap! state assoc :running? true :pubsub pubsub :thread thread :delivery-fn delivery-fn)
        (.setDaemon thread true)
        (.start thread)
        ;; Block until subscription is live (or time out so startup never hangs).
        (.await latch 5 TimeUnit/SECONDS)))
    nil)

  (stop-subscriber! [_this]
    (when (:running? @state)
      (swap! state assoc :running? false)
      (let [{:keys [^BinaryJedisPubSub pubsub ^Thread thread]} @state]
        (try (when (and pubsub (.isSubscribed pubsub)) (.unsubscribe pubsub))
             (catch Exception e (log/warn e "Redis unsubscribe failed")))
        (when thread
          (try (.join thread 2000) (catch Exception _ nil)))))
    nil)

  java.io.Closeable
  (close [this]
    (ports/stop-subscriber! this)
    (try (.close pool) (catch Exception _ nil))))

(defn create-redis-bus
  "Create a Redis message bus.
   Config: {:host :port :channel}. :channel defaults to \"wagoe:realtime:bus\"."
  [{:keys [channel] :as config}]
  (->RedisMessageBus (make-pool config)
                     (->bytes (or channel "wagoe:realtime:bus"))
                     (atom {:running? false})))
```

> Implementation notes for the worker:
> - `proxy` over `BinaryJedisPubSub` overrides `onSubscribe`/`onMessage` (both
>   take `byte[]`). `.subscribe` blocks the daemon thread — that is expected.
> - The reconnect loop uses a per-call pooled connection for the blocking
>   subscribe; on drop it closes and retries with capped backoff.
> - If `.exec`/varargs interop gives reflection warnings, add type hints; it must
>   still compile. Use `clj-paren-repair` if the `proxy`/`loop` nesting breaks.

- [ ] **Step 4: Run it (with Redis running locally).**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.bus.redis-test`
Expected: with Redis, PASS; without Redis, PASS (no-op). To exercise: `docker run --rm -p 6379:6379 redis` in another terminal.

- [ ] **Step 5: Lint + commit (gated).**

```bash
clojure -M:clj-kondo --lint libs/realtime/src/boundary/realtime/shell/bus/redis.clj
git add libs/realtime/src/boundary/realtime/shell/bus/redis.clj \
        libs/realtime/test/boundary/realtime/shell/bus/redis_test.clj
git commit -m "feat(realtime): Redis-backed message bus (BOU-85)"
```

---

### Task 11: Integrant wiring + provider selection (`module_wiring.clj`)

**Files:**
- Create: `libs/realtime/src/boundary/realtime/shell/module_wiring.clj`

- [ ] **Step 1: Write the implementation (mirrors `boundary.cache.shell.module-wiring`).**

```clojure
(ns boundary.realtime.shell.module-wiring
  "Integrant wiring for the realtime module.

   Config key: :boundary/realtime
     {:provider :in-memory | :redis
      ;; redis only:
      :host \"localhost\" :port 6379
      :channel \"wagoe:realtime:bus\"
      :key-prefix \"realtime\"
      :jwt-verifier <IJWTVerifier ref>}

   The local connection registry is in-memory under BOTH providers (sockets are
   node-local). Only the pub/sub manager and the bus differ.

   IMPORTANT: the web/WS server component MUST depend on :boundary/realtime so
   that start-subscriber! has completed (subscription live) before any WebSocket
   connection is accepted."
  (:require [boundary.realtime.ports :as ports]
            [boundary.realtime.shell.service :as service]
            [boundary.realtime.shell.connection-registry :as registry]
            [boundary.realtime.shell.pubsub-manager :as atom-pubsub]
            [boundary.realtime.shell.adapters.redis-pubsub :as redis-pubsub]
            [boundary.realtime.shell.bus.in-memory :as in-memory-bus]
            [boundary.realtime.shell.bus.redis :as redis-bus]
            [clojure.tools.logging :as log]
            [integrant.core :as ig])
  (:import [redis.clients.jedis JedisPool JedisPoolConfig]))

(defmethod ig/init-key :boundary/realtime
  [_ {:keys [provider jwt-verifier] :as config}]
  (log/info "Initializing realtime component" {:provider provider})
  (let [conn-registry (registry/create-in-memory-registry)
        [pubsub-manager bus pool]
        (case provider
          :redis
          (let [pool (JedisPool. (JedisPoolConfig.)
                                 (or (:host config) "localhost")
                                 (int (or (:port config) 6379)))]
            [(redis-pubsub/create-redis-pubsub-manager pool {:prefix (or (:key-prefix config) "realtime")})
             (redis-bus/create-redis-bus config)
             pool])

          ;; default :in-memory
          [(atom-pubsub/create-pubsub-manager)
           (in-memory-bus/create-in-memory-bus)
           nil])
        svc (service/create-realtime-service conn-registry jwt-verifier
                                             :pubsub-manager pubsub-manager
                                             :bus bus)]
    (log/info "Realtime component initialized" {:provider provider})
    {:service svc :registry conn-registry :pubsub-manager pubsub-manager
     :bus bus :pool pool}))

(defmethod ig/halt-key! :boundary/realtime
  [_ {:keys [bus pool]}]
  (log/info "Halting realtime component")
  (when bus (try (ports/stop-subscriber! bus) (catch Exception e (log/warn e "stop-subscriber! failed"))))
  (when pool (try (.close pool) (catch Exception e (log/warn e "pool close failed")))))
```

> Note: `create-realtime-service` already calls `start-subscriber!` with the
> delivery-fn built from the registry + pubsub-manager, so the wiring does not
> need to register it again. The returned map exposes `:service` for consumers
> and `:bus`/`:pool` for clean shutdown.

- [ ] **Step 2: Smoke-load the namespace.**

Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.bus.in-memory-test`
(any realtime test triggers compilation of the lib; or evaluate
`(require 'boundary.realtime.shell.module-wiring)` in a REPL).
Expected: compiles without error.

- [ ] **Step 3: Lint + `bb check:ports` + `bb check:fcis`.**

Run: `clojure -M:clj-kondo --lint libs/realtime/src && bb check:ports && bb check:fcis`
Expected: PASS.

- [ ] **Step 4: Commit (gated).**

```bash
git add libs/realtime/src/boundary/realtime/shell/module_wiring.clj
git commit -m "feat(realtime): integrant wiring + :provider selection (BOU-85)"
```

---

### Task 12: Docs — AGENTS.md, ports docstrings (done in Task 1), scaling.adoc

**Files:**
- Modify: `libs/realtime/AGENTS.md`
- Modify: `docs/modules/architecture/pages/scaling.adoc`

- [ ] **Step 1: Add a provider section to `libs/realtime/AGENTS.md`.**

Document: `:provider :in-memory|:redis`; that the connection registry is always
node-local; that `:redis` requires Redis; the relay model in one paragraph; and
the **caveat**: under `:redis`, `send-to-user/role/broadcast/publish-to-topic`
return `nil` and `send-to-connection` returns `nil` (async fan-out) — only
`:in-memory` returns counts/booleans. Mention the wiring requirement that the
web/WS component depend on `:boundary/realtime`.

- [ ] **Step 2: Update `scaling.adoc`.**

Find the realtime row/paragraph that says realtime is not yet replica-safe and
change it to: replica-safe via `:provider :redis` (Redis pub/sub relay, BOU-85);
keep the sticky-session/single-node note as the `:in-memory` fallback. Reference
ADR-035.

- [ ] **Step 3: Verify doc links.**

Run: `bb check-links`
Expected: PASS (no broken local links introduced).

- [ ] **Step 4: Commit (gated).**

```bash
git add libs/realtime/AGENTS.md docs/modules/architecture/pages/scaling.adoc
git commit -m "docs(realtime): document Redis provider + scaling status (BOU-85)"
```

---

### Task 13: Full verification sweep

- [ ] **Step 1: Full realtime suite.**

Run: `clojure -M:test:db/h2 :realtime`
Expected: PASS (Redis tests no-op without Redis; run once WITH Redis up to confirm the Redis paths).

- [ ] **Step 2: Run with Redis to exercise the Redis adapters.**

Start Redis (`docker run --rm -p 6379:6379 redis`), then:
Run: `clojure -M:test:db/h2 --focus boundary.realtime.shell.adapters.redis-pubsub-test --focus boundary.realtime.shell.bus.redis-test`
Expected: PASS with real assertions exercised.

- [ ] **Step 3: All quality gates.**

Run: `bb check:fcis && bb check:ports && bb check:deps && clojure -M:clj-kondo --lint libs/realtime/src libs/realtime/test`
Expected: PASS.

- [ ] **Step 4: Placeholder-test gate (new tests must not use `(is true)`).**

Run: `bb check:placeholder-tests`
Expected: PASS.

- [ ] **Step 5: Final commit / ready for PR (gated on user approval).**

Summarize the branch state and ask the user whether to push / open a PR for BOU-85.

---

## Acceptance mapping (BOU-85)

| Acceptance criterion | Covered by |
|---|---|
| Redis adapter for both protocols behind existing ports | Task 9 (`RedisPubSubManager`) + Task 10 (`RedisMessageBus`); registry stays in-memory by design (ADR-035) |
| Config switch `:provider :in-memory\|:redis` | Task 11 (`module_wiring.clj`) |
| Cross-instance broadcast verified with 2 nodes | Task 7 (in-memory shared bus, CI) + Task 10 cross-bus Redis test |
| Document sticky-session / single-node workaround | Task 12 (`scaling.adoc`, AGENTS.md) |
