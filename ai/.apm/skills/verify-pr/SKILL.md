---
name: verify-pr
description: Hands-on verify a pull request against its acceptance criteria with runnable test scenarios. Not for static review; use review-pr.
argument-hint: "[PR number, URL, or branch name — defaults to current branch PR]"
allowed-tools: Bash, Read, Write, Edit, Grep, Glob, AskUserQuestion
---

# Verify PR

Turn a pull request into a concrete, trackable manual verification session: check CI, derive test scenarios from the story contract and the diff, record them in the branch's shared progress file, optionally spin up the branch, and record evidence per scenario. This closes the gap static review leaves open: nobody actually ran the code. Chain: `understand-pr` → `review-pr` → `verify-pr`.

## Design principles

1. **CI covers automated tests; this skill covers behaviour.** Do not re-run the test suite. Check that CI is green and spend human time on scenarios automation does not cover: real flows, real data, ACs as a user experiences them.
2. **Scenarios trace to a source.** Every scenario cites the AC it verifies or the diff hunk that motivated it. No generic "click around and see".
3. **The user approves before anything is written.** Present the derived scenario list first; the user prunes, edits, or adds.
4. **Warn, never block.** Red or pending CI is reported prominently, then the user decides whether to continue.
5. **Evidence, not vibes.** A scenario passes when its recorded actual result matches the expected result. "Seems fine" is not a result.

## Step 1: Determine scope

Parse `$ARGUMENTS`:

- **PR number** (e.g. `123`): `gh pr view 123`
- **PR URL**: extract number, use `gh pr view`
- **Branch name**: resolve the PR via `gh pr list --head <branch> --json number,title`. If no PR exists, run in **branch mode**: diff against the default base branch (`git symbolic-ref refs/remotes/origin/HEAD`) and skip the CI check (Step 2) and the PR comment offer (Step 8).
- **Empty**: detect the current branch's PR via `gh pr view`

After the PR (or branch) is identified, set up or load the progress file `.pr-review/<branch-slug>/progress.md` per `ai/.apm/skills/references/review-progress.md` (includes the gitignore check). If it already holds verification scenarios, resume: report progress and jump to Step 7 unless the user wants to regenerate. If understand-pr already ran on this branch, its story contract and scrutiny points are in this file; reuse them.

## Step 2: Check CI status

```bash
gh pr checks <number>
```

Report per check: name, status, link. Then classify:

- **All green**: state it and continue.
- **Pending or red**: warn prominently, name the failing/pending checks, and note that automated coverage is unconfirmed. Continue regardless; the user decides whether manual verification is worth their time now.

## Step 3: Gather context

1. **PR metadata**: `gh pr view <number> --json title,body,headRefName,baseRefName,files`
2. **Story contract**: reuse the `## Story contract` section from the progress file when present; otherwise follow `ai/.apm/skills/references/story-contract-extraction.md` and write the result to that section.
3. **Diff**: `git fetch origin <head>` then `git diff origin/<base>...origin/<head>`
4. **Runnability**: Wagoe runs with no external services on the test profile: `JWT_SECRET="dev-secret-at-least-32-characters-long" WAG_ENV=test clojure -M:run server` serves on port 3100–3199 (read the logged port). For REPL-level scenarios, start `clojure -M:repl-clj` and drive it with `clj-nrepl-eval -p 7888`. Adapter scenarios need `bb test:services up`. Use these to make scenario steps executable, not abstract.

## Step 4: Derive test scenarios

Derive from three sources, in this order:

1. **AC-derived** (when a contract exists): one scenario per acceptance criterion, phrased as an executable check of the user-visible behaviour. Cite the AC number.
2. **Diff-derived**: behaviour the diff introduces or changes that the ACs do not name. Look for: new error paths, changed validation, edge inputs (empty, boundary, malformed), state transitions, UI states (loading, empty, error), permission/role branches, migration or config effects.
3. **Regression-adjacent**: existing behaviour the diff plausibly disturbs (shared components, changed signatures, modified queries). One or two scenarios maximum; this is not a full regression suite.

Per scenario define: **id** (`S1`, `S2`, …), **title**, **source** (`AC 3` / `diff: path/file.ext` / `regression`), **preconditions** (test data, config, user role), **steps** (exact commands, URLs, or UI actions), **expected result** (observable and specific).

Prioritize: AC scenarios first, then diff scenarios by risk. Cap the initial proposal at ~10 scenarios; verification checklists that are too long do not get executed, which recreates the original problem.

## Step 5: Approval and scenario recording

Present the scenario list compactly (id, title, source, expected result) and ask the user to approve, prune, edit, or add. Use AskUserQuestion when the choice is structural (e.g. drop a whole category); otherwise plain conversation.

After approval, write the scenarios into the `## Verification scenarios` section of the progress file, following the template and field rules in `ai/.apm/skills/references/review-progress.md`. Set `phase: verifying`. If the section already has scenarios, overwrite only after explicit confirmation.

Scrutiny points left open by understand-pr are scenario candidates: where one is verifiable by running the code, offer to include it as a scenario.

## Step 6: Optional environment setup

Ask whether to prepare a running environment. If yes:

1. **Worktree checkout**, so current work stays untouched:
   ```bash
   git worktree add ../<repo>-verify-<branch-slug> origin/<head>
   ```
2. **Start the app** from the worktree using the project's documented run path (Step 3.4). Report the URL/port and how to stop it.
3. Record the worktree path in the progress file frontmatter. On completion (Step 8), remind the user to remove it: `git worktree remove ../<repo>-verify-<branch-slug>`.

If no, the scenario list stands alone; steps must still contain exact commands so the user can run them independently.

## Step 7: Execute and track

Work through scenarios interactively:

- Per scenario, restate the steps. When a step is a command or API call, offer to run it and capture output as evidence. UI steps are executed by the user, who reports what they saw.
- Record the outcome in the progress file immediately: status (`pass` / `fail` / `blocked` / `skipped`), actual result, evidence (command output, response body, observed behaviour).
- A failing scenario is a finding, not a stop: record it and continue with the rest unless the environment is broken.
- The session can end at any point; the file preserves progress and Step 1 resumes it.

## Step 8: Verification report

When all scenarios are resolved (or the user stops), append the report to the `## Report` section of the progress file and set `phase: done`:

```markdown
# Verification report: [PR title]

**PR:** #<number> `<head>` → `<base>` | **CI at start:** [green / N failing]
**Result:** X pass, Y fail, Z blocked/skipped of N scenarios

## Failures
[Per failed scenario: id, title, source AC/diff ref, expected vs actual, evidence]

## Passed
[One line each: id, title, evidence pointer]

## Not verified
[Blocked/skipped scenarios and why: what remains unconfirmed]
```

Then offer, in order:

1. **Post as PR comment**: write the report body transiently to `.pr-review/<branch-slug>/report.md`, then `gh pr comment <number> --body-file .pr-review/<branch-slug>/report.md`. Only on explicit confirmation.
2. **Feed failures into review**: suggest running `/review-pr` (or adding to it manually) so failures become part of the inline PR comments.
3. **Cleanup**: remove the worktree if one was created.

## Notes

- If `gh` is unavailable, skip the CI check and the PR comment offer, and derive scenarios from the diff and local artifacts only.
- If no story contract exists, say so and derive scenarios from the diff alone; flag that "built as specified" cannot be verified without a spec.
- Never push, commit, or modify the PR branch. The only writes in the repo under review live under `.pr-review/` (excluded via `$(git rev-parse --git-common-dir)/info/exclude` per the review-progress reference); the repo's own tracked `.gitignore` is never touched.
