(ns wagoe.core.utils.type-conversion
  "Generic type and case conversion utilities."
  (:require [clojure.string :as str]
            [wagoe.core.utils.case-conversion :as case-conv])
  (:import [java.util UUID]
           [java.time Instant OffsetDateTime]))

(defn uuid->string
  "Convert UUID to string for storage (nil-safe)."
  [uuid]
  (when uuid (.toString uuid)))

(defn string->uuid
  "Convert string to UUID from storage (nil-safe).
   
   Handles both string UUIDs and native UUID objects (e.g., from H2)."
  [s]
  (when (and s (not= s ""))
    (if (instance? UUID s)
      s  ; Already a UUID, return as-is
      (try
        (UUID/fromString s)
        (catch IllegalArgumentException _
          nil)))))

(defn instant->string
  "Convert Instant to ISO 8601 string for storage (nil-safe)."
  [instant]
  (when instant (.toString instant)))

(defn string->instant
  "Convert ISO 8601 string to Instant from storage (nil-safe).

   Handles:
   - String timestamps in ISO 8601 format
   - Native Instant objects (e.g., from some databases)
   - java.sql.Timestamp objects (e.g., from H2, PostgreSQL JDBC)
   - java.time.OffsetDateTime objects (e.g., from H2 TIMESTAMP WITH TIME ZONE columns)"
  [s]
  (when (and s (not= s ""))
    (cond
      (instance? Instant s)
      s  ; Already an Instant, return as-is

      (instance? OffsetDateTime s)
      (.toInstant ^OffsetDateTime s)  ; Convert OffsetDateTime to Instant

      (instance? java.sql.Timestamp s)
      (.toInstant ^java.sql.Timestamp s)  ; Convert Timestamp to Instant

      (string? s)
      (try
        (Instant/parse s)
        (catch Exception _
          nil))

      :else
      nil)))

(defn keyword->string
  "Convert keyword to string for storage (nil-safe).
   Removes the colon prefix for database storage."
  [kw]
  (when kw (name kw)))

(defn string->keyword
  "Convert string to keyword from storage (nil-safe)."
  [s]
  (when (and s (not= s "")) (keyword s)))

(defn boolean->int
  "Convert boolean to integer for storage."
  [b]
  (if b 1 0))

(defn int->boolean
  "Convert integer to boolean from storage."
  [i]
  (= i 1))

(defn snake-case->kebab-case
  "Convert snake_case keys to kebab-case keys in a map (nil-safe).
   Delegates to wagoe.core.utils.case-conversion/snake-case->kebab-case-map."
  [m]
  (case-conv/snake-case->kebab-case-map m))

(defn kebab-case->snake-case
  "Convert kebab-case keys to snake_case keys in a map (nil-safe).
   Delegates to wagoe.core.utils.case-conversion/kebab-case->snake-case-map."
  [m]
  (case-conv/kebab-case->snake-case-map m))

(defn string->boolean
  "Convert string values to booleans with support for various formats."
  [value]
  (cond
    (boolean? value) value
    (string? value) (case (.toLowerCase value)
                      "true" true
                      "false" false
                      "1" true
                      "0" false
                      "yes" true
                      "no" false
                      "on" true
                      "off" false
                      value)
    :else value))

(defn string->int
  "Convert string values to integers."
  [value]
  (cond
    (int? value) value
    (string? value) (try
                      (Integer/parseInt value)
                      (catch NumberFormatException _
                        value))
    :else value))

(defn string->enum
  "Converts string values to keywords for enums."
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else value))

;; =============================================================================
;; CLI Parsing Functions
;; =============================================================================

(defn parse-uuid-string
  "Parse a string as UUID, returning nil if invalid.

   This is commonly used in CLI argument parsing where UUID validation
   is needed but we want to return nil for invalid UUIDs rather than throw.

   Args:
     s: String to parse as UUID

   Returns:
     UUID object if valid, nil if invalid"
  [s]
  (try
    (UUID/fromString s)
    (catch Exception _
      nil)))

(defn parse-int
  "Parse a string as positive integer, returning nil if invalid.

   This is commonly used in CLI argument parsing where positive integer
   validation is needed (like limits, offsets, etc.).

   Args:
     s: String to parse as positive integer

   Returns:
     Positive integer if valid, nil if invalid or non-positive"
  [s]
  (try
    (let [n (Integer/parseInt s)]
      (when (pos? n) n))
    (catch Exception _
      nil)))

(defn parse-bool
  "Parse a string as boolean. Accepts: true, false, 1, 0, yes, no (case-insensitive).

   This is commonly used in CLI argument parsing where flexible boolean
   input is desired.

   Args:
     s: String to parse as boolean

   Returns:
     Boolean value if valid format, nil if invalid"
  [s]
  (case (str/lower-case s)
    ("true" "1" "yes" "y") true
    ("false" "0" "no" "n") false
    nil))
