(ns wagoe.mcp.shell.evaluator
  "Default evaluator for the Tier 2 `eval` tool. Reads and evaluates Clojure
   forms in the server's own JVM, capturing the value and stdout.

   This is RCE by design: the security gate restricts :execute to the :full
   context (local dev), so this is never reachable in prod/CI. The evaluator is
   injected as the `:evaluator` dep so it is swappable — an nREPL-bridge
   evaluator can target the *project's* live REPL instead of the server JVM
   (mirroring the SystemSource nREPL plan)."
  (:require [clojure.string :as str]))

(defn default-evaluator
  "Evaluate `code` (one or more Clojure forms) in-process. Returns
     {:status :ok    :value <pr-str of last value> :out <captured stdout>}
   or
     {:status :error :error <message> :out <captured stdout>}.
   `load-string` evaluates every form and yields the last value."
  [code]
  (let [out (java.io.StringWriter.)]
    (try
      (let [v (binding [*out* out] (load-string code))]
        {:status :ok
         :value  (pr-str v)
         :out    (str/trim-newline (str out))})
      (catch Throwable t
        {:status :error
         :error  (or (.getMessage t) (str (class t)))
         :out    (str/trim-newline (str out))}))))
