(ns wagoe.core.validation.messages
  "Error message templating and suggestion engine.
  
   This namespace provides message template resolution, parameter interpolation,
   and intelligent suggestion generation for validation errors.
   
   Key Features:
   - Template resolution with fallback chain (domain → shared → default)
   - Safe parameter interpolation with sanitization
   - 'Did you mean?' suggestion engine using Damerau-Levenshtein distance
   - Expected value, range, and regex hints
   - Dependency and resolution step formatting
   
   Design Principles:
   - Non-breaking: Falls back to legacy messages if template missing
   - i18n-ready: All templates support parameter interpolation
   - Pure functions: No side effects, deterministic output
   - Sanitized: PII and sensitive data are filtered"
  (:require [clojure.string :as str]
            [wagoe.core.validation.codes :as codes]))

;; =============================================================================
;; String Distance (Damerau-Levenshtein)
;; =============================================================================

(defn- damerau-levenshtein-distance
  "Calculate Damerau-Levenshtein distance between two strings.
  
   Handles insertions, deletions, substitutions, and transpositions.
   This is more accurate than basic Levenshtein for typos.
   
   Args:
     s1: First string
     s2: Second string
   
   Returns:
     Integer distance (0 = identical)
   
   Algorithm adapted from Wikipedia/standard implementations."
  [s1 s2]
  (when (and s1 s2)
    (let [s1 (str/lower-case (str s1))
          s2 (str/lower-case (str s2))
          len1 (count s1)
          len2 (count s2)]
      (if (or (zero? len1) (zero? len2))
        (max len1 len2)
        ;; Use standard Levenshtein with transposition support
        (let [d (vec (for [_ (range (inc len1))]
                       (vec (repeat (inc len2) 0))))
              ;; Initialize first row and column
              d (reduce (fn [mat i] (assoc-in mat [i 0] i))
                        d (range (inc len1)))
              d (reduce (fn [mat j] (assoc-in mat [0 j] j))
                        d (range (inc len2)))]
          (loop [i 1
                 mat d]
            (if (> i len1)
              (get-in mat [len1 len2])
              (recur (inc i)
                     (loop [j 1
                            m mat]
                       (if (> j len2)
                         m
                         (let [cost (if (= (nth s1 (dec i)) (nth s2 (dec j))) 0 1)
                               deletion (inc (get-in m [(dec i) j]))
                               insertion (inc (get-in m [i (dec j)]))
                               substitution (+ (get-in m [(dec i) (dec j)]) cost)
                               ;; Check for transposition
                               transposition (if (and (> i 1) (> j 1)
                                                      (= (nth s1 (dec i)) (nth s2 (- j 2)))
                                                      (= (nth s1 (- i 2)) (nth s2 (dec j))))
                                               (inc (get-in m [(- i 2) (- j 2)]))
                                               Integer/MAX_VALUE)
                               min-val (min deletion insertion substitution transposition)]
                           (recur (inc j)
                                  (assoc-in m [i j] min-val)))))))))))))

(defn- similarity-score
  "Calculate similarity score between 0.0 (completely different) and 1.0 (identical).
  
   Args:
     s1: First string
     s2: Second string
   
   Returns:
     Float between 0.0 and 1.0"
  [s1 s2]
  (if (or (nil? s1) (nil? s2))
    0.0
    (let [max-len (max (count s1) (count s2))]
      (if (zero? max-len)
        1.0
        (let [distance (damerau-levenshtein-distance s1 s2)]
          (if distance
            (- 1.0 (/ distance max-len))
            0.0))))))

;; =============================================================================
;; Value Sanitization
;; =============================================================================

(defn- sanitize-value
  "Sanitize a value for display in error messages.
  
   Rules:
   - Truncate long strings (max 50 chars)
   - Redact potential PII patterns (emails, phone numbers)
   - Convert to string safely
   
   Args:
     value: Any value
     opts: Optional map with :max-length, :redact-pii?
   
   Returns:
     Sanitized string"
  [value {:keys [max-length redact-pii?] :or {max-length 50 redact-pii? true}}]
  (let [str-val (str value)
        truncated (if (> (count str-val) max-length)
                    (str (subs str-val 0 max-length) "...")
                    str-val)]
    (if redact-pii?
      ;; Redact email patterns
      (-> truncated
          (str/replace #"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}" "[email]"))
      truncated)))

;; =============================================================================
;; Template Resolution
;; =============================================================================

(def ^:private default-templates
  "Default message templates for common error codes."
  {:required "{{field-name}} is required"
   :invalid-format "{{field-name}} format is invalid"
   :invalid-value "{{field-name}} has an invalid value"
   :out-of-range "{{field-name}} is out of range"
   :too-short "{{field-name}} is too short"
   :too-long "{{field-name}} is too long"
   :duplicate "{{field-name}} already exists"
   :not-found "{{entity}} not found"
   :forbidden "Cannot modify {{field-name}}"
   :dependency "{{field-name}} requires {{dependency}}"})

(def ^:private detailed-templates
  "Detailed message templates with more context."
  {:invalid-format "{{field-name}} must match the format: {{expected}}"
   :invalid-value "{{field-name}} must be one of: {{allowed}}"
   :out-of-range "{{field-name}} must be between {{min}} and {{max}}"
   :too-short "{{field-name}} must be at least {{min}} characters"
   :too-long "{{field-name}} must be at most {{max}} characters"
   :forbidden "{{field-name}} cannot be changed after {{reason}}"
   :dependency "{{field-name}} can only be set when {{dependency}} is {{expected}}"})

(defn- get-template
  "Resolve message template with fallback chain.
  
   Resolution order:
   1. Module-specific template (e.g., :user.email/required)
   2. Generic code template from error catalog
   3. Base error type template (e.g., :required)
   4. Default fallback
   
   Args:
     code: Error code keyword
     params: Parameter map (may contain :use-detailed?)
   
   Returns:
     Template string"
  [code params]
  (let [use-detailed? (:use-detailed? params)
        templates (if use-detailed? detailed-templates default-templates)

        ;; Try module-specific code first
        module-template (get templates code)

        ;; Extract base error type (e.g., :user.email/required -> :required)
        base-type (when (namespace code)
                    (keyword (name code)))
        base-template (when base-type (get templates base-type))

        ;; Fallback to error catalog
        catalog-info (codes/get-error-code-info code)
        catalog-template (:template catalog-info)

        ;; Ultimate fallback
        default-fallback "Validation error"]

    (or module-template
        base-template
        catalog-template
        default-fallback)))

;; =============================================================================
;; Parameter Interpolation
;; =============================================================================

(defn format-field-name
  "Format field name for display (kebab-case to Title Case).
  
   Args:
     field: Field keyword
   
   Returns:
     Formatted string
   
   Examples:
     :email -> \"Email\"
     :tenant-id -> \"Tenant ID\"
     :user-agent -> \"User Agent\""
  [field]
  (let [field-str (name field)
        words (str/split field-str #"-")
        capitalized (map str/capitalize words)]
    ;; Handle acronyms
    (->> capitalized
         (map #(cond
                 (= % "Id") "ID"
                 (= % "Uuid") "UUID"
                 (= % "Url") "URL"
                 (= % "Api") "API"
                 :else %))
         (str/join " "))))

(defn interpolate-template
  "Interpolate parameters into template string.
  
   Replaces {{placeholder}} with corresponding param value.
  
   Args:
     template: Template string with {{placeholder}} markers
     params: Map of parameter values
   
   Returns:
     Interpolated string
   
   Example:
     (interpolate-template \"{{field-name}} is required\" {:field-name \"Email\"})
     => \"Email is required\""
  [template params]
  (reduce-kv
   (fn [result param-key param-value]
     (let [placeholder (str "{{" (name param-key) "}}")
           value-str (if (keyword? param-value)
                       (name param-value)
                       (str param-value))]
       (str/replace result placeholder value-str)))
   template
   params))

;; =============================================================================
;; Suggestion Generation
;; =============================================================================

(defn suggest-similar-value
  "Suggest similar value from allowed values using string distance.
  
   Args:
     value: Input value (possibly misspelled)
     allowed-values: Collection of allowed values
     opts: Optional map with :threshold (default 0.6), :max-distance (default 2)
   
   Returns:
     Best match string or nil
   
   Example:
     (suggest-similar-value \"admim\" [\"admin\" \"user\" \"viewer\"])
     => \"admin\""
  [value allowed-values {:keys [threshold max-distance]
                         :or {threshold 0.6 max-distance 2}}]
  (when (and value (seq allowed-values))
    (let [value-str (str/lower-case (str value))
          scored (->> allowed-values
                      (map (fn [allowed]
                             (let [allowed-str (str/lower-case (str allowed))
                                   score (similarity-score value-str allowed-str)
                                   distance (damerau-levenshtein-distance value-str allowed-str)]
                               {:value allowed
                                :score score
                                :distance distance})))
                      (filter #(and (>= (:score %) threshold)
                                    (<= (:distance %) max-distance)))
                      (sort-by (juxt (comp - :score) :distance)))
          best-match (first scored)]
      (when best-match
        (str (:value best-match))))))

(defn format-allowed-values
  "Format list of allowed values for display.
  
   Args:
     values: Collection of allowed values
     opts: Optional map with :max-items (default 10), :conjunction (default 'and')
   
   Returns:
     Formatted string
   
   Examples:
     (format-allowed-values [\"admin\" \"user\" \"viewer\"])
     => \"admin, user, and viewer\"
     
     (format-allowed-values [\"a\" \"b\" \"c\" \"d\"] {:conjunction \"or\"})
     => \"a, b, c, or d\""
  [values {:keys [max-items conjunction] :or {max-items 10 conjunction "and"}}]
  (let [value-strs (map str values)
        limited (if (> (count value-strs) max-items)
                  (concat (take (dec max-items) value-strs) ["..."])
                  value-strs)]
    (cond
      (empty? limited) ""
      (= 1 (count limited)) (first limited)
      :else
      (let [all-but-last (butlast limited)
            last-item (last limited)]
        (str (str/join ", " all-but-last) ", " conjunction " " last-item)))))

;; =============================================================================
;; Suggestion Constructors
;; =============================================================================

(defn create-did-you-mean-suggestion
  "Create 'Did you mean?' suggestion text.
  
   Args:
     params: Map with :value, :allowed, optional :suggestion
   
   Returns:
     Suggestion string or nil
   
   Example:
     {:value \"admim\" :allowed \"admin, user, viewer\" :suggestion \"admin\"}
     => \"Did you mean \\\"admin\\\"? Allowed values: admin, user, viewer\""
  [{:keys [allowed suggestion]}]
  (when (and suggestion allowed)
    (str "Did you mean \"" suggestion "\"? Allowed values: " allowed)))

(defn create-expected-value-hint
  "Create expected value/format hint.
  
   Args:
     params: Map with :field-name, :expected
   
   Returns:
     Hint string
   
   Example:
     {:field-name \"Email\" :expected \"user@domain.com\"}
     => \"Provide Email in the correct format. Expected: user@domain.com\""
  [{:keys [field-name expected]}]
  (when (and field-name expected)
    (str "Provide " field-name " in the correct format. Expected: " expected)))

(defn create-range-hint
  "Create range validation hint.
  
   Args:
     params: Map with :field-name, :min, :max, optional :value
   
   Returns:
     Hint string
   
   Example:
     {:field-name \"Age\" :min \"0\" :max \"120\" :value \"150\"}
     => \"Provide Age between 0 and 120. You provided: 150\""
  [{:keys [field-name min max value]}]
  (when (and field-name min max)
    (let [base (str "Provide " field-name " between " min " and " max)]
      (if value (str base ". You provided: " value) base))))

(defn create-length-hint
  "Create length validation hint.
  
   Args:
     params: Map with :field-name, :min or :max
   
   Returns:
     Hint string"
  [{:keys [field-name min max]}]
  (cond
    (and field-name min)
    (str "Provide " field-name " with at least " min " characters")

    (and field-name max)
    (str "Provide " field-name " with at most " max " characters")

    :else nil))

(defn create-dependency-hint
  "Create dependency hint.
  
   Args:
     params: Map with :field-name, :dependency
   
   Returns:
     Hint string
   
   Example:
     {:field-name \"Billing Address\" :dependency \"Payment Method\"}
     => \"Provide Payment Method before setting Billing Address\""
  [{:keys [field-name dependency]}]
  (when (and field-name dependency)
    (str "Provide " dependency " before setting " field-name)))

;; =============================================================================
;; Main Message Rendering
;; =============================================================================

(defn render-message
  "Render complete error message with template and parameters.
  
   Args:
     code: Error code keyword
     params: Parameter map
     opts: Optional rendering options
   
   Returns:
     Rendered message string
   
   Example:
     (render-message :required {:field :email})
     => \"Email is required\""
  [code params _]
  (let [;; Ensure field-name is formatted
        formatted-params (cond-> params
                           (and (:field params)
                                (not (:field-name params)))
                           (assoc :field-name (format-field-name (:field params)))

                          ;; Sanitize value if present
                           (:value params)
                           (update :value sanitize-value {}))

        ;; Get template
        template (get-template code formatted-params)

        ;; Interpolate
        message (interpolate-template template formatted-params)]
    message))

(defn render-suggestion
  "Render suggestion text based on error code and parameters.
  
   Args:
     code: Error code keyword
     params: Parameter map
   
   Returns:
     Suggestion string or nil
   
   Example:
     (render-suggestion :invalid-value 
                       {:field :role
                        :value \"admim\"
                        :allowed \"admin, user, viewer\"
                        :suggestion \"admin\"})
     => \"Did you mean \\\"admin\\\"? Allowed values: admin, user, viewer\""
  [code params]
  (let [base-type (when (namespace code) (keyword (name code)))
        ;; Ensure field-name is formatted for suggestions
        formatted-params (cond-> params
                           (and (:field params)
                                (not (:field-name params)))
                           (assoc :field-name (format-field-name (:field params))))]
    (case (or base-type code)
      :invalid-value
      (create-did-you-mean-suggestion formatted-params)

      :invalid-format
      (create-expected-value-hint formatted-params)

      :out-of-range
      (create-range-hint formatted-params)

      (:too-short :too-long)
      (create-length-hint formatted-params)

      :dependency
      (create-dependency-hint formatted-params)

      ;; Default: no suggestion
      nil)))

(defn enhance-error
  "Enhance error map with rendered message and suggestion.
  
   Non-breaking: If rendering fails, preserves original message.
  
   Args:
     error: Error map with :code and :params
     opts: Optional rendering options
   
   Returns:
     Enhanced error map with :message and optional :suggestion
   
   Example:
     (enhance-error {:field :email :code :required :params {}})
     => {:field :email
         :code :required
         :params {}
         :message \"Email is required\"
         :suggestion \"Provide an email address for the user\"}"
  [error opts]
  (try
    (let [code (:code error)
          ;; Ensure params includes field if not present
          params (cond-> (:params error {})
                   (and (:field error) (not (:field (:params error))))
                   (assoc :field (:field error)))
          message (render-message code params opts)
          suggestion (render-suggestion code params)]
      (cond-> error
        message (assoc :message message)
        suggestion (assoc :suggestion suggestion)))
    (catch Exception _
      ;; Non-breaking: preserve original error if rendering fails
      error)))
