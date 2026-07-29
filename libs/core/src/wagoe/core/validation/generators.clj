;; ^:wagoe/allow-throw — property-based test-data generator tooling; the
;; throws signal generation failure (after retries) or misuse of the generator
;; API, which is intentional developer tooling behaviour.
(ns ^:wagoe/allow-throw wagoe.core.validation.generators
  "Property-based test data generators for validation testing.
  
   This namespace provides pure functions for generating test data from Malli schemas,
   supporting both valid and invalid data generation with deterministic seeding.
   
   Key Features:
   - Valid data generation conforming to schemas
   - Invalid data generation with specific violation types
   - Wagoe case generation (min/max values, edge cases)
   - Rule-aware generation for targeted testing
   - Deterministic seeding for reproducible tests
   
   Design Principles:
   - Pure functions only (no side effects, no I/O)
   - Schema lookup via injected functions (no direct registry access)
   - Deterministic with explicit seeds
   - FC/IS compliant (all generators are functional core)
   
   Usage:
     ;; Generate valid data
     (gen-valid-one User {:seed 42})
     => {:id #uuid \"...\" :email \"user@example.com\" ...}
     
     ;; Generate invalid data
     (gen-invalid-one User :missing-required {:seed 42 :field :email})
     => {:id #uuid \"...\" :name \"John\" ...} ; missing :email
     
     ;; Generate boundary cases
     (gen-boundaries User {:seed 42})
     => [{:name \"\" ...} {:name \"a\" ...} {:name \"aaa...\" ...}]"
  (:require [malli.core :as m]
            [malli.generator :as mg]))

;; =============================================================================
;; Constants and Configuration
;; =============================================================================

(def default-seed
  "Default seed for deterministic generation."
  42)

(def default-size
  "Default size parameter for Malli generator."
  5)

(def default-count
  "Default number of samples to generate."
  10)

;; =============================================================================
;; Schema Resolution
;; =============================================================================

(defn resolve-schema
  "Resolve schema from keyword or schema value using optional registry function.
  
   Args:
     schema-or-key: Either a Malli schema or a keyword to look up
     opts: Options map with optional :registry function (fn [k] schema)
   
   Returns:
     Malli schema or nil if not found
   
   Example:
     (resolve-schema :user {:registry (fn [k] User)})
     => User schema
     
     (resolve-schema User {})
     => User schema"
  [schema-or-key opts]
  (if (keyword? schema-or-key)
    (when-let [registry-fn (:registry opts)]
      (registry-fn schema-or-key))
    schema-or-key))

;; =============================================================================
;; Valid Data Generation
;; =============================================================================

(defn rng-from-seed
  "Create deterministic RNG from seed.
  
   Args:
     seed: Long integer seed value
   
   Returns:
     Java Random instance
   
   Example:
     (rng-from-seed 42)
     => #object[java.util.Random ...]"
  [seed]
  (java.util.Random. (long seed)))

(defn gen-valid-one
  "Generate single valid sample from schema.
  
   Args:
     schema-or-key: Malli schema or keyword to resolve
     opts: Options map with :seed, :size, :registry
   
   Returns:
     Single generated sample conforming to schema
   
   Example:
     (gen-valid-one User {:seed 42 :size 5})
     => {:id #uuid \"...\" :email \"user@example.com\" :name \"Alice\" ...}"
  [schema-or-key opts]
  (let [schema (resolve-schema schema-or-key opts)
        seed (or (:seed opts) default-seed)
        size (or (:size opts) default-size)]
    (when schema
      (try
        (mg/generate schema {:seed seed :size size})
        (catch Exception e
          (throw (ex-info "Failed to generate valid data"
                          {:schema schema
                           :seed seed
                           :size size
                           :error (.getMessage e)}
                          e)))))))

(defn gen-valid
  "Generate sequence of valid samples from schema.
  
   Args:
     schema-or-key: Malli schema or keyword to resolve
     opts: Options map with :seed, :size, :count, :registry
   
   Returns:
     Lazy sequence of generated samples
   
   Example:
     (take 3 (gen-valid User {:seed 42 :count 5}))
     => ({:id #uuid \"...\" ...} {:id #uuid \"...\" ...} ...)"
  [schema-or-key opts]
  (let [schema (resolve-schema schema-or-key opts)
        count (or (:count opts) default-count)
        base-seed (or (:seed opts) default-seed)]
    (when schema
      (map (fn [i]
             (gen-valid-one schema (assoc opts :seed (+ base-seed i))))
           (range count)))))

;; =============================================================================
;; Schema Introspection
;; =============================================================================

;; =============================================================================
;; Invalid Data Generation - Strategies
;; =============================================================================

(def violation-types
  "Supported violation types for invalid data generation."
  #{:missing-required
    :wrong-type
    :wrong-format
    :out-of-range
    :too-long
    :too-short
    :unknown-key
    :enum-outside
    :boundary-underflow
    :boundary-overflow})

(defn- remove-required-field
  "Remove a required field from valid data.
  
   Args:
     data: Valid data map
     field: Field keyword to remove
     schema: Malli schema
   
   Returns:
     Data with field removed"
  [data field _schema]
  (dissoc data field))

(defn- apply-wrong-type
  "Replace field value with wrong type.
  
   Args:
     data: Valid data map
     field: Field keyword to corrupt
     schema: Malli schema
   
   Returns:
     Data with field type changed"
  [data field _schema]
  (let [current-value (get data field)
        wrong-value (cond
                      (string? current-value) 42
                      (number? current-value) "not-a-number"
                      (boolean? current-value) "not-a-boolean"
                      (map? current-value) []
                      (vector? current-value) {}
                      (keyword? current-value) 123
                      (uuid? current-value) "not-a-uuid"
                      :else nil)]
    (assoc data field wrong-value)))

(defn- apply-wrong-format
  "Apply wrong format to field based on common patterns.
  
   Args:
     data: Valid data map
     field: Field keyword to corrupt
     schema: Malli schema
   
   Returns:
     Data with invalid format"
  [data field _schema]
  (let [current-value (get data field)
        ;; Common invalid formats
        wrong-value (cond
                      ;; Email-like patterns
                      (and (string? current-value) (re-find #"@" current-value))
                      "not-an-email"

                      ;; UUID-like strings
                      (string? current-value)
                      "not-a-valid-format"

                      :else
                      "invalid-format")]
    (assoc data field wrong-value)))

(defn- apply-out-of-range
  "Apply out-of-range value to numeric/string field.
  
   Args:
     data: Valid data map
     field: Field keyword to corrupt
     schema: Malli schema
     opts: Options with :overflow? boolean
   
   Returns:
     Data with out-of-range value"
  [data field _schema opts]
  (let [current-value (get data field)
        overflow? (get opts :overflow? true)
        wrong-value (cond
                      (number? current-value)
                      (if overflow? Integer/MAX_VALUE Integer/MIN_VALUE)

                      (string? current-value)
                      (if overflow?
                        (apply str (repeat 10000 "x"))
                        "")

                      :else
                      current-value)]
    (assoc data field wrong-value)))

(defn- add-unknown-key
  "Add unknown key to data map.
  
   Args:
     data: Valid data map
     seed: Seed for deterministic key generation
   
   Returns:
     Data with extra key"
  [data seed]
  (let [rng (rng-from-seed seed)
        unknown-key (keyword (str "unknown-field-" (.nextInt rng 1000)))]
    (assoc data unknown-key "unexpected-value")))

(defn- apply-enum-outside
  "Replace enum field with value outside allowed set.
  
   Args:
     data: Valid data map
     field: Field keyword to corrupt
     schema: Malli schema
   
   Returns:
     Data with invalid enum value"
  [data field _schema]
  (assoc data field :invalid-enum-value))

;; =============================================================================
;; Invalid Data Generation - Main API
;; =============================================================================

(defn gen-invalid-one
  "Generate single invalid sample from schema with specific violation.
  
   Args:
     schema-or-key: Malli schema or keyword to resolve
     violation-type: Keyword from violation-types set
     opts: Options map with :seed, :field, :registry
   
   Returns:
     Single generated sample that violates schema
   
   Example:
     (gen-invalid-one User :missing-required {:seed 42 :field :email})
     => {:id #uuid \"...\" :name \"Alice\" ...} ; missing :email"
  [schema-or-key violation-type opts]
  (let [schema (resolve-schema schema-or-key opts)
        seed (or (:seed opts) default-seed)
        field (or (:field opts) :unknown)
        valid-data (gen-valid-one schema (assoc opts :seed seed))]
    (when (and schema valid-data)
      (try
        (let [invalid-data
              (case violation-type
                :missing-required
                (remove-required-field valid-data field schema)

                :wrong-type
                (apply-wrong-type valid-data field schema)

                :wrong-format
                (apply-wrong-format valid-data field schema)

                :out-of-range
                (apply-out-of-range valid-data field schema opts)

                :too-long
                (apply-out-of-range valid-data field schema (assoc opts :overflow? true))

                :too-short
                (apply-out-of-range valid-data field schema (assoc opts :overflow? false))

                :unknown-key
                (add-unknown-key valid-data seed)

                :enum-outside
                (apply-enum-outside valid-data field schema)

                :boundary-underflow
                (apply-out-of-range valid-data field schema (assoc opts :overflow? false))

                :boundary-overflow
                (apply-out-of-range valid-data field schema (assoc opts :overflow? true))

                valid-data)]
          ;; Verify it actually fails validation
          ;; NOTE: Some violations like :unknown-key may not fail validation
          ;; if the schema allows extra keys (default for Malli :map)
          (if (and (m/validate schema invalid-data)
                   (not (#{:unknown-key} violation-type)))
            (throw (ex-info "Generated data unexpectedly passed validation"
                            {:schema schema
                             :violation-type violation-type
                             :data invalid-data}))
            invalid-data))
        (catch Exception e
          (throw (ex-info "Failed to generate invalid data"
                          {:schema schema
                           :violation-type violation-type
                           :seed seed
                           :field field
                           :error (.getMessage e)}
                          e)))))))

(defn gen-invalid
  "Generate sequence of invalid samples from schema.
  
   Args:
     schema-or-key: Malli schema or keyword to resolve
     violation-type: Keyword from violation-types set
     opts: Options map with :seed, :count, :field, :registry
   
   Returns:
     Lazy sequence of generated invalid samples
   
   Example:
     (take 3 (gen-invalid User :missing-required {:seed 42 :field :email :count 5}))
     => ({:id #uuid \"...\" :name \"Alice\" ...} ...)"
  [schema-or-key violation-type opts]
  (let [schema (resolve-schema schema-or-key opts)
        count (or (:count opts) default-count)
        base-seed (or (:seed opts) default-seed)]
    (when schema
      (map (fn [i]
             (gen-invalid-one schema violation-type
                              (assoc opts :seed (+ base-seed i))))
           (range count)))))

;; =============================================================================
;; Wagoe Case Generation
;; =============================================================================

(defn gen-boundaries
  "Generate boundary case values for schema fields.
  
   Args:
     schema-or-key: Malli schema or keyword to resolve
     opts: Options map with :seed, :registry
   
   Returns:
     Vector of boundary case samples
   
   Example:
     (gen-boundaries User {:seed 42})
     => [{:name \"\" ...} {:name \"a\" ...} {:name \"aaa...\" ...}]"
  [schema-or-key opts]
  (let [schema (resolve-schema schema-or-key opts)
        base-data (gen-valid-one schema opts)]
    (when (and schema base-data)
      ;; Generate boundary cases for string fields
      (let [boundaries
            (cond
              ;; Map schema - generate boundaries for string fields
              (= :map (m/type schema))
              (let [children (m/children schema)
                    string-fields (->> children
                                       (filter (fn [child]
                                                 ;; Malli children structure: [key properties schema]
                                                 (let [child-schema (if (= 3 (count child))
                                                                      (nth child 2)  ; [k props schema]
                                                                      (second child))] ; [k schema]
                                                   (= :string (m/type child-schema)))))
                                       (map first))]
                (mapcat (fn [field]
                          [(assoc base-data field "")        ; Empty string
                           (assoc base-data field "a")       ; Single char
                           (assoc base-data field (apply str (repeat 255 "x")))])  ; Max length
                        string-fields))

              :else
              [base-data])]
        (vec (distinct boundaries))))))

;; =============================================================================
;; Rule-Aware Generation
;; =============================================================================

(defn gen-for-rule
  "Generate examples for specific validation rule.
  
   Args:
     rule-id: Validation rule identifier keyword
     opts: Options map with:
           - :type (:valid or :invalid)
           - :violation (violation type if :type is :invalid)
           - :seed
           - :resolve-schema (fn [rule-id] schema)
           - :rule->schema-key (fn [rule-id] keyword)
   
   Returns:
     Generated sample for the rule
   
   Example:
     (gen-for-rule :user.email/required
                   {:type :invalid
                    :violation :missing-required
                    :seed 42
                    :resolve-schema my-resolver})
     => {:id #uuid \"...\" :name \"Alice\" ...}"
  [rule-id opts]
  (let [resolve-fn (or (:resolve-schema opts)
                       (throw (ex-info ":resolve-schema function required"
                                       {:rule-id rule-id})))
        schema (resolve-fn rule-id)
        gen-type (or (:type opts) :valid)
        seed (or (:seed opts) default-seed)]
    (when schema
      (case gen-type
        :valid
        (gen-valid-one schema (assoc opts :seed seed))

        :invalid
        (let [violation (or (:violation opts)
                            (throw (ex-info ":violation required for :invalid type"
                                            {:rule-id rule-id})))]
          (gen-invalid-one schema violation (assoc opts :seed seed)))))))

(defn gen-for-module
  "Generate examples across all rules in a module.
  
   Args:
     module-kw: Module keyword (e.g., :user, :billing)
     opts: Options map with:
           - :type (:valid or :invalid)
           - :seed
           - :list-rules (fn [module-kw] [rule-ids])
           - :resolve-schema (fn [rule-id] schema)
   
   Returns:
     Map of rule-id to generated samples
   
   Example:
     (gen-for-module :user
                     {:type :valid
                      :seed 42
                      :list-rules my-list-fn
                      :resolve-schema my-resolver})
     => {:user.email/required {:id #uuid \"...\" ...}
         :user.name/required {:id #uuid \"...\" ...}}"
  [module-kw opts]
  (let [list-fn (or (:list-rules opts)
                    (throw (ex-info ":list-rules function required"
                                    {:module module-kw})))
        rule-ids (list-fn module-kw)
        base-seed (or (:seed opts) default-seed)]
    (into {}
          (map-indexed (fn [i rule-id]
                         [rule-id
                          (gen-for-rule rule-id
                                        (assoc opts :seed (+ base-seed i)))])
                       rule-ids))))
