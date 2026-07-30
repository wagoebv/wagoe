(ns wagoe.geo.shell.adapters.osm
  "OpenStreetMap Nominatim geocoding adapter.

   Rate limit: 1 request per second (OSM usage policy requires this).
   No API key required. A descriptive User-Agent header is mandatory.

   Usage:
     (def adapter (create-nominatim-adapter {:user-agent \"MyApp/1.0 (contact@example.com)\"}))
     (geocode adapter {:postcode \"1012 JS\" :city \"Amsterdam\"})"
  (:require [wagoe.geo.ports :as ports]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-base-url "https://nominatim.openstreetmap.org")
(def ^:private default-min-interval-ms 1000)

;; =============================================================================
;; Rate limiting helpers
;; =============================================================================

(defn- enforce-rate-limit!
  "Sleep if less than `min-interval-ms` have passed since the last request."
  [last-request-ms min-interval-ms]
  (let [now     (System/currentTimeMillis)
        elapsed (- now @last-request-ms)
        wait    (- min-interval-ms elapsed)]
    (when (pos? wait)
      (Thread/sleep wait))
    (reset! last-request-ms (System/currentTimeMillis))))

;; =============================================================================
;; Response parsing
;; =============================================================================

(defn- parse-address-details
  "Extract postcode, city, and country from Nominatim addressdetails block."
  [address]
  {:postcode (or (get address "postcode") (get address :postcode))
   :city     (or (get address "city")
                 (get address "town")
                 (get address "village")
                 (get address :city))
   :country  (or (get address "country") (get address :country))})

(defn- parse-result
  "Parse a single Nominatim JSON result map into a GeoResult."
  [item]
  (when item
    (let [lat     (Double/parseDouble (str (get item "lat")))
          lng     (Double/parseDouble (str (get item "lon")))  ; OSM uses "lon"
          address (get item "address" {})]
      (merge {:lat      lat
              :lng      lng
              :formatted-address (get item "display_name")
              :provider :openstreetmap
              :cached?  false}
             (parse-address-details address)))))

;; =============================================================================
;; Adapter record
;; =============================================================================

(defrecord NominatimAdapter [base-url last-request-ms min-interval-ms user-agent])

(extend-protocol ports/GeoProviderProtocol
  NominatimAdapter

  (geocode [this query]
    (let [{:keys [address postcode city country]} query
          q (str/join ", "
                      (remove nil? [address postcode city
                                    (or country "Netherlands")]))]
      (when (not (str/blank? q))
        (enforce-rate-limit! (:last-request-ms this) (:min-interval-ms this))
        (log/debug "OSM geocode request" {:q q})
        (try
          (let [response (http/get (str (:base-url this) "/search")
                                   {:query-params     {"q"              q
                                                       "format"         "json"
                                                       "limit"          "1"
                                                       "addressdetails" "1"}
                                    :headers          {"User-Agent" (:user-agent this)}
                                    :as               :json
                                    :coerce           :always
                                    :throw-exceptions false})
                status   (:status response)
                results  (:body response)]
            (if (= 200 status)
              (parse-result (first results))
              (do
                (log/warn "OSM geocode non-200 response" {:status status})
                nil)))
          (catch Exception e
            (log/error e "OSM geocode request failed" {:q q})
            nil)))))

  (reverse-geocode [this point]
    (enforce-rate-limit! (:last-request-ms this) (:min-interval-ms this))
    (log/debug "OSM reverse-geocode request" point)
    (try
      (let [response (http/get (str (:base-url this) "/reverse")
                               {:query-params     {"lat"    (str (:lat point))
                                                   "lon"    (str (:lng point))
                                                   "format" "json"
                                                   "addressdetails" "1"}
                                :headers          {"User-Agent" (:user-agent this)}
                                :as               :json
                                :coerce           :always
                                :throw-exceptions false})
            status   (:status response)
            body     (:body response)]
        (if (= 200 status)
          (parse-result body)
          (do
            (log/warn "OSM reverse-geocode non-200 response" {:status status})
            nil)))
      (catch Exception e
        (log/error e "OSM reverse-geocode request failed" point)
        nil))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-nominatim-adapter
  "Create an OpenStreetMap Nominatim adapter.

   Config keys:
     :user-agent       - Descriptive User-Agent string (REQUIRED by OSM policy)
     :base-url         - Override base URL (default: https://nominatim.openstreetmap.org)
     :min-interval-ms  - Minimum ms between requests (default: 1000)

   Returns:
     NominatimAdapter implementing GeoProviderProtocol"
  [{:keys [user-agent base-url min-interval-ms]
    :or   {base-url         default-base-url
           min-interval-ms  default-min-interval-ms}}]
  {:pre [(string? user-agent)]}
  (->NominatimAdapter base-url (atom 0) min-interval-ms user-agent))
