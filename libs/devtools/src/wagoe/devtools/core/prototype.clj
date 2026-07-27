(ns wagoe.devtools.core.prototype
  "Pure functions for mapping prototype specs to scaffolder contexts
   and migration definitions.
   Delegates to scaffolder's template functions for context building
   to ensure generated output matches what generators expect."
  (:require [wagoe.scaffolder.core.template :as tmpl]))

(defn malli->sql-type
  "Map a Malli type spec to a SQL column type."
  [malli-spec]
  (let [type-kw (if (vector? malli-spec) (first malli-spec) malli-spec)]
    (case type-kw
      :string     "VARCHAR(255)"
      :text       "TEXT"
      :int        "INTEGER"
      :integer    "INTEGER"
      :decimal    "DECIMAL"
      :double     "DOUBLE"
      :float      "FLOAT"
      :boolean    "BOOLEAN"
      :date       "DATE"
      :instant    "TIMESTAMP"
      :timestamp  "TIMESTAMP"
      :uuid       "UUID"
      :enum       "VARCHAR(50)"
      :map        "TEXT"
      :json       "TEXT"
      "VARCHAR(255)")))

(def ^:private malli-type->scaffolder-type
  "Map Malli type keywords to the scaffolder's known field types."
  {:string  :string
   :int     :int
   :integer :int
   :decimal :decimal
   :double  :decimal
   :float   :decimal
   :boolean :boolean
   :date    :date
   :instant :inst
   :uuid    :uuid
   :enum    :enum
   :text    :text
   :email   :email
   :map     :json})

(defn- malli-spec->scaffolder-field
  "Convert a [field-name malli-spec] pair into a scaffolder field definition.
   Reads :optional and :unique from the Malli props map (second element of
   vector specs like [:string {:optional true}])."
  [[field-name malli-spec]]
  (let [type-kw (if (vector? malli-spec) (first malli-spec) malli-spec)
        props   (when (and (vector? malli-spec)
                           (>= (count malli-spec) 2)
                           (map? (second malli-spec)))
                  (second malli-spec))
        scaffolder-type (get malli-type->scaffolder-type type-kw :string)
        base {:name     (name field-name)
              :type     scaffolder-type
              :required (not (:optional props))
              :unique   (boolean (:unique props))}]
    (if (and (= type-kw :enum) (vector? malli-spec))
      (let [rest-items (vec (remove map? (rest malli-spec)))
            ;; Support both [:enum :a :b :c] (standard Malli) and
            ;; [:enum [:a :b :c]] (nested vector shorthand)
            enum-values (if (and (= 1 (count rest-items))
                                 (vector? (first rest-items)))
                          (first rest-items)
                          rest-items)]
        (assoc base :enum-values enum-values))
      base)))

(defn build-scaffold-context
  "Map a prototype spec to a scaffolder-compatible context.
   Delegates to template/build-module-context to produce the exact
   format that generators expect (including :field-name-kebab, :malli-type, etc.)."
  [module-name spec]
  (let [field-defs (mapv malli-spec->scaffolder-field (:fields spec))
        request {:module-name module-name
                 :entities    [{:name   module-name
                                :fields field-defs}]}]
    (tmpl/build-module-context request)))

(defn endpoints-to-generators
  "Map endpoint keywords to the set of generators needed."
  [endpoints]
  (let [endpoint-set (set endpoints)
        base #{:schema :ports :core :service :persistence}
        needs-http (some #{:crud :list :search} endpoint-set)]
    (vec (cond-> base
           needs-http (conj :http)))))

(defn build-migration-spec
  "Convert a field spec to migration column definitions.
   Reads :optional and :unique from Malli props to set NOT NULL and UNIQUE."
  [_module-name fields]
  (let [user-columns (mapv (fn [[field-name malli-spec]]
                             (let [props (when (and (vector? malli-spec)
                                                    (>= (count malli-spec) 2)
                                                    (map? (second malli-spec)))
                                           (second malli-spec))]
                               (cond-> {:name     field-name
                                        :sql-type (malli->sql-type malli-spec)
                                        :not-null (not (:optional props))}
                                 (:unique props) (assoc :unique true))))
                           fields)]
    (vec (concat
          [{:name :id :sql-type "UUID" :primary-key true}]
          user-columns
          [{:name :created-at :sql-type "TIMESTAMP" :not-null true :default "CURRENT_TIMESTAMP"}
           {:name :updated-at :sql-type "TIMESTAMP" :not-null true :default "CURRENT_TIMESTAMP"}]))))
