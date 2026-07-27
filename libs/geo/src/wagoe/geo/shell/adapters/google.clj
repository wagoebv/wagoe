(ns wagoe.geo.shell.adapters.google
  "Google Maps Geocoding API adapter.

   Requires a valid Google Maps API key with the Geocoding API enabled.

   Usage:
     (def adapter (create-google-adapter {:api-key \"AIza...\"}))
     (geocode adapter {:address \"Damrak 1, Amsterdam\"})"
  (:require [wagoe.geo.ports :as ports]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-base-url "https://maps.googleapis.com")
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

(defn- extract-component
  "Extract an address component by type from Google's address_components."
  [components type-name]
  (some (fn [c]
          (when (some #(= type-name %) (get c "types" []))
            (get c "long_name")))
        components))

(defn- parse-result
  "Parse a single Google geocoding result into a GeoResult."
  [item]
  (when item
    (let [loc        (get-in item ["geometry" "location"])
          lat        (get loc "lat")
          lng        (get loc "lng")
          components (get item "address_components" [])]
      {:lat               lat
       :lng               lng
       :formatted-address (get item "formatted_address")
       :postcode          (extract-component components "postal_code")
       :city              (or (extract-component components "locality")
                              (extract-component components "administrative_area_level_2"))
       :country           (extract-component components "country")
       :provider          :google
       :cached?           false})))

;; =============================================================================
;; Adapter record
;; =============================================================================

(defrecord GoogleAdapter [api-key base-url last-request-ms min-interval-ms])

(extend-protocol ports/GeoProviderProtocol
  GoogleAdapter

  (geocode [this query]
    (let [{:keys [address postcode city country]} query
          q (str/join ", "
                      (remove nil? [address postcode city
                                    (or country "Netherlands")]))]
      (when (not (str/blank? q))
        (enforce-rate-limit! (:last-request-ms this) (:min-interval-ms this))
        (log/debug "Google geocode request" {:q q})
        (try
          (let [response (http/get (str (:base-url this) "/maps/api/geocode/json")
                                   {:query-params     {"address" q
                                                       "key"     (:api-key this)}
                                    :as               :json
                                    :coerce           :always
                                    :throw-exceptions false})
                status   (:status response)
                body     (:body response)]
            (if (= 200 status)
              (parse-result (first (get body "results")))
              (do
                (log/warn "Google geocode non-200 response" {:status status})
                nil)))
          (catch Exception e
            (log/error e "Google geocode request failed" {:q q})
            nil)))))

  (reverse-geocode [this point]
    (enforce-rate-limit! (:last-request-ms this) (:min-interval-ms this))
    (log/debug "Google reverse-geocode request" point)
    (try
      (let [latlng   (str (:lat point) "," (:lng point))
            response (http/get (str (:base-url this) "/maps/api/geocode/json")
                               {:query-params     {"latlng" latlng
                                                   "key"    (:api-key this)}
                                :as               :json
                                :coerce           :always
                                :throw-exceptions false})
            status   (:status response)
            body     (:body response)]
        (if (= 200 status)
          (parse-result (first (get body "results")))
          (do
            (log/warn "Google reverse-geocode non-200 response" {:status status})
            nil)))
      (catch Exception e
        (log/error e "Google reverse-geocode request failed" point)
        nil))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-google-adapter
  "Create a Google Maps Geocoding adapter.

   Config keys:
     :api-key         - Google Maps API key (required)
     :base-url        - Override base URL (default: https://maps.googleapis.com)
     :min-interval-ms - Minimum ms between requests (default: 100)

   Returns:
     GoogleAdapter implementing GeoProviderProtocol"
  [{:keys [api-key base-url min-interval-ms]
    :or   {base-url        default-base-url
           min-interval-ms default-min-interval-ms}}]
  {:pre [(string? api-key)]}
  (->GoogleAdapter api-key base-url (atom 0) min-interval-ms))
