(ns wagoe.mcp.shell.guardrail
  "Resolves guardrail payloads against the shared BND error catalog
   (wagoe.devtools.error-codes — the single source of truth). The catalog
   lookup loads a resource (I/O), so it lives in the shell; payload assembly
   stays pure in wagoe.mcp.core.guardrail."
  (:require [wagoe.devtools.error-codes :as codes]
            [wagoe.mcp.core.guardrail :as guardrail]))

(defn payload-for-denial
  "Full guardrail payload for a `wagoe.mcp.core.security/authorize` denial,
   enriched with the BND catalog entry (title / principle / fix)."
  [denial]
  (let [descriptor (guardrail/from-denial denial)
        entry      (codes/lookup (:code descriptor))]
    (guardrail/build descriptor entry)))

(defn error-for-denial
  "JSON-RPC error response (for request `id`) carrying the guardrail payload."
  [id denial]
  (guardrail/->jsonrpc-error id (payload-for-denial denial)))

(defn payload-for-code
  "Full guardrail payload for a codegen guardrail identified by BND `code`
   (e.g. BND-806 FC/IS, BND-807 convention). `extra` merges into the descriptor
   (e.g. {:reason ... :context {:tool ...}})."
  ([code] (payload-for-code code {}))
  ([code extra]
   (let [descriptor (merge {:code         code
                            :rule         nil
                            :overridable? (guardrail/overridable? code)}
                           extra)
         entry      (codes/lookup code)]
     (guardrail/build descriptor entry))))
