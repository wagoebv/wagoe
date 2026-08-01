# Multi-Tenancy Module

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-tenant.svg)](https://clojars.org/com.wagoe/wagoe-tenant)

Multi-tenancy for the Wagoe Framework — isolated PostgreSQL schemas, tenant-scoped caching, and automatic tenant context propagation across background jobs.

## Features

- **Schema-Per-Tenant**: PostgreSQL schema isolation for complete data separation
- **Automatic Provisioning**: Create and configure tenant schemas programmatically
- **Tenant Resolution**: Extract tenant context from subdomains, headers, or tokens
- **Cross-Module Integration**: Seamless tenant context in jobs and cache modules
- **Middleware Support**: Automatic tenant context injection into HTTP requests
- **Flexible Identification**: Support for subdomains, API keys, or custom strategies

## Quick Start

### 1. Create Tenant

```clojure
(ns my-app.tenants
  (:require [wagoe.tenant.ports :as tenant-ports]))

;; Create new tenant
(def tenant-input
  {:slug "acme-corp"
   :name "ACME Corporation"
   :settings {:features {:api true :analytics false}
              :limits {:users 100 :storage-gb 50}}})

(def tenant (tenant-ports/create-new-tenant tenant-service tenant-input))
;; => {:id #uuid "..." 
;;     :slug "acme-corp" 
;;     :name "ACME Corporation" 
;;     :status :active
;;     :created-at #inst "..."}
```

### 2. Provision Tenant (PostgreSQL)

```clojure
(require '[wagoe.tenant.shell.provisioning :as provisioning])

;; Provision creates isolated schema and copies structure
(provisioning/provision-tenant! db-ctx tenant)
;; → Creates schema: tenant_acme_corp
;; → Copies tables from public schema
;; → Sets up constraints and indexes
;; → Marks tenant as :provisioned

;; Verify provisioning status
(tenant-ports/find-tenant-by-id tenant-service (:id tenant))
;; => {:id #uuid "..." :slug "acme-corp" :status :provisioned ...}
```

### 3. Use Tenant Schema

```clojure
;; Execute queries in tenant schema
(provisioning/with-tenant-schema 
  db-ctx 
  "tenant_acme_corp"
  (fn [tenant-ctx]
    ;; All queries run in tenant_acme_corp schema
    (jdbc/execute! tenant-ctx 
                   ["INSERT INTO users (name, email) VALUES (?, ?)"
                    "Alice" "alice@acme-corp.com"])
    
    (jdbc/execute! tenant-ctx 
                   ["SELECT * FROM users"])))
;; => Returns only users from ACME Corporation's schema
```

### 4. HTTP Tenant Resolution

Tenant middleware lives in the **tenant** library (`wagoe.tenant.shell.tenant-middleware`):

```clojure
(require '[wagoe.tenant.shell.tenant-middleware :as tenant-mw])

;; Add tenant resolution middleware (resolves tenant from subdomain/header/token)
(def app
  (-> routes
      (tenant-mw/wrap-tenant-resolution tenant-service
        {:require-tenant? true})))  ; Pass true to enforce tenant on all routes

;; Handler receives tenant context
(defn get-users-handler [request]
  (let [tenant (:tenant request)  ; Extracted from subdomain/header/token
        tenant-id (:id tenant)]
    ;; Use tenant-id for queries, jobs, cache
    {:status 200
     :body {:tenant (:slug tenant)
            :users (list-tenant-users db-ctx tenant-id)}}))
```

**Middleware locations** (all in the tenant lib since BOU-198):
- `wagoe.tenant.shell.tenant-middleware`: `wrap-tenant-resolution` (resolves tenant from request), `wrap-tenant-schema` (sets DB search_path), `wrap-multi-tenant` (combines both)
- `wagoe.tenant.shell.membership-middleware`: `wrap-tenant-membership` (checks user membership in tenant)

## Tenant Provisioning

### What Provisioning Does

Provisioning creates an isolated PostgreSQL schema for each tenant:

1. **Create Schema**: `CREATE SCHEMA tenant_<slug>`
2. **Copy Structure**: Copy all tables, indexes, constraints from `public` schema
3. **Initialize Data**: Optionally seed tenant-specific data
4. **Update Status**: Mark tenant as `:provisioned`

```clojure
;; Before provisioning
{:id #uuid "..." :slug "acme-corp" :status :active}

;; After provisioning
{:id #uuid "..." :slug "acme-corp" :status :provisioned}
```

### Provisioning API

```clojure
(require '[wagoe.tenant.shell.provisioning :as provisioning])

;; Provision new tenant
(provisioning/provision-tenant! db-ctx tenant)
;; → Returns: {:schema "tenant_acme_corp" :status :success}

;; Check if tenant is provisioned
(provisioning/tenant-provisioned? db-ctx tenant)
;; => true

;; Execute in tenant schema
(provisioning/with-tenant-schema db-ctx "tenant_acme_corp"
  (fn [tenant-ctx]
    (jdbc/execute! tenant-ctx ["SELECT * FROM users"])))

;; Deprovision tenant (careful!)
(provisioning/deprovision-tenant! db-ctx tenant)
;; → Drops schema: tenant_acme_corp (irreversible!)
```

### Schema Lifecycle

```
1. Create Tenant
   ↓
2. Provision Schema (PostgreSQL only)
   ↓
3. Use Tenant (queries, jobs, cache)
   ↓
4. Suspend Tenant (optional - keep data)
   ↓
5. Deprovision (remove schema - irreversible)
```

**States**:
- `:active` - Tenant created, schema not yet provisioned
- `:provisioned` - Schema created and ready for use
- `:suspended` - Tenant access disabled (schema preserved)
- `:deleted` - Soft delete (schema can be deprovisioned)

### PostgreSQL vs Non-PostgreSQL

**PostgreSQL (Schema-Per-Tenant)**:
```clojure
;; Automatic schema switching
(provisioning/with-tenant-schema db-ctx "tenant_acme_corp"
  (fn [ctx]
    (jdbc/execute! ctx ["SELECT * FROM users"])))
;; → Executes: SET search_path TO tenant_acme_corp
;; → Query: SELECT * FROM users  (in tenant schema)
;; → Resets: SET search_path TO public
```

**Other Databases (Row-Level Filtering)**:
```clojure
;; Manual tenant_id filtering required
(jdbc/execute! db-ctx 
               ["SELECT * FROM users WHERE tenant_id = ?" tenant-id])
```

### Provisioning Best Practices

1. **Provision Asynchronously**: Schema creation can take seconds for large structures
   ```clojure
   (jobs/enqueue-job! job-queue :provision-tenant {:tenant-id tenant-id})
   ```

2. **Validate Before Provisioning**: Ensure slug is unique and valid
   ```clojure
   (when (tenant-ports/find-tenant-by-slug tenant-service slug)
     (throw (ex-info "Slug already exists" {:type :conflict})))
   ```

3. **Handle Provisioning Failures**: Schema creation can fail
   ```clojure
   (try
     (provisioning/provision-tenant! db-ctx tenant)
     (catch Exception e
       (tenant-ports/update-tenant-status! tenant-service (:id tenant) :failed)
       (throw e)))
   ```

4. **Test with Multiple Tenants**: Verify isolation
   ```clojure
   (let [tenant-a (create-and-provision! "tenant-a")
         tenant-b (create-and-provision! "tenant-b")]
     ;; Insert user in tenant-a
     (with-tenant-schema db-ctx "tenant_tenant_a"
       (fn [ctx] (insert-user! ctx "Alice")))
     ;; Verify tenant-b doesn't see it
     (with-tenant-schema db-ctx "tenant_tenant_b"
       (fn [ctx] (is (empty? (list-users ctx))))))
   ```

## Tenant Resolution

### Subdomain-Based Resolution

```clojure
(defn subdomain-resolver [tenant-service]
  (fn [request]
    (let [host (get-in request [:headers "host"])
          subdomain (first (clojure.string/split host #"\."))]
      (when-not (contains? #{"www" "api" "localhost"} subdomain)
        (tenant-ports/find-tenant-by-slug tenant-service subdomain)))))

;; Usage
(def app
  (-> routes
      (tenant-mw/wrap-tenant-resolution
        tenant-service
        {:resolver (subdomain-resolver tenant-service)})))

;; Request: https://acme-corp.myapp.com/dashboard
;; → Resolves to tenant with slug "acme-corp"
```

### Header-Based Resolution

```clojure
(defn header-resolver [tenant-service]
  (fn [request]
    (when-let [tenant-id (get-in request [:headers "x-tenant-id"])]
      (tenant-ports/find-tenant-by-id tenant-service (parse-uuid tenant-id)))))

;; Request with header: X-Tenant-Id: 123e4567-e89b-12d3-a456-426614174000
;; → Resolves to tenant with that ID
```

### Token-Based Resolution

```clojure
(defn token-resolver [tenant-service]
  (fn [request]
    (when-let [claims (get-in request [:auth :claims])]
      (when-let [tenant-id (:tenant-id claims)]
        (tenant-ports/find-tenant-by-id tenant-service tenant-id)))))

;; JWT payload: {"sub": "user-123", "tenant_id": "uuid-456"}
;; → Resolves to tenant from JWT claim
```

## Cross-Module Integration

### Jobs Module Integration

Background jobs automatically execute in tenant schema:

```clojure
(require '[wagoe.jobs.shell.tenant-context :as tenant-jobs])

;; Enqueue tenant-scoped job
(tenant-jobs/enqueue-tenant-job! 
  job-queue 
  tenant-id 
  :send-welcome-email
  {:user-id user-id})

;; Worker processes job in tenant schema
(defn send-welcome-email-handler [db-ctx job-args]
  ;; db-ctx is already in tenant schema!
  (let [user (jdbc/execute-one! db-ctx 
                                ["SELECT * FROM users WHERE id = ?" 
                                 (:user-id job-args)])]
    (send-email! (:email user) "Welcome!" ...)))
```

See [Jobs Module README](../jobs/README.md#multi-tenancy-support) for details.

### Cache Module Integration

Cache keys automatically scoped per tenant:

```clojure
(require '[wagoe.cache.shell.tenant-cache :as tenant-cache])

;; Create tenant-scoped cache
(def tenant-cache (tenant-cache/create-tenant-cache base-cache tenant-id))

;; Set value (key automatically prefixed)
(cache-ports/set-value! tenant-cache :user-123 {:name "Alice"})
;; → Stored as: "tenant:<tenant-id>:user-123"

;; Get value (automatic tenant isolation)
(cache-ports/get-value tenant-cache :user-123)
;; => {:name "Alice"} (only from this tenant's cache)
```

See [Cache Module README](../cache/README.md#tenant-scoping) for details.

## Complete Tenant Lifecycle Example

```clojure
(require '[wagoe.tenant.ports :as tenant-ports]
         '[wagoe.tenant.shell.provisioning :as provisioning]
         '[wagoe.jobs.shell.tenant-context :as tenant-jobs]
         '[wagoe.cache.shell.tenant-cache :as tenant-cache])

;; 1. Create tenant
(def tenant-input
  {:slug "acme-corp"
   :name "ACME Corporation"
   :settings {:features {:api true}
              :limits {:users 100}}})

(def tenant (tenant-ports/create-new-tenant tenant-service tenant-input))

;; 2. Provision schema (PostgreSQL only)
(provisioning/provision-tenant! db-ctx tenant)

;; 3. Add first user in tenant schema
(provisioning/with-tenant-schema db-ctx (str "tenant_" (:slug tenant))
  (fn [tenant-ctx]
    (jdbc/execute! tenant-ctx
                   ["INSERT INTO users (name, email, role) VALUES (?, ?, ?)"
                    "Admin User" "admin@acme-corp.com" "admin"])))

;; 4. Enqueue welcome job (runs in tenant schema)
(tenant-jobs/enqueue-tenant-job! 
  job-queue 
  (:id tenant)
  :send-welcome-email
  {:email "admin@acme-corp.com"})

;; 5. Cache tenant settings
(def tenant-cache (tenant-cache/create-tenant-cache base-cache (:id tenant)))
(cache-ports/set-value! tenant-cache :settings (:settings tenant) 3600)

;; 6. Later: Retrieve tenant and verify isolation
(def retrieved-tenant (tenant-ports/find-tenant-by-slug tenant-service "acme-corp"))
(def cached-settings (cache-ports/get-value tenant-cache :settings))

;; All operations automatically scoped to tenant - no data leakage!
```

## Tenant Management REST API

When the tenant module is wired, it exposes a REST API for tenant administration
under `/api/v1/tenants` (JSON responses; standard 400/404/500 error handling):

| Method | Path | Purpose |
|--------|------|---------|
| `GET`    | `/api/v1/tenants?limit=&offset=&status=` | List tenants (filtered, paginated) |
| `POST`   | `/api/v1/tenants` | Create tenant (`{"name","slug","status"}`) |
| `GET`    | `/api/v1/tenants/:id` | Get tenant by id |
| `PUT`    | `/api/v1/tenants/:id` | Update tenant |
| `DELETE` | `/api/v1/tenants/:id` | Soft-delete tenant |
| `POST`   | `/api/v1/tenants/:id/suspend` | Suspend tenant (blocks access) |
| `POST`   | `/api/v1/tenants/:id/activate` | Re-activate tenant |
| `POST`   | `/api/v1/tenants/:id/provision` | Provision schema (PostgreSQL only) |

## Configuration

### Integrant Configuration

```clojure
;; config/dev.edn
{:wagoe/db-context {...}
 
 :wagoe/tenant-repository
 {:db-context #ig/ref :wagoe/db-context}
 
 :wagoe/tenant-service
 {:repository #ig/ref :wagoe/tenant-repository
  :logger #ig/ref :wagoe/logger
  :error-reporter #ig/ref :wagoe/error-reporter}
 
 :wagoe/membership-service
 {:repository #ig/ref :wagoe/membership-repository
  :logger #ig/ref :wagoe/logger}

 ;; Builds the tenant HTTP middleware seq (tenant resolution + membership).
 ;; The app injects it into platform's http-handler via :extra-middleware (BOU-200),
 ;; so platform never requires the tenant lib directly.
 :wagoe/tenant-http-middleware
 {:tenant-service #ig/ref :wagoe/tenant-service
  :membership-service #ig/ref :wagoe/membership-service
  :db-context #ig/ref :wagoe/db-context}}
```

Under the Wagoe app wiring (`wagoe.config`), these keys are added
automatically and `:wagoe/tenant-http-middleware` is referenced by
`:wagoe/http-handler`'s `:extra-middleware` — no manual middleware mounting
is required.

### Database Migrations

```sql
-- Migration: 011_create_tenants_table.sql
CREATE TABLE IF NOT EXISTS tenants (
  id UUID PRIMARY KEY,
  slug TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active',
  settings JSONB,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tenants_slug ON tenants(slug);
CREATE INDEX idx_tenants_status ON tenants(status);
```

## Testing

### Run Tests

```bash
# All tenant module tests
clojure -M:test --focus wagoe.tenant.*

# Specific test suites
clojure -M:test --focus wagoe.tenant.core.tenant-test         # Unit tests
clojure -M:test --focus wagoe.tenant.shell.service-test       # Service tests
clojure -M:test --focus wagoe.tenant.shell.provisioning-test  # Provisioning tests
```

### Test Coverage

- **Core**: 100+ assertions, 0 failures
- **Provisioning**: 250+ assertions, 0 failures (PostgreSQL schema operations)
- **Jobs Integration**: 10 tests, 80 assertions, 0 failures
- **Cache Integration**: 20 tests, 182 assertions, 0 failures

## Performance Characteristics

- **Tenant Lookup (by ID)**: < 5ms (single database query, cacheable)
- **Tenant Lookup (by Slug)**: < 5ms (indexed column)
- **Schema Provisioning**: 1-5 seconds (depends on schema complexity)
- **Schema Switching**: < 1ms (PostgreSQL `SET search_path`)
- **Total Request Overhead**: < 10ms (resolution + context propagation)

## Production Checklist

✅ **Database**
- Use PostgreSQL 12+ for schema-per-tenant
- Create indexes on `tenants.slug` and `tenants.status`
- Set connection pool size >= concurrent requests

✅ **Provisioning**
- Provision schemas asynchronously (background jobs)
- Monitor provisioning failures
- Set up alerts for failed provisions

✅ **Caching**
- Use Redis for distributed tenant cache
- Set appropriate TTLs for tenant lookups (e.g., 5 minutes)
- Monitor cache hit rates per tenant

✅ **Monitoring**
- Track tenant-specific metrics (requests, errors, latency)
- Set up per-tenant error reporting
- Monitor schema sizes and query performance

## Migration Guide

### From Manual Multi-Tenancy

**Before (Manual Tenant Filtering)**:

```clojure
(defn get-users [tenant-id]
  (jdbc/execute! db-ctx 
                 ["SELECT * FROM users WHERE tenant_id = ?" tenant-id]))

(defn create-user! [tenant-id user-data]
  (jdbc/execute! db-ctx
                 ["INSERT INTO users (tenant_id, name, email) VALUES (?, ?, ?)"
                  tenant-id (:name user-data) (:email user-data)]))
```

**After (Schema-Per-Tenant)**:

```clojure
(defn get-users [tenant-slug]
  (provisioning/with-tenant-schema db-ctx (str "tenant_" tenant-slug)
    (fn [ctx]
      (jdbc/execute! ctx ["SELECT * FROM users"]))))  ; No tenant_id needed!

(defn create-user! [tenant-slug user-data]
  (provisioning/with-tenant-schema db-ctx (str "tenant_" tenant-slug)
    (fn [ctx]
      (jdbc/execute! ctx
                     ["INSERT INTO users (name, email) VALUES (?, ?)"
                      (:name user-data) (:email user-data)]))))
```

### Migration Steps

1. **Install Module**: Add `wagoe-tenant` dependency
2. **Run Migration**: Create `tenants` table via migration 011
3. **Create Tenants**: Insert existing tenants into `tenants` table
4. **Provision Schemas**: Run provisioning for each existing tenant
5. **Migrate Data**: Copy data from shared tables to tenant schemas
6. **Update Code**: Replace manual filtering with `with-tenant-schema`
7. **Add Middleware**: Enable tenant resolution in HTTP layer
8. **Test**: Verify isolation and performance

## API Reference

### Core Operations

**`wagoe.tenant.ports`**

- `(create-new-tenant service tenant-input)` - Create new tenant
- `(find-tenant-by-id service tenant-id)` - Lookup by ID
- `(find-tenant-by-slug service slug)` - Lookup by slug
- `(update-tenant service tenant-id updates)` - Update tenant
- `(delete-tenant service tenant-id)` - Soft delete tenant
- `(list-tenants service filters)` - List tenants with filtering

### Provisioning Operations

**`wagoe.tenant.shell.provisioning`**

- `(provision-tenant! db-ctx tenant)` - Create tenant schema
- `(deprovision-tenant! db-ctx tenant)` - Drop tenant schema
- `(tenant-provisioned? db-ctx tenant)` - Check provision status
- `(with-tenant-schema db-ctx schema f)` - Execute in tenant schema
- `(list-tenant-schemas db-ctx)` - List all tenant schemas

### Middleware

**`wagoe.tenant.shell.tenant-middleware`** (tenant lib):

- `(wrap-tenant-resolution handler service opts)` - Resolve tenant from request (subdomain/header/token); pass `:require-tenant? true` to enforce tenant presence
- `(wrap-tenant-schema handler db-ctx)` - Automatic schema switching
- `(wrap-multi-tenant handler service db-ctx opts)` - Combines resolution + schema switching

**`wagoe.tenant.shell.membership-middleware`** (tenant lib):

- `(wrap-tenant-membership membership-service handler)` - Verify user membership in tenant

## Architecture

The tenant module follows **Functional Core / Imperative Shell**:

- **Core** (`core/tenant.clj`): Pure tenant logic (validation, calculations)
- **Shell** (`shell/service.clj`): Service layer with I/O
- **Shell** (`shell/provisioning.clj`): Schema management
- **Shell** (`shell/tenant_middleware.clj`, `shell/membership_middleware.clj`): HTTP tenant resolution + membership
- **Ports** (`ports.clj`): Protocol definitions

## Database Support

### PostgreSQL Only (See ADR-020)

The tenant module requires **PostgreSQL 12+** for the schema-per-tenant isolation model.

**Why PostgreSQL?**
- Uses PostgreSQL schemas (namespaces) for logical isolation
- Fast schema switching via `SET search_path` (< 1ms)
- Strong database-enforced tenant boundaries
- Clean queries without `tenant_id` filtering
- Proven at scale (1000s of tenants)

**Other Databases**: Not supported for provisioning. Projects on MySQL or SQLite must implement their own row-level `WHERE tenant_id = ?` filtering outside this library.

**H2**: Used as a fast in-memory test database only. Provisioning functions gracefully return `false` / empty results / throw `:not-supported` on non-PostgreSQL contexts.

The decision to keep PostgreSQL-only is documented in [ADR-020](../../dev-docs/adr/ADR-020-tenant-database-scope.adoc). The `ITenantSchemaProvider` protocol provides an extension point if a different database backend is needed in the future.

---

## License

Part of Wagoe Framework - See main LICENSE file.

---

## Next Steps

- **Production Deployment**: See [ADR-004](../../docs/adr/ADR-004-multi-tenancy-architecture.md) for architecture details
- **Background Jobs**: See [Jobs Module](../jobs/README.md) for tenant-scoped background jobs
- **Caching**: See [Cache Module](../cache/README.md) for tenant-scoped caching
- **Database Support**: See [Future Enhancements](#database-support--limitations) for roadmap

---

**Questions or Issues?**
- GitHub Issues: Report bugs or request features
- GitHub Discussions: Ask questions or share use cases
- ADR-004: Full architectural decision record
