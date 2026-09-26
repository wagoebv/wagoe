(ns wagoe.scaffolder.schema
  "Scaffolder module schemas for module generation inputs and outputs."
  (:require [clojure.string :as str]
            [wagoe.scaffolder.core.template :as template]))

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
   :decimal                                                 ; Decimal number
   :relation])                                              ; Foreign key to another entity

(def FieldShape
  "The keys a field definition may carry."
  [:map {:title "Field Definition"}
   [:name :keyword]                                         ; Field name (kebab-case)
   [:type FieldType]                                        ; Field type
   [:required {:optional true} :boolean]                    ; Is field required?
   [:unique {:optional true} :boolean]                      ; Is field unique?
   [:default {:optional true} :any]                         ; Default value
   [:enum-values {:optional true} [:vector :keyword]]       ; For enum type
   [:min {:optional true} :int]                             ; Min length/value
   [:max {:optional true} :int]                             ; Max length/value
   ;; Both are interpolated into DDL, so both are allowlisted rather than
   ;; merely non-blank. Here and not only in the CLI parser: `generate-module`
   ;; validates every request against this schema, so the MCP tool and any
   ;; direct caller pass through it too (BOU-480 review).
   [:references {:optional true}                            ; For relation type: entity referenced
    [:re template/entity-name-pattern]]
   [:references-table {:optional true}                      ; For relation type: target's table, when it is not the default plural
    [:re template/table-name-pattern]]
   [:on-delete {:optional true}                             ; For relation type
    [:enum :cascade :restrict :set-null :no-action]]
   [:description {:optional true} :string]])                ; Field documentation

(def FieldDefinition
  "Schema for a field definition in an entity.

   An `:enum` field must name its values: `[:enum]` is a Malli schema nothing
   satisfies, so a module generated without them rejected every write of that
   field (BOU-447). A `:relation` must name what it references, for the same
   reason — without it there is nothing to generate but a bare UUID column
   (BOU-480)."
  [:and
   FieldShape
   [:fn {:error/message "an enum field needs a non-empty :enum-values"}
    (fn [{:keys [type enum-values]}]
      (or (not= :enum type) (seq enum-values)))]
   [:fn {:error/message "a relation field needs :references"}
    (fn [{:keys [type references]}]
      (or (not= :relation type) (not (str/blank? references))))]
   ;; `NOT NULL ... ON DELETE SET NULL` is accepted by the database and then
   ;; fails on the first delete of a parent row: the foreign key action sets a
   ;; column the table forbids to be null. Refused rather than silently
   ;; dropping whichever of the two the caller meant less (BOU-480 review).
   [:fn {:error/message ":on-delete :set-null needs a nullable column, so the field cannot be :required"}
    (fn [{:keys [type on-delete required]}]
      (not (and (= :relation type) (= :set-null on-delete) required)))]
   ;; :default goes into DDL (BOU-494).
   [:fn {:error/message ":default must suit the field's type: a number for int/decimal, true/false for boolean, one of the values for enum, none for relation"}
    template/valid-default?]])

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
   ;; Which interfaces to generate. Optional, and every key in it optional:
   ;; absent means yes, so leaving it out is the full module. It was required,
   ;; while being read by nothing — and `:cli` named an interface the
   ;; scaffolder has never had a generator for (BOU-479).
   [:interfaces {:optional true}
    [:map
     [:http {:optional true} :boolean]
     [:web {:optional true} :boolean]]]
   [:features                                               ; Optional features
    {:optional true}
    [:map
     [:audit {:optional true} :boolean]
     [:soft-delete {:optional true} :boolean]
     [:pagination {:optional true} :boolean]]]
   [:dry-run {:optional true} :boolean]                     ; Preview without writing
   ;; Overwrite files that already exist. Without it, generation refuses rather
   ;; than replacing a module someone has edited — the CLI declared this flag
   ;; and threaded it into the request, and nothing read it (BOU-308).
   [:force {:optional true} :boolean]
   [:output-dir {:optional true} :string]])                 ; Write somewhere other than cwd

;; =============================================================================
;; Module Generation Result
;; =============================================================================

(def GeneratedFile
  "Schema for a generated file."
  [:map {:title "Generated File"}
   [:path :string]                                          ; File path
   [:content :string]                                       ; File content
   [:action [:enum :create :update :skip :overwrite]]])                ; Action taken

(def ModuleGenerationResult
  "Schema for module generation result."
  [:map {:title "Module Generation Result"}
   [:success :boolean]
   [:module-name :string]
   [:files [:vector GeneratedFile]]
   [:errors {:optional true} [:vector :string]]
   [:warnings {:optional true} [:vector :string]]])
