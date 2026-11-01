# Platform SQL Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

`com.wagoe/wagoe-platform-sql`: the SQL/JDBC half of platform. Engine adapters,
migrations, seeding, leases and the transit wire format, without Jetty, Reitit,
Muuntaja or Buddy. `wagoe-platform` depends on it, so an application on platform
gets it unchanged. A library that needs only the database depends on this one
instead (BOU-871): jobs, events and storage carry no HTTP stack.

The namespaces kept their `wagoe.platform.*` names when they moved here.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.platform.database` | Data-access API for modules: queries, transactions, `upsert!` |
| `wagoe.platform.ports.database` | `DBAdapter` and the per-engine capability protocols (ADR-039) |
| `wagoe.platform.shell.adapters.database.*` | Engine adapters (PostgreSQL, MySQL, SQLite, H2), connection pooling |
| `wagoe.platform.shell.database.*` | Migrations, seeding, `db:reset`, and their CLIs |
| `wagoe.platform.core.database.*` | Pure query, constraint, migration-SQL and seed logic |
| `wagoe.platform.lease` | A lease row replicas take turns holding, timed by the database clock |
| `wagoe.platform.transit` | Transit JSON for events and jobs that leave the process |

## Gotchas

- Migrations skip switched-off modules by asking platform's module registry
  (`wagoe.platform.shell.modules`), resolved at run time: platform depends on
  this library. Without platform on the classpath, `migrate` runs every directory.
- Database configuration and `db-context` are documented in
  [platform's AGENTS.md](../platform/AGENTS.md#database-integration).

## Testing

```bash
clojure -M:test:test/pg :platform-sql          # add :test/pg-mac on Apple Silicon
bb test:services up                            # the conformance suite needs MySQL
```

`wagoe.platform.database-adapter-contract-test` is the adapter conformance suite:
one set of cases on all four engines.
