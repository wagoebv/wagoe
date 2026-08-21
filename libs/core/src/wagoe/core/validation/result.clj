(ns wagoe.core.validation.result
  "Standard validation result format and utilities.

   This namespace defines the canonical result format for all validation
   operations in the Wagoe framework, supporting both schema validation
   and business rule validation with structured errors and warnings.

   Result Format:
     {:valid?   boolean                    ; Overall validation status
      :data     map                        ; Validated/transformed data (if valid)
      :errors   vector-of-error-maps       ; Validation errors (if invalid)
      :warnings vector-of-warning-maps}    ; Non-blocking warnings (optional)

   Error/Warning Map Format:
     {:field   keyword                     ; Field identifier
      :code    keyword                     ; Error/warning code
      :message string                      ; Human-readable message
      :params  map                         ; Template parameters
      :path    vector                      ; Path to error location
      :rule-id keyword}                    ; Optional: validation rule ID

   Design Principles:
   - Forward compatibility: new keys can be added without breaking existing code
   - Feature-flag gated: new functionality controlled by WAG_DEVEX_VALIDATION
   - I18n-ready: :code + :params enable future message translation
   - FC/IS compliant: pure data structures, no side effects"
  (:require [wagoe.core.config.feature-flags :as flags]))

;; =============================================================================
;; Feature Flag Support
;; =============================================================================

(defn devex-validation-enabled?
  "Check if DevEx validation features are enabled.

   Delegates to the feature-flags system for consistent flag resolution.
   Default: false (backward compatible).

   Returns:
     Boolean indicating if DevEx validation is enabled"
  []
  (flags/enabled? :devex-validation))

;; =============================================================================
;; Result Constructors
;; =============================================================================

(defn success-result
  "Create a successful validation result.
  
   Args:
     data: Validated/transformed data
     warnings: (optional) Vector of warning maps
   
   Returns:
     Standard success result map
   
   Example:
     (success-result {:email \"user@example.com\" :name \"John\"})
     => {:valid? true, :data {...}, :errors [], :warnings []}"
  ([data]
   (success-result data []))
  ([data warnings]
   {:valid? true
    :data data
    :errors []
    :warnings (or warnings [])}))

(defn failure-result
  "Create a failed validation result.
  
   Args:
     errors: Vector of error maps or single error map
     warnings: (optional) Vector of warning maps
   
   Returns:
     Standard failure result map
   
   Example:
     (failure-result {:field :email, :code :invalid-format, :message \"Invalid email\"})
     => {:valid? false, :data nil, :errors [{...}], :warnings []}"
  ([errors]
   (failure-result errors []))
  ([errors warnings]
   (let [error-vec (if (vector? errors) errors [errors])]
     {:valid? false
      :data nil
      :errors error-vec
      :warnings (or warnings [])})))

;; =============================================================================
;; Error Map Constructors
;; =============================================================================

(defn error-map
  "Create a structured error map.
  
   Args:
     field: Keyword identifying the field
     code: Keyword error code
     message: Human-readable error message
     opts: (optional) Map with :params, :path, :rule-id
   
   Returns:
     Structured error map
   
   Example:
     (error-map :email :invalid-format \"Invalid email format\" 
                {:params {:value \"bad@email\"} :path [:user :email]})"
  ([field code message]
   (error-map field code message {}))
  ([field code message {:keys [params path rule-id] :as _opts}]
   (cond-> {:field field
            :code code
            :message message
            :params (or params {})
            :path (or path [field])}
     rule-id (assoc :rule-id rule-id))))

(defn warning-map
  "Create a structured warning map (same structure as error-map).
  
   Args:
     field: Keyword identifying the field
     code: Keyword warning code
     message: Human-readable warning message
     opts: (optional) Map with :params, :path, :rule-id
   
   Returns:
     Structured warning map"
  ([field code message]
   (warning-map field code message {}))
  ([field code message opts]
   (error-map field code message opts)))

;; =============================================================================
;; Result Utilities
;; =============================================================================

(defn validation-passed?
  "Check if validation result indicates success.
   
    Args:
      result: Validation result map
    
    Returns:
      Boolean indicating if validation passed"
  [result]
  (boolean (:valid? result)))

(defn validation-failed?
  "Check if validation result indicates failure.
   
    Args:
      result: Validation result map
    
    Returns:
      Boolean indicating if validation failed"
  [result]
  (not (validation-passed? result)))

(defn get-errors
  "Extract errors from validation result.
  
   Args:
     result: Validation result map
   
   Returns:
     Vector of error maps (empty if validation passed)"
  [result]
  (or (:errors result) []))

(defn get-warnings
  "Extract warnings from validation result.
  
   Args:
     result: Validation result map
   
   Returns:
     Vector of warning maps"
  [result]
  (or (:warnings result) []))

(defn get-validated-data
  "Extract validated data from result.
  
   Args:
     result: Validation result map
   
   Returns:
     Validated data or nil if validation failed"
  [result]
  (when (validation-passed? result)
    (:data result)))

(defn has-warnings?
  "Check if result has warnings.
   
    Args:
      result: Validation result map
    
    Returns:
      Boolean indicating presence of warnings"
  [result]
  (seq (:warnings result)))

(defn errors-by-field
  "Group errors by field.
  
   Args:
     result: Validation result map
   
   Returns:
     Map of {field [error-maps]}
   
   Example:
     {:email [{:field :email :code :invalid-format ...}]
      :age   [{:field :age :code :out-of-range ...}]}"
  [result]
  (group-by :field (get-errors result)))

(defn errors-by-code
  "Group errors by code.
  
   Args:
     result: Validation result map
   
   Returns:
     Map of {code [error-maps]}"
  [result]
  (group-by :code (get-errors result)))

(defn first-error
  "Get first error from result.
  
   Args:
     result: Validation result map
   
   Returns:
     First error map or nil"
  [result]
  (first (get-errors result)))

(defn error-count
  "Count total errors in result.
  
   Args:
     result: Validation result map
   
   Returns:
     Integer error count"
  [result]
  (count (get-errors result)))

;; =============================================================================
;; Result Combinators
;; =============================================================================

(defn merge-results
  "Merge multiple validation results.
  
   Combined result is valid only if all inputs are valid.
   Errors and warnings are concatenated.
   
   Args:
     results: Vector of validation result maps
   
   Returns:
     Merged validation result
   
   Example:
     (merge-results [result1 result2 result3])"
  [results]
  (let [all-valid? (every? validation-passed? results)
        all-errors (mapcat get-errors results)
        all-warnings (mapcat get-warnings results)
        merged-data (when all-valid?
                      (apply merge (map :data results)))]
    (if all-valid?
      (success-result merged-data all-warnings)
      (failure-result all-errors all-warnings))))

(defn add-error
  "Add an error to existing result.
  
   Args:
     result: Existing validation result
     error: Error map to add
   
   Returns:
     Updated result with error added and :valid? set to false"
  [result error]
  (-> result
      (assoc :valid? false)
      (assoc :data nil)
      (update :errors (fnil conj []) error)))

(defn add-warning
  "Add a warning to existing result.
  
   Args:
     result: Existing validation result
     warning: Warning map to add
   
   Returns:
     Updated result with warning added"
  [result warning]
  (update result :warnings (fnil conj []) warning))
