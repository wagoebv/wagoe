(ns wagoe.external.schema
  "Malli validation schemas for external service adapters."
  (:require [malli.core :as m]
            [malli.error :as me]))

;; =============================================================================
;; SMTP Schemas
;; =============================================================================

(def ^:private mail-tls-entries
  ;; :ssl-trust is "*" alone, or hosts as a space-separated string or a vector:
  ;; Angus reads a "*" among hosts, or a comma, as part of a host name. nil is
  ;; allowed so an unset #env reads as absent.
  [[:ssl-trust {:optional true}
    [:maybe [:or [:= "*"]
             [:re #"^[^,*\s]+(\s+[^,*\s]+)*$"]
             [:vector {:min 1} [:re #"^[^,*\s]+$"]]]]]
   [:check-server-identity? {:optional true} [:maybe :boolean]]])

(def MailTls
  "TLS trust settings the SMTP and IMAP adapters accept."
  (into [:map] mail-tls-entries))

(def SmtpConfig
  "SMTP transport configuration schema."
  (into [:map
         [:host [:string {:min 1}]]
         [:port [:int {:min 1 :max 65535}]]
         [:username {:optional true} [:string {:min 1}]]
         [:password {:optional true} [:string {:min 1}]]
         [:tls? {:optional true} :boolean]
         [:ssl? {:optional true} :boolean]
         [:from [:string {:min 3}]]]
        mail-tls-entries))

(def Attachment
  "Outbound email attachment. :content is raw bytes or a base64-encoded string."
  [:map
   [:filename [:string {:min 1}]]
   [:content-type [:string {:min 1}]]
   [:content [:or bytes? [:string {:min 1}]]]
   [:size {:optional true} [:int {:min 0}]]])

(def OutboundEmail
  "Outbound email message schema."
  [:map
   [:to [:or [:string {:min 3}] [:vector [:string {:min 3}]]]]
   [:from {:optional true} [:string {:min 3}]]
   [:subject [:string {:min 1}]]
   [:body {:optional true} :string]
   [:html-body {:optional true} :string]
   [:reply-to {:optional true} [:string {:min 3}]]
   [:cc {:optional true} [:vector [:string {:min 3}]]]
   [:bcc {:optional true} [:vector [:string {:min 3}]]]
   [:attachments {:optional true} [:vector Attachment]]
   ;; Caller-supplied deterministic Message-ID (e.g. issuance dedup); when absent
   ;; Jakarta Mail generates one.
   [:message-id {:optional true} [:string {:min 1}]]])

(def EmailSendResult
  "Result of an email send operation."
  [:map
   [:success? :boolean]
   [:message-id {:optional true} [:string {:min 1}]]
   ;; A keyword since ADR-036 §3 — :smtp-error, :imap-error, :twilio-error …
   [:error {:optional true} [:map
                             [:message :string]
                             [:type keyword?]]]])

;; =============================================================================
;; IMAP Schemas
;; =============================================================================

(def ImapConfig
  "IMAP mailbox configuration schema."
  (into [:map
         [:host [:string {:min 1}]]
         [:port [:int {:min 1 :max 65535}]]
         [:username [:string {:min 1}]]
         [:password [:string {:min 1}]]
         [:ssl? {:optional true} :boolean]
         [:folder {:optional true} [:string {:min 1}]]]
        mail-tls-entries))

(def InboundAttachment
  "A file from an inbound message: a part with Content-Disposition attachment,
   a filename, or a type other than text/plain and text/html. :content is the
   decoded bytes. Inline parts are included; :embedded? is true for one the
   HTML body shows by cid:, such as a signature logo. :disposition is the lower-cased
   Content-Disposition and :content-id has no angle brackets."
  [:map
   [:filename {:optional true} [:string {:min 1}]]
   [:disposition {:optional true} [:string {:min 1}]]
   [:content-id {:optional true} [:string {:min 1}]]
   [:embedded? {:optional true} :boolean]
   [:content-type [:string {:min 1}]]
   [:size [:int {:min 0}]]
   [:content bytes?]])

(def InboundMessage
  "Inbound email message from IMAP. :body and :html-body are the first inline
   text/plain and text/html parts; :raw is the RFC 822 source, on request."
  [:map
   [:uid :int]
   [:folder {:optional true} [:string {:min 1}]]
   [:message-id {:optional true} :string]
   [:from :string]
   [:to [:vector :string]]
   [:subject :string]
   [:body {:optional true} :string]
   [:html-body {:optional true} :string]
   [:received-at inst?]
   [:seen? {:optional true} :boolean]
   [:headers {:optional true} [:map-of :keyword :string]]
   [:attachments {:optional true} [:vector InboundAttachment]]
   [:raw {:optional true} bytes?]])

(def ImapFetchOptions
  "Options for fetching messages from IMAP."
  [:map
   [:folder {:optional true} [:string {:min 1}]]
   [:limit {:optional true} [:int {:min 1}]]
   [:unread-only? {:optional true} :boolean]
   [:since {:optional true} inst?]
   [:include-raw? {:optional true} :boolean]])

;; =============================================================================
;; Twilio Schemas
;; =============================================================================

(def TwilioConfig
  "Twilio API configuration schema."
  [:map
   [:account-sid [:string {:min 1}]]
   [:auth-token [:string {:min 1}]]
   [:from-number [:string {:min 1}]]
   [:base-url {:optional true} [:string {:min 1}]]])

(def SendMessageInput
  "Input for sending a Twilio SMS or WhatsApp message."
  [:map
   [:to [:string {:min 1}]]
   [:body [:string {:min 1}]]
   [:from {:optional true} [:string {:min 1}]]
   [:media-url {:optional true} [:string {:min 1}]]])

(def MessageResult
  "Result of a Twilio message send operation."
  [:map
   [:success? :boolean]
   [:message-sid {:optional true} :string]
   [:status {:optional true} :string]
   ;; A keyword since ADR-036 §3 — :smtp-error, :imap-error, :twilio-error …
   [:error {:optional true} [:map
                             [:message :string]
                             [:type keyword?]]]])

;; =============================================================================
;; FTPS Schemas
;; =============================================================================

(def FtpsConfig
  "FTPS (explicit TLS) file source configuration. :trust-store is a keystore
   file holding the server's certificate or CA, when the JVM default does not."
  [:map
   [:host [:string {:min 1}]]
   [:port {:optional true} [:int {:min 1 :max 65535}]]
   [:username [:string {:min 1}]]
   [:password [:string {:min 1}]]
   [:timeout-ms {:optional true} [:int {:min 1 :max Integer/MAX_VALUE}]]
   [:max-attempts {:optional true} [:int {:min 1 :max 10}]]
   [:backoff-ms {:optional true} [:int {:min 0}]]
   [:trust-store {:optional true} [:maybe [:string {:min 1}]]]
   [:trust-store-password {:optional true} [:maybe :string]]
   [:trust-store-type {:optional true} [:string {:min 1}]]])

(def RemoteFile
  "A file in a remote directory."
  [:map
   [:name [:string {:min 1}]]
   [:path [:string {:min 1}]]
   [:size [:int {:min 0}]]
   [:modified-at {:optional true} inst?]])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(def ^:private smtp-config-validator (m/validator SmtpConfig))
(def ^:private imap-config-validator (m/validator ImapConfig))
(def ^:private twilio-config-validator (m/validator TwilioConfig))
(def ^:private ftps-config-validator (m/validator FtpsConfig))

(defn valid-smtp-config?
  "Validate SMTP configuration against schema."
  [config]
  (smtp-config-validator config))

(defn valid-imap-config?
  "Validate IMAP configuration against schema."
  [config]
  (imap-config-validator config))

(def ^:private mail-tls-explainer (m/explainer MailTls))

(defn assert-mail-tls!
  "Throw a :validation-error when config's TLS trust settings are invalid."
  [config]
  (when-let [errors (some-> (mail-tls-explainer config) me/humanize)]
    (throw (ex-info "Invalid mail TLS configuration"
                    {:type :validation-error :host (:host config) :errors errors}))))

(defn valid-twilio-config?
  "Validate Twilio configuration against schema."
  [config]
  (twilio-config-validator config))

(defn valid-ftps-config?
  "Validate FTPS configuration against schema."
  [config]
  (ftps-config-validator config))
