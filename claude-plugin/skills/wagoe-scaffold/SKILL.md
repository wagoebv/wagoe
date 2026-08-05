---
name: wagoe-scaffold
description: Generate a Wagoe module, field, endpoint or adapter with the correct FC/IS structure, then wire it in and verify the quality gates pass. Use when adding a feature, entity, domain concept, REST endpoint or port implementation to an existing Wagoe project — for example "add a products module with name and price" or "add a status field to Invoice". Never hand-write a module skeleton; the generator produces the structure the gates require.
---

# Wagoe — scaffolding

The scaffolder exists because `bb check` enforces rules that are tedious to
satisfy by hand: `core/` may not import shell, do I/O, log, throw, or hold
mutable state; every module needs `ports.clj`; every test needs exactly one
pyramid tag. Generated code satisfies all of them. Hand-written code usually
does not, and you find out at the gate.

```bash
bb scaffold generate --module-name product --entity Product \
  --field name:string:required --field price:decimal
bb scaffold field    --module-name product --entity Product --name sku --type string --unique
bb scaffold endpoint --module-name product --path /products/:id/publish --method POST \
  --handler-name publish-product
bb scaffold adapter  --module-name product --port IProductNotifier --adapter-name email
bb scaffold ai "product module with name, price, stock" --yes    # needs an AI provider
```

Field spec: `name:type[:required][:unique]`, types `string text integer decimal
boolean email uuid enum date datetime json`.

Add `--dry-run` to any of them to see the file list without writing.

## Run it from the project root

**`--output-dir` is accepted and ignored** — files land relative to the working
directory regardless (BOU-268). So `cd` into the project first. Running it from
the wrong directory scatters a module across whatever repository you happened to
be standing in, exits 0, and prints a success message listing files that are not
where it says.

## What `generate` produces

Thirteen files for one entity: `schema.clj`, `ports.clj`, `core/<entity>.clj`,
`core/ui.clj`, four under `shell/`, an up/down migration pair, and three tests.

The tests are real tests, not placeholders — they are tagged and they assert
something, because `bb check` rejects both untagged deftests and `(is true)`.

## After generating

```bash
bb scaffold integrate <module>   # prints the wiring steps — writes nothing
bb migrate up                    # the generated migration
bb check --ci                    # the gates
clojure -M:test                  # the generated tests
```

**`integrate` is advisory. It does not modify a single file.** It reports what
it found and prints the steps for you to carry out:

```
✓ On the classpath — src/ and test/ are already on the project paths;
  the module's tests run with clojure -M:test (no deps.edn/tests.edn changes).

Register the module's Integrant components:
  1. Add config to resources/conf/dev/config.edn (and test)
  2. This module has no shell/module_wiring.clj yet — add one …
```

So `deps.edn` and `tests.edn` need nothing — the generated paths already cover
them. What *is* outstanding is the Integrant registration, and **you have to do
it**. Running `integrate` and moving on leaves the module on disk, compiling,
passing the gates, and never started by the application.

Confirm with `git status` that you know what changed, rather than trusting the
command name.

`--ci` on `bb check` matters: without it the command reports failures and still
exits 0, so a script or an agent reads a broken project as fine.

A freshly scaffolded module passes all nine portable checks. If `bb check`
fails right after scaffolding, that is a framework bug worth reporting, not
something to work around — it was one until recently (BOU-267).

## Creating a project, not a module

`bb scaffold new` no longer exists. New projects come from the CLI:

```bash
wagoe new my-app
```

## Steps

1. Confirm you are in the project root.
2. Ask only what you cannot infer: module name, entity name, fields and types.
   Default the rest.
3. Preview with `--dry-run` when the shape is unobvious.
4. Generate, then `integrate`, then `migrate up`.
5. Run `bb check --ci` and the tests; report both.
6. Point at the generated files and say what to edit — business logic goes in
   `core/`, everything with side effects in `shell/`.

## What this does not cover

The generator writes structure, not behaviour. The core functions it produces
are stubs with real signatures; the HTTP handlers return canned responses. It
gets you a module that compiles, migrates, tests and passes the gates — the
domain logic is still yours to write.
