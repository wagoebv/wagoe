(ns wagoe.realtime.shell.connection-registry
  "In-memory connection registry implementation.
  
  Stores active WebSocket connections in an atom for single-server deployments.
  For multi-server scaling, swap with Redis-backed registry implementation.
  
  Registry Structure:
    {connection-id {:connection <Connection record>
                    :ws-adapter <IWebSocketConnection>}}
  
  Responsibilities (Shell/I/O):
  - Store and retrieve connection mappings (stateful atom)
  - Filter connections by user-id, role (uses core filtering functions)
  - Thread-safe updates (atom swap operations)"
  (:require [wagoe.realtime.ports :as ports]
            [wagoe.realtime.core.connection :as conn]))

;; =============================================================================
;; In-Memory Connection Registry
;; =============================================================================

(defrecord InMemoryConnectionRegistry [state] ; state is an atom of {connection-id {:connection ... :ws-adapter ...}}
  ports/IConnectionRegistry

  (register [_this connection-id connection ws-connection]
    (swap! state assoc connection-id {:connection connection
                                      :ws-adapter ws-connection})
    nil)

  (unregister [_this connection-id]
    (swap! state dissoc connection-id)
    nil)

  (find-by-user [_this user-id]
    ;; Use core filtering function for business logic
    (let [all-entries (vals @state)
          all-connections (map :connection all-entries)
          matching-connections (conn/filter-by-user all-connections user-id)
          matching-ids (set (map :id matching-connections))]
      ;; Return ws-adapters for matching connections
      (->> all-entries
           (filter #(contains? matching-ids (get-in % [:connection :id])))
           (mapv :ws-adapter))))

  (find-by-role [_this role]
    ;; Use core filtering function for business logic
    (let [all-entries (vals @state)
          all-connections (map :connection all-entries)
          matching-connections (conn/filter-by-role all-connections role)
          matching-ids (set (map :id matching-connections))]
      ;; Return ws-adapters for matching connections
      (->> all-entries
           (filter #(contains? matching-ids (get-in % [:connection :id])))
           (mapv :ws-adapter))))

  (all-connections [_this]
    ;; Return all ws-adapters
    (->> @state
         vals
         (mapv :ws-adapter)))

  (connection-count [_this]
    (count @state))

  (find-connection [_this connection-id]
    ;; Return Connection record (not ws-adapter) for inspection
    (get-in @state [connection-id :connection]))

  (find-adapters-by-ids [_this connection-ids]
    (let [snapshot @state]
      (into []
            (keep (fn [cid] (get-in snapshot [cid :ws-adapter])))
            connection-ids))))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-in-memory-registry
  "Create in-memory connection registry.
  
  Suitable for single-server deployments. For multi-server scaling,
  use create-redis-registry (to be implemented in future).
  
  Returns:
    InMemoryConnectionRegistry instance implementing IConnectionRegistry"
  []
  (->InMemoryConnectionRegistry (atom {})))
