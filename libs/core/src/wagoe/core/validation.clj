(ns wagoe.core.validation
  "Legacy validation namespace maintained for backward compatibility.
  
   This namespace provides the original validation interface while supporting
   the new structured result format when WAG_DEVEX_VALIDATION is enabled.
   
   New code should use:
     - wagoe.core.validation.result
     - wagoe.core.validation.registry (pure rule helpers; the stateful rule
       registry lives in wagoe.platform.shell.validation-registry)
     - wagoe.core.validation.codes
   
   This namespace will delegate to new implementations when feature flag is enabled."
  (:require [malli.core :as m]
            [wagoe.core.validation.result :as vr]))

;; Compiling a Malli validator/explainer/decoder is ~10x the cost of running
;; it. Schemas are a small fixed set of def'd values, so cache compilation
;; keyed on the schema value (lookup hits on identity for def'd schemas).
;; ponytail: unbounded memoize — bounded by the app's schema count by design.
(def validator
  "Cached (m/validator schema)."
  (memoize m/validator))

(def explainer
  "Cached (m/explainer schema)."
  (memoize m/explainer))

(def decoder
  "Cached (m/decoder schema transformer)."
  (memoize (fn [schema transformer] (m/decoder schema transformer))))

(defn valid?
  "Validate data against schema using the cached compiled validator."
  [schema data]
  ((validator schema) data))

(defn explain
  "Explain validation errors using the cached compiled explainer."
  [schema data]
  ((explainer schema) data))

(defn validate-with-transform
  "Validate data with transformation (legacy interface).
  
   When WAG_DEVEX_VALIDATION is enabled, returns enhanced result format.
   Otherwise maintains legacy format for backward compatibility.
  
   Args:
     schema: Malli schema
     data: Data to validate
     transformer: Malli transformer
  
   Returns:
     {:valid? boolean :data map :errors ...} (format depends on feature flag)"
  [schema data transformer]
  (let [result ((decoder schema transformer) data)
        valid? ((validator schema) result)]
    (if (vr/devex-validation-enabled?)
      ;; Enhanced format with structured errors
      (if valid?
        (vr/success-result result)
        (let [explanation ((explainer schema) result)
              ;; Convert Malli errors to structured format
              errors (mapv (fn [err]
                             (vr/error-map
                              (first (:path err))
                              :invalid-format
                              (or (:message err) "Validation failed")
                              {:path (:path err)
                               :params {:value (:value err)
                                        :schema (:schema err)}}))
                           (:errors explanation))]
          (vr/failure-result errors)))
      ;; Legacy format
      (if valid?
        {:valid? true :data result}
        {:valid? false :errors ((explainer schema) result)}))))

(defn validation-passed?
  "Check if validation passed (works with legacy and new format).
   
    Args:
      result: Validation result map
    
    Returns:
      Boolean indicating if validation passed"
  [result]
  (vr/validation-passed? result))

(defn get-validation-errors
  "Get validation errors (works with legacy and new format)."
  [result]
  (when (and result (not (validation-passed? result)))
    (:errors result)))

(defn get-validated-data
  "Get validated data (works with legacy and new format)."
  [result]
  (vr/get-validated-data result))

(defn validate-cli-args
  "Validate CLI arguments (legacy convenience function)."
  [schema args transformer]
  (validate-with-transform schema args transformer))

(defn validate-request
  "Validate API request (legacy convenience function)."
  [schema request transformer]
  (validate-with-transform schema request transformer))

;; =============================================================================
;; New Enhanced API (delegates to validation.result)
;; =============================================================================

(defn success-result
  "Create success result (delegates to validation.result)."
  ([data] (vr/success-result data))
  ([data warnings] (vr/success-result data warnings)))

(defn failure-result
  "Create failure result (delegates to validation.result)."
  ([errors] (vr/failure-result errors))
  ([errors warnings] (vr/failure-result errors warnings)))

(defn error-map
  "Create structured error map (delegates to validation.result)."
  ([field code message] (vr/error-map field code message))
  ([field code message opts] (vr/error-map field code message opts)))

(defn devex-enabled?
  "Check if DevEx validation features are enabled.
   
    Returns:
      Boolean indicating if DevEx validation is enabled"
  []
  (vr/devex-validation-enabled?))