---
name: handle-linear-code-ticket
description: Take a Linear code ticket to a reviewed pull request with auto-merge on - claim it, implement in its own worktree, simplify, review-pr local, fix, verify, open the PR - then pick the next ticket. Use when the user says "handle BOU-123", "pick up a ticket" or "next ticket".
argument-hint: "[Linear issue id, e.g. BOU-123 — picks the next Todo ticket when empty]"
allowed-tools: Bash, Read, Edit, Write, Grep, Glob, Agent, Skill
---

# Handle a Linear code ticket

Run one ticket to a pull request with auto-merge on, then take the next one. Every change goes through a PR (ADR-045); the merge queue runs the full suite and merges, and `approval-gate` waits for a core member's `approved` label unless the PR touches only docs and tests. See AGENTS.md "Pull request flow" and CONTRIBUTING.md.

## 0. Clean up merged work

From the main checkout, remove every worktree whose PR is merged, with its local branch. GitHub already deleted the remote branch.

```bash
git switch main && git pull --ff-only --prune
git worktree list --porcelain | awk '/^worktree /{w=$2} /^branch /{sub("refs/heads/","",$2); print w, $2}' |
  while read -r wt br; do
    [ "$br" = main ] && continue
    [ "$(gh pr view "$br" --json state -q .state 2>/dev/null)" = MERGED ] || continue
    git worktree remove "$wt" && git branch -D "$br"
  done
```

A worktree with uncommitted changes is refused: report it, never `--force`. A closed PR that did not merge stays; ask the user.

## 1. Pick and claim

1. With an id in `$ARGUMENTS`, take that ticket. Without one, pick the next: `list_issues` for team Wagoe Framework in state Todo, highest priority first, then oldest. Skip a ticket assigned to someone else, and one that changes a stable port another open PR changes; say which you skipped.
2. Read it: `get_issue` and `list_comments` (images via `extract_images`). If it is not a code change or the ask is unclear, stop and ask.
3. Claim it: set it **In Progress**, assigned to me. Read it back; if someone else holds it, drop it and pick again.
4. Work in its own worktree, named after Linear's `gitBranchName`, so other tickets can run beside it:

```bash
git fetch origin main
git worktree add ../wagoe-<ticket> -b <gitBranchName> origin/main
cd ../wagoe-<ticket> && npm ci   # node_modules for bb check's CSS build
```

A `cd` may not carry over between shell calls: start every later command with `cd ../wagoe-<ticket> &&` or use `git -C`.

## 2. Implement

Follow `AGENTS.md` and the guides it names for the task (scaffold first, FC/IS, i18n, migrations). Write the minimum change plus tests, and the `changes/<TICKET>.md` fragment for shipped code (CONTRIBUTING.md "Commits and the changelog"). Unfinished work follows "Unfinished work on main". One ticket, one PR: split a ticket that grows.

## 3. Simplify, then review

1. Run the `simplify` skill on the diff against `origin/main` and keep its changes.
2. Run the `review-pr` skill in `local` mode on the same diff.
3. Fix the blocking findings (CRITICAL, IMPORTANT, a failing gate). Fix each non-blocking one, file a Linear ticket for it, or reject it with a reason for the PR description. If a fix is impossible or contradicts the ticket, stop and ask the user.
4. A fix that changes logic or structure gets `review-pr local` again on the changes since.

## 4. Verify

`bb check`, once, after the last fix: it runs the full kondo lint and the suites of the touched libraries. All green before the PR; the merge queue runs the rest.

## 5. Open the PR

```bash
git commit -m "<what changed> (BOU-123)"
git push -u origin <gitBranchName>
gh pr create --title "<what changed> (BOU-123)" --body "<what and why; Fixes BOU-123>"
gh pr comment --body-file <review-pr report>
gh pr merge --auto --merge
```

Write short (AGENTS.md "Write short"); no attribution footer or `Co-Authored-By`. The review report goes on the PR as a comment, with the findings you ticketed or rejected. The description says so when the change is breaking, touches a stable port, is security-sensitive or adds a migration. If auto-merge is refused, report the PR and leave the merge to the developer.

Never push to `main`, tag, run `bb release`, pass `--no-verify` or add the `approved` label.

## 6. While it is in the queue

- Linear follows GitHub: the PR moves the ticket to In Review, the merge to Done. Until the integration is connected (BOU-1119), and whenever it did not, set the status by hand.
- A red PR: fix it on the branch and push; auto-merge stays on. A PR the queue removed: fix or rerun, then queue it again with `gh pr merge --auto --merge`.
- Review comments: answer or fix them on the branch.
- After the merge, step 0 of the next round removes the worktree and its branch.

## 7. Next

Report in two or three lines: ticket, PR, what changed. Go back to step 1 for the next ticket without waiting for the merge. Stop when the user says so or no Todo ticket is left.
