# Tenant-scoped migrations

Migrations in this directory run **inside every `tenant_<slug>` schema**, once
per tenant, with the connection's `search_path` pinned to the tenant schema
only (no `public` fallback). Each tenant schema keeps its own
`schema_migrations` ledger.

Rules:

- Only reference **tenant-scoped** tables (the per-tenant domain tables). A
  migration here must not touch shared tables that live in `public` (e.g.
  `users`) — there is no `public` in the search_path, so it would fail.
- Shared/public schema changes belong in the top-level `migrations/` directory,
  which runs once against `public`.
- Because each migration runs once per tenant schema, prefer idempotent DDL
  (`IF NOT EXISTS` / `IF EXISTS`).

The fan-out runner is `wagoe.tenant.shell.tenant-migrations`
(`migrate-tenant!` / `migrate-all-tenants!`). Provisioning a new tenant runs
this set into the fresh schema; deploys run it across all existing tenants.
