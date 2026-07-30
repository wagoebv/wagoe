(ns wagoe.realtime.shell.pubsub-manager
  "In-memory pub/sub topic management (imperative shell).
  
   Implements IPubSubManager protocol using atom-based subscription storage.
   Coordinates between pure pub/sub core functions and stateful subscription
   management.
   
   Single-server implementation - subscriptions stored in memory. For
   multi-server deployments, would need Redis-backed implementation (v0.2.0).
   
   Thread-safe via atom swap operations."
  (:require [wagoe.realtime.ports :as ports]
            [wagoe.realtime.core.pubsub :as pubsub-core]
            [wagoe.realtime.schema :as schema]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Atom-Based Pub/Sub Manager
;; =============================================================================

(defrecord AtomPubSubManager [subscriptions]
  ports/IPubSubManager

  (subscribe-to-topic [_this connection-id topic]
    ;; Validate inputs
    (when-not (schema/valid-topic? topic)
      (throw (ex-info "Invalid topic name"
                      {:type :validation-error
                       :topic topic
                       :errors (schema/explain-topic topic)})))
    
    ;; Update subscriptions (pure function wrapped in atom swap)
    (swap! subscriptions pubsub-core/subscribe connection-id topic)
    
    ;; Log subscription
    (log/debug "Connection subscribed to topic"
               {:connection-id connection-id
                :topic topic})
    
    nil)

  (unsubscribe-from-topic [_this connection-id topic]
    ;; Update subscriptions (pure function wrapped in atom swap)
    (swap! subscriptions pubsub-core/unsubscribe connection-id topic)
    
    ;; Log unsubscription
    (log/debug "Connection unsubscribed from topic"
               {:connection-id connection-id
                :topic topic})
    
    nil)

  (unsubscribe-from-all-topics [_this connection-id]
    ;; Get topics before unsubscribing (for logging)
    (let [topics (pubsub-core/get-connection-topics @subscriptions connection-id)]
      
      ;; Update subscriptions (pure function wrapped in atom swap)
      (swap! subscriptions pubsub-core/unsubscribe-all connection-id)
      
      ;; Log cleanup
      (log/debug "Connection unsubscribed from all topics"
                 {:connection-id connection-id
                  :topic-count (count topics)
                  :topics topics}))
    
    nil)

  (get-topic-subscribers [_this topic]
    ;; Read-only operation - no atom swap needed
    (pubsub-core/get-subscribers @subscriptions topic))

  (get-connection-subscriptions [_this connection-id]
    ;; Read-only operation
    (pubsub-core/get-connection-topics @subscriptions connection-id))

  (topic-count [_this]
    ;; Read-only operation
    (pubsub-core/topic-count @subscriptions))

  (subscription-count [_this]
    ;; Read-only operation
    (pubsub-core/subscriber-count @subscriptions)))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-pubsub-manager
  "Create new in-memory pub/sub manager.
  
   Uses atom for thread-safe subscription storage. All state changes go
   through pure core functions via atom swap.
   
   Returns:
     AtomPubSubManager record implementing IPubSubManager
   
   Example:
     (def pubsub-mgr (create-pubsub-manager))
     (ports/subscribe-to-topic pubsub-mgr conn-id \"order:123\")"
  []
  (->AtomPubSubManager (atom {})))

(defn create-pubsub-manager-with-state
  "Create pub/sub manager with initial state.
  
   Useful for testing or restoring from snapshot.
   
   Args:
     initial-subscriptions - Map of topic to set of connection IDs
   
   Returns:
     AtomPubSubManager with initial state
   
   Example:
     (def pubsub-mgr
       (create-pubsub-manager-with-state
         {\"order:123\" #{#uuid \"111...\" #uuid \"222...\"}}))"
  [initial-subscriptions]
  {:pre [(schema/valid-subscriptions? initial-subscriptions)]}
  (->AtomPubSubManager (atom initial-subscriptions)))
