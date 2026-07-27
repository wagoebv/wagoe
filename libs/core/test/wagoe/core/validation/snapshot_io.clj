(ns wagoe.core.validation.snapshot-io
  "Test-only I/O helpers for snapshot testing.

  This namespace contains side-effectful operations for reading and writing snapshots.
  All I/O is guarded by environment variables:
  - UPDATE_SNAPSHOTS: When true, writes new snapshots
  - BND_DEVEX_VALIDATION: When false, snapshots act as pass-through assertions

  Usage:
    ;; In tests - check snapshot matches
    (check-snapshot! actual {:ns *ns* :test 'email-required :case 'missing})

    ;; Update snapshots mode
    UPDATE_SNAPSHOTS=true clojure -M:test:db/h2

    ;; Disable validation devex (snapshots pass through)
    BND_DEVEX_VALIDATION=false clojure -M:test:db/h2"
  (:require [wagoe.core.validation.snapshot :as snapshot]
            [clojure.java.io :as io]
            [clojure.test :as t]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Environment Checks
;; -----------------------------------------------------------------------------

(defn- update-snapshots?
  "Check if UPDATE_SNAPSHOTS environment variable is set to true."
  []
  (= "true" (System/getenv "UPDATE_SNAPSHOTS")))

(defn- devex-enabled?
  "Check if BND_DEVEX_VALIDATION is enabled (default true)."
  []
  (not= "false" (System/getenv "BND_DEVEX_VALIDATION")))

;; -----------------------------------------------------------------------------
;; File I/O
;; -----------------------------------------------------------------------------

(defn- ensure-parent-dir!
  "Create parent directories if they don't exist.

  Args:
    file-path - Path string

  Side effects:
    Creates directories on filesystem."
  [file-path]
  (let [file (io/file file-path)
        parent (.getParentFile file)]
    (when parent
      (.mkdirs parent))))

(defn write-snapshot!
  "Write snapshot to file using deterministic serialization.

  Args:
    snapshot  - Snapshot map (from snapshot/capture)
    file-path - Path string where snapshot should be written

  Side effects:
    - Creates parent directories
    - Writes EDN file to disk

  Returns:
    file-path"
  [snapshot file-path]
  (ensure-parent-dir! file-path)
  (let [edn-str (snapshot/stable-serialize snapshot)]
    (spit file-path edn-str)
    file-path))

(defn read-snapshot!
  "Read snapshot from file.

  Args:
    file-path - Path string to snapshot file

  Side effects:
    Reads file from disk.

  Returns:
    Snapshot map, or nil if file doesn't exist."
  [file-path]
  (let [file (io/file file-path)]
    (when (.exists file)
      (snapshot/parse-snapshot (slurp file-path)))))

;; -----------------------------------------------------------------------------
;; Diff Formatting
;; -----------------------------------------------------------------------------

(defn- format-unified-diff
  "Format diff in unified diff style for easy reading.

  Args:
    diff-result - Result from snapshot/compare

  Returns:
    Formatted string."
  [diff-result]
  (snapshot/format-diff diff-result))

;; -----------------------------------------------------------------------------
;; Assertion Helper
;; -----------------------------------------------------------------------------

(defn check-snapshot!
  "Check actual result against stored snapshot.

  Behavior depends on environment:
  - UPDATE_SNAPSHOTS=true: Always writes new snapshot (pass)
  - File missing: Writes snapshot and passes
  - File exists: Compares and fails with diff if mismatch
  - BND_DEVEX_VALIDATION=false: Pass-through (always passes)

  Args:
    actual - Actual validation result or data to snapshot
    opts   - Options map:
             :ns   - Test namespace (*ns* in tests)
             :test - Test name
             :case - Test case name (optional)
             :seed - Seed used for generation (optional, for metadata)
             :meta - Additional metadata (optional)

  Side effects:
    - May write snapshot file
    - Asserts via clojure.test

  Returns:
    true if snapshot matches, false otherwise (for REPL use).

  Example:
    (deftest email-required-missing-test
      (let [result (validate-user {:name \"Test\"})]
        (check-snapshot! result {:ns *ns* 
                                :test 'email-required 
                                :case 'missing
                                :seed 42})))"
  [actual opts]
  (if-not (devex-enabled?)
    ;; Pass-through mode when devex disabled
    (do
      (t/is true "Snapshot check skipped (BND_DEVEX_VALIDATION=false)")
      true)

    ;; Normal snapshot checking
    (let [file-path (snapshot/path-for opts)
          snapshot-opts {:seed (:seed opts)
                         :meta (merge (:meta opts)
                                      {:test-ns (:ns opts)
                                       :test-name (:test opts)
                                       :case-name (:case opts)})}
          actual-snap (snapshot/capture actual snapshot-opts)]

      (cond
        ;; Force update mode
        (update-snapshots?)
        (do
          (write-snapshot! actual-snap file-path)
          (t/is true (str "Snapshot updated: " file-path))
          true)

        ;; File doesn't exist - write it
        (not (.exists (io/file file-path)))
        (do
          (write-snapshot! actual-snap file-path)
          (t/is true (str "Snapshot created: " file-path))
          true)

        ;; File exists - compare
        :else
        (let [expected-snap (read-snapshot! file-path)
              ;; Normalize both snapshots to ensure consistent data types (strings, not objects)
              ;; This is done by serializing and deserializing both
              normalized-expected (snapshot/parse-snapshot (snapshot/stable-serialize expected-snap))
              normalized-actual (snapshot/parse-snapshot (snapshot/stable-serialize actual-snap))
              comparison (snapshot/compare-snapshots normalized-expected normalized-actual)]
          (if (:equal? comparison)
            (do
              (t/is true "Snapshot matches")
              true)
            (let [diff-msg (format-unified-diff comparison)]
              (t/is false (str "Snapshot mismatch for " file-path "\n"
                               diff-msg
                               "\n\nTo update snapshots, run:\n"
                               "  UPDATE_SNAPSHOTS=true clojure -M:test:db/h2"))
              false)))))))

;; -----------------------------------------------------------------------------
;; Utilities
;; -----------------------------------------------------------------------------

(defn delete-snapshot!
  "Delete a snapshot file.

  Useful for test cleanup or when renaming tests.

  Args:
    opts - Same options as check-snapshot! (:ns, :test, :case)

  Side effects:
    Deletes file from filesystem.

  Returns:
    true if file was deleted, false if it didn't exist."
  [opts]
  (let [file-path (snapshot/path-for opts)
        file (io/file file-path)]
    (when (.exists file)
      (.delete file)
      true)))

(defn list-snapshots
  "List all snapshot files for a test namespace.

  Args:
    ns-sym - Namespace symbol

  Returns:
    Vector of file paths (strings)."
  [ns-sym]
  (let [base-path (snapshot/path-for {:ns ns-sym :test "_dummy"})
        parent-dir (-> base-path
                       (str/replace #"/[^/]+$" "")
                       io/file)]
    (when (.exists parent-dir)
      (->> (.listFiles parent-dir)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".edn"))
           (mapv #(.getPath %))))))
