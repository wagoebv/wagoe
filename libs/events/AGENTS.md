# wagoe-events — Event Bus

Asynchronous, cross-process communication between modules.

The synchronous seam between modules is `ports.clj`, and across processes the
remote-port adapter (BOU-90). This is the other half: a publisher does not know
who is listening, does not wait, and is unaffected if a consumer is down.

---

## When to use this, and when not

| Want | Use |
|---|---|
| An answer | A port. Synchronous, in-process, or the remote-port adapter across processes. |
| To tell others something happened | This. |
| Work done later, reliably, by this app | `wagoe-jobs`. A job queue is not an event bus: it has one worker per job, not N independent subscribers. |
| To push to a browser | `wagoe-realtime`. |

An event is a statement of fact in the past tense — `:order/placed`, not
`:order/place`. If the publisher cares what the subscriber does about it, it
wants a port call, not an event.

---

## Quick start

```clojure
(require '[wagoe.events.ports :as events]
         '[wagoe.events.shell.publisher :as publisher])

;; Publish — pass the request's context so the trace survives the hop
(publisher/emit! bus :orders :order/placed :orders
                 {:order-id 7}
                 {:correlation-id (:correlation-id request)
                  :tenant-id      (:tenant-id request)})

;; Subscribe
(def sub (events/subscribe! bus :orders
                            (fn [event]
                              (when (= :order/placed (:type event))
                                (send-confirmation! (:payload event))))))

(events/unsubscribe! bus sub)
```

### Configuration

```clojure
;; config.edn — under :active
:wagoe/events
{:provider :redis-streams
 :host     #env REDIS_HOST
 :port     #long #or [#env REDIS_PORT 6379]
 :password #env REDIS_PASSWORD
 :group    "my-app"}          ; see "Consumer groups" below

;; test / single process
:wagoe/events
{:provider :in-memory}
```

Requires `[wagoe.events.shell.module-wiring]` in the namespace that builds your
Integrant config — the layer that emits a key registers it.

---

## Delivery semantics

**At-least-once.** An event is redelivered until a consumer acknowledges it, so
a consumer that crashes mid-handler sees it again on restart. **Consumers must
be idempotent.** `:id` on the envelope is what makes that possible: it is
assigned by the publisher, so it is stable across redeliveries, unlike the
broker's own entry id.

```clojure
(defn handle [seen event]
  (when-not (wagoe.events.core.event/redelivery? @seen event)
    (swap! seen conj (:id event))
    (do-the-work event)))
```

Everything follows from where the acknowledgement happens: the Redis adapter
acks *after* the handler returns. Acking before would make delivery
at-most-once, and a consumer crash would lose the event with nothing to show
for it.

**Ordering** is per topic, not global. Two topics have no relative order.

**A throwing handler does not lose the event** — it is logged and the entry is
left unacknowledged, so it comes back.

---

## Consumer groups

`:group` is the unit of "who has seen what". Every event goes to *exactly one*
member of a group, and to *every* group.

- Three replicas of one service sharing a group → the work is split between
  them. This is what you want for scaling a consumer.
- Two different services needing every event → **two groups**. Putting them in
  one means each event is handled by one service and not the other, which looks
  like random message loss.

Name the group after the service, not the topic.

---

## Adapters

| Provider | Crosses processes | History | Use |
|---|---|---|---|
| `:redis-streams` | yes | yes, within stream retention | production, multi-process |
| `:in-memory` | no | bounded buffer, dies with the process | development, tests |

Redis **Streams**, not Redis pub/sub. Pub/sub is fire-and-forget: a subscriber
that is restarting when an event is published never learns it happened, and
cannot find out afterwards. A stream keeps its entries and tracks what has not
been acknowledged, which is what makes at-least-once possible at all.

The in-memory adapter delivers asynchronously even though it need not, so that
code written against it does not acquire an assumption — "the handler has run by
the time `publish!` returns" — that silently fails against Redis.

---

## Pitfalls

**Blocking in a handler.** Handlers run on the bus's thread. Work that can block
belongs on a job queue, or one slow consumer stops the topic for that process.

**Expecting exactly-once.** No broker offers it. Build idempotency on `:id`.

**Tenant leakage.** A subscriber must not see another tenant's events because it
listens to the same topic. `core.event/matches-tenant?` is the check; an event
with no `:tenant-id` is a system event and goes to everyone.

**Unbounded streams.** `:max-len` trims approximately (default 10 000 entries).
A stream with no bound is a disk leak that appears in the longest-running
deployment first.

**Publish failures are returned, not thrown.** A failed analytics event should
not roll back the transaction that produced it, so the caller decides:

```clojure
(let [result (publisher/emit! bus :analytics :page/viewed :web {...})]
  (when (:error result)
    (log/warn "analytics event dropped" result)))   ; carry on
```

---

## Testing

`clojure -M:test :events`

The unit tests need nothing. The Redis Streams tests skip themselves when no
Redis is reachable on `localhost:6379`:

```bash
docker run -d -p 6379:6379 redis:7-alpine
clojure -M:test :events
```

One of them starts a **second JVM** and has it publish, because two adapters in
one process share a heap — they prove the wire format and the consumer group,
and would keep passing if the whole thing depended on something process-local.

---

## Structure

```
libs/events/src/wagoe/events/
├── core/event.clj                    # pure: envelope, validation, redelivery, tenancy
├── ports.clj                         # IEventPublisher, IEventSubscriber, IEventHistory
└── shell/
    ├── publisher.clj                 # id + clock, then publish
    ├── module_wiring.clj             # :wagoe/events
    └── adapters/{in_memory,redis_streams}.clj
```

Three protocols rather than one: a module that only emits events should not
depend on subscription machinery it never calls, and not every backend can
replay history.
