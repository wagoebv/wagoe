---
name: understand-pr
description: Walk through a pull request to build a mental model before review. Not for finding issues; use review-pr.
argument-hint: "[PR number, URL, or branch name — defaults to current branch PR]"
allowed-tools: Bash, Read, Write, Edit, Grep, Glob, AskUserQuestion
---

# Understand PR

Build the reviewer's mental model of a pull request before any judgement happens. The output is comprehension, not findings: what the PR does, why, in what order to read it, and where the risk concentrates. Chain: `understand-pr` → `review-pr` → `verify-pr`.

## Design principles

1. **Understanding before judgement.** Do not report bugs, style issues, or improvement suggestions. If something looks genuinely broken, mention it once under "Questions worth asking in review" and move on.
2. **Story before code.** The user first builds the business picture: the story's context, then its acceptance criteria, then any technical notes the ticket carries. Only after the user confirms that picture does the skill fetch the diff and start the code walkthrough. Never open with file-level detail.
3. **Reading order beats file order.** GitHub shows files alphabetically; humans understand code by following the flow. Present changes in the order a maintainer would trace them: entry point → core logic → data flow → edge cases → tests.
4. **Depth scales with the PR.** A 3-file fix gets a five-sentence walkthrough. A cross-layer feature gets clusters, a diagram, and Q&A. Never pad a small PR to look thorough.
5. **Evidence over recollection.** Every claim about behaviour must come from code actually read in this session, cited as `file:line`.
6. **Teach the mechanism, don't narrate the diff.** When a change relies on a non-obvious mechanism (a framework feature, protocol, algorithm, library behaviour, domain rule), explain it in a short paragraph of everyday language that stands on its own for someone who does not have the code open: what happens and why, with no class names, method names, or framework vocabulary inside the explanation. Anchor it to a `file:line` afterwards if that helps. Conversely, state simple changes in one sentence; a renamed field needs no paragraph. Prose that restates the diff without adding understanding gets cut.
7. **Define the vocabulary before you use it.** Define every load-bearing term in one plain sentence before the first prose that uses it. This covers both the words the PR or its ADR coins (a `watermark`, an `epoch`) and domain or infra words a first-time reader may not know. A reader meeting a term they have to reverse-engineer from context is the most common reason a technically correct walkthrough still reads as opaque.

## Step 1: Determine scope

Parse `$ARGUMENTS`:

- **PR number** (e.g. `123`): `gh pr view 123`
- **PR URL**: extract number, use `gh pr view`
- **Branch name**: resolve via `gh pr view <branch>`; if no PR exists, diff against the default base branch (`git symbolic-ref refs/remotes/origin/HEAD`), never assume `main`
- **Empty**: detect the current branch's PR via `gh pr view`, fall back to a diff against the default base branch

## Step 2: Gather story context

Run sequentially. Do **not** fetch or read the diff yet; this step is about the story, not the code.

1. **Progress file**: set up `.pr-review/<branch-slug>/progress.md` per `ai/.apm/skills/references/review-progress.md` (includes the gitignore check). If the file already exists, resume: report the recorded phase and open items, and skip work it already captures.
2. **PR metadata**: `gh pr view <number> --json title,body,headRefName,baseRefName,files,additions,deletions,commits`
3. **Story contract**: reuse the `## Story contract` section from the progress file when present; otherwise follow `ai/.apm/skills/references/story-contract-extraction.md` and write the result to that section. The ticket's "why" is the backbone of the walkthrough.

## Step 3: Story briefing

Present the story to the user before touching any code, in exactly this order, as one message:

1. **Context** — one short paragraph: the problem being solved, who has it, why it matters now, and how it fits the surrounding feature or epic. Synthesized from the ticket and PR description. Plain business language; no file names, no code.
2. **Acceptance criteria** — the ACs from the story contract, numbered, each restated in one plain sentence when the original is Gherkin or terse. If the ticket has none, derive the observable outcomes the PR description promises and label them as such. This numbering is canonical: the walkthrough and guided mode cite ACs by these numbers (AC 1, AC 2, …).
3. **Technical notes from the ticket** — only if the ticket or PR description carries them: named components, constraints, design decisions, links to ADRs. Report what the *ticket* says, not what the code does; the code has not been read yet.

If there is no ticket and the PR description is thin, say so, brief from whatever is available, and note that the walkthrough will have to infer intent from the code.

Close with a gate: ask whether to proceed to the code walkthrough, or skip straight to guided review mode (Step 6). When skipping, still run Steps 4-5 internally — write the walkthrough to the progress file only — and deliver the code cluster by cluster. Do not fetch the diff until the user confirms either option. Record `phase: briefing` and a status-log line in the progress file.

## Step 4: Assess size, pick depth, then read

Fetch the code now:

1. **Diff**: `git fetch origin <base> <head>` then `git diff origin/<base>...origin/<head>`
2. **Commit sequence**: `git log origin/<base>...origin/<head> --oneline --reverse`. The commit order often reveals the author's intended narrative.

Classify from the Step 2.2 metadata (file count, additions/deletions) and the diff's shape, **before** reading any file in full:

| Class | Heuristic | Output |
|-------|-----------|--------|
| Small | ≤5 files and ≤150 changed lines, single layer | Summary + short walkthrough. No diagram. |
| Medium | ≤15 files or single-feature scope | Full clustered walkthrough. Diagram only if flow crosses ≥3 components. |
| Large / architectural | >15 files, cross-layer, new components, or concurrency/lifecycle changes | Full walkthrough + mermaid diagram + explicit risk map. |

Generated files (lockfiles, snapshots, formatting-only churn) do not count toward size. Name them once and exclude them from the walkthrough.

Then read changed file contents from the PR branch via `git show origin/<head>:<filepath>`; understanding requires surrounding code, not just hunks. For small and medium PRs, read every changed file in full up front. For large PRs, read cluster by cluster while building the walkthrough in Step 5, so context follows the reading order instead of front-loading everything.

## Step 5: Produce the walkthrough

Structure:

```markdown
# Understanding: [PR title]

**Branch:** `<head>` → `<base>` | **Ticket:** [key + one-line summary, if any] | **Size:** [class, N files, +A/−D]

## Key terms

[A compact glossary, per principle 7. Placed first so every later section can
lean on it. When in doubt, include it: an opaque walkthrough is a worse
failure than a slightly redundant glossary.]

## Background concepts

[1-3 short explainers, per principle 6, of the machinery this PR depends on.
Skip entirely when the PR uses only familiar patterns.]

## Intent

[A few sentences: the approach the code takes to deliver the story briefed in
Step 3. Do not repeat the briefing's context; add what only the code reveals.
If the ticket or description and the code disagree, say so explicitly. That
is the single most valuable comprehension signal.]

## Reading order

[Numbered clusters in trace order, not alphabetical order. Per cluster:]

### 1. [Cluster name] — `path/a.ext`, `path/b.ext`

**What:** [the change, in one or two plain sentences]
**Why:** [its role in the intent]
**AC:** [the acceptance criterion(s) this cluster delivers, cited by briefing
number with a half-sentence on how (e.g. "AC 2: this is the validation that
rejects expired tokens"). For enabling work with no direct AC (refactoring,
configuration, test scaffolding), write which AC it enables, or "supporting,
no direct AC".]
**How it works:** [the mechanism, per principle 6. Omit for straightforward
changes.]
**Connects to:** [which cluster consumes or triggers this]

## How the pieces fit

[Only for medium+ PRs: one paragraph narrating one end-to-end flow through
the changed code, with file:line waypoints, naming the AC being satisfied at
each stage where one applies.]

## Flow diagram

[Only when Step 4 says so: a mermaid sequenceDiagram (runtime flow) or
flowchart (structural change). Include only changed or directly-adjacent
components. Mark changed components. Keep the fenced block here (it is the
record) and also render it per "Rendering the diagram" below.]

## Questions worth asking in review

[3-6 pointed questions a thoughtful reviewer would raise: unexplained
deviations from the ticket, surprising dependency choices, untested paths,
behaviour changes not mentioned in the description. If any AC from the
briefing maps to no cluster, ask where it is delivered. Questions, not
verdicts.]
```

### Rendering the diagram

Whenever the walkthrough contains a `## Flow diagram`, also render it so the user can look at it instead of reading mermaid source. Terminals do not render mermaid; an unrendered diagram is wasted work.

Write a self-contained HTML page to `.pr-review/<slug>/flow.html`, alongside the progress file and under the same slug rules, then open it (`open <path>` on macOS, `xdg-open <path>` on Linux). The path follows from the branch slug, so a later session rewrites the same file instead of leaving a second copy behind.

Keep the page local and self-contained: it names components, paths and the ticket, which is not content to hand to a hosted renderer on a client codebase.

After delivering the walkthrough, record its state in the progress file: the cluster list as unchecked items under `## Clusters`, the review questions as unchecked items under `## Scrutiny points`, a `## Status log` line, and `phase: understanding`.

## Step 6: Guided review mode (opt-in)

After the walkthrough, offer two follow-ups: open questions (Step 7) or a guided review ("walk me through it"). Never start guided mode unprompted; the user may prefer to review solo with an independent eye.

In guided mode, present one cluster at a time in walkthrough order and wait for the user between clusters. Per cluster:

- **Header:** cluster n of N, name, files.
- **Role in the PR:** one or two sentences tying it to the intent and to the neighbouring clusters, naming the AC(s) it delivers by briefing number.
- **How it works:** the mechanism explanation, only when non-obvious: plain language first, code references after (design principle 6).
- **Where to look:** `file:line` pointers into the actual diff, one line each on what to see there, ordered by how much attention they deserve. Point, don't paste: the user reads the real code in their IDE or the GitHub diff; reproduce a hunk only on request.
- **Worth scrutinizing:** the walkthrough's review questions that belong to this cluster, phrased as questions, not verdicts.
- **Pacing prompt:** close with: say "done" for the next cluster, ask anything about this code, or "skip ahead".

Questions asked mid-cluster get the Step 7 treatment, scoped to the current cluster. After each cluster, tick it in `## Clusters` and check off scrutiny points the user resolved; a later session resumes guided mode mid-PR from exactly this state. After the last cluster, recap the scrutiny points the user did not explicitly resolve; that list doubles as a draft review-comment shortlist for the reviewer.

## Step 7: Interactive Q&A

Available after the walkthrough and inside guided mode. Stay in comprehension mode:

- Answer "why does X change Y", "what calls this", "what happens when Z" by reading actual code from the PR branch and tracing call chains. Cite `file:line` for every hop.
- When an answer hinges on framework or library behaviour, teach it in a sentence or two of plain language before naming the code path; the goal is that the user could answer the same question themselves next time.
- Use Grep/Glob across the full repo when a question needs callers, config, or history beyond the diff.
- If a question cannot be answered from the code (author intent, product decision), say so and suggest it as a PR comment for the author.

When the user is done, suggest the next step: `/review-pr` for the static review, or `/verify-pr` to derive and run test scenarios.

## Notes

- If `gh` is unavailable, fall back to git-only mode: walkthrough from diff and commits alone.
- If the diff is empty or the branch does not exist, report the error and stop.
- Do not modify any files in the repo under review, with one exception: the progress file under `.pr-review/` (excluded via `$(git rev-parse --git-common-dir)/info/exclude` per the review-progress reference). The code itself is never touched.
