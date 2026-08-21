(ns wagoe.core.utils.validation
  "Value predicates the CLI layer validates arguments with.

   It also held a second implementation of validate-with-transform,
   validate-cli-args, validate-request and the result accessors — the same
   names wagoe.core.validation defined, with a different implementation and no
   compiled-schema cache. Nothing outside its own tests called them (BOU-323).

   Schema validation goes through wagoe.core.validation, whose validators are
   compiled once and cached; result shapes live in
   wagoe.core.validation.result."
  (:require [wagoe.core.utils.type-conversion :as type-conv]))

;; =============================================================================
;; Value predicates
;; =============================================================================

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

