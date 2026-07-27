# Multi-Database Adapter System

A comprehensive database abstraction layer that provides unified access to multiple database engines while maintaining full backward compatibility with existing SQLite-based code.

## Supported Databases

- **SQLite** - Lightweight embedded database for development and small applications
- **H2** - Fast in-memory and file-based database with PostgreSQL compatibility
- **PostgreSQL** - Enterprise-grade database with advanced features and JSON support
- **MySQL** - Popular open-source database with JSON support and high performance

## Architecture Overview

The system is built on a clean layered architecture with proper separation of concerns:

```
┌─────────────────────────────────────────────────────────────┐
│              Business Logic Layer                           │
│        (boundary.<module>.shell.service)                    │
│               - Database agnostic                           │
│               - Uses dependency injection                   │
├─────────────────────────────────────────────────────────────┤
│              Repository Interfaces                          │
│            (boundary.<module>.ports)                        │
├─────────────────────────────────────────────────────────────┤
│              Database Layer                                 │
│     (boundary.<module>.shell.persistence                    │
│               - Implements repositories                     │
│               - Handles entity transformations              │
├─────────────────────────────────────────────────────────────┤
│              Common Database API                            │
│  (boundary.platform.shell.adapters.database.common.core)             │
│               - Connection pool management                  │
│               - Query execution & transactions              │
│               - Schema introspection                        │
├─────────────────────────────────────────────────────────────┤
│              Schema Generation                              │
│    (boundary.platform.shell.adapters.database.schema)                │
│               - Malli to DDL conversion                     │
├─────────────────────────────────────────────────────────────┤
│              DBAdapter Protocol                             │
│   (boundary.platform.shell.adapters.database.protocols)              │
├─────────────────────────────────────────────────────────────┤
│  SQLite      │    H2        │  PostgreSQL  │    MySQL       │
│  Module      │   Module     │   Module     │   Module       │
│  ├─core.clj  │  ├─core.clj  │  ├─core.clj  │  ├─core.clj    │
│  ├─connection│  ├─connection│  ├─connection│  ├─connection  │
│  ├─query     │  ├─query     │  ├─query     │  ├─query       │
│  ├─metadata  │  ├─metadata  │  ├─metadata  │  ├─metadata    │
│  └─utils     │  └─utils     │  └─utils     │  └─utils       │
└─────────────────────────────────────────────────────────────┘
```

## Key Features

### Database Agnostic
- Write once, run on any supported database
- Consistent API across all database engines
- Automatic query translation and optimization

### Production Ready
- Robust connection pooling with HikariCP
- Transaction support with rollback capabilities
- Comprehensive error handling and logging
- Performance monitoring and optimization

### Advanced Features
- Native upsert operations for all databases
- JSON support where available (PostgreSQL, MySQL)
- Batch operations for improved performance
- Schema introspection and validation
- Database-specific optimizations

## Quick Start

### 1. Add Dependencies

Ensure your `deps.edn` includes the necessary JDBC drivers:

```clojure
{:deps {org.xerial/sqlite-jdbc {:mvn/version "3.50.3.0"}
        com.h2database/h2 {:mvn/version "2.3.232"}
        org.postgresql/postgresql {:mvn/version "42.7.7"}
        com.mysql/mysql-connector-j {:mvn/version "9.1.0"}
        com.zaxxer/HikariCP {:mvn/version "7.0.2"}}}
```

### 2. Create Database Context

```clojure
(require '[boundary.platform.shell.adapters.database.factory :as dbf])

;; SQLite (file-based)
(def sqlite-ctx (dbf/db-context {:adapter :sqlite
                                 :database-path "./data/app.db"}))

;; H2 (in-memory)
(def h2-ctx (dbf/db-context {:adapter :h2
                             :database-path "mem:testdb"}))

;; PostgreSQL
(def pg-ctx (dbf/db-context {:adapter :postgresql
                             :host "localhost"
                             :port 5432
                             :database "myapp"
                             :username "user"
                             :password "password"}))

;; MySQL
(def mysql-ctx (dbf/db-context {:adapter :mysql
                                :host "localhost"
                                :port 3306
                                :database "myapp"
                                :username "root"
                                :password "password"}))
```

### 3. Execute Queries

```clojure
(require '[boundary.platform.shell.adapters.database.common.core :as db])

;; The same query works with any database context
(db/execute-query! ctx {:select [:*] :from [:users] :where [:= :active true]})

;; Insert data
(db/execute-update! ctx {:insert-into :users
                         :values [{:name "John Doe"
                                  :email "john@example.com"
                                  :active true}]})

;; Use transactions
(db/with-transaction [tx ctx]
  (db/execute-update! tx {:insert-into :users :values [user-data]})
  (db/execute-update! tx {:update :audit :set {:last-modified (java.time.Instant/now)}}))
```

### 4. Use Database-Agnostic User System

```clojure
(require '[boundary.platform.shell.adapters.database.user :as db-user]
         '[boundary.user.shell.service :as user-service])

;; Initialize schema
(db-user/initialize-user-schema! ctx)

;; Create repositories (database layer)
(def user-repo (db-user/create-user-repository ctx))
(def session-repo (db-user/create-session-repository ctx))

;; Create service with dependency injection (business layer)
(def service (user-service/create-user-service user-repo session-repo))

;; Use service for business operations (database-agnostic)
(def user (.create-user service {:email "test@example.com"
                                :name "Test User"
                                :role :admin
                                :active true
                                :tenant-id (random-uuid)}))

(def found-user (.find-user-by-email service "test@example.com" tenant-id))
```

## Configuration Examples

### Environment-Based Configuration

```clojure
(require '[boundary.platform.shell.adapters.database.factory :as dbf])

;; Configure from environment variables
;; DB_ADAPTER=postgresql DB_HOST=localhost DB_PORT=5432 DB_NAME=myapp ...
(def ctx (dbf/db-context (dbf/db-config-from-env)))
```

### Development vs Production

```clojure
;; Development (H2 in-memory)
(def dev-ctx (dbf/db-context {:adapter :h2
                              :database-path "mem:devdb"
                              :pool {:maximum-pool-size 5}}))

;; Production (PostgreSQL with connection pooling)
(def prod-ctx (dbf/db-context {:adapter :postgresql
                               :host "db.example.com"
                               :port 5432
                               :database "production"
                               :username "app_user"
                               :password (System/getenv "DB_PASSWORD")
                               :pool {:minimum-idle 5
                                     :maximum-pool-size 20
                                     :connection-timeout-ms 30000}}))
```

### Connection Pooling Configuration

```clojure
(def ctx (dbf/db-context {:adapter :postgresql
                          :host "localhost"
                          :database "myapp"
                          :pool {:minimum-idle 2
                                :maximum-pool-size 10
                                :connection-timeout-ms 30000
                                :idle-timeout-ms 600000
                                :max-lifetime-ms 1800000
                                :validation-timeout-ms 5000}}))
```

## Database-Specific Features

### SQLite
- WAL mode for better concurrency
- Optimized PRAGMA settings
- Boolean values stored as integers
- Text-based UUID storage

### H2
- PostgreSQL compatibility mode
- In-memory and file-based storage
- Native boolean support
- MERGE statements for upserts

### PostgreSQL
- Full ACID compliance
- JSON and JSONB support
- Array data types
- ON CONFLICT for upserts
- Advanced indexing options

### MySQL
- InnoDB storage engine
- JSON support (MySQL 5.7+)
- ON DUPLICATE KEY UPDATE for upserts
- UTF8MB4 character set by default

## Performance Considerations

### Connection Pooling
- SQLite: Small pools (1-5 connections) due to file locking
- H2: Medium pools (5-10 connections) for in-memory databases
- PostgreSQL/MySQL: Large pools (10-50 connections) for server databases

### Batch Operations
- Use `db/execute-batch!` for multiple similar operations
- Prefer upsert operations over select-then-insert patterns
- Use transactions for related operations

### Query Optimization
- All adapters support query explanation and analysis
- Database-specific optimizations applied automatically
- Connection-level settings tuned per database type

## Testing

The system includes comprehensive tests for all databases:

```bash
# Run basic tests (SQLite and H2 only, no external dependencies)
clojure -M:test:db/h2 boundary.platform.shell.adapters.database.multi-db-test/run-basic-tests

# Run integration tests (requires PostgreSQL and MySQL servers)
clojure -M:test:db/h2 boundary.platform.shell.adapters.database.multi-db-test/run-integration-tests

# Run performance tests
clojure -M:test:db/h2 boundary.platform.shell.adapters.database.multi-db-test/run-performance-tests
```

## Troubleshooting

### Common Issues

1. **JDBC Driver Not Found**
   - Ensure the appropriate JDBC driver is in your dependencies
   - Check that the driver class name matches the database type

2. **Connection Pool Exhaustion**
   - Increase pool size or reduce connection timeout
   - Ensure connections are properly closed after use
   - Check for long-running transactions

3. **SQL Dialect Differences**
   - The system handles most differences automatically
   - For custom queries, use database-specific adapters

4. **Performance Issues**
   - Enable query logging to identify slow queries
   - Use connection pooling appropriately
   - Consider database-specific optimizations

### Logging

Enable debug logging to troubleshoot issues:

```clojure
;; Add to your logging configuration
{"boundary.platform.shell.adapters.database" :debug
 "com.zaxxer.hikari" :debug}
```

## Contributing

### Adding New Database Support

1. Implement the `DBAdapter` protocol
2. Add JDBC driver dependency
3. Update factory configuration
4. Add comprehensive tests
5. Update documentation

### Running Tests

```bash
# Install test databases (Docker)
docker run -d --name test-postgres -e POSTGRES_PASSWORD=test -p 5432:5432 postgres:13
docker run -d --name test-mysql -e MYSQL_ROOT_PASSWORD=test -p 3306:3306 mysql:8

# Run all tests
clojure -M:test:db/h2

# Run specific database tests
clojure -M:test:db/h2 -i :integration  # PostgreSQL/MySQL tests
clojure -M:test:db/h2 -e :integration  # SQLite/H2 tests only
```

## License

See the main project license.

## Modular Architecture

The system has been refactored into a clean modular architecture where each database adapter and the common functionality are organized into focused namespaces:

### Common Module Structure
```
common/
├── core.clj        # Main entry point - coordinates all common functionality
├── connection.clj  # HikariCP connection pool management
├── query.clj       # HoneySQL formatting and query building utilities
├── execution.clj   # Query execution with logging and error handling
├── schema.clj      # Schema introspection and DDL execution
└── utils.clj       # Database information and convenience functions
```

### Database Adapter Module Structure
Each database adapter (SQLite, H2, PostgreSQL, MySQL) follows the same pattern:
```
<adapter>/
├── core.clj        # Main entry point - implements DBAdapter protocol
├── connection.clj  # Database-specific connection management
├── query.clj       # Database-specific query building and conversions
├── metadata.clj    # Database-specific table introspection
└── utils.clj       # Database-specific utilities and DDL helpers
```

### Benefits of Modular Design
- **Focused Responsibilities**: Each module has a single, clear purpose
- **Easier Maintenance**: Changes are isolated to relevant modules
- **Better Testability**: Each module can be tested independently
- **Consistent Patterns**: All adapters follow the same structure
- **Clear Documentation**: Module purposes are clearly defined
