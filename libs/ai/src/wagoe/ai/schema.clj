(ns wagoe.ai.schema
  "Malli validation schemas for the AI module."
  (:require [malli.core :as m]))

;; =============================================================================
;; Message — a single entry in a conversation
;; =============================================================================

(def Message
  "A single message in an AI conversation.
   :role    — :system, :user, or :assistant
   :content — the message text"
  [:map
   [:role    [:enum :system :user :assistant]]
   [:content :string]])

;; =============================================================================
;; AIRequest — input to complete / complete-json
;; =============================================================================

(def AIRequest
  "Input to an AI completion call."
  [:map
   [:messages             [:vector Message]]
   [:model                {:optional true} :string]
   [:temperature          {:optional true} :double]
   [:max-tokens           {:optional true} :int]
   [:response-format      {:optional true} [:enum :text :json]]
   [:system-prompt        {:optional true} :string]])

;; =============================================================================
;; AIResponse — returned by complete / complete-json
;; =============================================================================

(def AIResponse
  "Output from an AI completion call."
  [:map
   [:text              {:optional true} :string]
   [:data              {:optional true} :map]
   [:tokens            {:optional true} :int]
   [:provider          :keyword]
   [:model             :string]
   [:error             {:optional true} :string]])

;; =============================================================================
;; ProviderConfig — one provider's connection configuration
;; =============================================================================

(def ProviderConfig
  "Configuration for a single AI provider."
  [:map
   [:provider  [:enum :ollama :anthropic :openai :no-op]]
   [:model     {:optional true} :string]
   [:base-url  {:optional true} :string]
   [:api-key   {:optional true} [:maybe :string]]
   [:timeout   {:optional true} :int]])

;; =============================================================================
;; AIConfig — top-level :wagoe/ai Integrant config
;; =============================================================================

(def AIConfig
  "Top-level AI module configuration."
  [:map
   [:provider  [:enum :ollama :anthropic :openai :no-op]]
   [:model     {:optional true} :string]
   [:base-url  {:optional true} :string]
   [:api-key   {:optional true} [:maybe :string]]
   [:timeout   {:optional true} :int]
   [:fallback  {:optional true} ProviderConfig]])

;; =============================================================================
;; Validation helpers
;; =============================================================================

(def ^:private message-validator (m/validator Message))
(def ^:private ai-response-validator (m/validator AIResponse))

(defn valid-message?
  "Returns true if the given map satisfies the Message schema."
  [msg]
  (message-validator msg))

(defn valid-ai-response?
  "Returns true if the given map satisfies the AIResponse schema."
  [resp]
  (ai-response-validator resp))
