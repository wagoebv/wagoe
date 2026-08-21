(ns wagoe.external.schema
  "Malli validation schemas for external service adapters."
  (:require [malli.core :as m]))

;; =============================================================================
;; SMTP Schemas
;; =============================================================================

(def SmtpConfig
  "SMTP transport configuration schema."
  [:map
   [:host [:string {:min 1}]]
   [:port [:int {:min 1 :max 65535}]]
   [:username {:optional true} [:string {:min 1}]]
   [:password {:optional true} [:string {:min 1}]]
   [:tls? {:optional true} :boolean]
   [:ssl? {:optional true} :boolean]
   [:from [:string {:min 3}]]])

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
   ;; javax.mail generates one.
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
  [:map
   [:host [:string {:min 1}]]
   [:port [:int {:min 1 :max 65535}]]
   [:username [:string {:min 1}]]
   [:password [:string {:min 1}]]
   [:ssl? {:optional true} :boolean]
   [:folder {:optional true} [:string {:min 1}]]])

(def InboundMessage
  "Inbound email message from IMAP."
  [:map
   [:uid :int]
   [:message-id {:optional true} :string]
   [:from :string]
   [:to [:vector :string]]
   [:subject :string]
   [:body {:optional true} :string]
   [:html-body {:optional true} :string]
   [:received-at inst?]
   [:headers {:optional true} [:map-of :keyword :string]]
   [:attachments {:optional true} [:vector :map]]])

(def ImapFetchOptions
  "Options for fetching messages from IMAP."
  [:map
   [:folder {:optional true} [:string {:min 1}]]
   [:limit {:optional true} [:int {:min 1}]]
   [:unread-only? {:optional true} :boolean]
   [:since {:optional true} inst?]])

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
;; Validation Functions
;; =============================================================================

(def ^:private smtp-config-validator (m/validator SmtpConfig))
(def ^:private imap-config-validator (m/validator ImapConfig))
(def ^:private twilio-config-validator (m/validator TwilioConfig))

(defn valid-smtp-config?
  "Validate SMTP configuration against schema."
  [config]
  (smtp-config-validator config))

(defn valid-imap-config?
  "Validate IMAP configuration against schema."
  [config]
  (imap-config-validator config))

(defn valid-twilio-config?
  "Validate Twilio configuration against schema."
  [config]
  (twilio-config-validator config))
