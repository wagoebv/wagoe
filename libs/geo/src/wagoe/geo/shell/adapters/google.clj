(ns wagoe.geo.shell.adapters.google
  "Google Maps Geocoding API adapter.

   Requires a valid Google Maps API key with the Geocoding API enabled.

   Usage:
     (def adapter (create-google-adapter {:api-key \"AIza...\"}))
     (geocode adapter {:address \"Damrak 1, Amsterdam\"})"
  (:require [wagoe.geo.ports :as ports]
            [wagoe.geo.shell.adapters.support :as support]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-base-url "https://maps.googleapis.com")
(def ^:private default-min-interval-ms 100)

;; =============================================================================
;; Response parsing
;; =============================================================================

(defn- extract-component
  "Extract an address component by type from Google's address_components."
  [components type-name]
  (some (fn [c]
          (when (some #(= type-name %) (:types c))
            (:long_name c)))
        components))

(defn- parse-result
  "Parse a single Google geocoding result into a GeoResult."
  [item]
  (let [{:keys [lat lng]} (get-in item [:geometry :location])
        components        (:address_components item)]
    {:lat               lat
     :lng               lng
     :formatted-address (:formatted_address item)
     :postcode          (extract-component components "postal_code")
     :city              (or (extract-component components "locality")
                            (extract-component components "administrative_area_level_2"))
     :country           (extract-component components "country")
     :provider          :google
     :cached?           false}))

;; Google answers 200 even for a rejected key; the reason is in the body.
(defn- first-result [body]
  (when-not (#{"OK" "ZERO_RESULTS"} (:status body))
    (log/warn "Google geocode request rejected"
              {:status (:status body) :error (:error_message body)}))
  (first (:results body)))

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
        (support/enforce-rate-limit! (:last-request-ms this) (:min-interval-ms this))
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
              (support/parse-response :google parse-result (first-result body))
              (do
                (log/warn "Google geocode non-200 response" {:status status})
                nil)))
          (catch Exception e
            (log/error e "Google geocode request failed" {:q q})
            nil)))))

  (reverse-geocode [this point]
    (support/enforce-rate-limit! (:last-request-ms this) (:min-interval-ms this))
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
          (support/parse-response :google parse-result (first-result body))
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
