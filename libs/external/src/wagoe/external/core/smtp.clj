(ns wagoe.external.core.smtp
  "Pure functions for SMTP email preparation and validation.
   No I/O, no side effects."
  (:require [wagoe.external.schema :as schema]
            [malli.core :as m])
  (:import [java.util Properties]))

;; =============================================================================
;; Recipient Normalisation
;; =============================================================================

(defn normalize-recipients
  "Normalise :to field to a vector of strings.

  Args:
    to - string or vector of strings

  Returns:
    Vector of non-blank strings"
  [to]
  (cond
    (nil? to)    []
    (string? to) (if (seq to) [to] [])
    (vector? to) (vec (filter seq to))
    :else        []))

;; =============================================================================
;; javax.mail Properties
;; =============================================================================

(defn build-mime-properties
  "Build java.util.Properties for a javax.mail Session.
   Pure function — returns a Properties object with no I/O.

  Args:
    config - map with :host :port :tls? :ssl? (all optional except host/port)

  Returns:
    java.util.Properties instance"
  [{:keys [host port tls? ssl? username password]}]
  (let [props (Properties.)]
    (.put props "mail.smtp.host" (str host))
    (.put props "mail.smtp.port" (str port))
    (when tls?
      (.put props "mail.smtp.starttls.enable" "true")
      (.put props "mail.smtp.starttls.required" "true"))
    (when ssl?
      (.put props "mail.smtp.ssl.enable" "true")
      (.put props "mail.smtp.socketFactory.port" (str port))
      (.put props "mail.smtp.socketFactory.class" "javax.net.ssl.SSLSocketFactory"))
    (when (and username password)
      (.put props "mail.smtp.auth" "true"))
    props))

;; =============================================================================
;; Config Validation
;; =============================================================================

(def ^:private smtp-config-validator (m/validator schema/SmtpConfig))
(def ^:private smtp-config-explainer (m/explainer schema/SmtpConfig))

(defn validate-config
  "Validate SMTP config against schema.

  Returns:
    {:valid? true} or {:valid? false :errors [...malli explain...]}"
  [config]
  (if (smtp-config-validator config)
    {:valid? true}
    {:valid? false
     :errors (smtp-config-explainer config)}))

;; =============================================================================
;; Email Preparation
;; =============================================================================

(defn prepare-outbound-email
  "Normalise and stamp an outbound email map.

  Args:
    input - OutboundEmail map
    now   - java.util.Date or java.time.Instant for :prepared-at timestamp

  Returns:
    Normalised map with :to as vector and :prepared-at added"
  [input now]
  (-> input
      (update :to normalize-recipients)
      (assoc :prepared-at now)))

(defn outbound-email-summary
  "Return a concise summary map for logging/monitoring.

  Args:
    email - prepared OutboundEmail map

  Returns:
    {:to-count :from :subject :has-html? :has-reply-to?}"
  [email]
  {:to-count     (count (normalize-recipients (:to email)))
   :from         (:from email)
   :subject      (:subject email)
   :has-html?    (some? (:html-body email))
   :has-reply-to? (some? (:reply-to email))})
