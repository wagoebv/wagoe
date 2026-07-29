# wagoe-cli

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/org.wagoe/wagoe-cli.svg)](https://clojars.org/org.wagoe/wagoe-cli)

Standalone project bootstrapper and module installer for the Wagoe framework. Creates new projects (`wagoe new`) and adds framework modules to an existing project (`wagoe add`).

## Installation

One-liner (installs the `wagoe` command via [bbin](https://github.com/babashka/bbin)):

```bash
curl -fsSL https://raw.githubusercontent.com/wagoebv/wagoe/main/scripts/install.sh | bash
```

Requires [Babashka](https://babashka.org) and [bbin](https://github.com/babashka/bbin). The artifact is also published to Clojars as `org.wagoe/wagoe-cli`.

## Commands

| Command | Description |
|---------|-------------|
| `wagoe new <project-name>` | Create a new Wagoe project (full FC/IS template: `src/wagoe/config.clj`, `src/<project>/system.clj`, `deps.edn`, `bb.edn`, `.env`, tests, git hooks) |
| `wagoe add <module>` | Add a framework module (payments, tenant, admin, …) to the current project — patches `deps.edn`, config, and the AGENTS.md module table |
| `wagoe list` | List the available modules from the module catalogue |
| `boundary agents update [--check]` | Re-sync the project's `AGENTS.md` installed-modules table with what is actually installed (`--check` verifies without writing) |

## Quick Start

```bash
# Create and enter a new project
wagoe new my-app
cd my-app

# Add modules as you need them
wagoe add payments
wagoe add tenant

# Boot it (JWT_SECRET is generated into .env by `wagoe new`)
source .env
clojure -M:repl
```

Projects created with `wagoe new` are agent-ready out of the box: they ship a
`CLAUDE.md`/`AGENTS.md`, a Wagoe MCP-server wiring (`.mcp.json`), and a
`.claude/` skill that points coding agents at the scaffolder.

## How it works

The CLI renders a set of templates (`resources/wagoe/cli/templates/*.tmpl`)
against a project name, and drives module installation from a shared
**module catalogue** (`resources/wagoe/cli/modules-catalogue.edn`) that lists
each module, its published version, and its dependencies. `wagoe add` uses
that catalogue to wire the right `org.wagoe/wagoe-<module>` coordinate
and config into the target project.

## Development

```bash
# Run the CLI test suite (template rendering, catalogue, new/add)
bb test:wagoe-cli

# Lint
clojure -M:clj-kondo --lint libs/wagoe-cli/src libs/wagoe-cli/test
```

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
