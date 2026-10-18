(ns wagoe.payments.test-support
  "Fake PSP responses and Stripe signing, shared by the adapter tests."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.net URLDecoder]
           [java.nio.charset StandardCharsets]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(defn json-response
  "What clj-http returns with `:as :string`."
  [status body-map]
  {:status status :body (json/generate-string body-map)})

(defn form-decode
  "Decode an application/x-www-form-urlencoded body into a string->string map."
  [body]
  (into {}
        (for [pair (str/split (str body) #"&")
              :let [[k v] (str/split pair #"=" 2)]]
          [(URLDecoder/decode ^String k "UTF-8")
           (URLDecoder/decode (str v) "UTF-8")])))

(defn stripe-sig-header
  "A Stripe-Signature value for `raw-body`, signed independently of the adapter."
  [raw-body timestamp secret]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (.getBytes ^String secret StandardCharsets/UTF_8) "HmacSHA256")))
        sig (->> (.doFinal mac (.getBytes (str timestamp "." raw-body) StandardCharsets/UTF_8))
                 (map #(format "%02x" (bit-and % 0xff)))
                 (apply str))]
    (str "t=" timestamp ",v1=" sig)))
