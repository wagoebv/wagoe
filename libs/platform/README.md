# wagoe/platform

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-platform.svg)](https://clojars.org/com.wagoe/wagoe-platform)

Core infrastructure for web applications: database, HTTP routing, pagination, and system lifecycle management.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {com.wagoe/wagoe-platform {:mvn/version "1.1.0"}
        ;; Choose your database driver
        org.postgresql/postgresql {:mvn/version "42.7.12"}}}
```

**Leiningen**:
```clojure
[com.wagoe/wagoe-platform "1.1.0"]
[org.postgresql/postgresql "42.7.12"]
```

## Features

| Feature | Description |
|---------|-------------|
| **Multi-Database Support** | SQLite, PostgreSQL, MySQL, H2 with unified API |
| **HTTP Routing** | Reitit-based routing with interceptors |
| **Multi-Tenancy** | Tenant resolution + schema switching (middleware lives in the tenant lib) |
| **API Versioning** | Built-in versioning support for REST APIs |
| **Pagination** | Offset and cursor-based pagination |
| **Migrations** | Database migration management with Migratus |
| **System Lifecycle** | Integrant-based component lifecycle |
| **Module System** | Dynamic module registration and discovery |
| **Configuration** | Aero-based environment configuration |

## Requirements

- Clojure 1.12+
- wagoe/core
- wagoe/observability
- Database driver (PostgreSQL, SQLite, MySQL, or H2)

## Database Support

| Database | Driver | Status |
|----------|--------|--------|
| PostgreSQL | `org.postgresql/postgresql` | Production ready |
| SQLite | `org.xerial/sqlite-jdbc` | Development/testing |
| MySQL | `com.mysql/mysql-connector-j` | Production ready |
| H2 | `com.h2database/h2` | Testing only |

## Quick Start

### System Startup

```clojure
(ns myapp.main
  (:require [wagoe.platform.shell.system.wiring :as wiring]
            [wagoe.config :as config]
            [myapp.system-config :as sys-config]))   ; your Integrant config

(defn -main [& args]
  ;; The HTTP port is logged at start: "HTTP server started successfully {:port …}"
  (wiring/start! (sys-config/ig-config (config/load-config))))
```

### HTTP Routes

```clojure
(ns myapp.routes
  (:require [myapp.handlers :as handlers]))

;; Reitit route data. Paths are relative — /api/v1 is added when the
;; module's :api contribution is mounted.
(def routes
  [["/users"
    {:get  {:handler      handlers/list-users
            :summary      "List all users"
            :interceptors ['auth/require-auth]}
     :post {:handler handlers/create-user
            :summary "Create user"}}]
   ["/users/:id"
    {:get    {:handler handlers/get-user}
     :put    {:handler handlers/update-user}
     :delete {:handler handlers/delete-user}}]])
```

### Database Operations

```clojure
(ns myapp.persistence
  (:require [wagoe.platform.database :as db]))

;; Queries are HoneySQL maps; rows come back with kebab-case keys
(defn find-user-by-id [db-ctx user-id]
  (db/execute-one! db-ctx {:select [:*] :from [:users] :where [:= :id user-id]}))

;; Transaction support
(defn transfer-funds [db-ctx from-id to-id amount]
  (db/with-transaction [tx db-ctx]
    (db/execute-update! tx {:update :accounts :set {:balance [:- :balance amount]} :where [:= :id from-id]})
    (db/execute-update! tx {:update :accounts :set {:balance [:+ :balance amount]} :where [:= :id to-id]})))
```

### Pagination

```clojure
(ns myapp.handlers
  (:require [wagoe.platform.core.pagination.pagination :as pagination]))

;; Offset pagination
(defn list-users [request]
  (let [limit  (pagination/parse-limit (get-in request [:query-params "limit"]) 20)
        offset (pagination/parse-offset (get-in request [:query-params "offset"]))
        {:keys [items total]} (user-service/list-users {:limit limit :offset offset})]
    (pagination/create-paginated-response
      items (pagination/calculate-offset-pagination total offset limit) {})))
```

Cursor pagination: `calculate-cursor-pagination` and `extract-cursor-value`, with
`wagoe.platform.shell.pagination.cursor` for encoding.

### Search

Full-text search lives in the dedicated **`libs/search`** module
(`wagoe.search.*`), not in platform. See `libs/search/AGENTS.md`.

### Multi-Tenancy

Multi-tenant middleware, tenant resolution (subdomain/JWT/header), PostgreSQL
schema switching, caching, and the tenant management REST API live in the
**tenant** lib (`wagoe.tenant.shell.tenant-middleware`). The app injects the
tenant middleware into platform's HTTP pipeline via `:extra-middleware` (BOU-200).
See [libs/tenant/README.md](../tenant/README.md).

### Configuration

```clojure
;; resources/conf/prod/config.edn — modules are switched on by their key under :active
{:active
 {:wagoe/postgresql            ; the one database key under :active becomes :wagoe/db-context
  {:host     #env POSTGRES_HOST
   :port     #long #or [#env POSTGRES_PORT 5432]
   :dbname   #env POSTGRES_DB
   :user     #env POSTGRES_USER
   :password #env POSTGRES_PASSWORD
   :pool     {:maximum-pool-size 10}}

  :wagoe/http
  {:port #long #or [#env PORT 3000]
   :host "0.0.0.0"}

  :wagoe/user  {}}}            ; one key per module you enable
```

## Module Structure

```
libs/platform/src/wagoe/platform/
├── core/
│   ├── pagination/          # Offset/cursor pagination, API versioning
│   ├── http/                # Errors, access, client IP, nav, error pages
│   ├── csrf.clj             # CSRF tokens
│   ├── authorization.clj    # Route authorization rules
│   └── scope.clj            # Authorization scopes
├── ports/
│   ├── http.clj             # IHttpServer
│   ├── database.clj         # DBAdapter and engine capabilities
│   └── authorization.clj    # Permission registry, rights
├── shell/
│   ├── adapters/database/   # Database adapters (PostgreSQL, MySQL, SQLite, H2)
│   ├── http/                # Interceptors, Reitit router, Jetty server, flash
│   ├── interceptors.clj     # Universal cross-cutting interceptors
│   ├── database/            # Migrations, seeds
│   ├── rpc/                 # Remote-port client and server
│   └── system/
│       ├── config.clj       # Integrant config from the loaded config
│       └── wiring.clj       # Integrant init/halt, start!/stop!
├── database.clj             # Data-access API for modules
├── lease.clj                # Database-clock lease
└── schema.clj               # Platform schema definitions
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `wagoe/observability` | 1.1.0 | Logging, metrics |
| `next.jdbc` | 1.3.1118 | Database access |
| `honeysql` | 2.7.1437 | SQL generation |
| `HikariCP` | 7.1.0 | Connection pooling |
| `migratus` | 1.6.7 | Database migrations |
| `reitit` | 0.10.1 | HTTP routing |
| `ring` | 1.15.5 | HTTP abstractions |
| `integrant` | 1.0.1 | System lifecycle |
| `aero` | 1.1.6 | Configuration |

## Relationship to Other Libraries

```
┌─────────────────────────────────────────┐
│       user, admin, storage, etc.        │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│           wagoe/platform             │
│   (database, HTTP, config, modules)     │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│   wagoe/observability + wagoe/core│
└─────────────────────────────────────────┘
```

## Development

```bash
# Run tests (from the repository root; embedded PostgreSQL needs :test/pg, plus :test/pg-mac on Apple Silicon)
clojure -M:test:test/pg :platform

# Lint
clojure -M:clj-kondo --lint libs/platform/src libs/platform/test

# Run migrations
clojure -M:migrate up
```

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
