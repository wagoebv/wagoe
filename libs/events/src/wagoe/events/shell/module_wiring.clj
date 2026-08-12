(ns wagoe.events.shell.module-wiring
  "Integrant wiring for the event bus.

   The layer that emits a key registers it (BOU-131), so an application that
   configures `:wagoe/events` requires this namespace and nothing in the
   framework needs to know the module exists."
  (:require [wagoe.events.shell.adapters.in-memory :as in-memory]
            [wagoe.events.shell.adapters.redis-streams :as redis-streams]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig])
  (:import (redis.clients.jedis JedisPool JedisPoolConfig)))

(defn- redis-pool
  [{:keys [host port timeout password database]}]
  (JedisPool. (JedisPoolConfig.)
              (or host "localhost")
              (int (or port 6379))
              (int (or timeout 2000))
              ;; Blank is not a password, and the database is honoured either
              ;; way — the same two mistakes the cache adapter made (BOU-89).
              ^String (when-not (str/blank? (str password)) password)
              ^int (int (or database 0))))

(defmethod ig/init-key :wagoe/events
  [_ {:keys [provider] :as config}]
  (log/info "Initializing event bus" {:provider provider})
  (let [bus (case provider
              :redis-streams (redis-streams/create-redis-streams-bus
                              (redis-pool config)
                              (select-keys config [:prefix :group :max-len]))
              :in-memory     (in-memory/create-in-memory-bus
                              (select-keys config [:history-limit]))
              (throw (ex-info (str "Unknown event bus provider: " (pr-str provider))
                              {:type :configuration-error
                               :known [:redis-streams :in-memory]})))]
    (log/info "Event bus initialized" {:provider provider})
    (assoc bus :wagoe.events/provider provider)))

(defmethod ig/halt-key! :wagoe/events
  [_ bus]
  (when bus
    (log/info "Stopping event bus")
    (case (:wagoe.events/provider bus)
      :redis-streams (redis-streams/stop! bus)
      :in-memory     (in-memory/stop! bus)
      nil)))
