(ns wagoe.geo.shell.adapters.osm
  "OpenStreetMap Nominatim geocoding adapter.

   Rate limit: 1 request per second (OSM usage policy requires this).
   No API key required. A descriptive User-Agent header is mandatory.

   Usage:
     (def adapter (create-nominatim-adapter {:user-agent \"MyApp/1.0 (contact@example.com)\"}))
     (geocode adapter {:postcode \"1012 JS\" :city \"Amsterdam\"})"
  (:require [wagoe.geo.ports :as ports]
            [wagoe.geo.shell.adapters.support :as support]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-base-url "https://nominatim.openstreetmap.org")
(def ^:private default-min-interval-ms 1000)

;; =============================================================================
;; Response parsing
;; =============================================================================

(defn- parse-result
  "Parse a Nominatim result into a GeoResult. clj-http's `:as :json` keywordizes
   keys; Nominatim sends lat/lon as strings."
  [{:keys [lat lon display_name address]}]
  {:lat               (parse-double lat)
   :lng               (parse-double lon)
   :formatted-address display_name
   :postcode          (:postcode address)
   :city              (or (:city address) (:town address) (:village address))
   :country           (:country address)
   :provider          :openstreetmap
   :cached?           false})

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
        (support/enforce-rate-limit! (:last-request-ms this) (:min-interval-ms this))
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
              (support/parse-response :openstreetmap parse-result (first results))
              (do
                (log/warn "OSM geocode non-200 response" {:status status})
                nil)))
          (catch Exception e
            (log/error e "OSM geocode request failed" {:q q})
            nil)))))

  (reverse-geocode [this point]
    (support/enforce-rate-limit! (:last-request-ms this) (:min-interval-ms this))
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
          ;; No address here is a 200 with {"error": "Unable to geocode"}.
          (when-not (:error body)
            (support/parse-response :openstreetmap parse-result body))
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
     :min-interval-ms  - Minimum ms between requests (default and floor on the public server: 1000)

   Returns:
     NominatimAdapter implementing GeoProviderProtocol"
  [{:keys [user-agent base-url min-interval-ms]
    :or   {base-url         default-base-url
           min-interval-ms  default-min-interval-ms}}]
  {:pre [(string? user-agent)]}
  ;; The public server's policy caps clients at 1 req/s; a self-hosted one may go faster.
  (let [interval (if (= base-url default-base-url)
                   (max min-interval-ms default-min-interval-ms)
                   min-interval-ms)]
    (->NominatimAdapter base-url (atom 0) interval user-agent)))
