# libs/e2e — End-to-end test suite

This library holds no production Clojure source. All code lives under
`test/wagoe/e2e/`. The `src/` directory exists only so repository
tooling (`bb check:deps`, build scripts, IDE classpath) treats this
library the same way as the 29 published ones under `libs/`. Unlike those,
`libs/e2e` is not published to Clojars.

See `../test/wagoe/e2e/` for helpers and test namespaces.
