(ns wagoe.scaffolder.schema
  "Scaffolder module schemas for module generation inputs and outputs.")

;; =============================================================================
;; Field and Entity Definitions
;; =============================================================================

(def FieldType
  "Supported field types for entity fields."
  [:enum
   :string                                                  ; Variable length string
   :text                                                    ; Long text
   :int                                                     ; Integer
   :uuid                                                    ; UUID
   :boolean                                                 ; Boolean
   :email                                                   ; Email (validated string)
   :enum                                                    ; Enumeration
   :inst                                                    ; Instant/timestamp
   :json                                                    ; JSON/map data
   :decimal])                                               ; Decimal number

(def FieldDefinition
  "Schema for a field definition in an entity."
  [:map {:title "Field Definition"}
   [:name :keyword]                                         ; Field name (kebab-case)
   [:type FieldType]                                        ; Field type
   [:required {:optional true} :boolean]                    ; Is field required?
   [:unique {:optional true} :boolean]                      ; Is field unique?
   [:default {:optional true} :any]                         ; Default value
   [:enum-values {:optional true} [:vector :keyword]]       ; For enum type
   [:min {:optional true} :int]                             ; Min length/value
   [:max {:optional true} :int]                             ; Max length/value
   [:description {:optional true} :string]])                ; Field documentation

(def EntityDefinition
  "Schema for an entity definition."
  [:map {:title "Entity Definition"}
   [:name :string]                                          ; Entity name (PascalCase)
   [:plural {:optional true} :string]                       ; Plural form (e.g., "customers")
   [:fields [:vector FieldDefinition]]                      ; Entity fields
   [:description {:optional true} :string]])                ; Entity documentation

;; =============================================================================
;; Module Generation Request
;; =============================================================================

(def ModuleGenerationRequest
  "Schema for module generation request."
  [:map {:title "Module Generation Request"}
   [:module-name :string]                                   ; Module name (lowercase, e.g., "customer")
   [:base-ns {:optional true} :string]                      ; Base namespace + path (default: "wagoe")
   [:entities [:vector EntityDefinition]]                   ; Entities to generate
   [:interfaces                                             ; Which interfaces to generate
    [:map
     [:http {:optional true} :boolean]
     [:cli {:optional true} :boolean]
     [:web {:optional true} :boolean]]]
   [:features                                               ; Optional features
    {:optional true}
    [:map
     [:audit {:optional true} :boolean]
     [:soft-delete {:optional true} :boolean]
     [:pagination {:optional true} :boolean]]]
   [:dry-run {:optional true} :boolean]])                   ; Preview without writing

;; =============================================================================
;; Module Generation Result
;; =============================================================================

(def GeneratedFile
  "Schema for a generated file."
  [:map {:title "Generated File"}
   [:path :string]                                          ; File path
   [:content :string]                                       ; File content
   [:action [:enum :create :update :skip]]])                ; Action taken

(def ModuleGenerationResult
  "Schema for module generation result."
  [:map {:title "Module Generation Result"}
   [:success :boolean]
   [:module-name :string]
   [:files [:vector GeneratedFile]]
   [:errors {:optional true} [:vector :string]]
   [:warnings {:optional true} [:vector :string]]])
