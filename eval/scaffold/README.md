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

Each run writes `<date>-<provider>.edn` to `--out` (default `target/eval-scaffold`). A run without
`OPENAI_API_KEY` is recorded with `:status :skipped`. The file is rewritten
after each prompt with `:status :partial`, so a killed run keeps what ran;
`eval:report` counts only finished runs.

In CI, run the "Scaffold AI eval" workflow by hand on main; each provider's
file is a run artifact. It needs the `eval` environment (main only) with the
`OPENAI_API_KEY` secret. A nightly schedule follows once a few runs show the
Ollama runtime and the OpenAI cost (BOU-979).

```bash
bb eval:report target/eval-scaffold
```

`:summary {:mergeable n}` counts prompts mergeable without edits.
`--regression <provider>` exits 2 when the latest run is more than one below
the provider's 7-day average. Per prompt, `:failure` names
the first red step or expectation.

`bb eval:report <dir> --stability-line` prints the stability page sentence once
a provider has 30 nights, and nothing before. It is not on the page yet.
