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
{:provider       :redis-streams
 :host           #env REDIS_HOST
 :port           #long #or [#env REDIS_PORT 6379]
 :password       #env REDIS_PASSWORD
 :group          "my-app"     ; see "Consumer groups" below
 :max-deliveries 5            ; nil retries forever — see "Delivery semantics"
 :min-idle-ms    30000
 :max-len        10000}

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

Leaving an entry unacknowledged is only half of it — something has to offer it
again. Each poll cycle does three things, in order:

1. **Reclaim** entries idle longer than `:min-idle-ms` (default 30 s) from
   consumers that are not coming back. A consumer name is unique per
   subscription, so a restarted process polls under a new one and would
   otherwise never see what its predecessor left pending.
2. **Retry** this consumer's own unacknowledged entries.
3. **Wait** for new ones.

Reading only new entries — which is what an `XREADGROUP >` on its own does —
leaves a failed event pending forever: durable-looking, and never delivered.

**Ordering** is per topic, not global. Two topics have no relative order.

**A poison event does not stall the topic.** After `:max-deliveries` attempts
(default 5) the event is written to `<stream>:dead` and acknowledged, so
everything behind it moves on and the event is still there to inspect.
`:max-deliveries nil` retries forever, which is a choice rather than a stronger
guarantee: one permanently failing handler then blocks its topic.

---

## Consumer groups

`:group` splits work between **processes**. Every event goes to exactly one
member of a group, and to every group.

- Three replicas of one service sharing a group → the work is split between
  them. This is what you want for scaling a consumer.
- Two different services needing every event → **two groups**. Putting them in
  one means each event is handled by one service and not the other, which looks
  like random message loss.

Name the group after the service, not the topic.

Within a process it does not apply: every `subscribe!` on a topic receives
every event on it, as the port says and as the in-memory adapter does. The
Redis adapter runs one consumer per topic and fans out locally. A consumer per
subscription would put both handlers in the same group and hand each event to
one of them — two modules listening to the same topic would then each get
roughly half, at random.

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

**A failing handler still costs its neighbours a redelivery.** Every local
handler runs before the failure is raised, so none is skipped — but the entry
stays unacknowledged, so handlers that already succeeded see the event again
when it comes back. That is what at-least-once means, and why idempotency is
not optional.

**Expecting exactly-once.** No broker offers it. Build idempotency on `:id`.

**Tenant leakage.** A subscriber must not see another tenant's events because it
listens to the same topic. `core.event/matches-tenant?` is the check; an event
with no `:tenant-id` is a system event and goes to everyone.

**Unbounded streams.** `:max-len` trims approximately (default 10 000 entries).
A stream with no bound is a disk leak that appears in the longest-running
deployment first.

**Topics are whole keywords.** `:orders/placed` and `:billing/placed` are
different topics; the namespace is part of the stream name. Deriving it from
`(name topic)` collapsed them into one stream, which the in-memory adapter —
keying on the keyword — never did. Anything that must hold for both backends
belongs in `adapter_contract_test.clj`, which runs against each of them.

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
