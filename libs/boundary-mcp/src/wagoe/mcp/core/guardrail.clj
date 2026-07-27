(ns wagoe.mcp.core.guardrail
  "Pure construction of guardrail error payloads — \"guardrail, not
   straitjacket\" (ADR-032).

   Every enforcing tool returns the same shape: the BND code + rule that fired,
   the principle behind it, a suggested fix, and — when the rule is overridable
   — the audited bypass.

   Two classes of denial:
     * Security/capability gates (BOU-97): tier exceeded, read-only, allowlist,
       disabled. HARD — not bypassable per call; the audited override is
       changing the environment/context (e.g. MCP_CAPABILITY_MODE).
     * Codegen guardrails (Tier 1, BOU-101): FC/IS and convention violations in
       generated code. SOFT — bypassable per call with `--allow`, which is
       audited.

   This namespace is pure: it maps a denial to a BND code and assembles the
   payload from a catalog entry passed in. The catalog lookup (I/O) lives in
   wagoe.mcp.shell.guardrail."
  (:require [wagoe.mcp.core.protocol :as proto]))

;; Security/authorize :violation -> BND code (see devtools error catalog).
(def violation->code
  {:disabled           "BND-801"
   :unknown-capability "BND-802"
   :tier-exceeded      "BND-803"
   :read-only          "BND-804"
   :allowlist          "BND-805"})

;; Codes whose guardrail may be bypassed per call with an audited override.
;; Security gates (8xx 801-805) are deliberately absent — they are hard.
(def overridable-codes #{"BND-806" "BND-807"})

(defn overridable?
  [code]
  (contains? overridable-codes code))

(def override-flag
  "Tool-argument key that requests an audited bypass of an overridable guardrail."
  :allow)

(defn from-denial
  "Map a `wagoe.mcp.core.security/authorize` denial to a guardrail
   descriptor: {:code :rule :reason :overridable? :context}. No catalog text —
   the shell enriches it via the BND catalog."
  [denial]
  (let [code (get violation->code (:violation denial) "BND-800")]
    {:code         code
     :rule         (:violation denial)
     :reason       (:reason denial)
     :overridable? (overridable? code)
     :context      (select-keys denial [:tool :capability :mode])}))

(defn build
  "Assemble the full guardrail payload from a `descriptor` (from `from-denial`
   or a codegen guardrail) and a BND `catalog-entry` map (or nil if the code is
   unknown / the catalog is unavailable).

   Payload shape:
     {:code :rule :principle :fix :overridable?
      [:reason] [:details] [:override]}"
  [descriptor catalog-entry]
  (let [{:keys [code rule reason overridable? context]} descriptor]
    (cond-> {:code         code
             :rule         (or (:title catalog-entry)
                               (some-> rule name)
                               "Guardrail")
             :principle    (or (:description catalog-entry) reason)
             :fix          (:fix catalog-entry)
             :overridable? (boolean overridable?)}
      reason          (assoc :reason reason)
      (seq context)   (assoc :details context)
      overridable?    (assoc :override
                             {:flag override-flag
                              :how  (str "Re-invoke with argument "
                                         (pr-str override-flag)
                                         " set to true to proceed; the override is audited.")}))))

(defn ->jsonrpc-error
  "Wrap a guardrail `payload` as a JSON-RPC error response for request `id`.
   Uses the :forbidden application code; the structured payload rides in :data."
  [id payload]
  (proto/error id :forbidden (or (:principle payload) "Request denied by guardrail") payload))

;; --- Audited override path --------------------------------------------------

(defn override-requested?
  "Did the caller request an audited bypass? `arguments` is the tool-call
   arguments map."
  [arguments]
  (true? (get arguments override-flag)))

(defn override-event
  "Audit event recorded when an overridable guardrail is bypassed. Records the
   rule that was overridden so the bypass is reconstructable."
  [payload context]
  {:event     :guardrail-override
   :code      (:code payload)
   :rule      (:rule payload)
   :principle (:principle payload)
   :tool      (:tool context)})
