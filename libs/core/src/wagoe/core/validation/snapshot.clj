;; ^:wagoe/allow-throw — snapshot-testing helper; the throws guard against
;; missing required opts (:ns / :test) when misusing the snapshot API —
;; intentional developer tooling behaviour.
(ns ^:wagoe/allow-throw wagoe.core.validation.snapshot
  "Pure snapshot capture, comparison, and serialization for validation testing.

  This namespace provides data-only operations for snapshot testing:
  - capture: Create snapshot maps with metadata
  - stable-serialize: Deterministic EDN serialization
  - path-for: Compute snapshot file paths from test metadata
  - compare-snapshots: Deep comparison with detailed diffs

  All functions are pure with no I/O. File operations belong in test helpers.

  Example usage:

    ;; Capture a validation result
    (def snap (capture {:status :failure :errors [...]} 
                      {:schema-version \"1.0\" :seed 42 :meta {:test \"user-email\"}}))

    ;; Serialize deterministically
    (stable-serialize snap)
    ;; => \"{:meta {:schema-version \\\"1.0\\\" :seed 42 ...} :result {...}}\"_in

    ;; Compute file path
    (path-for {:ns 'wagoe.user.validation-test :test 'email-required :case 'missing})
    ;; => \"test/snapshots/validation/boundary/user/validation_test/email_required__missing.edn\"

    ;; Compare snapshots
    (compare-snapshots expected actual)
    ;; => {:equal? true :diff [nil nil nil]}

  Snapshot format:
  {:meta {:schema-version \"1.0\" 
          :seed 42 
          :test-ns 'wagoe.user.validation-test
          :test-name 'email-required
          :case-name 'missing
          :captured-at \"2025-01-04\"}
   :result <validation-result>}"
  (:require [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Snapshot Capture
;; -----------------------------------------------------------------------------

(defn capture
  "Capture a validation result as a snapshot map with metadata.

  Args:
    result - The validation result to capture (typically a map with :status, :errors, etc.)
    opts   - Map of metadata options:
             :schema-version - Version string for snapshot format (default \"1.0\")
             :seed           - Random seed used for generation (for reproducibility)
             :meta           - Additional metadata (test name, case name, etc.)

  Returns:
    Snapshot map with :meta and :result keys.

  Example:
    (capture {:status :failure :errors [{:code :user.email/required}]}
             {:schema-version \"1.0\" :seed 42 :meta {:test \"email-validation\"}})
    ;; => {:meta {:schema-version \"1.0\" :seed 42 :test \"email-validation\"}
    ;;     :result {:status :failure :errors [{:code :user.email/required}]}}"
  [result opts]
  (let [schema-version (get opts :schema-version "1.0")
        seed (get opts :seed)
        meta-data (get opts :meta {})
        merged-meta (cond-> {:schema-version schema-version}
                      seed (assoc :seed seed)
                      (seq meta-data) (merge meta-data))]
    {:meta merged-meta
     :result result}))

;; -----------------------------------------------------------------------------
;; Deterministic Serialization
;; -----------------------------------------------------------------------------

(defn- strip-memory-address
  "Strip memory address (@hexadecimal) and reify identifiers from object string representation.

  Args:
    s - String representation of an object

  Returns:
    String with @address and reify__xxxx removed for deterministic comparison.

  Example:
    (strip-memory-address \"malli.core$Schema@7a6ba56\")
    ;; => \"malli.core$Schema\"
    
    (strip-memory-address \"malli.core$_map_schema$reify$reify__9248\")
    ;; => \"malli.core$_map_schema$reify\""
  [s]
  (-> s
      (str/replace #"@[0-9a-fA-F]+" "")
      (str/replace #"reify__\d+" "")))

(defn- sort-map-keys
  "Recursively sort all map keys and convert non-EDN-serializable values.

  Transforms:
  - Java time objects (Instant, LocalDate, etc.) to ISO-8601 strings
  - Regex patterns to string representations
  - Other objects to their string representation (with memory addresses stripped)

  Args:
    x - Data structure (maps, vectors, lists, primitives)

  Returns:
    Same structure with all maps converted to sorted-maps and non-EDN values converted."
  [x]
  (cond
    ;; Handle java.time objects
    (instance? java.time.temporal.Temporal x)
    (str x)

    ;; Handle regex patterns
    (instance? java.util.regex.Pattern x)
    {:regex-pattern (str x)}

    ;; Handle other Java objects that aren't EDN-serializable
    (and (instance? Object x)
         (not (or (string? x) (number? x) (boolean? x) (nil? x)
                  (keyword? x) (symbol? x) (uuid? x)
                  (map? x) (vector? x) (list? x) (set? x))))
    (strip-memory-address (str x))

    ;; Recursively process collections
    (map? x)
    (into (sorted-map) (map (fn [[k v]] [k (sort-map-keys v)]) x))

    (vector? x)
    (mapv sort-map-keys x)

    (list? x)
    (map sort-map-keys x)

    (set? x)
    (into (sorted-set) (map sort-map-keys x))

    :else
    x))

(defn stable-serialize
  "Serialize a snapshot to deterministic EDN string.

  Ensures consistent output regardless of map insertion order:
  - Sorts all map keys recursively
  - Uses sorted-map and sorted-set
  - Pretty-prints with consistent formatting

  Args:
    snapshot - Snapshot map (from capture)

  Returns:
    EDN string with deterministic formatting.

  Example:
    (stable-serialize {:meta {:seed 42 :test \"example\"} :result {:status :success}})
    ;; Always produces same string for same data"
  [snapshot]
  (let [sorted (sort-map-keys snapshot)]
    (binding [*print-length* nil
              *print-level* nil]
      (with-out-str
        (pprint/pprint sorted)))))

(defn parse-snapshot
  "Parse EDN string back to snapshot map.

  Args:
    edn-string - EDN string (from stable-serialize)

  Returns:
    Snapshot map.

  Example:
    (parse-snapshot (stable-serialize snapshot))
    ;; => original snapshot"
  [edn-string]
  (edn/read-string edn-string))

;; -----------------------------------------------------------------------------
;; Path Computation
;; -----------------------------------------------------------------------------

(defn- namespace->path
  "Convert namespace symbol to file path segment.

  Args:
    ns-sym - Namespace symbol (e.g., 'wagoe.user.validation-test)

  Returns:
    Path string (e.g., \"wagoe/user/validation_test\")

  Example:
    (namespace->path 'wagoe.user.validation-test)
    ;; => \"wagoe/user/validation_test\""
  [ns-sym]
  (-> (str ns-sym)
      (str/replace #"\." "/")
      (str/replace #"-" "_")))

(defn path-for
  "Compute snapshot file path from test metadata.

  Args:
    opts - Map with:
           :ns   - Test namespace symbol
           :test - Test name symbol or string
           :case - Test case name symbol or string (optional)
           :base - Base directory (default \"test/snapshots/validation\")

  Returns:
    Relative path string to snapshot file.

  Examples:
    (path-for {:ns 'wagoe.user.validation-test 
               :test 'email-required 
               :case 'missing})
    ;; => \"test/snapshots/validation/boundary/user/validation_test/email_required__missing.edn\"

    (path-for {:ns 'wagoe.user.validation-test 
               :test 'email-format})
    ;; => \"test/snapshots/validation/boundary/user/validation_test/email_format.edn\""
  [opts]
  (let [base-dir (get opts :base "test/snapshots/validation")
        ns-sym (get opts :ns)
        test-name (get opts :test)
        case-name (get opts :case)
        _ (when-not ns-sym
            (throw (ex-info "Missing required :ns in opts" {:opts opts})))
        _ (when-not test-name
            (throw (ex-info "Missing required :test in opts" {:opts opts})))
        ns-path (namespace->path ns-sym)
        test-str (-> (str test-name)
                     (str/replace #"-" "_"))
        case-str (when case-name
                   (-> (str case-name)
                       (str/replace #"-" "_")))
        filename (if case-str
                   (str test-str "__" case-str ".edn")
                   (str test-str ".edn"))]
    (str base-dir "/" ns-path "/" filename)))

;; -----------------------------------------------------------------------------
;; Comparison
;; -----------------------------------------------------------------------------

(defn compare-snapshots
  "Deep comparison of two snapshots with detailed diff.

  Uses clojure.data/diff to identify differences between expected and actual snapshots.

  Args:
    expected - Expected snapshot map
    actual   - Actual snapshot map

  Returns:
    Map with:
    :equal? - Boolean indicating if snapshots are equal
    :diff   - Vector [only-in-expected only-in-actual shared] from clojure.data/diff

  Example:
    (compare-snapshots {:meta {:seed 42} :result {:status :success}}
                       {:meta {:seed 42} :result {:status :failure}})
    ;; => {:equal? false
    ;;     :diff [{:result {:status :success}} 
    ;;            {:result {:status :failure}} 
    ;;            {:meta {:seed 42}}]}"
  [expected actual]
  (let [diff-result (data/diff expected actual)
        equal? (and (nil? (first diff-result))
                    (nil? (second diff-result)))]
    {:equal? equal?
     :diff diff-result}))

;; Alias for backwards compatibility with tests
;; Note: This shadows clojure.core/compare in this namespace
(def ^{:doc "Alias for compare-snapshots. See compare-snapshots for full documentation."}
  compare-snaps compare-snapshots)

(defn format-diff
  "Format a diff result into human-readable string.

  Args:
    diff - Diff result from compare-snapshots

  Returns:
    Formatted string showing differences.

  Example:
    (format-diff (compare-snapshots expected actual))
    ;; => \"Differences found:\n  Only in expected: {...}\n  Only in actual: {...}\""
  [{:keys [equal? diff]}]
  (if equal?
    "Snapshots are equal."
    (let [[only-expected only-actual shared] diff]
      (str "Differences found:\n"
           (when only-expected
             (str "  Only in expected:\n"
                  (with-out-str (pprint/pprint only-expected))))
           (when only-actual
             (str "  Only in actual:\n"
                  (with-out-str (pprint/pprint only-actual))))
           (when shared
             (str "  Shared values:\n"
                  (with-out-str (pprint/pprint shared))))))))
