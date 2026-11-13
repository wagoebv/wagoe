---
name: rebase
description: Rebase the current feature branch onto main and resolve conflicts, including when a PR conflicts with main.
---

# Rebase onto main

Rebase the current feature branch onto `origin/main`, resolve conflicts using project conventions, regenerate **only the derived files that actually conflicted**, and verify the result.

## Conflict resolution rules

When conflicts arise during rebase, apply these rules **automatically** — do not ask the user:

| File pattern | Resolution | Regenerate after? |
|---|---|---|
| `CHANGELOG.md` | **Merge manually**: keep both sides' entries under `[Unreleased]`, ours below main's | No |
| `libs/i18n/resources/wagoe/i18n/translations/*.edn` | **Merge manually**: keep both sides' keys, sorted as the file is | No |
| `libs/tools/resources/wagoe/agents/knowledge.edn`, `modules-catalogue.edn` | **Merge manually**: these are the sources | Yes — `bb agents:gen` |
| Root `AGENTS.md`, `libs/wagoe-cli/resources/wagoe/cli/templates/AGENTS.md.tmpl` | Pick **main** (`--theirs`) | Yes — `bb agents:gen` |
| `examples/shop/**` | Pick **main** (`--theirs`) | Yes — `bb example:regen` |
| `test/snapshots/**` | Pick **main** (`--theirs`) | Yes — rerun the snapshot test with `UPDATE_SNAPSHOTS=true` |
| Source code (`.clj`, `.cljc`, `.edn`, `.adoc`, `ci.yml`, etc.) | **Merge manually** — keep both sides | No |

`--theirs` means main here: during a rebase, "ours" is the branch being rebased onto.

Only the generated sections of root `AGENTS.md` come from `knowledge.edn`. If our branch changed hand-written prose in `AGENTS.md`, merge that part manually instead of taking main's copy, then run `bb agents:gen`.

For source code conflicts: both sides typically add new functions, tests or config keys that need to coexist. Keep both. After resolving a Clojure file, run `clj-paren-repair <file>`; never fix delimiters by hand.

## Steps

### 1. Rebase

```bash
git fetch origin main
git rebase origin/main
```

### 2. Resolve conflicts

For each conflict round:
1. Check which files are conflicted: `git diff --name-only --diff-filter=U`
2. Apply the resolution rules above
3. Stage resolved files: `git add <files>`
4. Continue: `git -c core.editor=true rebase --continue`
5. Repeat until rebase completes

Remember which file patterns conflicted — step 3 only regenerates those.

### 3. Regenerate what conflicted

- AGENTS sources or outputs: `bb agents:gen`, then `bb check:agents`
- `examples/shop`: `bb example:regen`, then `bb example:regen --check`
- Snapshots: `UPDATE_SNAPSHOTS=true clojure -M:test:test/all --focus <snapshot-test-ns>`, then review the snapshot diff — a changed snapshot is a behaviour change, not noise

### 4. Check the result

```bash
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test
bb check:fcis && bb check:ports && bb check:deps
```

Then run the suites of the libraries the branch touches (`clojure -M:test :<lib>`, plus the aliases AGENTS.md lists for that lib). Run `bb test:all` if the conflicts touched shared code.

### 5. Commit regenerated files

Only if step 3 produced changes:

```bash
git add AGENTS.md libs/wagoe-cli/resources/wagoe/cli/templates/AGENTS.md.tmpl examples/shop test/snapshots
git commit -m "Regenerate derived files after rebase"
```

### 6. Hand off to user

Tell the user to review the rebase result and push when satisfied:

> Rebase complete. Please review the changes with `git log --oneline origin/main..HEAD` to verify no work was lost, then run `git push --force-with-lease` when everything looks good.

Do **not** push — the user must confirm.

## Troubleshooting

### Rebase conflict in a commit that only modifies generated files

A commit consisting purely of generated file changes can be skipped, because step 3 regenerates them anyway. First confirm that is actually what the commit contains:

```bash
git show --stat REBASE_HEAD
```

Every path listed must be root `AGENTS.md` (generated sections only), `AGENTS.md.tmpl`, under `examples/shop/`, or under `test/snapshots/`. If even one hand-written file appears, do not skip: `git rebase --skip` discards the whole commit and that work is gone silently.

```bash
git rebase --skip
```
