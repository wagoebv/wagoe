(ns wagoe.geo.shell.adapters.pdok
  "PDOK Locatieserver adapter: Dutch addresses from the BAG.

   No API key. Netherlands only; a query for another country is a miss, so a
   fallback chain moves on. A postcode with a house number is looked up exactly.

   Usage:
     (def adapter (create-pdok-adapter {}))
     (geocode adapter {:postcode \"1012 JS\" :house-number 1})"
  (:require [wagoe.core.utils.text :as text]
            [wagoe.geo.ports :as ports]
            [wagoe.geo.shell.adapters.support :as support]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-base-url "https://api.pdok.nl/bzk/locatieserver/search/v3_1")
(def ^:private default-min-interval-ms 100)
(def ^:private fields
  "weergavenaam,straatnaam,huisnummer,huisletter,huisnummertoevoeging,postcode,woonplaatsnaam,centroide_ll")
(def ^:private dutch #{"netherlands" "nederland" "nl" "the netherlands"})

;; =============================================================================
;; Response parsing
;; =============================================================================

(defn- parse-point
  "\"POINT(4.8937175 52.37329259)\" — longitude first."
  [wkt]
  (when-let [[_ lng lat] (some->> wkt (re-matches #"POINT\((\S+) (\S+)\)"))]
    [(parse-double lng) (parse-double lat)]))

(defn- addition
  "BAG writes 1A-2 as huisletter A and huisnummertoevoeging 2."
  [{:keys [huisletter huisnummertoevoeging]}]
  (not-empty (str/join "-" (remove str/blank? [huisletter huisnummertoevoeging]))))

(defn- parse-result [doc]
  (let [[lng lat] (parse-point (:centroide_ll doc))]
    (merge {:lat               lat
            :lng               lng
            :formatted-address (:weergavenaam doc)
            :postcode          (:postcode doc)
            :city              (:woonplaatsnaam doc)
            :country           "Netherlands"
            :provider          :pdok
            :cached?           false}
           (support/street-fields (:straatnaam doc) (:huisnummer doc) (addition doc)))))

(defn- squash [s]
  (some-> s text/upper-case (str/replace #"[^0-9A-Z]" "")))

(defn- pick
  "The doc with the asked addition; without one asked, the plain number if there is one."
  [docs wanted]
  (if (str/blank? wanted)
    (or (first (remove addition docs)) (first docs))
    (first (filter #(= (squash wanted) (squash (addition %))) docs))))

;; =============================================================================
;; Request
;; =============================================================================

(defn- request
  "{:params .. :pick-fn ..} for a query, or nil when PDOK cannot answer it.
   A valid postcode goes in a filter, so it and the house number match exactly.
   A house number of \"12a\" is 12 with addition a."
  [{:keys [address postcode house-number house-number-addition city country]}]
  (let [pc               (squash postcode)
        hn               (some-> house-number str str/trim not-empty)
        [_ number extra] (some->> hn (re-matches #"(\d+)\s*-?\s*(\S*)"))]
    (cond
      (and country (not (dutch (text/lower-case (str/trim country)))))
      nil

      (and pc (re-matches #"\d{4}[A-Z]{2}" pc) (or (nil? hn) number))
      {:params  {"fq"   (cond-> ["type:adres" (str "postcode:" pc)]
                          number (conj (str "huisnummer:" number)))
                 "rows" "20"}
       :pick-fn #(pick % (if (str/blank? house-number-addition) extra house-number-addition))}

      :else
      (let [q (str/join " " (remove str/blank? [address hn house-number-addition postcode city]))]
        (when-not (str/blank? q)
          {:params  {"q" q "fq" "type:adres" "rows" "1"}
           :pick-fn first})))))

(defn- fetch-docs
  "The docs from one PDOK call, or nil after a WARN or ERROR."
  [this path params op]
  (support/enforce-rate-limit! (:last-request-ms this) (:min-interval-ms this))
  (log/debug "PDOK request" {:op op :params params})
  (try
    (let [response (http/get (str (:base-url this) path)
                             {:query-params     (assoc params "fl" fields)
                              :as               :json
                              :coerce           :always
                              :throw-exceptions false})
          status   (:status response)]
      (if (= 200 status)
        (get-in response [:body :response :docs])
        (do
          (log/warn (str "PDOK " op " non-200 response") {:status status})
          nil)))
    (catch Exception e
      (log/error e (str "PDOK " op " request failed") {:params params})
      nil)))

;; =============================================================================
;; Adapter record
;; =============================================================================

(defrecord PdokAdapter [base-url last-request-ms min-interval-ms])

(extend-protocol ports/GeoProviderProtocol
  PdokAdapter

  (geocode [this query]
    (when-let [{:keys [params pick-fn]} (request query)]
      (some->> (fetch-docs this "/free" params "geocode")
               pick-fn
               (support/parse-response :pdok parse-result))))

  (reverse-geocode [this point]
    (some->> (fetch-docs this "/reverse"
                         {"lat" (str (:lat point)) "lon" (str (:lng point))
                          "type" "adres" "rows" "1"}
                         "reverse-geocode")
             first
             (support/parse-response :pdok parse-result))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-pdok-adapter
  "Create a PDOK Locatieserver adapter.

   Config keys:
     :base-url        - Override base URL (default: https://api.pdok.nl/bzk/locatieserver/search/v3_1)
     :min-interval-ms - Minimum ms between requests (default: 100)

   Returns:
     PdokAdapter implementing GeoProviderProtocol"
  [{:keys [base-url min-interval-ms]
    :or   {base-url        default-base-url
           min-interval-ms default-min-interval-ms}}]
  (->PdokAdapter base-url (atom 0) min-interval-ms))
