(ns wagoe.mcp.core.registry
  "Tool and resource registry as plain data. Empty in the skeleton (BOU-96).
   Tier 0 read/analyze tools (BOU-100) and reflective resources (BOU-99)
   register their definitions here; the stdio transport stays unchanged.")

(def empty-registry
  "An MCP server with no tools or resources."
  {:tools     {}     ;; tool name -> tool definition map
   :resources {}})   ;; resource uri -> resource definition map

(defn register-tool
  "Add a tool definition (a map with at least :name) to the registry."
  [registry tool]
  (assoc-in registry [:tools (:name tool)] tool))

(defn register-resource
  "Add a resource definition (a map with at least :uri) to the registry."
  [registry resource]
  (assoc-in registry [:resources (:uri resource)] resource))

(defn list-tools
  "MCP `tools/list` result. Exposes only wire fields, dropping any internal
   keys (e.g. a server-side :handler)."
  [registry]
  {:tools (mapv #(select-keys % [:name :description :inputSchema])
                (vals (:tools registry)))})

(defn list-resources
  "MCP `resources/list` result. Exposes only wire fields."
  [registry]
  {:resources (mapv #(select-keys % [:uri :name :description :mimeType])
                    (vals (:resources registry)))})
