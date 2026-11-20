---
name: handle-linear-code-ticket
description: Take a Linear code ticket to a reviewed local commit on main's trunk-based flow - implement, simplify, review-pr local, fix, verify, commit with trailers - then hand the push to the developer and ask for the next ticket. Use when the user says "handle BOU-123", "pick up a ticket" or "next ticket".
argument-hint: "[Linear issue id, e.g. BOU-123 — asks when empty]"
allowed-tools: Bash, Read, Edit, Write, Grep, Glob, Agent, Skill
---

# Handle a Linear code ticket

Run one ticket to a reviewed local commit, then ask for the next one and repeat. The core team commits straight to `main` (ADR-044); an agent commits locally and **never pushes or tags**: the developer reads the diff and pushes. See AGENTS.md "Trunk-based flow" and CONTRIBUTING.md "Review before main".

## 1. Pick up the ticket

1. Ask for the issue id if `$ARGUMENTS` is empty.
2. Read it with the Linear MCP: `get_issue` and `list_comments` (images via `extract_images`). If it is not a code change or the ask is unclear, stop and ask.
3. Set the issue **In Progress**, assigned to me.
4. Branch from fresh main, named after Linear's `gitBranchName`:

```bash
git fetch origin main
git switch -c <gitBranchName> origin/main
```

## 2. Implement

Follow `AGENTS.md` and the guides it names for the task (scaffold first, FC/IS, i18n, migrations). Work in small steps that each leave `main` releasable; unfinished work follows CONTRIBUTING.md "Unfinished work on main". Write the minimum change plus tests, and a CHANGELOG line under `[Unreleased]` for shipped code.

A change that is breaking or touches a stable port, is security-sensitive, or adds a library or a framework migration still goes through a reviewed pull request (CONTRIBUTING.md "Still a pull request"): say so before starting, and hand it over as a branch for a PR instead of a push to main.

## 3. Simplify, then review

1. Run the `simplify` skill on the diff against `origin/main` and keep its changes.
2. Run the `review-pr` skill in `local` mode on the same diff.
3. Fix the blocking findings (CRITICAL, IMPORTANT, a failing gate). Fix each non-blocking one, file a Linear ticket for it, or reject it with a reason. If a fix is impossible or contradicts the ticket, stop and ask the user.
4. A fix that changes logic or structure gets `review-pr local` again on the changes since.

## 4. Verify

```bash
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test
bb check
clojure -M:test:test/all :<suite>   # suites of the touched libs; bb test:all when shared code changed
```

All green before committing.

## 5. Commit and hand over

Commit locally with the ticket at the end of the subject and the review trailers. Every commit for main carries them, so each small step gets its own review; a trivial commit carries `Review-skipped: <reason>` instead.

```bash
git commit -m "<what changed> (BOU-123)" -m "Fixes BOU-123

Simplified-by: simplify
Reviewed-by: review-pr"
```

`Fixes BOU-123` goes only on the commit that finishes the ticket. Write short (AGENTS.md "Write short"); no attribution footer or `Co-Authored-By`.

Then rebase: `git fetch origin main && git rebase origin/main` (the `rebase` skill on a conflict). If main moved, re-run the step 4 checks.

Do not push, tag, run `bb release` or pass `--no-verify`. Report the commits (`git log --oneline origin/main..HEAD`) and ask the developer to read the diff and push, e.g. `git push origin HEAD:main`; the pre-push hook checks the trailers and runs `bb check`.

## 6. After the developer pushed

1. Check the commits reached main: `git fetch origin main && git branch -r --contains <sha> | grep origin/main`.
2. CI on main runs the full suite. A red run opens a "main is red" issue: help revert first, debug afterwards (CONTRIBUTING.md "Stop the line").
3. Set the Linear issue **Done** with a comment naming the commits, unless the Linear GitHub integration closed it through `Fixes BOU-123`.
4. Clean up: `git switch main && git pull --ff-only && git branch -d <branch>`. If `-d` refuses, some commits did not reach main: stop and report.

## 7. Next

Report in two or three lines: ticket, commits, what changed. Ask for the next ticket id and start again at step 1. Stop when the user says they are done.
