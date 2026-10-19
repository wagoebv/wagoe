(ns wagoe.storage.shell.adapters.spool
  "A streamed file written to a temporary file before it is stored: that
   gives the length an upload needs up front without holding the file in
   memory, and leaves nothing stored when the writer fails."
  (:import [java.io BufferedOutputStream]
           [java.nio.file Files LinkOption OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration Instant]))

(defn- spool!
  "Call `(write! out)` on a new temporary file in directory `dir`, the
   system's when nil, and return its Path. On failure the file is deleted."
  ^Path [write! ^Path dir]
  (let [attrs (make-array FileAttribute 0)
        path  (if dir
                (Files/createTempFile dir ".spool-" ".tmp" attrs)
                (Files/createTempFile "wagoe-spool-" ".tmp" attrs))]
    (try
      (with-open [out (BufferedOutputStream. (Files/newOutputStream path (make-array OpenOption 0)))]
        (write! out))
      path
      (catch Throwable t
        (Files/deleteIfExists path)
        (throw t)))))

(defn with-spooled
  "Call `(f path)` on what `write!` wrote, spooled to directory `dir`, the
   system's temporary one when nil, and delete the file afterwards unless `f`
   moved it."
  ([write! f] (with-spooled write! nil f))
  ([write! dir f]
   (let [path (spool! write! dir)]
     (try (f path)
          (finally (Files/deleteIfExists path))))))

(defn remove-abandoned!
  "Delete the spools in `dir` nobody has written to for `idle`, a Duration:
   left by a process that died mid-write. One being written is newer."
  [^Path dir ^Duration idle]
  (when (Files/isDirectory dir (make-array LinkOption 0))
    (let [cutoff (.minus (Instant/now) idle)]
      (with-open [paths (Files/newDirectoryStream dir ".spool-*.tmp")]
        (doseq [^Path p paths
                :when (.isBefore (.toInstant (Files/getLastModifiedTime p (make-array LinkOption 0))) cutoff)]
          (Files/deleteIfExists p))))))
