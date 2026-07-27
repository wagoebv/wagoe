(ns wagoe.core.validation.context
  "Contextual message rendering and example payload generation.
  
   This namespace extends the message templating system to support:
   - Operation-aware messages (create, update, delete)
   - Role-based messaging
   - Multi-tenant context
   - Example payload generation using Malli
   
   Design Principles:
   - Opt-in: Example generation requires explicit flag
   - Deterministic: Uses fixed seeds for reproducible examples
   - Redacted: Sensitive fields are replaced with placeholders
   - Minimal: Examples include only required + relevant fields"
  (:require [clojure.string :as str]
            [wagoe.core.validation.messages :as messages]
            [malli.core :as m]
            [malli.generator :as mg]))

;; =============================================================================
;; Context Types
;; =============================================================================

(def operation-types
  "Valid operation types for contextual messaging."
  #{:create :update :delete :validate :query})

(def role-types
  "Common role types for role-based messaging."
  #{:admin :user :viewer :moderator :guest})

;; =============================================================================
;; Contextual Message Templates
;; =============================================================================

(def operation-specific-templates
  "Operation-specific message templates.
  
   These override default templates when operation context is provided."
  {:create
   {:required "{{field-name}} is required when creating a {{entity}}"
    :invalid "{{field-name}} is invalid for {{entity}} creation"
    :invalid-format "{{field-name}} must be in valid format for {{entity}} creation"
    :duplicate "A {{entity}} with this {{field-name}} already exists"}

   :update
   {:required "{{field-name}} is required when updating a {{entity}}"
    :invalid "{{field-name}} is invalid when updating a {{entity}}"
    :invalid-format "{{field-name}} has an invalid format for {{entity}} update"
    :forbidden "{{field-name}} cannot be changed during update"
    :not-found "Cannot update {{entity}}: {{entity}} not found"}

   :delete
   {:forbidden "Cannot delete {{entity}}: {{reason}}"
    :not-found "Cannot delete {{entity}}: {{entity}} not found"
    :dependency "Cannot delete {{entity}}: referenced by {{dependency}}"}})

(def role-specific-guidance
  "Role-specific guidance appended to messages."
  {:admin
   "You have full access to perform this operation."

   :viewer
   "You have view-only access. Contact your administrator to request changes."

   :user
   "You have limited access. Contact your administrator if you need different permissions."

   :moderator
   "You have moderation privileges. Contact your administrator for elevated permissions."

   :guest
   "Not logged in. Please log in to perform this operation."})

;; =============================================================================
;; Context Application
;; =============================================================================

(defn get-operation-template
  "Get operation-specific template if available.
  
   Args:
     operation: Operation type keyword
     code: Error code (may be namespaced like :user.email/required)
   
   Returns:
     Template string or nil"
  [operation code]
  (when (operation-types operation)
    ;; Try exact code first, then base type
    (or (get-in operation-specific-templates [operation code])
        (when (namespace code)
          (let [base-type (keyword (name code))]
            (get-in operation-specific-templates [operation base-type]))))))

(defn apply-operation-context
  "Apply operation context to message parameters.
  
   Args:
     params: Parameter map
     context: Context map with :operation, :entity
   
   Returns:
     Updated params with operation context"
  [params context]
  (let [operation (:operation context)
        entity (:entity context "item")]
    (cond-> params
      operation (assoc :operation operation)
      entity (assoc :entity entity))))

(defn apply-role-context
  "Add role-specific guidance to message.
  
   Args:
     message: Base error message
     context: Context map with :role
   
   Returns:
     Message with role-specific guidance appended"
  [message context]
  (if-let [role-guidance (get role-specific-guidance (:role context))]
    (str message " " role-guidance)
    message))

(defn apply-tenant-context
  "Apply multi-tenant context to parameters.
  
   Args:
     params: Parameter map
     context: Context map with :tenant-id, :tenant-name
   
   Returns:
     Updated params with tenant context"
  [params context]
  (cond-> params
    (:tenant-id context)
    (assoc :tenant-id (:tenant-id context))

    (:tenant-name context)
    (assoc :tenant-name (:tenant-name context))

    (:tenant-rules context)
    (merge (:tenant-rules context))))

;; =============================================================================
;; Example Payload Generation
;; =============================================================================

(def ^:private sensitive-fields
  "Fields that should be redacted in examples."
  #{:password :secret :token :api-key :auth-token :credit-card :ssn})

(def ^:private example-seed
  "Fixed seed for deterministic example generation."
  42)

(defn- redact-sensitive-fields
  "Replace sensitive field values with placeholders.
  
   Args:
     data: Generated example data
   
   Returns:
     Data with sensitive fields redacted"
  [data]
  (reduce-kv
   (fn [result k _v]
     (if (sensitive-fields k)
       (assoc result k (str "<" (name k) ">"))
       result))
   data
   data))

(defn- minimal-example
  "Generate minimal example with only required + relevant fields.
  
   Args:
     schema: Malli schema
     relevant-fields: Set of fields relevant to the error
     opts: Options map with :seed, :exclude-fields, :include-fields
   
   Returns:
     Minimal example map"
  [schema relevant-fields opts]
  (try
    (let [seed (or (:seed opts) example-seed)
          generated (mg/generate schema {:seed seed :size 1})
          ;; Get required fields from schema
          required-keys (when (= :map (m/type schema))
                          (let [children (m/children schema)]
                            (->> children
                                 (remove #(get-in % [1 :optional]))
                                 (map first)
                                 set)))
          ;; If include-fields specified, use only those + error field
          ;; Otherwise use required + relevant
          include-keys (if-let [inc-fields (:include-fields opts)]
                         (into relevant-fields inc-fields)
                         (into (or required-keys #{}) relevant-fields))
          ;; Apply exclusions
          exclude-keys (set (:exclude-fields opts))
          final-keys (remove exclude-keys include-keys)
          minimal (select-keys generated final-keys)]
      (redact-sensitive-fields minimal))
    (catch Exception _e
      nil)))

(defn generate-example-payload
  "Generate example valid payload for schema.
  
   Args:
     schema: Malli schema
     error-field: Field that caused the error (for relevance)
     opts: Options map with :include-fields, :exclude-fields, :seed
   
   Returns:
     Example payload map or nil
   
   Example:
     (generate-example-payload User :email {:include-fields [:name :role]})
     => {:email \"user@example.com\" :name \"John Doe\" :role :user}"
  [schema error-field opts]
  (when schema
    (let [relevant-fields (into #{error-field} (:include-fields opts))]
      (minimal-example schema relevant-fields opts))))

;; =============================================================================
;; Actionable Next Steps
;; =============================================================================

(def common-next-steps
  "Common next steps for error types."
  {:required
   ["Provide a value for the required field"
    "Check API documentation for required fields"
    "Ensure the field is not null or empty"]

   :invalid-format
   ["Verify the format matches the expected pattern"
    "Check the example in the error message"
    "Refer to API documentation for format requirements"]

   :invalid-value
   ["Check the 'Did you mean?' suggestion if provided"
    "Review the list of allowed values"
    "Verify correct spelling and casing"]

   :duplicate
   ["Choose a different unique value"
    "Check if updating the existing record is more appropriate"
    "Contact your administrator if this seems incorrect"]

   :forbidden
   ["Review the constraint mentioned in the error"
    "Check if an alternative operation is available"
    "Consult API documentation for allowed operations"]

   :not-found
   ["Verify the ID or reference exists"
    "Check if the resource was recently deleted"
    "Ensure you have permission to access the resource"]})

(defn get-next-steps
  "Get actionable next steps for error code.
  
   Args:
     code: Error code keyword
     context: Context map (may contain custom steps)
   
   Returns:
     Vector of step strings or nil"
  [code context]
  (or (:next-steps context)
      (let [base-type (when (namespace code) (keyword (name code)))]
        (get common-next-steps (or base-type code)))))

(defn format-next-steps
  "Format next steps as numbered list.
  
   Args:
     steps: Vector of step strings
   
   Returns:
     Formatted string or empty string"
  [steps]
  (if (seq steps)
    (str "Next steps:\n"
         (str/join "\n" (map-indexed #(str (inc %1) ". " %2) steps)))
    ""))

;; =============================================================================
;; Main Context Rendering
;; =============================================================================

(defn render-contextual-message
  "Render error message with full context.
  
   Args:
     code: Error code keyword
     params: Parameter map
     context: Context map with :operation, :role, :tenant-id, :entity
     opts: Rendering options
   
   Returns:
     Message string
   
   Example:
     (render-contextual-message
       :required
       {:field :email}
       {:operation :create :entity \"user\" :role :viewer}
       {})
     => \"Email is required when creating a user. You have view-only access...\""
  [code params context opts]
  (let [;; Ensure field-name param exists
        enriched-params (cond-> params
                          (and (:field params) (not (:field-name params)))
                          (assoc :field-name (messages/format-field-name (:field params))))
        ;; Check for operation-specific template
        op-template (get-operation-template (:operation context) code)

        ;; Apply context to params
        contextual-params (-> enriched-params
                              (apply-operation-context context)
                              (apply-tenant-context context))

        ;; Render base message (use op template if available)
        base-message (if op-template
                       (messages/interpolate-template op-template contextual-params)
                       (messages/render-message code contextual-params opts))

        ;; Add tenant context if present
        message-with-tenant (if-let [tenant-id (:tenant-id context)]
                              (str base-message " in tenant " tenant-id)
                              base-message)

        ;; Apply role-specific guidance
        full-message (apply-role-context message-with-tenant context)]
    full-message))

(defn enhance-error-with-context
  "Enhance error map with contextual message, suggestion, and optional example.
  
   Args:
     error: Error map with :code, :params, :field
     context: Context map
     opts: Options with :include-example?, :schema
   
   Returns:
     Enhanced error map
   
   Example:
     (enhance-error-with-context
       {:field :email :code :required :params {}}
       {:operation :create :entity \"user\"}
       {:include-example? true :schema User})
     => {:field :email
         :code :required
         :message \"Email is required when creating a user\"
         :suggestion \"Provide an email address for the user\"
         :example {:email \"user@example.com\" :name \"John Doe\"}}"
  [error context opts]
  (try
    (let [code (:code error)
          params (:params error)

          ;; Render contextual message
          message (render-contextual-message code params context opts)

          ;; Render suggestion (uses existing messages/render-suggestion)
          suggestion (messages/render-suggestion code params)

          ;; Get next steps
          next-steps (get-next-steps code context)
          formatted-steps (format-next-steps next-steps)

          ;; Generate example payload if requested
          example (when (and (:include-example? opts)
                             (:schema opts))
                    (generate-example-payload
                     (:schema opts)
                     (:field error)
                     opts))]

      (cond-> error
        message (assoc :message message)
        suggestion (assoc :suggestion suggestion)
        formatted-steps (assoc :next-steps formatted-steps)
        example (assoc :example example)))
    (catch Exception _e
      ;; Non-breaking: preserve original error
      error)))

(defn add-context-to-error
  "Add contextual message and example to error map.
   
   Convenience wrapper around enhance-error-with-context.
   
   Args:
     error: Error map
     schema: Optional Malli schema for example generation
     context: Context map
   
   Returns:
     Error with :contextual-message and optional :example"
  [error schema context]
  (let [opts (cond-> {}
               schema (assoc :schema schema
                             :include-example? true))
        enhanced (enhance-error-with-context error context opts)]
    (cond-> error
      (:message enhanced) (assoc :contextual-message (:message enhanced))
      (:example enhanced) (assoc :example (:example enhanced)))))

(defn get-role-guidance
  "Get guidance text for a specific role.
   
   Args:
     role: Role keyword
   
   Returns:
     Guidance string or nil"
  [role]
  (get role-specific-guidance role))
