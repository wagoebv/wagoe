---
name: wagoe-migrate
description: Create, apply, verify and roll back database migrations in a Wagoe project. Use when adding or changing a database column or table, when a migration seems not to have run, when `bb migrate status` disagrees with the schema, or when adding a field to an entity — which needs a migration plus two other changes to stay consistent. Covers bb migrate create/up/status/rollback and the three-way schema sync.
---

# Wagoe — migrations

```bash
bb migrate status          # what is applied, what is pending
bb migrate create <name>   # new up/down pair
bb migrate up              # apply everything pending
bb migrate rollback        # undo the last one
```

## `status` is the step that matters

Creating a migration file proves nothing. Migratus only applies files it
*discovers*, and the discovery rule is the filename: `<id>-<name>.up.sql` and
`.down.sql`. A file that does not match is invisible — it will not error, it
will simply never run. A whole release shipped a sample module whose table did
not exist for exactly this reason (BOU-256).

So after creating a migration, always:

```bash
bb migrate status          # the new one must appear as Pending
```

Only once it is pending is it real. Verified sequence:

```
create    →  Pending 1
up        →  Applied 1, Pending 0
rollback  →  Applied 0, Pending 1
```

## Where the files land

`bb migrate create` writes into `migrations/` when that directory exists, and
falls back to `resources/migrations/` when it does not — both are on the
discovery path, so both work. Scaffolded modules put theirs in `migrations/`.
If you cannot find what you just created, look in the other one rather than
creating it again.

## Adding a field takes three changes, not one

The most common migration bug is doing one third of the job. A new field needs:

1. **Malli schema** — `src/<project>/<module>/schema.clj`
2. **Database column** — the migration
3. **Persistence transform** — `shell/persistence.clj`, both directions

Miss the schema and validation rejects the field. Miss the persistence
transform and it reads back `nil` with no error anywhere. `bb scaffold field`
does all three; doing it by hand means doing all three by hand.

Remember the case boundary: kebab-case in Clojure, snake_case only in SQL.
`:created-at` in the entity, `created_at` in the column.

## Migration ids are timestamps

The id is generated from the clock, so two branches that each add a migration
produce ids that interleave rather than conflict — until they share a database.
If several worktrees or branches point at the same DB, a migration applied from
one is recorded as applied for all of them, and the other branch's file with a
lower id will never run. Check `bb migrate status` after switching branches.

## Rollback is a real step, not a formality

`bb migrate rollback` runs the `.down.sql` you wrote. If that file is empty or
wrong, you find out during an incident. Roll back and re-apply once, locally,
while it is cheap:

```bash
bb migrate up && bb migrate rollback && bb migrate up
```

## Steps

1. `bb migrate status` first — know the starting point.
2. `bb migrate create <name>`, then write both `.up.sql` and `.down.sql`.
3. `bb migrate status` — confirm it appears as **Pending**. If it does not, the
   filename is wrong.
4. `bb migrate up`, then `status` again to confirm it applied.
5. If it is a new field, make the other two changes (schema, persistence) and
   run the module's tests.
6. Exercise the rollback path once.

## What this does not cover

Nothing here edits production. `bb migrate` acts on the database in the active
config — check with `bb doctor` which one that is before running `up` anywhere
that matters.
