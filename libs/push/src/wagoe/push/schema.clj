(ns wagoe.push.schema
  (:require [malli.core :as m]))

;; --- Enums ---
(def Platform [:enum :fcm :apns])
(def Priority [:enum :normal :high])
(def BackoffStrategy [:enum :exponential :linear :fixed])
(def AnalyticsEventType [:enum :sent :delivered :opened :failed])

;; --- Composites ---
(def LocalizedString
  [:or :string [:map-of :keyword :string]])

(def RetryConfig
  [:map
   [:max-attempts [:int {:min 1 :max 10}]]
   [:backoff {:optional true} BackoffStrategy]])

;; --- defpush definition ---
(def PushDefinition
  [:map
   [:id :keyword]
   [:title LocalizedString]
   [:body LocalizedString]
   [:channels [:set Platform]]
   [:priority {:optional true} Priority]
   [:ttl {:optional true} [:int {:min 0}]]
   [:deep-link {:optional true} :string]
   [:silent? {:optional true} :boolean]
   [:collapse-key {:optional true} :keyword]
   [:retry {:optional true} RetryConfig]])

;; --- Device ---
(def DeviceInfo
  [:map
   [:token [:string {:min 1 :max 512}]]
   [:platform Platform]
   [:app-id [:string {:min 1}]]
   [:device-name {:optional true} :string]
   [:os-version {:optional true} :string]])

(def DeviceRecord
  [:map
   [:id :uuid]
   [:user-id :uuid]
   [:token :string]
   [:platform Platform]
   [:app-id :string]
   [:active? :boolean]
   [:created-at inst?]
   [:last-used-at inst?]])

;; --- Send input ---
(def SendPushInput
  [:map
   [:user-id :uuid]
   [:locale {:optional true} :keyword]])

;; --- Analytics ---
(def AnalyticsEvent
  [:map
   [:id :uuid]
   [:notification-id :keyword]
   [:device-token :string]
   [:event-type AnalyticsEventType]
   [:platform Platform]
   [:user-id {:optional true} :uuid]
   [:provider-message-id {:optional true} :string]
   [:error {:optional true} :string]
   [:timestamp inst?]])

(def PushStats
  [:map
   [:notification-id :keyword]
   [:sent :int]
   [:delivered :int]
   [:opened :int]
   [:failed :int]
   [:delivery-rate {:optional true} :double]
   [:open-rate {:optional true} :double]])

;; --- Callback ---
(def CallbackPayload
  [:map
   [:device-token :string]
   [:provider-message-id :string]
   [:event-type [:enum :delivered :opened]]
   [:callback-token :string]
   [:notification-id :keyword]
   [:platform [:enum :fcm :apns]]
   [:timestamp {:optional true} inst?]])

;; --- Validators ---
(def ^:private push-definition-validator (m/validator PushDefinition))
(def ^:private push-definition-explainer (m/explainer PushDefinition))
(def ^:private device-info-validator (m/validator DeviceInfo))
(def ^:private callback-payload-validator (m/validator CallbackPayload))

(defn valid-push-definition? [d] (push-definition-validator d))
(defn explain-push-definition [d] (push-definition-explainer d))
(defn valid-device-info? [d] (device-info-validator d))
(defn valid-callback? [d] (callback-payload-validator d))
