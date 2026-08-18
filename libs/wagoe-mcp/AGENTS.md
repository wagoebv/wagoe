# wagoe-mcp — MCP Server for Wagoe

Model Context Protocol server exposing Wagoe's framework knowledge to editor
agents (Claude Code, Cursor). Standalone library — **deliberately not wired into
the root `deps.edn` paths** so applications never pull an MCP server
transitively. Depends on `wagoe/ai` for the core context/parsing helpers that
later tools reuse.

> Status: **active** — stdio transport + JSON-RPC handshake, Tier 0 (read),
> Tier 1 (generate + closed verify loop), and Tier 2 (execute) tools all
> implemented. Some live-introspection resources and the read-only DB datasource
> are `:unavailable` pending an nREPL bridge.

## Run

```bash
cd libs/wagoe-mcp
clojure -M:mcp        # stdio MCP server, from the monorepo root (isolated classpath)
clojure -M:run        # same server, from libs/wagoe-mcp (cwd = the lib, so the
                      # reflective resources report :unavailable)
clojure -M:test       # kaocha unit tests
```

Smoke test the handshake:

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  '{"jsonrpc":"2.0","id":3,"method":"resources/list"}' \
  | clojure -M:run 2>/dev/null
```

## Layout (FC/IS)

```
src/wagoe/mcp/
├── core/
│   ├── protocol.clj   # pure JSON-RPC 2.0 + MCP message builders, error codes,
│   │                  #   supported spec versions + negotiation
│   ├── registry.clj   # tool/resource registry as data (+ list/register fns)
│   ├── security.clj   # pure capability/context gating (tiers, modes, authorize)
│   ├── guardrail.clj  # pure guardrail error payloads (BOU-98)
│   ├── resources.clj  # pure reflective-resource catalog + producers (BOU-99)
│   ├── tools.clj      # pure tool catalog + inputSchemas (Tier 0 BOU-100, Tier 1 BOU-101, Tier 2 BOU-102)
│   ├── execute.clj    # pure Tier 2 policy: read-only SQL classify, row-limit clamp, migration dirs (BOU-102)
│   ├── verify.clj     # pure verify-loop report builder (BOU-101)
│   └── handlers.clj   # pure dispatch: initialize, ping, tools/list, resources/list
├── ports.clj          # Transport + AuditLog + SystemSource protocols
└── shell/
    ├── codec.clj      # cheshire JSON <-> data (kept out of core)
    ├── context.clj    # read env -> security context (I/O)
    ├── audit.clj      # AuditLog sinks: logging (stderr JSON) + in-memory
    ├── guardrail.clj  # guardrail payloads from the devtools BND catalog (I/O)
    ├── system_source.clj # SystemSource adapters: in-process (now), nREPL (later)
    ├── tools.clj      # tool executors (Tier 0: kondo/Malli/ai/reflection; Tier 1: scaffold + verify; Tier 2: execute)
    ├── verify.clj     # verify-loop steps: kondo + FC/IS + tests over written files (BOU-101)
    ├── test_runner.clj # default injected test-runner: shell out to the project's Kaocha (BOU-101)
    ├── evaluator.clj  # default injected evaluator for the `eval` tool: in-process load-string (BOU-102)
    ├── migrator.clj   # default injected migrator for `run-migration`: shell out to :migrate (BOU-102)
    ├── dispatch.clj   # shell dispatch: resources/read + tools/call (gate+encode)
    ├── stdio.clj      # newline-delimited stdin/stdout loop; logs to stderr
    └── server.clj     # -main: resolve context, audit start, boot serve loop
```

**Core is pure** — no JSON, no I/O. The shell owns cheshire and stdin/stdout.
stdout carries protocol messages only; all logging goes to **stderr** so it
never corrupts the message stream.

## stdout belongs to the protocol

stdio MCP puts JSON-RPC on stdout, so anything else written there breaks the
client. This library ships a `logback.xml` targeting stderr — and that is not
enough, because the server does not control the classpath it runs on.

Measured from the monorepo root with the previously documented `-Sdeps`
command: **88 lines of logback output before the first JSON-RPC response**.
`-Sdeps` merges with the repository's `deps.edn`, which puts `resources/` on
the classpath, and that `logback.xml`'s console appender targets stdout. Two
`logback.xml` files also warn, and a warning makes logback dump its whole
status log.

Two defences, because one is not sufficient:

1. **`clojure -M:mcp`** uses `:replace-paths []` / `:replace-deps`, so
   `resources/` never joins the classpath. This is the one that actually fixes
   the monorepo case — logback initialises during classpath startup, *before*
   `-main`, so no code in the server can prevent it.
2. **`server/claim-stdout!`** takes stdout and points `System/out` and `*out*`
   at stderr, so a host project whose own logging targets stdout cannot corrupt
   the stream at runtime.

`wagoe.mcp.shell.server` therefore requires **nothing**. Loading any namespace
that logs initialises logback; the composition root lives in
`wagoe.mcp.shell.boot` and is loaded from inside `-main`, after the claim. A
test asserts the entry namespace has no `:require` — adding one silently brings
the banner back.

## Protocol notes

- JSON-RPC field names are the wire contract: `protocol.clj` builds maps with the
  exact MCP keys (camelCase where the spec requires, e.g. `:protocolVersion`).
  The usual kebab-case-internal rule does not apply to these protocol fields.
- Notifications (no `:id`) get no reply; unknown **requests** return `-32601`
  (method not found); malformed JSON returns `-32700` (parse error) and the loop
  continues.
- Adding a transport: implement `wagoe.mcp.ports/Transport`; the `serve` loop
  is transport-agnostic.

## Security — capability/context gating (ADR-031)

Threat model: an agent may be pointed at a live REPL with a production DB.
Every tool declares a **capability tier** — `:read` (Tier 0), `:generate`
(Tier 1, reversible writes), `:execute` (Tier 2, RCE surface). The active
**context** grants a ceiling derived from the environment:

| Mode | Max tier | Set by |
|------|----------|--------|
| `:full`       | `:execute`  | local dev (`WAG_ENV=dev`/`test`) |
| `:no-execute` | `:generate` | `WAG_ENV=prod` |
| `:read-only`  | `:read`     | CI (`CI` truthy), or no env signal (fail-closed) |
| `:disabled`   | none        | `MCP_CAPABILITY_MODE=disabled` |

Resolution precedence: **`MCP_CAPABILITY_MODE` override > `CI` > `WAG_ENV` >
fail-closed `:read-only`**. The decision is pure (`core/security/resolve-context`,
`authorize`); the shell reads env (`shell/context`) and audits (`shell/audit`,
stderr JSON — never stdout).

**Tool authors (BOU-99/100/101/102):** before any work, call
`security/authorize` with the context + `{:name :capability}`; on deny, build
the guardrail error and do nothing (see below); record the decision via the
audit log.

## Guardrail error payload (ADR-032)

"Guardrail, not straitjacket." Every enforcing tool returns one structured
payload — the **rule** that fired (a BND code), the **principle** behind it, a
suggested **fix**, and (when overridable) the audited bypass:

```clojure
{:code "BND-803" :rule "Capability Tier Exceeded"
 :principle "...exceeds the ceiling..." :fix "Run in local dev, ..."
 :overridable? false :details {:tool "eval" :capability :execute :mode :no-execute}}
```

- BND text comes from the shared `devtools` catalog (`BND-8xx` = MCP guardrails),
  the single source of truth. `core/guardrail` is pure; `shell/guardrail` does
  the catalog lookup (I/O) and `wagoe/devtools` is a **shell-only** dep.
- Map a `security/authorize` denial → payload: `shell/guardrail/payload-for-denial`
  (or `error-for-denial` for the JSON-RPC error, app code `:forbidden` `-32001`).
- **Hard vs soft:** security/capability denials (BND-801..805) are *not*
  per-call overridable — the audited override is changing the env/context
  (`MCP_CAPABILITY_MODE`). Codegen guardrails (BND-806/807, Tier 1) *are*: the
  caller passes `{:allow true}` (`guardrail/override-requested?`) and the tool
  records a `:guardrail-override` audit event (`guardrail/override-event`)
  before proceeding.

## Reflective resources (ADR-033)

Resources reflect the **running project**, never hardcoded — the answer to
version skew. Producers (`core/resources`) are pure functions of a project
**snapshot**; a `SystemSource` port supplies it (hybrid: in-process file
reflection now; nREPL bridge later for live-system views).

| URI | Source | Where it resolves |
|-----|--------|-------------------|
| `wagoe://conventions`     | `wagoe/agents/knowledge.edn` on the classpath (FC/IS, naming, pitfalls); a project's own `resources/agents/knowledge.edn` overrides it | anywhere |
| `wagoe://module-graph`    | this repo: `libs/*/deps.edn` + `ports.clj`. A project: its `com.wagoe/*` deps and the `:wagoe/*` keys its dev config switches on | anywhere |
| `wagoe://kondo-rules`     | `.clj-kondo/config.edn` | where one exists |
| `wagoe://schema-registry` | live Malli registry | not yet — nREPL bridge |
| `wagoe://routes`          | live reitit router | not yet — nREPL bridge |
| `wagoe://workflows`       | workflow registry | not yet — nREPL bridge |
| `wagoe://lib/{name}`      | installed lib API surface | not yet — nREPL bridge |

- **`resources/list` advertises only what this project can serve.** The registry
  is seeded from `resources/available-catalog` against a snapshot, so an agent
  is not offered a schema registry that answers "not available in the current
  context" (BOU-320). A direct `resources/read` of a known-but-unfilled uri
  still returns `{:status :unavailable :note ...}` — honest, not silent-empty.
- `resources/read` is gated (`security/authorize` `:read`) + audited in
  `shell/dispatch`; denial returns the guardrail payload. Unknown uri →
  `-32602`. Content is JSON.
- The in-process adapter reflects the **current working directory** — run from a
  Wagoe project root. `build-snapshot` takes the root as an argument, which is
  what makes the reflection testable: relative paths in the JVM resolve against
  the process working directory, and no test can change that.

## Tier 0 tools (BOU-100)

Zero-mutation read/analyze tools, callable over `tools/call`. Pure catalog +
inputSchemas in `core/tools`; executors in `shell/tools`. Every tool is
capability `:read` — `tools/call` authorizes, audits, and (on a `tools/call`
failure) returns an `isError` result; capability denial returns the guardrail
error (`-32001`); unknown tool → `-32602`.

| Tool | Does | Reuses |
|------|------|--------|
| `explain-error` | summarise a stacktrace + enrich any `BND-xxx` code | `ai.core.context`, devtools `error-codes` |
| `lint` | clj-kondo structured findings for paths | `clj-kondo.core` |
| `validate-schema` | Malli validate + humanized errors | `malli` |
| `describe-module` | module deps / ports / libs from the live snapshot | BOU-99 `SystemSource` |
| `sql-preview` | NL → HoneySQL (generated, never executed) | `ai` IAIProvider, `ai.core.parsing` |

`sql-preview` needs an AI provider (`deps :ai-provider`); when none is wired it
returns `{:status :unavailable}`. The provider is config-driven (future).

## Tier 1 generate tools + closed verify loop (BOU-101, ADR-034)

Tier 1 tools **write to disk** (capability `:generate`, reversible via git) and
are the moat: every generate runs the **closed verify loop** so the agent gets
structured failures and self-corrects without a human.

```
generate (scaffolder) → write → kondo → FC/IS → run affected tests → structured report
```

| Tool | Does | Reuses |
|------|------|--------|
| `scaffold-module` | scaffold a full FC/IS module from a structured spec | scaffolder `generate-module` |
| `add-field` | migration + schema-update instructions for a new field | scaffolder `add-field` |
| `gen-tests` | AI-generate a test ns for a source file (needs provider) | `wagoe.ai` `generate-tests` |
| `gen-migration` | SQL migration for an entity's table | scaffolder migration generator |

- **In-process codegen.** `wagoe/scaffolder` + `wagoe/tools` are deps
  (dev tooling, like the server). The scaffolder writes app-layout files
  (`src/wagoe/<module>/…`); the server runs **inside the target project**.
- **Verify loop** (`shell/verify` → pure `core/verify`):
  - **kondo** — in-process over the written `.clj` files.
  - **FC/IS** — `wagoe.tools.check-fcis/check-file` per written `core/` file →
    **BND-806**. (Per-file, not the monorepo's `core-source-paths`, so it works
    in any project layout.)
  - **Malli** — the scaffolder validates the request before writing; its errors
    flow through as generate `:errors`.
  - **tests** — injected `:test-runner` (a fn of the module). The default
    (`shell/test_runner`) shells out to the project's Kaocha (no stable
    programmatic API; tests need the *project's* classpath, not the server's).
    No runner → an honest `:tests :unavailable` step, never a silent pass.
- **Report** (`core/verify/build-report`): `{:status :pass|:fail|:overridden
  :issues [{:step :severity :file :line :code :message [:expected] [:actual]}]
  :counts :steps}`.
- **Hard vs soft.** kondo errors and failing tests are **hard** — never
  overridable (the code does not compile / does not pass). FC/IS (BND-806) and
  convention (BND-807) are **soft**: an audited `{:allow true}` turns a `:fail`
  whose blocking issues are *all* soft into `:overridden`, recording a
  `:guardrail-override` audit event. A mixed hard+soft run stays `:fail`.
- **Capability gate first.** `:generate` is denied in `:read-only`/CI/`:disabled`
  contexts (BND-803/804/801) at dispatch, before any codegen runs.
- `scaffold-module` accepts `preview: true` (dry-run) → returns the file `:plan`
  without writing or verifying.

## Tier 2 execute tools (BOU-102)

The **RCE surface, off by default.** Capability `:execute` — the security gate
denies it in every context except `:full` (local dev), so these refuse in prod
(`:no-execute` → BND-803) and CI (`:read-only` → BND-803) **before** any work
runs. Every call is audited twice: the generic `:tool-call` event in the
dispatch, plus an `:execute` event from the executor carrying the payload (the
code run, the SQL, the migration direction) so the trail names what executed.

| Tool | Does | Injected dep (default) |
|------|------|------------------------|
| `run-tests`     | run a module's test suite, structured pass/fail report | `:test-runner` (shell out to Kaocha — shared with the Tier 1 verify loop) |
| `eval`          | evaluate Clojure code, return value + captured stdout | `:evaluator` (`shell/evaluator` in-process `load-string`) |
| `run-migration` | apply (`up`) or report (`status`) migrations | `:migrator` (`shell/migrator` shell out to `:migrate`) |
| `query-db`      | run one read-only SQL query, row-limited | `:db-query` (**nil** — needs a read-only datasource, not yet wired → `:unavailable`) |

- **Injected, not hardcoded** (like the Tier 1 test-runner): the real work
  targets the *project*, not the server JVM, and is stubbable. A missing dep
  yields an honest `{:status :unavailable :note ...}`, never a silent no-op.
- **Pure policy in `core/execute`** (no I/O, no throwing — the shell enforces):
  - `query-db` — `sql-violation` rejects anything that isn't a single read-only
    statement (`:empty` | `:multiple-statements` | `:not-read-only`); `clamp-limit`
    bounds rows to `[1, 1000]` (default 100). This is **defense-in-depth, not the
    primary control** — the classifier is a keyword denylist, not a parser, and
    can be fooled (e.g. `SELECT writing_fn()`). **Requirement:** when `:db-query`
    is wired, the datasource MUST connect with a **read-only DB role**; the
    classifier must never be the only guard.
  - `run-migration` — `valid-direction?` allowlists `#{"up" "status"}` (no
    destructive rollback over the MCP surface); an unknown direction throws a
    `:validation-error`.
- **`eval` default is in-process** in the server JVM (only the server's
  classpath); an nREPL-bridge evaluator targeting the project's live REPL is the
  planned swap (mirrors the SystemSource nREPL plan).

## Adding tools / resources (BOU-99 / BOU-100 / BOU-101 / BOU-102)

Register definitions into the registry data and add a dispatch case (or a
`tools/call` / `resources/read` handler) in `core/handlers.clj`. Gate it per
the Security section above. The stdio transport stays unchanged.
