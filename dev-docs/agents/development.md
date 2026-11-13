# Development reference

Read when directed by [AGENTS.md](../../AGENTS.md). Paths in commands and inline code are relative to the repository root.

## Quick Reference

### Essential Commands

```bash
# ⭐ SCAFFOLDER (Primary tool for creating modules/features — use this first!)
bb scaffold                                        # Interactive module scaffolding wizard
bb scaffold ai "product module with name, price"   # AI-powered NL scaffolding (interactive confirm)
bb scaffold ai "product module with name, price" --yes  # AI-powered NL scaffolding (non-interactive)
bb scaffold entity --module-name invoice --entity InvoiceLineItem --belongs-to invoice --field qty:int  # Add entity to existing module
bb scaffold field --module-name invoice --entity Invoice --name amount --type decimal   # Add field to existing module
bb scaffold endpoint --module-name invoice --path /invoices --method post --handler-name create-invoice  # Add HTTP endpoint
bb scaffold integrate product                      # Write a scaffolded module's config key (--dry-run to preview)

# Testing - EVERY test surface (use this before committing / claiming green)
bb test:all                                              # main + tools + agents + wagoe-cli + wagoe-mcp
bb test:all --list                                       # show the surfaces, and what is deliberately excluded
bb test:services up                                      # Redis + MySQL the adapter sweeps need (docker compose, waits for healthy)
bb test:services                                         # which of them are up; `down` stops them

# Testing - main suite only (does NOT cover the standalone libs — see below)
clojure -M:test:test/all                           # every tests.edn suite (H2 in-memory by default)
JWT_SECRET="dev-secret-at-least-32-characters-long" WAG_ENV=test clojure -M:test:test/all  # With JWT secret

# Why `:test/all`. The heavy test dependencies — embedded PostgreSQL's two
# platform binaries (90 MB), the OpenTelemetry in-memory exporters, clj-http-lite
# — used to sit in `:test`, which all 27 per-library CI jobs resolve. One flaky
# artifact there failed them all under an innocent library's name (BOU-260).
# They now live in narrow aliases, and `:test/all` composes them for a full local
# run. Bare `clojure -M:test` runs the lean set: fine for a suite that needs
# nothing extra, and it fails on ClassNotFoundException for one that does.
#
#   :test/pg      admin, platform, platform-sql, scaffolder, tenant, workflow (embedded PostgreSQL, Linux binary)
#   :test/pg-mac  add to :test/pg on Apple Silicon — CI is ubuntu-only
#   :test/otel    observability             (OpenTelemetry in-memory exporters)
#   :test/http    devtools                  (clj-http-lite)
#   :test/mail    external                  (GreenMail IMAP server)
#   :test/ftp     external                  (MINA FTPS server)
clojure -M:test:test/pg:test/pg-mac :admin         # one suite, only what it needs (macOS)
WAGOE_TEST_DB=mysql clojure -M:test:test/pg :admin # admin, user, workflow, search, push on mysql or pg (support.test-db)

# Testing - Per-library test suites
clojure -M:test :core                              # Core library tests
clojure -M:test:test/otel :observability           # Observability library tests
clojure -M:test:test/pg :platform                  # Platform library tests
clojure -M:test:test/pg :platform-sql              # Platform SQL library tests (MySQL: bb test:services up)
clojure -M:test :user                              # User library tests
clojure -M:test:test/pg :admin                     # Admin library tests
clojure -M:test :storage                           # Storage library tests
clojure -M:test:test/pg :scaffolder                # Scaffolder library tests
clojure -M:test :cache                             # Cache library tests
clojure -M:test :jobs                              # Jobs library tests
clojure -M:test :email                             # Email library tests
clojure -M:test:test/pg :tenant                    # Tenant library tests
clojure -M:test :realtime                          # Realtime library tests
clojure -M:test:test/pg :workflow                  # Workflow library tests
clojure -M:test :search                            # Search library tests
clojure -M:test:test/mail:test/ftp :external       # External adapters tests
clojure -M:test :payments                          # Payments library tests
clojure -M:test :reports                           # Reports library tests
clojure -M:test :calendar                          # Calendar library tests
clojure -M:test :geo                               # Geo library tests
clojure -M:test :ai                                # AI library tests
clojure -M:test :ui-style                          # UI style library tests
clojure -M:test :i18n                              # i18n library tests

# Testing - By metadata category
clojure -M:test:test/all --focus-meta :unit                 # Unit tests only
clojure -M:test:test/all --focus-meta :integration          # Integration tests
clojure -M:test:test/all --focus-meta :contract             # Database contract tests

# Testing - Watch mode and specific namespaces
clojure -M:test --watch :core                      # Watch core library tests
clojure -M:test:test/all --focus validation-test            # Single namespace

# Update validation snapshots
UPDATE_SNAPSHOTS=true clojure -M:test:test/all --focus user-validation-snapshot-test

# Code Quality
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test  # Lint all code

# REPL Development
clojure -M:repl-clj                                # Start REPL (nREPL on port 7888)
# WAG_ENV selects the profile; JWT_SECRET must be set. With WAG_ENV=test the
# system starts on in-memory H2 and needs no database running.
# In REPL:
(require '[integrant.repl :as ig-repl])
(ig-repl/go)                                       # Start system
(ig-repl/reset)                                    # Reload and restart
(ig-repl/halt)                                     # Stop system

# Tools
clj-nrepl-eval --discover-ports                    # Find nREPL ports
clj-nrepl-eval -p 7888 "(+ 1 2)"                   # Evaluate code via nREPL
clj-paren-repair <file>                            # Fix parentheses

# Build
clojure -T:build clean && clojure -T:build uber    # Build uberjar
java -jar target/wagoe-*.jar server             # Run standalone jar (HTTP server)
java -jar target/wagoe-*.jar worker             # Run as a background worker (no HTTP listener)
java -jar target/wagoe-*.jar service payments  # Run one module as an independent service (BOU-91)
java -jar target/wagoe-*.jar service user tenant  # Several modules in one process
java -jar target/wagoe-*.jar authz-report       # Each route's authorization rule; what :enforce refuses

# Deploy (see deploy/README.md)
docker build -t wagoe:latest .                  # Prod image (root Dockerfile; server + worker modes)

# Database Migrations
clojure -M:migrate up                              # Run migrations

# Scripting (Babashka)
bb ai explain --file stacktrace.txt                # Explain error via AI (experimental)
bb ai gen-tests libs/user/src/wagoe/user/core/validation.clj  # Generate test namespace (experimental)
bb ai sql "find active users with orders in last 7 days"          # HoneySQL from NL (experimental)
bb ai docs --module libs/user --type agents                       # Generate AGENTS.md
bb ai admin-entity "products with name, price, status"            # Generate admin entity EDN config
wagoe doctor                                       # Diagnostics front door: environment + config
                                                   # + commands + setup, one next action
bb doctor                                          # Just the config checks
bb doctor --env all --ci                           # Check all environments (CI mode)
bb setup                                           # Interactive config setup wizard
bb setup ai "PostgreSQL with Stripe"               # AI-powered config setup
bb create-admin                                    # Create first admin user for a new project (interactive wizard)
bb create-admin --env prod                         # Use production config
bb create-admin --email a@b.com --name "Admin"     # Skip email/name prompts (password still prompted securely)
bb migrate up                                      # Run pending database migrations
bb migrate status                                  # Show current migration status
bb coverage                                        # Run Cloverage coverage with the monorepo test/classpath setup
bb check-links                                     # Validate local markdown links in AGENTS.md files
bb smoke-check                                     # Verify deps.edn aliases and key tool entrypoints
bb install-hooks                                   # Configure git hooks path to .githooks
bb scripts/docs_lint.clj                           # Run documentation drift linter directly

# Quality Gates (all run in CI + check:fcis/check:ports run in pre-commit)
bb check:fcis                                      # FC/IS enforcement: core/ must not import shell/IO/logging/DB, throw, or hold mutable state
bb check:placeholder-tests                         # Detect (is true) placeholder assertions in tests
bb check:deps                                      # Declared wagoe deps = used ones, both ways; cycles; undeclared third-party deps
bb check:ports                                     # Hexagonal: modules must define ports.clj; shell/web must not bypass protocols
bb check:ports-diff                                # A stable library's protocols gained, lost or moved no method since the last release (Convention 5)
bb check:poms                                      # Published POMs must carry inter-Wagoe deps (build-shared rewrite + pom-basis)
bb check:roadmap                                   # One roadmap, and it may not plan what scaling.adoc marks shipped
bb check:vulns                                     # Known vulnerabilities in every deps.edn, aliases included (needs GITHUB_TOKEN; weekly in CI)
bb check:error-shape                               # Errors carry the shape ADR-022/ADR-036 decided: :type on a thrown ex-info, {:error {:type <kw>}} on a {:success? false}
clojure -M:test:test/all --focus-meta :security             # Security-focused tests (error mapping, CSRF, XSS, SQL)
```

### AI Assistant Helpers

Wagoe assumes two Clojure assistant helpers are installed from
`https://github.com/bhauman/clojure-mcp-light`:

- `clj-nrepl-eval` for shell-driven REPL evaluation
- `clj-paren-repair` for delimiter repair after edits

Install them with `bbin`:

```bash
bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2 --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'
bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2 --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]'
```

Verify:

```bash
clj-nrepl-eval --discover-ports
clj-paren-repair --help
```

## Scaffolding

Use the commands above and the scaffold-first rules in [AGENTS.md](../../AGENTS.md#scaffold-first).

### Adding New Functionality (Manual Workflow)

If you cannot use the scaffolder (rare edge cases), follow this checklist:

1. **Define schema** in `libs/{library}/src/wagoe/{library}/schema.clj`
2. **Write core logic** in `libs/{library}/src/wagoe/{library}/core/{domain}.clj` (pure functions)
3. **Write unit tests** in `libs/{library}/test/wagoe/{library}/core/{domain}_test.clj`
4. **Define port** in `libs/{library}/src/wagoe/{library}/ports.clj` (protocol). In a stable library a new
   capability is a new protocol, never a method added to an existing one (Convention 5)
5. **Implement in service** in `libs/{library}/src/wagoe/{library}/shell/service.clj`
6. **Add HTTP endpoint** in `libs/{library}/src/wagoe/{library}/shell/http.clj`

⚠️ **After manual creation, run `bb check:fcis` to verify FC/IS boundaries are not violated.**

## Testing

See [Testing reference](testing.md) for test surfaces, services, reporter and PostgreSQL runs.

### REPL Debugging

```clojure
;; Get system component
(def user-svc (get integrant.repl.state/system :wagoe/user-service))

;; Test directly
(ports/list-users user-svc {:limit 10})

;; Reload namespace with changes (use :reload)
(require '[wagoe.user.core.user :as user-core] :reload)

;; Full system reset
(ig-repl/halt)
(ig-repl/go)
```

### Debugging Workflow

When encountering 500 errors or unexpected behavior:

1. **Check logs first** - Errors are logged with stack traces:
   ```bash
   tail -100 logs/wagoe.log | grep -A 10 "ERROR"
   ```
   `resources/logback.xml` writes `logs/wagoe.log`, plus `logs/audit.log` and
   `logs/security.log`. A generated project's `resources/logback.xml` logs to
   the console (stderr) rather than to a file.

2. **Add temporary logging** - Use `println` for quick debugging:
   ```clojure
   (println "DEBUG:" {:field value :other other-value})
   ```
   Output appears in REPL/server stdout, not log files.

3. **Test in isolation via REPL** - Bypass HTTP layer to test services directly:
   ```clojure
   (def admin-svc (get integrant.repl.state/system :wagoe/admin-service))
   (admin-ports/update-entity admin-svc :users uuid {:name "Test"})
   ```

4. **Check actual HTTP request data** - Add temporary logging in handlers:
   ```clojure
   (println "DEBUG request:"
            {:method (:request-method request)
             :params (:params request)
             :headers (select-keys (:headers request) ["hx-request"])})
   ```

5. **Verify database state** - Query directly via REPL:
   ```clojure
   (def ds (get-in integrant.repl.state/system [:wagoe/db-context :datasource]))
   (jdbc/execute! ds ["SELECT * FROM users WHERE id = ?" uuid])
   ```

6. **Remove debug logging before committing** - Clean up all `println` statements.

**Key Principle**: Start from the innermost layer (core/database) and work outward to HTTP layer. Don't debug through the full stack if you can isolate the issue.

---

## Key Technologies

| Library | Purpose | Critical Knowledge |
|---------|---------|-------------------|
| **Integrant** | Lifecycle | Use `ig-repl/go`, `ig-repl/reset`, `ig-repl/halt` |
| **Aero** | Config | Environment-based profiles in `resources/conf/` |
| **Malli** | Validation | Schemas in `{module}/schema.clj` |
| **next.jdbc** | Database | Connection pooling, prepared statements |
| **Ring/Reitit** | HTTP | Normalized routes, interceptors |
| **HTMX** | Web UI | Server-side rendering, no build step |

---

## Configuration

**Supported databases**: PostgreSQL, SQLite and MySQL 8, and H2 for tests
(`scripts/mysql-smoke.sh` proves a generated app on MySQL). Tenancy
(`:wagoe/tenant`) needs PostgreSQL and refuses to boot on SQLite, MySQL, and H2
unless `:wagoe/tenant` sets `:allow-h2? true` (the test profile does).

**Current Setup (Development)**:
- **Database**: PostgreSQL on `localhost:5432`, database `wagoe_dev` — `:wagoe/postgresql`
  is the adapter in `:active`. SQLite and H2 are present but under `:inactive`;
  move one into `:active` to use it. Without a reachable PostgreSQL,
  `clojure -M:run server` fails at `:wagoe/db-context` with
  `FATAL: database "wagoe_dev" does not exist`.
- **HTTP**: Port 3000, auto-finding 3000–3099 when busy. The allocated port is
  logged (`HTTP server started successfully {:port …}`) — read it rather than
  assume 3000.
- **Environment**: `WAG_ENV=dev` (`development` is accepted as an alias).

**Running with no external services**: the `test` profile uses in-memory H2 and
needs nothing installed:

```bash
JWT_SECRET="dev-secret-at-least-32-characters-long" WAG_ENV=test clojure -M:run server
```

It serves in about 30 seconds on port 3100 (range 3100–3199, chosen so it does
not collide with a dev server on 3000).

**Environment Variables**:
```bash
export JWT_SECRET="dev-secret-minimum-32-characters"
export DB_PASSWORD="dev_password"
```

**Configuration File**: `resources/conf/dev/config.edn`

### Sizing & Scaling

Vertical scaling is config-driven (HikariCP pool, Jetty, JVM heap, Redis pool —
all in `config.edn` + env). Horizontal scaling rides on the `ports.clj` seam: swap
an in-process adapter for a distributed one. Cache, jobs, auth, tenancy, realtime,
rate limiting and the event bus are all replica-safe via Redis/DB — each still has
a single-process in-memory adapter, so the work is activating them, not building
them. One provider vocabulary across cache, realtime, events and jobs: `:memory` and
`:redis` everywhere, plus `:db` where a module has a database adapter — today
only jobs. Events has no default and throws on anything else. The same seam allows
functional decomposition: `java -jar wagoe.jar service <module>...` boots a module
alone, `wagoe.platform.shell.rpc` gives any port a remote implementation, and no
dependency cycles remain to block slicing. What is not done is service discovery
and the data-ownership decision. Full readiness matrix, topologies, sliceability,
and the production checklist: `docs/modules/architecture/pages/scaling.adoc`.

---

## Examples

Three, all in this repository and all run by CI on every commit:

| Path | What it is |
|---|---|
| `examples/shop` | A generated application — `wagoe new` + `bb scaffold` output, committed. Boots, serves HTTP, has one module to copy from. |
| `examples/stores` | `examples/shop`'s module on a second store, an atom. One contract suite runs on both repositories. |
| `examples/todo` | Functional core / imperative shell with no framework at all. ~200 lines, prints and exits. |

```bash
./scripts/example-smoke.sh     # runs all three, asserts on what they do
bb example:regen               # rebuild examples/shop from the current generators
bb example:regen --check       # CI: fail if it has drifted from generator output
```

`examples/shop` is regenerated rather than maintained, so it cannot describe a
project shape nobody gets. See `examples/shop/README.md`.

The former `wagoe-examples` repository is archived; these are the examples.

### SQLite `nil` LIMIT/OFFSET pitfall

Clojure's `{:or {limit 20 offset 0}}` destructuring only fires for **absent** keys. If the caller passes `{:limit nil :offset nil}` (e.g. from `some->` on missing query params), the defaults are ignored and `LIMIT nil` causes `SQLITE_MISMATCH`. Always guard with `(or limit 20)` / `(or offset 0)` at the point where the SQL params vector is assembled.

### Framework migration ids end in an odd second

Apps and libraries share one id space in `schema_migrations` (BOU-717). New framework ids end in an odd second
(`20261020120017`), off the round timestamps apps pick by hand; `test/wagoe/migration_ids_test.clj` enforces it. Never
rename a released id: databases that recorded it would run it again. Per-module tables are a follow-up.

---
