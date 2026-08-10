(ns wagoe.realtime.core.pubsub
  "Pure functions for pub/sub topic management.
  
   Implements topic-based message routing where connections can subscribe to
   topics and receive messages published to those topics. All functions are pure
   (no I/O, no side effects) following FC/IS pattern.
   
   Key Features:
   - Subscribe connection to topic (add to subscription set)
   - Unsubscribe connection from topic (remove from set)
   - Find all subscribers for topic (set intersection)
   - Clean up subscriptions on disconnect (remove all for connection)
   
   Data Structure:
   Subscriptions are represented as a map:
   {topic-name #{connection-id-1 connection-id-2 ...}}")

;; =============================================================================
;; Subscription Management (Pure Functions)
;; =============================================================================

(defn create-subscription
  "Create new subscription record (pure).
  
   Args:
     connection-id - UUID of WebSocket connection
     topic - Topic name string (e.g. 'order:123', 'user:456:messages')
   
   Returns:
     Subscription map with :connection-id, :topic, :created-at
   
   Example:
     (create-subscription #uuid \"123...\" \"order:456\")
     => {:connection-id #uuid \"123...\"
         :topic \"order:456\"
         :created-at #inst \"2025-01-01T12:00:00Z\"}"
  [connection-id topic now]
  {:connection-id connection-id
   :topic topic
   :created-at now})

(defn subscribe
  "Add connection to topic subscribers (pure).
  
   Subscriptions data structure is a map of topic to set of connection IDs:
   {\"order:123\" #{#uuid \"conn-1\" #uuid \"conn-2\"}}
   
   Args:
     subscriptions - Current subscriptions map
     connection-id - UUID of connection to subscribe
     topic - Topic name string
   
   Returns:
     Updated subscriptions map with connection added to topic
   
   Example:
     (subscribe {} #uuid \"123...\" \"order:456\")
     => {\"order:456\" #{#uuid \"123...\"}}
     
     (subscribe {\"order:456\" #{#uuid \"111...\"}}
                #uuid \"222...\"
                \"order:456\")
     => {\"order:456\" #{#uuid \"111...\" #uuid \"222...\"}}"
  [subscriptions connection-id topic]
  (update subscriptions topic
          (fn [subscribers]
            (if subscribers
              (conj subscribers connection-id)
              #{connection-id}))))

(defn unsubscribe
  "Remove connection from topic subscribers (pure).
  
   Args:
     subscriptions - Current subscriptions map
     connection-id - UUID of connection to unsubscribe
     topic - Topic name string
   
   Returns:
     Updated subscriptions map with connection removed from topic.
     If topic has no more subscribers, topic key is removed from map.
   
   Example:
     (unsubscribe {\"order:456\" #{#uuid \"111...\" #uuid \"222...\"}}
                  #uuid \"111...\"
                  \"order:456\")
     => {\"order:456\" #{#uuid \"222...\"}}
     
     (unsubscribe {\"order:456\" #{#uuid \"111...\"}}
                  #uuid \"111...\"
                  \"order:456\")
     => {}"
  [subscriptions connection-id topic]
  (let [updated-subscribers (disj (get subscriptions topic #{}) connection-id)]
    (if (empty? updated-subscribers)
      (dissoc subscriptions topic)
      (assoc subscriptions topic updated-subscribers))))

(defn unsubscribe-all
  "Remove connection from all topics (pure).
  
   Used when connection disconnects - clean up all subscriptions at once.
   Iterates through all topics and removes connection from each.
   
   Args:
     subscriptions - Current subscriptions map
     connection-id - UUID of connection to unsubscribe from all topics
   
   Returns:
     Updated subscriptions map with connection removed from all topics
   
   Example:
     (unsubscribe-all {\"order:456\" #{#uuid \"111...\" #uuid \"222...\"}
                       \"user:789\" #{#uuid \"111...\"}}
                      #uuid \"111...\")
     => {\"order:456\" #{#uuid \"222...\"}}"
  [subscriptions connection-id]
  (reduce-kv
   (fn [acc topic subscribers]
     (let [updated-subscribers (disj subscribers connection-id)]
       (if (empty? updated-subscribers)
         acc
         (assoc acc topic updated-subscribers))))
   {}
   subscriptions))

(defn get-subscribers
  "Get all connection IDs subscribed to topic (pure).
  
   Args:
     subscriptions - Current subscriptions map
     topic - Topic name string
   
   Returns:
     Set of connection UUIDs subscribed to topic (empty set if none)
   
   Example:
     (get-subscribers {\"order:456\" #{#uuid \"111...\" #uuid \"222...\"}}
                      \"order:456\")
     => #{#uuid \"111...\" #uuid \"222...\"}
     
     (get-subscribers {} \"nonexistent\")
     => #{}"
  [subscriptions topic]
  (get subscriptions topic #{}))

(defn get-connection-topics
  "Get all topics connection is subscribed to (pure).
  
   Args:
     subscriptions - Current subscriptions map
     connection-id - UUID of connection
   
   Returns:
     Set of topic name strings connection is subscribed to
   
   Example:
     (get-connection-topics {\"order:456\" #{#uuid \"111...\"}
                             \"user:789\" #{#uuid \"111...\" #uuid \"222...\"}}
                            #uuid \"111...\")
     => #{\"order:456\" \"user:789\"}"
  [subscriptions connection-id]
  (reduce-kv
   (fn [acc topic subscribers]
     (if (contains? subscribers connection-id)
       (conj acc topic)
       acc))
   #{}
   subscriptions))

(defn topic-count
  "Count number of active topics (pure).
  
   Args:
     subscriptions - Current subscriptions map
   
   Returns:
     Integer count of topics with at least one subscriber
   
   Example:
     (topic-count {\"order:456\" #{#uuid \"111...\"}
                   \"user:789\" #{#uuid \"222...\"}})
     => 2"
  [subscriptions]
  (count subscriptions))

(defn subscriber-count
  "Count total number of subscriptions across all topics (pure).
  
   Note: Same connection can be subscribed to multiple topics, so this
   may be greater than the number of unique connections.
   
   Args:
     subscriptions - Current subscriptions map
   
   Returns:
     Integer count of total subscriptions
   
   Example:
     (subscriber-count {\"order:456\" #{#uuid \"111...\" #uuid \"222...\"}
                        \"user:789\" #{#uuid \"111...\"}})
     => 3  ; connection 111 counted twice (subscribed to 2 topics)"
  [subscriptions]
  (reduce + 0 (map count (vals subscriptions))))

(defn topic-exists?
  "Check if topic has any subscribers (pure).
  
   Args:
     subscriptions - Current subscriptions map
     topic - Topic name string
   
   Returns:
     Boolean - true if topic has at least one subscriber
   
   Example:
     (topic-exists? {\"order:456\" #{#uuid \"111...\"}} \"order:456\")
     => true
     
     (topic-exists? {} \"nonexistent\")
     => false"
  [subscriptions topic]
  (contains? subscriptions topic))

(defn subscribed?
  "Check if connection is subscribed to topic (pure).
  
   Args:
     subscriptions - Current subscriptions map
     connection-id - UUID of connection
     topic - Topic name string
   
   Returns:
     Boolean - true if connection is subscribed to topic
   
   Example:
     (subscribed? {\"order:456\" #{#uuid \"111...\"}}
                  #uuid \"111...\"
                  \"order:456\")
     => true
     
     (subscribed? {\"order:456\" #{#uuid \"111...\"}}
                  #uuid \"222...\"
                  \"order:456\")
     => false"
  [subscriptions connection-id topic]
  (contains? (get subscriptions topic #{}) connection-id))

;; =============================================================================
;; Service subscriptions (in-process handlers)
;; =============================================================================
;;
;; Kept in their own map rather than mixed into `subscriptions`, which holds
;; connection ids. A handler is a function, not an id: it cannot be serialised,
;; relayed to another node, or compared for equality, so the two are different
;; kinds of subscriber even though they share a topic namespace.
;;
;; Shape: {subscription-id {:topic str :handler fn}}

(defn add-service-subscription
  "Register `handler` for `topic` under `subscription-id` (pure).

   Args:
     services        - Current service-subscription map
     subscription-id - UUID for this subscription
     topic           - Topic name string
     handler         - (fn [message] ...)

   Returns:
     Updated map"
  [services subscription-id topic handler]
  (assoc services subscription-id {:topic topic :handler handler}))

(defn remove-service-subscription
  "Remove `subscription-id` (pure). Unknown ids are a no-op.

   Returns:
     Updated map"
  [services subscription-id]
  (dissoc services subscription-id))

(defn service-handlers-for
  "Handlers subscribed to `topic` (pure).

   Order is unspecified: handlers are independent, and relying on registration
   order would make one subscriber's behaviour depend on another's startup.

   Returns:
     Vector of functions (may be empty)"
  [services topic]
  (into [] (comp (filter (fn [[_ sub]] (= topic (:topic sub))))
                 (map (fn [[_ sub]] (:handler sub))))
        services))
