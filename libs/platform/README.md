# wagoe/platform

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-platform.svg)](https://clojars.org/com.wagoe/wagoe-platform)

Core infrastructure for web applications: database, HTTP routing, pagination, search, and system lifecycle management.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {com.wagoe/wagoe-platform {:mvn/version "1.0.0-beta-4"}
        ;; Choose your database driver
        org.postgresql/postgresql {:mvn/version "42.7.12"}}}
```

**Leiningen**:
```clojure
[com.wagoe/wagoe-platform "1.0.0-beta-4"]
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
| **Search** | Full-text search with ranking and filtering |
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
            [wagoe.config :as config]))

(defn -main [& args]
  (let [cfg (config/load-config "production")
        system (wiring/start! cfg)]
    (println "System started on port" (get-in system [:wagoe/http :port]))
    system))
```

### HTTP Routes

```clojure
(ns myapp.routes
  (:require [wagoe.platform.ports.http :as http]))

;; Define normalized routes
(def routes
  [{:path "/api/users"
    :methods {:get {:handler 'myapp.handlers/list-users
                    :summary "List all users"
                    :interceptors ['auth/require-auth]}
              :post {:handler 'myapp.handlers/create-user
                     :summary "Create user"}}}
   {:path "/api/users/:id"
    :methods {:get {:handler 'myapp.handlers/get-user}
              :put {:handler 'myapp.handlers/update-user}
              :delete {:handler 'myapp.handlers/delete-user}}}])
```

### Database Operations

```clojure
(ns myapp.persistence
  (:require [wagoe.platform.shell.adapters.database.common.execution :as db]))

;; Query with connection pool
(defn find-user-by-id [db-ctx user-id]
  (db/execute-one! db-ctx
    ["SELECT * FROM users WHERE id = ?" user-id]))

;; Transaction support
(defn transfer-funds [db-ctx from-id to-id amount]
  (db/with-transaction* db-ctx
    (fn [tx]
      (db/execute! tx ["UPDATE accounts SET balance = balance - ? WHERE id = ?" amount from-id])
      (db/execute! tx ["UPDATE accounts SET balance = balance + ? WHERE id = ?" amount to-id]))))
```

### Pagination

```clojure
(ns myapp.handlers
  (:require [wagoe.platform.core.pagination :as pagination]))

;; Offset pagination
(defn list-users [request]
  (let [params (pagination/parse-offset-params request {:default-limit 20 :max-limit 100})
        result (user-service/list-users params)]
    (pagination/wrap-offset-response result params)))

;; Cursor pagination  
(defn list-events [request]
  (let [params (pagination/parse-cursor-params request)
        result (event-service/list-events params)]
    (pagination/wrap-cursor-response result params)))
```

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
;; resources/conf/prod/config.edn
{:wagoe/db-context
 {:adapter :postgresql
  :host #env DB_HOST
  :port #env [DB_PORT :int 5432]
  :database #env DB_NAME
  :username #env DB_USER
  :password #env DB_PASSWORD
  :pool-size 10}

 :wagoe/http
 {:port #env [PORT :int 3000]
  :host "0.0.0.0"}

 :wagoe/modules
 [:wagoe.user.shell.module-wiring
  :wagoe.admin.shell.module-wiring]}
```

## Module Structure

```
libs/platform/src/wagoe/platform/
├── core/
│   ├── pagination.clj       # Pagination utilities
│   ├── search.clj           # Search utilities
│   └── api-versioning.clj   # API version handling
├── ports/
│   ├── http.clj             # HTTP port protocol
│   └── database.clj         # Database port protocol
├── shell/
│   ├── adapters/
│   │   ├── database/        # Database adapters (SQLite, PG, MySQL)
│   │   └── http/            # HTTP server adapter
│   ├── interceptors/        # HTTP interceptors
│   └── system/
│       └── wiring.clj       # Integrant system config
└── schema.clj               # Platform schema definitions
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `wagoe/observability` | 1.0.0-beta-4 | Logging, metrics |
| `next.jdbc` | 1.3.1086 | Database access |
| `honeysql` | 2.7.1364 | SQL generation |
| `HikariCP` | 7.0.2 | Connection pooling |
| `migratus` | 1.6.4 | Database migrations |
| `reitit` | 0.9.2 | HTTP routing |
| `ring` | 1.15.3 | HTTP abstractions |
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
# Run tests
cd libs/platform
clojure -M:test

# Lint
clojure -M:clj-kondo --lint src test

# Run migrations
clojure -M:migrate up
```

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
