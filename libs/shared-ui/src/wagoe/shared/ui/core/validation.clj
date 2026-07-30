(ns wagoe.shared.ui.core.validation
  "Validation error transformation utilities for UI display.
   
   Provides functions to convert Malli validation errors into
   formats suitable for inline field error display in web forms."
  (:require [malli.error :as me]))

(defn explain->field-errors
  "Convert Malli explain data to field-keyed error messages map.
   
   Args:
     explain-data: Malli explain output from m/explain
     
   Returns:
     Map of {field-keyword -> [error-messages]}
     
   Example:
     (explain->field-errors explain-data)
     => {:name [\"Name is required\"]
         :email [\"Invalid email format\"]
         :password [\"Password must be at least 8 characters\"]}"
  [explain-data]
  (when explain-data
    (let [human (me/humanize explain-data)]
      (reduce-kv
       (fn [m path-or-key msg]
         (let [;; Handle both direct keys and path vectors
               k (if (keyword? path-or-key)
                   path-or-key
                   (keyword (name (first path-or-key))))
               msgs (cond
                      (string? msg) [msg]
                      (vector? msg) (mapv str msg)
                      (sequential? msg) (mapv str msg)
                      :else [(str msg)])]
           (update m k (fnil into []) msgs)))
       {}
       human))))

(defn has-errors?
  "Check if validation errors exist.
   
   Args:
     errors: Error map from explain->field-errors
     
   Returns:
     Boolean indicating presence of errors"
  [errors]
  (boolean (and (map? errors)
                (seq errors))))

(defn field-error
  "Get error messages for a specific field.
   
   Args:
     errors: Error map from explain->field-errors
     field-key: Keyword field name
     
   Returns:
     Vector of error messages or nil if no errors"
  [errors field-key]
  (when (map? errors)
    (get errors field-key)))
