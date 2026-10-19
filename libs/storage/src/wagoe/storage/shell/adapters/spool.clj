(ns wagoe.storage.shell.adapters.spool
  "A streamed file written to a temporary file before it is stored: that
   gives the length an upload needs up front without holding the file in
   memory, and leaves nothing stored when the writer fails."
  (:import [java.io BufferedOutputStream]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn spool!
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
  "Call `(f path)` on what `write!` wrote, spooled to the system's temporary
   directory, and delete the file afterwards."
  [write! f]
  (let [path (spool! write! nil)]
    (try (f path)
         (finally (Files/deleteIfExists path)))))
