# Scaffold AI eval

How good `bb scaffold ai` output is against real providers, one number per
night per provider (BOU-909). `prompts.edn` holds five fixed prompts, easiest
first, with what their output must satisfy.

Each prompt runs in a fresh project generated from this checkout, the way
`scripts/first-run-smoke.sh` does it. The module must generate, compile, pass
its generated tests, `bb check:fcis`, `bb migrate up` and `(go)`, and meet its
expectations. `check:deps` is recorded as skipped: it only runs in the monorepo.

When something is red, `clj-paren-repair` and then cljfmt run on the generated
files. The changed lines are the edits needed. Still red after that: not
mergeable, edits `nil`.

## Run it locally

```bash
ollama serve & ollama pull qwen2.5-coder:7b
bb eval:scaffold --provider ollama --only product
OPENAI_API_KEY=… bb eval:scaffold --provider openai
```

Results land in `target/eval-scaffold/<date>-<provider>.edn`. With
`OPENAI_BASE_URL` set, the openai cell talks to that endpoint and records it
under `:endpoint`.

## Read the results

The nightly workflow commits each result to the `eval-results` branch under
`scaffold/`. A run without `OPENAI_API_KEY` is recorded with `:status :skipped`.

```bash
git fetch origin eval-results && git worktree add /tmp/eval-results origin/eval-results
bb eval:report /tmp/eval-results/scaffold
```

`:summary {:mergeable n}` counts prompts mergeable without edits. A night more
than one below its provider's 7-day average opens an `eval-regression` issue
with the failing step's log and the repair diff. Per prompt, `:failure` names
the first red step or expectation.

`bb eval:report <dir> --stability-line` prints the stability page sentence once
a provider has 30 nights, and nothing before. It is not on the page yet.
