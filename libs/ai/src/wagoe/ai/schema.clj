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
   [:provider  [:enum :ollama :anthropic :openai :replicate :no-op]]
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
   [:provider  [:enum :ollama :anthropic :openai :replicate :no-op]]
   [:model     {:optional true} :string]
   [:base-url  {:optional true} :string]
   [:api-key   {:optional true} [:maybe :string]]
   [:timeout   {:optional true} :int]
   [:fallback  {:optional true} ProviderConfig]])

;; =============================================================================
;; AdminEntityFile — what `bb ai admin-entity` writes
;; =============================================================================

;; The admin's own EntityConfig (wagoe.admin.schema) describes a merged config:
;; its FieldConfig requires :name and :widget, which introspection supplies and
;; a file of overrides never carries. This is the file's shape: the keys of the
;; admin's EntityOverrides, closed at the same levels, with values typed where
;; EntityConfig types them. The ai library cannot depend on admin, so a root
;; test pins the two together.

(def AdminFieldType
  "Field types the admin renders — wagoe.admin.schema/FieldType."
  [:enum :uuid :string :int :decimal :boolean :instant :date :enum :json :text :binary])

(def AdminFieldWidget
  "Widgets the admin renders — wagoe.admin.schema/FieldWidget."
  [:enum :text-input :email-input :password-input :number-input :checkbox :select
   :multiselect :textarea :date-input :datetime-input :file-input :color-input
   :url-input :hidden])

(def AdminFieldOverride
  [:map {:closed true}
   [:name          {:optional true} :keyword]
   [:type          {:optional true} AdminFieldType]
   [:widget        {:optional true} AdminFieldWidget]
   [:label         {:optional true} :string]
   [:required      {:optional true} :boolean]
   [:readonly      {:optional true} :boolean]
   [:hidden        {:optional true} :boolean]
   [:searchable    {:optional true} :boolean]
   [:sortable      {:optional true} :boolean]
   [:filterable    {:optional true} :boolean]
   [:primary-key   {:optional true} :any]
   [:default-value {:optional true} :any]
   [:options       {:optional true} [:vector [:tuple :keyword :string]]]
   [:min           {:optional true} :int]
   [:max           {:optional true} :int]
   [:pattern       {:optional true} :string]
   [:help-text     {:optional true} :string]
   [:placeholder   {:optional true} :string]
   [:width         {:optional true} [:int {:min 1}]]
   [:rows          {:optional true} :any]])

(def AdminHasMany
  [:map {:closed true}
   [:entity      :keyword]
   [:table       :keyword]
   [:foreign-key :keyword]
   [:label       {:optional true} :string]
   [:fields      {:optional true} [:vector :keyword]]
   [:editable    {:optional true} :boolean]
   [:min         {:optional true} [:int {:min 0}]]
   [:on-delete   {:optional true} [:enum :cascade :restrict]]])

(def AdminEntityConfig
  [:map {:closed true}
   [:label           :string]
   [:label-singular  {:optional true} :string]
   [:table-name      :keyword]
   [:primary-key     {:optional true} :keyword]
   [:list-fields     {:optional true} [:vector :keyword]]
   [:detail-fields   {:optional true} [:vector :keyword]]
   [:search-fields   {:optional true} [:vector :keyword]]
   [:editable-fields {:optional true} [:vector :keyword]]
   [:hide-fields     {:optional true} [:set :keyword]]
   [:readonly-fields {:optional true} [:set :keyword]]
   [:fields          {:optional true} [:map-of :keyword AdminFieldOverride]]
   [:field-order     {:optional true} [:vector :keyword]]
   [:field-groups    {:optional true} [:vector [:map {:closed true}
                                                [:id :keyword]
                                                [:label :string]
                                                [:fields [:vector :keyword]]]]]
   [:default-sort     {:optional true} :keyword]
   [:default-sort-dir {:optional true} [:enum :asc :desc]]
   [:icon            {:optional true} :string]
   [:description     {:optional true} :string]
   [:soft-delete     {:optional true} :boolean]
   [:create-redirect-url {:optional true} :any]
   [:permissions     {:optional true} [:map {:closed true}
                                       [:create {:optional true} :boolean]
                                       [:create-hint {:optional true} :string]]]
   [:ui              {:optional true} [:map {:closed true}
                                       [:field-grouping {:optional true}
                                        [:map {:closed true}
                                         [:other-label {:optional true} :string]]]]]
   [:has-many        {:optional true} [:vector AdminHasMany]]
   [:sidebar-hidden  {:optional true} :boolean]
   [:parent-context  {:optional true} [:map {:closed true}
                                       [:label :string]
                                       [:fields [:vector :keyword]]]]
   [:query-overrides {:optional true} [:map {:closed true}
                                       [:from {:optional true} :any]
                                       [:join {:optional true} :any]
                                       [:select {:optional true} :any]
                                       [:field-aliases {:optional true} :any]
                                       [:soft-delete-table {:optional true} :any]]]
   [:split-table-update {:optional true} [:map {:closed true}
                                          [:secondary-table {:optional true} :any]
                                          [:secondary-fields {:optional true} :any]]]
   ;; The workflow is the entity's, not a field's: there is no :widget for it.
   [:workflow        {:optional true} [:map {:closed true} [:entity-type :keyword]]]])

(def AdminEntityFile
  "One or more entity configs keyed by entity name."
  [:and [:map-of :keyword AdminEntityConfig] [:fn seq]])

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
