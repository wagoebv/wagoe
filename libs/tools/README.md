# wagoe/tools

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()

Developer Babashka tooling for the Wagoe framework — scaffolding, AI assistance,
config management, i18n, deployment, and the CI quality gates.

This library is **dev-only** in the sense that applications do not depend on it at
runtime — but it **is published to Clojars**, as `com.wagoe/wagoe-tools`, in
lockstep with the rest of the suite. Inside this monorepo it is consumed via a
`:local/root` dependency in the root `bb.edn`, so every `bb ...` task below is
available out of the box:

```clojure
;; bb.edn
{:deps {wagoe-tools {:local/root "libs/tools"}}}
```

## What's inside

| Namespace | `bb` command(s) | Purpose |
|-----------|-----------------|---------|
| `wagoe.tools.scaffold` | `bb scaffold`, `bb scaffold ai`, `bb scaffold generate/field/endpoint/adapter` | Interactive scaffolding wizards + AI passthrough |
| `wagoe.tools.integrate` | `bb scaffold integrate`, `bb scaffold:integrate` | Wire a scaffolded module into deps/tests/wiring |
| `wagoe.tools.ai` | `bb ai explain/gen-tests/sql/docs/admin-entity` | Framework-aware AI CLI |
| `wagoe.tools.doctor` | `bb doctor` | Rule-based config validation (6 checks) |
| `wagoe.tools.setup` | `bb setup`, `bb setup ai` | Config setup wizard (interactive / flags / AI) |
| `wagoe.tools.admin` | `bb create-admin` | Create the first admin user |
| `wagoe.tools.deploy` | `bb deploy` | Publish the 30 Clojars artifacts (tools itself included) |
| `wagoe.tools.dev` | `bb migrate`, `bb check-links`, `bb smoke-check`, `bb install-hooks` | Dev utilities |
| `wagoe.tools.i18n` | `bb i18n:find/scan/missing/unused` | Translation catalogue management |

### Quality gates

These CI checks live in `libs/tools` too (`check:fcis` + `check:ports` also run in the pre-commit hook):

| Command | What it enforces |
|---------|------------------|
| `bb check:fcis` | Core namespaces must not import shell/IO/logging/DB, throw, or hold mutable state |
| `bb check:ports` | Every module defines `ports.clj`; shell/web must not bypass another module's protocols |
| `bb check:deps` | Dependency direction + cycle detection between libraries |
| `bb check:poms` | Published POMs carry their inter-Wagoe deps |
| `bb check:placeholder-tests` | Detects `(is true)` placeholder assertions |

## Usage

```bash
# Scaffold a module from a natural-language description, then wire it in
bb scaffold ai "product module with name, price" --yes
bb scaffold integrate product

# Validate config before running
bb doctor --env all --ci

# AI helpers
bb ai explain --file stacktrace.txt
bb ai sql "find active users with orders in last 7 days"

# i18n catalogue checks
bb i18n:scan
bb i18n:missing

# Migrations + first admin user
bb migrate up
bb create-admin --email admin@app.com --name "Admin"
```

## Documentation

- Full command reference: [`AGENTS.md`](./AGENTS.md)
- Docs site page: [`docs/modules/libraries/pages/tools.adoc`](../../docs/modules/libraries/pages/tools.adoc)

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
