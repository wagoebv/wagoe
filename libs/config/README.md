# wagoe-config

[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-config.svg)](https://clojars.org/com.wagoe/wagoe-config)

Configuration loading and typed accessors for Wagoe applications.

```clojure
{:deps {com.wagoe/wagoe-config {:mvn/version "1.0.0-beta-5"}}}
```

## Usage

```clojure
(require '[wagoe.config :as config])

(def cfg (config/load-config))   ; reads conf/<WAG_ENV>/config.edn via Aero
(config/db-spec cfg)             ; => {:adapter :sqlite :database-path "app.db" ...}
(config/http-config cfg)
(config/logging-config cfg)
```

`load-config` resolves the profile from `WAG_ENV` and accepts both spellings —
`production` and `prod` name the same directory.

## What it does not do

Assemble an Integrant system. Which components an application runs is the
application's decision; see the `system_config.clj` that `wagoe new` generates.

## Accessors

| Function | Reads |
|----------|-------|
| `db-adapter` / `db-spec` | The active database. Exactly one adapter section must be present — none is an error, not a default |
| `http-config` | Port, host, server settings |
| `app-config` / `default-tenant-id` | Application identity and the development tenant |
| `user-validation-config` | Password and profile rules for `wagoe-user` |
| `logging-config` / `metrics-config` / `tracing-config` / `error-reporting-config` | Observability adapters; each defaults rather than throwing |

See [AGENTS.md](AGENTS.md) for design notes.
