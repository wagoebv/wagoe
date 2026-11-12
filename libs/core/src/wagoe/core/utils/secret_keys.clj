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
