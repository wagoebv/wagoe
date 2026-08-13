(ns wagoe.events.core.event
  "The event envelope, and what can be said about one without touching a broker.

   An event crossing a process boundary carries more than its payload: which
   module emitted it, which request caused it, and which tenant it belongs to.
   The first is for the reader of a log, the second keeps one correlation id
   across an asynchronous hop the way the RPC adapter does across a synchronous
   one (BOU-90), and the third is not optional — an event delivered to the
   wrong tenant is a data leak, not a bug in ordering.

   FC/IS: pure. Nothing here connects to anything."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Envelope
;; =============================================================================

(def envelope-schema
  "Malli schema for an event.

   `:id` is assigned by the publisher rather than the broker, so a consumer can
   recognise a redelivery. At-least-once delivery means the same event arrives
   twice sooner or later, and a consumer that cannot tell has no way to be
   idempotent."
  [:map {:closed true}
   [:id :string]
   [:type :keyword]
   [:source :keyword]
   [:payload [:maybe :any]]
   [:published-at inst?]
   [:correlation-id {:optional true} [:maybe :string]]
   [:tenant-id {:optional true} [:maybe :string]]])

(defn topic-problem
  "Why `topic` cannot be published to, or nil.

   Topics become stream names, and a broker will happily create one called
   `nil` or `\"\"`. The publish succeeds, nothing consumes it, and the events
   are gone — so this is a refusal rather than a warning."
  [topic]
  (cond
    (not (or (keyword? topic) (string? topic)))
    (str "Topic must be a keyword or string, got " (pr-str (type topic)))

    (str/blank? (name topic))
    "Topic must not be blank"))

(defn event
  "Build an event of `type` from `source` carrying `payload`.

   `id` and `published-at` are supplied rather than generated: this namespace
   is pure, and a random id or a clock reading here would make every test of it
   untestable. The shell passes them in — see `wagoe.events.shell.publisher`."
  [{:keys [id type source payload published-at correlation-id tenant-id]}]
  (cond-> {:id           (str id)
           :type         (keyword type)
           :source       (keyword source)
           :payload      payload
           :published-at published-at}
    correlation-id (assoc :correlation-id (str correlation-id))
    tenant-id      (assoc :tenant-id (str tenant-id))))

(defn event-problem
  "Why `event` cannot be published, or nil.

   Checked before it reaches a broker, because an event that is malformed on
   the wire fails at the consumer — in another process, minutes later, with
   nothing to say where it came from."
  [{:keys [id type source published-at] :as event}]
  (cond
    (not (map? event))          "Event must be a map"
    (str/blank? (str id))       "Event must have an :id, so a consumer can recognise a redelivery"
    (not (keyword? type))       "Event must have a keyword :type"
    (not (keyword? source))     "Event must have a keyword :source, naming the module that emitted it"
    (not (inst? published-at))  "Event must have a :published-at instant"))

;; =============================================================================
;; Delivery
;; =============================================================================

(defn redelivery?
  "Whether `event` has been seen before, given the ids already handled.

   At-least-once is what a broker can offer; exactly-once is what a consumer
   builds on top by keeping this set. Here so that it is testable and so the
   documentation for it has somewhere to live, rather than being a paragraph in
   an adapter."
  [seen-ids event]
  (contains? (set seen-ids) (:id event)))

(defn matches-tenant?
  "Whether `event` may be handled by a consumer scoped to `tenant-id`.

   An event with no tenant is a system event and goes to everyone. An event
   with one goes only to that tenant — a subscriber must never see another
   tenant's events because it happened to be listening to the same topic."
  [tenant-id event]
  (let [event-tenant (:tenant-id event)]
    (or (nil? event-tenant)
        (= (str tenant-id) event-tenant))))
