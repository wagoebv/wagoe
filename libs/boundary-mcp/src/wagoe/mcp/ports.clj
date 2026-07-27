(ns wagoe.mcp.ports
  "Transport seam for the MCP server. The stdio transport (shell) implements
   this; future HTTP/SSE transports implement the same protocol, so the core
   dispatch loop never depends on a concrete transport.")

(defprotocol Transport
  "A bidirectional JSON-RPC message channel to an MCP peer."
  (send! [this message]
    "Serialize and write a single JSON-RPC message map to the peer.")
  (receive [this]
    "Read and parse the next JSON-RPC message from the peer. Returns a map, or
     nil at end of stream.")
  (close! [this]
    "Release the transport's underlying resources."))

(defprotocol AuditLog
  "Records security-relevant events: authorization decisions, overrides,
   mutations, and server lifecycle. Implementations MUST NOT write to stdout —
   that stream is reserved for the protocol (see ADR-031)."
  (record! [this event]
    "Persist a single audit event map. Returns the event."))

(defprotocol SystemSource
  "Supplies a snapshot of the running project's state for reflective resources
   (BOU-99). The in-process adapter reads the live Integrant system; an nREPL
   adapter (later) evaluates introspection forms against the project's REPL.
   The core resource producers are pure functions of this snapshot (ADR-033)."
  (snapshot [this]
    "Return the current project snapshot map (keys: :conventions, :module-graph,
     :kondo-rules, :schema-registry, :routes, :workflows, :libs). Missing keys
     mean 'not reflected in this context'."))
