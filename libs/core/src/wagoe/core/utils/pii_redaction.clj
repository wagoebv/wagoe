(ns wagoe.core.utils.pii-redaction
  "Shared PII redaction utilities used by error reporting adapters."
  (:require [clojure.string :as str]))

(def default-redact-keys
  "Default set of keys whose values should be redacted before sending to external systems.

  Keys are stored as lower-case strings for case-insensitive matching."
  #{"password" "pass" "pwd"
    "authorization" "auth" "auth-header"
    "token" "access_token" "refresh_token"
    "secret" "api-key" "api_key"
    "email" "e-mail"
    "ssn" "social_security_number"
    "credit-card" "credit_card"})

(defn normalize-key-name
  "Normalize a map key to a lower-case string for matching."
  [k]
  (-> (cond
        (keyword? k) (name k)
        (string? k) k
        :else (str k))
      (str/lower-case)))

(defn email-string?
  "Best-effort detection of email-like strings.
   
    Args:
      s: String to check
    
    Returns:
      Boolean indicating if string looks like an email"
   [s]
   (and (string? s) (str/includes? s "@")))

(defn mask-email
  "Mask an email address, preserving only the first character of the local part.\n\n  For non-email strings, returns [REDACTED]."
  [s]
  (if (email-string? s)
    (let [[local domain] (str/split s #"@" 2)
          prefix (subs local 0 (min 1 (count local)))
          masked (str prefix "***")]
      (str masked "@" domain))
    "[REDACTED]"))

(defn build-redact-state
  "Build effective redaction configuration from adapter config.

  Config structure (all optional):

  {:redact {:keys           [:password :authorization ...]
            :additional-keys [:custom-field]
            :mask-email?    true}}"
  [config]
  (let [rcfg (or (:redact config) {})
        extra-keys (map normalize-key-name (:additional-keys rcfg))
        cfg-keys (map normalize-key-name (:keys rcfg))
        all-keys (into default-redact-keys (concat cfg-keys extra-keys))
        mask-email? (if (contains? rcfg :mask-email?)
                      (:mask-email? rcfg)
                      true)]
    {:keys all-keys
     :mask-email? mask-email?}))

(defn redact-pii-value
  "Redact a single value based on its key and redaction state.\n\n  Email is treated specially:\n  - when :mask-email? is true and the value looks like an email, the local part is masked\n  - when :mask-email? is false, email values are left as-is, even if :email is in the default key set"
  [k v {:keys [keys mask-email?] :as _state}]
  (let [kname (normalize-key-name k)]
    (cond
      ;; Email keys are handled separately from generic key-based redaction
      (= kname "email")
      (cond
        (and mask-email? (email-string? v)) (mask-email v)
        :else v)

      ;; All other keys use generic redaction
      (contains? keys kname)
      "[REDACTED]"

      :else
      v)))

(defn redact-pii
  "Recursively redact PII from arbitrarily nested data structures.

  Redacts based on key names using the provided redaction state."
  [data state]
  (cond
    (map? data)
    (into {}
          (for [[k v] data]
            (let [v' (redact-pii v state)]
              [k (redact-pii-value k v' state)])))

    (sequential? data)
    (mapv #(redact-pii % state) data)

    :else
    data))

(defn apply-redaction
  "Apply PII redaction to a context map that may contain :tags and :extra.

  Always applies default PII redaction, even when :redact is omitted."
  [context config]
  (let [state (build-redact-state config)]
    (-> context
        (update :extra #(when % (redact-pii % state)))
        (update :tags #(when % (redact-pii % state))))))
