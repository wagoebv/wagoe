# Wagoe Claude Code Plugin

Makes agentic CLIs (Claude Code and compatible tools) aware of Wagoe's
scaffolder and AI tooling, so the agent reaches for `bb scaffold` / `bb ai`
instead of hand-writing module boilerplate.

New projects created with `boundary new` already include this skill at
`.claude/skills/wagoe/SKILL.md` — the plugin is for **existing** projects.

## Install

In Claude Code:

```
/plugin marketplace add thijs-creemers/boundary
/plugin install boundary@boundary
```

## What it provides

A single `boundary` skill with:

- A decision table mapping user requests ("add a product module") to the
  right `bb scaffold` / `bb ai` / `bb setup` / `bb doctor` / `bb guide` command
- Scaffold-first rules (never hand-write a module skeleton)
- FC/IS architecture invariants (core purity, case conventions, field sync)

## Keeping in sync

`skills/wagoe/SKILL.md` must stay byte-identical to
`libs/wagoe-cli/resources/wagoe/cli/templates/claude-skill.md.tmpl`.
A test in `libs/wagoe-cli` enforces this.

## Versioning

`plugin.json` intentionally has no `version` field: Claude Code then versions
the plugin by git commit SHA, so every merged change is picked up by
`/plugin marketplace update` without a manual version bump.
