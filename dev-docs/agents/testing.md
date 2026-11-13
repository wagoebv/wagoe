# Testing reference

Read before running or changing tests. Paths in commands and inline code are relative to the repository root.

See the [command catalogue](development.md#essential-commands) for suite aliases and filters.

### Test surfaces — why `bb test:all` exists

`clojure -M:test` runs the kaocha suites declared in `tests.edn`, which
cover the libraries on the app classpath. Three surfaces are **not** on that
classpath and are invisible to it:

| Surface | Run by | Why it is separate |
|---|---|---|
| `libs/wagoe-cli` | `clojure -M:test` in that dir | standalone lib, own `deps.edn` + `:test` alias |
| `libs/wagoe-mcp` | `clojure -M:test` in that dir | standalone lib, own `deps.edn` + `:test` alias |
| `libs/tools` | `bb test:tools` | pure Babashka, no Maven deps at runtime |
| `scripts/agents_gen_test.clj` | `bb test:agents` | generator test, outside the lib tree |

During the Wagoe rename this hid real failures three times — 8 in `wagoe-mcp`,
1 in `wagoe-cli`, 5 in `tools` (including a silently-broken FC/IS gate) — each
found only by remembering to run an extra command.

**So a green `clojure -M:test` does not mean the codebase is green.**
`bb test:all` runs all of them, prints a per-surface summary, and exits non-zero
if any fails. `bb test:all --list` shows the set, including what is deliberately
excluded (`e2e`, which needs Chromium installed — run it with `bb e2e`, which
starts a server on :3100 and tears it down). CI runs e2e on every pull request
and a red e2e test blocks the merge.

None of the surfaces enumerate namespaces: the standalone libs let kaocha
discover `test/`, and the rest discover from disk. An earlier `bb test:wagoe-cli`
hardcoded four requires and had already drifted, silently skipping
`wagoe.cli.agents-update-test`.

### Backing services — `bb test:services`

The adapter sweeps (`libs/{jobs,cache}/test/.../adapter_surface_test.clj`,
`test/wagoe/audience_dialect_test.clj`) compare every backend they can reach and
**fail** rather than pass when one is absent, because a sweep that quietly
compares two adapters instead of three reports green while proving less. So a
full run needs Redis and MySQL:

```bash
bb test:services up     # docker compose -f docker-compose.test.yml, waits for healthy
bb test:services        # what is up; `down` stops them
```

`bb test:all` names the missing ones before it runs anything, and `bb doctor:env`
reports them as a warning. The registry is `wagoe.tools.test-services/services`;
a test pins it to `docker-compose.test.yml` and to the suites that need it, so
adding a service to one and not the other fails. PostgreSQL is not here — those
suites run embedded (`support.embedded-pg`).

### Custom Test Reporter

The Kaocha reporter at `dev/wagoe/test/reporter.clj` shows a green ✓ for
passing tests and a red ✗ for failing tests. It is configured in `tests.edn` as
`:kaocha/reporter [wagoe.test.reporter/reporter]`. The `dev/` directory is on
the `:test` classpath via `:extra-paths` in `deps.edn`.

### PostgreSQL Test Runs

The default `test` profile uses in-memory H2. There is currently no dedicated
PostgreSQL test alias such as `:db/pg`.

To do a complete run against PostgreSQL:

1. Start a disposable PostgreSQL instance that matches the credentials in
   `resources/conf/test/config.edn`.
2. Temporarily move `:wagoe/postgresql` from `:inactive` to `:active` in
   `resources/conf/test/config.edn`, and move `:wagoe/h2` out of `:active`.
3. Run:

```bash
WAG_ENV=test JWT_SECRET="dev-secret-at-least-32-characters-long" clojure -M:migrate up
WAG_ENV=test JWT_SECRET="dev-secret-at-least-32-characters-long" clojure -M:test:test/all
```

4. Revert `resources/conf/test/config.edn` afterwards so normal local and CI
   runs keep using H2.
