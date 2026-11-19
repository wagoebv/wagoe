(ns wagoe.core.utils.secret-keys
  "Whether a config or data key names a secret. One rule for log redaction,
   the devtools config views and `bb doctor` (BOU-973). wagoe.tools.secret-keys
   is its Babashka copy; a tools test holds the two to the same answers."
  (:require [clojure.string :as str]
            [wagoe.core.utils.text :as text]))

(def ^:private secret-name
  ;; The last word decides: :stripe-api-key and :webhook-secret are secrets,
  ;; :secret-name and :token-ttl only mention one. A plural (:api-keys) counts.
  #"(?:^|-)(?:password|passwd|passphrase|pwd|secret|token|credential|apikey|(?:api|private|secret|access|service|encryption|signing|master)-key)s?$")

(defn normalize
  "`k` as lower-case kebab: `:Password`, `\"password_hash\"`, `\"passwordHash\"`
   and `\"DBPassword\"` become \"password\", \"password-hash\", \"password-hash\"
   and \"db-password\"."
  [k]
  (-> (cond (keyword? k) (name k) (string? k) k :else (str k))
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (text/lower-case)
      (str/replace "_" "-")))

(defn secret-name?
  "True when `kname`, already `normalize`d, names a secret."
  [kname]
  (boolean (re-find secret-name kname)))

(defn secret-key?
  "True when key `k` names a secret."
  [k]
  (secret-name? (normalize k)))

(def ^:private placeholder-marker
  "What the shipped examples and docs write in place of a secret."
  #"change-?me|your-|at-least|minimum|min-32|32-char|^dev-secret")

(defn placeholder-secret?
  "True when `secret` is a published example value rather than a real secret:
   one phrased as the examples and docs phrase theirs, or a few characters
   repeated (BOU-1014)."
  [secret]
  (let [s (text/lower-case (str secret))]
    (boolean (or (re-find placeholder-marker s) (< (count (set s)) 6)))))

(def min-secret-length
  "A key shorter than this materially weakens what it signs or encrypts."
  32)

(defn weak-secret
  "Why `secret`, named `what` in the message, cannot key anything, or nil:
   under 32 characters, or, unless `dev-like?`, a published example value
   (BOU-1021)."
  [what secret dev-like?]
  (cond
    (< (count (str secret)) min-secret-length)
    (str what " must be at least " min-secret-length " characters.")

    (and (not dev-like?) (placeholder-secret? secret))
    (str what " is an example value from the docs or a template. Generate one: openssl rand -base64 32")))
