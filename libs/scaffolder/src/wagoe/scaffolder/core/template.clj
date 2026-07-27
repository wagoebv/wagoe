(ns wagoe.scaffolder.core.template
  "Pure template rendering functions for module generation.
   
   This namespace provides pure functions for transforming entity definitions
   into code templates. All functions are deterministic and have no side effects."
  (:require [clojure.string :as str]))

;; =============================================================================
;; String Transformation Utilities
;; =============================================================================

(defn kebab->pascal
  "Convert kebab-case to PascalCase.
   
   Args:
     s - String in kebab-case
   
   Returns:
     String in PascalCase
   
   Pure: true
   
   Example:
     (kebab->pascal \"user-profile\") => \"UserProfile\""
  [s]
  (->> (str/split (name s) #"-")
       (map str/capitalize)
       (str/join "")))

(defn kebab->snake
  "Convert kebab-case to snake_case.
   
   Args:
     s - String in kebab-case
   
   Returns:
     String in snake_case
   
   Pure: true
   
   Example:
     (kebab->snake \"user-profile\") => \"user_profile\""
  [s]
  (str/replace (name s) #"-" "_"))

(defn pluralize
  "Simple pluralization (just adds 's' for now).
   
   Args:
     s - Singular string
   
   Returns:
     Plural string
   
   Pure: true
   
   Example:
     (pluralize \"customer\") => \"customers\""
  [s]
  (str s "s"))

;; =============================================================================
;; Field Type Mappings
;; =============================================================================

(defn field-type->malli
  "Convert scaffolder field type to Malli schema type.
   
   Args:
     field-def - Field definition map with :type key
   
   Returns:
     Malli schema keyword or vector
   
   Pure: true
   
   Example:
     (field-type->malli {:type :string}) => :string
     (field-type->malli {:type :enum :enum-values [:a :b]}) => [:enum :a :b]"
  [{:keys [type enum-values]}]
  (case type
    :string :string
    :text :string
    :int :int
    :uuid :uuid
    :boolean :boolean
    :email [:re {:error/message "Invalid email format"}
            #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9]+([.-][a-zA-Z0-9]+)*\.[a-zA-Z]{2,}$"]
    :enum (into [:enum] enum-values)
    :inst 'inst?
    :date [:re {:error/message "Must be an ISO date (YYYY-MM-DD)"}
           #"^\d{4}-\d{2}-\d{2}$"]
    :json :map
    :decimal :double))

(defn field-type->sql
  "Convert scaffolder field type to SQL type.

   Generates PostgreSQL-compatible types. The production DDL generator
   in platform/utils/schema.clj handles dialect-specific conversions.

   Args:
     field-def - Field definition map with :type key

   Returns:
     SQL type string

   Pure: true

   Example:
     (field-type->sql {:type :string}) => \"VARCHAR(255)\"
     (field-type->sql {:type :uuid}) => \"UUID\""
  [{:keys [type]}]
  (case type
    :string "VARCHAR(255)"
    :text "TEXT"
    :int "INTEGER"
    :uuid "UUID"
    :boolean "BOOLEAN"
    :email "VARCHAR(255)"
    :enum "VARCHAR(50)"
    :inst "TIMESTAMPTZ"
    :date "DATE"
    :json "JSONB"
    :decimal "DOUBLE PRECISION"))

;; =============================================================================
;; Template Context Building
;; =============================================================================

(defn build-field-context
  "Build template context for a single field.
   
   Args:
     field-def - Field definition map
   
   Returns:
     Map with template placeholders for the field
   
   Pure: true"
  [field-def]
  {:field-name (name (:name field-def))
   :field-name-kebab (name (:name field-def))
   :field-name-snake (kebab->snake (:name field-def))
   :field-name-pascal (kebab->pascal (:name field-def))
   :field-type (:type field-def)
   :field-required (get field-def :required true)
   :field-unique (get field-def :unique false)
   :malli-type (field-type->malli field-def)
   :sql-type (field-type->sql field-def)})

(defn build-entity-context
  "Build template context for an entity.
   
   Args:
     entity-def - Entity definition map
     module-name - Module name string
   
   Returns:
     Map with template placeholders for the entity
   
   Pure: true"
  [entity-def module-name]
  (let [entity-name (:name entity-def)
        entity-lower (str/lower-case entity-name)
        entity-plural (or (:plural entity-def) (pluralize entity-lower))]
    {:module-name module-name
     :entity-name entity-name
     :entity-lower entity-lower
     :entity-kebab (str/replace entity-lower #"\s+" "-")
     :entity-snake (kebab->snake entity-lower)
     :entity-plural entity-plural
     :entity-plural-snake (kebab->snake entity-plural)
     :entity-table (kebab->snake entity-plural)
     :fields (mapv build-field-context (:fields entity-def))
     :description (:description entity-def "")}))

(defn build-module-context
  "Build complete template context for module generation.
   
   Args:
     request - Module generation request map
   
   Returns:
     Map with all template placeholders
   
   Pure: true"
  [request]
  (let [module-name (:module-name request)
        entities (:entities request)
        base-ns (or (:base-ns request) "wagoe")]
    {:module-name module-name
     :module-pascal (kebab->pascal module-name)
     :base-ns base-ns
     :base-ns-path (str/replace base-ns "." "/")
     :entities (mapv #(build-entity-context % module-name) entities)
     :interfaces (:interfaces request)
     :features (merge {:audit false :soft-delete false :pagination true}
                      (:features request {}))}))
