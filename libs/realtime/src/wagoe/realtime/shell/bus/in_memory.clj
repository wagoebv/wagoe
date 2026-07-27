(ns wagoe.realtime.shell.bus.in-memory
  "In-memory message bus (single-process / test).

   Holds an atom vector of registered delivery-fns. `publish` invokes each
   synchronously and sums their returned counts. This is the default bus for
   single-node deployments and the vehicle for the 2-node cross-instance test
   (two services sharing one bus instance via the :bus option)."
  (:require [wagoe.realtime.ports :as ports]))

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
