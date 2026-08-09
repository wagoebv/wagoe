(ns wagoe.ai.core.context
  "Pure context-extraction functions for AI features.

   FC/IS rule: file I/O happens in the caller (shell layer).
   These functions receive already-read content as strings/maps
   and transform it into context strings for prompt building."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Project context — existing modules
;; =============================================================================

(defn extract-module-names
  "Extract existing module names from a list of directory paths.

   Args:
     lib-dirs - seq of path strings like [\"libs/user\" \"libs/core\" ...]

   Returns:
     Sorted seq of module name strings (basename of each path)."
  [lib-dirs]
  (->> lib-dirs
       (map #(last (str/split % #"/")))
       (remove #{"ai"})
       sort))

;; =============================================================================
;; Error context — stack trace parsing
;; =============================================================================

(defn extract-file-references
  "Extract file:line references from a stack trace string.

   Finds patterns like (file.clj:42) or file.clj:42 in the trace.

   Args:
     stacktrace - stack trace string

   Returns:
     Sorted distinct seq of 'filename:line' strings."
  [stacktrace]
  (->> (re-seq #"\(([a-zA-Z0-9_\-]+\.clj):(\d+)\)" stacktrace)
       (map (fn [[_ f l]] (str f ":" l)))
       distinct
       sort))

(defn summarise-stacktrace
  "Return the first N lines of a stack trace (to fit context window).

   Args:
     stacktrace - full stack trace string
     n          - max number of lines (default 60)

   Returns:
     Truncated string."
  ([stacktrace] (summarise-stacktrace stacktrace 60))
  ([stacktrace n]
   (->> (str/split-lines stacktrace)
        (take n)
        (str/join "\n"))))

;; =============================================================================
;; Source file context — function signatures
;; =============================================================================

(defn extract-public-function-names
  "Extract public defn names from a Clojure source string.

   Args:
     source - Clojure source code string

   Returns:
     Seq of function name strings."
  [source]
  (->> (re-seq #"\(defn ([a-z][a-z0-9\-?!>]*)" source)
       (map second)
       distinct))

(defn determine-test-type
  "Determine the Kaocha test type for a source file path.

   Args:
     file-path - string like 'libs/user/src/wagoe/user/core/validation.clj'

   Returns:
     :unit, :integration, or :contract keyword."
  [file-path]
  (cond
    (str/includes? file-path "/core/")    :unit
    (str/includes? file-path "/adapters/") :contract
    :else                                              :integration))

(defn derive-test-ns
  "Derive the test namespace string from a source file path.

   Example:
     'libs/user/src/wagoe/user/core/validation.clj'
     => 'wagoe.user.core.validation-test'

   Args:
     source-path - file path string

   Returns:
     Test namespace string."
  [source-path]
  (-> source-path
      (str/replace #".*/src/" "")
      (str/replace #"\.clj$" "-test")
      (str/replace #"/" ".")
      (str/replace #"_" "-")))

(defn derive-test-path
  "Derive the file path a generated test namespace belongs at.

   Example:
     'libs/user/src/wagoe/user/core/validation.clj'
     => 'libs/user/test/wagoe/user/core/validation_test.clj'

   Both monorepo layouts (`libs/<lib>/src/...`) and generated-project layouts
   (`src/...`) work: the first `src` path *segment* becomes `test`. Matching a
   segment rather than a substring keeps a directory such as `srcgen/` or a
   namespace containing `src` from being rewritten.

   Args:
     source-path - file path string

   Returns:
     Test file path string, or nil when `source-path` has no src segment —
     there is no convention to apply, and guessing one would put the file
     somewhere Kaocha does not look."
  [source-path]
  (when (and source-path (re-find #"(?:^|/)src/" source-path))
    (-> source-path
        (str/replace-first #"(^|/)src/" "$1test/")
        (str/replace #"\.clj$" "_test.clj"))))

;; =============================================================================
;; Schema context — table/field discovery
;; =============================================================================

(defn extract-schema-context
  "Build a schema context string from a map of schema file contents.

   Args:
     schema-files - map of {relative-path source-str} for schema.clj files

   Returns:
     A human-readable summary string of table names and fields found."
  [schema-files]
  (when (seq schema-files)
    (->> schema-files
         (map (fn [[path content]]
                (let [defs (->> (re-seq #"\(def ([A-Z][A-Za-z0-9]+)" content)
                                (map second))]
                  (str path ": " (str/join ", " defs)))))
         (str/join "\n"))))

;; =============================================================================
;; Module context — source file discovery
;; =============================================================================

(defn filter-clj-sources
  "From a map of {path content}, keep only .clj source files under src/.

   Args:
     all-files - map of {path content-str}

   Returns:
     Filtered map."
  [all-files]
  (->> all-files
       (filter (fn [[path _]]
                 (and (str/ends-with? path ".clj")
                      (str/includes? path "/src/"))))
       (into {})))

(defn truncate-source
  "Truncate a source file string to max-lines lines to fit context.

   Args:
     source    - source code string
     max-lines - maximum number of lines to keep (default 150)

   Returns:
     Possibly truncated string with a note if truncated."
  ([source] (truncate-source source 150))
  ([source max-lines]
   (let [lines (str/split-lines source)]
     (if (<= (count lines) max-lines)
       source
       (str (str/join "\n" (take max-lines lines))
            "\n;; ... (truncated)")))))
