(ns wagoe.mcp.shell.stdio
  "Newline-delimited JSON-RPC over stdin/stdout (MCP stdio transport).
   stdout carries protocol messages only; all logging goes to stderr so it
   never corrupts the message stream."
  (:require [wagoe.mcp.core.protocol :as proto]
            [wagoe.mcp.ports :as ports]
            [wagoe.mcp.shell.codec :as codec]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.io BufferedReader Writer)))

(defrecord StdioTransport [^BufferedReader reader ^Writer writer]
  ports/Transport
  (send! [_ message]
    (let [line (codec/encode message)]
      (locking writer
        (.write writer line)
        (.write writer "\n")
        (.flush writer))))
  (receive [_]
    ;; Return nil only at true end-of-stream (readLine -> nil). Skip blank
    ;; lines so a stray newline never looks like EOF and stops the server.
    (loop []
      (let [line (.readLine reader)]
        (cond
          (nil? line)       nil
          (str/blank? line) (recur)
          :else             (codec/decode line)))))
  (close! [_]
    (.close reader)
    (.close writer)))

(defn transport
  "Construct a StdioTransport. With no args, binds to process stdin/stdout.
   The 2-arity accepts anything `clojure.java.io/reader` and `/writer` handle
   (e.g. a StringReader / StringWriter in tests). Streams are UTF-8 as the MCP
   spec mandates."
  ([] (transport System/in System/out))
  ([in out]
   (->StdioTransport (io/reader in :encoding "UTF-8")
                     (io/writer out :encoding "UTF-8"))))

(defn serve
  "Blocking receive → dispatch → respond loop. Reads messages from `t`,
   dispatches each with `handle-fn` (a 1-arg fn of the parsed message returning
   a response map or nil), and writes responses. Returns when the peer closes
   the stream (EOF). Malformed input yields a JSON-RPC parse error and the loop
   continues."
  [t handle-fn]
  (log/info "boundary-mcp stdio server ready")
  (loop []
    (let [msg (try
                (ports/receive t)
                (catch Exception e
                  (log/warn e "JSON-RPC parse error")
                  (ports/send! t (proto/error nil :parse-error (.getMessage e)))
                  ::parse-error))]
      (cond
        (nil? msg) nil ;; EOF — fall through to shutdown log

        (= ::parse-error msg) (recur)

        :else
        (do
          (try
            (when-let [response (handle-fn msg)]
              (ports/send! t response))
            (catch Exception e
              (log/error e "Error handling MCP message")
              (when (:id msg)
                (ports/send! t (proto/error (:id msg) :internal-error
                                            (.getMessage e))))))
          (recur)))))
  (log/info "boundary-mcp stdio server stopped (EOF)"))
