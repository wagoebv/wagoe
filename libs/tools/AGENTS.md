# wagoe-tools

**Location:** `libs/tools`
**Version:** `1.0.0-beta-2`
**Distribution:** Part of the Wagoe monorepo — not published to Clojars. Wired directly into `bb.edn` as a local dependency.

Developer tooling for the Wagoe framework: scaffolding, AI assistance, config management, i18n management, deployment, and development utilities — available out of the box in every Wagoe project.

---

## Getting started

`libs/tools` is included in the monorepo and wired into the root `bb.edn` as a local dependency. No Clojars dependency needed — the tasks are available out of the box in every Wagoe project.

The root `bb.edn` wiring looks like this:

```clojure
{:deps {wagoe-tools {:local/root "libs/tools"}}
 :tasks
 {:requires ([wagoe.tools.scaffold  :as scaffold]
             [wagoe.tools.ai        :as ai]
             [wagoe.tools.i18n      :as i18n]
             [wagoe.tools.admin     :as admin]
             [wagoe.tools.deploy    :as deploy]
             [wagoe.tools.dev       :as dev]
             [wagoe.tools.doctor    :as doctor]
             [wagoe.tools.setup     :as setup]
             [wagoe.tools.integrate :as integrate])

  scaffold     {:task (apply scaffold/-main *command-line-args*)}
  ai           {:task (apply ai/-main *command-line-args*)}
  doctor       {:task (apply doctor/-main *command-line-args*)}
  setup        {:task (apply setup/-main *command-line-args*)}
  scaffold:integrate {:task (apply integrate/-main *command-line-args*)}
  create-admin {:task (apply admin/-main *command-line-args*)}
  deploy       {:task (apply deploy/-main *command-line-args*)}
  migrate      {:task (apply dev/migrate *command-line-args*)}
  check-links  {:task (dev/check-links)}
  smoke-check  {:task (dev/smoke-check)}
  install-hooks {:task (dev/install-hooks)}
  i18n:find    {:task (apply i18n/-main "find" *command-line-args*)}
  i18n:scan    {:task (i18n/-main "scan")}
  i18n:missing {:task (i18n/-main "missing")}
  i18n:unused  {:task (i18n/-main "unused")}}}
```

---

## Command reference

### `bb doctor` — Config Doctor

Validates your Wagoe config files for common mistakes. Rule-based (no AI needed) — runs 6 checks against your `config.edn` and project files.

```bash
bb doctor                    # Check dev environment (default)
bb doctor --env prod         # Check a specific environment
bb doctor --env all          # Check all environments (dev, test, acc, prod)
bb doctor --ci               # Exit non-zero on any error (for CI pipelines)
```

**Checks performed:**

| Check | Level | What it catches |
|-------|-------|-----------------|
| `env-refs` | error | `#env VAR` references in the `:active` section that have no `#or` fallback and are not set in the environment |
| `providers` | error | Unknown `:provider` values (e.g. `:provider :reddis` instead of `:redis`) |
| `jwt-secret` | error | `JWT_SECRET` not set when the user module is active |
| `admin-parity` | warn | Admin entity EDN files that exist in `dev/admin/` but not `test/admin/` (or vice versa) |
| `prod-placeholders` | error | Placeholder values like `company.com`, `example.com`, `TODO` in prod/acc configs |
| `wiring-requires` | warn | Active Integrant modules missing their `module-wiring` require in `wiring.clj` |

**Example output:**

```
Wagoe Config Doctor — dev

  ✓ env-refs             All #env references resolved or have defaults
  ✗ jwt-secret           JWT_SECRET not set (required by user module)
                         Fix: export JWT_SECRET="your-32-char-secret"
  ✓ providers            All provider values are known
  ⚠ admin-parity         dev/admin/users.edn has :split-table-update, test does not
  ✓ prod-placeholders    Placeholder check skipped (non-production env)
  ✓ wiring-requires      All active modules wired

Summary: 4 passed, 1 warning, 1 error
```

**Known valid providers** (used by the `providers` check):

```clojure
{:wagoe/logging          #{:no-op :stdout :slf4j :file}
 :wagoe/metrics          #{:no-op :prometheus :datadog-statsd}
 :wagoe/error-reporting  #{:no-op :sentry}
 :wagoe/payment-provider #{:mock :mollie :stripe}
 :wagoe/ai-service       #{:ollama :anthropic :openai :no-op}
 :wagoe/cache            #{:redis :in-memory}}
```

**CI integration example** (GitHub Actions):

```yaml
- name: Config validation
  run: bb doctor --ci
```

---

### `bb setup` — Config Setup Wizard

Generates `config.edn` for dev and test environments plus a `.env.example` file. Three modes: interactive wizard, CLI flags, or AI-powered natural language.

```bash
# Interactive wizard — guided prompts for database, AI, payments, cache, etc.
bb setup

# Non-interactive flags — useful for CI or scripting
bb setup --database postgresql --payment stripe --ai-provider anthropic --cache redis

# AI-powered — describe your setup in natural language
bb setup ai "PostgreSQL with Stripe payments and Redis caching"

# Minimal setup
bb setup --database h2 --payment none --ai-provider none
```

**What it generates:**

| File | Purpose |
|------|---------|
| `resources/conf/dev/config.edn` | Full dev config with `:active`/`:inactive` sections |
| `resources/conf/test/config.edn` | Test config with H2 in-memory DB and mock/no-op providers |
| `.env.example` | All required env vars with comments and sensible defaults |

**Available options:**

| Option | Values | Default |
|--------|--------|---------|
| `--project-name` | any kebab-case string | `my-app` |
| `--database` | `postgresql`, `sqlite`, `h2`, `mysql` | `postgresql` |
| `--ai-provider` | `ollama`, `anthropic`, `openai`, `none` | `none` |
| `--payment` | `none`, `mock`, `stripe`, `mollie` | `none` |
| `--cache` | `none`, `redis`, `in-memory` | `none` |
| `--email` | `none`, `smtp` | `none` |
| `--admin-ui` | `true`, `false` | `true` |

**Example workflow** — setting up a new project:

```bash
# 1. Generate config files
bb setup --database postgresql --payment mock --ai-provider ollama

# 2. Copy and fill in environment variables
cp .env.example .env
# Edit .env with your values

# 3. Verify the generated config
bb doctor

# 4. Run migrations
bb migrate up

# 5. Start the system
clojure -M:repl-clj
```

**AI mode** delegates to `clojure -M -m wagoe.ai.shell.cli-entry setup-parse` to parse the description, then renders templates from the parsed spec. Falls back to interactive mode if no AI provider is available.

---

### `bb scaffold` — Interactive scaffolding wizard

```bash
bb scaffold                          # Show help
bb scaffold generate                 # Interactive module generation wizard
bb scaffold new                      # Interactive new project wizard
bb scaffold field                    # Interactive add-field wizard
bb scaffold endpoint                 # Interactive add-endpoint wizard
bb scaffold adapter                  # Interactive add-adapter wizard
bb scaffold ai "<description>"       # AI-powered module from NL description
bb scaffold ai "<description>" --yes # Non-interactive (skip confirmation)
bb scaffold integrate <module>       # Wire scaffolded module into project (see below)

# Non-interactive passthrough (pass args directly to scaffolder CLI):
bb scaffold generate --module-name foo --entity Foo --field name:string:required
bb scaffold field --module-name foo --entity Foo --name price --type decimal
```

---

### `bb scaffold integrate` — Module Integration

After scaffolding a new module with `bb scaffold generate`, this tool locates it and guides the remaining wiring. Purely rule-based — no AI needed.

```bash
# Guide integration of the "product" module
bb scaffold integrate product

# Module scaffolded under a custom base namespace
bb scaffold integrate product --base-ns myapp

# Preview only
bb scaffold integrate product --dry-run

# Also available via the scaffold:integrate task
bb scaffold:integrate product
```

**What it does:** `bb scaffold generate [--base-ns NS]` writes a module to `src/<base-ns-path>/<module>/` (default base-ns `wagoe`). Because `src`/`test` are already on the project's paths, the module is **already on the classpath and covered by the standard test suites** — there is nothing to patch into `deps.edn`/`tests.edn`. What remains is registering the module's Integrant components, which `integrate` guides:

| Step | Where | What |
|------|-------|------|
| Locate module | `src/<base-ns-path>/<module>/` | Discovers the module + whether it has HTTP routes / module-wiring |
| Print config snippet | stdout | An Integrant config template to paste into `config.edn` |
| Print wiring require | stdout | The `[<base-ns>.<module>.shell.module-wiring]` require to add to your app's config namespace (when the module ships one) |

**Example output:**

```
Wagoe Module Integration — product

Discovered: src/wagoe/product
  Namespace: wagoe.product.*
  Tests:     test/wagoe/product
  HTTP:      yes
  Wiring:    no

✓ On the classpath — src/ and test/ are already on the project paths;
  the module's tests run with clojure -M:test (no deps.edn/tests.edn changes).

Register the module's Integrant components:
  1. Add config to resources/conf/dev/config.edn (and test):
     (snippet shown)
  2. This module has no shell/module_wiring.clj yet — add one to register its
     Integrant keys, then require it from your app config.
  3. Verify: clojure -M:test
```

**Why config.edn modification is manual:** Config files use Aero reader tags (`#env`, `#or`, `#include`, `#merge`) that aren't standard EDN. Programmatic modification risks corrupting these tags, so the tool prints a snippet for the developer to insert.

**Complete scaffolding workflow:**

```bash
# 1. Generate the module
bb scaffold generate --module-name product --entity Product \
  --field name:string:required --field price:decimal:required

# 2. Wire it into the project
bb scaffold integrate product

# 3. Add config (manually paste the printed snippet into config.edn)
# 4. Run migrations
bb migrate up

# 5. Run tests
clojure -M:test
```

---

### `bb ai` — Framework-aware AI tooling

```bash
bb ai                                        # Show help
bb ai explain                                # Explain error from stdin
bb ai explain --file stacktrace.txt          # Explain error from file
bb ai gen-tests libs/user/src/...clj         # Generate test namespace
bb ai gen-tests libs/user/src/...clj -o out  # Write tests to file
bb ai sql "find active users last 7 days"    # HoneySQL from NL description
bb ai docs --module libs/user                # Generate all docs
bb ai docs --module libs/user --type agents  # Generate AGENTS.md only
bb ai admin-entity "<description>"           # Generate admin entity EDN config
```

Provider selection (first matching env var wins):
- `ANTHROPIC_API_KEY` → Anthropic (Claude)
- `OPENAI_API_KEY` → OpenAI (GPT)
- `OLLAMA_URL` → Ollama (local, default `http://localhost:11434`)

#### `bb ai admin-entity` — Admin Entity Generator

Generates admin entity EDN configuration files from a natural language description. Uses AI to understand your entity and produce a complete config that matches the Wagoe admin UI format.

```bash
# Generate an admin entity config
bb ai admin-entity "products with name, price, status (active/archived), and category"

# Skip confirmation and write immediately
bb ai admin-entity "invoices with number, customer, total, status, due-date" --yes
```

**What it generates:**

Writes EDN files to both `resources/conf/dev/admin/<entity>.edn` and `resources/conf/test/admin/<entity>.edn`.

**Example generated EDN** (for `bb ai admin-entity "products with name, price, status"`):

```edn
{:products
 {:label           "Products"
  :table-name      :products
  :list-fields     [:name :price :status :created-at]
  :search-fields   [:name]
  :hide-fields     #{:deleted-at}
  :readonly-fields #{:id :created-at :updated-at}
  :fields
  {:status     {:type :enum :label "Status"
                :options [[:active "Active"] [:archived "Archived"]]
                :filterable true}
   :price      {:type :decimal :label "Price"}
   :created-at {:type :instant :label "Created" :filterable true}}
  :field-order [:name :price :status :created-at :updated-at]
  :field-groups
  [{:id :identity :label "Identity" :fields [:name]}
   {:id :details  :label "Details"  :fields [:price :status]}]}}
```

**After generation, register the entity:**

```bash
# 1. Add to the allowlist in config.edn:
#    :entity-discovery {:allowlist #{:users :tenants :products}}
# 2. Add the #include:
#    :entities #merge [#include "admin/users.edn"
#                      #include "admin/tenants.edn"
#                      #include "admin/products.edn"]
```

---

### `bb create-admin` — Create first admin user

```bash
bb create-admin                              # Interactive wizard
bb create-admin --env prod                   # Use production config
bb create-admin --email admin@app.com --name "Admin"  # Skip prompts
bb create-admin --dir examples/ecommerce-api # Target a sub-project
```

Run database migrations first: `clojure -M:migrate up`

### `bb deploy` — Deploy to Clojars

Deploys the 22 published Wagoe libraries to Clojars. `wagoe-tools` itself is not published — it is a monorepo-internal tool.

```bash
bb deploy --help                    # Show help
bb deploy --all                     # Deploy all 22 published artifacts
bb deploy --missing                 # Deploy only unpublished artifacts
bb deploy core platform user        # Deploy specific libraries
```

Required environment variables:
- `CLOJARS_USERNAME` — your Clojars username
- `CLOJARS_PASSWORD` — your Clojars deploy token

Important release note:
- `bb deploy --all` publishes every artifact listed in `wagoe.tools.deploy/all-libs`. `wagoe-tools` is excluded from this list.
- A Git tag only triggers the GitHub Actions workflow; actual artifact versions still come from each artifact's `build.clj`.
- For a tagged full release, bump every included artifact to an unpublished version first, otherwise the workflow will fail on the first duplicate version.

### `bb migrate` — Database migrations

Thin Babashka wrapper around the standard `clojure -M:migrate` CLI, so projects
can use a consistent `bb ...` developer workflow without introducing a second
migration implementation.

```bash
bb migrate up
bb migrate status
bb migrate rollback
bb migrate create add-search-index
```

### `bb check-links` — Validate AGENTS.md links

```bash
bb check-links    # Check root AGENTS.md + all libs/*/AGENTS.md for broken local links
```

Exits non-zero if broken links are found. Skips `http://`, `https://`, `mailto:`, and `#anchor` links.

### `bb smoke-check` — Verify tool entrypoints

```bash
bb smoke-check    # Verify deps.edn aliases, migrate CLI, test runner, docs lint
```

Checks that required aliases (`:migrate`, `:test`, `:repl-clj`, `:docs-lint`) exist in `deps.edn` and that key CLIs respond.

### `bb install-hooks` — Configure git hooks

```bash
bb install-hooks  # Sets git config core.hooksPath to .githooks
```

### `bb i18n:*` — Internationalisation tooling

```bash
bb i18n:find "Sign in"      # Find key by substring in catalogue + grep codebase
bb i18n:find :user/sign-in  # Find by exact keyword
bb i18n:scan                # Scan core/ui.clj files for unexternalised strings (CI gate)
bb i18n:missing             # Report keys in en.edn missing from other locales
bb i18n:unused              # Report catalogue keys not referenced in source
```

Translation files live in `libs/i18n/resources/wagoe/i18n/translations/`.

---

## Namespaces

| Namespace | Purpose |
|---|---|
| `wagoe.tools.scaffold` | Interactive scaffolding wizards + AI passthrough + integrate dispatch |
| `wagoe.tools.ai` | AI CLI frontend (explain, gen-tests, sql, docs, admin-entity) |
| `wagoe.tools.doctor` | Config Doctor — rule-based config validation (6 checks) |
| `wagoe.tools.setup` | Config Setup Wizard — interactive + template-based config generation |
| `wagoe.tools.integrate` | Module Integration — locate scaffolded modules under src/ and guide Integrant config + wiring |
| `wagoe.tools.admin_entity` | Admin Entity Generator — Babashka wrapper for AI admin entity generation |
| `wagoe.tools.i18n` | i18n catalogue management (find/scan/missing/unused) |
| `wagoe.tools.admin` | First admin user creation wizard |
| `wagoe.tools.deploy` | Clojars deployment for all 22 published Wagoe artifacts |
| `wagoe.tools.dev` | migrate + check-links + smoke-check + install-hooks |

---

## Releasing wagoe-tools

`wagoe-tools` is not published to Clojars. It is distributed as part of the Wagoe monorepo. To update it, commit and push changes to `libs/tools` — consumers pick up changes by pulling the repository.
