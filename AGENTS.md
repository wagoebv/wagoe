# Wagoe Framework — Agent Instructions

## Working rules

- Don't assume or hide confusion; surface tradeoffs.
- Write the minimum code that solves the problem. Touch only what you must and clean up your own changes.
- Define success criteria and verify them before declaring work complete.
- Never commit or push without explicit user approval. Show the intended changes and wait for "commit" or "push".
- Before changing a library, read its `libs/<library>/AGENTS.md` if present.

## Read before the relevant task

These files are task-specific instructions. Read the relevant guide before acting;
paths in commands and inline code are relative to the repository root.

| Task | Required reading |
|---|---|
| Run or change tests | [Testing reference](dev-docs/agents/testing.md) and [test commands](dev-docs/agents/development.md#essential-commands) |
| Start/debug the app, scaffold a feature, change configuration or migrations | [Development reference](dev-docs/agents/development.md) |
| Diagnose a failure; change routes, schemas, SQL pagination, Java interop or OpenAPI | [Pitfalls](dev-docs/agents/pitfalls.md) |
| Change a public protocol or need convention examples | [Conventions](dev-docs/agents/conventions.md) |
| Change a quality gate or use an exception to one | [Quality gates](dev-docs/agents/quality-gates.md) |
| Add/change user-facing text | [i18n workflow](dev-docs/agents/i18n.md) and [i18n module](libs/i18n/AGENTS.md) |
| Change agent docs, add a library/CI job, or find a module guide | [Maintenance](dev-docs/agents/maintenance.md) |
| Change deployment/scaling | [Scaling](docs/modules/architecture/pages/scaling.adoc) and [deployment](deploy/README.md) |

## Architecture and naming

Modules normally live in `libs/<library>/src/wagoe/<library>/` with `core/`,
`shell/`, `ports.clj` and `schema.clj` (Malli). Core contains pure business logic;
shell handles I/O, validation and side effects; ports define protocols.

<!-- gen:naming -->
| Location | Convention | Example |
|----------|-----------|---------|
| All Clojure code | kebab | `:password-hash, :created-at` |
| Database boundary only | snake | `password_hash, created_at` |
| API/JSON boundary only | camel | `passwordHash, createdAt` |
<!-- /gen:naming -->

Convert keys only at persistence/API boundaries. Use
`wagoe.core.utils.case-conversion` for database snake/kebab conversion.

<!-- gen:fc-is -->
| Direction | Allowed? |
|-----------|----------|
| Shell → Core | ✅ allowed |
| Core → Ports | ✅ allowed |
| Shell → Ports | ✅ allowed |
| Core → Shell | ❌ NEVER — violates FC/IS |
| Core → IO | ❌ NEVER — even logging |

Every module MUST define `ports.clj`.

- core/ may require only pure libraries (clojure.string/set/walk/edn, malli, hiccup, cheshire, buddy.core) and its own core/schema/ports — an allowlist, so anything else is refused by default
- core/ and ports know no SQL: neither requires honey.sql, next.jdbc or wagoe.platform.database, and no port signature or docstring types a value as a HoneySQL map or a next.jdbc datasource or transaction — an opaque store handle (tx, conn) may pass through untyped (exceptions: :allow-sql in .wagoe/check-fcis.edn, each with a :why)
- core/ must not throw — return typed error values ({:error {:type ... :message ...}}); the shell translates them into HTTP responses (escape hatch: ^:wagoe/allow-throw ns metadata or .wagoe/check-fcis.edn)
- core/ reads no store or clock and writes no store: a write to the app's own store, append-only too, stays in the shell, and core returns the entry as data. No logging yet: core has no observation channel. bb check:fcis warns on print, tap> and clock or random reads (escape hatch: ^:wagoe/allow-print or ^:wagoe/allow-clock ns metadata, or :allow-print / :allow-clock in .wagoe/check-fcis.edn, each with a :why) — see https://wagoe.org/docs/architecture/fc-is.html
- core/ must not hold mutable state (defonce/atom/swap!/reset!) — definition registries and process state live in the shell (wagoe.<lib>.shell.registry)
- cross-module calls go through service ports
- web/HTTP layers never require *.shell.persistence directly
- a module picks its store (SQL, Datomic, a REST API) in shell/persistence.clj behind a domain-shaped port; Wagoe's own libraries stay on SQL — see https://wagoe.org/docs/architecture/other-stores.html (ADR-042)

```clojure
(ns myapp.core.product)
(defn calculate-total [items] (reduce + (map :price items)))
```
<!-- /gen:fc-is -->

Errors thrown by the shell carry `:type` in `ex-data`; failed results use
`{:success? false :error {:type <keyword> ...}}`. Core returns typed errors.

Layout exceptions: cache is shell + ports; platform and observability split
ports across files; wagoe-mcp uses `wagoe.mcp.*`; shared-ui uses
`wagoe.shared.ui.*` and has no ports/schema.

## Scaffold first

Use the scaffolder for new modules and major features. Do not manually create
module directories, schema files or tests for scaffolded functions.

```bash
bb scaffold ai "invoice module with number, date, amount, status" --yes
bb scaffold integrate invoice
bb scaffold entity --module-name invoice --entity InvoiceLineItem --belongs-to invoice --field qty:int
bb scaffold field --module-name invoice --entity Invoice --name amount --type decimal
bb scaffold endpoint --module-name invoice --path /invoices --method post --handler-name create-invoice
```

Integrate immediately after generating a module. Run `bb check:fcis`,
`bb check:deps` and the module tests afterwards. Edit business logic inside
existing core functions, shell service implementations, generated handler bodies
and test assertions manually. For a scaffolder limitation, follow the manual
workflow in the development reference and run `bb check:fcis`.

## Stable ports

Use `wagoe list modules` to check stability. In stable 1.x libraries:

- Add a new protocol for a new capability; never add methods or arities to an existing protocol.
- Check capabilities once at component startup with `satisfies?`. Keep the old path when safe; otherwise refuse boot with a `WGE-` configuration error naming the missing capability, and register it in `libs/devtools/resources/wagoe/devtools/error_catalog.edn`.
- Never remove, rename or move protocols, methods or arities. Deprecate with `^:deprecated` and `### Deprecated`; keep until 2.0.
- Ask before introducing an urgent security break. Name each affected method in backticks under `[Unreleased]` `### Breaking`, and record an exception on the stability page.

Incubating ports may change in a minor release; document under `### Changed`.
`bb check:ports-diff` compares with the last final release tag.

## Verification

```bash
bb test:services up       # Redis + MySQL; PostgreSQL suites use embedded PG
bb test:all               # main, tools, agents, wagoe-cli and wagoe-mcp
bb check                 # registered quality gates
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test
```

Run `bb test:all` before committing or claiming the codebase is green.
`clojure -M:test` alone misses standalone surfaces. `bb test:all --list` explains
coverage; e2e is separate (`bb e2e`, requires Chromium) and blocks PR merges in CI.
Adapter sweeps fail if backing services are missing.

For targeted runs use `clojure -M:test:test/all :<suite>`; on Apple Silicon add
`:test/pg-mac` for embedded PostgreSQL. The development reference lists narrower
aliases and metadata/namespace filters. Do not replace assertions with placeholders.

After Clojure edits, use `clj-paren-repair <file>` for delimiter repair when needed.
Use `clj-nrepl-eval --discover-ports` to find running REPLs. Installation and
REPL restart instructions are in the development reference.

## Details that must not be missed

- Use `[:t :module/key]` markers for UI strings; update both English and Dutch catalogues and run `bb i18n:scan` and `bb i18n:missing`.
- Module route prefixes are added by the framework; don't repeat them in module paths.
- Declare OpenAPI parameters explicitly; handler destructuring does not document them.
- SQL defaults must handle explicit `nil` values, not just absent keys.
- New framework migration ids end in an odd second. Never rename a released migration id.
- After changing a `defrecord`, reload its namespace and restart the system; old instances retain the old methods.
- Development uses PostgreSQL by default. For an app without external services use `WAG_ENV=test` with a JWT secret of at least 32 characters. Read the logged HTTP port instead of assuming 3000.
- Remove temporary debug logging before committing; keep logging in the shell.

## Write short

Comments, docstrings, commits, PRs, reviews and issues must be short and human.
Explain non-obvious reasons; avoid filler, diff narration, tool signatures,
"Generated with" footers and `Co-Authored-By` trailers. Reference the ticket.
Comments/docstrings usually need only one or two sentences.

Changelog entries have a hard limit of two lines: what changed and what the user
must do. Trim existing overlong entries when encountered; leave unrelated long
comments alone. Commit bodies are only needed when the reason is not evident.
PRs explain what changed and why; include verification output only when useful.
Issues state the problem, reproduction and completion criteria.

## Maintaining these instructions

Keep commands in one reference location and library details in library guides.
Do not expand this root file with troubleshooting or implementation histories.

Naming and FC/IS blocks here, pitfalls in `dev-docs/agents/pitfalls.md`, the module
table in `dev-docs/agents/maintenance.md`, and downstream template blocks are
generated from `libs/tools/resources/wagoe/agents/knowledge.edn` and
`modules-catalogue.edn`. Edit the sources, then run:

```bash
bb agents:gen
bb check:agents
bb check-links
```

Regenerate before publishing wagoe-cli. The maintenance guide describes source
ownership, adding libraries and the separate per-module AI documentation tool.
Install local pre-commit hooks once per clone with `bb install-hooks`.
