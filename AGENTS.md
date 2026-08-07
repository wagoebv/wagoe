# Wagoe Framework — Developer Reference

Essential commands, conventions, and patterns for working with the Wagoe Framework.

---

## Quick Reference

### Behavior essentials
1. Don’t assume. Don’t hide confusion. Surface tradeoffs.
2. Minimum code that solves the problem. Nothing speculative.
3. Touch only what you must. Clean up only your own mess.
4. Define success criteria. Loop until verified.

### Essential Commands

```bash
# ⭐ SCAFFOLDER (Primary tool for creating modules/features — use this first!)
bb scaffold                                        # Interactive module scaffolding wizard
bb scaffold ai "product module with name, price"   # AI-powered NL scaffolding (interactive confirm)
bb scaffold ai "product module with name, price" --yes  # AI-powered NL scaffolding (non-interactive)
bb scaffold field --module-name invoice --entity Invoice --name amount --type decimal   # Add field to existing module
bb scaffold endpoint --module-name invoice --path /invoices --method post --handler-name create-invoice  # Add HTTP endpoint
bb scaffold integrate product                      # Guide integration of a scaffolded module (config + wiring)

# Testing - EVERY test surface (use this before committing / claiming green)
bb test:all                                              # main + tools + agents + wagoe-cli + wagoe-mcp
bb test:all --list                                       # show the surfaces, and what is deliberately excluded

# Testing - main suite only (does NOT cover the standalone libs — see below)
clojure -M:test                                    # tests.edn suites (default test profile uses H2 in-memory)
JWT_SECRET="dev-secret-at-least-32-characters-long" WAG_ENV=test clojure -M:test  # With JWT secret

# Testing - Per-library test suites
clojure -M:test :core                              # Core library tests
clojure -M:test :observability                     # Observability library tests
clojure -M:test :platform                          # Platform library tests
clojure -M:test :user                              # User library tests
clojure -M:test :admin                             # Admin library tests
clojure -M:test :storage                           # Storage library tests
clojure -M:test :scaffolder                        # Scaffolder library tests
clojure -M:test :cache                             # Cache library tests
clojure -M:test :jobs                              # Jobs library tests
clojure -M:test :email                             # Email library tests
clojure -M:test :tenant                            # Tenant library tests
clojure -M:test :realtime                          # Realtime library tests
clojure -M:test :workflow                          # Workflow library tests
clojure -M:test :search                            # Search library tests
clojure -M:test :external                          # External adapters tests
clojure -M:test :payments                          # Payments library tests
clojure -M:test :reports                           # Reports library tests
clojure -M:test :calendar                          # Calendar library tests
clojure -M:test :geo                               # Geo library tests
clojure -M:test :ai                                # AI library tests
clojure -M:test :ui-style                          # UI style library tests
clojure -M:test :i18n                              # i18n library tests

# Testing - By metadata category
clojure -M:test --focus-meta :unit                 # Unit tests only
clojure -M:test --focus-meta :integration          # Integration tests
clojure -M:test --focus-meta :contract             # Database contract tests

# Testing - Watch mode and specific namespaces
clojure -M:test --watch :core                      # Watch core library tests
clojure -M:test --focus validation-test            # Single namespace

# Update validation snapshots
UPDATE_SNAPSHOTS=true clojure -M:test --focus user-validation-snapshot-test

# Code Quality
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test  # Lint all code

# REPL Development
clojure -M:repl-clj                                # Start REPL (nREPL on port 7888)
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

# Deploy (see deploy/README.md)
docker build -t wagoe:latest .                  # Prod image (root Dockerfile; server + worker modes)

# Database Migrations
clojure -M:migrate up                              # Run migrations

# Scripting (Babashka)
bb ai explain --file stacktrace.txt                # Explain error via AI
bb ai gen-tests libs/user/src/wagoe/user/core/validation.clj  # Generate test namespace
bb ai sql "find active users with orders in last 7 days"          # HoneySQL from NL
bb ai docs --module libs/user --type agents                       # Generate AGENTS.md
bb ai admin-entity "products with name, price, status"            # Generate admin entity EDN config
bb doctor                                          # Validate config for common mistakes
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
bb check:deps                                      # Dependency direction + cycles + undeclared third-party deps
bb check:ports                                     # Hexagonal: modules must define ports.clj; shell/web must not bypass protocols
bb check:poms                                      # Published POMs must carry inter-Wagoe deps (build-shared rewrite + pom-basis)
clojure -M:test --focus-meta :security             # Security-focused tests (error mapping, CSRF, XSS, SQL)
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

### Architecture Quick Facts

```
┌─────────────────────────────────────────────┐
│         IMPERATIVE SHELL (shell/*)          │
│  I/O, validation, logging, side effects     │
└─────────────────────────────────────────────┘
                    ↓ calls
┌─────────────────────────────────────────────┐
│           PORTS (ports.clj)                 │
│  Protocol definitions (interfaces)          │
└─────────────────────────────────────────────┘
                    ↑ implements
┌─────────────────────────────────────────────┐
│         FUNCTIONAL CORE (core/*)            │
│  Pure functions, business logic only        │
└─────────────────────────────────────────────┘
```

| **Module Structure:**
| ```
| libs/{library}/src/wagoe/{library}/
| ├── core/          # Pure business logic
| ├── shell/         # I/O, validation, adapters
| ├── ports.clj      # Protocol definitions
| └── schema.clj     # Malli validation schemas
| 
| # Layout exceptions (the shape above is the norm; these libs deviate by design):
| #   - cache/         has no core/ — thin adapter lib (shell + ports only)
| #   - platform/ , observability/  split ports across several files, not one ports.clj
| #   - wagoe-mcp/  sources live under wagoe/mcp/ (ns wagoe.mcp.*) —
| #                    lib dir name and namespace segment differ on purpose
| #   - shared-ui/     shared Hiccup UI primitives (wagoe.shared.ui.*); no ports/schema
| 
| # Library structure (monorepo)
| libs/
| ├── core/          # Foundation: validation, utilities, interceptors
| ├── observability/ # Logging, metrics, error reporting
| ├── platform/      # HTTP, database, CLI infrastructure
| ├── user/          # Authentication, authorization, MFA
| ├── admin/         # Auto-CRUD admin interface
| ├── storage/       # File storage (local & S3)
| ├── scaffolder/    # Module code generator
| ├── cache/         # Distributed caching (Redis/in-memory)
| ├── jobs/          # Background job processing
| ├── email/         # Production-ready email sending (SMTP)
| ├── realtime/      # WebSocket/SSE for real-time features
| ├── tenant/        # Multi-tenancy (PostgreSQL schema-per-tenant)
| ├── workflow/      # State machine workflows with audit trails
| ├── search/        # Full-text search (PostgreSQL FTS / H2 LIKE)
| ├── payments/      # PSP abstraction: Mollie, Stripe, Mock (checkout-session flow)
| ├── external/      # External communication adapters: SMTP, IMAP, Twilio
| ├── reports/       # Report definitions, PDF/CSV export, scheduling (defreport macro)
| ├── calendar/      # Calendar events, RRULE recurrence, iCal export/import, conflict detection
| ├── geo/           # Geocoding (OSM/Google/Mapbox), DB-backed cache, Haversine distance
| ├── ai/            # Framework-aware AI tooling: NL scaffolding, error explainer, test generator, SQL copilot, docs wizard
| ├── ui-style/      # Shared UI style bundles, tokens, and CSS assets contract
| └── i18n/          # Marker-based internationalisation, translation catalogues, locale chains
| 
| ├── tools/         # Developer tooling: scaffolding, AI, i18n, deploy, dev utilities
| ```

---

## Scaffolder — Enforcing FC/IS Architecture

The **Wagoe Scaffolder** (`bb scaffold`) is the **primary tool for creating new modules and features** in Wagoe. It automatically generates correct FC/IS structure and prevents the most common architectural violations.

### Why the Scaffolder Matters

The quality gate `bb check:fcis` (run on every commit) enforces strict rules:
- Core layers **CANNOT** import shell code, I/O, logging, or database code
- Shell layers **MUST** depend on ports (protocols), not core implementations
- Dependencies must flow *downward* from shell → core → ports

**The scaffolder generates code that passes these gates by design.** Manually written code frequently violates these rules, especially:
- Imports from shell in core (Pitfall #5)
- Validation logic in core instead of shell (Pitfall #4)
- Schema-database mismatches (Pitfall #6)

### Scaffolder Commands (For LLMs)

| Command | Use When |
|---------|----------|
| `bb scaffold` | Creating a new module from scratch (interactive wizard) |
| `bb scaffold ai "description"` | Generating module structure from natural language description |
| `bb scaffold field --module-name {m} --entity {E} --name {field} --type {type}` | Adding a field to an existing module's schema |
| `bb scaffold endpoint --module-name {m} --path {path} --method {method} --handler-name {name}` | Adding an HTTP endpoint to an existing module |
| `bb scaffold integrate {module}` | Guide integration of a generated module (Integrant config + wiring) |

### Scaffolder Best Practices for AI Agents

**RULE 1:** Always start with scaffolder when creating functionality.
```bash
bb scaffold ai "invoice module with number, date, amount, status" --yes
```

**RULE 2:** Use `bb scaffold field` instead of manually editing schema.clj:
```bash
# Instead of manually adding to schema.clj:
bb scaffold field --module-name invoice --entity Invoice --name invoice-id --type string
```

**RULE 3:** After scaffolding, immediately run integration:
```bash
bb scaffold integrate invoice
```

**RULE 4:** Run quality gates after scaffolder completes:
```bash
bb check:fcis      # Verify FC/IS boundaries
bb check:deps      # Check dependency direction
clojure -M:test --watch :{module-name}  # Watch tests
```

### When NOT to Manually Write Code

❌ **NEVER manually create:**
- New module directories with existing structure (use scaffolder)
- Schema files without scaffolder validation
- Core→shell imports (violates FC/IS)
- Test files for scaffolded functions (scaffolder generates these)

✅ **OK to manually edit:**
- Business logic *inside* already-correct core functions
- Shell service implementations that wire ports
- HTTP handler bodies (after scaffolder creates the route structure)
- Test assertions (after scaffolder creates test file)

---

## Critical Conventions

### 1. ALWAYS Use kebab-case Internally

<!-- gen:naming -->
| Location | Convention | Example |
|----------|-----------|---------|
| All Clojure code | kebab | `:password-hash, :created-at` |
| Database boundary only | snake | `password_hash, created_at` |
| API/JSON boundary only | camel | `passwordHash, createdAt` |
<!-- /gen:naming -->

```clojure
;; ✅ CORRECT - Convert ONLY at persistence boundary using shared utilities
(require '[wagoe.core.utils.case-conversion :as cc])

;; At persistence boundary - DB to Clojure
(cc/snake-case->kebab-case-map db-record)

;; At persistence boundary - Clojure to DB
(cc/kebab-case->snake-case-map entity)

;; Alternative: Manual transformation
(defn db->user-entity [db-record]
  (-> db-record
      (set/rename-keys {:created_at :created-at
                        :password_hash :password-hash
                        :updated_at :updated-at})))
```

**Why**: Recent bug caused authentication failures because service layer used `:password_hash` but entities had `:password-hash`.

### 2. Layer Responsibilities

| Layer | Allowed | Never |
|-------|---------|-------|
| **Core** | Pure functions, calculations | I/O, logging, exceptions |
| **Shell** | I/O, validation, side effects | Business logic |
| **Ports** | Protocol definitions | Implementations |

### 3. Dependency Rules

<!-- gen:fc-is -->
| Direction | Allowed? |
|-----------|----------|
| Shell → Core | ✅ allowed |
| Core → Ports | ✅ allowed |
| Shell → Ports | ✅ allowed |
| Core → Shell | ❌ NEVER — violates FC/IS |
| Core → IO | ❌ NEVER — even logging |

Every module MUST define `ports.clj`.

- core/ must not import shell/IO/logging/DB
- core/ must not throw — return typed error values ({:error {:type ... :message ...}}); the shell translates them into HTTP responses (escape hatch: ^:wagoe/allow-throw ns metadata or .wagoe/check-fcis.edn)
- core/ must not hold mutable state (defonce/atom/swap!/reset!) — definition registries and process state live in the shell (wagoe.<lib>.shell.registry)
- cross-module calls go through service ports
- web/HTTP layers never require *.shell.persistence directly

```clojure
(ns myapp.core.product)
(defn calculate-total [items] (reduce + (map :price items)))
```
<!-- /gen:fc-is -->

---

## Common Workflows

### ⭐ Creating New Modules — USE THE SCAFFOLDER FIRST

**ALWAYS use the scaffolder to create new modules or add major features.** This ensures strict FC/IS architecture compliance and prevents common mistakes.

**For new modules:**
```bash
bb scaffold                                          # Interactive wizard (recommended)
bb scaffold ai "product module with name, price"    # AI-powered from description (interactive confirm)
bb scaffold ai "product module with name, price" --yes  # AI-powered (non-interactive)
```

**Why scaffolder is mandatory:**
- ✅ Creates correct FC/IS directory structure (core/, shell/, ports.clj, schema.clj)
- ✅ Generates validated Malli schemas—prevents schema-database mismatches (Pitfall #6)
- ✅ Prevents core→shell dependencies: scaffolder wires ports correctly (Pitfall #5)
- ✅ Integrates with quality gates: scaffolder output passes `bb check:fcis`
- ✅ Auto-generates unit test skeletons with correct metadata
- ✅ Hooks into deps.edn, tests.edn, and wiring automatically
- ✅ Enforces kebab-case conventions everywhere

**After scaffolding:**
```bash
bb scaffold integrate {module-name}    # Guides integration of your new module (config + wiring)
```

**For large features within existing modules:**
Use scaffolder's `--field` and `--endpoint` commands to add fields and HTTP handlers to existing modules. This is faster than manual creation and guarantees FC/IS compliance.

### Adding New Functionality (Manual Workflow)

If you cannot use the scaffolder (rare edge cases), follow this checklist:

1. **Define schema** in `libs/{library}/src/wagoe/{library}/schema.clj`
2. **Write core logic** in `libs/{library}/src/wagoe/{library}/core/{domain}.clj` (pure functions)
3. **Write unit tests** in `libs/{library}/test/wagoe/{library}/core/{domain}_test.clj`
4. **Define port** in `libs/{library}/src/wagoe/{library}/ports.clj` (protocol)
5. **Implement in service** in `libs/{library}/src/wagoe/{library}/shell/service.clj`
6. **Add HTTP endpoint** in `libs/{library}/src/wagoe/{library}/shell/http.clj`

⚠️ **After manual creation, run `bb check:fcis` to verify FC/IS boundaries are not violated.**

### Testing Workflow

```bash
# Watch mode while developing
clojure -M:test --watch --focus-meta :unit

# Watch specific library
clojure -M:test --watch :core

# Full test suite before committing — every surface, not just tests.edn
bb test:all

# Lint before committing
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test
```

### Test surfaces — why `bb test:all` exists

`clojure -M:test` runs the kaocha suites declared in `tests.edn`, which
cover the libraries on the app classpath. Three surfaces are **not** on that
classpath and are invisible to it:

| Surface | Run by | Why it is separate |
|---|---|---|
| `libs/wagoe-cli` | `clojure -M:test` in that dir | standalone lib, own `deps.edn` + `:test` alias |
| `libs/wagoe-mcp` | `clojure -M:test` in that dir | standalone lib, own `deps.edn` + `:test` alias |
| `libs/tools` | `bb test:tools` | pure Babashka, no Maven deps at runtime |
| `scripts/agents_gen_test.clj` | `bb test:agents` | generator test, outside the lib tree |

During the Wagoe rename this hid real failures three times — 8 in `wagoe-mcp`,
1 in `wagoe-cli`, 5 in `tools` (including a silently-broken FC/IS gate) — each
found only by remembering to run an extra command.

**So a green `clojure -M:test` does not mean the codebase is green.**
`bb test:all` runs all of them, prints a per-surface summary, and exits non-zero
if any fails. `bb test:all --list` shows the set, including what is deliberately
excluded (`e2e`, which needs a running server — use `bb e2e`).

None of the surfaces enumerate namespaces: the standalone libs let kaocha
discover `test/`, and the rest discover from disk. An earlier `bb test:wagoe-cli`
hardcoded four requires and had already drifted, silently skipping
`wagoe.cli.agents-update-test`.

### Custom Test Reporter

The Kaocha reporter at `dev/wagoe/test/reporter.clj` shows a green ✓ for
passing tests and a red ✗ for failing tests. It is configured in `tests.edn` as
`:kaocha/reporter [wagoe.test.reporter/reporter]`. The `dev/` directory is on
the `:test` classpath via `:extra-paths` in `deps.edn`.

### PostgreSQL Test Runs

The default `test` profile uses in-memory H2. There is currently no dedicated
PostgreSQL test alias such as `:db/pg`.

To do a complete run against PostgreSQL:

1. Start a disposable PostgreSQL instance that matches the credentials in
   `resources/conf/test/config.edn`.
2. Temporarily move `:wagoe/postgresql` from `:inactive` to `:active` in
   `resources/conf/test/config.edn`, and move `:wagoe/h2` out of `:active`.
3. Run:

```bash
WAG_ENV=test JWT_SECRET="dev-secret-at-least-32-characters-long" clojure -M:migrate up
WAG_ENV=test JWT_SECRET="dev-secret-at-least-32-characters-long" clojure -M:test
```

4. Revert `resources/conf/test/config.edn` afterwards so normal local and CI
   runs keep using H2.

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
   `logs/security.log`. Generated projects ship no logback config, so their
   logs go to the console rather than to a file.

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

## Common Pitfalls

**Most pitfalls listed below are automatically prevented by the scaffolder.** If you use `bb scaffold` to create modules, you will avoid pitfalls #1, #2, #4, #5, #6, and #9. Manual code creation is where these issues arise.

**LLM Reminder**: When adding functionality, reach for `bb scaffold` first. It's faster, safer, and ensures FC/IS compliance.

<!-- gen:pitfalls -->
### 1. snake_case vs kebab-case mixing

- **Symptom:** Using snake_case internally causes nil lookups.
- **Cause:** snake_case keys used inside Clojure code instead of converting only at boundaries.
- **Fix:** Always use kebab-case internally; convert at the persistence/HTTP boundary only via snake-case->kebab-case-map / kebab-case->snake-case-map.

### 2. defrecord changes not taking effect

- **Symptom:** (ig-repl/reset) doesn't recreate defrecord instances.
- **Cause:** ig-repl/reset reuses existing defrecord instances rather than recompiling them.
- **Fix:** Run (ig-repl/halt) then (ig-repl/go), or restart the REPL entirely.

```clojure
(ig-repl/halt)
(ig-repl/go)
;; OR restart REPL entirely
```

### 3. unbalanced parentheses

- **Symptom:** Manual editing creates syntax errors.
- **Cause:** Manually fixing delimiters by hand.
- **Fix:** ALWAYS use the tool, never manually fix: clj-paren-repair <file>.

### 4. validation in wrong layer

- **Symptom:** Validation side effects placed in pure core code.
- **Cause:** Calling valid?/throwing inside core functions instead of in the shell service.
- **Fix:** Validate in the shell; pass clean data into core functions.

```clojure
;; ❌ WRONG - Validation in core
(defn create-user [user-data]
  (when-not (valid? user-data)  ; Side effect in pure code!
    (throw ...)))

;; ✅ CORRECT - Validation in shell
(defn create-user-service [user-data]
  (let [[valid? errors data] (validate user-data)]
    (if valid?
      (user-core/create-user data)  ; Core gets clean data
      (throw ...))))
```

### 5. core depending on shell

- **Symptom:** Core namespace requires a shell namespace.
- **Cause:** Core requiring shell.persistence (or other shell IO) instead of depending on ports only.
- **Fix:** Core depends on ports only; keep core functions pure.

```clojure
(ns myapp.core.user
  ;; WRONG - Core requires shell namespace
  (:require [myapp.shell.persistence :as db]))  ; BAD!

;; CORRECT - Core depends on ports only
(ns myapp.core.user)
(defn find-user-decision [user-id existing-user] ...)  ; Pure logic
```

### 6. schema-database mismatch

- **Symptom:** 500 errors with cryptic messages, nil values for expected fields, SQL errors about missing columns.
- **Cause:** Adding logic that uses new fields without adding them to the Malli schema, the database columns, and the persistence transformations.
- **Fix:** When adding a field, synchronize all three: Malli schema, database column (migration), and persistence layer transformations.

```clojure
;; ALWAYS follow this checklist when adding new fields:

;; 1. Add to Malli schema (src/{module}/schema.clj)
[:failed-login-count {:optional true} :int]
[:lockout-until {:optional true} [:maybe inst?]]

;; 2. Add to persistence layer transformations (src/{module}/shell/persistence.clj)
;; In entity->db:
(update :lockout-until type-conversion/instant->string)
;; In db->entity:
(update :lockout-until type-conversion/string->instant)

;; 3. Verify database has columns (or add migration)
ALTER TABLE users ADD COLUMN failed_login_count INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN lockout_until TEXT;
```

### 7. exception handling - missing :type in ex-data

- **Symptom:** Exceptions without :type in ex-data trigger generic 500 errors; log warning "Exception reached HTTP boundary without :type in ex-data".
- **Cause:** Throwing exceptions from Java methods or using ex-info without a :type key.
- **Fix:** Wrap external/Java calls in try-catch and ensure ALL ex-info calls include a :type (validation-error, not-found, unauthorized, forbidden, conflict, or internal-error).

```clojure
;; WRONG - No :type in ex-data
(parse-long "invalid")  ; Throws NumberFormatException (no ex-data)
(UUID/fromString "bad") ; Throws IllegalArgumentException (no ex-data)
(throw (ex-info "Error" {:field :foo}))  ; Missing :type

;; CORRECT - Wrap in try-catch with :type
(try
  (parse-long value)
  (catch NumberFormatException _
    (throw (ex-info "Invalid integer value"
                    {:type :validation-error
                     :field field-keyword
                     :value value
                     :message "Must be a valid integer"}))))
```

### 8. Java interop - static vs instance methods

- **Symptom:** IllegalArgumentException: No matching method <method-name> found taking N args for class java.lang.Class.
- **Cause:** Using instance method syntax (.method) for static methods or static syntax for instance methods.
- **Fix:** Use ClassName/method for static methods and (.method object) for instance methods; check Java docs to identify which is which.

```clojure
;; WRONG - Using instance syntax for static method
(.between java.time.Duration instant now)
;; Error: No matching method between found taking 2 args

;; WRONG - Using static syntax for instance method
(java.time.Instant/getSeconds my-instant)
;; Error: No matching field or method getSeconds

;; CORRECT - Static method using ClassName/method
(java.time.Duration/between instant now)

;; CORRECT - Instance method using .method
(.getSeconds duration)

;; Common Java Interop Patterns:
;; Static methods (ClassName/method)
(java.time.Instant/now)
(java.util.UUID/randomUUID)
(java.lang.Math/abs -5)
(java.time.Duration/between start end)

;; Instance methods (.method object)
(.toString my-object)
(.getSeconds duration)
(.format instant formatter)

;; Static fields (ClassName/FIELD)
java.time.temporal.ChronoUnit/DAYS

;; Instance fields (.field object)
(.length my-string)
```

### 9. module API routes — wrong format (Reitit vectors vs normalized maps)

- **Symptom:** IllegalArgumentException: Key must be integer at wrap-route-with-version on (ig-repl/go).
- **Cause:** Module shell/http.clj returns Reitit-style route vectors instead of the normalized map format; the platform's apply-versioning calls (update route :path ...) which throws on vectors.
- **Fix:** API routes in shell/http.clj MUST be vectors of maps [{:path "..." :methods {...}}], not Reitit vectors, and paths must NOT include the /api prefix (versioning adds /api/v1).

```clojure
;; WRONG - Reitit-style tuple (do NOT use for module :api routes)
(defn my-routes [svc]
  [["/api/my-resource"
    {:get {:handler (fn [req] ...)}}]])

;; CORRECT - Normalized map format
(defn my-routes [svc]
  [{:path    "/my-resource"   ; NO /api prefix — versioning adds /api/v1
    :methods {:get {:handler (fn [req] ...)
                    :summary "..."}}}])
```

### 10. forward references — define before use

- **Symptom:** Unable to resolve symbol: my-helper in this context; Syntax error compiling.
- **Cause:** Private helpers placed after the public functions that call them; Clojure compiles top-to-bottom.
- **Fix:** Private helpers must always appear above the first function that calls them (in src/, dev/, and test/ alike).

```clojure
;; WRONG — system-component is called before it is defined
(defn dev-resend-invite! [...]
  (let [svc (system-component :wagoe/my-service)] ...))  ; used here

(defn- system-component [k]   ; defined here — too late!
  (get integrant.repl.state/system k))

;; CORRECT — helper defined first
(defn- system-component [k]
  (get integrant.repl.state/system k))

(defn dev-resend-invite! [...]
  (let [svc (system-component :wagoe/my-service)] ...))
```

### 11. Swagger/OpenAPI — parameters invisible without explicit declaration

- **Symptom:** Routes with path or query parameters show no input fields in Swagger UI.
- **Cause:** reitit-swagger only auto-generates parameter fields when a coercion layer is configured; without coercion, parameters render with no inputs.
- **Fix:** Add a :swagger key with raw OpenAPI 2.0 parameter specs to every handler/route that has path or query parameters.

```clojure
;; Path parameter — on the method
:get {:handler ...
      :summary "Get product by slug"
      :swagger {:parameters [{:name "slug" :in "path" :required true :type "string"
                              :description "Product slug (e.g. wagoe-tshirt)"}]}}

;; Query parameters — on the method
:get {:handler ...
      :summary "List customer orders"
      :swagger {:parameters [{:name "email"  :in "query" :required true  :type "string"}
                             {:name "limit"  :in "query" :required false :type "integer"}
                             {:name "offset" :in "query" :required false :type "integer"}]}}

;; Shared path param when a route has multiple methods — put :swagger at route level
["/api/cart/items/:product-id"
 {:swagger {:parameters [{:name "product-id" :in "path" :required true :type "string"}]}
  :patch  {:handler ... :summary "Update item quantity"}
  :delete {:handler ... :summary "Remove item from cart"}}]
```
<!-- /gen:pitfalls -->

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

**Current Setup (Development)**:
- **Database**: SQLite (`dev-database.db`) - no setup required
- **HTTP**: Port 3000 (auto-find if busy: 3001-3099)
- **Environment**: Set `WAG_ENV=development`

**Environment Variables**:
```bash
export JWT_SECRET="dev-secret-minimum-32-characters"
export DB_PASSWORD="dev_password"
```

**Configuration File**: `resources/conf/dev/config.edn`

### Sizing & Scaling

Vertical scaling is config-driven (HikariCP pool, Jetty, JVM heap, Redis pool —
all in `config.edn` + env). Horizontal scaling rides on the `ports.clj` seam: swap
an in-process adapter for a distributed one. Cache, jobs, auth, and tenancy are
already replica-safe via Redis/DB; realtime WebSocket and default rate-limiting
are not yet. The same seam allows functional decomposition (slicing a module into
its own service) — positioned for it, but needs a remote-port adapter + cycle
breaking, not free by config. Full readiness matrix, topologies, sliceability,
and the production checklist: `docs/modules/architecture/pages/scaling.adoc`.

---

## Internationalisation (i18n) — ADR-013

Translation keys live as `[:t :key]` data markers inside Hiccup trees.
A shell-layer `postwalk` resolves them before HTML is emitted.

### Workflow: adding a new string

1. **Add to `en.edn`** first — `libs/i18n/resources/wagoe/i18n/translations/en.edn`
2. **Add to `nl.edn`** — `libs/i18n/resources/wagoe/i18n/translations/nl.edn`
3. **Use marker in Hiccup** — `[:t :user/my-key]` instead of `"Hardcoded string"`
4. **Verify** — `bb i18n:scan` (exits 0 if no unexternalised literals remain)
5. **Check parity** — `bb i18n:missing` reports gaps between locales

### Marker syntax

```clojure
[:t :user/sign-in]                        ; simple lookup
[:t :user/greeting {:name "Alice"}]       ; interpolation
[:t :user/items {:n 3} 3]                 ; plural (4th arg = count)
```

### Babashka tooling

```bash
bb i18n:find "Sign in"      ; search catalogue + codebase
bb i18n:scan                ; CI gate — exit 1 if literals found
bb i18n:missing             ; report translation gaps
bb i18n:unused              ; report unreferenced catalogue keys
```

### Key namespaces in catalogues

| Namespace | Module |
|-----------|--------|
| `:common/*` | Shared across all modules |
| `:user/*` | User module |
| `:admin/*` | Admin module |
| `:search/*` | Search module |
| `:calendar/*` | Calendar module |
| `:workflow/*` | Workflow module |

### Full documentation

See `libs/i18n/AGENTS.md` for complete API reference, middleware wiring, and common pitfalls.

---

## Git Policy

- **Never commit or push without explicit user approval.** Always show the intended changes and wait for the user to say "commit" or "push" before running `git commit` or `git push`.

---

## Maintenance Notes

- Keep command examples in one place (`Quick Reference`); avoid duplicate command blocks elsewhere in this file.
- Library-specific troubleshooting and deep implementation notes belong in `libs/*/AGENTS.md`.
- Use `bb check-links` after editing AGENTS documentation.
- Install local pre-commit hooks once per clone: `bb install-hooks`.

---

## AGENTS.md generation

FC/IS rules, naming conventions, pitfalls, and the module table in this file —
and the FC/IS / naming / pitfalls sections of the downstream
`libs/wagoe-cli/resources/wagoe/cli/templates/AGENTS.md.tmpl` — are
generated from `resources/agents/knowledge.edn` (+ `modules-catalogue.edn` for the
module table). The generator lives at `scripts/agents_gen.clj`.

- Regenerate:  `bb agents:gen`
- Verify sync: `bb check:agents`  (also part of `bb check` + CI)
- Add/edit a pitfall, naming rule, or FC/IS rule: edit `resources/agents/knowledge.edn`,
  then run `bb agents:gen`.
- Add a library: add it to `modules-catalogue.edn` (or, for dev-only tooling not
  published as an app module, to `:dev-modules` in `knowledge.edn`), then `bb agents:gen`.
- **Regenerate before publishing `wagoe-cli`** so downstream `wagoe new`
  projects ship the current template.

The per-module AI doc generator (`bb ai docs --module libs/<x> --type agents`) is
separate and unchanged.

---

## Quality Gates

Automated safeguards run in CI (and `check:fcis` + `check:ports` in pre-commit) to prevent regressions caught during QA review (PRs #108–#116). `bb check` runs the full set — currently 14 — and `wagoe.tools.check/all-checks` is its registry; the table below covers the ones with non-obvious rules.

| Gate | Command | What it catches | Hard fail? |
|------|---------|-----------------|------------|
| **FC/IS enforcement** | `bb check:fcis` | Core namespaces importing shell, I/O, logging, or DB code; throwing; or holding mutable state (defonce/atom/swap!/reset!) | Yes |
| **Placeholder tests** | `bb check:placeholder-tests` | `(is true)` / `(is (= true true))` masking missing coverage | Yes |
| **Dependency direction** | `bb check:deps` | Core independence violations, circular deps between libraries, third-party namespaces required but not declared (so the published POM would omit them), requires no artifact mapping covers, unparseable `deps.edn` | Yes (cycles/core/new undeclared/unmapped); warn (allowlisted) |
| **Ports / hexagonal** | `bb check:ports` | Modules missing `ports.clj`; shell coupling to another module's `shell.persistence`/`shell.service`; web/HTTP requiring `shell.persistence` directly | Yes |
| **POM dep completeness** | `bb check:poms` | Published POMs dropping inter-Wagoe deps: `build_shared` losing the `:local/root`→mvn rewrite, a publishable `build.clj` bypassing `pom-basis`, or a referenced wagoe dep that is not itself publishable | Yes |
| **Documented library counts** | `bb check:doc-counts` | Prose disagreeing with `wagoe.tools.deploy/all-libs`: a documented library/artifact count that is not the real number, or a document calling a published library unpublished | Yes |
| **Security tests** | `clojure -M:test --focus-meta :security` | Error→HTTP mapping, CSRF routing, XSS escaping, SQL injection, sensitive field leaks | Yes (test failure) |
| **clj-kondo lint** | `clojure -M:clj-kondo --lint ...` | Static analysis (existing gate) | Yes |
| **Config doctor** | `bb doctor --env dev --ci` | Configuration errors (existing gate) | Yes |

**`check:ports` escape hatch (for legitimate exceptions / gradual adoption):** add `^:wagoe/allow-direct` metadata to a namespace to exempt it from the coupling rules, or list `:allow-missing-ports` (module ns prefixes) / `:allow-direct` (namespaces) in a `.wagoe/check-ports.edn` at the repo root.

**`check:doc-counts` escape hatch:** add an entry to `.wagoe/check-doc-counts.edn`. Every entry must carry a `:why` — the gate throws on one without it. Prefer the alternatives first: a historical document (changelog, ADR, delivered talk) belongs in `excluded-paths`, and a number that is not a library count (`HTTP 200 for each library`) belongs in `gap-disqualifiers`, so the same shape never needs allowlisting twice.

**Scripts location:** `libs/tools/src/wagoe/tools/check_{fcis,tests,deps,ports,poms,doc_counts}.clj`
**Security tests:** `libs/platform/test/wagoe/platform/shell/security_test.clj` (tagged `^:security ^:unit`)
**Handler test helpers:** `test/support/handler_test_helpers.clj` (Ring request builders, response assertions)
**ADRs:** `dev-docs/adr/ADR-021-fcis-boundary-rules.adoc`, `ADR-022-error-handling-conventions.adoc`

---

## Library-Specific Guides

<!-- gen:modules -->
| Module                                                                                   | Description                                                                       |
|------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| [admin](https://github.com/wagoebv/wagoe/blob/main/libs/admin/AGENTS.md)                 | Admin UI with entity config, HTMX forms                                           |
| [ai](https://github.com/wagoebv/wagoe/blob/main/libs/ai/AGENTS.md)                       | Multi-provider AI — Ollama, Anthropic Claude, OpenAI                              |
| [audience](https://github.com/wagoebv/wagoe/blob/main/libs/audience/AGENTS.md)           | Rule-based audience segmentation with SQL + predicate pipeline                    |
| [cache](https://github.com/wagoebv/wagoe/blob/main/libs/cache/AGENTS.md)                 | Distributed caching — Redis or in-memory, TTL, atomic ops                         |
| [calendar](https://github.com/wagoebv/wagoe/blob/main/libs/calendar/AGENTS.md)           | iCal, RRULE recurrence, conflict detection, Hiccup UI                             |
| [core](https://github.com/wagoebv/wagoe/blob/main/libs/core/AGENTS.md)                   | Pure validation, case conversion, interceptor pipeline, feature flags             |
| [devtools](https://github.com/wagoebv/wagoe/blob/main/libs/devtools/AGENTS.md)           | Dev-only tools: REPL helpers, error pipeline, dashboard                           |
| [email](https://github.com/wagoebv/wagoe/blob/main/libs/email/AGENTS.md)                 | SMTP email sending, async and queued modes                                        |
| [external](https://github.com/wagoebv/wagoe/blob/main/libs/external/AGENTS.md)           | External service adapters — Twilio, SMTP, IMAP                                    |
| [geo](https://github.com/wagoebv/wagoe/blob/main/libs/geo/AGENTS.md)                     | Multi-provider geocoding (OSM/Google/Mapbox), Haversine distance                  |
| [i18n](https://github.com/wagoebv/wagoe/blob/main/libs/i18n/AGENTS.md)                   | Marker-based i18n, translation catalogues, locale chains                          |
| [jobs](https://github.com/wagoebv/wagoe/blob/main/libs/jobs/AGENTS.md)                   | Background job processing with retry logic                                        |
| [observability](https://github.com/wagoebv/wagoe/blob/main/libs/observability/AGENTS.md) | Interceptor-based metrics, logging, and error reporting                           |
| [payments](https://github.com/wagoebv/wagoe/blob/main/libs/payments/AGENTS.md)           | PSP abstraction — Mollie, Stripe, Mock checkout and webhook verification          |
| [platform](https://github.com/wagoebv/wagoe/blob/main/libs/platform/AGENTS.md)           | HTTP server, Reitit router, Ring middleware pipeline                              |
| [push](https://github.com/wagoebv/wagoe/blob/main/libs/push/AGENTS.md)                   | Multi-platform push notifications — FCM (Firebase) + APNs (Apple)                 |
| [realtime](https://github.com/wagoebv/wagoe/blob/main/libs/realtime/AGENTS.md)           | WebSocket pub/sub messaging                                                       |
| [reports](https://github.com/wagoebv/wagoe/blob/main/libs/reports/AGENTS.md)             | PDF/CSV export and scheduled report generation                                    |
| [scaffolder](https://github.com/wagoebv/wagoe/blob/main/libs/scaffolder/AGENTS.md)       | Module generation with FC/IS structure, tests, migrations                         |
| [search](https://github.com/wagoebv/wagoe/blob/main/libs/search/AGENTS.md)               | Full-text search                                                                  |
| [storage](https://github.com/wagoebv/wagoe/blob/main/libs/storage/AGENTS.md)             | File storage — local filesystem and S3, image processing                          |
| [tenant](https://github.com/wagoebv/wagoe/blob/main/libs/tenant/AGENTS.md)               | Multi-tenancy with schema-per-tenant isolation                                    |
| [tools](https://github.com/wagoebv/wagoe/blob/main/libs/tools/AGENTS.md)                 | Developer CLI: scaffolding, AI, config, i18n, deployment                          |
| [ui-style](https://github.com/wagoebv/wagoe/blob/main/libs/ui-style/AGENTS.md)           | Shared CSS/JS style bundles — :base, :pilot, :admin-pilot                         |
| [user](https://github.com/wagoebv/wagoe/blob/main/libs/user/AGENTS.md)                   | Authentication, JWT, MFA, user management                                         |
| [wagoe-mcp](https://github.com/wagoebv/wagoe/blob/main/libs/wagoe-mcp/AGENTS.md)         | MCP server (stdio): tool/resource registry + JSON-RPC transport for editor agents |
| [workflow](https://github.com/wagoebv/wagoe/blob/main/libs/workflow/AGENTS.md)           | Workflow orchestration with state machines                                        |
<!-- /gen:modules -->

---

## Adding a New Library to CI

When a new library is added under `libs/`, update **`.github/workflows/ci.yml`** in three places:

1. **Lint step** — add `libs/{name}/src` to the `clojure -M:clj-kondo --lint \` path list.
2. **New test job** — copy an existing `test-*` job block; set `needs: lint` (or add a dependency if the lib depends on another wagoe lib); run `clojure -M:test :{name}`.
3. **`test-summary` job** — add `test-{name}` to the `needs:` array and add an echo line.

Also add the lib's `:id` test suite to `tests.edn` and its source/test paths to the root `deps.edn`.

---

## Ecommerce API Example

A complete reference application demonstrating Wagoe patterns with SQLite, Integrant, Reitit, and Swagger UI.
Source: https://github.com/wagoebv/wagoe-examples/tree/main/ecommerce-api

```bash
# Clone wagoe-examples and run from ecommerce-api/
git clone https://github.com/wagoebv/wagoe-examples
cd wagoe-examples/ecommerce-api
clojure -M:run          # Start server on port 3002
```

**Swagger UI**: `http://localhost:3002/api-docs/`
**OpenAPI spec**: `http://localhost:3002/swagger.json`

### Swagger parameter documentation

The app reads `query-params`/`path-params` directly and does **not** configure reitit coercion middleware. This means Swagger will not auto-generate input fields from route patterns. See **Common Pitfalls #11** for the required `:swagger` parameter pattern.

### SQLite `nil` LIMIT/OFFSET pitfall

Clojure's `{:or {limit 20 offset 0}}` destructuring only fires for **absent** keys. If the caller passes `{:limit nil :offset nil}` (e.g. from `some->` on missing query params), the defaults are ignored and `LIMIT nil` causes `SQLITE_MISMATCH`. Always guard with `(or limit 20)` / `(or offset 0)` at the point where the SQL params vector is assembled.

---

## libs/tools — Developer Tooling

`libs/tools` contains all portable Babashka developer tooling. It lives under `libs/tools/` alongside the other libraries and is consumed by the monorepo via a local path dep in `bb.edn`:

```clojure
;; bb.edn (monorepo)
{:deps {com.wagoe/wagoe-tools {:local/root "libs/tools"}}}
```

> **Note:** the monorepo consumes `libs/tools` through the `:local/root` dep above, not from Clojars — but it *is* published, as `com.wagoe/wagoe-tools`, and `bb deploy --all` includes it along with the other 28. "Dev-only" describes when you use it, not whether it ships.

### Namespaces

| Namespace | Entry point |
|---|---|
| `wagoe.tools.scaffold` | `bb scaffold` |
| `wagoe.tools.ai` | `bb ai` |
| `wagoe.tools.doctor` | `bb doctor` — config validation (6 rule-based checks) |
| `wagoe.tools.setup` | `bb setup` — config setup wizard (interactive / flags / AI) |
| `wagoe.tools.integrate` | `bb scaffold integrate` — guide module integration (Integrant config + wiring) |
| `wagoe.tools.i18n` | `bb i18n:find/scan/missing/unused` |
| `wagoe.tools.admin` | `bb create-admin` |
| `wagoe.tools.deploy` | `bb deploy` (handles all 29 libs) |
| `wagoe.tools.dev` | `bb migrate`, `bb check-links`, `bb smoke-check`, `bb install-hooks` |
| `wagoe.tools.check-fcis` | `bb check:fcis` — FC/IS boundary enforcement (ADR-021) |
| `wagoe.tools.check-tests` | `bb check:placeholder-tests` — placeholder assertion detection |
| `wagoe.tools.check-deps` | `bb check:deps` — dependency direction linting, cycle detection, third-party declaration completeness |
| `wagoe.tools.check-ports` | `bb check:ports` — hexagonal boundary enforcement (ports.clj presence + protocol usage) |
| `wagoe.tools.check-poms` | `bb check:poms` — published-POM dependency completeness (build-shared rewrite + pom-basis usage + publishable deps) |
| `wagoe.tools.parsing` | Shared source-parsing utilities for quality-gate checkers |

See `libs/tools/AGENTS.md` for the full command reference.

---

## Detailed Documentation

**For in-depth information, see:**

- **[Documentation Index](dev-docs/content-readme.adoc)** - Main docs navigation
- **[Architecture Guide](docs/modules/architecture/pages/fc-is.adoc)** - FC/IS patterns, design decisions
- **[Module Scaffolding](libs/scaffolder/README.md)** - Complete scaffolding workflow
- **[MFA Setup Guide](docs/modules/guides/pages/authentication.adoc)** - Multi-factor authentication
- **[Observability Integration](docs/modules/libraries/pages/observability.adoc)** - Custom adapters, configuration
- **[HTTP Interceptors](dev-docs/adr/ADR-010-http-interceptor-architecture.adoc)** - Technical specification
- **[PRD](dev-docs/reference/wagoe-prd.adoc)** - Product vision and requirements

---

**Last Updated**: 2026-04-20
**Version**: 5.2.0 (quality gate improvements, i18n library documentation, wagoe.tools.parsing extraction)
