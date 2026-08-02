---
name: wagoe-setup
description: Create a new Wagoe (Clojure) project from nothing and leave it running. Use when the user wants to start a new Wagoe project, try Wagoe, scaffold a Wagoe app, or says they have nothing installed and want a Wagoe app running. Installs the toolchain if missing, creates the project, configures the database, creates an admin user, starts the server, and verifies it serves HTTP.
---

# Wagoe — new project, running

Takes a user from nothing to a serving Wagoe app with working admin
credentials. Every step below is an existing command. Run them in order and
**stop at the first failure**.

Ask exactly two questions: project name and database. Everything else has a
default.

## Step 0 — refuse the wrong context

If the working directory has a `deps.edn` containing `com.wagoe/`, this is
already a Wagoe project. Do not create another one inside it. Tell the user to
run `bb quickstart` instead, and stop.

## Step 1 — check the toolchain

```bash
for t in java clojure bb wagoe; do
  printf '%s: ' "$t"; command -v "$t" || echo 'not found'
done
```

## Step 2 — install what is missing

If anything is missing, show the user this command, say what it does, and ask
before running it:

```
curl -fsSL https://raw.githubusercontent.com/wagoebv/wagoe/main/scripts/install.sh | bash
```

It installs a JVM (via sdkman), Clojure, Babashka and the `wagoe` CLI, and adds
one line to the shell rc file. If the user declines, stop and leave them the
command — do not try to work around a missing toolchain.

On failure: stop, show the actual output, do not continue.

## Step 3 — put the new tools on PATH

`install.sh` leaves the toolchain reachable only through the shell rc file, and
a non-interactive shell never sources it. **Every** command from here on must
carry both lines:

```bash
export PATH="$HOME/.babashka/bbin/bin:$PATH"
set +u; [ -s "$HOME/.sdkman/bin/sdkman-init.sh" ] && . "$HOME/.sdkman/bin/sdkman-init.sh"; set -u
```

Two separate mechanisms, and both are needed:

- `~/.babashka/bbin/bin` holds `wagoe`, `bb` and `clojure`. Without it every
  command fails with `command not found`.
- The JVM comes from **sdkman**, which puts `java` on PATH only via its init
  script. Without it `wagoe new` succeeds and then `bb quickstart` dies with
  `Couldn't find 'java'. Please set JAVA_HOME.` — the tools resolve, so the
  failure looks like a Wagoe bug rather than a missing shell setup.

`set +u` around the sourcing is required, not defensive: `sdkman-init.sh` reads
unset variables and aborts under `set -u`. `install.sh` does the same thing for
the same reason.

Verified in a bare Ubuntu container: without these, `command -v wagoe` fails in
a non-interactive shell; with them it resolves and the rest of the flow runs.

## Step 4 — ask for a project name

Must be kebab-case: lowercase letters, digits and single hyphens, starting with
a letter. Reject `My-App`, `123app`, `my.app`, `my_app`, `my-`, `my--app` — and
**state the rule** when rejecting, rather than just refusing.

## Step 5 — ask for a database

| Option | When |
| --- | --- |
| **SQLite** (default) | Zero setup, file-backed, survives restarts. Recommend this. |
| **PostgreSQL** | Only if they already have a server running. |

Do not offer H2 or MySQL. Both work, but H2 is a second file-backed embedded
database beside SQLite and a newcomer has no basis for choosing between them.
Mention `bb setup --database h2` or `--database mysql` as things they can run
later.

## Step 6 — probe PostgreSQL, if chosen

Before anything writes config:

```bash
(exec 3<>/dev/tcp/localhost/5432) 2>/dev/null && echo reachable || echo unreachable
```

If unreachable: say so, and offer either SQLite instead or

```
docker run --name wagoe-pg -e POSTGRES_PASSWORD=postgres -p 5432:5432 -d postgres:16
```

Stop until they choose. Do not write config and let the migration fail later —
probing first is the whole point.

## Step 7 — create the project

```bash
wagoe new <name> && cd <name>
```

## Step 8 — configure and migrate

SQLite:

```bash
bb quickstart
```

PostgreSQL:

```bash
bb quickstart --preset standard
```

**Do not pass `--preset` for SQLite.** `bb quickstart` skips reconfiguration
only when no preset is given; passing one rewrites the config `wagoe new` just
wrote correctly.

This checks the environment, validates config, scaffolds a sample `tasks`
module, applies migrations, and smoke-checks the project.

On failure with PostgreSQL: the config defaults to `localhost:5432`, user
`postgres`, database `wagoe_dev`. Name `POSTGRES_HOST`, `POSTGRES_PORT`,
`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` and offer a SQLite
fallback.

## Step 9 — create the admin user

Generate a strong random password. Pipe it twice — the prompt asks for
confirmation:

```bash
printf '%s\n%s\n' "$PW" "$PW" | bb create-admin --email "admin@<name>.test" --name "Admin"
```

Keep the password for Step 13.

On failure: stop. Without an admin user the admin UI redirects to a login
nobody can pass.

## Step 10 — start the server

Background it, capturing output:

```bash
clojure -M:run > /tmp/<name>-server.log 2>&1 &
```

Record the pid.

## Step 11 — find the port

The configured port is 3000, but the platform auto-finds through 3099 when it
is busy, so do not assume. Read the actual port from the log:

```bash
grep -o 'started successfully.*:port [0-9]*' /tmp/<name>-server.log | grep -o '[0-9]*$'
```

If that yields nothing, probe 3000–3099 for one that answers.

## Step 12 — prove it serves

Poll until the server answers, up to 60 seconds:

```bash
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$PORT/" || true)
  [ -n "$code" ] && [ "$code" != "000" ] && break
  sleep 1
done
```

**Check the HTTP response, not the pid.** A process that is up is not a process
that serves.

Do **not** require 200 from `/`. A fresh app redirects it — `/` returns 302,
which is the server answering correctly. `000` is curl reporting that it never
connected, and that is the only failure. If you want a 200 to point the user at,
`/api-docs/` serves the Swagger UI.

On timeout: say so, leave the process running, and show the last 30 lines of
the log. Do not report success.

## Step 13 — report

```
Your app is running:
  http://localhost:PORT           (redirects to /web/login)
  http://localhost:PORT/api-docs/ Swagger UI

Sign in with:
  admin@<name>.test
  <password>

This is a local development account on <name>-dev.db.
Change or remove it before this project goes anywhere real.

Stop it with:   kill <pid>
Start it again: clojure -M:run
Next:           bb scaffold ai "..."   add a module
                wagoe add admin        the auto-CRUD admin UI
                bb guide next          what to do next
```

Do **not** print `/web/admin/` as a link. The admin module is optional —
`wagoe new` wires `core`, `observability`, `platform` and `user`, so that path
returns 404 in a default project until the user runs `wagoe add admin`.
Verified in a container. `/web/login` is where `/` actually lands.

## Rules

1. Stop at the first failing step. Name the step, show the real output, state
   the fix. Never summarise a failure as success.
2. Never continue past a failed step hoping the next one recovers.
3. Every command after Step 2 carries the PATH export from Step 3.
4. Two questions only — project name and database.
