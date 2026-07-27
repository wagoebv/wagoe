(ns wagoe.geo.shell.adapters.mapbox
  "Mapbox Geocoding API adapter.

   Requires a valid Mapbox access token.
   Note: Mapbox returns coordinates as [longitude, latitude] (GeoJSON order).

   Usage:
     (def adapter (create-mapbox-adapter {:api-key \"pk.eyJ1...\"}))
     (geocode adapter {:address \"Damrak 1, Amsterdam\"})"
  (:require [wagoe.geo.ports :as ports]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.net URLEncoder]))

(def ^:private default-base-url "https://api.mapbox.com")
(def ^:private default-min-interval-ms 100)

;; =============================================================================
;; Rate limiting helpers
;; =============================================================================

(defn- enforce-rate-limit!
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

(defn- extract-context-field
  "Extract a value from Mapbox context array by id prefix."
  [context prefix]
  (some (fn [c]
          (when (str/starts-with? (get c "id" "") prefix)
            (get c "text")))
        context))

(defn- parse-result
  "Parse a single Mapbox feature into a GeoResult.
   Mapbox returns coordinates as [lng, lat] (GeoJSON — longitude first)."
  [feature]
  (when feature
    (let [coords   (get-in feature ["geometry" "coordinates"])
          lng      (first coords)
          lat      (second coords)
          context  (get feature "context" [])]
      {:lat               lat
       :lng               lng
       :formatted-address (get feature "place_name")
       :postcode          (extract-context-field context "postcode")
       :city              (extract-context-field context "place")
       :country           (extract-context-field context "country")
       :provider          :mapbox
       :cached?           false})))

;; =============================================================================
;; Adapter record
;; =============================================================================

(defrecord MapboxAdapter [api-key base-url last-request-ms min-interval-ms])

(extend-protocol ports/GeoProviderProtocol
  MapboxAdapter

  (geocode [this query]
    (let [{:keys [address postcode city country]} query
          q (str/join ", "
                      (remove nil? [address postcode city
                                    (or country "Netherlands")]))]
      (when (not (str/blank? q))
        (enforce-rate-limit! (:last-request-ms this) (:min-interval-ms this))
        (log/debug "Mapbox geocode request" {:q q})
        (try
          (let [encoded  (URLEncoder/encode q "UTF-8")
                url      (str (:base-url this)
                              "/geocoding/v5/mapbox.places/"
                              encoded ".json")
                response (http/get url
                                   {:query-params     {"access_token" (:api-key this)
                                                       "limit"        "1"}
                                    :as               :json
                                    :coerce           :always
                                    :throw-exceptions false})
                status   (:status response)
                body     (:body response)]
            (if (= 200 status)
              (parse-result (first (get body "features")))
              (do
                (log/warn "Mapbox geocode non-200 response" {:status status})
                nil)))
          (catch Exception e
            (log/error e "Mapbox geocode request failed" {:q q})
            nil)))))

  (reverse-geocode [this point]
    (enforce-rate-limit! (:last-request-ms this) (:min-interval-ms this))
    (log/debug "Mapbox reverse-geocode request" point)
    (try
      ;; Mapbox reverse geocode endpoint: /geocoding/v5/mapbox.places/{lng},{lat}.json
      (let [url      (str (:base-url this)
                          "/geocoding/v5/mapbox.places/"
                          (:lng point) "," (:lat point) ".json")
            response (http/get url
                               {:query-params     {"access_token" (:api-key this)
                                                   "limit"        "1"}
                                :as               :json
                                :coerce           :always
                                :throw-exceptions false})
            status   (:status response)
            body     (:body response)]
        (if (= 200 status)
          (parse-result (first (get body "features")))
          (do
            (log/warn "Mapbox reverse-geocode non-200 response" {:status status})
            nil)))
      (catch Exception e
        (log/error e "Mapbox reverse-geocode request failed" point)
        nil))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-mapbox-adapter
  "Create a Mapbox Geocoding adapter.

   Config keys:
     :api-key         - Mapbox access token (required)
     :base-url        - Override base URL (default: https://api.mapbox.com)
     :min-interval-ms - Minimum ms between requests (default: 100)

   Returns:
     MapboxAdapter implementing GeoProviderProtocol"
  [{:keys [api-key base-url min-interval-ms]
    :or   {base-url        default-base-url
           min-interval-ms default-min-interval-ms}}]
  {:pre [(string? api-key)]}
  (->MapboxAdapter api-key base-url (atom 0) min-interval-ms))
