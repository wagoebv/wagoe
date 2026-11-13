# Story contract extraction

Shared procedure for building a **story contract**: the structured spec that PR comprehension, review, and verification workflows diff the implementation against. Single source of truth, referenced by `review-pr` (step 2b), `understand-pr`, and `verify-pr`.

## Sources, in priority order

1. **Ticket references**: Linear keys (`BOU-\d+`) in the PR title, description, branch name, or commits. Fetch with the Linear MCP `get_issue` tool, and its discussion with `list_comments` (`get_issue` does not include comments). **Always fetch the parent issue (and its parent, if it has one)**: ACs and numeric targets often live one level above the sub-issue being worked on.
2. **Inline references inside fetched tickets**: ADRs, implementation plans, design docs, prior stories. Fetch every link the ticket cites that looks like a contract (ADR, RFC, implementation plan, spec). Skip dashboards and reference docs.
3. **Local planning artifacts**: linked ADR, design doc, RFC, task-tracker entry (e.g. `.beans/`, `docs/plan/`, `TODO.md`, issue body). Search PR description links, branch name conventions, commit messages, and paths added or modified in the diff that look like task records.
4. **Nothing**: if no written contract exists, note that and proceed. Consumers without a contract fall back to diff-derived reasoning.

## Output structure

Extract into a single labeled block, omitting empty subsections:

```
### Acceptance criteria
<verbatim, numbered as in the source — do not paraphrase>

### Explicit invariants
<statements the source asserts as true by construction, e.g. "core/ never throws", "one transaction per request">

### Numeric targets
<throughput, latency, growth horizons, capacity ceilings — with units and source>

### Failure handling rules
<named policies: no silent drops, idempotency keys, redelivery semantics, DLQ behaviour>

### Observability requirements
<named metrics, dashboards, alerts that must exist>

### Open questions (and resolutions)
<questions flagged in the source, with the inline resolution if one is given>

### Out of scope
<verbatim list from the source>

### Sources
<ticket keys + URLs, ADR paths, doc paths>
```

## Rules

- Quote ACs verbatim. Paraphrasing loses the contract.
- Do not invent ACs the source didn't state. If something feels missing but the source is silent, put it under `Open questions`.
- If the Linear MCP is unavailable or no ticket is found, fall back to local artifacts. If none exist, note "no story contract" and continue.
