# Wagoe Claude Code Plugin

Makes agentic CLIs (Claude Code and compatible tools) aware of Wagoe's
scaffolder and AI tooling, so the agent reaches for `bb scaffold` / `bb ai`
instead of hand-writing module boilerplate.

New projects created with `wagoe new` already include the `wagoe` skill at
`.claude/skills/wagoe/SKILL.md`. The plugin carries that same skill for
**existing** projects, plus `wagoe-setup`, which cannot ship in a project
template because it runs before the project exists.

## Install

In Claude Code:

```
/plugin marketplace add wagoebv/wagoe
/plugin install wagoe@wagoe
```

## What it provides

Two skills, split by whether a project exists yet.

### `wagoe` — working inside a project

- A decision table mapping user requests ("add a product module") to the
  right `bb scaffold` / `bb ai` / `bb setup` / `bb doctor` / `bb guide` command
- Scaffold-first rules (never hand-write a module skeleton)
- FC/IS architecture invariants (core purity, case conventions, field sync)

### `wagoe-setup` — creating one

Nothing to a running app: checks the toolchain and offers to install it, runs
`wagoe new`, configures the database, applies migrations, creates an admin
user, starts the server, and polls until it actually answers HTTP. Ends with
the URL and working credentials.

Invoke it from an empty directory. It refuses to run inside an existing Wagoe
project and points at `bb quickstart` instead.

## Keeping in sync

`skills/wagoe/SKILL.md` must stay byte-identical to
`libs/wagoe-cli/resources/wagoe/cli/templates/claude-skill.md.tmpl`.
A test in `libs/wagoe-cli` enforces this.

`skills/wagoe-setup/SKILL.md` has no template counterpart — a project template
cannot carry the skill that creates the project.

## Versioning

`plugin.json` intentionally has no `version` field: Claude Code then versions
the plugin by git commit SHA, so every merged change is picked up by
`/plugin marketplace update` without a manual version bump.
