(ns wagoe.geo.shell.adapters.mapbox
  "Mapbox Geocoding API adapter.

   Requires a valid Mapbox access token.
   Note: Mapbox returns coordinates as [longitude, latitude] (GeoJSON order).

   Usage:
     (def adapter (create-mapbox-adapter {:api-key \"pk.eyJ1...\"}))
     (geocode adapter {:address \"Damrak 1, Amsterdam\"})"
  (:require [wagoe.geo.ports :as ports]
            [wagoe.geo.shell.adapters.support :as support]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.net URLEncoder]))

(def ^:private default-base-url "https://api.mapbox.com")
(def ^:private default-min-interval-ms 100)

;; =============================================================================
;; Response parsing
;; =============================================================================

(defn- extract-context-field
  "Extract a value from Mapbox context array by id prefix."
  [context prefix]
  (some (fn [c]
          (when (str/starts-with? (:id c "") prefix)
            (:text c)))
        context))

(defn- parse-result
  "Parse a single Mapbox feature into a GeoResult.
   Mapbox returns coordinates as [lng, lat] (GeoJSON — longitude first)."
  [feature]
  (let [[lng lat] (get-in feature [:geometry :coordinates])
        context   (:context feature)
        address?  (some #{"address"} (:place_type feature))]
    (merge {:lat               lat
            :lng               lng
            :formatted-address (:place_name feature)
            :postcode          (extract-context-field context "postcode")
            :city              (extract-context-field context "place")
            :country           (extract-context-field context "country")
            :provider          :mapbox
            :cached?           false}
           ;; An address feature's text is its street and its address the number.
           (when address?
             (support/street-fields (:text feature) (:address feature) nil)))))

;; =============================================================================
;; Adapter record
;; =============================================================================

(defrecord MapboxAdapter [api-key base-url last-request-ms min-interval-ms])

(extend-protocol ports/GeoProviderProtocol
  MapboxAdapter

  (geocode [this query]
    (let [q (support/query-text query)]
      (when (not (str/blank? q))
        (support/enforce-rate-limit! (:last-request-ms this) (:min-interval-ms this))
        (log/debug "Mapbox geocode request" {:q q})
        (try
          ;; URLEncoder writes spaces as "+", which is literal in a path segment.
          (let [encoded  (str/replace (URLEncoder/encode q "UTF-8") "+" "%20")
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
              (support/parse-response :mapbox parse-result (first (:features body)))
              (do
                (log/warn "Mapbox geocode non-200 response" {:status status})
                nil)))
          (catch Exception e
            (log/error e "Mapbox geocode request failed" {:q q})
            nil)))))

  (reverse-geocode [this point]
    (support/enforce-rate-limit! (:last-request-ms this) (:min-interval-ms this))
    (log/debug "Mapbox reverse-geocode request" point)
    (try
      ;; Mapbox reverse geocode endpoint: /geocoding/v5/mapbox.places/{lng},{lat}.json
      ;; No limit: Mapbox rejects one on reverse unless a single `types` is given.
      ;; Features come most specific first.
      (let [url      (str (:base-url this)
                          "/geocoding/v5/mapbox.places/"
                          (:lng point) "," (:lat point) ".json")
            response (http/get url
                               {:query-params     {"access_token" (:api-key this)}
                                :as               :json
                                :coerce           :always
                                :throw-exceptions false})
            status   (:status response)
            body     (:body response)]
        (if (= 200 status)
          (support/parse-response :mapbox parse-result (first (:features body)))
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
