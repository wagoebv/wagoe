(ns wagoe.geo.shell.cache
  "DB-backed geocoding cache implementing GeoCacheProtocol.

   Uses a `geo_cache` table with a SHA-256 address hash as primary key.
   Run the migration in resources/boundary/geo/migrations/001-geo-cache.sql
   before using this component.

   TTL is enforced at lookup time by comparing `created_at` to `NOW() - cache-ttl`."
  (:require [wagoe.geo.ports :as ports]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql])
  (:import [java.time Instant]
           [java.sql Timestamp]))

;; =============================================================================
;; SQL helpers
;; =============================================================================

(defn- ttl-cutoff
  "Return a java.sql.Timestamp representing `now - ttl-seconds`."
  [ttl-seconds]
  (Timestamp/from (Instant/ofEpochSecond (- (.getEpochSecond (Instant/now)) ttl-seconds))))

(defn- row->geo-result
  "Convert a geo_cache DB row (unqualified lowercase keys) to a GeoResult map."
  [row]
  (when row
    {:lat               (double (:lat row))
     :lng               (double (:lng row))
     :formatted-address (:formatted_address row)
     :postcode          (:postcode row)
     :city              (:city row)
     :country           (:country row)
     :provider          (keyword (:provider row))
     :cached?           true}))

;; =============================================================================
;; DbGeoCache record
;; =============================================================================

(defrecord DbGeoCache [ds cache-ttl])

(extend-protocol ports/GeoCacheProtocol
  DbGeoCache

  (cache-lookup [this query-hash]
    (log/debug "geo cache lookup" {:hash query-hash})
    (try
      (let [cutoff (ttl-cutoff (:cache-ttl this))
            row    (first
                    (jdbc/execute! (:ds this)
                                   ["SELECT * FROM geo_cache WHERE address_hash = ? AND created_at > ?"
                                    query-hash cutoff]
                                   {:builder-fn rs/as-unqualified-lower-maps}))]
        (row->geo-result row))
      (catch Exception e
        (log/warn e "geo cache lookup failed" {:hash query-hash})
        nil)))

  (cache-store! [this query-hash result]
    (log/debug "geo cache store" {:hash query-hash :provider (:provider result)})
    (try
      (sql/insert! (:ds this) :geo_cache
                   {:address_hash      query-hash
                    :lat               (:lat result)
                    :lng               (:lng result)
                    :formatted_address (:formatted-address result)
                    :postcode          (:postcode result)
                    :city              (:city result)
                    :country           (:country result)
                    :provider          (name (:provider result))
                    :created_at        (Timestamp/from (Instant/now))}
                   {:return-keys false})
      nil
      (catch Exception e
        ;; INSERT may fail on duplicate key (race condition) — treat as non-fatal.
        ;; Other failures (DB down, connection lost) are logged at warn level.
        (if (or (re-find #"(?i)unique|duplicate|constraint" (.getMessage e))
                (re-find #"(?i)primary key" (.getMessage e)))
          (log/debug e "geo cache duplicate key (concurrent insert)" {:hash query-hash})
          (log/warn e "geo cache store failed (DB issue?)" {:hash query-hash}))
        nil))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-db-geo-cache
  "Create a DB-backed geo cache.

   Args:
     ds        - next.jdbc datasource or connection pool
     cache-ttl - TTL in seconds (default: 86400 = 24 hours)

   Returns:
     DbGeoCache implementing GeoCacheProtocol"
  ([ds]
   (create-db-geo-cache ds 86400))
  ([ds cache-ttl]
   (->DbGeoCache ds cache-ttl)))
