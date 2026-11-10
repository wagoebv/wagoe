(ns wagoe.events.shell.publisher
  "Building and publishing an event, with the parts that are not pure.

   `wagoe.events.core.event/event` takes an id and a timestamp rather than
   making them, so it can be tested. This is where they come from — one small
   namespace, so that every other caller gets the same envelope instead of each
   inventing its own id scheme."
  (:require [wagoe.events.core.event :as event]
            [wagoe.events.ports :as ports]
            [wagoe.platform.database :as db]))

(defn build
  "An event of `type` from `source` carrying `payload`.

   `context` may carry `:correlation-id` and `:tenant-id`, which defaults to the
   tenant bound on this thread: an event without one goes to every tenant (BOU-947). Pass the request's
   correlation id: an asynchronous hop is where a trace is most easily lost,
   and an event that cannot be tied back to the request that caused it is the
   hardest kind of production question to answer."
  [type source payload & [context]]
  (event/event (merge {:id           (str (random-uuid))
                       :type         type
                       :source       source
                       :payload      payload
                       :published-at (java.time.Instant/now)}
                      (some->> db/*tenant-id* (hash-map :tenant-id))
                      ;; An explicit `:tenant-id nil` makes a system event, from inside a tenant too
                      (select-keys context [:correlation-id :tenant-id]))))

(defn emit!
  "Build and publish in one step, for the common case."
  [bus topic type source payload & [context]]
  (ports/publish! bus topic (build type source payload context)))

(defn emit-in-tx!
  "`emit!` through the outbox, inside the open transaction `tx` (BOU-448)."
  [outbox tx topic type source payload & [context]]
  (ports/publish-in-tx! outbox tx topic (build type source payload context)))

(defn subscribe-for-tenant!
  "`subscribe!` for one tenant's events, and those of no tenant, from code that
   runs outside that tenant's binding (BOU-947)."
  [bus tenant-id topic handler]
  (ports/subscribe! bus topic (event/for-tenant tenant-id handler)))
