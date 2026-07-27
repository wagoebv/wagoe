(ns wagoe.platform.shell.adapters.filesystem.protocols
  "Protocol defining the common interface for filesystem adapters.
   
   This protocol abstracts filesystem operations to enable testing
   with in-memory implementations and support different storage
   backends (local FS, cloud storage, etc.).
   
   Design Philosophy:
   - Protocol defines generic file operations
   - Implementations handle specific storage backends
   - Enables testing without actual filesystem I/O
   - Supports multiple storage strategies (local, cloud, in-memory)")

;; =============================================================================
;; File System Adapter Protocol
;; =============================================================================

(defprotocol IFileSystemAdapter
  "Generic file system operations for storage abstraction.
   
   This protocol provides a common interface for file system operations,
   allowing different implementations for production (real filesystem),
   testing (in-memory), and cloud storage scenarios.
   
   Implementations:
   - FileSystemAdapter: Real filesystem operations using java.io
   - InMemoryFileSystemAdapter: In-memory storage for testing
   - CloudStorageAdapter: Cloud storage backends (S3, etc.)"

  (file-exists? [this path]
    "Check if a file exists at the given path.
     
     Args:
       path - String path relative to adapter's base path
     
     Returns:
       Boolean - true if file exists, false otherwise
     
     Example:
       (file-exists? adapter \"src/foo.clj\") ;; => true")

  (read-file [this path]
    "Read the entire content of a file.
     
     Args:
       path - String path relative to adapter's base path
     
     Returns:
       String - file content, or nil if file doesn't exist
     
     Example:
       (read-file adapter \"config.edn\") ;; => \"{:port 3000}\"")

  (write-file [this path content]
    "Write content to a file, creating parent directories if needed.
     
     Args:
       path - String path relative to adapter's base path
       content - String content to write
     
     Returns:
       Boolean - true if write successful, false or throws on error
     
     Side Effects:
       - Creates parent directories if they don't exist
       - Overwrites existing file content
     
     Example:
       (write-file adapter \"output.txt\" \"Hello World\") ;; => true")

  (delete-file [this path]
    "Delete a file at the given path.
     
     Args:
       path - String path relative to adapter's base path
     
     Returns:
       Boolean - true if file was deleted, false if file didn't exist
     
     Example:
       (delete-file adapter \"temp.txt\") ;; => true")

  (list-files [this directory]
    "List all files in a directory (non-recursive).
     
     Args:
       directory - String path to directory relative to base path
     
     Returns:
       Vector of filename strings (not full paths), or nil if directory doesn't exist
     
     Note:
       - Only returns files, not subdirectories
       - Returns filenames only, not full paths
       - Non-recursive (doesn't traverse subdirectories)
     
     Example:
       (list-files adapter \"src\") ;; => [\"core.clj\" \"util.clj\"]"))
