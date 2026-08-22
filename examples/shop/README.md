# shop — a generated Wagoe application

This directory is not written by hand. It is the output of:

```bash
wagoe new shop
bb scaffold generate --module-name product --entity Product \
  --field name:string:required --field sku:string:required --field price:decimal
bb scaffold integrate product
```

with one edit: the `com.wagoe/*` dependencies point at `../../libs/*` instead of
published versions, so the example runs against this checkout rather than the
last release.

`bb example:regen` reproduces it and CI runs `bb example:regen --check`, so the
example cannot quietly drift from what the generators produce. If a change to
the scaffolder shows up as a diff here, that is the point — this directory is
what every new project gets.

## Run it

```bash
WAG_ENV=test JWT_SECRET=$(openssl rand -hex 32) clojure -M:run
```

The test profile uses H2 in memory, so there is nothing to install and nothing
to clean up afterwards. Then:

```bash
curl localhost:3000/api/v1/products     # => []   the scaffolded module
curl localhost:3000/health              # => the platform's health endpoint
curl -i localhost:3000/api/products     # => 307 to /api/v1/products
```

`scripts/example-smoke.sh` asserts exactly those, in CI, on every commit.

For a database that survives a restart, `WAG_ENV=dev` uses SQLite — copy
`.env.example` to `.env` first, and run `clojure -M:migrate up`.

## What to read

| Path | What it shows |
|---|---|
| `src/wagoe/product/core/product.clj` | pure functions — no clock, no database |
| `src/wagoe/product/shell/service.clj` | where the clock and the ID generator live |
| `src/wagoe/product/shell/http.clj` | Reitit route data, at paths relative to `/api/v1` |
| `src/wagoe/product/shell/module_wiring.clj` | the four Integrant components a module has |
| `src/wagoe/system_config.clj` | how the app assembles the framework and its own keys |
| `resources/conf/test/config.edn` | the module key `bb scaffold integrate` wrote |

The generated handlers return canned responses. Wiring them to the service is
the intended first edit — see
[Your First Module](../../docs/modules/getting-started/pages/your-first-module.adoc).

## Two things the generator does that are worth knowing

**Modules land under `wagoe.`, not under your project's namespace.** This app is
`shop`, and its module is `wagoe.product`. That is what module discovery
resolves today; `bb scaffold generate --base-ns shop` writes the files
elsewhere, and `bb scaffold integrate` then refuses and tells you why.

**The test profile has no `:wagoe/http` block,** so `HTTP_PORT` is not read
under `WAG_ENV=test` and the port is the platform default. The smoke script
reads the port from the app's own startup log rather than assuming one.
