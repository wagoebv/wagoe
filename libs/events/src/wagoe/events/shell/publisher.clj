(ns wagoe.events.shell.publisher
  "Building and publishing an event, with the parts that are not pure.

   `wagoe.events.core.event/event` takes an id and a timestamp rather than
   making them, so it can be tested. This is where they come from — one small
   namespace, so that every other caller gets the same envelope instead of each
   inventing its own id scheme."
  (:require [wagoe.events.core.event :as event]
            [wagoe.events.ports :as ports]))

(defn build
  "An event of `type` from `source` carrying `payload`.

   `context` may carry `:correlation-id` and `:tenant-id`. Pass the request's
   correlation id: an asynchronous hop is where a trace is most easily lost,
   and an event that cannot be tied back to the request that caused it is the
   hardest kind of production question to answer."
  [type source payload & [context]]
  (event/event (merge {:id           (str (random-uuid))
                       :type         type
                       :source       source
                       :payload      payload
                       :published-at (java.time.Instant/now)}
                      (select-keys context [:correlation-id :tenant-id]))))

(defn emit!
  "Build and publish in one step, for the common case."
  [bus topic type source payload & [context]]
  (ports/publish! bus topic (build type source payload context)))
