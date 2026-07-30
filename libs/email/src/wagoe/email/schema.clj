(ns wagoe.email.schema
  "Malli validation schemas for email module."
  (:require [malli.core :as m]))

;; =============================================================================
;; Email Address Schema
;; =============================================================================

(def email-address-pattern
  "Basic RFC 5322 email address pattern."
  #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$")

(def EmailAddress
  "Email address string schema with pattern validation."
  [:string {:pattern email-address-pattern
            :min 3
            :error/message "Must be a valid email address"}])

;; =============================================================================
;; Attachment Schema
;; =============================================================================

(def Attachment
  "Email attachment schema."
  [:map
   [:filename [:string {:min 1}]]
   [:content-type [:string {:min 1}]]
   [:content [:or bytes? :string]]  ; Binary data or base64 string
   [:size {:optional true} [:int {:min 0}]]])

;; =============================================================================
;; Email Schema
;; =============================================================================

(def Email
  "Complete email schema with all fields."
  [:map
   [:id uuid?]
   [:to [:vector EmailAddress]]
   [:from EmailAddress]
   [:subject [:string {:min 1}]]
   [:body :string]
   [:headers {:optional true} [:map-of :keyword :string]]
   [:attachments {:optional true} [:vector Attachment]]
   [:created-at inst?]
   [:metadata {:optional true} [:map-of :keyword :any]]])

(def SendEmailInput
  "Schema for creating a new email.
   Input before normalization - to can be string or vector."
  [:map
   [:to [:or EmailAddress [:vector EmailAddress]]]
   [:from EmailAddress]
   [:subject [:string {:min 1}]]
   [:body :string]
   [:headers {:optional true} [:map-of :keyword :string]]
   [:attachments {:optional true} [:vector Attachment]]
   [:metadata {:optional true} [:map-of :keyword :any]]])

;; =============================================================================
;; Email Validation Result Schema
;; =============================================================================

(def EmailValidationResult
  "Schema for email validation result."
  [:map
   [:valid? :boolean]
   [:errors [:vector :string]]])

(def RecipientValidationResult
  "Schema for recipient validation result."
  [:map
   [:valid? :boolean]
   [:valid-emails [:vector EmailAddress]]
   [:invalid-emails [:vector :string]]])

;; =============================================================================
;; Email Summary Schema
;; =============================================================================

(def EmailSummary
  "Summary schema for logging/monitoring."
  [:map
   [:id uuid?]
   [:to [:int {:min 1}]]  ; Count of recipients
   [:from EmailAddress]
   [:subject [:string {:min 1}]]
   [:has-attachments? :boolean]
   [:created-at inst?]])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(def ^:private email-validator (m/validator Email))
(def ^:private email-explainer (m/explainer Email))
(def ^:private send-email-input-validator (m/validator SendEmailInput))

(defn valid-email?
  "Validate email against schema."
  [email]
  (email-validator email))

(defn valid-email-input?
  "Validate email input against schema."
  [email-input]
  (send-email-input-validator email-input))

(defn explain-email-errors
  "Get human-readable validation errors for email."
  [email]
  (email-explainer email))
