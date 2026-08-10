(ns wagoe.realtime.shell.delivery
  "Node-local delivery: resolve a routing envelope to the local node's ws
   adapters and send. Built as a closure over the local registry + pubsub
   manager; registered with a message bus via start-subscriber!. Never calls
   service send methods (no re-publish recursion)."
  (:require [wagoe.realtime.ports :as ports]
            [clojure.tools.logging :as log]))

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

(defn- service-handlers-for
  "In-process handlers subscribed to a :topic envelope on this node.

   Only topics have them — a service handler subscribes to a topic, not to a
   user or a role. Registered per node, so this returns the handlers that live
   in this JVM; under the :redis provider each node invokes its own, which is
   how a handler fires exactly once for a message published anywhere."
  [pubsub-manager {:keys [route target]}]
  (if (and pubsub-manager (= :topic route))
    (ports/get-topic-service-handlers pubsub-manager target)
    []))

(defn- deliver-to-handlers!
  "Invoke each handler with `message`; return how many ran.

   Guarded individually, for the same reason sockets are: a subscriber that
   throws must not stop the ones after it, and must not stop the sockets."
  [handlers message]
  (reduce (fn [n h]
            (try
              (h message)
              (catch Exception e
                (log/warn e "realtime service subscriber threw")))
            (inc n))
          0
          handlers))

(defn make-delivery-fn
  "Return (fn [envelope] -> int): deliver the envelope's :message to every open
   local adapter it resolves to, and to every in-process handler subscribed to
   it; return how many recipients there were.

   The count includes service handlers. A topic with three handlers and no open
   sockets returns 3, not 0 — `publish-to-topic` returning zero while three
   subscribers ran would describe something other than what happened."
  [registry pubsub-manager]
  (fn [{:keys [message] :as envelope}]
    (let [adapters (adapters-for registry pubsub-manager envelope)
          sockets  (reduce
                    (fn [n a]
                      ;; Guard each send so one bad/closing socket can't abort the
                      ;; rest of the fan-out (critical for broadcast).
                      ;; send-message adapters already swallow internally, but a
                      ;; registry holding a faulty adapter must not break
                      ;; delivery to healthy ones.
                      (if (ports/open? a)
                        (do (try
                              (ports/send-message a message)
                              (catch Exception e
                                (log/warn e "realtime delivery to a socket failed")))
                            (inc n))
                        n))
                    0
                    adapters)]
      (+ sockets
         (deliver-to-handlers! (service-handlers-for pubsub-manager envelope) message)))))
