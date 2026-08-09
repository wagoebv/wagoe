---
name: wagoe-run
description: Boot a Wagoe app and confirm a change works in the running application rather than only in tests. Use when asked to run, start or open the app, to check a page or endpoint, to reproduce a bug against a live server, or to keep a REPL-driven system going while editing. Covers server and worker mode, the REPL lifecycle, which profile needs no external services, and how to find the port the server actually took.
---

# Wagoe — running the app

```bash
# HTTP server, no external services (in-memory H2)
JWT_SECRET="dev-secret-at-least-32-characters-long" WAG_ENV=test clojure -M:run server

# Background worker, no HTTP listener
JWT_SECRET="…" WAG_ENV=test clojure -M:run worker

# REPL-driven, for editing while it runs
JWT_SECRET="…" WAG_ENV=test clojure -M:repl-clj      # nREPL on 7888
```

`JWT_SECRET` is required and must be at least 32 characters. Startup takes
roughly 30 seconds — the system runs migrations and initialises every module —
so poll rather than assuming failure at 10.

## Pick the profile before anything else

| `WAG_ENV` | Database | Needs installing |
|---|---|---|
| `test` | in-memory H2 | nothing |
| `dev` | PostgreSQL `wagoe_dev` on :5432 | a running PostgreSQL |
| `acc`, `prod` | from environment | everything |

**`dev` is not the zero-setup profile.** Without PostgreSQL it fails after
about 40 seconds of startup with

```
Error on key :wagoe/db-context when building system
Caused by: FATAL: database "wagoe_dev" does not exist
```

That is a stack trace, not a message — if you see one, read the `Caused by:`
line at the bottom rather than the Hikari frames above it.

Use `test` to see a change working. Use `dev` when the change concerns
PostgreSQL specifically, and start a database first.

`development`, `production`, `acceptance` and `testing` are accepted as aliases
for the four short names. An unrecognised value throws `Configuration file not
found` rather than silently falling back.

## Never assume the port

The server takes the configured port if free and otherwise scans a range, so
the port it ends up on is a fact to read, not to guess:

| Profile | Configured | Range on conflict |
|---|---|---|
| `dev` | 3000 | 3000–3099 |
| `test` | 3100 | 3100–3199 |

`HTTP_PORT` overrides the configured port. The allocated one is logged:

```
HTTP server started successfully {:port 3101, :url http://0.0.0.0:3101, …}
HTTP Server port conflict resolved {:requested-port 3100, :allocated-port 3101}
```

Grep the log for `HTTP server started successfully` and take `:port` from
there. From a REPL:

```clojure
(-> integrant.repl.state/system :wagoe/http-server .getURI str)
;; => "http://0.0.0.0:3100/"
```

## Verifying a change

Starting the server proves it starts. It does not prove your change works —
finish the loop:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3100/web/login   # 200
curl -s http://localhost:3100/api/v1/<your-route> | head
```

For a page, fetch it and grep for the text or element you added. If the project
has Playwright wired up, `bb e2e` starts a server on 3100, runs the suite and
tears it down — use that rather than driving a browser by hand.

A change that only shows up after a request needs a request. Boot, hit the
thing, read the response.

## The REPL loop is the one to use while editing

```clojure
(require '[integrant.repl :as ig-repl])
(ig-repl/go)      ; start
(ig-repl/reset)   ; reload changed namespaces and restart
(ig-repl/halt)    ; stop
```

`reset` is enough for ordinary edits. **After changing a `defrecord` it is
not** — it reuses the existing instances. Use `(halt)` then `(go)`, or restart
the REPL.

Drive it from the shell without an editor attached:

```bash
clj-nrepl-eval --discover-ports
clj-nrepl-eval -p 7888 "(do (require '[integrant.repl :as ig-repl]) (ig-repl/go) :up)"
```

Inspect a live component instead of adding logging:

```clojure
(def svc (get integrant.repl.state/system :wagoe/user-service))
(def ds  (get-in integrant.repl.state/system [:wagoe/db-context :datasource]))
(jdbc/execute! ds ["SELECT * FROM users LIMIT 5"])
```

## Worker mode

`clojure -M:run worker` boots the same system minus the HTTP surface — it logs
`Starting Wagoe worker (no HTTP listener)` and opens no port. Use it to check
that a background job runs without a web server in the way. Nothing to curl;
read the log.

## Steps

1. Choose the profile. `test` unless the change is about PostgreSQL.
2. Start the server, redirecting output to a file you can grep.
3. Poll until it answers — up to about 60 seconds. Take the port from the log,
   not from the table above.
4. Exercise the change with `curl`, and check the response, not just the status.
5. Stop it when you are done. A stray server holds its port, and the next boot
   will silently pick a different one.

## What this does not cover

Nothing here touches a deployed environment; `WAG_ENV=prod` against a real
database is a deploy concern. Config problems are `bb doctor`'s job — if
startup fails on configuration rather than on the database, run that first.
