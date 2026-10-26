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
      :cache?     true}

   Example (fallback chain):
     :wagoe/geo-service
     {:provider   [:openstreetmap :google]
      :api-key    #env WAG_GEO_API_KEY
      :cache-ttl  86400
      :cache?     true}

   :cache? true caches lookups in the geo_cache table, through the
   :wagoe/db-context the module wires itself. An explicit :db-ctx still works.

   :timeout-ms (default 3000) bounds each provider's connect and read.
   The component is a service/GeoService; a call can keep to some of the
   providers with {:only #{:pdok}}."
  (:require [wagoe.geo.shell.adapters.osm :as osm]
            [wagoe.geo.shell.adapters.google :as google]
            [wagoe.geo.shell.adapters.mapbox :as mapbox]
            [wagoe.geo.shell.adapters.pdok :as pdok]
            [wagoe.geo.shell.cache :as cache]
            [wagoe.geo.shell.service :as service]
            [wagoe.geo.schema :as schema]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [malli.core :as m]
            [malli.error :as me]))

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
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries. `:cache? true` wires `:wagoe/db-context`
   for the cache; it is opt-in because the cache needs the geo_cache table."
  [settings _ctx]
  {:components
   {:wagoe/geo-service (cond-> settings
                         (and (:cache? settings) (not (contains? settings :db-ctx)))
                         (assoc :db-ctx (ig/ref :wagoe/db-context)))}})

;; =============================================================================
;; Integrant lifecycle
;; =============================================================================

(def ^:private config-explainer (m/explainer schema/GeoConfig))

(defn- check-config!
  "Fails at boot, naming the bad keys, rather than on the first lookup."
  [config]
  (when-let [explanation (config-explainer config)]
    (throw (ex-info (str ":wagoe/geo-service config is invalid: " (pr-str (me/humanize explanation)))
                    {:type   :wagoe/geo-config-error
                     :errors (me/humanize explanation)}))))

(defn- cache-datasource
  "The datasource to cache in, from :db-ctx. :db, a bare datasource, is read
   until 2.0."
  [{:keys [db-ctx db]}]
  (cond
    db-ctx (:datasource db-ctx)

    db
    (do (log/warn ":wagoe/geo-service :db is deprecated and goes in 2.0; use :cache? true")
        (or (:datasource db) db))))

(defmethod ig/init-key :wagoe/geo-service
  [_ {:keys [provider cache-ttl] :as config}]
  (log/info "Initializing geo service" {:provider provider})
  (check-config! config)
  (let [names     (provider-names provider)
        providers (mapv #(build-provider % config) names)
        ds        (cache-datasource config)
        geo-cache (when ds
                    (cache/create-db-geo-cache ds (or cache-ttl 86400)))]
    (log/info "Geo service initialized"
              {:providers names :cache? (boolean geo-cache)})
    (service/map->GeoService {:providers      providers
                              :provider-names names
                              :cache          geo-cache})))

(defmethod ig/halt-key! :wagoe/geo-service
  [_ _]
  (log/info "Halting geo service")
  nil)
