(ns wagoe.webhooks.shell.crypto
  "Signing secrets at rest. They cannot be hashed — signing needs the
   plaintext — so they are AES-256-GCM encrypted, in the `enc:v1:` format the
   user module uses for MFA seeds."
  (:require [buddy.core.codecs :as codecs]
            [buddy.core.crypto :as crypto]
            [buddy.core.hash :as hash]
            [buddy.core.nonce :as nonce]
            [clojure.string :as str])
  (:import (java.util Arrays)))

(def ^:private prefix "enc:v1:")

(def ^:private iv-bytes 12)

(defn derive-key
  "The 32-byte key for `key-material`, a string of at least 32 characters."
  [key-material]
  (when (< (count (str key-material)) 32)
    (throw (ex-info "Webhooks need an encryption key of at least 32 characters: set :encryption-key, or JWT_SECRET"
                    {:type :configuration-error})))
  (hash/sha256 key-material))

(defn generate-secret
  "A new signing secret."
  []
  (str "whsec_" (codecs/bytes->hex (nonce/random-bytes 32))))

(defn encrypt
  "`plaintext` as `enc:v1:<base64(iv || ciphertext)>`."
  [key plaintext]
  (let [^bytes iv  (nonce/random-bytes iv-bytes)
        ^bytes ct  (crypto/encrypt (codecs/to-bytes plaintext) key iv {:algorithm :aes256-gcm})
        ^bytes out (Arrays/copyOf iv (+ iv-bytes (alength ct)))]
    (System/arraycopy ct 0 out iv-bytes (alength ct))
    (str prefix (codecs/bytes->b64-str out true))))

(defn decrypt
  "The plaintext of a value `encrypt` produced. Throws on anything else, or
   under the wrong key: a secret that cannot be read cannot sign."
  [key value]
  (when-not (and (string? value) (str/starts-with? value prefix))
    (throw (ex-info "Webhook secret is not encrypted" {:type :internal-error})))
  (let [^bytes raw (codecs/b64->bytes (subs value (count prefix)) true)]
    (codecs/bytes->str (crypto/decrypt (Arrays/copyOfRange raw iv-bytes (alength raw)) key
                                       (Arrays/copyOfRange raw 0 iv-bytes)
                                       {:algorithm :aes256-gcm}))))
