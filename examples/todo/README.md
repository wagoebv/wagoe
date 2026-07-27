# Todo — a runnable Wagoe example

A tiny, self-contained example that demonstrates Wagoe's **Functional Core /
Imperative Shell** (FC/IS) pattern with a real database. No HTTP server, no
configuration, no setup — it runs to completion in a couple of seconds.

## Run it

```bash
cd examples/todo
clojure -M:run
```

Expected output:

```
Adding todos…
3 todos, 2 remaining:
  [ ] Write the docs
  [ ] Ship the example
  [x] Buy milk
Done.
```

It boots an in-memory H2 database, adds a few todos through the service, marks
one done, and lists them.

## What it shows

The module follows the same FC/IS shape every Wagoe module uses:

| File | Layer | Responsibility |
|------|-------|----------------|
| `src/todo/schema.clj` | schema | Malli schemas (`Todo`, `TodoInput`) |
| `src/todo/core/todo.clj` | **functional core** | Pure rules — validation, entity building, `remaining`. No I/O; `now`/`id` are passed in. |
| `src/todo/ports.clj` | port | `ITodoRepository` protocol — the seam between shell and storage |
| `src/todo/shell/persistence.clj` | **imperative shell** | H2 repository; the *only* place snake_case ↔ kebab-case conversion happens (via `wagoe.core.utils.case-conversion`) |
| `src/todo/shell/service.clj` | **imperative shell** | Orchestration — validates input, calls the core, persists |
| `src/todo/main.clj` | entry point | Boots the DB and drives the workflow |

Key ideas on display:

- **The core is pure.** `todo.core.todo` has no database and no clock — the
  shell injects `now` and the id, so the core is deterministic and testable
  without mocks.
- **Conversion lives at the boundary.** Everything internal is kebab-case;
  `snake_case` only appears in `todo.shell.persistence`, converted with the
  shared `wagoe.core` helpers.
- **Storage is behind a port.** Swap `H2TodoRepository` for another
  `ITodoRepository` and neither the core nor the service changes.

## About the dependency

`deps.edn` pulls `wagoe-core` via `:local/root "../../libs/core"`, so this
example runs against the source in this repository with no publish step. A real
project would instead use the published coordinate:

```clojure
org.wagoe/wagoe-core {:mvn/version "1.0.0-beta-1"}
```

## Next

For a guided tour of building a full module (schema → core → ports → shell) with
the scaffolder, see
[Your First Module](../../docs/modules/getting-started/pages/your-first-module.adoc).
