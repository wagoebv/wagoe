(ns wagoe.realtime.schema
  "Malli schemas for realtime module data structures.
  
  Schemas for:
  - Connection state
  - WebSocket messages
  - Authentication tokens
  - Routing metadata
  - Pub/sub topics and subscriptions"
  (:require [malli.core :as m]))

;; Connection Schemas

(def Connection
  "Schema for WebSocket connection state."
  [:map
   [:id :uuid]
   [:user-id :uuid]
   [:roles [:set :keyword]]
   [:metadata [:map]]
   [:created-at inst?]])

(def ConnectionInput
  "Schema for creating new connection."
  [:map
   [:user-id :uuid]
   [:roles [:set :keyword]]
   [:metadata {:optional true} [:map]]])

;; Message Schemas

(def MessageType
  "Valid message types for routing."
  [:enum :broadcast :user :role :connection])

(def Message
  "Schema for WebSocket message."
  [:map
   [:type MessageType]
   [:payload :map]
   [:target {:optional true} [:or :uuid :keyword]]
   [:timestamp {:optional true} inst?]])

(def MessageInput
  "Schema for creating new message."
  [:map
   [:type MessageType]
   [:payload :map]
   [:target {:optional true} [:or :uuid :keyword]]])

;; Authentication Schemas

(def QueryParams
  "Schema for WebSocket connection query parameters."
  [:map
   [:token :string]])

(def JWTClaims
  "Schema for JWT token claims."
  [:map
   [:user-id :uuid]
   [:roles [:set :keyword]]
   [:permissions {:optional true} [:set :keyword]]
   [:exp {:optional true} int?]
   [:iat {:optional true} int?]])

;; Routing Schemas

(def RoutingTarget
  "Schema for message routing targets."
  [:map
   [:connection-ids [:vector :uuid]]])

;; Validation functions

(def ^:private connection-validator (m/validator Connection))
(def ^:private message-validator (m/validator Message))
(def ^:private query-params-validator (m/validator QueryParams))
(def ^:private jwt-claims-validator (m/validator JWTClaims))
(def ^:private connection-explainer (m/explainer Connection))
(def ^:private message-explainer (m/explainer Message))

(defn valid-connection?
  "Check if connection data is valid."
  [connection]
  (connection-validator connection))

(defn valid-message?
  "Check if message data is valid."
  [message]
  (message-validator message))

(defn valid-query-params?
  "Check if query params are valid."
  [params]
  (query-params-validator params))

(defn valid-jwt-claims?
  "Check if JWT claims are valid."
  [claims]
  (jwt-claims-validator claims))

;; Explain functions for better error messages

(defn explain-connection
  "Explain why connection validation failed."
  [connection]
  (connection-explainer connection))

(defn explain-message
  "Explain why message validation failed."
  [message]
  (message-explainer message))

;; Pub/Sub Schemas

(def Topic
  "Schema for pub/sub topic name."
  [:string {:min 1, :max 255}])

(def Subscription
  "Schema for topic subscription record."
  [:map
   [:connection-id :uuid]
   [:topic Topic]
   [:created-at inst?]])

(def SubscriptionInput
  "Schema for creating new subscription."
  [:map
   [:connection-id :uuid]
   [:topic Topic]])

(def Subscriptions
  "Schema for subscriptions data structure.
   Map of topic name to set of connection IDs."
  [:map-of Topic [:set :uuid]])

;; Pub/Sub validation functions

(def ^:private topic-validator (m/validator Topic))
(def ^:private subscription-validator (m/validator Subscription))
(def ^:private subscriptions-validator (m/validator Subscriptions))
(def ^:private topic-explainer (m/explainer Topic))
(def ^:private subscription-explainer (m/explainer Subscription))

(defn valid-topic?
  "Check if topic name is valid."
  [topic]
  (topic-validator topic))

(defn valid-subscription?
  "Check if subscription is valid."
  [subscription]
  (subscription-validator subscription))

(defn valid-subscriptions?
  "Check if subscriptions data structure is valid."
  [subscriptions]
  (subscriptions-validator subscriptions))

(defn explain-topic
  "Explain why topic validation failed."
  [topic]
  (topic-explainer topic))

(defn explain-subscription
  "Explain why subscription validation failed."
  [subscription]
  (subscription-explainer subscription))

