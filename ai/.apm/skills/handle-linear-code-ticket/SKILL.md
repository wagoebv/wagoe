---
name: handle-linear-code-ticket
description: Take a Linear code ticket from start to merge - implement, PR, review-pr, simplify, rebase, green CI, merge, Done - then ask for the next ticket. Use when the user says "handle BOU-123", "pick up a ticket" or "next ticket".
argument-hint: "[Linear issue id, e.g. BOU-123 — asks when empty]"
allowed-tools: Bash, Read, Edit, Write, Grep, Glob, Agent, Skill
---

# Handle a Linear code ticket

Run one ticket through the full loop, then ask for the next one and repeat. Invoking this skill is the user's approval to commit and push on the ticket branch; it is **not** approval to merge, which the user does.

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

Follow `AGENTS.md` and the guides it names for the task (scaffold first, FC/IS, i18n, migrations). Write the minimum change plus tests. Then verify:

```bash
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test
bb check
clojure -M:test:test/all :<suite>   # suites of the touched libs; bb test:all when shared code changed
```

All green before moving on.

## 3. Commit, push, PR

```bash
git add <files> && git commit -m "<what changed> (BOU-123)"
git push -u origin HEAD
gh pr create --title "<title> (BOU-123)" --body "<what and why>

Fixes BOU-123"
```

Write short (AGENTS.md "Write short"); no attribution footer or trailer. Set the issue **In Review** and add the PR link as a comment.

## 4. Review and fix everything

1. Run the `review-pr` skill on the PR.
2. Fix **every** finding — CRITICAL, IMPORTANT, SUGGESTION, NIT, convention notes and boy-scout items. Do not skip, defer or argue one away. If a fix is impossible or contradicts the ticket, stop and ask the user; never drop it silently.
3. Re-run the step 2 checks, commit (`Address review findings (BOU-123)`), push.

## 5. Simplify

Run the `simplify` skill on the branch and keep all its changes. Re-run the step 2 checks, commit (`Simplify (BOU-123)`), push. Skip the commit when simplify changed nothing.

## 6. Rebase

Run the `rebase` skill. Its "do not push" hand-off does not apply here: check `git log --oneline origin/main..HEAD` shows all ticket commits, re-run the touched suites, then:

```bash
git push --force-with-lease
```

## 7. Green CI

```bash
gh pr checks <n> --watch --fail-fast
```

On a failure: read the log (`gh run view <run-id> --log-failed`), fix the cause (never weaken or skip a test), run the check locally, commit, push, watch again. A flake: rerun once (`gh run rerun <run-id> --failed`); if it fails again, treat it as real. Loop until every required check is green, including e2e.

If main moved and the PR is behind or conflicts, go back to step 6.

## 8. Merge and close

1. Ask the user to merge: `! gh pr merge <n> --merge`. Do not merge yourself.
2. When they say it is merged, check first, on its own:

```bash
gh pr view <n> --json state,mergedAt
```

Act only on `MERGED`; otherwise say so and wait.
3. Set the Linear issue **Done** by hand (the GitHub integration is not connected) with a comment linking the PR.
4. Clean up: `git switch main && git pull --ff-only && git branch -d <branch>`.

## 9. Next

Report in two or three lines: ticket, PR, what changed. Ask for the next ticket id and start again at step 1. Stop when the user says they are done.
