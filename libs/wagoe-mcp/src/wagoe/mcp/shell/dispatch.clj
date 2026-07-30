(ns wagoe.mcp.shell.dispatch
  "Shell-level request dispatch. Pure protocol methods delegate to
   wagoe.mcp.core.handlers; resources/read is handled here because it needs
   I/O (snapshot reflection, JSON serialization), the security gate, and the
   guardrail payload on denial.

   `deps` is {:registry :security :audit :system-source :ai-provider}."
  (:require [wagoe.mcp.core.handlers :as handlers]
            [wagoe.mcp.core.protocol :as proto]
            [wagoe.mcp.core.resources :as resources]
            [wagoe.mcp.core.security :as security]
            [wagoe.mcp.core.tools :as tools]
            [wagoe.mcp.ports :as ports]
            [wagoe.mcp.shell.codec :as codec]
            [wagoe.mcp.shell.guardrail :as guardrail]
            [wagoe.mcp.shell.tools :as tool-exec]))

(defn- resource-read
  [deps id params]
  (let [{sec :security, audit :audit, src :system-source} deps
        uri      (:uri params)
        decision (security/authorize sec {:name       (str "resource:" uri)
                                          :capability resources/resource-capability})]
    (if (:allow? decision)
      (let [data (resources/read-resource (ports/snapshot src) uri)]
        (if (nil? data)
          (proto/error id :invalid-params (str "Unknown resource: " uri) {:uri uri})
          (do
            (when audit (ports/record! audit {:event :resource-read :uri uri}))
            (proto/success id {:contents [{:uri      uri
                                           :mimeType (resources/mime-type uri)
                                           :text     (codec/encode data)}]}))))
      (let [resp (guardrail/error-for-denial id decision)]
        (when audit (ports/record! audit {:event :resource-read-denied
                                          :uri   uri
                                          :code  (get-in resp [:error :data :code])}))
        resp))))

(defn- tool-call
  [deps id params]
  (let [{sec :security, audit :audit} deps
        tool-name (:name params)
        args      (:arguments params)]
    (if-not (contains? tools/tool-names tool-name)
      (proto/error id :invalid-params (str "Unknown tool: " tool-name) {:name tool-name})
      (let [decision (security/authorize sec {:name       tool-name
                                              :capability (tools/capability tool-name)})]
        (if-not (:allow? decision)
          (let [resp (guardrail/error-for-denial id decision)]
            (when audit (ports/record! audit {:event :tool-call-denied
                                              :tool  tool-name
                                              :code  (get-in resp [:error :data :code])}))
            resp)
          (try
            (let [data (tool-exec/run deps tool-name args)]
              (when audit (ports/record! audit {:event :tool-call :tool tool-name}))
              (proto/success id {:content [{:type "text" :text (codec/encode data)}]
                                 :isError false}))
            (catch Exception e
              (when audit (ports/record! audit {:event :tool-call-error
                                                :tool  tool-name
                                                :error (.getMessage e)}))
              ;; MCP tool failures are returned as an isError result, not a
              ;; protocol error, so the agent sees the message and can adapt.
              (proto/success id {:content [{:type "text"
                                            :text (codec/encode {:error (.getMessage e)})}]
                                 :isError true}))))))))

(defn dispatch
  "Dispatch a parsed JSON-RPC message against `deps`. resources/read and
   tools/call are handled in the shell (I/O, gate, guardrail, JSON); every
   other method delegates to the pure core handler."
  [deps msg]
  (let [{:keys [id method params]} msg]
    (case method
      "resources/read" (when (proto/request? msg) (resource-read deps id params))
      "tools/call"     (when (proto/request? msg) (tool-call deps id params))
      (handlers/handle (:registry deps) msg))))
