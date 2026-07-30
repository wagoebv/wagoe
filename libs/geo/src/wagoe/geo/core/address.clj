(ns wagoe.geo.core.address
  "Pure address query normalisation and hashing — no I/O, no side effects."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(def ^:private default-country "Netherlands")

(defn normalize-query
  "Produce a canonical lowercase, trimmed address string from an AddressQuery map.

   Fields are joined in a deterministic order: address, postcode, city, country.
   Missing fields are omitted. Country defaults to \"Netherlands\" when not provided.

   Args:
     query - AddressQuery map with optional :address :postcode :city :country

   Returns:
     A non-empty lowercase string suitable for hashing."
  [{:keys [address postcode city country]}]
  (let [parts (cond-> []
                address  (conj (str/lower-case (str/trim address)))
                postcode (conj (str/lower-case (str/trim postcode)))
                city     (conj (str/lower-case (str/trim city)))
                true     (conj (str/lower-case
                                (str/trim (or country default-country)))))]
    (str/join ", " parts)))

(defn query-hash
  "Compute a SHA-256 hex digest of the normalised address query.

   Used as the primary key in the geo_cache table.

   Args:
     query - AddressQuery map

   Returns:
     64-character lowercase hex string."
  [query]
  (let [normalised (normalize-query query)
        digest     (MessageDigest/getInstance "SHA-256")
        bytes      (.digest digest (.getBytes normalised StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" %) bytes))))
