(ns wagoe.tools.secret-keys
  "Babashka copy of wagoe.core.utils.secret-keys, which the tooling cannot load.
   secret_keys_test holds the two to the same answers (BOU-973)."
  (:require [clojure.string :as str]
            [wagoe.tools.text :as text]))

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
