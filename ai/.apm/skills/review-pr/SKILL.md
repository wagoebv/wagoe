---
name: review-pr
description: Evidence-backed PR review with convention checks and call-chain tracing. Not a walkthrough or hands-on verification; use understand-pr or verify-pr.
argument-hint: "[PR number, URL, or branch name — defaults to current branch PR, then current branch vs main]"
allowed-tools: Bash, Read, Grep, Glob, Agent
---

# Verified PR review

Produce a high-confidence, evidence-backed PR review. Every finding must be verified against actual code, checked against codebase conventions, and include a concrete failure scenario. Chain: `understand-pr` → `review-pr` → `verify-pr`. This skill is the judgement step, not a walkthrough or hands-on verification.

## Design principle

Each reviewer agent carries its own evidence standards; this skill orchestrates them and does not review code itself. The one principle that belongs at the orchestration level:

**Contract fidelity matters, scope and behaviour both.** Deviation from a planning artifact (Linear issue, ADR, spec) is a reviewable issue. So is implementation that contradicts a stated AC, invariant, or numeric target. Scope drift ("the named hook is absent") and conformance violation ("the code silently drops messages, AC 7.1 forbids that") are distinct failure modes; both belong in the review.

## Step 1: Determine review scope

Parse the input argument (`$ARGUMENTS`) to determine the PR:

- **PR number** (e.g., `123`): use `gh pr view 123`
- **PR URL** (e.g., `https://github.com/org/repo/pull/123`): extract number, use `gh pr view`
- **Branch name**: use `git diff origin/main...origin/<branch>`
- **Empty**: detect current branch, use `gh pr view` for current branch or fall back to `git diff origin/main...HEAD`

## Step 2: Gather context

Execute these steps sequentially (each depends on the previous):

### 2a. PR metadata

```bash
gh pr view <number> --json title,body,headRefName,baseRefName,files
```

Extract: title, description, branch name, base branch, list of changed files.

### 2b. Story contract ingestion

Build the **story contract**, the structured spec the reviewers will diff the implementation against. Follow `ai/.apm/skills/references/story-contract-extraction.md` for the sources, the output structure, and the extraction rules.

The labeled block that procedure produces goes verbatim into the `## Story contract` section fed to every reviewer agent in Step 3, not just the conformance agent.

### 2c. Diff

```bash
git diff origin/<base>...origin/<head>
```

If the PR branch is not fetched locally:

```bash
git fetch origin <head>
```

### 2d. Changed file contents

For each changed file, read the full content from the PR branch:

```bash
git show origin/<head>:<filepath>
```

Budget: read every changed file in full. If a single file exceeds 1000 lines, read it but note the size for agents.

### 2e. Project conventions

Read the root `AGENTS.md` and the `libs/<lib>/AGENTS.md` of every library the diff touches. These are the conventions agents may cite.

### 2f. Quality gates

On the PR head, run the gates that enforce the architecture rules, and keep their output for the report:

```bash
bb check:fcis; bb check:ports; bb check:deps; bb check:error-shape
```

Do not ask an agent to re-derive FC/IS, ports or dependency rules by reading code; the gates are the source of truth. A failing gate is a CRITICAL finding quoting the gate output. If the head is not checked out locally, use a worktree (`git worktree add ../<repo>-review-<slug> origin/<head>`) and remove it afterwards.

## Step 3: Route and dispatch agents

Agent definitions live in `ai/.apm/agents/`. Each agent has its own severity definitions, confidence ratings, and output format.

Determine which agents to dispatch based on the changes:

| Agent                            | When to dispatch                                                                                                          | What to send                                                                                                               |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| `story-conformance-reviewer`     | Mandatory whenever a story contract was extracted in 2b                                                                   | Story contract, diff, full file contents, conventions                                                                      |
| `operational-reviewer`           | Mandatory whenever the contract has numeric targets OR operational invariants (threading model, lifecycle, failure rules) | Story contract, diff, full file contents, conventions                                                                      |
| `code-quality-reviewer`          | Always                                                                                                                    | All source file contents, diff, conventions, PR context, story contract                                                    |
| `error-handling-reviewer`        | When changes include error handling (try/catch, ex-info, `{:success? false}` / `{:error …}` values) or business logic                          | Relevant source file contents, diff, conventions, PR context, story contract                                               |
| `test-coverage-reviewer`         | When source files are changed (not just config/docs)                                                                      | Source files + their test files, diff, conventions, PR context, story contract                                             |

For each agent, construct a prompt containing:

1. The PR context (title, description)
2. The **`## Story contract`** block extracted in 2b, verbatim (omit only if no contract was found)
3. The project conventions (the AGENTS.md files from 2e)
4. The full content of relevant changed files (labeled clearly with file paths)
5. The diff

Every agent receives the story contract, not just the conformance agent. The contract raises the bar on every lens: a silent error path becomes an AC citation, not a first-principles inference; a concurrency primitive becomes an invariant violation, not a vibe; a per-message allocation becomes a measurable cost against a stated throughput, not a stylistic note.

Launch applicable agents in parallel using the Agent tool. With a contract present, expect 3–5 agents — `story-conformance` and `operational` are nearly always worth running alongside the first-principles lenses.

## Step 4: Aggregate and format report

Collect results from all agents. Produce the final report:

```markdown
# PR review: [PR title]

**Branch:** `<head>` → `<base>`
**Linear:** [issue key and summary, if available]
**Files changed:** [count]
**Agents dispatched:** [list]

## Story conformance

[From story-conformance-reviewer. Render as a checklist: every AC, invariant,
numeric target, and observability requirement with met / partial / violated /
not verifiable verdict and file:line evidence. Skip this section only if no
story contract was extracted.]

## Quality gates

[One line per gate from 2f: pass, or fail with the gate output quoted.]

## Verified issues

### [CRITICAL/IMPORTANT/SUGGESTION/NIT] — [title]

**File:** `path/to/file:line`
**Evidence:** [from agent]
**Impact:** [from agent]
**Suggestion:** [from agent]

[...repeat for each finding, ordered by severity...]

## Convention notes

[Patterns that are established conventions but worth reconsidering broadly.
Include where the pattern was found in the codebase.]

## Verified non-issues

[Observations that were investigated and found to be correct.
Briefly note what was checked and why it's fine.]

## Test coverage summary

[From test-coverage-reviewer: coverage map and gaps]

## Strengths

[What the PR does well, aggregated from all agents]

## Boy scout opportunities

[Pre-existing issues in touched files that the PR does not introduce, aggregated from all agents.
These are not PR issues. They are optional improvement opportunities for the developer who is already working in these files.
Group by file path. For each, note the domain (quality, error handling, testing, architecture) and a one-line suggestion.]
```

### Deduplication

If multiple agents flag the same issue:

- Keep the version with the strongest evidence
- Merge complementary evidence from both
- Use the higher severity if justified by the combined evidence

### Self-filtering gate

Findings fall into two categories with different evidence standards.

**Correctness findings** (bugs, errors, security, performance) — drop if any filter fails:

1. **Is the failure scenario technically possible?** The scenario must be possible given how the technology actually works, not just plausible-sounding. "The framework could fail to roll back" is not a scenario if the framework guarantees rollback. If you cannot explain the mechanism that makes the scenario happen, drop it.

   **Exception — contract violations.** If the finding cites a violated AC, named invariant, or numeric target from the story contract, the contract violation **is** the failure scenario. Do not require an additional materialized incident. A contract-cited violation passes this filter without needing a separate "what could go wrong" narrative.

2. **Did the agent show its convention search?** The agent must have included grep/glob results proving it searched for the pattern. "Checked the codebase" without evidence means it wasn't checked. If no search evidence, drop the finding.
3. **Did the agent's own analysis conclude the finding is negligible?** If the agent used phrases like "essentially zero risk," "no action needed," "unlikely to matter," or similar self-assessments that contradict the finding's severity, drop it. Reporting a finding you've already dismissed is worse than reporting nothing.
4. **Does it include actual code evidence?** Not assumptions, not signatures, not "might." Actual traced code paths.
5. **For unreachable edge cases, drop instead of suggesting a guard.** If the failure scenario is "the library could throw with an empty-string message" or similar, and the agent has no evidence the library ever does this, the finding is theoretical. Adding a guard for an unreachable path also forces a synthetic test (especially under strict coverage gates), which is net-negative. Drop unless the agent shows the path is actually reached.

Validate severity: CRITICAL/IMPORTANT require a concrete, technically valid failure scenario with the exact code path. SUGGESTION/NIT are the correct severity for "works but could be better."

**Convention findings** (documented convention violations in newly added code) — drop if any filter fails:

1. **Does the agent cite a specific convention rule?** Must reference a concrete rule from an AGENTS.md file — not a general best practice.
2. **Is the violation in newly added code?** Only flag code from the diff's `+` lines. Pre-existing convention violations belong in the boy scout section, not in findings.
3. **Does it show both the rule and the violating code?** The evidence must include the convention source and the specific new code that deviates from it.
4. **For API-shape suggestions, did the agent check sibling patterns in the same module?** Before flagging "this constructor / factory / parameter shape is a smell," the agent must show grep evidence that the same module does _not_ already use the proposed shape. If a canonical sibling exists (e.g. an existing `@FunctionalInterface` factory of the same shape in the same package), the new code is following the convention — drop the finding. Stylistic uniformity with siblings beats abstract preference.

Convention findings are always SUGGESTION severity. They do not require failure scenarios — code organization conventions are about maintainability, not correctness.

If the filters reduce the report to zero findings, that is the best possible outcome. Say so explicitly.

## Notes

- If `gh` CLI is not available, fall back to git-only workflow (no PR metadata, just diff)
- If the Linear MCP is not available, skip issue context and note it
- If the diff is empty or the branch doesn't exist, report the error and stop
