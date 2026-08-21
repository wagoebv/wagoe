(ns wagoe.cache.shell.module-wiring
  "Integrant wiring for the cache module.

  Supports two providers selectable via config:
  - :redis      — distributed Redis-backed cache (production)
  - :in-memory  — local atom-based cache (dev / tests without Redis)

  Config key: :wagoe/cache
  Example (Redis):
    {:provider :redis
     :host \"localhost\" :port 6379
     :default-ttl 300
     :max-total 20 :max-idle 10 :min-idle 2}

  Example (in-memory):
    {:provider :in-memory
     :default-ttl 300
     :max-size 10000}"
  (:require [wagoe.cache.ports :as cache-ports]
            [wagoe.cache.shell.adapters.redis :as redis-cache]
            [wagoe.cache.shell.adapters.in-memory :as mem-cache]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defmethod ig/init-key :wagoe/cache
  [_ {:keys [provider] :as config}]
  (log/info "Initializing cache component" {:provider provider})
  (let [cache (case provider
                :redis
                (let [pool (redis-cache/create-redis-pool config)]
                  (redis-cache/create-redis-cache pool config))

                :in-memory
                (mem-cache/create-in-memory-cache config)

                (do
                  (log/warn "Unknown cache provider, falling back to in-memory" {:provider provider})
                  (mem-cache/create-in-memory-cache config)))]
    (log/info "Cache component initialized" {:provider provider})
    cache))

(defmethod ig/halt-key! :wagoe/cache
  [_ cache]
  (log/info "Halting cache component")
  (try
    (when (satisfies? cache-ports/ICacheManagement cache)
      (cache-ports/close! cache))
    (catch Exception e
      (log/warn e "Error while closing cache"))))

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   The cache is one component, but the HTTP handler takes a ref to it, so this
   cannot be the default passthrough the assembler applies to a settings-only
   module."
  [settings _ctx]
  {:components {:wagoe/cache settings}
   :http       {:cache (ig/ref :wagoe/cache)}})
