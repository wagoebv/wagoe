(ns wagoe.cache.shell.module-wiring
  "Integrant wiring for the cache module.

  Supports two providers selectable via config:
  - :redis   — distributed Redis-backed cache (production)
  - :memory  — local atom-based cache (dev / tests without Redis)

  Config key: :wagoe/cache
  Example (Redis):
    {:provider :redis
     :host \"localhost\" :port 6379
     :default-ttl 300
     :max-total 20 :max-idle 10 :min-idle 2}

  Example (in-process):
    {:provider :memory
     :default-ttl 300
     :max-size 10000}

  `:in-memory` is the pre-1.0 spelling and still works; see
  `normalize-provider`."
  (:require [wagoe.cache.ports :as cache-ports]
            [wagoe.cache.shell.adapters.redis :as redis-cache]
            [wagoe.cache.shell.adapters.in-memory :as mem-cache]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defn normalize-provider
  "The provider keyword in the vocabulary cache, realtime, events and jobs
   share. This module implements `:memory` | `:redis`; `:db` exists only where a
   module has a database adapter.

   `:in-memory` is accepted as `:memory` and warns. Removal no earlier than 2.0
   (BOU-436). nil keeps the documented default rather than failing: omitting
   `:provider` has always meant in-process."
  [provider]
  (case provider
    nil :memory
    :in-memory (do (log/warn "Cache :provider :in-memory is now spelled :memory")
                   :memory)
    provider))

(defmethod ig/init-key :wagoe/cache
  [_ {:keys [provider] :as config}]
  (log/info "Initializing cache component" {:provider provider})
  (let [provider (normalize-provider provider)
        cache (case provider
                :redis
                (let [pool (redis-cache/create-redis-pool config)]
                  (redis-cache/create-redis-cache pool config))

                :memory
                (mem-cache/create-in-memory-cache config)

                ;; Not a fallback. A typo used to produce an in-process cache
                ;; with a warning, so a production node configured for Redis
                ;; ran on a per-node atom and said so once, at info level.
                (throw (ex-info (str "Unknown cache provider: " (pr-str provider))
                                {:type     :unknown-provider
                                 :provider provider
                                 :known    [:memory :redis]})))]
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
