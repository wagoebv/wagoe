(ns wagoe.mcp.core.handlers
  "Pure JSON-RPC dispatch. `(handle registry msg)` -> response map for a
   request, or nil for a notification / handled-without-reply message. No I/O;
   the shell transport feeds parsed messages in and writes responses out."
  (:require [wagoe.mcp.core.protocol :as proto]
            [wagoe.mcp.core.registry :as registry]))

(def server-info
  {:name "boundary-mcp" :version "0.1.0"})

(defn- initialize-result
  "MCP `initialize` handshake result: negotiate the protocol version against
   the client's request, advertise capabilities (tools + resources, both with
   empty option maps), and report server identity."
  [params]
  {:protocolVersion (proto/negotiate-version (:protocolVersion params))
   :capabilities    {:tools     {}
                     :resources {}}
   :serverInfo      server-info})

(defn handle
  "Dispatch a parsed JSON-RPC message against `registry`. Returns a response
   map for requests; nil for notifications and unknown notifications."
  [registry msg]
  (let [{:keys [id method params]} msg]
    (case method
      "initialize"                 (proto/success id (initialize-result params))
      "ping"                       (proto/success id {})
      "tools/list"                 (proto/success id (registry/list-tools registry))
      "resources/list"             (proto/success id (registry/list-resources registry))
      "notifications/initialized"  nil ;; client ack after initialize; no reply
      ;; default: unknown method — error for requests, silence for notifications
      (when (proto/request? msg)
        (proto/error id :method-not-found
                     (str "Method not found: " method)
                     {:method method})))))
