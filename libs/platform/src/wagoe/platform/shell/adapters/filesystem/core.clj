(ns wagoe.platform.shell.adapters.filesystem.core
  "File system adapter implementation for local filesystem operations.
   
   Provides concrete implementation of IFileSystemAdapter protocol
   using Java I/O for local filesystem access."
(:require [wagoe.platform.shell.adapters.filesystem.protocols :as fs-protocols]
            [clojure.java.io :as io]))

;; =============================================================================
;; File System Adapter Implementation
;; =============================================================================

(defrecord FileSystemAdapter [base-path]
  fs-protocols/IFileSystemAdapter

  (file-exists? [_ path]
    (let [full-path (io/file base-path path)]
      (.exists full-path)))

  (read-file [_ path]
    (let [full-path (io/file base-path path)]
      (when (.exists full-path)
        (slurp full-path))))

  (write-file [_ path content]
    (let [full-path (io/file base-path path)]
      ;; Create parent directories if needed
      (io/make-parents full-path)
      (spit full-path content)
      true))

  (delete-file [_ path]
    (let [full-path (io/file base-path path)]
      (when (.exists full-path)
        (io/delete-file full-path)
        true)))

  (list-files [_ directory]
    (let [dir (io/file base-path directory)]
      (when (.exists dir)
        (->> (.listFiles dir)
             (filter #(.isFile %))
             (map #(.getName %))
             vec)))))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn create-file-system-adapter
  "Create a new file system adapter for local filesystem operations.
   
   The adapter operates relative to a base path, making all file operations
   relative to that path. This enables safer operations and easier testing.
   
   Args:
     base-path - String base path for all file operations (optional, defaults to \".\")
   
   Returns:
     FileSystemAdapter instance implementing IFileSystemAdapter
   
   Examples:
     ;; Use current directory as base
     (def adapter (create-file-system-adapter))
     
     ;; Use specific directory as base
     (def adapter (create-file-system-adapter \"/tmp/output\"))
     
     ;; All operations are relative to base path
     (write-file adapter \"test.txt\" \"content\")  ; writes to /tmp/output/test.txt"
  ([]
   (create-file-system-adapter "."))
  ([base-path]
   (->FileSystemAdapter base-path)))
