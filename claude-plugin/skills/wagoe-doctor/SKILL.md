---
name: wagoe-doctor
description: Diagnose and fix a Wagoe project's configuration and development environment. Use when a Wagoe app will not start, when config.edn changes are not taking effect, before running dev commands on an unfamiliar machine, when JWT_SECRET or database settings look wrong, or when the user asks whether their setup is correct. Runs bb doctor and bb doctor:env, explains each failure, and applies the fix it names.
---

# Wagoe — config and environment preflight

Two commands, two different questions:

| | |
|---|---|
| `bb doctor` | Is `resources/conf/<env>/config.edn` coherent? |
| `bb doctor:env` | Does this machine have the tools to run the project? |

Run both with `bb doctor --all`.

## Use `--ci` when you need an answer, not a report

**`bb doctor` exits 0 even when it reports errors.** Only `--ci` makes it exit
non-zero:

```
$ bb doctor            # config.edn truncated so it does not parse
  ✗ config-loadable    config.edn could not be parsed: EOF while reading
Summary: 8 passed, 0 warnings, 1 error
$ echo $?
0

$ bb doctor --ci ; echo $?
1
```

So `bb doctor` is for reading; `bb doctor --ci` is for deciding. This exact trap
sat inside `bb check` for months — its Config doctor row invoked `bb doctor`
without the flag and could never fail (BOU-270).

## Reading the output

Each check prints one line, and a failing one carries its own `Fix:`:

```
  ⚠ ai-providers    No AI providers detected (Ollama on 11434, MLX on 8080)
                    Fix: Optional: start Ollama with `ollama serve` …
```

Prefer the printed fix over inventing one — it is written against the check
that failed.

Distinguish the three levels. **Errors** block: the config will not load or the
app will not start. **Warnings** are conditional — `ai-providers` only matters
if the user wants `bb ai` or `bb scaffold ai`. **Passes** that read oddly are
usually skips: `jwt-secret ✓ User module not active, JWT_SECRET not required`
means it was not checked, not that it is fine.

## Steps

1. `bb doctor --all`. If everything passes, say so and stop — do not go hunting.
2. For each `✗`, read its `Fix:` line and apply it. Re-run.
3. For each `⚠`, decide whether it is relevant to what the user is doing before
   raising it.
4. Confirm with `bb doctor --all --ci` and check the exit code, so "fixed" is a
   measurement rather than an impression.

   Verify the same surface you asked about. `bb doctor --ci` alone checks
   *config only* — an agent that started from `--all`, fixed a missing tool or
   an occupied port, and then confirmed with `bb doctor --ci` would report
   success while the environment failure stood. `--all --ci` runs
   `bb doctor:env --ci` too and exits non-zero if either half fails.

## Other environments

`bb doctor` checks `dev` by default. Config problems usually bite in the one
nobody runs locally:

```bash
bb doctor --env prod
bb doctor --env all
```

`--env all` before a release is worth the ten seconds.

## What this does not cover

`bb doctor` reads configuration; it does not connect to your database, call
your PSP, or start the app. A green run means the config is coherent and the
tools are present — not that the system boots. For that, start it and look
(`clojure -M:run`, then `curl`).
