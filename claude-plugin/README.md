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

Seven skills. `wagoe` routes a request to the right command; the rest each own
a workflow, run it, and check the result.

| Skill | Use for |
|---|---|
| `wagoe` | Finding the right command for a request |
| `wagoe-setup` | Nothing → a running app |
| `wagoe-scaffold` | Adding a module, field, endpoint or adapter |
| `wagoe-migrate` | Creating, applying and rolling back migrations |
| `wagoe-gen-tests` | Tests for a namespace that has none |
| `wagoe-doctor` | Config and environment preflight |
| `wagoe-debug` | Diagnosing a failure |

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

### `wagoe-scaffold`, `wagoe-migrate`, `wagoe-gen-tests`, `wagoe-doctor`, `wagoe-debug`

The dev-workflow skills. Each wraps commands the `wagoe` decision table already
names, but adds the part a lookup table cannot: what to run afterwards, how to
tell whether it worked, and which failure modes are known.

They were written by running the commands rather than reading them, which keeps
turning up defects in the tooling they wrap — `bb doctor`'s exit code inside
`bb check`, `bb migrate create` throwing, every `bb ai` subcommand being
unreachable in a generated project, and, while writing `wagoe-gen-tests`, a
test generator whose output carried none of the metadata Kaocha selects on and
would not compile. Each skill records the behaviour that was actually observed,
including the parts that are unhelpful.

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
