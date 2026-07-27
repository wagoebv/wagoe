# Boundary Framework

[![CI](https://github.com/thijs-creemers/boundary/actions/workflows/ci.yml/badge.svg)](https://github.com/thijs-creemers/boundary/actions/workflows/ci.yml)
[![Clojars Project](https://img.shields.io/clojars/v/org.boundary-app/boundary-core.svg)](https://clojars.org/org.boundary-app/boundary-core)
[![cljdoc](https://cljdoc.org/badge/org.boundary-app/boundary-core)](https://cljdoc.org/d/org.boundary-app/boundary-core)
[![License: EPL-2.0](https://img.shields.io/badge/License-EPL_2.0-blue.svg)](https://www.eclipse.org/legal/epl-2.0/)

**Boundary** is a batteries-included Clojure web framework that enforces the **Functional Core / Imperative Shell (FC/IS)** pattern: pure business logic in `core/`, side effects in `shell/`, and clean interfaces through `ports.clj` protocols.

---

## Why Boundary?

**For developers:** 23 independently-publishable libraries on Clojars — use just `boundary-core` for validation utilities, or go full-stack with JWT + MFA auth, auto-generated CRUD UIs, background jobs, multi-tenancy, real-time WebSockets, and more. Every library follows the same FC/IS structure, making any Boundary codebase instantly familiar.

**Ship faster:** The scaffolder generates fully structured modules (entity + routes + tests) in seconds. The admin UI auto-generates CRUD interfaces from your schema — no manual forms. Built-in observability, RFC 5988 pagination, and declarative interceptors mean you write business logic, not plumbing. AI tooling (`bb scaffold ai`, `bb ai gen-tests`, `bb ai sql`) handles the repetitive parts.

**Ship with confidence:** Reference deployment configs (systemd, nginx, Fly.io, Render), an OWASP-aligned security checklist, scaling guides, health check endpoints, and zero-downtime migration patterns.

**Zero lock-in:** Each library is a standard `deps.edn` dependency. Swap what doesn't fit.

---

## Install

Install the Boundary CLI — it handles all prerequisites (JVM, Clojure CLI, Babashka, bbin) automatically:

```bash
curl -fsSL https://get.boundary-app.org | bash
```

Fallback if `get.boundary-app.org` is unavailable:

```bash
curl -fsSL https://raw.githubusercontent.com/thijs-creemers/boundary/main/scripts/install.sh | bash
```

Supports macOS, Debian/Ubuntu, Arch Linux, and WSL2.

## Quick Start

```bash
# 1. Create a new project
boundary new my-app
cd my-app

# 2. Add optional modules (e.g. payments, cache, search)
boundary add payments
boundary list modules    # see all 19 optional modules

# 3. Run database migrations
clojure -M:migrate up

# 4. Start the REPL (headless nREPL server on port 7888)
export JWT_SECRET="change-me-dev-secret-min-32-chars"
clojure -M:repl
```

Connect your editor (or the Boundary MCP server) to the nREPL port, then eval:

```clojure
(go)    ; start the system — http://localhost:3000
(reset) ; reload changed namespaces and restart
(halt)  ; stop the system
```

You get: H2 in-memory database (zero-config), HTTP server on port 3000, a complete Integrant system, and REPL-driven development.

---

## AI-Native Development (Claude Code & Agentic CLIs)

Projects created with `boundary new` are agent-ready out of the box: they
include a `CLAUDE.md`, an `AGENTS.md`, and a Claude Code skill at
`.claude/skills/boundary/SKILL.md` that teaches the agent to use Boundary's
scaffolder and AI tooling instead of hand-writing boilerplate. Open Claude
Code in a fresh project and ask:

> add a product module with name, price, and stock

The agent will reach for `bb scaffold` and generate a complete FC/IS module
with tests and migrations.

For **existing** projects (or to get updates without regenerating), install
the plugin from this repo's marketplace:

```
/plugin marketplace add thijs-creemers/boundary
/plugin install boundary@boundary
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

Boundary is a monorepo of **23 independently publishable libraries** plus development tooling:

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
| [devtools](libs/devtools/) | Dev-only: error pipeline, dev dashboard, REPL power tools, guidance engine |
| [tools](libs/tools/) | Dev-only: deploy, doctor, setup, scaffolder integration, quality checks |

---

## Architecture

Boundary enforces the **Functional Core / Imperative Shell** pattern throughout:

```
libs/{library}/src/boundary/{library}/
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

| Boundary | Convention |
|----------|------------|
| Clojure code | `kebab-case` (`:password-hash`, `:created-at`) |
| Database | `snake_case` |
| API (JSON) | `camelCase` |

Use `boundary.core.utils.case-conversion` for conversions. Never convert manually.

---

## Essential Commands

```bash
# Testing (Kaocha, default test profile uses H2 in-memory DB)
clojure -M:test:db/h2                                          # All tests
clojure -M:test:db/h2 :core                                    # Single library
clojure -M:test:db/h2 --focus-meta :unit                       # Unit tests only
clojure -M:test:db/h2 --focus-meta :integration                # Integration tests only
clojure -M:test:db/h2 --watch :core                            # Watch mode
JWT_SECRET="dev-secret-32-chars-minimum" WAG_ENV=test clojure -M:test:db/h2

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
bb ai gen-tests libs/user/src/boundary/user/core/validation.clj  # Generate tests
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
WAG_ENV=test JWT_SECRET="dev-secret-32-chars-minimum" clojure -M:migrate up
WAG_ENV=test JWT_SECRET="dev-secret-32-chars-minimum" clojure -M:test:db/h2
```

4. Revert `resources/conf/test/config.edn` after the run.

---

## Quality Gates

Six automated safeguards run in CI to catch regressions early. The FC/IS check also runs as a pre-commit hook.

```bash
bb check:fcis                    # Core namespaces must not import shell, I/O, logging, or DB
bb check:placeholder-tests       # No (is true) placeholders masking missing coverage
bb check:deps                    # Library dependency direction + cycle detection
clojure -M:test:db/h2 --focus-meta :security  # Error mapping, CSRF, XSS, SQL parameterization
```

See [ADR-021](./dev-docs/adr/ADR-021-fcis-boundary-rules.adoc) (FC/IS rules) and [ADR-022](./dev-docs/adr/ADR-022-error-handling-conventions.adoc) (error handling conventions) for rationale.

---

## Releasing a New Version

Version appears in 26+ files — use these steps to bump consistently.

**1. Replace the version string everywhere (all .clj, .edn, and .md files):**

```bash
OLD="1.0.0-beta-1"
NEW="1.0.0-beta-1"   # example

# Source and config files
find . \( -name "*.clj" -o -name "*.edn" \) \
  ! -path "*/docs/superpowers/*" ! -path "*/.git/*" \
  -exec grep -l "$OLD" {} \; | xargs sed -i '' "s/$OLD/$NEW/g"

# Documentation (.md and .adoc)
find . \( -name "*.md" -o -name "*.adoc" \) \
  ! -path "*/CHANGELOG.md" ! -path "*/docs/superpowers/*" ! -path "*/.git/*" \
  -exec grep -l "$OLD" {} \; | xargs sed -i '' "s/$OLD/$NEW/g"
```

On Linux, use `sed -i` instead of `sed -i ''`.

**2. Verify, commit, tag, and release:**

```bash
# Verify — must print nothing
grep -r "$OLD" --include="*.clj" --include="*.edn" --include="*.adoc" . | grep -v ".git" | grep -v "docs/superpowers"

bb check --quick

git add -A && git commit -m "bump library suite version $OLD → $NEW"
git tag -a "$NEW" -m "Release $NEW"
git push && git push --tags
gh release create "$NEW" --title "$NEW" --notes "Library suite release $NEW"
```

**3. Deploy to Clojars:**

```bash
bb deploy --all
```

`patch-catalogue-version!` in the deploy script keeps `modules-catalogue.edn` in sync automatically after each successful deploy.

**What to skip:** `CHANGELOG.md` (maintain manually), `docs/superpowers/` (historical planning docs), draft/pre-releases on GitHub (`install.sh` uses `/releases/latest` which only returns published releases).

---

## Using Individual Libraries

```clojure
;; Validation utilities only
{:deps {org.boundary-app/boundary-core {:mvn/version "1.0.0-beta-1"}}}

;; Full web application stack
{:deps {org.boundary-app/boundary-platform {:mvn/version "1.0.0-beta-1"}
        org.boundary-app/boundary-user     {:mvn/version "1.0.0-beta-1"}
        org.boundary-app/boundary-admin    {:mvn/version "1.0.0-beta-1"}}}
```

---

## Deployment

Build the uberjar and deploy to any platform:

```bash
clojure -T:build clean && clojure -T:build uber
WAG_ENV=prod java -jar target/boundary-*-standalone.jar
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

https://boundary-app.org

---
## License

Copyright 2024–2026 Thijs Creemers.

Distributed under the [Eclipse Public License 2.0](./LICENSE).
