# Agents knowledge source

`knowledge.edn` is the single structured source for Boundary's agent guardrails.
A deterministic generator (`scripts/agents_gen.clj`, `bb agents:gen`) renders it
into the framework root `AGENTS.md` and the downstream
`libs/wagoe-cli/resources/boundary/cli/templates/AGENTS.md.tmpl`.

## Keys
- `:fc-is`    — layer/dependency rules (Functional Core / Imperative Shell)
- `:naming`   — kebab/snake/camel boundary conventions
- `:pitfalls` — common mistakes; each tagged `:surfaces #{:framework :downstream}`,
                with an optional `:example` code block
- `:dev-modules` — libs with an AGENTS.md that are NOT installable app modules
                   (dev/build tooling); rendered into the framework module table
                   and used as the module-source validation allowlist

Installable module data comes from
`libs/wagoe-cli/resources/boundary/cli/modules-catalogue.edn`.

## Commands
- `bb agents:gen`     — regenerate both AGENTS files
- `bb agents:gen --check` / `bb check:agents` — verify in sync + catalogue valid
- `bb test:agents`    — generator unit tests

## Maintainer note
Pitfall numbers (`### N.`) are assigned by render order from `:pitfalls`. Prose that
references a pitfall by number (e.g. "see Common Pitfalls #11") lives OUTSIDE the
generated regions and is NOT drift-checked — if you reorder or insert pitfalls in
`knowledge.edn`, update those hand-written references by hand.

## Phase 2 — MCP server data contract
A future Boundary MCP guardrails server serves this same data, no schema change:

| MCP tool         | Source                            |
|------------------|-----------------------------------|
| `list_modules`   | `modules-catalogue.edn :modules`  |
| `get_fc_is_rules`| `knowledge.edn :fc-is`            |
| `naming_rule`    | `knowledge.edn :naming`           |
| `lookup_pitfall` | `knowledge.edn :pitfalls`         |
