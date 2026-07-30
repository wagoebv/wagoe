# wagoe/e2e

[![Status](https://img.shields.io/badge/status-internal-lightgrey)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()

End-to-end browser + HTTP-API test suite for the Wagoe platform. **Test code only** — this library ships no production namespaces and is **not published to Clojars**.

It uses [`com.blockether/spel`](https://github.com/blockether/spel), an idiomatic
Clojure wrapper around Playwright (Java), for both browser automation and
HTTP-API assertions in a single ecosystem — **no Node.js, npm, or TypeScript**.

## Layout

All code lives under `test/` (a placeholder empty `src/` is kept only so
monorepo tooling that assumes the standard library layout doesn't choke):

```
libs/e2e/test/wagoe/e2e/
├── fixtures.clj          # Shared setup (server handle, browser, auth helpers)
├── smoke_test.clj        # Minimal up-and-running check
├── html/                 # Browser (Playwright) flows
│   ├── web_login_test.clj
│   ├── web_register_test.clj
│   ├── admin_users_test.clj
│   └── admin_tenants_test.clj
└── api/                  # HTTP-API flows
    ├── auth_login_test.clj
    ├── auth_register_test.clj
    ├── auth_sessions_test.clj
    └── auth_mfa_test.clj
```

## Running

The suite runs against a live server on port `3100`. The `bb e2e` task starts
that server (on the `:test` profile), waits for `/web/login`, runs the Kaocha
`:e2e` suite, and tears the server down:

```bash
bb e2e
```

To run the server on its own (e.g. to iterate on tests from your editor):

```bash
bb run-e2e-server         # starts the app on :test profile, port 3100
# then, in another shell:
clojure -M:test:e2e --config-file tests.e2e.edn :e2e
```

Playwright downloads its browser binaries on first run.

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
