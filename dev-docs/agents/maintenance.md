# Agent documentation and library maintenance

Read when directed by [AGENTS.md](../../AGENTS.md). Paths in commands and inline code are relative to the repository root.

## Maintenance Notes

- Keep command examples in one place (`Quick Reference`); avoid duplicate command blocks elsewhere in this file.
- Library-specific troubleshooting and deep implementation notes belong in `libs/*/AGENTS.md`.
- Use `bb check-links` after editing AGENTS documentation.
- Install local pre-commit hooks once per clone: `bb install-hooks`.

---

## AGENTS.md generation

FC/IS and naming rules in the root AGENTS.md, pitfalls in `pitfalls.md`, and the module table here —
and the FC/IS / naming / pitfalls sections of the downstream
`libs/wagoe-cli/resources/wagoe/cli/templates/AGENTS.md.tmpl` — are
generated from `libs/tools/resources/wagoe/agents/knowledge.edn` (+ `modules-catalogue.edn`
for the module table). The generator lives at `scripts/agents_gen.clj` and reads
both from the classpath — which is also how the MCP server reads the knowledge
base in a project that installed it (BOU-320).

- Regenerate:  `bb agents:gen`
- Verify sync: `bb check:agents`  (also part of `bb check` + CI)
- Add/edit a pitfall, naming rule, or FC/IS rule: edit `libs/tools/resources/wagoe/agents/knowledge.edn`,
  then run `bb agents:gen`.
- Add a library: add it to `modules-catalogue.edn` (or, for dev-only tooling not
  published as an app module, to `:dev-modules` in `knowledge.edn`), then `bb agents:gen`.
- **Regenerate before publishing `wagoe-cli`** so downstream `wagoe new`
  projects ship the current template.

The per-module AI doc generator (`bb ai docs --module libs/<x> --type agents`) is
separate and unchanged.

---

## Library-Specific Guides

<!-- gen:modules -->
| Module                                                                                   | Description                                                                                         |
|------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| [admin](https://github.com/wagoebv/wagoe/blob/main/libs/admin/AGENTS.md)                 | Admin UI with entity config, HTMX forms                                                             |
| [ai](https://github.com/wagoebv/wagoe/blob/main/libs/ai/AGENTS.md)                       | Multi-provider AI — Ollama, Anthropic Claude, OpenAI                                                |
| [audience](https://github.com/wagoebv/wagoe/blob/main/libs/audience/AGENTS.md)           | Rule-based audience segmentation with SQL + predicate pipeline                                      |
| [cache](https://github.com/wagoebv/wagoe/blob/main/libs/cache/AGENTS.md)                 | Distributed caching — Redis or in-memory, TTL, atomic ops                                           |
| [calendar](https://github.com/wagoebv/wagoe/blob/main/libs/calendar/AGENTS.md)           | iCal, RRULE recurrence, conflict detection, Hiccup UI                                               |
| [config](https://github.com/wagoebv/wagoe/blob/main/libs/config/AGENTS.md)               | Aero config loading and the typed accessors libraries read through                                  |
| [core](https://github.com/wagoebv/wagoe/blob/main/libs/core/AGENTS.md)                   | Pure validation, case conversion, interceptor pipeline, feature flags                               |
| [devtools](https://github.com/wagoebv/wagoe/blob/main/libs/devtools/AGENTS.md)           | Dev-only: error pipeline with WGE codes, REPL helpers, dashboard component                          |
| [email](https://github.com/wagoebv/wagoe/blob/main/libs/email/AGENTS.md)                 | SMTP email sending, async and queued modes                                                          |
| [events](https://github.com/wagoebv/wagoe/blob/main/libs/events/AGENTS.md)               | Event bus for cross-process, asynchronous module communication — in-memory and Redis Streams        |
| [external](https://github.com/wagoebv/wagoe/blob/main/libs/external/AGENTS.md)           | External service adapters — Twilio, SMTP, IMAP, FTPS, SOAP                                          |
| [geo](https://github.com/wagoebv/wagoe/blob/main/libs/geo/AGENTS.md)                     | Multi-provider geocoding (OSM/Google/Mapbox), Haversine distance                                    |
| [i18n](https://github.com/wagoebv/wagoe/blob/main/libs/i18n/AGENTS.md)                   | Marker-based i18n, translation catalogues, locale chains                                            |
| [jobs](https://github.com/wagoebv/wagoe/blob/main/libs/jobs/AGENTS.md)                   | Background job processing with retry logic                                                          |
| [observability](https://github.com/wagoebv/wagoe/blob/main/libs/observability/AGENTS.md) | Interceptor-based metrics, logging, and error reporting                                             |
| [payments](https://github.com/wagoebv/wagoe/blob/main/libs/payments/AGENTS.md)           | PSP abstraction — Mollie, Stripe, Mock checkout and webhook verification                            |
| [platform](https://github.com/wagoebv/wagoe/blob/main/libs/platform/AGENTS.md)           | HTTP server, Reitit router, Ring middleware pipeline                                                |
| [platform-sql](https://github.com/wagoebv/wagoe/blob/main/libs/platform-sql/AGENTS.md)   | JDBC adapters, migrations, seeding and leases, without the HTTP stack                               |
| [push](https://github.com/wagoebv/wagoe/blob/main/libs/push/AGENTS.md)                   | Multi-platform push notifications — FCM (Firebase) + APNs (Apple)                                   |
| [realtime](https://github.com/wagoebv/wagoe/blob/main/libs/realtime/AGENTS.md)           | WebSocket pub/sub messaging                                                                         |
| [reports](https://github.com/wagoebv/wagoe/blob/main/libs/reports/AGENTS.md)             | PDF/CSV export and scheduled report generation                                                      |
| [scaffolder](https://github.com/wagoebv/wagoe/blob/main/libs/scaffolder/AGENTS.md)       | Module generation with FC/IS structure, tests, migrations                                           |
| [search](https://github.com/wagoebv/wagoe/blob/main/libs/search/AGENTS.md)               | Full-text search                                                                                    |
| [storage](https://github.com/wagoebv/wagoe/blob/main/libs/storage/AGENTS.md)             | File storage — local filesystem and S3, image processing                                            |
| [tenant](https://github.com/wagoebv/wagoe/blob/main/libs/tenant/AGENTS.md)               | Multi-tenancy with schema-per-tenant isolation                                                      |
| [test](https://github.com/wagoebv/wagoe/blob/main/libs/test/README.md)                   | Test kit for apps: test system, Ring client with session + CSRF, deterministic jobs/events/realtime |
| [tools](https://github.com/wagoebv/wagoe/blob/main/libs/tools/AGENTS.md)                 | Developer CLI: scaffolding, AI, config, i18n, deployment                                            |
| [ui-style](https://github.com/wagoebv/wagoe/blob/main/libs/ui-style/AGENTS.md)           | daisyUI/Tailwind CSS and JS bundles — :pilot (default), :admin-pilot                                |
| [user](https://github.com/wagoebv/wagoe/blob/main/libs/user/AGENTS.md)                   | Authentication, JWT, MFA, user management                                                           |
| [wagoe-mcp](https://github.com/wagoebv/wagoe/blob/main/libs/wagoe-mcp/AGENTS.md)         | MCP server (stdio): tool/resource registry + JSON-RPC transport for editor agents                   |
| [webhooks](https://github.com/wagoebv/wagoe/blob/main/libs/webhooks/AGENTS.md)           | Outbound webhooks: signed, retried, per-tenant delivery of bus events                               |
| [workflow](https://github.com/wagoebv/wagoe/blob/main/libs/workflow/AGENTS.md)           | Workflow orchestration with state machines                                                          |
<!-- /gen:modules -->

---

## Adding a New Library to CI

When a new library is added under `libs/`, update **`.github/workflows/ci.yml`** in three places:

1. **Lint step** — add `libs/{name}/src` to the `clojure -M:clj-kondo --lint \` path list.
2. **New test job** — copy an existing `test-*` job block; set `needs: lint` (or add a dependency if the lib depends on another wagoe lib); run `clojure -M:test :{name}`.
3. **`test-summary` job** — add `test-{name}` to the `needs:` array and add an echo line.

Also add the lib's `:id` test suite to `tests.edn` and its source/test paths to the root `deps.edn`.

---

## libs/tools — Developer Tooling

`libs/tools` contains all portable Babashka developer tooling. It lives under `libs/tools/` alongside the other libraries and is put on the monorepo's Babashka classpath through `:paths` in `bb.edn`:

```clojure
;; bb.edn (monorepo)
{:paths ["libs/tools/src" "libs/tools/resources" ...]}
```

> **Note:** the monorepo reads `libs/tools` from source, not from Clojars — but it *is* published, as `com.wagoe/wagoe-tools`, and `bb deploy --all` includes it along with the other 33. "Dev-only" describes when you use it, not whether it ships.

### Namespaces

| Namespace | Entry point |
|---|---|
| `wagoe.tools.scaffold` | `bb scaffold` |
| `wagoe.tools.ai` | `bb ai` |
| `wagoe.tools.doctor` | `bb doctor` — config validation (`bb doctor --help` lists the checks; `--edn` for `wagoe doctor`) |
| `wagoe.tools.report` | the result shape the three diagnostics share, and its two renderers |
| `wagoe.tools.setup` | `bb setup` — config setup wizard (interactive / flags / AI) |
| `wagoe.tools.integrate` | `bb scaffold integrate` — guide module integration (Integrant config + wiring) |
| `wagoe.tools.i18n` | `bb i18n:find/scan/missing/unused` |
| `wagoe.tools.admin` | `bb create-admin` |
| `wagoe.tools.deploy` | `bb deploy` (handles all 34 libs) |
| `wagoe.tools.dev` | `bb migrate`, `bb check-links`, `bb smoke-check`, `bb install-hooks` |
| `wagoe.tools.check-fcis` | `bb check:fcis` — FC/IS boundary enforcement (ADR-021) |
| `wagoe.tools.check-tests` | `bb check:placeholder-tests` — placeholder assertion detection |
| `wagoe.tools.check-deps` | `bb check:deps` — declared wagoe deps = used ones, cycle detection, third-party declaration completeness |
| `wagoe.tools.check-ports` | `bb check:ports` — hexagonal boundary enforcement (ports.clj presence + protocol usage) |
| `wagoe.tools.check-poms` | `bb check:poms` — published-POM dependency completeness (build-shared rewrite + pom-basis usage + publishable deps) |
| `wagoe.tools.check-roadmap` | `bb check:roadmap` — one roadmap, and it may not plan what `scaling.adoc` marks shipped |
| `wagoe.tools.check-branch-protection` | `bb check:branch-protection` — the one required status check must cover every job |
| `wagoe.tools.parsing` | Shared source-parsing utilities for quality-gate checkers |

See `libs/tools/AGENTS.md` for the full command reference.

---

## Detailed Documentation

**For in-depth information, see:**

- **[Documentation Index](../../dev-docs/content-readme.adoc)** - Main docs navigation
- **[Architecture Guide](../../docs/modules/architecture/pages/fc-is.adoc)** - FC/IS patterns, design decisions
- **[Module Scaffolding](../../libs/scaffolder/README.md)** - Complete scaffolding workflow
- **[MFA Setup Guide](../../docs/modules/guides/pages/authentication.adoc)** - Multi-factor authentication
- **[Observability Integration](../../docs/modules/libraries/pages/observability.adoc)** - Custom adapters, configuration
- **[HTTP Interceptors](../../dev-docs/adr/ADR-010-http-interceptor-architecture.adoc)** - Technical specification
- **[PRD](../../dev-docs/reference/wagoe-prd.adoc)** - Product vision and requirements

---
