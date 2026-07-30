(ns wagoe.user.shell.mfa-crypto
  "Cryptographic protection for MFA secrets at rest.

   The TOTP secret is *reversible* — it must be recovered to compute one-time
   codes — so it is AES-256-GCM encrypted under a key derived from JWT_SECRET.
   Backup codes are *one-way* — only ever compared against — so they are
   bcrypt-hashed (constant-time verification, self-salting).

   A DB read (SQL injection, backup leak, insider) therefore yields neither a
   usable TOTP seed nor usable recovery codes."
  (:require [buddy.core.codecs :as codecs]
            [buddy.core.crypto :as crypto]
            [buddy.core.hash :as hash]
            [buddy.core.nonce :as nonce]
            [buddy.hashers :as hashers]
            [clojure.string :as str]))

(def ^:private encrypted-prefix
  "Marks an encrypted TOTP secret and versions the scheme, so legacy plaintext
   values are distinguishable and the format can evolve."
  "enc:v1:")

(def ^:private gcm-iv-bytes 12)

(def ^:private mfa-key
  "32-byte AES-256 key derived from JWT_SECRET (reused per the BOU-162 decision).
   Resolved once per process; throws if JWT_SECRET is absent, so a misconfigured
   deployment fails fast rather than storing recoverable secrets."
  (delay
    (let [secret (or (System/getenv "JWT_SECRET")
                     (throw (ex-info "JWT_SECRET not configured (required to encrypt MFA secrets)"
                                     {:type :configuration-error
                                      :required-env-var "JWT_SECRET"})))]
      (hash/sha256 secret))))

(defn encrypted-secret?
  "True if s is an encrypted TOTP secret produced by encrypt-secret (vs a legacy
   plaintext value)."
  [s]
  (boolean (and (string? s) (str/starts-with? s encrypted-prefix))))

(defn encrypt-secret
  "Encrypt a plaintext TOTP secret. Returns \"enc:v1:<base64(iv||ciphertext)>\".
   nil in, nil out."
  [plaintext]
  (when plaintext
    (let [iv (nonce/random-bytes gcm-iv-bytes)
          ct (crypto/encrypt (codecs/to-bytes plaintext) @mfa-key iv {:algorithm :aes256-gcm})]
      (str encrypted-prefix
           (codecs/bytes->b64-str (byte-array (concat (seq iv) (seq ct))) true)))))

(defn decrypt-secret
  "Decrypt a value produced by encrypt-secret. Returns nil for a legacy
   (non-encrypted) or blank value, so legacy MFA rows read as having no usable
   secret and must be re-enrolled."
  [value]
  (when (encrypted-secret? value)
    (let [raw (codecs/b64->bytes (subs value (count encrypted-prefix)) true)
          iv  (byte-array (take gcm-iv-bytes raw))
          ct  (byte-array (drop gcm-iv-bytes raw))]
      (codecs/bytes->str (crypto/decrypt ct @mfa-key iv {:algorithm :aes256-gcm})))))

(defn hash-backup-code
  "Bcrypt-hash a single plaintext backup code for storage."
  [code]
  (hashers/derive code {:alg :bcrypt+sha512}))

(defn backup-code-matches?
  "Constant-time check of a presented plaintext code against a stored bcrypt hash.
   A malformed / legacy (non-bcrypt) stored value never matches and never throws."
  [code stored-hash]
  (boolean
   (and code stored-hash
        (try
          (hashers/check code stored-hash)
          (catch Exception _ false)))))
