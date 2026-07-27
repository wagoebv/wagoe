(ns wagoe.mcp.shell.codec
  "JSON <-> Clojure data codec for the JSON-RPC wagoe. Lives in shell so
   cheshire (an I/O-adjacent dependency) stays out of the functional core."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn decode
  "Parse one JSON-RPC line into a Clojure map with keyword keys. Returns nil
   for blank input. Throws on malformed JSON (the caller maps that to a
   JSON-RPC parse error).

   Keys are keywordized so the core reads protocol/tool fields idiomatically.
   Keyword interning from untrusted input is bounded at runtime: Clojure's
   keyword table holds weak references and clears stale entries, so transient
   keys from peer input are reclaimed by GC. If a non-local transport (HTTP/SSE,
   multiple/untrusted peers) is added, revisit this — keep tool `:arguments`
   string-keyed there to remove the interning surface entirely."
  [line]
  (when-not (str/blank? line)
    (json/parse-string line true)))

(defn encode
  "Serialize a JSON-RPC message map to a single-line JSON string."
  [message]
  (json/generate-string message))
