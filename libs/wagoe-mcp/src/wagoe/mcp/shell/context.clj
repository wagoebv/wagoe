(ns wagoe.mcp.shell.context
  "Reads the process environment and resolves the active MCP security context.
   The pure decision lives in wagoe.mcp.core.security; this shell wrapper
   only supplies the environment (the I/O)."
  (:require [wagoe.mcp.core.security :as security]))

(defn env-map
  "The process environment as a Clojure map (String -> String)."
  []
  (into {} (map (juxt key val)) (System/getenv)))

(defn from-env
  "Resolve the security context. With no args, reads the live process
   environment; the 1-arity takes an explicit env map (for tests)."
  ([] (from-env (env-map)))
  ([env-map] (security/resolve-context env-map)))
