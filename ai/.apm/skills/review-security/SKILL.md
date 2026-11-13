---
name: review-security
description: Security review of uncommitted work, a branch or a PR, run before it merges - code that weakens auth, tenancy, input handling or secrets, and risky third-party libraries. Not a general review; use review-pr.
argument-hint: "[nothing for uncommitted work, a branch name, or a PR number/URL]"
allowed-tools: Bash, Read, Grep, Glob, Agent
---

# Security review

Find what a change does to security while it is still cheap to fix: before the commit, before the push, or before the merge. It checks the change against the mechanism Wagoe already has for each concern, and reviews every third-party library the change adds or bumps. `review-pr` runs the same reviewer as one of its lenses; use this skill on its own to check your work before anyone else sees it.

## Step 1: Scope

Parse `$ARGUMENTS`:

- **Empty**: the uncommitted work plus unpushed commits: `git diff $(git merge-base HEAD origin/main)` (staged, unstaged and committed-but-unmerged). Untracked files count: list them with `git status --short` and read them.
- **Branch**: `git fetch origin <branch>`, then `git diff origin/main...origin/<branch>`.
- **PR number or URL**: `gh pr view <n> --json title,body,headRefName,baseRefName,files`, then the diff against its base.

If the diff is empty, say so and stop.

## Step 2: Context

1. **Classify the changed files**:
   - **Source**: `src/`, `libs/*/src`, `dev/`, `scripts/`, `resources/` config and SQL migrations.
   - **Dependencies**: any `deps.edn`, `bb.edn`, `.github/workflows/*`, vendored or CDN-loaded JS/CSS.
   - **Tests and docs**: read for context; not reviewed for security on their own.
2. **Ticket**: a Linear key (`BOU-\d+`) in the branch, title or commits → fetch it. ACs about access, tenancy or data exposure go to the reviewer verbatim (see `ai/.apm/skills/references/story-contract-extraction.md`).
3. **Conventions**: the root `AGENTS.md` and the `libs/<lib>/AGENTS.md` of each library touched.

## Step 3: Automated checks

Run on the code under review (a worktree for a branch or PR that is not checked out: `git worktree add ../<repo>-security-<slug> origin/<head>`, removed afterwards):

| Check | When | Command |
|---|---|---|
| Known vulnerabilities | Any dependency file changed | `bb check:vulns` (needs `GITHUB_TOKEN`; without one, report it as not run) |
| Undeclared third-party requires, dependency cycles | Any source or dependency file changed | `bb check:deps` |
| Error shape | Source changed | `bb check:error-shape` |
| Security tests of touched libraries | Source changed | `clojure -M:test:test/all :<lib> --focus-meta :security` for each library touched (add `:test/pg-mac` on Apple Silicon) |
| Tenant isolation registry | A library gained `tenant_id` data | `clojure -M:test:test/all :app --focus wagoe.tenant-isolation-registry-test` |
| Route rules | Routes added or `:public` changed | `java -jar target/wagoe-*.jar authz-report` when an uberjar is at hand; otherwise the reviewer reads the route data |

A failing check is a finding: quote its output.

## Step 4: Dispatch the reviewer

Launch the `security-reviewer` agent (definition in `ai/.apm/agents/security-reviewer.agent.md`) with:

1. The scope: uncommitted work, branch or PR, with its title and description when there is one.
2. The ticket's access, tenancy or data-exposure ACs, verbatim.
3. The diff, and the full content of each changed source and dependency file (labelled by path).
4. The conventions from Step 2.
5. The Step 3 results, so it does not repeat them.

For a diff over ~40 source files, split it by library and launch one reviewer per group in parallel, and give each the dependency changes.

## Step 5: Filter and report

Drop a finding when its exploit path is not traced through code that was read, when the reviewer showed no search for the repo's mechanism, or when its own analysis called it negligible. A pre-existing weakness the diff does not touch is a boy scout note, not a finding.

```markdown
# Security review: [scope]

**Checks:** check:vulns [pass / N advisories / not run: no GITHUB_TOKEN] · check:deps [pass/fail] · :security tests [libs, pass/fail]

## Findings
### [CRITICAL/IMPORTANT/SUGGESTION] — [title]
**File:** `path:line` · **Concern:** [area]
**Exploit path:** [who sends what → which function → what they get]
**Fix:** [the repo's mechanism to use]
**Test:** [the `^:security` test that should hold it]

## Third-party libraries
[Changed coordinates, advisories, new transitive artifacts, pinning and exclusions; or "no dependency changes"]

## Checked and safe
[Boundaries traced and found sound, one line each]

## Boy scout opportunities
[Pre-existing weaknesses in touched files]
```

Zero findings is a good result: say so plainly, with the boundaries that were checked.

## Step 6: Prevent, don't just report

When the scope is your own uncommitted work, offer to fix each finding now: the fix that uses the repo's mechanism, plus the `^:security` test that holds it, then re-run Step 3. For a PR, offer to post the findings as inline comments (`gh api repos/{owner}/{repo}/pulls/<n>/reviews` with `event: COMMENT`), and never post without the user's go-ahead.

## Notes

- Never paste a working exploit payload into a report or a PR comment; describe the path.
- Do not run `bb check:vulns` with a token pasted into the command line; it reads `GITHUB_TOKEN` from the environment.
- Read-only on the code under review until the user asks for fixes.
