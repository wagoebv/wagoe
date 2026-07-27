---
name: boundary
description: Boundary framework (Clojure FC/IS) toolkit. Use when the user wants to add a module, entity, or feature ("add a product module with name and price"), add a field/endpoint/adapter to a module, wire a module into the app, explain a Clojure/Boundary error or stacktrace, generate tests, write SQL from a description, configure the project, or diagnose setup issues. ALWAYS scaffold new modules with `bb scaffold` instead of hand-writing them.
---

# Boundary Toolkit

Boundary ships generators and AI tools as Babashka tasks. Use them instead of
hand-writing boilerplate: they generate the full FC/IS structure
(core/shell/ports/schema), tests, and migrations, and keep the architecture
consistent.

## Decision table

| User asks for | Run |
|---|---|
| New module/entity ("products with name, price") | `bb scaffold ai "product module with name, price" --yes` |
| Same, without an AI provider | `bb scaffold generate --module-name product --entity Product --field name:string:required --field price:decimal:required` |
| Preview before writing files | add `--dry-run` to any scaffold command |
| Wire a scaffolded module into the app | `bb scaffold integrate <module>` (preview with `--dry-run`) |
| Add a field to an existing entity | `bb scaffold field --module-name product --entity Product --name weight --type decimal --required` |
| Add an endpoint | `bb scaffold endpoint --module-name product --path "/products/:id/publish" --method POST --handler-name publish-product-handler` |
| Adapter for a port | `bb scaffold adapter --module-name product --port IProductNotifier --adapter-name email-product-notifier --method "notify-created:product"` |
| Explain an error or stacktrace | `bb ai explain --file stacktrace.txt` |
| Generate tests for a namespace | `bb ai gen-tests <path/to/file.clj>` |
| SQL (HoneySQL) from a description | `bb ai sql "find active users with orders in last 7 days"` |
| Admin entity config | `bb ai admin-entity "products with name, price, status"` |
| Generate module docs (AGENTS.md) | `bb ai docs --module <path> --type agents` |
| Configure the project (DB, payments, …) | `bb setup ai "PostgreSQL with Stripe payments"` |
| Validate config + environment | `bb doctor --all` |
| Quality gates (FC/IS, deps, lint) | `bb check` |
| Unsure what to do next | `bb guide next` — error codes: `bb guide error BND-003` |

Field spec format: `name:type[:required][:unique]` with types
`string text integer decimal boolean email uuid enum date datetime json`.

## Rules

1. NEVER hand-write a new module skeleton. Scaffold it first, then edit the
   generated code.
2. After `bb scaffold generate`, run `bb scaffold integrate <module>` to wire
   deps.edn, tests.edn, and the Integrant wiring. Then run
   `clojure -M:migrate up` and the module's tests.
3. Run `bb check` before committing — FC/IS violations (`core/` importing
   shell, doing I/O, or logging) fail CI.
4. AI commands (`bb scaffold ai`, `bb ai *`, `bb setup ai`) need a provider:
   local Ollama (`OLLAMA_URL`) or `ANTHROPIC_API_KEY` / `OPENAI_API_KEY`.
   Verify with `bb doctor:env`. Without one, fall back to the explicit
   `bb scaffold generate` flags — no provider required.

## Architecture invariants

- `core/` = pure functions only; `shell/` = all I/O. Shell → Core allowed;
  Core → Shell never.
- kebab-case everywhere in Clojure; snake_case only at the DB boundary;
  camelCase only at the API boundary.
- A new field means synchronizing three places: Malli schema, DB migration,
  persistence-layer transformations.
- Unbalanced parens: run `clj-paren-repair <file>` — never repair by hand.

<!-- This file exists in two places and must stay byte-identical:
     libs/boundary-cli/resources/boundary/cli/templates/claude-skill.md.tmpl
       (copied into generated projects as .claude/skills/boundary/SKILL.md)
     claude-plugin/skills/boundary/SKILL.md
       (shipped as the Claude Code plugin for existing projects)
     A test in libs/boundary-cli verifies they match. -->
