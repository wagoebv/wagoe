(ns wagoe.realtime.shell.module-wiring
  "Integrant wiring for the realtime module.

   Config key: :wagoe/realtime
     {:provider :in-memory | :redis
      ;; redis only:
      :host \"localhost\" :port 6379
      :password \"...\" :database 0        ; auth + db selection (production)
      :timeout 2000                        ; socket timeout ms
      :max-total 8 :max-idle 8 :min-idle 0 ; publish-pool sizing
      :channel \"wagoe:realtime:bus\"
      :key-prefix \"realtime\"
      :subscribe-timeout-ms 5000           ; await window for subscription to go live
      :jwt-verifier <IJWTVerifier ref>}

   The local connection registry is in-memory under BOTH providers (sockets are
   node-local). Only the pub/sub manager and the bus differ. Under :redis the
   component opens two Jedis pools — one for topic subscriptions (pub/sub
   manager) and one inside the bus for publish — both closed on halt.

   IMPORTANT: the web/WS server component MUST depend on :wagoe/realtime so
   that start-subscriber! has completed (subscription live) before any WebSocket
   connection is accepted."
  (:require [wagoe.realtime.ports :as ports]
            [wagoe.realtime.shell.service :as service]
            [wagoe.realtime.shell.connection-registry :as registry]
            [wagoe.realtime.shell.pubsub-manager :as atom-pubsub]
            [wagoe.realtime.shell.adapters.redis-pubsub :as redis-pubsub]
            [wagoe.realtime.shell.bus.in-memory :as in-memory-bus]
            [wagoe.realtime.shell.bus.redis :as redis-bus]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defmethod ig/init-key :wagoe/realtime
  [_ {:keys [provider jwt-verifier] :as config}]
  (log/info "Initializing realtime component" {:provider provider})
  (let [conn-registry (registry/create-in-memory-registry)
        [pubsub-manager bus pool]
        (case provider
          :redis
          ;; Shared pool builder (redis-bus/create-pool) honours auth, db,
          ;; timeout, and sizing from config, so the pub/sub manager pool is
          ;; configured identically to the bus's publish pool.
          (let [pool (redis-bus/create-pool config)]
            [(redis-pubsub/create-redis-pubsub-manager pool {:prefix (or (:key-prefix config) "realtime")})
             (redis-bus/create-redis-bus config)
             pool])

          ;; default :in-memory
          [(atom-pubsub/create-pubsub-manager)
           (in-memory-bus/create-in-memory-bus)
           nil])
        svc (service/create-realtime-service conn-registry jwt-verifier
                                             :pubsub-manager pubsub-manager
                                             :bus bus)]
    (log/info "Realtime component initialized" {:provider provider})
    {:service svc :registry conn-registry :pubsub-manager pubsub-manager
     :bus bus :pool pool}))

(defmethod ig/halt-key! :wagoe/realtime
  [_ {:keys [bus pool]}]
  (log/info "Halting realtime component")
  ;; Closeable buses (RedisMessageBus) own an internal pool that .close releases
  ;; after stopping the subscriber; the in-memory bus is not Closeable, so just
  ;; stop its subscriber. Closing the bus this way avoids leaking its pool.
  (when bus
    (try
      (if (instance? java.io.Closeable bus)
        (.close ^java.io.Closeable bus)
        (ports/stop-subscriber! bus))
      (catch Exception e (log/warn e "realtime bus shutdown failed"))))
  (when pool (try (.close pool) (catch Exception e (log/warn e "pool close failed")))))
