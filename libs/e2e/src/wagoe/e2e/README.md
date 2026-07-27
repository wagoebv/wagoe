# libs/e2e — End-to-end test suite

This library holds no production Clojure source. All code lives under
`test/boundary/e2e/`. The `src/` directory exists only so repository
tooling (`bb check:deps`, build scripts, IDE classpath) treats this
library the same way as the other 22 under `libs/`.

See `../test/boundary/e2e/` for helpers and test namespaces, and the
implementation plan at `docs/superpowers/plans/2026-04-05-playwright-login-e2e.md`
for context.
