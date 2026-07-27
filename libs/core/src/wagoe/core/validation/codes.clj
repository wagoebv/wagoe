(ns wagoe.core.validation.codes
  "Error code catalog and definitions.
  
   This namespace defines standardized error codes used across all validation
   operations, ensuring consistency in error reporting and enabling i18n support.
   
   Error Code Format:
     :domain.field/error-type
   
   Examples:
     :user.email/required
     :user.email/invalid-format
     :user.role/invalid-value
     :billing.amount/out-of-range
   
   Categories:
     - :required         - Missing required field
     - :invalid-format   - Format validation failure
     - :invalid-value    - Value doesn't match allowed values
     - :out-of-range     - Numeric/date value outside allowed range
     - :too-short        - String/collection below minimum length
     - :too-long         - String/collection above maximum length
     - :duplicate        - Uniqueness constraint violation
     - :not-found        - Referenced entity doesn't exist
     - :forbidden        - Operation not allowed by business rules
     - :dependency       - Depends on another field/condition
   
   Design Principles:
     - Codes are keywords for easy matching
     - Hierarchical naming for discoverability
     - Module-specific prefixes for isolation
     - Generic suffixes for cross-module consistency")

;; =============================================================================
;; Common Error Codes
;; =============================================================================

(def common-error-codes
  "Map of common error codes to their metadata."
  {:required {:description "Field is required"
              :severity :error
              :suggestion "Provide a value for this field"}

   :invalid-format {:description "Field format is invalid"
                    :severity :error
                    :suggestion "Check the format requirements"}

   :invalid-value {:description "Field value is not allowed"
                   :severity :error
                   :suggestion "Choose from the allowed values"}

   :out-of-range {:description "Value is outside allowed range"
                  :severity :error
                  :suggestion "Provide a value within the allowed range"}

   :too-short {:description "Value is too short"
               :severity :error
               :suggestion "Provide a longer value"}

   :too-long {:description "Value is too long"
              :severity :error
              :suggestion "Provide a shorter value"}

   :duplicate {:description "Value must be unique"
               :severity :error
               :suggestion "This value is already in use"}

   :not-found {:description "Referenced item not found"
               :severity :error
               :suggestion "Verify the reference exists"}

   :forbidden {:description "Operation not allowed"
               :severity :error
               :suggestion "Check permissions or business rules"}

   :dependency {:description "Depends on another field"
                :severity :error
                :suggestion "Ensure dependent fields are valid"}})

;; =============================================================================
;; User Module Error Codes
;; =============================================================================

(def user-error-codes
  "User module specific error codes."
  {:user.email/required {:description "Email is required"
                         :category :schema
                         :field :email}

   :user.email/invalid-format {:description "Email format is invalid"
                               :category :schema
                               :field :email}

   :user.email/duplicate {:description "Email already exists"
                          :category :business
                          :field :email}

   :user.name/required {:description "Name is required"
                        :category :schema
                        :field :name}

   :user.name/too-short {:description "Name is too short"
                         :category :schema
                         :field :name}

   :user.name/too-long {:description "Name is too long"
                        :category :schema
                        :field :name}

   :user.role/required {:description "Role is required"
                        :category :schema
                        :field :role}

   :user.role/invalid-value {:description "Role is not valid"
                             :category :schema
                             :field :role}

   :user.tenant-id/forbidden {:description "Cannot change tenant-id"
                              :category :business
                              :field :tenant-id}

   :user.password/too-short {:description "Password is too short"
                             :category :schema
                             :field :password}})

;; =============================================================================
;; Billing Module Error Codes
;; =============================================================================

(def billing-error-codes
  "Billing module specific error codes."
  {:billing.amount/required {:description "Amount is required"
                             :category :schema
                             :field :amount}

   :billing.amount/out-of-range {:description "Amount is out of range"
                                 :category :business
                                 :field :amount}

   :billing.currency/required {:description "Currency is required"
                               :category :schema
                               :field :currency}

   :billing.currency/invalid {:description "Currency code is invalid"
                              :category :schema
                              :field :currency}})

;; =============================================================================
;; Workflow Module Error Codes
;; =============================================================================

(def workflow-error-codes
  "Workflow module specific error codes."
  {:workflow.status/required {:description "Status is required"
                              :category :schema
                              :field :status}

   :workflow.status/invalid-value {:description "Invalid status value"
                                   :category :schema
                                   :field :status}

   :workflow.transition/forbidden {:description "Status transition not allowed"
                                   :category :business
                                   :field :status}})

;; =============================================================================
;; Complete Error Code Catalog
;; =============================================================================

(def error-code-catalog
  "Complete catalog of all error codes."
  (merge common-error-codes
         user-error-codes
         billing-error-codes
         workflow-error-codes))

;; =============================================================================
;; Error Code Utilities
;; =============================================================================

(defn get-error-code-info
  "Get metadata for an error code.
  
   Args:
     code: Error code keyword
   
   Returns:
     Error code metadata map or nil
   
   Example:
     (get-error-code-info :user.email/required)
     => {:description \"Email is required\" :category :schema :field :email}"
  [code]
  (get error-code-catalog code))

(defn error-code-exists?
  "Check if error code is defined in catalog.
  
   Args:
     code: Error code keyword
   
   Returns:
     Boolean"
  [code]
  (contains? error-code-catalog code))

(defn get-error-codes-by-category
  "Get all error codes for a category.
  
   Args:
     category: Category keyword (:schema or :business)
   
   Returns:
     Vector of [code metadata] pairs"
  [category]
  (filterv (fn [[_code meta]] (= (:category meta) category))
           error-code-catalog))

(defn get-error-codes-for-field
  "Get all error codes related to a field.
  
   Args:
     field: Field keyword
   
   Returns:
     Vector of [code metadata] pairs"
  [field]
  (filterv (fn [[_code meta]] (= (:field meta) field))
           error-code-catalog))

(defn suggest-error-code
  "Suggest similar error codes for a typo/unknown code.
  
   Args:
     code: Error code keyword (possibly misspelled)
   
   Returns:
     Vector of similar codes (simple string distance matching)"
  [code]
  (let [code-str (name code)
        all-codes (keys error-code-catalog)
        scored-codes (map (fn [c]
                            (let [c-str (name c)
                                  distance (if (.contains c-str code-str) 0 1)]
                              [c distance]))
                          all-codes)]
    (->> scored-codes
         (filter #(zero? (second %)))
         (map first)
         (take 5)
         vec)))
