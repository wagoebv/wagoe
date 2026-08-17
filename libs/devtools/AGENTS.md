# libs/devtools — AGENTS.md

Development-only tools: REPL helpers, error pipeline, guidance engine, dev dashboard. Zero production overhead.

## Error Pipeline (Phase 3)

Layered pipeline: `classify -> enrich -> format -> output`

All pure functions in `core/`, side effects in `shell/`.

### Core Modules

| File | Purpose |
|------|---------|
| `core/error_classifier.clj` | Exception -> BND-xxx code (5 strategies: ex-data code, ex-data pattern, exception type, message regex, unclassified) |
| `core/error_enricher.clj` | Adds filtered stacktrace, suggestions, fix descriptor, URLs. Self-protected: sub-call failures omit the field |
| `core/error_formatter.clj` | Rich formatted output with BND code header, stack trace, fix hint. Also `format-unclassified-error` fallback |
| `core/stacktrace.clj` | Reorder stack traces: user code first, framework/JVM collapsed. Namespace prefix classification |
| `core/auto_fix.clj` | Pure fix descriptor registry. Maps BND codes to `{:fix-id :action :safe? :label}` |
| `core/error_codes.clj` | BND error catalog: BND-1xx (config), BND-2xx (validation), BND-3xx (persistence), BND-4xx (auth), BND-5xx (interceptor), BND-6xx (FC/IS) |

### Shell Modules

| File | Purpose |
|------|---------|
| `shell/repl_error_handler.clj` | `last-exception*` atom + `handle-repl-error!` — runs full pipeline |
| `shell/auto_fix.clj` | Executes fix descriptors: migrations, env vars, JWT, module wiring. Multimethod dispatch on `:action` |
| `shell/http_error_middleware.clj` | `wrap-dev-error-enrichment` — catches exceptions, attaches `:wagoe/dev-info` to ex-data, re-throws |
| `shell/fcis_checker.clj` | Post-reset namespace scan for BND-601 (core imports shell). Runs after `(go)` and `(reset)` |

### REPL Commands

- `(fix!)` — Auto-fix last error. Safe fixes auto-apply, risky fixes always confirm
- `(fix! ex)` — Fix a specific exception

### Safety Model

| Safe? | Behavior at `:full` | Behavior at `:minimal` |
|-------|--------------------|-----------------------|
| `true` | Apply with message | Apply silently |
| `false` | Always confirm | Always confirm |

The safety gate is never overridden by guidance level.

## Guidance Engine (Phase 1)

- `core/guidance.clj` — Startup dashboard, post-scaffold guidance, contextual tips, command palette
- Levels: `:full` (default), `:minimal`, `:off`

## Introspection (Phase 2)

- `core/introspection.clj` — Route table, config tree (secrets redacted), module summary
- `core/schema_tools.clj` — Schema tree, diff, example generation
- `core/state_analyzer.clj` — Module/migration/test state analysis
- `core/documentation.clj` — In-REPL help topics
- `shell/repl.clj` — Route extraction, request simulation, data queries, test/lint runners

## Dev Dashboard (Phase 4)

Local web UI at `localhost:9999` providing x-ray vision into the running system.

### Pages

- `/dashboard` — System Overview: components, routes, modules, environment
- `/dashboard/routes` — Route Explorer: filterable route table with interceptor chain
- `/dashboard/requests` — Request Inspector: live request stream (HTMX polling 2s)
- `/dashboard/schemas` — Schema Browser: Malli schema tree with example generation
- `/dashboard/db` — Database Explorer: migrations, pool stats, query runner
- `/dashboard/errors` — Error Dashboard: BND-coded errors with fix suggestions

### Architecture

- Integrant component (`:wagoe/dashboard`) starts Jetty on port 9999
- Server-rendered Hiccup + HTMX polling for live updates
- Request capture middleware wraps main HTTP handler (port 3000)
- Dark theme CSS in `resources/dashboard/assets/dashboard.css`
- All data access through existing introspection functions

Editing a config value from the dashboard rebuilds the system, so `:wagoe/dashboard` also takes `:ig-config-fn` — a zero-argument function returning your Integrant config. Without it the config editor reports what is missing and the rest of the dashboard works as normal.

### Key Files

- `shell/dashboard/server.clj` — Integrant component, Reitit router
- `shell/dashboard/middleware.clj` — Request capture middleware
- `shell/dashboard/layout.clj` — Sidebar, top bar, page wrapper
- `shell/dashboard/components.clj` — Reusable UI components
- `shell/dashboard/pages/*.clj` — Individual page renders

## Advanced REPL (Phase 5)

Request/response recording, route testing, and rapid prototyping from the REPL.

### Recording

Capture and replay HTTP request/response pairs for debugging and regression testing.

- `core/recording.clj` — Pure session management: `create-session`, `add-entry`, `stop-session`, `diff-entries`, `format-entry-table`, `serialize-session`/`deserialize-session`
- `shell/recording.clj` — File-based persistence: save/load recording sessions to disk

### Route Testing

Add, remove, and inspect routes on a running system without restart.

- `core/router.clj` — Pure route manipulation: `add-route`, `remove-route`, `inject-tap-interceptor`, `remove-tap-interceptor`
- `shell/router.clj` — Route simulation against the live Reitit router

### Rapid Prototyping

Generate scaffold specs from Malli schemas for quick module prototyping.

- `core/prototype.clj` — Schema-to-scaffold conversion: `malli->sql-type`, `build-scaffold-context`, `endpoints-to-generators`, `build-migration-spec`
- `shell/prototype.clj` — End-to-end prototype execution

### Key Files

| File | Purpose |
|------|---------|
| `core/recording.clj` | Pure session data structure, entry diffing, table formatting |
| `core/router.clj` | Route addition/removal, tap interceptor injection |
| `core/prototype.clj` | Malli schema → scaffold spec conversion, migration spec generation |
| `shell/recording.clj` | File I/O for recording sessions |
| `shell/router.clj` | Live router manipulation |
| `shell/prototype.clj` | Prototype execution with side effects |

## Phase 6: Jobs, Config, Security + AI REPL

Three new dashboard pages and three new AI-powered REPL commands. The nav sidebar now has **10 items** total: Overview, Routes, Requests, Schemas, Database, Errors, Jobs, Config, Security, Docs.

### Dashboard Pages

#### Jobs & Queues — `/dashboard/jobs`

Monitors background job processing in real time.

| Section | Contents |
|---------|----------|
| Stat row | Active/Pending, Processed, Succeeded, Failed |
| Queues table | Per-queue: size, processed, failed, avg duration |
| Failed Jobs list | Job type, error message, retry count, queue — with **Retry** button (HTMX POST) |

- HTMX polling every **5 seconds** keeps the failed-jobs container live
- Page degrades gracefully when no `:wagoe/jobs` component is configured
- Key files: `shell/dashboard/pages/jobs.clj`

#### Config Editor — `/dashboard/config`

Editable view of the running system config with secret redaction.

- Config tree rendered via `core/config_editor.clj` (`redact-secrets`, `format-config-tree`)
- Each top-level key gets its own card with an editable `<textarea>`
- **Preview Changes** button — HTMX POST to `/dashboard/fragments/config-preview` — shows a diff and lists affected components
- **Apply** button — HTMX POST to `/dashboard/fragments/config-apply` — restarts affected components; always confirms with a browser dialog
- Key files: `shell/dashboard/pages/config.clj`, `core/config_editor.clj`

#### Security Status — `/dashboard/security`

Point-in-time security posture of the running application.

| Stat card | What it shows |
|-----------|---------------|
| Password Strength | `:strong` / `:moderate` / `:weak` derived from policy analysis |
| Auth Methods | Count of active methods (JWT, session, MFA) |
| MFA | Enabled / Disabled |
| Active Sessions | Live session count |
| CSRF | Active / Inactive |
| Rate Limiting | Active / Inactive |

- Password Policy card: per-criterion check marks (min length, uppercase, lowercase, numbers, special chars)
- Authentication & Access card: lists auth methods, CSP status, role config, lockout thresholds
- Recent Auth Failures table: last 10 failures with timestamp, type, detail
- Data sourced from `core/security_analyzer.clj` (`build-security-summary`)
- Key files: `shell/dashboard/pages/security.clj`, `core/security_analyzer.clj`

### AI REPL Commands

Three new commands exposed via the `ai/` alias (namespace `wagoe.ai.shell.repl`):

```clojure
(ai/review "path/to/file.clj")
;; AI code review — reads source, sends to configured AI provider,
;; prints annotated feedback with provider/model/token footer.
;; Accepts a file path or an inline source string.

(ai/test-ideas "path/to/file.clj")
;; Suggest missing test cases — analyzes source + existing test file
;; (auto-resolved from namespace), prints a list of uncovered scenarios.

(ai/refactor-fcis 'wagoe.product.core.validation)
;; FC/IS refactoring guide — locates the source file from the namespace symbol,
;; identifies violations, and prints a step-by-step migration plan.
;; Also surfaced by (fix!) when a BND-601 violation is detected.
```

All three degrade gracefully when no AI service is configured.

### `(new-feature!)` Workflow Automation

Full end-to-end feature scaffolding from a single REPL call (defined in `dev/repl/user.clj`):

```clojure
(new-feature! "invoicing"
  "Invoice module with customer, line-items, PDF export")
```

Steps executed interactively:

1. **AI spec generation** — calls `ai-svc/scaffold-from-description`; falls back to basic scaffold if no AI service or parse failure
2. **Confirm prompt** — prints proposed spec, asks `[y/N]` before proceeding
3. **Scaffold** — `(scaffold! module-name {:fields fields})`
4. **Integrate** — runs `bb scaffold integrate <module>` via `clojure.java.shell/sh`
5. **Test** — runs `(test-module (keyword module-name))`

The workflow converts AI field specs (vector of `{:name :type}` maps) into the HoneySQL-compatible map format expected by the scaffolder.
