# Tenant Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Multi-tenancy with schema-per-tenant isolation on PostgreSQL. Provides:
- **Tenant CRUD** — create, update, suspend, activate, soft-delete
- **Schema provisioning** — per-tenant PostgreSQL schema creation
- **Membership management** — invite users, accept invitations, manage roles and status (ADR-016)
- **Email invite flow** — token-based external invites with expiry and revocation
- **Tenant resolution** — subdomain, JWT, or header-based tenant detection (via `platform` library)

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
| `wagoe.tenant.ports` | `ITenantRepository`, `ITenantService`, `ITenantMembershipRepository`, `ITenantMembershipService`, `ITenantInviteRepository`, `ITenantInviteService`, `ITenantInviteAcceptanceService`, `ITenantSchemaProvider` |

### Shell (side-effecting)

| Namespace | Purpose |
|-----------|---------|
| `wagoe.tenant.shell.service` | `TenantService` — tenant CRUD with observability interceptors |
| `wagoe.tenant.shell.persistence` | `TenantRepository` — DB CRUD with case/type conversion |
| `wagoe.tenant.shell.provisioning` | PostgreSQL schema provisioning/deprovisioning |
| `wagoe.tenant.shell.http` | Tenant REST API handlers (CRUD + provision/suspend/activate) |
| `wagoe.tenant.shell.membership_service` | `MembershipService` — membership management |
| `wagoe.tenant.shell.membership_persistence` | `MembershipRepository` — DB CRUD for memberships |
| `wagoe.tenant.shell.membership_http` | Membership REST API handlers |
| `wagoe.tenant.shell.membership_middleware` | `wrap-tenant-membership` Ring middleware |
| `wagoe.tenant.shell.invite_service` | `InviteService` + `InviteAcceptanceService` — email invite flows |
| `wagoe.tenant.shell.invite_persistence` | `InviteRepository` — DB CRUD for invites |
| `wagoe.tenant.shell.module_wiring` | Integrant `init-key` / `halt-key!` definitions |

### Schema

| Namespace | Contents |
|-----------|---------|
| `wagoe.tenant.schema` | `Tenant`, `TenantMembership`, `TenantInvite`, `TenantSettings`, input schemas, camelCase request/response transformers |

---

## Slug and Schema Naming

```clojure
;; Slug rules: lowercase alphanumeric + hyphens, 2-100 chars
;; Regex: ^[a-z0-9][a-z0-9-]{0,98}[a-z0-9]$
(tenant/valid-slug? "acme-corp")     ;=> true
(tenant/valid-slug? "ACME")          ;=> false (uppercase)
(tenant/valid-slug? "acme_corp")     ;=> false (underscore)

;; Slug → PostgreSQL schema name (hyphens become underscores)
(tenant/slug->schema-name "acme-corp")  ;=> "tenant_acme_corp"
```

---

## Tenant Lifecycle States

```
:active       → Created, not yet provisioned
:provisioned  → Schema created and ready
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

When a tenant is created there are no memberships yet.
Use `bootstrap-open?` before calling the membership service:

```clojure
(when (m/bootstrap-open? tenant-id membership-service)
  (membership-ports/create-active-membership!
    membership-service
    {:tenant-id tenant-id :user-id user-id :role :admin}))
```

### HTTP routes (membership)

```
POST   /tenants/:tenant-id/memberships          ; Invite user (admin only)
GET    /tenants/:tenant-id/memberships          ; List members (admin only)
GET    /tenants/:tenant-id/memberships/:id      ; Get membership (admin only)
PUT    /tenants/:tenant-id/memberships/:id      ; Update role/status (admin only)
DELETE /tenants/:tenant-id/memberships/:id      ; Revoke membership (admin only)
POST   /memberships/:id/accept                  ; Accept invitation (authenticated user)
```

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
;; 1. Generate raw token (32 random bytes, URL-safe base64)
(invite-core/generate-token)   ;=> "aB3cD..."

;; 2. Hash before storing
(invite-core/hash-token raw-token)   ;=> "sha256hex..."

;; 3. Store only the hash; send raw token to user via email
```

### Acceptance flow (two-phase, transactional)

```clojure
;; Phase 1: load and validate (does not mutate)
(invite-ports/load-external-invite-for-acceptance invite-service token)
;=> {:invite ... :tenant ...}  or throws :validation-error if expired/invalid

;; Phase 2: atomic accept — creates membership in same transaction
(invite-ports/accept-external-invite! invite-service token user-id
  {:after-accept-tx (fn [tx invite membership] ...)})
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
    ;; SET search_path TO tenant_acme_corp, public
    (jdbc/execute! tx ["SELECT * FROM users"])))
```

---

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
(tenant-migrations/migrate-tenant! db-cfg "tenant_acme_corp")   ; one schema
(tenant-migrations/migrate-all-tenants! db-cfg schemas)         ; fan out
```

Each tenant run pins `currentSchema` to the tenant schema **only** (no `,public`
fallback) so migratus keeps a per-tenant ledger. Consequences:

- Put only **tenant-scoped** DDL in `migrations-tenant/` — it may not reference
  shared tables that live in `public` (there is no `public` in the search_path).
- Migrations run once per tenant schema, so prefer idempotent DDL.

Fan-out happens automatically: `provision-tenant!` runs the set into a new
schema, and the `:wagoe/tenant-db-schema` component runs pending tenant
migrations across all existing schemas on startup (so a deploy reaches every
tenant). See `libs/tenant/src/wagoe/tenant/shell/tenant_migrations.clj`.

---

## Middleware

### `wrap-tenant-membership` (from `membership_middleware`)

Enriches the Ring request with `:tenant-membership` for the current user+tenant pair.
Must run **after** both `wrap-tenant-resolution` (platform) and user authentication middleware.

```clojure
(require '[wagoe.tenant.shell.membership_middleware :refer [wrap-tenant-membership]])

;; In your Ring handler stack
(-> handler
    (wrap-tenant-membership membership-service)
    (wrap-user-authentication user-service)
    (wrap-tenant-resolution tenant-service {:require-tenant? true}))
```

Result on request: `{:tenant {...} :user {...} :tenant-membership {:status :active :role :admin ...}}`

### Tenant resolution (platform library)

See `libs/platform/AGENTS.md` for `wrap-tenant-resolution`, `wrap-tenant-schema`, and `wrap-multi-tenant`.

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
;; API route — protected by tenant admin
{:path    "/tenants/:tenant-id/settings"
 :methods {:put {:handler   update-settings
                 :interceptors [http-int/require-authenticated
                                http-int/require-tenant-admin]}}}

;; Web route — HTML redirect instead of JSON 403
{:path    "/web/tenants/:tenant-id/settings"
 :methods {:get {:handler   settings-page
                 :interceptors [http-int/require-authenticated
                                http-int/require-web-tenant-admin]}}}
```

---

## Integrant Keys

| Key | Component |
|-----|-----------|
| `:wagoe/tenant-db-schema` | Initialise tables (`tenants`, `tenant_memberships`, `tenant_member_invites`) |
| `:wagoe/tenant-repository` | `TenantRepository` record |
| `:wagoe/tenant-service` | `TenantService` record |
| `:wagoe/tenant-routes` | Normalized tenant API routes |
| `:wagoe/membership-repository` | `MembershipRepository` record |
| `:wagoe/membership-service` | `MembershipService` record |
| `:wagoe/membership-routes` | Normalized membership API routes |
| `:wagoe/invite-repository` | `InviteRepository` record |
| `:wagoe/invite-service` | `InviteService` + `InviteAcceptanceService` record |

---

## Database Tables

```sql
-- Core tenant table (original migration)
tenants (id, slug, name, schema_name, status, settings JSONB,
         created_at, updated_at, deleted_at)

-- ADR-016 migrations
tenant_memberships (id, tenant_id, user_id, role, status,
                    invited_at, accepted_at, created_at, updated_at)
                   UNIQUE(tenant_id, user_id)

tenant_member_invites (id, tenant_id, email, role, status,
                       token_hash UNIQUE, expires_at,
                       accepted_at, revoked_at, accepted_by_user_id,
                       metadata JSONB, created_at, updated_at)
```

Run migrations:
```bash
clojure -M:migrate up
```

---

## Gotchas

1. **PostgreSQL only** for production — provisioning throws on other databases
2. **H2 (tests)** skips provisioning with warning — schema isolation can't be tested in H2
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
| **Platform** | `wrap-tenant-resolution`, `wrap-tenant-schema`, `wrap-multi-tenant` — tenant detection and schema switching |
| **User** | `require-tenant-member`, `require-tenant-admin`, `require-web-tenant-admin` interceptors |

---

## Testing

```bash
clojure -M:test :tenant                                             # All tenant tests
clojure -M:test --focus wagoe.tenant.core.tenant-test            # Slug/schema pure functions
clojure -M:test --focus wagoe.tenant.core.membership-test        # Membership pure functions
clojure -M:test --focus wagoe.tenant.shell.service-test          # TenantService (mocked repo)
clojure -M:test --focus wagoe.tenant.shell.membership-service-test  # MembershipService
clojure -M:test --focus wagoe.tenant.shell.invite-service-test   # InviteService
clojure -M:test --focus wagoe.tenant.shell.persistence-test      # Contract tests (H2)
clojure -M:test --focus wagoe.tenant.integration-test            # End-to-end flows
```

---

## Links

- [Tenant Implementation How-to](../../docs/modules/guides/pages/multi-tenancy.adoc)
- [Root AGENTS Guide](../../AGENTS.md)
- [Platform AGENTS — tenant middleware](../platform/AGENTS.md)
- [User AGENTS — auth interceptors](../user/AGENTS.md)
