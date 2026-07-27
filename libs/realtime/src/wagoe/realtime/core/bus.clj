(ns wagoe.realtime.core.bus
  "Pure constructors for cross-instance routing envelopes.

   An envelope is plain data describing WHERE a message should go; the bus
   transports it and a node-local delivery-fn resolves :route against the
   local registry. No I/O here.")

(defn user-envelope       [user-id message] {:route :user       :target user-id   :message message})
(defn role-envelope       [role message]    {:route :role       :target role      :message message})
(defn broadcast-envelope  [message]         {:route :broadcast  :target nil       :message message})
(defn connection-envelope [conn-id message] {:route :connection :target conn-id   :message message})
(defn topic-envelope      [topic message]   {:route :topic      :target topic     :message message})
