(ns wagoe.core.validation
  "Compiled Malli validators, explainers and decoders, cached.

   This is all this namespace does. It used to also carry a second copy of the
   validation-result API — validate-with-transform, validation-passed?,
   get-validation-errors, success-result, failure-result, error-map — wrapping
   wagoe.core.validation.result, and a third copy lived in
   wagoe.core.utils.validation with a different implementation of the same
   name. Two answers to one question, neither of them called by anything
   outside their own tests (BOU-323).

   Worse, validate-with-transform chose its *return shape* at runtime from the
   :devex-validation feature flag: {:valid? true :data …} with the flag off,
   a structured success-result with it on. A function whose result shape
   depends on an environment variable cannot be typed, tested or documented —
   which is what ADR-036 §2 settles, and why the fork is gone rather than
   ported.

   Result shapes live in wagoe.core.validation.result; rule helpers in
   wagoe.core.validation.registry; error codes in wagoe.core.validation.codes."
  (:require [malli.core :as m]))

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

