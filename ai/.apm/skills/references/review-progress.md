# Review progress file

Shared on-disk state for the PR review chain (`understand-pr`, `verify-pr`). One file per branch; both skills read it first and append to it, so a review survives session boundaries and skills reuse each other's work.

## Location and setup

1. **Slug the head branch**: replace every character outside `[a-zA-Z0-9._-]` with `-` (`feat/something` → `feat-something`). Never create nested directories from branch segments.
2. **Path**: `.pr-review/<slug>/progress.md` in the root of the repo under review.
3. **Gitignore check, before the first write**: run `git check-ignore -q .pr-review/` (exit 0 means ignored via any mechanism). On exit 1, append `.pr-review/` to `$(git rev-parse --git-common-dir)/info/exclude` in the repo under review, then re-run the check. Use that command rather than a literal `.git/info/exclude`, because in a linked worktree `.git` is a file, not a directory. The exclude file is repo-local and never committed, so it keeps the review scratch space out of `git status` without touching the repo's tracked `.gitignore` or the user's global git config.
4. **Resume over regenerate**: if `progress.md` already exists for this branch, load it, report phase and open items, and continue from that state. Overwrite existing sections only after explicit confirmation.

## Template

```markdown
---
pr: 123                     # omit in branch mode (no PR)
branch: feat/something
base: main
ticket: ABC-456             # omit if none
phase: understanding        # briefing | understanding | reviewing | verifying | done
worktree: ../repo-verify-feat-something   # omit if none
updated: 2026-07-20
---

# Review progress: feat/something

## Status log
- 2026-07-20 understand-pr: walkthrough delivered (4 clusters), guided mode through cluster 2

## Story contract
[labeled block per references/story-contract-extraction.md — written by
whichever skill runs first, reused by every later one instead of
re-fetching Linear]

## Clusters
- [x] 1. Config wiring
- [ ] 2. Token bucket enforcement   ← current

## Scrutiny points
- [ ] refill() uses wall-clock instead of monotonic time (cluster 2)
- [x] Retry-After unit checked: seconds

## Verification scenarios

### S1 — Requests over the limit get 429 [AC 1]
- **Status:** pass            # open | pass | fail | blocked | skipped
- **Preconditions:** app running from worktree on :8080, limit 10/min
- **Steps:**
  1. `for i in $(seq 1 12); do curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/ingest -d @sample.json; done`
- **Expected:** first 10 return 202, requests 11-12 return 429 with `Retry-After` header
- **Actual:** 10× 202, 2× 429, Retry-After: 42
- **Evidence:** curl output captured 2026-07-20 14:32

## Report
[verification report, appended by verify-pr at the end]
```

## Section ownership

| Section | Written by | Read by |
|---------|-----------|---------|
| Status log, frontmatter | both skills, on every state change | both |
| Story contract | whichever skill runs first | both |
| Clusters, Scrutiny points | understand-pr | verify-pr (scenario hints) |
| Verification scenarios, Report | verify-pr | the reviewer, for drafting PR comments |

## Field rules

- **Frontmatter**: update `phase` and `updated` on every write; append a one-line entry to `## Status log` describing what changed.
- **Scenario status values**: `open`, `pass`, `fail`, `blocked`, `skipped`. Every scenario starts `open`.
- **Scenario source tag** in the heading brackets: `AC <n>`, `diff: <path>`, or `regression`.
- **Steps** must be executable as written: exact commands, full URLs, named UI elements. A step that needs interpretation is not done.
- **Expected** must be observable: a status code, a rendered element, a log line, a row count. Not "works correctly".
- **Actual** and **Evidence** are filled at execution time and never pre-filled.
- **Posting the report**: `gh pr comment --body-file` needs a standalone file; write the report body transiently to `.pr-review/<slug>/report.md` at posting time. The `## Report` section in `progress.md` stays the source of truth.
- Scrutiny points still unchecked when the review ends are the draft shortlist for review comments.
