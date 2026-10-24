(ns wagoe.geo.core.address
  "Pure address query normalisation and hashing — no I/O, no side effects."
  (:require [wagoe.core.utils.text :as text]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(def ^:private default-country "Netherlands")

(defn- house-number-part
  "\"nr 12 a\" — tagged, so a house number never reads as an address or a postcode."
  [house-number addition]
  (let [n (str/trim (str house-number))]
    (when-not (str/blank? n)
      (text/lower-case
       (str/join " " (cond-> ["nr" n]
                       (not (str/blank? addition)) (conj (str/trim addition))))))))

(defn normalize-query
  "Produce a canonical lowercase, trimmed address string from an AddressQuery map.

   Fields are joined in a deterministic order: address, postcode, house number
   (with its addition), city, country. Missing fields are omitted, so a query
   without a house number hashes as it always did. Country defaults to
   \"Netherlands\" when not provided.

   Args:
     query - AddressQuery map with optional :address :postcode :house-number
             :house-number-addition :city :country

   Returns:
     A non-empty lowercase string suitable for hashing."
  [{:keys [address postcode house-number house-number-addition city country]}]
  (let [number (house-number-part house-number house-number-addition)
        parts  (cond-> []
                 address  (conj (text/lower-case (str/trim address)))
                 postcode (conj (text/lower-case (str/trim postcode)))
                 number   (conj number)
                 city     (conj (text/lower-case (str/trim city)))
                 true     (conj (text/lower-case
                                 (str/trim (or country default-country)))))]
    (str/join ", " parts)))

(defn query-hash
  "Compute a SHA-256 hex digest of the normalised address query.

   Used as the primary key in the geo_cache table. With `providers`, the names a
   lookup was restricted to, the key is that restriction's own, so an answer
   from a provider left out is never served for it.

   Args:
     query     - AddressQuery map
     providers - optional collection of provider keywords

   Returns:
     64-character lowercase hex string."
  ([query]
   (query-hash query nil))
  ([query providers]
   (let [normalised (cond-> (normalize-query query)
                      (seq providers) (str " | " (str/join "," (sort (map name providers)))))
         digest     (MessageDigest/getInstance "SHA-256")
         bytes      (.digest digest (.getBytes ^String normalised StandardCharsets/UTF_8))]
     (apply str (map #(format "%02x" %) bytes)))))
