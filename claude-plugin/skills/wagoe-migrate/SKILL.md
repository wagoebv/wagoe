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

## Keep every migration in one directory

`:migration-dir` is a **name**, not a path, and it resolves to exactly one
place. Migratus tries, in order: the system classloader, the context
classloader, `resources/migrations` (its `default-migration-parent` is
`"resources/"`), then `migrations/`. Only the last is the project directory —
so a `resources/migrations` directory, or a jar carrying `migrations/`, takes
the name instead.

When that happens, everything in `migrations/` is skipped: not applied, not
listed as pending, not counted. **This used to be silent** — `bb migrate up`
exited 0, `status` showed no pending migrations, and the table simply did not
exist (BOU-274).

It is not silent any more. Every migration command now refuses:

```
$ bb migrate up
❌ Migration failed: These migrations are never read:
  migrations/20260101000000-add-widgets.down.sql
  migrations/20260101000000-add-widgets.up.sql
':migration-dir' is a name, not a path, and it resolved to
'resources/migrations' (which is empty) — so everything above is skipped: not
applied, and not reported as pending.
Keep migrations in one place. 'migrations/' is the conventional home; if you
keep 'resources/migrations', it must hold all of them.
```

Exit status is 1, and `status` refuses the same way rather than reporting a
false clean.

What this means in practice:

- **You do not have to reason about which directory wins.** If the layout is
  ambiguous, you are told, with the files named. If nothing complains, the
  migrations you have are the migrations that run.
- **`bb migrate create` reports where it actually wrote**, which is not always
  `migrations/`. Read the path it prints; both the summary line and the
  "Edit the generated SQL files in …" line now name the same real directory.
- **Fix a reported conflict by moving files, not by guessing.** `migrations/`
  is the conventional home. A `resources/migrations`-only layout also works —
  this framework repository uses one — but it has to hold all of them.

The guard covers an empty capturing directory, migrations nested in
subdirectories, jars on the classpath, and EDN migrations as well as SQL. It
decides none of that itself: it asks migratus where the name resolves
(`find-migration-dir`) and what counts as a migration (`parse-name`).

After anything writes a migration — you, the scaffolder, or `bb migrate
create` — run `bb migrate status` and confirm it appears.

## Adding a field takes three changes, not one

The most common migration bug is doing one third of the job. A new field needs:

1. **Malli schema** — `src/<project>/<module>/schema.clj`
2. **Database column** — the migration
3. **Persistence transform** — `shell/persistence.clj`, both directions

Miss the schema and validation rejects the field. Miss the persistence
transform and it reads back `nil` with no error anywhere.

**`bb scaffold field` does not do all three.** Measured — it writes the
migration pair and nothing else:

```
$ bb scaffold field --module-name order --entity Order --name status --type string
  :create: migrations/…-add-status-to-orders.up.sql
  :create: migrations/…-add-status-to-orders.down.sql
  :update: src/wagoe/order/schema.clj        ← reported, but not written

$ git status --porcelain
?? migrations/…-add-status-to-orders.down.sql
?? migrations/…-add-status-to-orders.up.sql
```

The schema file is untouched despite the `:update:` line, and `persistence.clj`
is never mentioned. So after running it you still have to edit both by hand,
and the command's own output will tell you otherwise.

Check with `git status` after running it, then add the field to `schema.clj`
and to both directions of the persistence transform yourself.

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
3. `bb migrate status` — confirm it appears as **Pending**. If it does not,
   there are two causes, and both are silent:
   - the filename does not match `<id>-<name>.up.sql` / `.down.sql`
   - the file is in the ignored directory. Check both `migrations/` and
     `resources/migrations/`; if each holds `.sql` files, only
     `resources/migrations/` is read and everything in the other is invisible.
4. `bb migrate up`, then `status` again to confirm it applied.
5. If it is a new field, make the other two changes yourself — `schema.clj` and
   both directions of the persistence transform. `bb scaffold field` writes
   only the migration, whatever its output says. Then run the module's tests.
6. Exercise the rollback path once.

## What this does not cover

Nothing here edits production. `bb migrate` acts on the database in the active
config — check with `bb doctor` which one that is before running `up` anywhere
that matters.
