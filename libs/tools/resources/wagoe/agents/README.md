# Agents knowledge source

`knowledge.edn` is the single structured source for Wagoe's agent guardrails.
A deterministic generator (`scripts/agents_gen.clj`, `bb agents:gen`) renders it
into the framework root `AGENTS.md` and the downstream
`libs/wagoe-cli/resources/wagoe/cli/templates/AGENTS.md.tmpl`.

## Keys
- `:fc-is`    — layer/dependency rules (Functional Core / Imperative Shell)
- `:naming`   — kebab/snake/camel boundary conventions
- `:pitfalls` — common mistakes; each tagged `:surfaces #{:framework :downstream}`,
                with an optional `:example` code block
- `:dev-modules` — libs with an AGENTS.md that are NOT installable app modules
                   (dev/build tooling); rendered into the framework module table
                   and used as the module-source validation allowlist

Installable module data comes from
`libs/wagoe-cli/resources/wagoe/cli/modules-catalogue.edn`.

## Commands
- `bb agents:gen`     — regenerate both AGENTS files
- `bb agents:gen --check` / `bb check:agents` — verify in sync + catalogue valid
- `bb test:agents`    — generator unit tests

## Maintainer note
Pitfall numbers (`### N.`) are assigned by render order from `:pitfalls`. Prose that
references a pitfall by number (e.g. "see Common Pitfalls #11") lives OUTSIDE the
generated regions and is NOT drift-checked — if you reorder or insert pitfalls in
`knowledge.edn`, update those hand-written references by hand.

## The MCP server reads this file

`wagoe-mcp` serves `:fc-is`, `:naming` and `:pitfalls` as the `wagoe://conventions`
resource. It reads them off the classpath — this file ships in the `wagoe-tools`
jar — so an editor agent in any project gets the same rules the framework
generates `AGENTS.md` from (BOU-320). A project that wants different rules puts
its own `resources/agents/knowledge.edn` in place; that one wins.

The tool names an earlier draft of this file listed (`list_modules`,
`get_fc_is_rules`, `naming_rule`, `lookup_pitfall`) were never built. The server
exposes this as a resource, not as four tools.
