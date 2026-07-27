# wagoe-mcp

**An MCP server that lets an AI coding agent work _inside_ a Boundary project — safely.**

It turns the Boundary framework's knowledge and code-generation tools into a set
of capabilities a model can call directly: analyse errors, lint, scaffold whole
FC/IS modules, run tests, query the database. Every call is gated by what the
current environment allows and — for code generation — runs through a closed
verify loop so the agent self-corrects without a human in the way.

> **Status:** active development. Tier 0 (read), Tier 1 (generate + verify loop),
> and Tier 2 (execute) tools are implemented. Some live-introspection resources
> and the read-only DB datasource are stubbed `:unavailable` pending an nREPL
> bridge — see [Reflective resources](#reflective-resources).

This README is layered, read as far as you need:

1. **[The idea](#1-the-idea--why-this-exists)** — what it is and why it matters (mental model).
2. **[Using it](#2-using-it)** — wire it into Claude Code / Cursor and a worked walkthrough.
3. **[How it works](#3-how-it-works-architecture)** — architecture for contributors.

For the dense reference (every tool's inputs, every BND code, line refs), see
[`AGENTS.md`](./AGENTS.md). This README is the narrative; `AGENTS.md` is the spec.

---

## 1. The idea — why this exists

### The problem

An AI agent helping you on a Boundary project is, by default, working blind. It
reads files, greps, guesses at conventions, and writes code it _hopes_ compiles
and respects the framework's rules. When it's wrong, you find out later. And the
rules it has to respect aren't trivia — Boundary enforces a strict **Functional
Core / Imperative Shell** split that a generic model violates constantly (core
importing shell, validation in the wrong layer, schema/DB drift).

Three things are missing:

- **Ground truth.** The agent should _ask the project_ what its modules,
  conventions, and lint rules are — not infer them.
- **A feedback loop.** When the agent generates code, it should immediately learn
  whether that code lints, respects FC/IS, and passes tests — then fix it itself.
- **A safety enclosure.** The same agent might be pointed at your laptop _or_ at a
  box with a production database. What it's allowed to do must depend on where it
  is, enforced — not on the prompt asking it nicely.

wagoe-mcp is all three.

### What it is

It's a small server speaking the **Model Context Protocol** (MCP) — the open
standard editor agents (Claude Code, Cursor, …) use to discover and call tools.
You run it from a Boundary project root; your editor's agent connects to it and
gains a menu of Boundary-aware capabilities.

```
┌─────────────┐   MCP over stdio    ┌──────────────────┐   reflects / writes / runs
│ Claude Code │ ◄─────────────────► │   wagoe-mcp   │ ◄──────────────────────────►  Your Boundary project
│  / Cursor   │   (JSON-RPC 2.0)    │  (this server)   │     (files, kondo, tests, DB)
└─────────────┘                     └──────────────────┘
```

### The mental model: three tiers, one enclosure

Every tool declares a **capability tier** by how much damage it could do:

| Tier | Capability | What it can do | Reversible? |
|------|-----------|----------------|-------------|
| **0** | `:read` | Read & analyse. Lint, explain errors, validate schemas, describe modules, preview SQL. | Nothing to reverse |
| **1** | `:generate` | Write code to disk. Scaffold modules, add fields, generate tests & migrations. | Yes — via `git` |
| **2** | `:execute` | Run things. Tests, `eval`, apply migrations, query the DB. | This is the RCE surface |

The **environment** sets a ceiling on which tiers are reachable. You don't trust
the agent to stay in its lane — the server _enforces_ the lane:

| Environment signal | Mode | Max tier reachable |
|--------------------|------|--------------------|
| `BND_ENV=dev` / `test` | `:full` | Tier 2 — everything |
| `BND_ENV=prod` | `:no-execute` | Tier 1 — can write, can't run |
| `CI` truthy, or **no signal at all** | `:read-only` | Tier 0 — analysis only |
| `MCP_CAPABILITY_MODE=disabled` | `:disabled` | nothing |

Two properties make this trustworthy:

- **Fail-closed.** No clear signal → `:read-only`. The default is the _least_
  privilege, not the most.
- **Enforced before work runs.** A `:execute` tool called in prod is denied at
  dispatch — before a line of code executes — and the agent gets a structured
  explanation of _why_ (a `BND-8xx` guardrail with the rule, the principle, and
  the fix).

### The leverage: the closed verify loop

This is the part worth understanding. Tier 1 tools don't just write code and
hope. Every generate runs a **closed verify loop**:

```
generate → write files → clj-kondo → FC/IS check → run affected tests → structured report
```

The agent gets back a precise report: which step failed, which file and line,
which BND code, the expected vs actual. So instead of "I scaffolded a module,
hope it's right," the loop is:

> scaffold → _"FC/IS violation: core/invoice.clj:12 imports shell"_ → agent fixes it → re-verify → pass.

No human approval needed between iterations. The framework's hardest rules stop
being documentation the model forgets and become **a gate it has to pass**.

The guardrails distinguish **hard** failures from **soft** ones:

- **Hard** (kondo errors, failing tests): not bypassable. The code doesn't
  compile or doesn't pass — there's nothing to override.
- **Soft** (FC/IS `BND-806`, naming conventions `BND-807`): bypassable with an
  explicit `"allow": true`, which is **audited**. "Guardrail, not straitjacket" —
  a deliberate override is allowed and recorded; an accidental one is blocked.

That combination — ground truth + self-correcting codegen + an enforced safety
enclosure — is what an agent _can't easily get_ any other way, and it's the whole
point of wagoe-mcp.

---

## 2. Using it

### Run the server

> **The working directory matters.** The in-process adapter reflects **cwd** —
> tool paths, the `lint` targets, and the reflective resources
> (`boundary://conventions`, `boundary://module-graph`, …) all resolve against the
> directory the server runs in. Run it from the **root of the Boundary project you
> want the agent to work on**, or those resources come back `:unavailable`.

**From a project root** (the monorepo itself, or any app built on Boundary) — put
the server on the classpath and launch its main, so cwd stays the project root:

```bash
# from the Boundary project root
BND_ENV=dev clojure -Sdeps '{:deps {boundary/mcp {:local/root "libs/wagoe-mcp"}}}' \
  -M -m boundary.mcp.shell.server
```

An app consuming Boundary as a dependency would instead add `boundary/mcp` to its
own `deps.edn` and expose a one-line alias for that command.

**Quick smoke / dev from the lib directory** — convenient, but cwd is then
`libs/wagoe-mcp`, so the reflective resources report `:unavailable` (the
protocol handshake and the tools still work):

```bash
cd libs/wagoe-mcp
clojure -M:run        # stdio MCP server — reads JSON-RPC from stdin, returns on EOF
```

It logs to **stderr only** — stdout is reserved for the protocol stream, so never
`println` into it. The mode is resolved from the environment at startup (set
`BND_ENV` / `CI` / `MCP_CAPABILITY_MODE` on either command):

```bash
BND_ENV=dev    # :full       — Tier 2 available (local dev)
BND_ENV=prod   # :no-execute — Tier 1 max
CI=true        # :read-only  — Tier 0 only
MCP_CAPABILITY_MODE=read-only   # explicit override (wins over everything)
```

### Smoke-test the handshake

Before wiring an editor, confirm it speaks:

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  '{"jsonrpc":"2.0","id":3,"method":"resources/list"}' \
  | clojure -M:run 2>/dev/null
```

You should see three JSON-RPC responses: the handshake, the tool catalog, and the
resource catalog.

### Wire it into Claude Code

Register it as an MCP server whose `cwd` is your **project root** (so reflection
targets the project) and whose command puts the server on the classpath. With the
Claude Code CLI, run this from the project root:

```bash
claude mcp add boundary --env BND_ENV=dev -- \
  clojure -Sdeps '{:deps {boundary/mcp {:local/root "libs/wagoe-mcp"}}}' \
  -M -m boundary.mcp.shell.server
```

Or by hand, in your MCP config (`.mcp.json` / client settings):

```json
{
  "mcpServers": {
    "boundary": {
      "command": "clojure",
      "args": ["-Sdeps", "{:deps {boundary/mcp {:local/root \"libs/wagoe-mcp\"}}}",
               "-M", "-m", "boundary.mcp.shell.server"],
      "cwd": "/absolute/path/to/your/boundary/project",
      "env": { "BND_ENV": "dev" }
    }
  }
}
```

> `cwd` is the **project root**, not `libs/wagoe-mcp` — the server reflects its
> working directory, so pointing it at the lib dir would leave the reflective
> resources `:unavailable`. An app that depends on Boundary would reference its own
> `boundary/mcp` coordinate instead of the `libs/wagoe-mcp` local root.

Cursor and other MCP clients take the same shape: a `command`, `args`, `cwd`,
and `env`.

### The tools, at a glance

**Tier 0 — read & analyse** (always available, even in CI):

| Tool | What you ask it for |
|------|---------------------|
| `explain-error` | Paste a stacktrace → summary + the matching `BND-xxx` rule, principle, and fix |
| `lint` | clj-kondo findings (file/row/col/level/message) for given paths |
| `validate-schema` | Check a value against a Malli schema → humanized errors |
| `describe-module` | A module's deps, ports, libraries — from the live project, not docs |
| `sql-preview` | Natural language → HoneySQL + raw SQL, **generated, never run** (needs an AI provider) |

**Tier 1 — generate (with the verify loop)** (denied in CI / read-only):

| Tool | What it does |
|------|--------------|
| `scaffold-module` | A full FC/IS module (schema, ports, core, shell, tests, migration) from a spec |
| `add-field` | Add a field to an existing entity → migration + schema updates |
| `gen-tests` | Generate a test namespace for a source file (needs an AI provider) |
| `gen-migration` | A SQL migration for an entity's table |

**Tier 2 — execute** (only in `:full` / local dev):

| Tool | What it does |
|------|--------------|
| `run-tests` | Run a module's Kaocha suite → structured pass/fail |
| `eval` | Evaluate Clojure code → value + captured stdout (**the RCE surface**) |
| `run-migration` | Apply (`up`) or report (`status`) — destructive rollbacks are **not** exposed |
| `query-db` | One read-only SQL query, row-limited (needs a read-only datasource — `:unavailable` until wired) |

### Worked walkthrough: scaffold a module and watch it self-correct

This is the loop in action. The agent wants to add an `invoice` module.

**1. It orients itself** — reads ground truth instead of guessing:

```jsonc
// tools/call → describe-module, and a read of boundary://conventions
{"jsonrpc":"2.0","id":10,"method":"resources/read",
 "params":{"uri":"boundary://conventions"}}
// → FC/IS rules + naming conventions, straight from the project's knowledge.edn
```

**2. It scaffolds** — one call, a structured spec:

```jsonc
{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{
  "name":"scaffold-module",
  "arguments":{
    "module":"invoice",
    "entities":[{"name":"Invoice","fields":[
      {"name":"number","type":"string","required":true,"unique":true},
      {"name":"amount","type":"decimal","required":true},
      {"name":"status","type":"enum","enum-values":["draft","sent","paid"]}
    ]}],
    "interfaces":{"http":true}
  }}}
```

**3. The verify loop runs automatically** — generate → write → kondo → FC/IS →
tests — and returns a structured report:

```jsonc
{
  "status": "fail",
  "module": "invoice",
  "files":  [{"path":"src/boundary/invoice/core/invoice.clj","action":"created"}, ...],
  "issues": [
    {"step":"fc-is","severity":"error","file":"src/boundary/invoice/core/invoice.clj",
     "line":7,"code":"BND-806","message":"core/ must not import shell"}
  ],
  "counts": {"errors":1,"warnings":0},
  "steps":  ["kondo","fc-is","tests"]
}
```

**4. The agent reads the report and fixes it** — it sees `BND-806` at line 7,
removes the offending `:require`, re-runs `scaffold-module` (or edits + re-lints).
Now: `"status":"pass"`. No human approved anything; the framework's rule did the
reviewing.

**5. (Local dev only) it verifies end-to-end** — Tier 2:

```jsonc
{"jsonrpc":"2.0","id":12,"method":"tools/call",
 "params":{"name":"run-tests","arguments":{"module":"invoice"}}}
// → {"status":"ok","module":"invoice","passed":7,"failed":0}
```

Try that same `run-tests` call with `BND_ENV=prod` and it's denied at dispatch
with a `BND-803` guardrail _before anything runs_ — the enclosure holding.

### A preview/dry-run safety valve

`scaffold-module` accepts `"preview": true` — it returns the file _plan_ without
writing or verifying, so the agent (or you) can inspect what _would_ happen first.

---

## 3. How it works (architecture)

For contributors extending the server. The design is **Functional Core /
Imperative Shell** — the same discipline wagoe-mcp enforces on its users.

### Layout

```
src/boundary/mcp/
├── core/              # PURE — no JSON, no I/O. All decisions live here.
│   ├── protocol.clj   #   JSON-RPC 2.0 + MCP message builders, error codes, version negotiation
│   ├── registry.clj   #   tool/resource registry as data
│   ├── security.clj   #   capability/context gating: tiers, modes, `authorize` (the policy)
│   ├── guardrail.clj  #   guardrail error-payload assembly + override logic
│   ├── resources.clj  #   reflective-resource catalog + pure producers
│   ├── tools.clj      #   tool catalog + inputSchemas (all three tiers)
│   ├── execute.clj    #   Tier 2 policy: read-only SQL classifier, row-limit clamp, migration dirs
│   ├── verify.clj     #   verify-loop report builder
│   └── handlers.clj   #   pure dispatch: initialize, ping, tools/list, resources/list
├── ports.clj          # Transport + AuditLog + SystemSource protocols
└── shell/             # I/O — JSON, stdin/stdout, concrete adapters.
    ├── stdio.clj      #   newline-delimited JSON-RPC transport (logs to stderr)
    ├── codec.clj      #   cheshire JSON ↔ data (kept out of core)
    ├── context.clj    #   read env → security context
    ├── audit.clj      #   AuditLog sinks (stderr JSON + in-memory)
    ├── guardrail.clj  #   guardrail payloads from the devtools BND catalog (I/O lookup)
    ├── system_source.clj  # SystemSource adapter (in-process file reflection now; nREPL later)
    ├── tools.clj      #   the tool executors (all three tiers)
    ├── verify.clj     #   verify-loop steps: kondo + FC/IS + tests over written files
    ├── test_runner.clj    # default test-runner: shell out to the project's Kaocha
    ├── evaluator.clj  #   default `eval` evaluator: in-process load-string
    ├── migrator.clj   #   default migrator: shell out to :migrate
    ├── dispatch.clj   #   shell dispatch: gate (authorize) + audit + encode
    └── server.clj     #   -main: composition root — resolves context, seeds registry, boots serve loop
```

**Core is pure.** It contains every _decision_ — what's authorized, what a
guardrail payload says, whether SQL is read-only, what the verify report shows —
as functions of data, with no I/O. The shell owns cheshire and stdin/stdout, and
holds the concrete adapters. This is why the security policy and the SQL
classifier are exhaustively unit-testable without a running server.

### The request path

```
stdin → stdio (decode) → dispatch ──► security/authorize (pure) ──► deny? → guardrail payload + audit
                                              │ allow
                                              ▼
                                         tool executor (shell) ──► audit ──► encode → stdout
```

Every `tools/call` and `resources/read` passes through `shell/dispatch`, which:

1. Calls **`core/security/authorize`** with the context + tool's capability — a
   pure decision returning `{:allow? :violation :reason ...}`.
2. On **deny**, builds the structured guardrail payload (`shell/guardrail`, which
   looks the BND text up in the shared `devtools` catalog — the single source of
   truth) and does nothing else.
3. On **allow**, runs the executor and **audits** the outcome.

Tier 2 calls are **audited twice**: the generic `:tool-call` event plus an
`:execute` event carrying the payload (the code, the SQL, the migration
direction), so the trail names exactly what ran.

### Composition root

`shell/server.clj`'s `-main` wires the concrete adapters the way an Integrant
config would — audit sink, system-source, scaffolder service, test-runner,
evaluator, migrator. Anything not yet wired (the AI provider, the read-only
`:db-query` datasource) is passed as `nil`, and the corresponding tool returns an
honest `{:status :unavailable}` rather than a silent no-op. This is the seam where
you'd later swap the in-process evaluator for an **nREPL-bridge** that targets the
project's live REPL.

### Reflective resources

Resources answer "what is this project, really?" by reflecting the **running
project** rather than hardcoding — the cure for doc drift. Producers in
`core/resources` are pure functions of a project _snapshot_; a `SystemSource` port
supplies the snapshot.

| URI | Source | Status |
|-----|--------|--------|
| `boundary://conventions` | `resources/agents/knowledge.edn` (FC/IS + naming) | concrete |
| `boundary://module-graph` | `libs/*/deps.edn` + `ports.clj` presence | concrete |
| `boundary://kondo-rules` | `.clj-kondo/config.edn` | concrete |
| `boundary://schema-registry` | live Malli registry | `:unavailable` until nREPL bridge |
| `boundary://routes` | live reitit router | `:unavailable` until nREPL bridge |
| `boundary://workflows` | workflow registry | `:unavailable` until nREPL bridge |
| `boundary://lib/{name}` | installed lib API surface | `:unavailable` until nREPL bridge |

The "concrete" ones work today by reflecting files in the working directory. The
live-system ones honestly return `{:status :unavailable}` until the nREPL bridge
lands — never a silent empty answer.

### Adding a tool or resource

1. Add its definition (name, capability tier, inputSchema) to the catalog in
   `core/tools.clj` or `core/resources.clj`.
2. Add the executor in `shell/tools.clj` (or a resource producer in
   `core/resources.clj` + the snapshot wiring).
3. **Gate it.** It's authorized automatically by `shell/dispatch` via its declared
   capability — so declare the right tier. Tier 2 work must also audit its
   payload.
4. The stdio transport stays unchanged. A new transport (HTTP/SSE) just implements
   `boundary.mcp.ports/Transport`; the serve loop is transport-agnostic.

See [`AGENTS.md`](./AGENTS.md) for the full reference: every tool's exact inputs
and outputs, every BND guardrail code, the ADRs (ADR-031 gating, ADR-032
guardrails, ADR-033 reflective resources, ADR-034 verify loop), and file:line
pointers.

---

## Testing

```bash
clojure -M:test                              # kaocha unit tests
clojure -M:clj-kondo --lint src test         # lint
```

The security policy, SQL classifier, guardrail payloads, and verify-report builder
are all pure — they're tested directly, no server required.
