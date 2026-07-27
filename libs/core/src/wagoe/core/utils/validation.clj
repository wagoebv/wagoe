(ns wagoe.core.utils.validation
  "Generic validation utilities for use across Boundary application modules.
   
   This namespace provides reusable validation patterns and utilities that
   were previously embedded in specific modules. These functions work with
   Malli schemas and provide consistent validation workflows.
   
   Key Features:
   - Generic validation with transformation patterns
   - CLI argument validation
   - Request validation helpers
   - Validation result normalization
   
   Usage:
   (:require [wagoe.core.utils.validation :as validation])
   
   (validation/validate-with-transform SomeSchema data transformer)"
  (:require [malli.core :as m]
            [wagoe.core.utils.type-conversion :as type-conv]))

;; =============================================================================
;; Generic Validation Functions
;; =============================================================================

(defn validate-with-transform
  "Generic validation with data transformation.
   
   Args:
     schema: Malli schema to validate against
     data: Data to validate and transform
     transformer: Malli transformer to apply before validation
     
   Returns:
     {:valid? true :data transformed-data} or
     {:valid? false :errors validation-errors}
     
   Example:
     (validate-with-transform SomeSchema data mt/string-transformer)"
  [schema data transformer]
  (let [transformed-data (m/decode schema data transformer)]
    (if (m/validate schema transformed-data)
      {:valid? true :data transformed-data}
      {:valid? false :errors (m/explain schema transformed-data)})))

(defn validate-cli-args
  "Generic CLI argument validation with transformation.
   
   This is a reusable pattern for validating CLI arguments that need
   type conversion (strings to booleans, integers, UUIDs, etc.).
   
   Args:
     schema: Malli schema for the CLI arguments
     args: Map of CLI arguments (typically strings)
     cli-transformer: Transformer for CLI string conversion
     
   Returns:
     {:valid? true :data transformed-args} or
     {:valid? false :errors validation-errors}
     
   Example:
     (validate-cli-args CreateUserCLISchema cli-args cli-transformer)"
  [schema args cli-transformer]
  (validate-with-transform schema args cli-transformer))

(defn validate-request
  "Generic API request validation with transformation.
   
   Args:
     schema: Malli schema for the request
     request-data: Raw request data
     request-transformer: Transformer for request data
     
   Returns:
     {:valid? true :data transformed-request} or
     {:valid? false :errors validation-errors}"
  [schema request-data request-transformer]
  (validate-with-transform schema request-data request-transformer))

;; =============================================================================
;; Validation Result Utilities
;; =============================================================================

(defn validation-passed?
  "Check if validation result indicates success.
   
   Args:
     validation-result: Result from validation function
     
   Returns:
     Boolean indicating if validation passed"
  [validation-result]
  (:valid? validation-result))

(defn get-validation-errors
  "Extract errors from validation result.
   
   Args:
     validation-result: Result from validation function
     
   Returns:
     Validation errors or nil if validation passed"
  [validation-result]
  (:errors validation-result))

(defn get-validated-data
  "Extract validated data from validation result.
   
   Args:
     validation-result: Result from validation function
     
   Returns:
     Validated data or nil if validation failed"
  [validation-result]
  (:data validation-result))

(defn valid-uuid?
  "Check if string is a valid UUID.

   This is commonly used in CLI option validation where UUIDs are required.

   Args:
     s: String to validate as UUID

   Returns:
     Boolean indicating if string is valid UUID"
  [s]
  (some? (type-conv/parse-uuid-string s)))

(defn valid-output-format?
  "Check if format is valid (table or json).

       This is commonly used in CLI option validation for output format selection.

       Args:
         s: String to validate as format

       Returns:
         Boolean indicating if format is valid"
  [s]
  (contains? #{"table" "json"} s))

