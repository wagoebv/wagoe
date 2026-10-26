# Tenant Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Multi-tenancy with schema-per-tenant isolation on PostgreSQL. Provides:
- **Tenant CRUD** — create, update, suspend, activate, soft-delete
- **Schema provisioning** — per-tenant PostgreSQL schema creation
- **Membership management** — invite users, accept invitations, manage roles and status (ADR-016)
- **Email invite flow** — token-based external invites with expiry and revocation
- **Tenant resolution** — subdomain, JWT, or header-based tenant detection (`wagoe.tenant.shell.tenant-middleware`)

---

## Key Namespaces

### Core (pure functions)

| Namespace | Purpose |
|-----------|---------|
| `wagoe.tenant.core.tenant` | Slug validation, schema name generation, `prepare-tenant`, lifecycle decisions |
| `wagoe.tenant.core.membership` | Membership lifecycle: prepare-invitation*, prepare-active-membership*, accept, suspend, revoke, role updates, `active-member?`, `has-role?` |
| `wagoe.tenant.core.invite` | Invite lifecycle: create, check expiry, accept, revoke; token hashing |

### Ports (protocols)

| Namespace | Protocols |
|-----------|-----------|
| `wagoe.tenant.ports` | `ITenantRepository`, `ITenantService`, `ITenantMembershipRepository`, `ITenantMembershipDeletion`, `ITenantMembershipService`, `ITenantInviteRepository`, `ITenantInviteService`, `ITenantInviteAcceptanceService`, `ITenantSchemaProvider`, `ITenantAuditRepository` |

### Shell (side-effecting)

| Namespace | Purpose |
|-----------|---------|
| `wagoe.tenant.shell.service` | `TenantService` — tenant CRUD with observability interceptors |
| `wagoe.tenant.shell.persistence` | `TenantRepository` — DB CRUD with case/type conversion |
| `wagoe.tenant.shell.provisioning` | PostgreSQL schema provisioning/deprovisioning |
| `wagoe.tenant.shell.http` | Tenant REST API handlers (CRUD + provision/suspend/activate) |
| `wagoe.tenant.shell.membership-service` | `MembershipService` — membership management |
| `wagoe.tenant.shell.membership-persistence` | `MembershipRepository` — DB CRUD for memberships |
| `wagoe.tenant.shell.membership-http` | Membership REST API handlers |
| `wagoe.tenant.shell.membership-middleware` | `wrap-tenant-membership` Ring middleware |
| `wagoe.tenant.shell.tenant-middleware` | `wrap-tenant-resolution`, `wrap-tenant-schema`, `wrap-multi-tenant` |
| `wagoe.tenant.shell.invite-service` | `InviteService` + `InviteAcceptanceService` — email invite flows |
| `wagoe.tenant.shell.invite-persistence` | `InviteRepository` — DB CRUD for invites |
| `wagoe.tenant.shell.module-wiring` | Integrant `init-key` / `halt-key!` definitions |

### Schema

| Namespace | Contents |
|-----------|---------|
| `wagoe.tenant.schema` | `Tenant`, `TenantMembership`, `TenantInvite`, `TenantSettings`, input schemas, camelCase request/response transformers |

---

## Slug and Schema Naming

```clojure
;; Slug rules: lowercase alphanumeric + hyphens, 2-56 chars, so that
;; "tenant_" + slug fits PostgreSQL's 63-byte identifier limit
;; Regex: ^[a-z0-9][a-z0-9-]{0,54}[a-z0-9]$
(tenant/valid-slug? "acme-corp")     ;=> true
(tenant/valid-slug? "ACME")          ;=> false (uppercase)
(tenant/valid-slug? "acme_corp")     ;=> false (underscore)

;; Slug → PostgreSQL schema name (hyphens become underscores)
(tenant/slug->schema-name "acme-corp")  ;=> "tenant_acme_corp"
```

---

## Tenant Lifecycle States

```
:active       → Created (schema provisioning is separate: POST /tenants/:id/provision)
:suspended    → Access disabled (data preserved)
:deleted      → Soft delete (schema NOT auto-dropped)
```

---

## Membership Management (ADR-016)

### Entity shape

```clojure
{:id          uuid
 :tenant-id   uuid
 :user-id     uuid
 :role        :admin | :member | :viewer | :contractor
 :status      :invited | :active | :suspended | :revoked
 :invited-at  inst
 :accepted-at inst   ; set when :invited → :active
 :created-at  inst
 :updated-at  inst}
```

### Membership lifecycle

```
:invited  ──accept──→  :active
:active   ──suspend──→ :suspended
:active   ──revoke──→  :revoked
:invited  ──revoke──→  :revoked
:suspended ──revoke──→ :revoked
```

### Core helpers

```clojure
(require '[wagoe.tenant.core.membership :as m])

;; Prepare a new invitation (status :invited)
(m/prepare-invitation* membership-id user-id tenant-id :admin now)

;; Prepare a direct active membership (bootstrap / no-invite flow)
(m/prepare-active-membership* membership-id user-id tenant-id :admin now)

;; Transitions
(let [accepted-at #inst "2026-03-10T09:00:00Z"]
  (m/accept-invitation membership accepted-at))  ; :invited  → :active
(m/suspend-membership membership (Instant/now))  ; → :suspended
(m/revoke-membership  membership (Instant/now))  ; → :revoked
(m/update-role        membership :member (Instant/now))

;; Predicate helpers (used by interceptors)
(m/active-member? membership)              ;=> true/false
(m/has-role? membership #{:admin})         ;=> true/false
```

### Bootstrap flow — first admin

When a tenant is created there are no memberships yet. The membership
service's `bootstrap-open?` says whether the first member may still be added:

```clojure
(when (ports/bootstrap-open? membership-service tenant-id)
  (ports/bootstrap-first-member membership-service tenant-id user-id :admin))
```

### HTTP routes (membership)

```
POST   /tenants/:tenant-id/memberships          ; Invite user (tenant admin)
GET    /tenants/:tenant-id/memberships          ; List members (tenant member)
GET    /tenants/:tenant-id/memberships/:id      ; Get membership (tenant member)
PUT    /tenants/:tenant-id/memberships/:id      ; Update role/status (tenant admin)
DELETE /tenants/:tenant-id/memberships/:id      ; Revoke membership (tenant admin)
POST   /memberships/:id/accept                  ; Accept invitation (the invitee only)
```

A global admin passes every tenant check. The `/tenants` routes themselves
(create, update, suspend, provision) take the global admin role (BOU-568).

---

## Email Invite Flow

### Entity shape

```clojure
{:id                  uuid
 :tenant-id           uuid
 :email               string           ; normalized (lowercase)
 :role                keyword
 :status              :pending | :accepted | :revoked
 :token-hash          string           ; SHA-256 of the raw token (never stored plain)
 :expires-at          inst
 :accepted-at         inst
 :revoked-at          inst
 :accepted-by-user-id uuid
 :metadata            map
 :created-at          inst
 :updated-at          inst}
```

### Token security

The raw token is **never** stored in the database:

```clojure
;; Inside wagoe.tenant.shell.invite-service (private helpers):
;; 1. Raw token: 32 SecureRandom bytes, URL-safe base64 without padding
;; 2. Stored: its SHA-256 hash only; the raw token goes to the user by email
```

### Acceptance flow (two-phase, transactional)

```clojure
;; Phase 1: load and validate (does not mutate)
(invite-ports/load-external-invite-for-acceptance invite-service {:token token})
;=> the pending invite; throws :not-found, or :validation-error when expired

;; Phase 2: atomic accept — creates membership in same transaction
(invite-ports/accept-external-invite! invite-service
  {:token token
   :accepted-by-user-id user-id
   :hooks {:after-accept-tx (fn [{:keys [invite membership tx-context]}] ...)}})
;=> {:invite ... :membership ... :effects {:after-accept-tx ...}}
```

---

## Schema Provisioning

```clojure
;; Provision: creates the PostgreSQL schema and runs the tenant-scoped
;; migration set into it (see "Tenant migrations" below).
(provisioning/provision-tenant! db-ctx tenant)
;=> {:success? true :schema-name "tenant_acme_corp" :table-count 5}

;; Execute queries in tenant schema context
(provisioning/with-tenant-schema db-ctx "tenant_acme_corp"
  (fn [tx]
    ;; SET LOCAL search_path TO tenant_acme_corp, public (ends with the transaction)
    (jdbc/execute! tx ["SELECT * FROM users"])))
```

---

## The library's own tables

`tenants`, `tenant_memberships` and `tenant_member_invites` ship as a library
migration (`wagoe/tenant/migrations/`, BOU-551). Boot creates them from the same
definition, `wagoe.tenant.shell.tenant-tables`, so `bb migrate up` and a first
boot give the same tables. Change them there, never in a second copy.

A later migration keys `tenant_memberships.user_id` and
`tenant_member_invites.accepted_by_user_id` to `auth_users(id)` (BOU-611), so a
user still in a tenant cannot be hard-deleted; an accepted invite's key is
`ON DELETE SET NULL`. So a membership row exists only while its user belongs:
revoking deletes it, and a tenant marked deleted loses its memberships. The
migration first deletes such rows older versions kept, clears an accepted
invite's user that no longer exists, and refuses, naming them, memberships that
point at no user. Boot (BOU-612), after the user tables, adds only the keys it
can without changing a row; one that needs cleanup is logged for `migrate up`.

## Tenant migrations

Tenants use a **schema-per-tenant** layout, so schema changes to tenant-scoped
tables must reach every `tenant_<slug>` schema — not just `public`. Two
migration sets:

| Set | Directory | Runs where | Ledger |
|-----|-----------|------------|--------|
| Public/shared | `migrations/` | once against `public` (the normal `bb migrate`) | `public.schema_migrations` |
| Tenant-scoped | `migrations-tenant/` | inside **every** tenant schema | per-schema `schema_migrations` |

`wagoe.tenant.shell.tenant-migrations` drives the tenant set:

```clojure
(tenant-migrations/migrate-tenant! db-ctx "tenant_acme_corp")   ; one schema
(tenant-migrations/migrate-all-tenants! db-ctx schemas)         ; fan out
```

The set is the application's `migrations-tenant/` plus each library's
`:tenant-paths` from its `wagoe/migration-paths/<lib>.edn` manifest
(`tenant-migration-dirs`), of the modules switched on in `:active` only;
workflow names its public series there, so its tenants get every migration.
The tenant middleware binds `wagoe.platform.database/*tenant-id*` for the
request, and `call-in-schema` binds `*tenant-schema*` for its transaction.

`db-ctx` is the boot `:wagoe/db-context`. Each run opens its own connection with
search_path set to the tenant schema **only** (no `,public` fallback) so
migratus keeps a per-tenant ledger. Consequences:

- Put only **tenant-scoped** DDL in `migrations-tenant/` — it may not reference
  shared tables that live in `public` (there is no `public` in the search_path).
- Migrations run once per tenant schema, so prefer idempotent DDL.

Fan-out happens automatically: `provision-tenant!` runs the set into a new
schema, and the `:wagoe/tenant-db-schema` component runs pending tenant
migrations across all existing schemas on startup (so a deploy reaches every
tenant). See `libs/tenant/src/wagoe/tenant/shell/tenant_migrations.clj`.
Library tenant migrations run for the modules the context's
`:enabled-libraries` names: what `:active` switches on, which the system config
sets on `:wagoe/db-context`, as `migrate up` does for public.

---

## Middleware

### `wrap-tenant-membership` (from `membership_middleware`)

Enriches the Ring request with `:tenant-membership` for the current user+tenant pair.
Must run **after** both `wrap-tenant-resolution` (platform) and user authentication middleware.

```clojure
(require '[wagoe.tenant.shell.membership-middleware :refer [wrap-tenant-membership]])

;; Service first, then the handler
(wrap-tenant-membership membership-service handler)
```

`:wagoe/tenant-http-middleware` builds this, after `wrap-multi-tenant`, for the
platform's `:extra-middleware`; wiring it by hand is rarely needed.

Result on request: `{:tenant {...} :user {...} :tenant-membership {:status :active :role :admin ...}}`

### Tenant resolution

`wrap-tenant-resolution`, `wrap-tenant-schema` and `wrap-multi-tenant` live in
`wagoe.tenant.shell.tenant-middleware` (BOU-200 moved them out of platform).

---

## Interceptors (from `user` library)

The four tenant-aware HTTP interceptors live in `wagoe.user.shell.http-interceptors`:

| Interceptor | Behaviour on failure |
|-------------|---------------------|
| `require-tenant-member` | 403 JSON — active membership required |
| `require-tenant-role` | 403 JSON — factory fn, e.g. `(require-tenant-role #{:admin :member})` |
| `require-tenant-admin` | 403 JSON — shorthand for `(require-tenant-role #{:admin})` |
| `require-web-tenant-admin` | 302 redirect to `/web/login` for `/web/*` routes, 403 JSON otherwise |

```clojure
;; API route — protected by tenant admin. Served at /api/v1/tenants/…
["/tenants/:tenant-id/settings"
 {:put {:handler      update-settings
        :interceptors [http-int/require-authenticated
                       http-int/require-tenant-admin]}}]

;; Web route — HTML redirect instead of JSON 403. Served at /web/tenants/…
["/tenants/:tenant-id/settings"
 {:get {:handler      settings-page
        :interceptors [http-int/require-authenticated
                       http-int/require-web-tenant-admin]}}]
```

---

## Integrant Keys

| Key | Component |
|-----|-----------|
| `:wagoe/tenant-db-schema` | Initialise tables (`tenants`, `tenant_memberships`, `tenant_member_invites`, `tenant_audit_log`) |
| `:wagoe/tenant-repository` | `TenantRepository` record |
| `:wagoe/tenant-service` | `TenantService` record |
| `:wagoe/tenant-routes` | Normalized tenant API routes |
| `:wagoe/tenant-http-middleware` | Middleware seq (multi-tenant, then membership) for the platform's `:extra-middleware` |
| `:wagoe/tenant-data-exporter` | `TenantDataExporter` — the tenant section of a data export |
| `:wagoe/tenant-audit-repository` | `tenant_audit_log` repository |
| `:wagoe/tenant-export-routes` | `/tenants/:id/export` (and `/export-jobs` with the jobs module) |
| `:wagoe/each-tenant` | `(fn [f])`: `(f tenant)` in each active tenant's schema, `db/*tenant-id*` bound (`tenant-iteration/for-each-tenant`) |
| `:wagoe/membership-repository` | `MembershipRepository` record |
| `:wagoe/membership-service` | `MembershipService` record |
| `:wagoe/membership-routes` | Normalized membership API routes |
| `:wagoe/invite-repository` | `InviteRepository` record |
| `:wagoe/invite-service` | `InviteService` + `InviteAcceptanceService` record |

---

## Database Tables

Boot and the library migration both build the tables from the Malli schemas in
`wagoe.tenant.schema`, via `wagoe.tenant.shell.tenant-tables`; change a table
there (see "The library's own tables" above). The uniqueness below is
created at boot as `CREATE UNIQUE INDEX IF NOT EXISTS`, so it reaches existing
tables too; boot stops, naming the table, when rows already duplicate a value. The admin cannot create tenants (`schema_name` is derived at
provisioning), so the shipped admin config sets `:permissions {:create false}`;
use `POST /api/v1/tenants` (BOU-534).

```sql
-- Built from wagoe.tenant.schema
tenants (id, slug UNIQUE, name, schema_name UNIQUE, status, settings JSONB,
         created_at, updated_at, deleted_at)

tenant_memberships (id, tenant_id, user_id, role, status,
                    invited_at, accepted_at, created_at, updated_at)
                   UNIQUE(tenant_id, user_id)

tenant_member_invites (id, tenant_id, email, role, status,
                       token_hash UNIQUE, expires_at,
                       accepted_at, revoked_at, accepted_by_user_id,
                       metadata JSONB, created_at, updated_at)

tenant_audit_log (id, tenant_id, action, actor_id, actor_email,
                  ip_address, user_agent, created_at)
```

---

## Gotchas

1. **PostgreSQL only** — `:wagoe/tenant-db-schema` refuses to boot on SQLite, MySQL and H2 with `:type :not-supported` (BOU-576)
2. **H2 (tests)** boots only with `:allow-h2? true` under `:wagoe/tenant`, which the test profile sets, and skips provisioning with a warning — schema isolation can't be tested in H2
3. **A slug is never reused**, not even a deleted tenant's: its schema is kept, and the schema name derives from the slug
3. **Soft deletes** — `:deleted-at` set on tenant; schema NOT auto-dropped; call `deprovision-tenant!` separately
4. **Settings are JSONB** — nested map stored as JSON, parsed via Cheshire
5. **Case conversion** — kebab↔snake at persistence boundary, kebab↔camelCase at HTTP boundary
6. **UUID and Instant** stored as strings in DB, converted at persistence layer
7. **Unique membership constraint** — `(tenant_id, user_id)` is unique; re-inviting the same user throws `:conflict`
8. **Token never in DB** — always store the SHA-256 hash; raw token is sent to the user and then discarded
9. **Invite acceptance is transactional** — use the two-phase `load-external-invite-for-acceptance` / `accept-external-invite!` pair; never mutate invite and membership in separate transactions
10. **`wrap-tenant-membership` depends on `:user` and `:tenant`** — both must be on the request before this middleware runs

---

## Cross-Module Integration

| Module | Integration point |
|--------|-------------------|
| **Jobs** | `wagoe.jobs.shell.tenant-context/enqueue-tenant-job!` — jobs execute in tenant schema |
| **Cache** | `wagoe.cache.shell.tenant-cache/create-tenant-cache` — keys prefixed with tenant ID |
| **Platform** | Takes `:wagoe/tenant-http-middleware` as `:extra-middleware`; binds `wagoe.platform.database/*tenant-id*` / `*tenant-schema*` |
| **User** | `require-tenant-member`, `require-tenant-admin`, `require-web-tenant-admin` interceptors |

---

## Testing

```bash
clojure -M:test:test/pg :tenant                                             # All tenant tests
clojure -M:test:test/pg :tenant --focus wagoe.tenant.core.tenant-test            # Slug/schema pure functions
clojure -M:test:test/pg :tenant --focus wagoe.tenant.core.membership-test        # Membership pure functions
clojure -M:test:test/pg :tenant --focus wagoe.tenant.shell.service-test          # TenantService (mocked repo)
clojure -M:test:test/pg :tenant --focus wagoe.tenant.shell.membership-service-test  # MembershipService
clojure -M:test:test/pg :tenant --focus wagoe.tenant.shell.invite-service-test   # InviteService
clojure -M:test:test/pg :tenant --focus wagoe.tenant.shell.invite-persistence-test  # Invite persistence
clojure -M:test:test/pg :tenant --focus wagoe.tenant.integration-test            # End-to-end flows
```

---

## Links

- [Tenant Implementation How-to](../../docs/modules/guides/pages/multi-tenancy.adoc)
- [Root AGENTS Guide](../../AGENTS.md)
- [User AGENTS — auth interceptors](../user/AGENTS.md)
