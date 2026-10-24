(ns wagoe.geo.shell.module-wiring
  "Integrant wiring for the geo module.

   Config key: :wagoe/geo-service

   Example (single provider, no caching):
     :wagoe/geo-service
     {:provider  :openstreetmap
      :user-agent \"MyApp/1.0 (contact@example.com)\"}

   Example (Dutch addresses from postcode + house number, no key):
     :wagoe/geo-service
     {:provider :pdok}

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
      :db         #ig/ref :wagoe/db}

   :timeout-ms (default 3000) bounds each provider's connect and read.
   The component is a service/GeoService; a call can keep to some of the
   providers with {:only #{:pdok}}."
  (:require [wagoe.geo.shell.adapters.osm :as osm]
            [wagoe.geo.shell.adapters.google :as google]
            [wagoe.geo.shell.adapters.mapbox :as mapbox]
            [wagoe.geo.shell.adapters.pdok :as pdok]
            [wagoe.geo.shell.cache :as cache]
            [wagoe.geo.shell.service :as service]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; Provider construction helpers
;; =============================================================================

(defn- build-provider
  "Construct a single provider adapter from a keyword + config."
  [provider-kw {:keys [api-key user-agent rate-limit timeout-ms]}]
  (let [min-ms (when rate-limit (long (/ 1000 rate-limit)))
        opts   (cond-> {}
                 min-ms     (assoc :min-interval-ms min-ms)
                 timeout-ms (assoc :timeout-ms timeout-ms))]
    (case provider-kw
      :openstreetmap
      (osm/create-nominatim-adapter (assoc opts :user-agent user-agent))

      :google
      (google/create-google-adapter (assoc opts :api-key api-key))

      :mapbox
      (mapbox/create-mapbox-adapter (assoc opts :api-key api-key))

      :pdok
      (pdok/create-pdok-adapter opts)

      (throw (ex-info "Unknown geo provider" {:type :unknown-provider :provider provider-kw})))))

(defn- provider-names
  "The configured provider keywords: a single keyword or a vector."
  [provider]
  (if (vector? provider) provider [provider]))

;; =============================================================================
;; Integrant lifecycle
;; =============================================================================

(defmethod ig/init-key :wagoe/geo-service
  [_ {:keys [provider db cache-ttl] :as config}]
  (log/info "Initializing geo service" {:provider provider})
  (let [names     (provider-names provider)
        providers (mapv #(build-provider % config) names)
        geo-cache (when db
                    (cache/create-db-geo-cache db (or cache-ttl 86400)))]
    (log/info "Geo service initialized"
              {:providers names :cache? (boolean geo-cache)})
    (service/map->GeoService {:providers      providers
                              :provider-names names
                              :cache          geo-cache})))

(defmethod ig/halt-key! :wagoe/geo-service
  [_ _]
  (log/info "Halting geo service")
  nil)
