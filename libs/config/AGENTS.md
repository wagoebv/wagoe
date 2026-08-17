# wagoe-config — Configuration Library

`com.wagoe/wagoe-config` — Aero-based configuration loading and the typed
accessors libraries read settings through.

---

## Overview

Loads `resources/conf/<env>/config.edn` and exposes what libraries need from it.
It does **not** assemble an Integrant system — that is the application's job, in
`wagoe.system-config` here or in the `system_config.clj` that `wagoe new`
generates.

### Key design decisions

- **Reading, not composing.** Every function takes a loaded config map and
  returns a section of it. Nothing in here knows which components an
  application runs.
- **No Wagoe dependencies.** aero, tools.logging and Clojure. Anything else
  would put this library above the ones that read configuration.
- **Absent optional sections default; a missing database does not.** Boot-time
  reads happen on every start, so throwing for an absent observability section
  would make every optional feature mandatory. An unconfigured database is the
  opposite: defaulting would boot against something nobody chose.

## Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.config` | `load-config` and the typed accessors |

## Accessors

| Function | Reads |
|----------|-------|
| `db-adapter` / `db-spec` | Active database; one of `:wagoe/sqlite`, `:wagoe/h2`, `:wagoe/postgresql`, `:wagoe/mysql` |
| `http-config` | Port, host, server settings |
| `app-config` / `default-tenant-id` | Application identity, development tenant |
| `user-validation-config` | Password and profile rules for `wagoe-user` |
| `logging-config` / `metrics-config` / `tracing-config` / `error-reporting-config` | Observability adapters |

## Why this is a library (BOU-306)

Four published libraries read configuration. They used to do it with
`requiring-resolve` against a `wagoe.config` the monorepo and `wagoe new` both
happened to provide — a convention nothing declared and nothing checked, in one
case resting on a private var. Anyone assembling a Wagoe application by hand had
no such namespace.

`bb check:isolation` now fails a library that loads a namespace it neither owns
nor declares, so the shape cannot come back.
