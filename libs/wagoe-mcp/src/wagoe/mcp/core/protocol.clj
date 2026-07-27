(ns wagoe.mcp.core.protocol
  "Pure JSON-RPC 2.0 + MCP message construction. No I/O — the shell codec
   (wagoe.mcp.shell.codec) owns JSON serialization.

   Maps here use the exact JSON-RPC / MCP wire keys, including camelCase where
   the spec dictates (e.g. :protocolVersion, :serverInfo). This namespace IS
   the protocol boundary, so the wire schema is the representation; the usual
   kebab-case-internal rule does not apply to these protocol field names.")

(def jsonrpc-version "2.0")

;; Supported MCP protocol revisions, newest (preferred) first. Pinning the set
;; is part of the security contract (ADR-031): the server only speaks versions
;; it has been reviewed against.
(def supported-protocol-versions ["2025-06-18"])

;; Preferred revision — what the server proposes when the client's request is
;; absent or unsupported.
(def mcp-protocol-version (first supported-protocol-versions))

(defn supported-protocol-version?
  [version]
  (boolean (some #{version} supported-protocol-versions)))

(defn negotiate-version
  "Resolve the protocol version for the handshake: echo the client's
   `requested` version when supported, otherwise propose the server's
   preferred. (An MCP client that cannot speak the proposed version disconnects
   after `initialize`.)"
  [requested]
  (if (supported-protocol-version? requested)
    requested
    mcp-protocol-version))

;; Standard JSON-RPC 2.0 error codes (https://www.jsonrpc.org/specification),
;; plus application-defined codes in the reserved server range (-32000..-32099).
(def error-codes
  {:parse-error      -32700
   :invalid-request  -32600
   :method-not-found -32601
   :invalid-params   -32602
   :internal-error   -32603
   ;; A guardrail/capability denial. The structured guardrail payload rides in
   ;; the error's :data (see wagoe.mcp.core.guardrail).
   :forbidden        -32001})

(defn request?
  "A JSON-RPC request expects a response (carries an :id); a notification does
   not."
  [msg]
  (contains? msg :id))

(defn notification?
  [msg]
  (not (request? msg)))

(defn success
  "Build a JSON-RPC success response for request `id` with `result` payload."
  [id result]
  {:jsonrpc jsonrpc-version
   :id      id
   :result  result})

(defn error
  "Build a JSON-RPC error response. `code-key` is a key in `error-codes` (or a
   raw integer code). `data` is optional extra context."
  ([id code-key message] (error id code-key message nil))
  ([id code-key message data]
   (let [code (get error-codes code-key code-key)]
     {:jsonrpc jsonrpc-version
      :id      id
      :error   (cond-> {:code code :message message}
                 (some? data) (assoc :data data))})))
