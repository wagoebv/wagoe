# Wagoe Framework

[![CI](https://github.com/wagoebv/wagoe/actions/workflows/ci.yml/badge.svg)](https://github.com/wagoebv/wagoe/actions/workflows/ci.yml)
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-core.svg)](https://clojars.org/com.wagoe/wagoe-core)
[![cljdoc](https://cljdoc.org/badge/com.wagoe/wagoe-core)](https://cljdoc.org/d/com.wagoe/wagoe-core)
[![License: EPL-2.0](https://img.shields.io/badge/License-EPL_2.0-blue.svg)](https://www.eclipse.org/legal/epl-2.0/)

**Wagoe** is a batteries-included Clojure web framework that enforces the **Functional Core / Imperative Shell (FC/IS)** pattern: pure business logic in `core/`, side effects in `shell/`, and clean interfaces through `ports.clj` protocols.

---

## Why Wagoe?

**For developers:** 30 independently-publishable libraries on Clojars — use just `wagoe-core` for validation utilities, or go full-stack with JWT + MFA auth, auto-generated CRUD UIs, background jobs, multi-tenancy, real-time WebSockets, and more. Every library follows the same FC/IS structure, making any Wagoe codebase instantly familiar.

**Ship faster:** The scaffolder generates fully structured modules (entity + routes + tests) in seconds. The admin UI auto-generates CRUD interfaces from your schema — no manual forms. Built-in observability, RFC 5988 pagination, and declarative interceptors mean you write business logic, not plumbing. AI tooling (`bb scaffold ai`, `bb ai gen-tests`, `bb ai sql`) handles the repetitive parts.

**Ship with confidence:** Reference deployment configs (systemd, nginx, Fly.io, Render), an OWASP-aligned security checklist, scaling guides, health check endpoints, and zero-downtime migration patterns.

**Zero lock-in:** Each library is a standard `deps.edn` dependency. Swap what doesn't fit.

---

## Install

Install the Wagoe CLI — it handles all prerequisites (JVM, Clojure CLI, Babashka, bbin) automatically:

```bash
curl -fsSL https://get.wagoe.org | bash
```

Fallback if `get.wagoe.org` is unavailable:

```bash
curl -fsSL https://raw.githubusercontent.com/wagoebv/wagoe/main/scripts/install.sh | bash
```

Supports macOS, Debian/Ubuntu, Arch Linux, and WSL2.

## Quick Start

```bash
# 1. Create a new project
wagoe new my-app
cd my-app

# 2. Add optional modules (e.g. payments, cache, search)
wagoe add payments
wagoe list modules    # see all 19 optional modules

# 3. Run database migrations
clojure -M:migrate up

# 4. Start the REPL (headless nREPL server on port 7888)
export JWT_SECRET="change-me-dev-secret-min-32-chars"
clojure -M:repl-clj
```

Connect your editor (or the Wagoe MCP server) to the nREPL port, then eval:

```clojure
(go)    ; start the system — http://localhost:3000
(reset) ; reload changed namespaces and restart
(halt)  ; stop the system
```

You get: SQLite database (zero-config, and your data survives a restart), HTTP server on port 3000, a complete Integrant system, and REPL-driven development.

---

## AI-Native Development (Claude Code & Agentic CLIs)

Projects created with `wagoe new` are agent-ready out of the box: they
include a `CLAUDE.md`, an `AGENTS.md`, and a Claude Code skill at
`.claude/skills/wagoe/SKILL.md` that teaches the agent to use Wagoe's
scaffolder and AI tooling instead of hand-writing boilerplate. Open Claude
Code in a fresh project and ask:

> add a product module with name, price, and stock

The agent will reach for `bb scaffold` and generate a complete FC/IS module
with tests and migrations.

For **existing** projects (or to get updates without regenerating), install
the plugin from this repo's marketplace:

```
/plugin marketplace add wagoebv/wagoe
/plugin install wagoe@wagoe
```

See [claude-plugin/README.md](./claude-plugin/README.md) for details.

---

## Documentation

| Resource | Description |
|----------|-------------|
| [Documentation](./docs/) | Architecture guides, tutorials, library reference (Antora) |
| [AGENTS.md](./AGENTS.md) | Commands, conventions, common pitfalls, debugging |
| [dev-docs/adr/](./dev-docs/adr/) | Architecture Decision Records |
| [Deployment Patterns](./docs/modules/guides/pages/deployment-patterns.adoc) | systemd, nginx, Fly.io, Render reference configs |
| [Migrations Guide](./docs/modules/guides/pages/migrations.adoc) | Zero-downtime schema change patterns |
| [Security Checklist](./dev-docs/security-checklist.adoc) | OWASP Top 10 aligned production checklist |
| [Scaling Guide](./dev-docs/scaling-guide.adoc) | JVM, HikariCP, Redis, and HTTP tuning |

Each library also has its own `AGENTS.md` with library-specific documentation.

---

## Libraries

Wagoe is a monorepo of **30 independently publishable libraries**, application and development tooling alike:

| Library | Description |
|---------|-------------|
| [core](libs/core/) | Foundation: validation, utilities, interceptor pipeline, feature flags |
| [observability](libs/observability/) | Logging, metrics, error reporting (Datadog, Sentry) |
| [platform](libs/platform/) | HTTP, database, CLI infrastructure |
| [user](libs/user/) | Authentication, authorization, MFA, session management |
| [admin](libs/admin/) | Auto-generated CRUD admin UI (Hiccup + HTMX) |
| [storage](libs/storage/) | File storage: local filesystem and S3 |
| [scaffolder](libs/scaffolder/) | Interactive module code generator |
| [cache](libs/cache/) | Distributed caching: Redis and in-memory |
| [jobs](libs/jobs/) | Background job processing with retry logic |
| [email](libs/email/) | Email delivery: SMTP, async, jobs integration |
| [tenant](libs/tenant/) | Multi-tenancy with PostgreSQL schema-per-tenant isolation |
| [realtime](libs/realtime/) | WebSocket / SSE for real-time features |
| [external](libs/external/) | External service adapters: Twilio, IMAP |
| [payments](libs/payments/) | Payment provider abstraction: Stripe, Mollie, Mock |
| [reports](libs/reports/) | PDF, Excel, and Word (DOCX) generation via `defreport` |
| [calendar](libs/calendar/) | Recurring events, iCal export/import, conflict detection |
| [workflow](libs/workflow/) | Declarative state machine workflows with audit trail |
| [search](libs/search/) | Full-text search: PostgreSQL FTS with LIKE fallback for H2/SQLite |
| [geo](libs/geo/) | Geocoding (OSM/Google/Mapbox), DB cache, Haversine distance |
| [ai](libs/ai/) | Framework-aware AI tooling: NL scaffolding, error explainer, test generator, SQL copilot, docs wizard |
| [i18n](libs/i18n/) | Marker-based internationalisation with translation catalogues |
| [push](libs/push/) | Multi-platform push notifications: FCM (Firebase) + APNs (Apple) |
| [audience](libs/audience/) | Rule-based audience segmentation with SQL + predicate pipeline |
| [ui-style](libs/ui-style/) | Shared UI style bundles, design tokens, CSS/JS assets |
| [shared-ui](libs/shared-ui/) | Shared Hiccup primitives: forms, tables, layouts, modals, icons |
| [devtools](libs/devtools/) | Dev-only: error pipeline, dev dashboard, REPL power tools, guidance engine |
| [tools](libs/tools/) | Dev-only: deploy, doctor, setup, scaffolder integration, quality checks |
| [wagoe-cli](libs/wagoe-cli/) | The `wagoe` command: `new`, `add`, `list modules` |
| [wagoe-mcp](libs/wagoe-mcp/) | MCP server over stdio for editor agents |

---

## Architecture

Wagoe enforces the **Functional Core / Imperative Shell** pattern throughout:

```
libs/{library}/src/wagoe/{library}/
├── core/       # Pure functions only — no I/O, no logging, no exceptions
├── shell/      # All side effects: persistence, services, HTTP handlers
├── ports.clj   # Protocol definitions (interfaces for dependency injection)
└── schema.clj  # Malli validation schemas
```

**Dependency rules (strictly enforced):**

- Shell → Core (allowed)
- Core → Ports (allowed)
- Core → Shell (**never** — this violates FC/IS)

This keeps business logic fast to test (no mocks needed), easy to reason about, and safe to refactor.

**Case conventions** — a frequent source of bugs:

| Wagoe | Convention |
|----------|------------|
| Clojure code | `kebab-case` (`:password-hash`, `:created-at`) |
| Database | `snake_case` |
| API (JSON) | `camelCase` |

Use `wagoe.core.utils.case-conversion` for conversions. Never convert manually.

---

## Essential Commands

```bash
# Testing (Kaocha, default test profile uses H2 in-memory DB)
clojure -M:test                                          # All tests
clojure -M:test :core                                    # Single library
clojure -M:test --focus-meta :unit                       # Unit tests only
clojure -M:test --focus-meta :integration                # Integration tests only
clojure -M:test --watch :core                            # Watch mode
JWT_SECRET="dev-secret-at-least-32-characters-long" WAG_ENV=test clojure -M:test

# Linting
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test

# REPL (nREPL on port 7888)
clojure -M:repl-clj
# In REPL: (go) | (reset) | (halt)

# Build
clojure -T:build clean && clojure -T:build uber

# Database migrations
clojure -M:migrate up

# Scaffolding
bb scaffold   # Interactive module wizard
bb scaffold ai "product module with name, price, stock"  # NL scaffolding via AI (interactive confirm)
bb scaffold ai "product module with name, price, stock" --yes  # Non-interactive generation

# AI tooling
bb ai explain --file stacktrace.txt  # Explain error
bb ai gen-tests libs/user/src/wagoe/user/core/validation.clj  # Generate tests
bb ai sql "find active users with orders in last 7 days"          # HoneySQL from NL
bb ai docs --module libs/user --type agents                       # Generate AGENTS.md

# Operations
bb doctor                          # Validate config for common mistakes
bb doctor --env all --ci           # Check all envs, exit non-zero (CI)
bb setup                           # Interactive config setup wizard
bb setup ai "PostgreSQL with Stripe payments"  # AI-powered config setup
bb deploy --all                    # Deploy all libraries to Clojars
bb deploy --missing                # Deploy only unpublished libraries
```

See [AGENTS.md](./AGENTS.md) for the complete command reference, common pitfalls, and debugging strategies.

### Running The Full Suite Against PostgreSQL

The default `test` profile runs against in-memory H2. To run against PostgreSQL:

1. Start a PostgreSQL instance matching the credentials in
   [`resources/conf/test/config.edn`](./resources/conf/test/config.edn).
2. In `resources/conf/test/config.edn`, move `:wagoe/postgresql` from `:inactive` to `:active`
   and move `:wagoe/h2` out of `:active`.
3. Run:

```bash
WAG_ENV=test JWT_SECRET="dev-secret-at-least-32-characters-long" clojure -M:migrate up
WAG_ENV=test JWT_SECRET="dev-secret-at-least-32-characters-long" clojure -M:test
```

4. Revert `resources/conf/test/config.edn` after the run.

---

## Quality Gates

Six automated safeguards run in CI to catch regressions early. The FC/IS check also runs as a pre-commit hook.

```bash
bb check:fcis                    # Core namespaces must not import shell, I/O, logging, or DB
bb check:placeholder-tests       # No (is true) placeholders masking missing coverage
bb check:deps                    # Library dependency direction + cycle detection
clojure -M:test --focus-meta :security  # Error mapping, CSRF, XSS, SQL parameterization
```

See [ADR-021](./dev-docs/adr/ADR-021-fcis-boundary-rules.adoc) (FC/IS rules) and [ADR-022](./dev-docs/adr/ADR-022-error-handling-conventions.adoc) (error handling conventions) for rationale.

---

## Releasing a New Version

The version appears in 104 locations — 59 in source, 45 in documentation.
`bb check:versions` is the list, and `bb bump` is the way to change it.

**1. Bump:**

```bash
bb bump 1.0.0-beta-6 --dry-run   # list what would change
bb bump 1.0.0-beta-6
```

It rewrites exactly the locations `check:versions` discovers and nothing else,
prints a `git diff --stat`, and finishes by verifying the result against the
version it just wrote. Re-running it is a no-op.

Give the plain version, not the tag: `1.0.0-beta-6`, not `v1.0.0-beta-6`. It
refuses a leading `v` rather than writing it into 104 places, where every
location would then agree and the check would pass on it.

**2. Verify and commit:**

```bash
bb check
git add -A && git commit -m "bump library suite version to 1.0.0-beta-6"
```

The full `bb check` — not `--quick`, which skips `check:versions`. That gate is
the one that knows every location and fails when they disagree.

**3. Run the pre-release gate:**

The nightly first-run matrix doubles as the pre-release check — that is what its
`workflow_dispatch` is for. Run it against the commit you are about to tag
rather than trusting last night's run to describe today's tree.

```bash
gh workflow run first-run-matrix.yml -f reason="pre-release gate for 1.0.0-beta-6"
```

**4. Tag. The tag is the release:**

```bash
git push
git tag -a "1.0.0-beta-6" -m "Release 1.0.0-beta-6"
git push --tags
```

Pushing an unprefixed semver tag fires `.github/workflows/publish.yml`, which
waits for a maintainer to approve the `release` environment and then does the
rest: builds every library from the tagged commit in publish order, refuses if
the tag disagrees with the source version or if CI did not pass on that exact
commit, deploys the artifacts not already on Clojars, and creates the GitHub
release.

So there is nothing to run by hand afterwards. In particular:

- **Do not** `gh release create` — the workflow creates the release, and doing
  both collides.
- **Do not** `bb deploy --all` — the workflow has already deployed. Re-deploying
  a published artifact 409s and aborts the run, which is why the workflow itself
  uses `--missing`.

`bb deploy` by hand is the fallback for when the workflow cannot run, not a step
of the normal release. `patch-catalogue-version!` keeps `modules-catalogue.edn`
in sync after each successful deploy either way.

**What `bb bump` deliberately leaves alone:** `CHANGELOG.md` and the ADRs
(historical — they record what was true when written), `docs/superpowers/`
(dated design records), and `docs/modules/ROOT/pages/stability.adoc`, whose
subject *is* the old version numbers. Also draft/pre-releases on GitHub —
`install.sh` uses `/releases/latest`, which only returns published releases.

> Until BOU-316 this step was a global `find | xargs sed`. It set `OLD` and
> `NEW` to the same string, so a copy-paste run rewrote nothing and reported
> success — and the verification was `grep -r "$OLD"`, which then found nothing
> and agreed. It was also macOS-only (`sed -i ''`) and rewrote every occurrence
> of the version string, including third-party pins that happened to match.

---

## Using Individual Libraries

```clojure
;; Validation utilities only
{:deps {com.wagoe/wagoe-core {:mvn/version "1.0.0-beta-5"}}}

;; Full web application stack
{:deps {com.wagoe/wagoe-platform {:mvn/version "1.0.0-beta-5"}
        com.wagoe/wagoe-user     {:mvn/version "1.0.0-beta-5"}
        com.wagoe/wagoe-admin    {:mvn/version "1.0.0-beta-5"}}}
```

---

## Deployment

Build the uberjar and deploy to any platform:

```bash
clojure -T:build clean && clojure -T:build uber
WAG_ENV=prod java -jar target/wagoe-*-standalone.jar
```

Reference configurations are provided under `resources/deploy/`:

| Template | Description |
|----------|-------------|
| [systemd](resources/deploy/systemd/) | Service unit + environment file for bare-metal/VM |
| [nginx](resources/deploy/nginx/) | Reverse proxy with TLS, WebSocket support, static caching |
| [Fly.io](resources/deploy/cloud/fly.toml) | Auto-scaling, health checks, Amsterdam region |
| [Render](resources/deploy/cloud/render.yaml) | Blueprint with managed PostgreSQL |

Health endpoints: `/health` (liveness), `/health/ready` (readiness with DB/cache checks), `/health/live` (container orchestrator).

See the [Deployment Patterns guide](./docs/modules/guides/pages/deployment-patterns.adoc) for full instructions.

---

## Website

https://wagoe.org

---
## License

Copyright 2024–2026 Thijs Creemers.

Distributed under the [Eclipse Public License 2.0](./LICENSE).
