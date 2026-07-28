(ns wagoe.geo.shell.module-wiring
  "Integrant wiring for the geo module.

   Config key: :wagoe/geo-service

   Example (single provider, no caching):
     :wagoe/geo-service
     {:provider  :openstreetmap
      :user-agent \"MyApp/1.0 (contact@example.com)\"}

   Example (Google with DB cache):
     :wagoe/geo-service
     {:provider   :google
      :api-key    #env WAG_GEO_API_KEY
      :cache-ttl  86400
      :db         #ig/ref :wagoe/db}

   Example (fallback chain):
     :wagoe/geo-service
     {:provider   [:openstreetmap :google]
      :api-key    #env WAG_GEO_API_KEY
      :cache-ttl  86400
      :db         #ig/ref :wagoe/db}"
  (:require [wagoe.geo.shell.adapters.osm :as osm]
            [wagoe.geo.shell.adapters.google :as google]
            [wagoe.geo.shell.adapters.mapbox :as mapbox]
            [wagoe.geo.shell.cache :as cache]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; Provider construction helpers
;; =============================================================================

(defn- build-provider
  "Construct a single provider adapter from a keyword + config."
  [provider-kw {:keys [api-key user-agent rate-limit]}]
  (let [min-ms (when rate-limit (long (/ 1000 rate-limit)))]
    (case provider-kw
      :openstreetmap
      (osm/create-nominatim-adapter
       (cond-> {:user-agent (or user-agent "wagoe-geo/1.0 (https://github.com/wagoebv/wagoe)")}
         min-ms (assoc :min-interval-ms min-ms)))

      :google
      (google/create-google-adapter
       (cond-> {:api-key api-key}
         min-ms (assoc :min-interval-ms min-ms)))

      :mapbox
      (mapbox/create-mapbox-adapter
       (cond-> {:api-key api-key}
         min-ms (assoc :min-interval-ms min-ms)))

      (throw (ex-info "Unknown geo provider" {:provider provider-kw})))))

(defn- build-providers
  "Return a vector of provider adapters from a single keyword or a vector."
  [provider config]
  (if (vector? provider)
    (mapv #(build-provider % config) provider)
    [(build-provider provider config)]))

;; =============================================================================
;; Integrant lifecycle
;; =============================================================================

(defmethod ig/init-key :wagoe/geo-service
  [_ {:keys [provider db cache-ttl] :as config}]
  (log/info "Initializing geo service" {:provider provider})
  (let [providers (build-providers provider config)
        geo-cache (when db
                    (cache/create-db-geo-cache db (or cache-ttl 86400)))]
    (log/info "Geo service initialized"
              {:providers (count providers) :cache? (boolean geo-cache)})
    {:providers providers
     :cache     geo-cache}))

(defmethod ig/halt-key! :wagoe/geo-service
  [_ _]
  (log/info "Halting geo service")
  nil)
