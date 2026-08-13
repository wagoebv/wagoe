(ns wagoe.events.ports
  "The event bus contract.

   The synchronous seam between modules is `ports.clj` plus, across processes,
   the remote-port adapter (BOU-90). This is the asynchronous one: a publisher
   does not know who is listening, does not wait, and is not affected if a
   consumer is down.

   Two protocols rather than one, because publishing and consuming have
   different lifecycles. A module that only emits events needs no consumer
   group, no polling thread and no shutdown; making it depend on a protocol
   full of subscription machinery would be a lie about what it uses.")

(defprotocol IEventPublisher
  "Emit events. Implementations must not block on delivery."

  (publish! [this topic event]
    "Publish `event` to `topic`.

     Returns the broker's id for the event, or `{:error {:type ... }}`. Errors
     are returned rather than thrown so a publisher can decide — a failed
     analytics event should not roll back the transaction that produced it,
     while a failed audit event probably should.

     At-least-once: a publish that appears to fail may still have been
     delivered. Retrying is safe only if consumers are idempotent, which is
     what `:id` on the envelope is for."))

(defprotocol IEventSubscriber
  "Consume events. Implementations own a polling thread or equivalent."

  (subscribe! [this topic handler]
    "Call `handler` with each event published to `topic` from now on.

     Returns a subscription that `unsubscribe!` accepts. `handler` runs on the
     bus's own thread: work that can block belongs on a job queue, or one slow
     consumer stops the topic for everyone in that process.

     A handler that throws must not lose the event or kill the subscription —
     implementations log and continue, and the event stays unacknowledged where
     the backend supports it.")

  (unsubscribe! [this subscription]
    "Stop delivering to a subscription. Idempotent."))

(defprotocol IEventHistory
  "Read events that were published before this consumer existed.

   Separate from `IEventSubscriber` because not every backend can do it: an
   in-memory bus keeps a bounded buffer, Redis Streams keeps what its retention
   allows, and a plain pub/sub backend can offer nothing at all. A module that
   needs replay should depend on this protocol and fail to start without it,
   rather than discovering at runtime that its backend forgets."

  (history [this topic] [this topic opts]
    "Events previously published to `topic`, oldest first.

     `opts` may carry `:limit` and `:since` (an instant). Returns a sequence,
     which may be empty — an empty history and no history are different things,
     and a backend that cannot answer should not implement this protocol."))
