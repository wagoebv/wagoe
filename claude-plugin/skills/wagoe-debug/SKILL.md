---
name: wagoe-debug
description: Diagnose a failure in a Wagoe app — a 500, an exception, a stack trace, a nil where a value was expected, a field that reads back empty, or a test that fails for no obvious reason. Use when something in a Wagoe project is broken and the cause is not yet known. Works from the innermost layer outward, checks the framework's known pitfalls first, and can call bb ai explain on a stack trace.
---

# Wagoe — debugging

Most Wagoe bugs are one of a handful of shapes. Check those before reasoning
from first principles — it is usually faster, and the symptoms are distinctive.

## Symptom → likely cause

| Symptom | Look at |
|---|---|
| A field reads back `nil` for no reason | snake_case / kebab-case mismatch |
| A new field is missing or rejected | schema, column and persistence transform out of sync |
| Generic 500 with "Exception reached HTTP boundary" | `ex-info` without `:type` in ex-data |
| `Key must be integer` at `wrap-route-with-version` | module routes returning Reitit vectors, not normalized maps |
| `No matching method` on a Java call | static vs instance interop |
| `Unable to resolve symbol` for your own fn | private helper defined below its caller |

### The nil one is worth recognising on sight

kebab-case everywhere in Clojure; snake_case **only** at the database boundary;
camelCase **only** at the API boundary. Use `:created_at` internally and the
lookup silently returns nil — no error, no warning, just a missing value. In a
test it shows up as:

```
Expected:  #<java.time.Instant … 2026-01-01T00:00:00Z>
Actual:    -#<java.time.Instant …> +nil
```

That `+nil` against a concrete expected value is the signature. Note the
quality gates do **not** catch this — `bb check` passes on a project with this
defect, because a wrong keyword is still a valid keyword. Only a test that
exercises the value finds it.

## Work from the inside out

Do not debug through the whole stack. Find the innermost layer that is wrong:

1. **Core** — pure functions. Call them directly in a REPL with the failing
   input. No database, no HTTP. If they are wrong, stop here.
2. **Persistence** — query the database directly and compare with what the
   entity looks like after transformation. This is where case mismatches show.
3. **Service** — call the port function with a stub repository.
4. **HTTP** — only once the layers beneath are known good.

```clojure
;; in the REPL, after (go)
(def svc (get integrant.repl.state/system :wagoe/user-service))
(def ds  (get-in integrant.repl.state/system [:wagoe/db-context :datasource]))
(jdbc/execute! ds ["SELECT * FROM users LIMIT 5"])
```

Reload with `(reset)`. After changing a `defrecord`, `(reset)` is not enough —
it reuses the old instances. Use `(halt)` then `(go)`.

## Logs — check where they actually go first

**A generated project ships no logback config**, so there is no log file to
tail. Logback falls back to console, which means the stack trace is in the
terminal running `clojure -M:run` or in the REPL — not on disk. Looking for a
file and finding none is not evidence that nothing was logged.

```bash
find . -name "logback*.xml"     # nothing? then logs are on the console
```

In the Wagoe framework repository itself, `resources/logback.xml` writes to:

| | |
|---|---|
| `logs/wagoe.log` | application, including stack traces |
| `logs/audit.log` | audit trail |
| `logs/security.log` | security events |

```bash
tail -100 logs/wagoe.log | grep -A 10 "ERROR"
```

If a project has added its own logback config, read that file for the path
rather than guessing.

`println` goes to stdout, not to any log file — and should come out before
committing.

## `bb ai explain`

```bash
bb ai explain --file stacktrace.txt
cat stacktrace.txt | bb ai explain
```

It needs an AI provider:

```bash
bb doctor:env      # names the provider that would be used, or says none is set
```

The failure modes are readable now (BOU-280). With nothing configured it says
so and lists the variables to set; a rejected key says the key was rejected
rather than printing the request map; an exhausted balance is distinguished
from rate-limiting, because the advice differs. If you see a raw stack trace
from `bb ai`, that is a bug worth filing, not a configuration hint.

This is an accelerator, not the method. The symptom table and layer isolation
above work with no provider at all.

## Steps

1. Get the actual error — full text, not a paraphrase. A stack trace, a test
   diff, or a response body.
2. Match it against the symptom table.
3. Identify the innermost layer that could produce it, and test that layer
   directly.
4. Fix, then re-run the thing that failed. If a test caught it, that test is
   your verification.
5. If nothing matched, `bb ai explain` on the trace — provider permitting.

## What this does not cover

`bb check` and the test suite catch structural problems: FC/IS violations,
missing ports, untagged tests, lint. They do not catch logic errors, and as
above they do not catch case mismatches. A green `bb check` on a broken app is
normal and means nothing about correctness.
