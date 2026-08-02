# BOU-236 — `wagoe-setup` skill design

**Date:** 2026-08-02
**Ticket:** [BOU-236](https://linear.app/boundary-app/issue/BOU-236) (parent [BOU-235](https://linear.app/boundary-app/issue/BOU-235))
**Status:** Approved, ready for planning

## Goal

A Claude Code skill that takes a user from nothing to a Wagoe app serving HTTP,
with no manual steps, and ends by printing the URL and working admin
credentials.

## Why a skill and not another bb task

`bb quickstart` already covers the middle of this funnel — setup, validate,
scaffold a sample module, migrate, smoke-check — and deliberately stops before
starting the app. It cannot cover the ends: it runs *inside* a project, so it
cannot create one, and `bb` may not be installed when the user starts.

The skill owns the two ends and delegates the middle. It does not reimplement
`bb quickstart`.

## Decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Distribution | `claude-plugin/skills/wagoe-setup/SKILL.md` | The plugin already ships; `.claude-plugin/marketplace.json` exists. The skill must run before a project exists, so it cannot ship in the project template the way the current `wagoe` skill does. |
| Missing toolchain | Detect, show the command, ask once, run `install.sh` | Honours "no manual steps" without piping a remote script to bash unannounced. `install.sh` is matrix-verified across Ubuntu/Fedora/Arch × bash/zsh/fish (BOU-232). |
| Ending | Start the app, poll until it answers 200, leave it running | The only reading of "nothing → running app" that is literally true. |
| Admin password | Generate, pipe on stdin, print once | `create-admin` accepts a piped password; no interactive step. The secret lands in the transcript — acceptable for a local dev account on a SQLite file, and stated as such in the output. |
| Implementation | Skill file only, no new runtime code | Every step is an existing command. Adding a `bb first-run` task would duplicate `quickstart` and still could not cover the install step. |
| Database | Ask; offer SQLite (default) and PostgreSQL only | Two options a newcomer can actually choose between. H2 and MySQL are mentioned, not offered (see below). |

## Flow

| # | Step | Command | On failure |
| --- | --- | --- | --- |
| 0 | Refuse wrong context | `deps.edn` containing `com.wagoe/` → stop, point at `bb quickstart` | — |
| 1 | Detect toolchain | `command -v java clojure bb wagoe` | — |
| 2 | Offer install | show it, ask, then `curl -fsSL https://raw.githubusercontent.com/wagoebv/wagoe/main/scripts/install.sh \| bash` | stop, show output |
| 3 | Fix PATH | `export PATH="$HOME/.babashka/bbin/bin:$PATH"` on **every** later command | — |
| 4 | Ask project name | validate kebab-case before calling anything | reject with the rule stated |
| 5 | Ask database | SQLite (default) or PostgreSQL | — |
| 6 | Probe PostgreSQL | TCP `localhost:5432`, only if chosen | nothing listening → offer SQLite or a `docker run` line; **do not** write config |
| 7 | Create | `wagoe new <name>` | stop |
| 8 | Configure | SQLite: `bb quickstart`. PostgreSQL: `bb quickstart --preset standard` | stop |
| 9 | Admin | `bb create-admin --email admin@<name>.test --name Admin`, password piped twice on stdin | stop |
| 10 | Start | `clojure -M:run`, backgrounded | — |
| 11 | Find port | parse `:port N` from the `started successfully` log line; else probe 3000–3099 | — |
| 12 | Prove it serves | poll `http://localhost:PORT/` for 200, 60s cap | report, leave the process up, show the log tail |
| 13 | Report | URL, admin URL, email, password, pid, stop command, next steps | — |

### Three steps that look like boilerplate and are not

**Step 3 — PATH.** `install.sh` writes binaries to `~/.babashka/bbin/bin` and
appends a line to the shell rc file. A non-interactive shell never sources that
file, so without an explicit export every command after the install fails with
`command not found` — on exactly the fresh machines this skill exists to serve.

**Step 8 — no `--preset` for SQLite.** `bb quickstart` skips reconfiguration
only when no preset is given (the BOU-228 fix). Passing one rewrites the config
`wagoe new` just wrote correctly. That is what the user asked for when they
chose PostgreSQL, and not what they asked for otherwise.

**Step 12 — poll for 200, not for a live process.** A process that is up is not
a process that serves. BOU-251 exists because nothing proved the built artifact
answered HTTP, and BOU-232 found container port assertions passing while
exercising the wrong branch entirely.

## Database choice

SQLite is the default and needs no precheck: the generated dev config is already
SQLite, file-backed, and its own comment explains the choice — *"unlike
in-memory H2 the data survives a restart"*.

PostgreSQL is offered with a TCP probe **before** any config is written, because
the template defaults to `localhost:5432`, user `postgres`, database
`wagoe_dev`. Two failure modes, both handled:

1. Nothing listening → say so, offer SQLite or a `docker run` line, stop.
2. Listening but migrations fail on auth or a missing database → name the exact
   `POSTGRES_*` variables, offer to fall back to SQLite.

The skill does not orchestrate Docker. It prints the command.

H2 is not offered — but no longer because it is broken.

The original reason was that `bb setup --database h2` wrote `:memory true` for
dev, and in-memory H2 is private to one JVM, so `migrate`, `create-admin` and
the server each got their own empty database while every step exited 0. That is
fixed: [BOU-265](https://linear.app/boundary-app/issue/BOU-265) landed in #355
and dev H2 is now file-backed.

It stays off the menu on different grounds. Post-fix, H2 is a second
file-backed embedded database sitting beside SQLite, and a newcomer has no basis
on which to choose between them. A third option buys a harder question, not more
capability. `bb setup --database h2` remains available for anyone who wants it.

## Error handling

Every step stops on failure, names the step, shows the actual output, and states
the fix. No step continues past a failed predecessor, and no failure is
summarised as success. This is BOU-262 written as a rule.

## Testing

1. **Regression test** (`libs/tools`) pinning `create-admin`'s no-TTY stdin
   fallback. The flow depends on `System/console` returning nil under a piped
   stdin (`admin.clj:112`), which nobody wrote down as a contract; hardening
   that prompt would break the skill silently, in a file nobody touched.
   Requires lifting `read-pw` out of `-main` into a private `defn`.
2. **Manual verification in a clean container**, never a dev box — the method
   BOU-232 established. Two runs, since the promise now spans an external
   service:
   - Ubuntu image with nothing installed → SQLite path → serving app, admin logs in.
   - Same, PostgreSQL path against a running server → serving app, admin logs in.

## Out of scope

MySQL (`bb setup --database mysql` afterwards), module selection beyond the
sample `tasks` module `bb quickstart` already generates, and with/without-user —
that needs [BOU-234](https://linear.app/boundary-app/issue/BOU-234), still
Backlog.

## Files

| File | Change |
| --- | --- |
| `claude-plugin/skills/wagoe-setup/SKILL.md` | new |
| `libs/tools/src/wagoe/tools/admin.clj` | extract `read-pw` for testability |
| `libs/tools/test/wagoe/tools/admin_test.clj` | regression test |
| `claude-plugin/README.md` | list the second skill |
| `docs/modules/getting-started/pages/quickstart.adoc` | note that an assisted path exists, per BOU-235's "keep manual and assisted in sync" |
