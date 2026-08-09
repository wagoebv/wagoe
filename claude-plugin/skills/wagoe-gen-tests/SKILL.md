---
name: wagoe-gen-tests
description: Generate a Kaocha test namespace for a Wagoe source file with `bb ai gen-tests`, put it in the right place, and run it. Use when asked to write tests for an existing namespace, to cover a function that has none, or to bootstrap a test file for freshly scaffolded code. Covers the test-type metadata Kaocha selects on, where the file belongs, and how to triage the failures a generated draft always has.
---

# Wagoe — generating tests

```bash
bb ai gen-tests <source-file>            # print to stdout
bb ai gen-tests <source-file> --write    # write to the conventional test path
bb ai gen-tests <source-file> -o <file>  # write to a named file
```

It needs an AI provider. Check first — the command is otherwise a slow way to
discover that:

```bash
bb doctor:env      # names the provider it would use, or says none is set
```

## The output is a draft, and the failures are the point

Measured on this repository's own `libs/ai/src/wagoe/ai/core/context.clj`: the
generated namespace loaded, ran, and reported

```
Ran 10 tests containing 84 assertions.
{:test 10, :pass 74, :fail 10, :error 0}
```

Ten failing assertions out of eighty-four is the normal result for a subtle
namespace, not a malfunction. The model infers what each function *should*
return from its name and shape; some of those guesses are wrong. A short module
of obvious pure functions fared much better in the same measurement — 10 of 10
— so expect the failure rate to track how much of the behaviour a reader could
guess from the source.

**Every failure is either a wrong assertion or a real bug, and only you can say
which.** Read the function, then fix whichever is wrong — never delete the test
to get green.

Treat the run as a review queue. This is why the skill runs the tests rather
than stopping at "file written".

## Where the file goes, and what metadata it gets

Both are derived from the source path, in Clojure, not asked of the model:

| Source path contains | Kaocha metadata |
|---|---|
| `/core/` | `^:unit` |
| `/adapters/` | `^:contract` |
| anything else (`/shell/`, …) | `^:integration` |

```
libs/user/src/wagoe/user/core/validation.clj
  -> libs/user/test/wagoe/user/core/validation_test.clj   ^:unit
```

`--write` puts it there. A source file with no `src/` path segment has no
convention to apply — `--write` refuses and asks for `-o` rather than guessing
a location Kaocha will not look in.

The metadata matters more than it looks: **Kaocha selects suites on it.** A
`deftest` without it is in the file and in no test run. `bb ai gen-tests`
stamps it now; before it merely asked the model, which supplied it on 0 of 15
measured deftests.

## What the tool repairs, and what it will not

Three failures were reproducible enough to be fixed in `bb ai gen-tests`
itself, so you do not need to check for them:

- **Missing metadata** — stamped from the path, as above.
- **A namespace used but not required.** `str/join` in the body with no
  `[clojure.string :as str]` fails at load with `No such namespace: str`;
  `clojure.set/subset?` with no require fails with
  `ClassNotFoundException: clojure.set`. Both are repaired for the standard
  `clojure.*` aliases (`str set walk edn io pprint`). An alias outside that set
  is left to fail, because the namespace behind it cannot be inferred.
- **An answer cut off mid-form.** A long source file can exhaust the model's
  output budget; the reply then ends inside a form and the file cannot be
  read. That is now reported as an error instead of written to disk:

  ```
  Error: The model's answer was cut off mid-form — 598 lines, delimiters
  still open. Generate for a smaller source file, or raise :max-tokens.
  ```

  If you hit it, generate for a smaller namespace. Splitting a 250-line source
  file into two calls is more reliable than raising the budget.

It does **not** check that the tests pass, that the assertions are right, or
that coverage is meaningful. That is the step below.

## Steps

1. `bb doctor:env` — confirm a provider. Without one, stop; there is no
   offline fallback for this command.
2. `bb ai gen-tests <source-file> --write`. If the destination already exists
   it refuses rather than overwriting — with `-o` as well as `--write`. Write
   elsewhere and merge by hand, or pass `--force` only when you mean to replace
   an earlier draft.
3. Run just the new namespace and read every failure:
   ```bash
   clojure -M:test --focus <the.test.namespace>
   ```
   In this repository, add the library suite: `clojure -M:test :ai --focus …`.
4. Triage each failure — wrong assertion, or real bug. Fix the right one.
5. `bb check:placeholder-tests`. Generated drafts have not produced `(is true)`
   placeholders in practice, but the gate is cheap and it is a hard CI failure.
6. `bb check` before committing.

## What this does not cover

The generated namespace tests the public functions it can see in one file. It
does not know about the database, the Integrant system, or the ports a shell
namespace depends on, so `^:integration` output for a `shell/` file is usually
the weakest of the three — expect to supply the stubs yourself. For a service
that needs a repository, `reify` the port; the existing tests under
`libs/*/test/**/shell/` show the pattern.

Nor does it replace scaffolding: `bb scaffold` already generates a test
skeleton for a new module. Use this for code that exists and has none.
