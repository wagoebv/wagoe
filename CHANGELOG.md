# Changelog

All notable changes to the Wagoe Framework will be documented in this file.

> **Note:** entries below the "Renamed" section predate the Boundary → Wagoe
> rename and keep their original `boundary-*` names, `:boundary/*` keys, and
> `org.boundary-app` coordinates on purpose — they describe releases that
> shipped under those names.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
from `1.0.0` onwards. Until then, breaking changes are permitted between beta
releases — see the [Stability & Versioning policy](https://wagoe.org/docs/stability.html)
for what is public API, what is internal, and how deprecations are announced.

> **Note — the version scheme changed, and the number went down.** Releases were
> `1.0.1-alpha-N` up to `1.0.1-alpha-42` (2026-07-19); they are `1.0.0-beta-N`
> from `1.0.0-beta-1` (2026-07-23). The old scheme read as a patch release of a
> shipped `1.0`, which had never happened.
>
> Because Maven sorts `1.0.0` below `1.0.1`, every beta compares as *older* than
> the last alpha. Anything resolving "newest" will pick `1.0.1-alpha-42` over the
> current release — **pin exact versions**. The `1.0.1-alpha-*` line is
> discontinued and receives no fixes.
>
> There is no separate `1.0.0-beta-1` entry below. Its changes were still in
> `[Unreleased]` when `1.0.0-beta-2` shipped eight days later, so they are
> recorded under the `1.0.0-beta-2` heading.

## [Unreleased]

### Changed

- **Feature flags no longer read the environment from the functional core**
  (BOU-302). `wagoe.core.config.feature-flags` promised "Pure functions: No
  side effects" in its docstring while five of its functions had a convenience
  arity calling `(System/getenv)`. The environment map is now always a
  parameter, and the convenience moved to
  `wagoe.platform.shell.feature-flags`, which may read it.

  **Breaking, if you called a 1-arity.** `(flags/enabled? :devex-validation)`
  becomes either `(flags/enabled? :devex-validation env-map)` or
  `(shell-flags/enabled? :devex-validation)`. The two-argument forms are
  unchanged.

  `bb check:fcis` now refuses `System/getenv` in a core namespace, beside
  `getProperty` and `currentTimeMillis` — which is how the five were found.

- **One clock read per library instead of eleven** (BOU-302).
  `current-timestamp` was defined eleven times across seven libraries, and two
  of them were public — a one-line clock read in the API surface. They are
  private now, and the duplicates within a library share one helper
  (`wagoe.tenant.shell.time`, `wagoe.platform.shell.utils.time`,
  `wagoe.observability.logging.shell.time`). No behaviour changes: every call
  site was already in a shell namespace, which is the layer allowed to read a
  clock.

- **`bb check:fcis` reads code instead of lines, and its require list is now an
  allowlist** (BOU-301). The gate the architecture claim rests on could be
  switched off by pressing return: the scan matched `(\s*throw` one line at a
  time, so `(\n throw …)` — the same call to the reader — matched nothing.
  `(apply swap! reg …)` was invisible for the same reason. Both are caught now,
  and a violation is reported at the symbol rather than at the paren.

  The require check was a list of eleven forbidden namespaces, which could only
  name the I/O libraries somebody had thought of. A core namespace shelling out
  through `babashka.process`, holding process state in a `core.async` channel,
  or using any datastore client not on the list passed clean. It is inverted: a
  core namespace may require pure libraries (`clojure.string`/`set`/`walk`/
  `edn`, malli, hiccup, cheshire, `buddy.core`, `honey.sql`, rewrite-clj) and
  its own core/schema/ports, and anything else is a violation.

  On this repository the inversion produced exactly one finding, and it is a
  real exception rather than a false positive: `wagoe.core.validation.behavior`
  is a DSL whose output is `deftest` forms. `.wagoe/check-fcis.edn` gained
  `:allow-require`, per namespace and per require, with a mandatory `:why` — the
  rule check-ports already applies to its own allowlist.

- **A generated project's own code lives under its own namespace** (BOU-360).
  `wagoe new shop` put the application's wiring in `wagoe.system-config` and
  `bb scaffold generate product` put the module in `wagoe.product` — a project
  called shop defining namespaces in the framework's root. Now:

  ```
  src/shop/system_config.clj      ; ns shop.system-config
  src/shop/product/…              ; ns shop.product.*
  ```

  Config keys are unchanged: `:wagoe/product` still switches the module on,
  beside `:wagoe/http` and `:wagoe/h2`. The key names the module to the
  framework; the namespace says whose code it is.

  **Existing projects need no changes.** Module discovery resolves
  `<project-ns>.<module>.shell.module-wiring` and falls back to
  `wagoe.<module>.shell.module-wiring`, so a project generated on an earlier
  beta keeps booting as it is. To move a module across, move the directory and
  rename the namespaces — nothing else refers to them.

  `--base-ns` now works rather than being refused: `bb scaffold` passes the
  project's namespace by default, and `field`, `endpoint` and `adapter` accept
  the flag that only `generate` had.

### Added

- **`examples/shop` — a generated application you can read and run** (BOU-300).
  Between `examples/todo` (200 lines, no framework) and an external examples
  repository that tracked no release, there was nothing showing what a Wagoe
  app looks like. `examples/shop` is `wagoe new` plus one scaffolded module,
  committed as generator output:

  ```bash
  cd examples/shop
  WAG_ENV=test JWT_SECRET=$(openssl rand -hex 32) clojure -M:run
  curl localhost:3000/api/v1/products     # => []
  ```

  It is regenerated rather than maintained — `bb example:regen`, with
  `bb example:regen --check` in CI — so it cannot drift into describing a
  project shape nobody gets. CI also boots it and asserts the scaffolded
  module answers, and its dependencies resolve through `:local/root`, so a
  green run means *this commit* works rather than the last release.

### Changed

- **Modules emit Reitit route data; the normalized format is gone** (BOU-331,
  [ADR-037](dev-docs/adr/ADR-037-reitit-routes-directly.adoc)). **Breaking.**
  A route was `{:path "/users" :methods {:get {:handler 'ns/fn}}}` and is now
  `["/users" {:get {:handler ns/fn}}]`. Modules keep contributing
  `{:api [..] :web [..] :static [..]}`, and paths stay relative — the platform
  still adds `/api/v1` and `/web`.

  ADR-008 introduced the format so modules would not depend on Reitit; ADR-009
  then made Reitit the only router, and the second implementation was never
  built. What was left was 341 lines translating one EDN shape into another,
  and three things given up for it: Reitit's route conflict detection, reverse
  routing by name, and handlers as vars. The last of those is yours today — a
  typo in a handler was a boot failure and is now a lint error. The other two
  are now possible rather than delivered: conflict detection is still switched
  off (BOU-356), and reverse routing needs routes to carry `:name`.

  To migrate, turn each route map inside out: the `:path` becomes the first
  element, `:methods` and `:meta` merge into the data map, and quoted handler
  symbols become the vars themselves.

- **A library can serve HTTP without platform knowing it exists** (BOU-330).
  `:wagoe/http-handler` destructured six named route slots — user, admin,
  tenant, membership, workflow, search — each followed by its own copy of the
  same prefix-and-normalise block. Every module's mount point and doc
  visibility lived in platform, so a 31st library could not contribute a route
  without editing platform: the inversion the library split was meant to remove.
  Middleware was given injection in BOU-131; routes have it now.

  Routes arrive as one collection. A module returns `:routes` from its
  `ig-config` and says where its web routes mount with `:web-prefix` — admin,
  workflow and search say `/web/admin`, everyone else gets `/web`. Admin's
  slash redirect (`/web/admin` → `/web/admin/`) moved with it, as
  `:extra-web`, because it is admin's route.

  Module discovery asks any module for its own graph, not only the ones in
  platform's table, so a library with components of its own needs no entry
  anywhere in platform. A test proves it: `wagoe.widgets` is built, mounted and
  served, and the test fails if any platform source ever mentions it.

  The `:wagoe/http-handler` init-key is 106 lines, from 370. Platform
  endpoints, the security configuration, the interceptor services and the
  middleware pipeline are each their own function now. Both fail-loud
  bootguards — CSRF enabled with a blank secret, rate limiting without a shared
  cache in prod — were carried over whole and still refuse the boot.

  Verified by diffing the route table: 99 routes before and after, identical
  set, every path distinct so the concat order cannot change what matches.

- **A scaffolded module's web routes mount under `/web`, as the scaffolder always
  said they would** (BOU-330). `bb scaffold generate` writes
  `normalized-web-routes` with the docstring *"WITHOUT /web prefix… these routes
  will be mounted under /web by the top-level router"* — and the router did not.
  Framework modules were prefixed; discovered ones were passed through raw, so a
  generated module's web UI answered at `/products` while its own generated
  comment said `/web/products`. Folding every contribution through one path
  fixes it: measured in a generated project, `/web/products` now serves 200
  where it was a 404.

  **If you have a scaffolded module with web routes, its URLs move** — from
  `/<path>` to `/web/<path>`. That is the documented contract finally being
  kept, but it is a change to live URLs.

  One regression, caught by an existing test: `service <module>` prunes refs to
  the keys it dropped, and did so only for refs that were map values. With
  routes in a collection, `service user` kept `:module-routes` pointing at
  admin's and tenant's route keys, and Integrant refuses to build a config with
  dangling refs — so the service died at boot. Pruning now reaches into
  vectors, lists and sets.

  Two more found in review, both mine: `discovered-route-refs` referenced
  `:wagoe/<name>-routes` for every discovered module by convention, which was
  safe only while all of them went through the scaffolder's four-key shape. A
  module with a graph of its own need not serve HTTP — a background-jobs
  library is exactly the kind this welcomes — and referencing routes it never
  built is the same dangling ref, at boot. Refs are filtered against what the
  modules actually built.


### Fixed

- **The quickstart's last step 404'd** (BOU-328). It ended on
  `http://localhost:3000/admin/products`, which was wrong twice: the admin
  module is not in a generated project's `:active`, and admin mounts at
  `/web/admin`, not `/admin`. The final instruction of the framework's first
  page sent every new user to a 404.

  It ends on `curl http://localhost:3000/api/v1/products` returning `[]` —
  checked by scaffolding a module into a generated project and booting it — and
  says why `[]` is the right answer: the generated handlers are stubs, and
  wiring them up is your first edit. Adding the admin UI is a step of its own,
  with the `bb create-admin` that `wagoe add admin` itself tells you to run.

  The page also never named `bb quickstart`, the command `wagoe new` prints as
  your next step, while describing the same eight steps by hand. Both are named
  now, and a test keeps the page's URLs and that command in step.

- **Two guides were unreachable, and 29 ADRs did not say what they were**
  (BOU-329). `migrations.adoc` (304 lines, the largest guide) and
  `deployment-patterns.adoc` were in no nav and linked from nowhere under
  `docs/` — written, accurate, and invisible in the published site. Both are in
  the guides nav now, and linked from where a reader is standing when they need
  them: the quickstart's migration step, the tutorial's, and the deployment
  guide.

  Thirteen ADRs declared no status at all; sixteen sat on `Proposed` over code
  that had shipped and was in daily use — devtools, the dev dashboard, the MCP
  server, audience segmentation. Each is now `Accepted` and names the
  implementation, so the claim can be checked rather than taken.

  Three places said it and could disagree: the `:status:` attribute, the page's
  own Status section, and the index in `dev-docs/adr/README.adoc`, which
  repeated fourteen stale ones. A test holds the three together.

- **The getting-started tutorial showed code the scaffolder does not write**
  (BOU-327). `your-first-module.adoc` is the framework's flagship tutorial, and
  a reader following it hit a compile error on their first edit: it named
  `prepare-product` (really `prepare-new-product`), repository methods
  `find-product-by-id` and `list-products` (really `find-by-id` and
  `find-all`), and a `ProductInput` schema (really `CreateProductRequest`). Its
  core function validated its input and generated a UUID — the real one does
  neither, and the page's own lesson about keeping the clock in the shell
  contradicted the snippet above it.

  Rewritten against the generator's actual output, and a test calls the
  generators and checks that every name the page shows exists. It matches
  identifiers rather than formatting, so a rewrapped docstring does not fail the
  build; putting `find-product-by-id` back does, naming it.

  The page also said wiring a module into `deps.edn`/`tests.edn` "assumes the
  monorepo layout". `bb scaffold integrate` says the opposite and is right: a
  module under `src/` is already on the project's paths and its tests run under
  plain `clojure -M:test`.

  It now says plainly what the generator leaves undone — the generated service
  persists without validating, and `core/validate-product` is written but
  uncalled — with the edit that wires it in. That was the most useful thing the
  page could have said and the one thing it did not.


### Fixed

- **The e2e suite runs in CI again, and a red e2e test blocks the merge**
  (BOU-297). 52 tests over login, registration, MFA, sessions and admin CRUD —
  the flows a new user meets first — sat behind `if: false`, with
  `continue-on-error: true` under it for good measure. The reason given was that
  they need a live server on :3100 which CI does not start; the job's own steps
  run `bb e2e`, which starts one and tears it down. The reason had stopped being
  true and nothing noticed, because a parked job reports as skipped.

  Turning it on found real flakiness rather than a clean suite: three runs gave
  one pass and two failures, in `admin-users` and `admin-tenants` search. Both
  waited for a one-shot `htmx:afterSettle` listener, which fires for any swap
  anywhere on the page and could resolve on something else — leaving the
  assertion to read the previous search's rows. Its ten-second timeout resolved
  the promise to `false`, and nobody read it, so a swap that never happened was
  indistinguishable from one that happened and returned nothing.

  The helpers now mark what the request is about to replace and wait for that
  mark to go, with the timeout raised as an error naming the selector.

  `bb check:branch-protection` gained the other half of how this happened.
  It detected `if: false` and had no concept of `continue-on-error: true` —
  which is what the e2e job's run step actually carried, and which is worse: the
  job stays in the required context's `needs:` looking guarded while reporting
  success whatever the tests did. Both the job-level and step-level forms now
  fail the check, naming the step.

  Verified by breaking it: point the search button's `hx-target` at an element
  that does not exist and four tests report `HTMX never replaced
  #entity-table-container` — where the old mechanism failed two of them on a
  confusing assertion and let the other two pass over an app that was broken.


### Added

- **`wagoe doctor`** (BOU-324) — one answer to "something is wrong". Six commands
  could tell you (`bb doctor`, `bb doctor:env`, `bb doctor --all`, `bb check`,
  `bb smoke-check`, `bb guide next`) and nothing said which to reach for first.
  This runs the diagnostic ones in the order their answers depend on each other
  — environment, configuration, commands, project setup — and ends with a single
  next action.

  A failure in one of the first two stops the run and says so: a broken
  `config.edn` makes every command that boots the system fail for that one
  reason, and reporting the third symptom wastes the trip.

  The `bb` tasks are unchanged and remain the machinery; CI still calls them
  directly with `--ci`. They gained a `--edn` mode, which is what makes one next
  action possible — picking it out of formatted terminal output would mean
  parsing colour codes and column padding. Against a project pinned to an older
  wagoe-tools, `wagoe doctor` says so once and tells you what to bump, rather
  than reporting checks it could not read as passing.


### Changed

- **A healthy project produces no diagnostic noise** (BOU-324). Four checks
  warned about things that are not problems, and a warning you cannot clear is
  what teaches a reader to skim past the ones that matter.

  * `bb guide next` reported `wagoe-cli` and `wagoe-mcp` as "unintegrated
    modules" in the framework repository and told you to run `bb scaffold
    integrate` on them. Both are deliberately standalone, and the monorepo puts
    its libraries on `:paths` rather than in `:deps` — the question does not
    apply there. It was suppressed by a hand-maintained list of names to skip,
    which had never heard of the two added since.
  * A missing seed file and a missing `resources/migrations/` are both normal:
    seeding is optional, and `wagoe new` writes no migrations because the user
    module creates its tables through `:wagoe/user-db-schema`. Every fresh
    project carried two warnings it could only clear by making directories it
    did not need.
  * `bb doctor --env prod` reported "config.edn could not be parsed" about a
    file Aero reads without complaint, and told the reader to repair syntax that
    was never broken. Its reader table listed twelve Aero tags and not
    `#boolean`, which `acc` and `prod` both use; CI only ever ran `--env dev`, so
    the two profiles where it mattered were the two nothing checked. An unknown
    tag is now its own value rather than an exception.
  * `bb doctor`'s `wiring-requires` check told you to require
    `wagoe.dev-error-enricher.shell.module-wiring` — a namespace that does not
    exist. It checked BOU-171's contract, which BOU-326 replaced; a module whose
    library is genuinely missing now throws at boot, naming the library and both
    ways out. Removed, along with the half of `upgrade-wiring` that policed
    tenant middleware an application can no longer get wrong.

- **A generated `config.clj` is 63 lines instead of 404** (BOU-326). It used to
  carry the Integrant graph of every framework module it might enable — 41
  components, their refs, and the eighteen boolean flags that gated them —
  which meant the first file a new user opened was mostly framework plumbing
  they had no reason to edit. A module now describes its own graph
  (`ig-config` in its `module-wiring` namespace), and
  `wagoe.platform.shell.system.config/system-config` assembles what the config
  switches on. What is left in the generated file is the user module, the admin
  schemas, and a comment showing where your own components go.

  Nothing to do for an existing project: the file is yours, and the old one
  still works. To adopt the shorter version, replace `ig-config` with a call to
  `system-config` and keep whatever you added.

  `{{project-ns}}.system` is now empty too. The seven `ig/init-key` methods it
  defined belong to the libraries that own those keys, and four of those
  libraries (`jobs`, `reports`, `calendar`, `ui-style`) had no wiring namespace
  at all — so an application that enabled one of them and had not copied the
  defmethod failed the boot with `No such namespace: wagoe`.

### Fixed

- **`wagoe add workflow` created a module whose tables did not exist**
  (BOU-326). `libs/workflow` defines `:wagoe/workflow-db-schema`, which creates
  them, and documents it in the first lines of its wiring namespace. Neither
  the generated `config.clj` nor this repository's own system config wired it —
  the same omission in two hand-maintained copies of a graph the module already
  described. The workflow component logged `started without :db-schema
  dependency` and carried on.

- **The monorepo ran the tenant module whether or not it was configured**
  (BOU-326), and never wired `:wagoe/settings` or the AI service it had
  switched on. All three were the same class of defect: a component list
  maintained by hand next to a config that had moved on.

- **A module can no longer ship a component nothing wires** (BOU-326). The gate
  that catches the defect above, generalised: for every framework module, each
  `ig/init-key` it defines must be emitted by some application, or carry a
  written reason why not. It ships with seven exemptions and three of them are
  the same defect in another module — push enqueues jobs whose handlers are
  never registered, audience and storage ship HTTP routes nothing mounts.
  Emptying that list is BOU-346.

- **Two admin helpers had never worked** (BOU-326).
  `wagoe.admin.shell.module-wiring/admin-system-config` and
  `start-admin-only-system`, documented for REPL and integration use, built
  their graph against `:wagoe/database-context`, `:wagoe/logger` and
  `:wagoe/error-reporter` — keys no application wires. Removed; the module's
  real graph is `ig-config`.

### Fixed

- **A generated project no longer ships `bb deploy`** (BOU-325). It published
  *Wagoe's* libraries to Clojars, from a user's project, and `bb guide` printed
  a whole Deployment section advertising it. Both are gone downstream; the
  monorepo keeps them, and a test asserts the section appears in one context and
  not the other.

- **`bb check:deps` was a green row that could not fail — or worse** (BOU-325).
  It walks `libs/*`, which a generated project never has, so it reported "0
  libraries scanned, 0 violations" in every project. Give a project a `libs/`
  directory of its own and it does something worse than nothing: it reports the
  project's own namespaces as undeclared dependencies and tells the reader to
  edit a private var inside Wagoe's source. It is a framework-only check now,
  and `bb check` names it among the eleven it skips.

  `bb check --help` listed it too, along with the rest of a hardcoded list; it
  reads the registry now, so it describes the checks this project actually
  runs.

  A second lockstep test came with it. The existing one proves the template
  defines every task `bb check` calls; the new one proves it defines nothing
  framework-shaped — no task a monorepo-only check owns, and nothing that
  publishes. The template's copy of the task list has broken twice through
  registry drift (BOU-259, BOU-264); this closes the other direction.

- **One validation API instead of three** (BOU-323). `wagoe.core.validation` and
  `wagoe.core.utils.validation` each defined `validate-with-transform`,
  `validate-cli-args`, `validate-request` and a set of result accessors — same
  names, different implementations — and the first also re-exported the
  constructors from `wagoe.core.validation.result`. Nothing outside their own tests called any of them: the three real consumers —
  the user module's web handlers, the user CLI and the todo example — use the
  compiled-schema cache and one value predicate.

  The version in `wagoe.core.validation` also chose its *return shape* at
  runtime from the `WAG_DEVEX_VALIDATION` flag — `{:valid? true :data …}` with
  the flag off, a structured result with it on. A function whose result shape
  depends on an environment variable cannot be typed, tested or documented, and
  it had no test covering either branch. ADR-036 §2 settles the shape, so the
  fork is deleted rather than ported, along with the flag's only reader.

  What is left is one job per namespace: `wagoe.core.validation` caches
  compiled Malli validators (which is worth roughly ten runs each),
  `wagoe.core.validation.result` owns the result shape, and
  `wagoe.core.utils.validation` keeps the two predicates the user CLI uses. The
  cache had no tests at all; it does now.

  Not settled by this change: `failure-result` still returns `:errors` as a
  vector, and ADR-036 §2 asks for a map of field to messages. That migration is
  open, and pointing new code at `validation.result` points it at the shape
  that will move.

- **The error-shape allowlist is empty** (BOU-323, last migration step). It
  shipped with 81 findings on 18 August; the remaining 13 are gone.

  The string `:type` values in the outbound adapters are keywords —
  `"SmtpError"` became `:smtp-error`, and so on for IMAP, Twilio, the email and
  report job integrations, and the jobs worker's `:no-handler`. That change
  crossed a library boundary, which is why it went last: `wagoe-email`'s SMTP
  adapter reads the `:type` out of `wagoe-external`'s result — including as the
  default of a `get-in` — and `wagoe-email`'s tests cover `wagoe-external`'s
  code, so the two libraries had to move together.

  Two Malli schemas moved with them. `wagoe.jobs.schema/Job` and
  `wagoe.external.schema/EmailSendResult` declared `[:type :string]`, so the
  libraries' own published validators rejected the values their own adapters
  had started producing. `wagoe-jobs` also stored `:error` as JSON without
  restoring that keyword, so a consumer matching `:no-handler` matched against
  the in-memory store and missed against Redis; and its worker used the one
  field for two things — a failure kind (`:no-handler`) and a thrown
  exception's class name. The class has its own key now.

  `wagoe-push` came along, though the gate cannot see it: its adapters build
  `:error` from a computed value, and its `ports.clj` had documented
  ":error map" while the adapters returned a string.

  The dev dashboard's config-apply returned `:error` as a bare string in five
  branches; they carry `{:type … :message …}` now, and the page renders the
  message rather than the map.

  `wagoe.core.validation.result/normalize-result` and `legacy-result?` are
  deleted. They coerced between two result shapes at runtime, in the namespace
  that defines the one shape, and nothing outside their own tests called them.

  What the gate protects from here is a new violation, not a backlog: an entry
  added to the allowlist needs a `:why` and a `:count`, and an entry that stops
  exempting anything fails the build. It reads shapes, not intent — a computed
  `:type` is outside what any static check can see, which is why the push
  adapters above were found by reading rather than by the gate.

- **58 exceptions thrown at boundaries now say what kind of failure they are**
  (BOU-323; the untyped-throw step, which ADR-036 numbers first). ADR-022 required a `:type` on every
  `ex-info` reaching the HTTP boundary in April; the gate added last week
  measured 59 throws in `shell/` namespaces without one (58 after the user
  library's step). They are typed:
  `:configuration-error` for missing or unusable config (Datadog keys, Sentry
  DSN, an unknown AI or geo provider, a CSRF secret that is blank while CSRF is
  enabled), `:db/error` and `:database-error` for the database adapters,
  `:migration-failed` for the migration commands, `:storage-error` for the S3,
  GCS, local and image adapters, plus `:validation-error`, `:not-found`,
  `:conflict`, `:forbidden` and `:port-unavailable` where those fit.

  This is not bookkeeping. The HTTP layer maps `:type` to a status code, so an
  untyped throw on a request path is a 500 that could have said 404 or 400.

  It also fixed the classifier that reads `:type`. It walked to the *root
  cause* first, so a wrapped database failure was read as its `SQLException`
  and every one of them — a constraint violation, a syntax error — came back as
  `BND-303 "Database Connection Failed — verify the database is running"`. The
  type the thrower chose now wins over the class of what it wrapped, a failed
  query has its own code (`BND-304`), and `BND-102` ("Unknown Provider") has a
  producer at last.

  Which surfaced a fourth thing: the same mistake had four spellings across the
  module wirings — `:internal-error` in payments, `:configuration-error` in
  events, `:config-error` in the payments adapters, and `:validation-error` in
  storage, which the HTTP layer maps to **400**, telling a caller they sent a
  bad request when the server was misconfigured. All of them are
  `:unknown-provider` now.

  The allowlist is down from 71 findings to 13 — what is left is the
  `{:success? false}` normalisation of ADR-036 §3, which is the step ADR-036
  numbers second.

- **The user library's authentication and MFA results carry a typed error**
  (BOU-323, first migration step). `auth/authenticate-user` answered
  `{:error :authentication-failed :message "…"}` and the MFA shell answered
  `{:error "Invalid verification code"}` — a keyword in one place, a bare string
  in another, for the same idea. Both are `{:error {:type <keyword> :message
  <string>}}` now, the shape ADR-036 §3 decided, so a caller that wants to
  escalate can rethrow the `:error` map as a typed `ex-info` without inventing
  a taxonomy.

  Public behaviour is unchanged, and now tested rather than asserted:
  `IUserService/authenticate-user` still answers
  `{:authenticated false :reason … :message … :retry-after …}` — including its
  MFA-required branch, which the first version of this change broke silently
  because nothing in the repository reads that `:message` — and the three MFA
  endpoints still answer `{"error": "<message>"}`, which had no test at all
  until now. Direct callers of the two shell namespaces are affected; see
  UPGRADING.md.

  The deprecated `:wagoe/user-http-handler` init-key throws with
  `:type :configuration-error` instead of an untyped `ex-info`.

  `auth/change-user-password` is deleted rather than migrated. It had no
  callers, and it could not have had working ones: it called `update-user` with
  three arguments where the protocol takes two, so any call ended in an arity
  error. Ten of the 81 findings in `.wagoe/check-error-shape.edn` are gone with
  this step.

- **`bb check:error-shape`** (BOU-323). ADR-022 required a `:type` on every
  exception thrown at a boundary, in April; nothing checked it, and 59 `ex-info`
  throws in `shell/` namespaces had none by August (58 after the first
  migration step below). Roughly half sit on a
  request path, where the HTTP layer maps `:type` to a status code — so each of
  those is a 500 that could have been a 404 or a 400; the rest are startup, CLI
  and dev-only paths that never reach a response. ADR-036 added the return
  shapes: a `{:success? false}` carries `{:error {:type <keyword> :message …}}`,
  not a bare string, not a keyword, and not nothing.

  The gate shipped with 81 existing violations in
  `.wagoe/check-error-shape.edn`, each with a `:why` and a `:count` (71 after
  the first migration step below). The count
  is what makes it a burn-down list: an entry exempts the violations a file
  had, not the file, so a new one in an already-listed file fails the build and
  fixing one makes the entry stale — which fails it too. It reads shapes, not
  intent: a computed `:type` is left alone rather than guessed at.

- **The MCP server advertised seven resources and could serve none of them in a
  project** (BOU-320). `.mcp.json` ships in every generated project, so an
  editor agent connects and asks — and got `:unavailable` from all seven.
  `wagoe://conventions` read `resources/agents/knowledge.edn`, a path that
  exists in this repository and in no project; `wagoe://module-graph` walked a
  `libs/` directory only this repository has; the rest wait on the nREPL bridge.

  The knowledge base ships in the `wagoe-tools` jar now and is read from the
  classpath, so the resource that explains how to write Wagoe code answers
  wherever the server runs (a project can still override it with its own
  `resources/agents/knowledge.edn`). The module graph answers for a project:
  the `com.wagoe` libraries it depends on, which of those are dev-only, and the
  `:wagoe/*` modules its config switches on — from the config text, so a module
  named in a comment is not reported as running.

  And `resources/list` now advertises only what the project can serve. An
  advertisement that always answers "not available in the current context"
  costs the agent a round trip to learn nothing.

- **A malformed request answered 500, and no error carried a BND code**
  (BOU-321). Reitit applies a `:middleware` vector first-to-outermost, and the
  exception middleware sat last — *inside* request coercion. So a POST missing a
  required field threw past every handler in the chain: the app answered 500 to
  a request the client got wrong, and said nothing about which field. It is a
  400 now, naming the fields (`{"email": ["missing required key"]}`) but not the
  schema they were checked against.

  On top of that, in dev the response carries the BND code and where to read
  about it. The pipeline that produces it lives in devtools and reached no
  generated project; its classifier also did not recognise `:validation-error`,
  the type the platform HTTP boundary requires and every Wagoe handler raises —
  so the most common error in a Wagoe app was the one error it could not name.

  Production returns the same shape without the `dev` block, and its `details`
  name only the fields that were wrong: the full messages describe the schema
  (`"should be either \"admin\" or \"auditor\""` hands a caller every enum member
  it never knew about), so they are dev-only too. A 5xx still says only
  "Internal Server Error" with everything else in the log.

  Three conditions gate it — the `:wagoe/dev-error-enricher` key, which
  `wagoe new` writes into the dev config and nowhere else; a dev-like profile,
  which the wiring now passes to the interceptors (they read only `WAG_ENV`
  before, which a generated project never sets, so the check answered
  "development" wherever it ran); and devtools on the classpath, which the
  `:repl` alias keeps out of `-M:run`, the uberjar and the Docker image.
  Platform takes the enricher as an injected function and still has no
  dependency on devtools.

  Along the way: error responses no longer set their own `Content-Type`, so
  Muuntaja encodes them like any other body. An EDN or transit client can read
  them now, and an `Accept: text/html` request no longer hands Ring a raw map —
  the `PersistentArrayMap` crash `libs/admin/AGENTS.md` documents.

- **The first thing `bb quickstart` tells you to run did not exist** (BOU-319).
  Quickstart closes with "run `(status)`, run `(commands)`", and both lived only
  in this repository's own `dev/repl/user.clj`. The generated one was thirteen
  lines of `go`/`reset`/`halt`, so the first instruction a new user follows
  answered `Unable to resolve symbol: status`. A generated `dev/user.clj` now
  has `(status)`, `(modules)`, `(routes)`, `(config)`, `(fix!)` and
  `(commands)`, and `(go)` and `(reset)` print the startup dashboard the same
  text promises — listing the modules you added rather than every Integrant key
  the framework wired.

  The implementations live in `wagoe.devtools.shell.project-repl` rather than in
  the template — a template is compiled by nothing until someone generates a
  project and boots it, which is how this survived. `(commands)` lists what a
  generated project has, not this repository's palette of `(lint)`,
  `(check-all)` and `(scaffold!)`. Take devtools out of the `:repl` alias and
  the helpers print one line saying so: they resolve at call time, so a missing
  devtools cannot take `(go)` down with it.

- **devtools reached generated projects nowhere, and `wagoe add ai` wrote
  nothing** (BOU-318). The error pipeline with its BND codes, `(fix!)` and the
  dev dashboard existed only in the monorepo: `wagoe-devtools` was in no
  template dependency and in no catalogue entry, so `wagoe add devtools` failed
  with "Unknown module". The jar is now in the `:repl` alias of a generated
  `deps.edn` — dev-only on purpose, so it stays out of the uberjar and the
  Docker image — and in the catalogue as the first `:scope :dev` module, which
  `wagoe add` puts in that alias rather than in `:deps`. The classpath is what
  this fixes: the dashboard still needs `:wagoe/dashboard` in your config, and
  the generated `dev/user.clj` has no `(fix!)` yet (BOU-319).

  Finding that surfaced a second defect: `wagoe add` searched the whole
  `deps.edn` as text for the coordinate. The generated `:mcp` alias names five
  wagoe libs it launches the MCP server with, so `wagoe add ai`, `jobs`,
  `scaffolder` and `tools` all read as already installed — the command printed
  success and left `:deps` untouched. It reads the file as EDN now and looks
  where the module belongs.

- **A scaffolded module booted but served nothing in a generated project**
  (BOU-312). `wagoe new`'s config template discovered scaffolded modules and
  wired their components, but never passed their routes to the HTTP handler, so
  `/api/v1/<module>` was a 404 in every generated project while `bb quickstart`
  reported 8/8 Done. The first-run smoke could not see it either: it asserted
  that `/api-docs/` returns 200, which the framework serves in a project with no
  module at all. The smoke now curls the module quickstart scaffolded, requires
  a 2xx with a JSON body, and requires an unknown sibling path to 404 — a router
  that answers everything would otherwise pass. The nightly first-run matrix
  inherits all three.

- **A pull request from a fork ran no tests at all** (BOU-315). `ci.yml`
  triggered on `push:` alone, so same-repo branches were covered and fork PRs
  started zero jobs — leaving `All Tests Passed`, the one context branch
  protection requires, permanently *pending* rather than failing. Pending is not
  red, so nothing announced that the code had never been tested. CI now also
  triggers on `pull_request:`, with `push:` scoped to `main` so a same-repo PR
  runs the pipeline once rather than twice.

- **A tag on an untested commit could publish 30 immutable artifacts**
  (BOU-314). `publish.yml` verified that the tag agreed with source, that a
  re-run did not double-publish, and that everything landed — all properties of
  the *shape* of a release, none of them asking whether the code works. It now
  requires a completed, successful `All Tests Passed` on the tagged commit
  before any deploy step runs. Clojars coordinates cannot be recalled, so this
  aborts in seconds, before the release does any other work.

- **Stale install instructions on the first page users copy from** (BOU-313).
  `installation.adoc` pinned four coordinates to `1.0.1-alpha-42`, a
  discontinued line that Maven sorts *newer* than every beta, so anyone
  following it — or resolving a range — landed on unsupported jars. The manual
  install steps also asked for Java 17 while `install.sh` has required 21 since
  `1.0.0-beta-5`, in prose and in all four package-manager commands. Getting
  Started described bootstrapping "from the starter repository", which does not
  exist; it names `wagoe new` now.

 ### Removed

- **`realtime`'s `UserJWTAdapter`** (BOU-305). It resolved
  `wagoe.user.shell.auth` at runtime without declaring the dependency, so it
  worked in the monorepo and threw on first JWT verification for anyone using
  `wagoe-realtime` from Clojars — realtime's only verifier. Nothing wired it
  outside realtime's own tests. `IJWTVerifier` already exists as a port; the
  application supplies the implementation, since it knows what issues its
  tokens. `TestJWTAdapter` is unchanged.

### Fixed

- **`bb scaffold generate` no longer overwrites an existing module without
  asking** (BOU-308). The write path `spit`-ed every file unconditionally, so
  re-running the framework's most-recommended command after a day of editing
  replaced every file it had written — no prompt, no backup, exit 0. `--force` was
  declared in the CLI and threaded into the request; nothing read it. Generation
  now refuses before writing anything, listing the files it would have replaced,
  and exits non-zero. With `--force` it overwrites and reports those files as
  `:overwrite` rather than `:create`. A dry run never refuses, since it writes
  nothing. `--force` reuses the module's existing migration id rather than
  adding a second `create-<table>` pair, and the MCP `scaffold-module` tool
  gained the flag — without it, the verify-and-re-invoke loop that tool
  documents was a dead end. `bb scaffold adapter` had the same unconditional
  write, ignored `--output-dir`, and reported `:create` for a dry run.

### Added

- **`bb scaffold integrate` writes the config key, and `--dry-run` works**
  (BOU-310). It printed guidance and said so in its own header, while `bb.edn`,
  the generated `AGENTS.md` and `bb scaffold --help` all described it as wiring
  things up — and `--dry-run` was parsed and never read. It now writes
  `:wagoe/<module>` into `resources/conf/{dev,test}/config.edn`, reports what it
  did per file, and is a no-op on a second run. Since discovery loads the wiring
  namespace by convention (BOU-311), that key is the whole registration: no
  require to add by hand. The brace-balanced config editor `bb quickstart` had
  is now shared rather than duplicated, and reads `config.edn` with a real lexer:
  a brace inside a string or comment used to shift the depth count and place the
  key in the wrong section, silently, since the result still parsed. `--base-ns`
  is refused rather than writing a key discovery cannot resolve.

- **Scaffolded modules are discovered, and an unknown module key fails the boot**
  (BOU-311). `ig-config` built from a fixed list of module keys, so adding
  `:wagoe/tasks` after `bb scaffold generate tasks` produced nothing: no
  init-key, no route, no warning. `bb quickstart` reported success over a module
  that could not be reached. Any `:wagoe/<module>` carrying `:enabled?` — the
  shape `bb scaffold integrate` prints — is now wired from its
  `wagoe.<module>.shell.module-wiring`, and its routes reach `:wagoe/http-handler`
  through `:module-routes`, because the handler names its route keys one by one
  and cannot name a generated module. (In the framework's own app root only —
  generated projects got the `:module-routes` half in BOU-312, below.) A key whose wiring namespace will not load
  throws at boot naming the key, the namespace it looked for, and the fix; a
  misspelled key used to look exactly like a working one.

- **The scaffolder emits `shell/module_wiring.clj`** (BOU-309). It generated
  every file a module needs except the one its own integrate step requires, so
  `bb scaffold integrate` always reported that the module had no wiring yet and
  the user hand-wrote the Integrant keys the framework says never to hand-write.
  A generated module now ships `:wagoe/<module>-repository`, `-service`,
  `-routes` and the `:wagoe/<module>` key discovery looks for, each with a
  `halt-key!`. The dead next-step telling you to add the module to
  `[:active :wagoe/settings :modules]` — a path nothing reads, and one that
  contradicted `integrate` — is replaced by the command that does the work.

- **`com.wagoe/wagoe-config` — configuration loading as a library** (BOU-306).
  Four published libraries read settings through a `wagoe.config` namespace they
  resolved at runtime and nobody declared. It worked because the monorepo has
  one and `wagoe new` generates one, so the coupling was a convention rather
  than a dependency — in one case resting on a *private* var, meaning renaming
  it would have broken `wagoe-user` at runtime with nothing to catch it. Anyone
  assembling a Wagoe application by hand had no such namespace at all. The
  loading and the typed accessors now ship as a library that `ai` and `user`
  declare; `platform`'s `start!`/`restart!` and the devtools dashboard take the
  Integrant config as an argument instead of reaching for the application's
  composition root. This makes it 31 libraries.

### Changed

- **`check:ports` catches any reach into another module's shell, not two named
  suffixes** (BOU-307). The rule matched exactly `.shell.persistence` and
  `.shell.service`, and every real coupling in the tree went around it —
  database adapters, i18n render helpers, auth middleware. It now flags any
  foreign `*.shell.*` require, with composition roots exempt in code, because
  wiring is the one job that has to name concrete implementations; without that
  exemption 39 of the 62 cross-module requires would be the system assembling
  its own adapters. `.wagoe/check-ports.edn` gains
  `:allow-cross-module-shell`, a burn-down list of 9 target prefixes — one entry
  per decision rather than one per call site — each with a mandatory
  `:target-prefix` and `:why`, and each reported when it stops exempting
  anything. A malformed allowlist now throws instead of being read as empty.
  Framework gaps a downstream project cannot close — platform's database
  adapters, its interceptor pipelines, i18n's render helpers — are exempt
  everywhere rather than by file, so `bb check` still passes on a freshly
  scaffolded module.

- **The monorepo's `wagoe.config` is now `wagoe.system-config`, and `wagoe new`
  generates `system_config.clj`** (BOU-306). Assembling an Integrant system is
  an application's decision, and the name now says so — but the practical reason
  is that `wagoe.config` is a published library, and two namespaces of that name
  on one classpath shadow each other.

- **`bb check:isolation`, and an isolated build per library in CI** (BOU-304).
  "30 independently publishable libraries" was documented and never checked — no
  CI job had ever built a library against its own `deps.edn`. The new matrix job
  loads every namespace of each library against only its own dependencies. That
  turned out not to be enough on its own: 30 of 31 libraries already compile
  clean in isolation, `realtime` among them, because its require of
  `wagoe.user.shell.auth` sits inside a `try`/`catch` that swallows the failure.
  The namespace loads and the adapter throws on first use, from Clojars, in a
  user's application. So the gate reads the loading forms themselves — `require`,
  `requiring-resolve`, `the-ns`, `resolve` — and fails when a library reaches for
  a namespace it neither owns nor declares — written as a static `:require`, a
  fully qualified call, or any dynamic load. `libs/tools` cannot be a matrix cell
  because its runtime is Babashka rather than the JVM, so it gets the equivalent
  load under `bb` instead. It ships with a justified burn-down list in
  `.wagoe/check-isolation.edn` — 14 entries covering 26 sites — which BOU-305,
  BOU-306 and BOU-307 empty; an entry that stops exempting anything also fails
  the build, so the list cannot quietly become a drawer.

- **`bb bump <version>`** (BOU-316). The release bump was a global
  `find | xargs sed` copied out of the README, and three things were wrong with
  it at once: the snippet set `OLD` and `NEW` to the *same string*, so a
  copy-paste run rewrote nothing and reported success — with a verification step
  of `grep -r "$OLD"`, which then found nothing and agreed; `sed -i ''` is
  macOS-only, so the documented command fails on Linux and in CI; and it
  replaced *every* occurrence of the version string, including third-party pins
  and fixtures that happened to match. `bb bump` rewrites exactly the locations
  `check:versions` discovers — the same code, not a second list — prints a diff,
  and verifies the result against the version it just wrote. `--dry-run` lists
  the files and writes nothing, a plain re-run is a no-op, and a leading `v` is
  refused rather than written into 96 places where every location would then
  agree.

### Fixed

- **The documentation version scanner read one match per line, and attributed
  `--tag` too widely** (BOU-317 follow-up; found in review, neither reachable
  from the tree as it stands). Two `com.wagoe` coordinates on one line — routine
  Clojure formatting — left the second ungated, and therefore stale after a
  `bb bump` that then verified clean, because the check had the identical blind
  spot. Separately, `--tag` was attributed to any block mentioning this
  repository, so a third party's `--tag` in the same block would be read as a
  stale suite version and rewritten to ours, breaking the documented command. A
  tag now belongs to the nearest install URL at or above it, and every match on
  a line is a finding.

### Changed

- **`check:versions` now covers documentation as well as source** (BOU-317). It
  read 59 code locations and no `.md`/`.adoc`, so roughly 40% of the version
  surface was ungated — and it was the half users copy from, which is how
  `installation.adoc` sat 43 releases behind through every bump and every green
  run. It now also reads `com.wagoe` coordinates, `--tag v…` install recipes for
  this repository, and release-pinned prose such as "NEW in v…": 96 locations
  rather than 59. Historical documents are excluded by path, each with a stated
  reason, and documentation is checked *against* the version the source declares
  rather than being allowed to vote on it — otherwise a wholly stale
  documentation set would report the correctly-bumped files as the offenders.

- **`check:branch-protection` also verifies that CI can run at all.** Coverage
  of every job is worth nothing on a run that never starts, so the gate now
  reports a missing `pull_request:` trigger and an unscoped `push:` that would
  double every run — and, because a release path that routes around CI makes the
  merge gate optional in practice, a `publish.yml` whose CI guard is missing or
  sequenced after the deploy.

## [1.0.0-beta-5] — 2026-08-16

Running as more than one process. `1.0.0-beta-4` could serve an application from
a single JVM and little else: cross-module calls had a protocol seam but nothing
that crossed a network, the prod profile could not boot, and the deployment
documentation described topologies with no reference to run them from.

This release makes the seam real. A module can be served over HTTP and called
through the protocol its callers already use, one or several modules can be
booted as their own service, and modules can tell each other things
asynchronously through a new event bus. A circuit breaker keeps a service that
is down from taking its callers with it, and the state behind all of it lives
where every replica can see it rather than in one JVM's memory.

The other half is what looking closely turned up. Holding the cache and
job-queue adapters to a shared contract found twenty-one places where they
disagreed — including three different answers to what order jobs come off a
queue in, two of them newest-first. A failed production boot logged the database
password. `install.sh` accepted a JDK too old to run any of this. Those were all
shipped behaviour, and none of it was visible from the suite that was supposed
to be watching.

### Removed

- **Three HikariCP pool keys that no build has ever applied** (BOU-89).
  `:keepalive-time-ms`, `:validation-timeout-ms` and
  `:leak-detection-threshold-ms` were documented and accepted by nothing: the
  pool map is a `:closed` Malli schema and the builder applies five keys, so a
  config setting any of them failed the boot at `:wagoe/db-context`. Removed
  rather than implemented — the prod and acc profiles shipped with them and
  could not start.

- **Integrant config for four libraries that register none** (BOU-284,
  BOU-286). `wagoe add jobs|calendar|reports|ui-style` wrote
  `:wagoe/<lib> {:provider :in-memory}` into generated projects, and nothing
  reads it; `ui-style`'s own AGENTS.md says it has no Integrant keys.
  `:post-install` now says how each library is actually used. `wagoe add push`
  wrote one key where the library registers seven `:wagoe.push/*`, so the
  generated `config.clj` assembles them properly instead.

- **`bb scaffold new` / `wagoe scaffolder new`** (BOU-259). Projects were
  generated by two independent implementations: the `wagoe new` templates in
  `libs/wagoe-cli`, and a second copy inside the scaffolder. The copy had
  drifted until it no longer produced a Wagoe project — 7 files against the
  CLI's 20, with no `com.wagoe` dependencies, no `main.clj`/`system.clj`, no
  `build.clj`, no `tests.edn` and no `.env`, so the result could not boot, test
  or build. Both commands now print the replacement (`wagoe new my-app`) rather
  than failing as an unknown command, and both exit non-zero so a script that
  still calls them cannot read the redirect as a generated project — including
  `--help`, which the scaffolder CLI briefly answered with root help and exit 0,
  making the removed command look available to anything probing for it.
  Supersedes ADR-002.

### Added

- **A module can run in another process without its callers knowing** (BOU-90).
  `wagoe.platform.shell.rpc` serves any module's protocol over HTTP, and
  `remote-adapter` returns a value implementing that same protocol by calling
  it — so a call site keeps using the port it already used. transit+json on the
  wire (JSON has no keywords), a required `x-rpc-service-key` compared in
  constant time, and the server resolves an operation against the protocol's
  own `:sigs`, so the endpoint cannot become a general-purpose remote eval.

- **`service` launch mode** (BOU-91). `java -jar wagoe.jar service payments`
  boots only the modules named plus the platform they need; several can share
  one process. Declared in `config.edn`, on its own listener. The counterpart
  to the remote-port adapter: one of them slices a module out, the other calls
  it.

- **Reference deployment topologies** (BOU-89).
  `deploy/compose/multi-instance.yml` (N replicas behind nginx, Redis-backed
  cache, sessions and rate limiting), `deploy/compose/per-service.yml` (one
  module as its own service), `deploy/k8s/wagoe.yaml`, and
  `docs/modules/architecture/pages/deployment-topologies.adoc` describing when
  each applies.

- **`libs/events` — an asynchronous event bus** (BOU-93). The other half of the
  cross-process story: a publisher does not know who is listening, does not
  wait, and is unaffected if a consumer is down. Two adapters — in-memory, and
  Redis Streams with consumer groups, at-least-once delivery, reclaim of
  abandoned entries and a dead-letter stream after `:max-deliveries`. Three
  protocols rather than one, so a module that only emits does not depend on
  subscription machinery it never calls.

- **A circuit breaker for the remote-port adapter** (BOU-285). Retries bound
  the damage of one call; this bounds the damage of many. State lives in the
  cache port, so replicas share one breaker rather than each discovering the
  outage separately, and a `set-if-absent!` lease lets exactly one replica
  probe when the window elapses instead of all of them. Trips on consecutive
  `:rpc/unavailable` and `:rpc/timeout` — failures where the call did not reach
  the service — and returns `:rpc/circuit-open` with `:retry-after-ms`, which a
  log can tell apart from "tried and could not reach it". Opt-in: without a
  `:cache` there is no breaker and the client behaves as before.

- **`wagoe new --no-user`** (BOU-234). The scaffold wired the user chain
  unconditionally, so every generated application carried authentication and
  four tables whether or not it had accounts. Platform has been decoupled from
  user since BOU-171; this makes the scaffold's default a choice.

- **Realtime topics accept in-process subscribers** (BOU-233). Every subscriber
  had to be a WebSocket connection, so an application wanting an event to reach
  server-side code ran a second pub/sub beside this one.

- **Four dev-workflow Claude Code skills** (BOU-235) — `wagoe-doctor`,
  `wagoe-migrate`, `wagoe-scaffold` and `wagoe-debug`, beside the existing
  `wagoe` and `wagoe-setup`. Closes BOU-237, BOU-238, BOU-240 and BOU-242.

- **Adapter contract suites for cache and jobs** (BOU-288, BOU-289). Each
  library's adapters had separate test suites sharing no cases, so nothing said
  they behave alike — and they did not. One sweep per library now runs the port
  against every adapter; between them they found twenty-one divergences that
  the per-adapter suites had been passing over. `libs/events` gained the same
  thing with BOU-93.

- **`bb check:changelog`.** A branch that changes shipped `src/` must add an
  entry here. Thirty pull requests merged in the eleven days to 2026-08-16
  without one between them — a new library, a new launch mode, a removed config
  key and a change to the order jobs are dispatched in. Nothing checked. Tests,
  docs, CI and dev tooling are out of scope, and `[no changelog]` in a commit
  message waives it for a source change nobody will notice.

- **Gates.** Required status checks are verified against the job names CI emits
  (BOU-277) with no repository secret; every third-party namespace a library
  requires must be declared, and the allowlist is empty (BOU-273, BOU-276); a
  library with a `build.clj` must have a documentation page (BOU-93).

- **Three more first-run matrix cells** (BOU-232). A machine with a JDK too old
  to use and a machine with no network, both in `first-run-preconditions.sh`;
  and zsh alongside bash and fish in the adversarial suite, which had been
  verified by hand once and never since. The old-JDK cell found the `install.sh`
  defect below. The offline cell pins behaviour that was already correct: the
  installer fails with something actionable rather than raw curl output.

- **Nightly first-run matrix** (BOU-232). The broad first-run coverage —
  Ubuntu, Fedora and Arch for the install-to-serving-app path, plus the
  adversarial cases on Ubuntu and Fedora — now runs on a schedule and on
  demand, rather than only when someone remembers. The fast single-image smoke
  test stays on every push. `workflow_dispatch` makes the same matrix the
  pre-release gate.

### Changed

- **Job dispatch is FIFO within a priority, on every backend** (BOU-289).
  *Behaviour change.* The three `IJobQueue` backends had three different
  answers: the DB adapter was FIFO, the in-memory adapter ran critical, high
  and normal newest-first, and Redis ran `:low` newest-first. Anything relying
  on the old dispatch order in development or on Redis `:low` will see a
  different order. The DB adapter's `ORDER BY priority_rank, created_at` is now
  what all three do.

- **The cache adapters agree about expiry, batch reads and patterns**
  (BOU-288). *Behaviour change.* An expired key now reads as absent from every
  operation rather than only from `get-value`: `delete-key!` and `expire!`
  return false for one, `keys-matching` omits it, and `compare-and-swap!`
  matches `nil` against it. `ttl` rounds up, as Redis does, instead of
  reporting 29 for a key set to 30 a millisecond earlier. `get-many` returns
  keys holding `false` or `nil` instead of dropping them. `compare-and-swap!`
  on Redis keeps the key's TTL. And the in-memory pattern matcher treats
  everything but `*`, `?` and `[…]` literally — it compiled the glob straight
  to a regex, so `a.b` matched `axb`.

- **Heavy test dependencies moved out of the shared `:test` alias** (BOU-260).
  Embedded PostgreSQL's two platform binaries, the OpenTelemetry in-memory
  exporters and clj-http-lite sat in `:test`, which all 27 per-library CI jobs
  resolve, so one flaky artifact failed them all under an innocent library's
  name. They now live in `:test/pg`, `:test/pg-mac`, `:test/otel` and
  `:test/http`; `:test/all` composes them for a full local run.

- **platform no longer requires any module's wiring** (BOU-131). The system
  wiring statically required ten module-wiring namespaces, so every consumer of
  platform had to ship every one of those jars whether it used them or not, and
  a missing one was a `FileNotFoundException` at load. The layer that emits a
  key now registers it.

### Fixed

- **`install.sh` accepted any JDK, including ones too old to run Wagoe**
  (BOU-232). The check was `java -version | grep -q "version"`, which every JDK
  back to 8 passes, while the installer's own text says JDK 21+. On a machine
  with an older JDK it reported "JVM already installed" and carried on, and the
  failure surfaced much later as a class-file-version error out of the Clojure
  compiler — which tells a newcomer nothing. It now reads the major version,
  says which one it found and which is needed, installs a current JDK, and
  verifies the result rather than assuming it: an older JDK still first on PATH
  is a loud failure naming the one that is winning, not a silent success.

- **A deleted job stayed on the queue** (BOU-289). Redis and the in-memory
  backend removed the job data but left its id in the list, so it still counted
  towards `queue-size` and the dequeue that reached it found nothing and
  returned nil — work queued behind a deleted job waited for a poll that might
  not come. `peek-job` on Redis read only the `:normal` list, so a queue holding
  a critical job peeked as empty and disagreed with the very next dequeue.
  `list-queues` returned a Redis queue once per priority in use, and named
  in-memory queues that had been fully drained.

- **The in-memory cache lost concurrent writes while reclaiming expired
  entries** (BOU-288). `delete-key!` and `expire!` read the entry and then wrote
  based on what the read said, so a value written in between was deleted while
  the caller was told there had been nothing there; `expire!` could also
  recreate a key deleted since the read as an entry with an expiry and no value,
  which `exists?` reported as present and `get-value` threw on. Both are one
  `swap-vals!` now.

- **A BigInteger beyond 64 bits was silently lost by the Redis cache**
  (BOU-288). It took the native-integer path, where reading it back overflows
  `Long/parseLong` and the decimal bytes are not Nippy either — so the write
  reported success and the read reported a miss. `get-many` and `delete-many!`
  also handed an empty collection straight to MGET and DEL, which is an error
  rather than an empty answer.

- **The prod and acc profiles could not boot** (BOU-89). `:port` came from
  `#env POSTGRES_PORT` as a string against a `[:port pos-int?]` schema, on top
  of the three pool keys above.

- **The documented way to run `wagoe-mcp` corrupts the protocol** (BOU-105).
  Fixed along with the docs that described it.

- **The AI CLI discarded unknown options and swallowed the failures it was
  built to report** (BOU-279, BOU-280). Every subcommand destructured
  `parse-opts` without reading `:errors`, so a typo'd flag was ignored rather
  than rejected; failures named neither the provider that failed nor the
  endpoint that was configured, and an exhausted balance was reported as a rate
  limit.

- **`bb ai gen-tests` emitted tests that did not compile** (BOU-239), and
  **`bb i18n:scan` — a required CI job — could not report anything** (BOU-241).

- **The documented ways to run the app now run the app** (BOU-243).
  `clojure -M:repl-clj` could not start the system.

- **`bb migrate create` wrote to a shadowed directory** (BOU-274). Migrations
  under a `migrations/` that lost the classloader race were skipped silently;
  it now fails loudly. **`bb scaffold field` listed a file it never opened**
  (BOU-275).

- **Five quality items, each with a gate behind it** (BOU-92, BOU-151, BOU-61,
  BOU-253, BOU-245). The dependency allowlist is empty; platform no longer
  makes the SMTP/IMAP/Twilio adapters a mandatory dependency of the HTTP layer.

- **The scaling and deployment documentation described a system from several
  tickets ago.** Two entries told a reader to do something that breaks, and the
  rest was drift — launch modes documented as absent, shipped adapters
  described as unbuilt, and `libs/events` missing from the readiness matrix.

- **Every `bb ai` subcommand failed in a generated project** (BOU-272).
  `wagoe.tools.ai` shelled a plain `clojure -M -m wagoe.ai.shell.cli-entry`,
  but generated projects carry `com.wagoe/wagoe-ai` only in their `:mcp` alias,
  never in `:deps` — so `explain`, `gen-tests`, `sql`, `docs` and
  `admin-entity` all died with a FileNotFoundException. All five are listed in
  the generated `bb.edn`, the generated `AGENTS.md`, and the shipped `wagoe`
  Claude Code skill. The dependency is now injected via `-Sdeps`, matching what
  `bb scaffold` has always done, with a `WAGOE_AI_ROOT` override for exercising
  unreleased AI code from a generated project.

- **`bb migrate create` threw a ClassCastException** (BOU-271). The migration
  config carries `:migration-dir` as the discovered *vector* of every directory
  on the classpath; `up`, `status` and `rollback` accept that, but
  `migratus/create` casts it to String. So the documented way to add a
  migration failed for everyone, pushing people onto hand-written files — the
  exact path BOU-256 was filed against, because that filename format is easy to
  get wrong and silently invisible to migratus. Creation now receives the
  project's own `migrations/` as a string.

- **`bb check`'s Config doctor gate could never fail** (BOU-270). It invoked
  `bb doctor` without `--ci`, and doctor prints its errors but exits 0 unless
  that flag is set — so the row reported `✓` for every config, including one
  that did not parse. It now passes `--ci`. Every other checker in the registry
  already exits non-zero on violations; doctor was the only flag-gated one.

- **Scaffolded modules now pass `bb check`** (BOU-267). Generated source
  produced 36 clj-kondo warnings, and clj-kondo exits non-zero on warnings, so
  `bb check` failed the moment a user scaffolded their first module. Two of
  those were real defects rather than lint noise: `update-<entity>` was
  declared in *both* the repository and service protocols in one namespace, so
  the second silently overwrote the first and `ports/update-<entity>` carried
  the wrong arity; and the generated service called `.list-<plural>` on its
  repository, which only declares `find-all`, so listing failed at runtime. The
  repository method is now `update-entity`, the service calls `find-all`, and
  the remaining warnings — unused `this`/`req`/`config` bindings, an unused
  require, a partially-reified protocol — are gone. `bb check` on a freshly
  scaffolded module is 9/9.

- **`wagoe new` into a directory you cannot write surfaced a stack trace**
  instead of a permissions message (BOU-232). The pre-flight directory check
  cannot catch it — the target does not exist yet, so the failure comes out of
  `clojure.java.io/writer` partway through generating. Found by the adversarial
  suite's read-only case, which had never actually run: it skipped whenever the
  container was root, because `chmod` does not restrict uid 0. It now uses a
  read-only bind mount, which the kernel enforces for every uid, so the whole
  suite runs with nothing skipped for the first time.
- **`bb check` reported failures a user could not act on** (BOU-264). It runs
  each check as a subprocess (`bb check:fcis`, …), but five of them only mean
  something in the Wagoe repository — `doc-counts` and `poms` compare against
  the published library set, `agents` diffs `knowledge.edn`, `no-boundary` is a
  rename gate for this repo's history, and `docs:lint` lives on the `dev/` path.
  Generated projects define none of those tasks, so `bb` exited 1 on "File does
  not exist" and they were reported as violations. Checks now declare a scope
  and the framework-only ones are skipped outside this repo — and **named** in
  the output, because a silently shorter list reads as a clean run. Generated
  projects also gain `check:test-meta`, `check:test-tags` and `check:hygiene`,
  which were monorepo-only despite being useful anywhere.

- **`bb create-admin` could not create a user at all** (BOU-266). The
  `:user-cli` alias ran the CLI through `-e` reading `*command-line-args*`, and
  `clojure.main` takes the first non-option argument as a script path — so the
  `create` verb was dropped and the CLI rejected `--email` as an unknown global
  option. Without an admin user the admin UI redirects to a login nobody can
  pass. The alias now uses `-m` against a new `-main` on
  `wagoe.user.shell.cli-entry`. Existing generated projects pick this up when
  they move to a release containing it.
- Generated projects were missing the `check:ports` bb task while `bb check`
  shelled out to it, so the hexagonal gate BOU-80 requires — and that the
  generated `AGENTS.md` documents — could not run in a new project. Found
  while reconciling the two generators.
- **H2 is now file-backed in dev** (BOU-265). `bb setup --database h2` wrote
  `:memory true` for every environment, and an in-memory H2 database is private
  to the JVM that opened it. `bb migrate up`, `bb create-admin` and the app are
  three separate processes, so each got its own empty database: migrations
  applied nowhere, the admin user was written nowhere, and the app booted
  unmigrated — with every step exiting 0. `bb quickstart --preset minimal`, the
  first-listed preset, could not produce a working app. Dev and other non-test
  environments now use `./<env>-h2-database`; the test profile keeps in-memory
  H2, which is correct for a single JVM. The path is explicitly relative
  because H2 2.x rejects an implicitly-relative one.
- The root README said a new project gets "H2 in-memory database
  (zero-config)". It gets SQLite, and has since before the rename.

### Security

- **A failed production boot no longer logs the database password** (BOU-244).
  `wagoe.main` logged the exception on any startup failure, and Integrant's
  ex-data carries `:value` — the config map for the key that failed, which for
  `:wagoe/db-context` is the database configuration.

- **js-yaml and brace-expansion advisories cleared in the docs build**
  (GHSA-5p4m-2wfm-xmqj, GHSA-mh99-v99m-4gvg, GHSA-rgw5-rvv9-x895). Both are
  dev-only transitive dependencies of Antora, which parses only our own
  playbook, so the exposure was theoretical; the override pinning js-yaml did
  not exclude the affected release.

## [1.0.0-beta-4] — 2026-08-01

Everything a new project touches. `1.0.0-beta-3` shipped a scaffolder whose
migrations were never applied, so the sample module `bb quickstart` creates had
no database table — and reported success while doing it.

### Fixed

- **Scaffolded migrations are now applied.** The scaffolder emitted
  `001_create_tasks.sql`; migratus discovers `<id>-<name>.up.sql`, so the file
  sat on disk and `bb migrate status` reported "0 pending" while
  `bb quickstart` reported 8/8 Done — running zero migrations succeeds. Also
  fixes the id: the counter scanned `resources/migrations` while writing to
  `migrations/` and parsed ids with `Integer/parseInt`, which overflows on
  14-digit timestamps, so every module got `001`. Ids are now UTC timestamps
  that step forward on collision, and each migration gets a matching
  `.down.sql`. `wagoe scaffold field` had the same defect.
- **`bb doctor` no longer passes configs the application cannot load.** An
  unparseable `config.edn`, or `:active` misspelled, both left every check
  inspecting an empty map and reporting a pass — `bb doctor --ci`, a CI gate,
  exited 0 on a config that fails at runtime with `No active database
  configured`.
- **`install.sh` supports Fedora and the RHEL family.** Previously
  "Unsupported OS" with no path forward. `which` also joins the prerequisite
  check: babashka's installer calls it and minimal Fedora images do not ship
  it, so the run died inside a third-party script.
- The admin UI is reachable at `/web/admin` without the trailing slash, and
  `wagoe add admin` now says that `bb create-admin` is needed to log in.

### Added

- **`bb db:seed`.** Previously advertised in `bb.edn` and printed "not yet
  implemented". Seed files are EDN — a map of table → rows, or a vector of
  `[table rows]` pairs when order matters. Inserts run in one transaction, and
  seeding refuses outside development environments unless `--force` is given.
- **A production build path for generated projects**: `src/<ns>/main.clj`,
  a `:run` alias for foreground start, a `:build` alias producing an uberjar,
  and a Dockerfile. Previously a generated project could only be started from
  an editor-connected REPL, so it could not be containerised or supervised.
  Shutdown is graceful — a container stop drains the server and closes the
  pool.
- A first-run smoke test in CI that walks install → `wagoe new` →
  `bb quickstart` → serving app inside a bare container, asserting on HTTP
  rather than exit codes.

### Changed

- The published quickstart told newcomers to run `clojure -M:repl-clj` and
  `export WAG_ENV="development"`. Neither works in a generated project: the
  alias exists only in the monorepo, and there is no `conf/development`
  profile. Corrected across the getting-started, index, repl-workflow and
  monorepo pages.

## [1.0.0-beta-3] — 2026-07-31

The release that makes the documented install path true. `1.0.0-beta-2`
advertised a first-run flow that did not work on a machine with nothing
installed; every failure below was measured in a clean `ubuntu:24.04`
container, not inferred.

### Changed

- **New projects default to SQLite** (previously H2). SQLite needs no server
  and, unlike in-memory H2, the data survives a restart. `org.xerial/sqlite-jdbc`
  now ships in a generated project's `deps.edn`, and the generated config reader
  gained the `:wagoe/sqlite` branch it previously lacked.
- `bb setup` defaults to SQLite on **all three** entry points — interactive menu,
  `bb setup ai`, and flag invocations such as `bb setup --payment mock`.
- `bb quickstart` no longer runs the configuration wizard over a config that
  `wagoe new` has just written. Pass `--preset <name>` to reconfigure deliberately.
- `bb quickstart`'s banner says it will *verify* rather than *start*; it never
  started the app, and claiming otherwise sent people hunting for a failure that
  had not happened.

### Fixed

- **`install.sh` failed three separate ways on clean Linux** (BOU-226):
  - no prerequisite check, so a bare image died in ~1s on sdkman's own
    "Please install unzip" — an error about a tool the user never asked for,
    printed under a screenful of sdkman ASCII art, never naming Wagoe. Now
    checks `curl`/`git`/`unzip`/`zip` up front and prints the exact command for
    the detected OS.
  - sourcing sdkman's init script under `set -euo pipefail` aborted with
    `SDKMAN_CANDIDATES_API: unbound variable` *immediately after* sdkman printed
    "All done!". The source and `sdk install` now run under `set +u`.
  - `sudo` was assumed to exist, which it does not in containers or minimal
    images. Worse, the steps were written `sudo ./installer && rm installer`, and
    `set -e` exempts the failure of any command in an `&&` list except the last —
    so a failed install fell through and printed `✓ Clojure CLI installed` having
    installed nothing. Adds `as_root()` and reachable `|| fail` handling.
- **A new project could not complete its own quickstart** (BOU-228). The setup
  wizard listed PostgreSQL first and defaulted to it, so both a non-interactive
  run and a user pressing Enter selected a database server that was not installed
  and whose driver was not on the classpath. Migration died on
  `ClassNotFoundException: org.postgresql.Driver` and nothing ever served.
- The generated config used `:database-path` where the platform's config reader
  looks for `:db`, yielding `database-path nil` and a Malli validation abort at
  migration time.

### Known gaps

- `/admin` returns 404 in a new project — `com.wagoe/wagoe-admin` ships in
  `deps.edn` but `:wagoe/admin` is not wired into the generated config (BOU-229).
- Generated projects have no `:run` alias, `-main`, or `:build` alias, so the app
  can only be started from a REPL via `(go)` (BOU-254).

## [1.0.0-beta-2] — 2026-07-31

First release under the Wagoe name, on the **`com.wagoe`** Clojars group.

### Changed

- **Clojars coordinates are now `com.wagoe/wagoe-<lib>`** (previously
  `org.boundary-app/boundary-<lib>`). The group matches `wagoe.com`; `org.wagoe`
  was never claimable, since Clojars verifies a reverse-domain group against its
  matching domain and `wagoe.org` was not owned at the time.
- Website moved to `framework.wagoe.com`, and subsequently to `wagoe.org` with the
  older hostnames kept as permanent redirects.

### Security

- PostgreSQL JDBC driver bumped to 42.7.12 (GHSA-j92g-9f8w-j867, high), and a
  stale root `pom.xml` carrying a vulnerable pin was removed.

### Fixed

- Three bugs that let the built jar start silently without serving (BOU-251), plus
  a smoke test that proves the artifact actually serves.
- Transactional job enqueue hardened: the transaction contract is guarded, a lost
  log field restored, and the capability moved onto a port (BOU-252).
- The uberjar build was repaired and its artifact version pinned to the suite.

### Renamed — Boundary is now Wagoe (BOU-209 … BOU-217)

The framework is renamed from **Boundary** to **Wagoe** ahead of the first
public release, and versioning moves to plain SemVer starting `1.0.0-beta-1`.
This is a hard rename with no compatibility shims: nothing had been published
under a stable version, so no deprecation window is provided.

**Migration — mechanical replacements, in this order:**

| Kind | Before | After |
|---|---|---|
| Namespaces | `boundary.<seg>.…` | `wagoe.<seg>.…` |
| Integrant / config keys | `:boundary/http-server` | `:wagoe/http-server` |
| Maven / Clojars coords | `org.boundary-app/boundary-<lib>` | `com.wagoe/wagoe-<lib>` |
| deps.edn local aliases | `boundary/<lib>` | `wagoe/<lib>` |
| Environment variables | `BND_*`, `BOUNDARY_*` | `WAG_*` |
| CLI binary | `boundary <cmd>` | `wagoe <cmd>` |
| Resource paths | `boundary/i18n/translations` | `wagoe/i18n/translations` |
| MCP resource URIs | `boundary://…` | `wagoe://…` |
| MCP server name (`.mcp.json`) | `"boundary"` | `"wagoe"` |
| `AGENTS.md` region markers | `<!-- boundary:installed-modules -->` | `<!-- wagoe:installed-modules -->` |
| Redis pub/sub channel | `boundary:realtime:bus` | `wagoe:realtime:bus` |
| Logger name (logback) | `boundary` | `wagoe` |
| Repositories | `thijs-creemers/boundary{,-examples}` | `wagoebv/wagoe{,-examples}` |
| Sites | `boundary-app.org`, `get.boundary-app.org` | `wagoe.org`, `get.wagoe.org` |

`docs.boundary-app.org` is retired; the documentation is folded into
`wagoe.org/docs`. The GitHub repository transfers preserve history
and leave redirects in place, so existing clones keep working until you update
the remote.

**Not renamed, on purpose.** "Boundary" is also an architecture term in this
codebase, and those uses are unchanged: the FC/IS *boundary rules* (ADR-021),
the persistence / HTTP / API / DB *boundary*, the `boundary-check` step,
*boundary conditions* and *boundary testing*, and "System Boundary" in the PRD.
A `bb check:no-boundary` gate guards the renamed token families and treats the
prose word as report-only for exactly this reason.

### Security

Framework Quality (Phase 0–2, 2026-07) security hardening:

- **`boundary-user`**: JWT algorithm pinned and startup fails fast on a weak secret — a `JWT_SECRET` of ≥32 chars is now required, with a separate CSRF secret (BOU-163, #253).
- **`boundary-user`**: MFA TOTP secrets are encrypted at rest and backup codes are hashed (BOU-162, #252).
- **`boundary-user`**: IDOR closed on the user API routes (`/users`, `/users/:id`) — a caller can no longer read or modify another user by id (BOU-190, #280).
- **`boundary-user`**: sessions are rotated on password and role change, defeating fixation and stale-privilege reuse (BOU-191, #281).
- **`boundary-user`**: user-management web routes are mounted behind authz guards and the web user-detail page is admin-only (BOU-197, #286, #287).
- **`boundary-platform`**: 5xx responses no longer leak exception internals, and the admin error flash no longer echoes raw exception messages (BOU-161, BOU-182, #250, #260).
- **`boundary-platform`**: hardened security response headers and session-cookie attributes; brute-force lockout and session-fixation coverage; a dedicated authz negative-path suite (RBAC / IDOR / cross-tenant) (BOU-168, #272, #274, #275).
- **`boundary-jobs`**: reliable Redis dequeue — jobs are no longer lost when a worker crashes mid-dequeue (BOU-160, #248).
- **docs-site**: patched a js-yaml DoS (GHSA-h67p-54hq-rp68) and a brace-expansion advisory (#251).
- **Phase 1 production blockers**: secure session-cookie defaults, tenant migration provisioning, and PostgreSQL in CI (BOU-158/159/164, #247).

### Added

- **`boundary-cli`**: `boundary agents update` (+ generated `bb agents:update` task) refreshes the framework-owned sections of a project's `AGENTS.md` after a Boundary upgrade. The marker-delimited blocks (`gen:fc-is`, `gen:naming`, `gen:pitfalls`, `boundary:available-modules`) are re-rendered from the installed CLI's template and spliced in place; everything outside the markers — team notes, custom sections — is left untouched, the `installed-modules` block is treated as project state, and installed modules stay removed from the refreshed available table (mirroring `boundary add`). `--check` exits 1 when the file is stale, for CI. Idempotent (#236).
- **dev tooling**: Hot-path benchmark harness (`clojure -M:bench hotpaths`, `dev/boundary/bench/hotpaths.clj`) measuring current-vs-proposed implementations for each performance-assessment finding: raw vs compiled Malli validation, reflective vs protocol logger dispatch, DB result-set case-conversion passes, and i18n marker resolution. Notable negative result recorded: memoizing case-conversion keys is *slower* than plain `str/replace` — the DB-layer fix targets pass elimination, not caching (#232).
- **`boundary-cli`**: The generated `AGENTS.md` template now opens the "Adding new functionality" workflow with **Step -1 — check existing Boundary modules FIRST**: run `boundary list modules` and prefer `boundary add <module>` + its ports before writing custom code. Coding agents working in bootstrapped projects were reimplementing functionality that existing modules (auth, storage, jobs, email, cache, search, payments, …) already provide (#232).

Framework Quality (Phase 0–2, 2026-07):

- **`boundary-observability`**: a Prometheus metrics adapter with a `GET /metrics` scrape endpoint; a backend-agnostic tracing port (`ITracer` + the `with-span` macro) with no-op and logging adapters; and an OpenTelemetry **OTLP** exporter for both traces and metrics, plus automatic per-request HTTP spans and per-job worker spans. One vendor-neutral OTLP/HTTP exporter feeds any OTel backend (SigNoz, Grafana Tempo, Jaeger, Datadog-via-OTel) (BOU-174, #304, #305, #306).
- **`boundary-storage`**: a Google Cloud Storage adapter (V4 signed URLs); a `:boundary/storage` Integrant key dispatching `:local` / `:s3` / `:gcs`; and real HMAC-signed, expiring URLs for the local adapter (BOU-206, #308).
- **`boundary-email`**: an in-memory `EmailQueueProtocol` implementation (bounded retry); `:boundary/email` + `:boundary/email-queue` Integrant keys; and user welcome mail routed through the email lib (BOU-206, #309).
- **`boundary-jobs`**: a DB-backed job queue adapter (durable background jobs without Redis) with transactional outbox enqueue (BOU-181, #299, #300).
- **deployment**: a production Dockerfile, a `worker` run mode (no HTTP listener), and a production configuration guard (BOU-173, #298).
- **`boundary-scaffolder`**: module namespace + path parameterization via `--base-ns`, and an app-first `bb scaffold integrate` flow (BOU-205, #302, #303).
- **quality gates**: `check:poms` (published-POM boundary-dep completeness) with a publishable `boundary-shared-ui` (BOU-202, #289); a `check:test-tags` gate with test-pyramid tag backfill across all libs (BOU-166, #262–#266); a `check:test-meta` gate (BOU-184, #255); and a full-system boot test against embedded PostgreSQL (BOU-183, #261).
- **downstream upgrade path** for the Phase-2 refactors, documented for consuming apps (BOU-204, #294).
- **docs**: a runnable in-repo example (`examples/todo`) with CI smoke, 7 missing library READMEs, expanded module `AGENTS.md` guides, and multi-tenancy usage docs relocated to the tenant README (BOU-175, BOU-201, #295, #296, #297, #292).
- **tests**: property tests for the snake↔kebab conversion boundary and RRULE recurrence; contract round-trip tests for user/session persistence and admin auto-CRUD; restored + hardened ring-jetty adapter tests (BOU-167, BOU-172, #268–#271, #249).
- **Phase 0 quick-wins**: first-run experience, POM/hygiene fixes, and repository trust signals (BOU-152…157, #246).

### Changed

- **Performance, tier 1** (#232): framework-wide hot-path fixes, all benchmarked (criterium) before implementation.
  - **Malli validators compiled once**: `m/validate`/`m/explain` with a raw schema re-parse the schema and rebuild the predicate on every call — measured **8.6–9.9× overhead**. All 121 static-schema call sites across 43 files (login, per-request handlers, per-WebSocket-message `:pre` checks) now use `m/validator`/`m/explainer` defs compiled at namespace load. `boundary.core.validation` memoizes validator/explainer/decoder compilation for schema-as-argument callers, and the scaffolder template emits compiled validators in generated modules.
  - **`boundary-platform`**: reflective `(.info logger …)` interop in the HTTP/service interceptors (fired on enter+leave of every request) replaced with `ILogger` protocol calls — measured **~90×** per call; `*warn-on-reflection*` enabled in both namespaces.
- **Performance, tier 2** (#233):
  - **`boundary-platform`**: DB result keys are converted snake→kebab **in the next.jdbc builder-fn** (`as-unqualified-kebab-maps`, column names converted once per result set) instead of a second full per-row map rebuild — ~1.7× on 100-row results; redundant third conversions removed from user `db->user-entity` and six admin service sites.
  - **`boundary-admin`**: entity config and table metadata cached in the long-lived `SchemaRepository` component — previously every admin page issued 2×N `information_schema` queries (N = registered entities). `reset-cache!` provided for post-migration invalidation.
  - **`boundary-platform`**: static-resource middleware no longer does a classloader lookup on every request — gated to GET/HEAD URIs with a file extension; duplicate query/form param parsing removed (global `wrap-params` dropped in favour of reitit's `parameters-middleware`).
  - **`boundary-i18n`**: `resolve-markers` postwalk replaced with a structural-sharing transform that returns original nodes when no descendant changed — **82.4µs → 25.0µs (3.3×)** on a 50-row table page, full render ~1.9×; `translate/t` no longer re-runs `satisfies?` per locale per key.
  - **`boundary-audience`** / **`boundary-user`**: N+1 write patterns batched. `save-memberships!`: exists-SELECT + single-row INSERT per user (50k-user audience = 100k statements) → one SELECT + in-memory diff + chunked 500-row multi-row INSERTs (~102 statements, portable H2/PG). `update-users-batch`: per-user UPDATEs → `next.jdbc/execute-batch!` grouped by column shape. Signatures, return values and transaction semantics unchanged.
- **Performance, tier 3**:
  - **`boundary-platform`**: CSRF fast paths — `http-csrf-protection` short-circuits before any binding/cookie work when disabled (the default), and `wrap-csrf` decides at wrap time, returning the raw handler unwrapped. When enabled, a request already carrying a valid token for the current binding gets it re-exposed instead of a fresh CSPRNG draw + HMAC sign per request (tokens have no expiry/rotation requirement; binding model, cookie attributes, constant-time compare and 403 semantics unchanged — security suite green).
  - **`boundary-platform`**: correlation ids generated from `ThreadLocalRandom` instead of `UUID/randomUUID`'s shared, contended `SecureRandom` (internal trace ids, not security tokens); `http-request-metrics` no longer computes a per-request duration it then discards; interceptor leave/error phases use `rseq` instead of `reverse`; version headers built once at wrap time instead of per response.
  - **`boundary-user`**: JWT signing secret resolved from the environment once per process instead of a `System/getenv` call on every token sign/verify.
  - **`boundary-jobs`**: worker Redis heartbeat throttled to `:heartbeat-interval-ms` (default 5000 ms, key TTL 60 s) instead of one round-trip per loop iteration — under load the loop spins once per job.

Framework Quality (Phase 0–2, 2026-07) — architecture & FC/IS:

- **FC/IS core purity** (BOU-165, #254): definition registries and process guards moved out of `core/` into the shell; `core/` may no longer throw or hold mutable state, enforced by `check:fcis`. Follow-ups: audience filter/composition validation moved to the shell (BOU-185, #256), the validation rule registry split into a pure core + stateful shell (BOU-188, #258), and legitimate core exemptions reclassified inline (BOU-186, #257).
- **dependency-cycle dissolution**: the platform↔user cycle broken via app-layer wiring (BOU-171, #279); platform back-edges to admin/workflow/search removed (BOU-192, #282); shared Hiccup UI extracted into `boundary-shared-ui`, dissolving admin↔user (BOU-193/194, #283); tenant HTTP middleware relocated out of platform and its wiring moved to the app layer (BOU-198/200, #284, #291); parallel search stacks merged into `libs/search` (BOU-169, #276).
- **dead-code / honesty**: 15 previously-undeclared deps declared and module layout exceptions documented (BOU-196, #285); 5 dead cross-cutting protocols dropped from `user/ports` (BOU-170, #277); dead admin `http/support` helpers removed (#293); admin `core/ui` and `shell/http` split into focused namespaces behind a facade (BOU-195, #288).
- **`boundary-observability`**: HTTP request metrics now emit through the real `IMetricsEmitter` (request count, error count, latency histogram) instead of a no-op stub, so they reach any active provider (BOU-208, #307).
- **deploy**: publish order is derived via a topological sort of the inter-library dependency graph (BOU-203, #290).

### Fixed

- **`boundary-platform`**: PostgreSQL session settings (`statement_timeout`, `TimeZone=UTC`, `ApplicationName`) now reach **every** pooled connection via JDBC URL properties — the previous `SET` statements only affected the single pooled connection that happened to execute them, leaving the statement-timeout guard effectively unset on the rest of the pool. `reWriteBatchedInserts=true` enabled while at it (#232).
- **build**: the uberjar silently omitted 13 libraries (email, tenant, realtime, payments, external, workflow, search, reports, calendar, geo, ai, i18n, ui-style, push, audience) because `build.clj` hardcoded 9 source dirs; the list now derives from deps.edn `:paths`, so new libs are packaged automatically. Also enables `-Dclojure.compiler.direct-linking=true` and resolves a LICENSE file-vs-directory merge conflict between dependency jars (#232).
- **`boundary-email`** / **`boundary-external`**: `Attachment` schemas used `:bytes`, which is not a schema in Malli's default registry — `valid-email?`, `valid-email-input?` and `explain-email-errors` threw `:malli.core/invalid-schema` on every call (latent: zero callers; surfaced by compiling validators at namespace load). Fixed to `bytes?` with regression tests (#232).
- **`boundary-user`**: `find-active-users-by-role`, `find-users-created-since` and `find-users-by-email-domain` ran `SELECT … ORDER BY created_at DESC` with **no LIMIT** — unbounded memory/latency as data grows. All three now default to the platform's `max-pagination-limit` (1000) via `build-pagination`; new `{:limit :offset}` options arities added to `IUserRepository` (base arities delegate), `nil` limit/offset guarded at the SQL assembly point.

Framework Quality (Phase 0–2, 2026-07):

- **`boundary-observability`**: Prometheus metric/label names that collide after sanitization (e.g. `:http.requests` and `:http-requests` both → `http_requests`) are handled deterministically — a later colliding metric registration is logged and ignored (first wins), and colliding label keys within a series are de-duplicated — instead of rendering invalid exposition (BOU-207, #310).
- **`boundary-audience`**: the `account-tenure :neq` filter now maps to SQL `<>` (BOU-189, #259).
- **`boundary-platform`**: enum `CHECK` constraints are now idempotent across H2 reconnects (#273).

## [1.0.1-alpha-36] - 2026-07-02

### Fixed

- **`boundary-payments`**: Stripe checkout 400 on an invalid `success_url` (BOU-148, BOU-149). `create-checkout-session` POSTed whatever `stripe-checkout-params` produced. A broken upstream return-URL config (e.g. an unset `PUBLIC_BASE_URL`) reached Stripe two ways: an empty string when the `:redirect-url` fallback was also blank (`400 parameter_invalid_empty`, BOU-148), or a scheme-less relative path like `/web/license/payment/return?…` when the configured URL was left relative (`400 url_invalid`, BOU-149). The adapter now validates the resolved `success_url`/`cancel_url` **before** the Stripe call: anything that is not an absolute `http(s)` URL throws a `:config-error` ex-info naming the offending param, its value, and the fix (provide an absolute return URL / set `PUBLIC_BASE_URL` in acc/prod), so the misconfiguration is actionable instead of surfacing as an opaque provider error.
- **`boundary-payments`**: Stripe webhook 500 on unmapped event types (BOU-147). `process-webhook` threw an `:internal-error` ex-info for any Stripe event whose type is not one of the four generic `payment_intent.*` mappings — including `checkout.session.completed` (the primary paid-flow event), `checkout.session.expired`, and `charge.dispute.created`. In the billing webhook handler that throw is uncaught and returns **HTTP 500**, so a freshly-connected Stripe endpoint 500s on every delivery Stripe fires by default and Stripe retries for days. The consumer's `event-action` was already designed to route these by payload type (or ignore them), but the throw fired first. `process-webhook` now returns a result with `:event-type nil` and the full payload for any unmapped-but-parseable event instead of throwing, so the handler acknowledges with 200 and the billing layer routes/ignores by payload type. Only the four `payment_intent.*` types still resolve to a framework event-type.

## [1.0.1-alpha-35] - 2026-07-01

### Added

- **`boundary-platform`**: Rate limiting wired into the default route pipeline (BOU-87). New config-driven `http-rate-limit-protection` interceptor runs in the default HTTP stack and reads its policy from `:boundary/http :rate-limit {:enabled? :limit :window-ms}` (env `HTTP_RATE_LIMIT`, `HTTP_RATE_LIMIT_WINDOW_MS`); the wiring injects the `:boundary/cache` so an active Redis cache yields a fixed-window limit **shared across replicas**. Enforcement is **opt-in** (default off) so an upgrade cannot start 429-ing existing consumers — enabled only in the bundled dev config (single-node, 1000/min); the prod/acc configs ship it **disabled** (enable together with an active Redis cache), and test leaves it off. **Caveat:** with no active cache the limiter falls back to a per-process counter — correct on a single node only; across N replicas the effective global limit is `limit × N`, and the wiring logs a warning at startup. The existing fixed-arg `http-rate-limit` form remains for explicit per-route use.
- **`boundary-jobs`**: Multi-instance hardening (BOU-88). (1) A dequeued job whose type has no handler on the local worker is now **re-enqueued** for another instance instead of being silently dead-lettered; it is failed terminally with a `NoHandlerError` only after it has gone unhandled for `:max-requeue-age-ms` (default 300000 / 5 min). The re-enqueue is *delayed* (parked in the scheduled set `:requeue-delay-ms` ahead, default 1000) so a handlerless worker cannot reacquire the job on its next immediate poll and spin. Give-up is **age-based, not attempt-based** (tracked via `[:metadata :first-missing-at]`): a wall-clock window is independent of fleet size and load, so wrong-worker misses can't drop a job a slow handler-owning worker hadn't polled yet (`:max-requeues`, default 10000, remains only as a runaway backstop). A worker created with an empty handler registry logs a loud warning at startup. (2) Scheduled-job promotion is now an **atomic claim** so concurrent workers can't both move (and run) the same due job: the Redis adapter uses `ZREM` as the claim (only the worker whose `ZREM` returns 1 enqueues) and the in-memory adapter uses `swap-vals!`. Both `process-scheduled-jobs!` implementations now return the count actually promoted.

- **`boundary-tenant`**: Per-tenant provisioning of the compliance/vbar/import entity tables plus a background-job schema-iteration helper (ZZP-86). Four new tables (`compliance_snapshots`, `compliance_changes`, `vbar_assessments`, `import_batches`) join `tenant-scoped-tables`, so `provision-tenant!` now copies them into each new `tenant_<slug>` schema. New `sync-tenant-schemas!` is the idempotent upgrade/sync path: because `provision-tenant!` only copies tenant-scoped tables when *creating* a schema and returns early for existing ones, previously-provisioned tenants would otherwise miss tables added in a later release. `sync-tenant-schemas!` re-copies every tenant-scoped table into every existing tenant schema via `CREATE TABLE IF NOT EXISTS` (existing tables untouched, only missing ones created — safe to re-run); no-op on non-PostgreSQL. Run it on deploy after extending `tenant-scoped-tables`. New `boundary.tenant.shell.tenant-iteration/for-each-tenant-schema` is the background-job analogue of the HTTP `wrap-tenant-schema` middleware: it runs a 1-arg fn once per provisioned tenant schema with that tenant's `search_path` pinned via `with-tenant-schema`, so jobs touching tenant-scoped tables get the same connection-pinned isolation as the request path (without it a job runs under `search_path = public` and sees only the empty public tables). Per-tenant failures are isolated and counted — one tenant's error never aborts the others — returning `{:processed n :failed n :results [...]}`.

- **`boundary-platform`**: Graceful connection draining on shutdown (BOU-86). The `:boundary/http-server` component configures Jetty's `GracefulHandler` and `setStopTimeout` so that on stop the server stops accepting new connections, rejects new requests with `503`, and lets in-flight requests finish before halting — eliminating cut requests during rolling restarts. New config knob `:boundary/http :drain-timeout-ms` (env `HTTP_DRAIN_TIMEOUT_MS`): default 30000 ms in prod/acc, 5000 in dev, 1000 in test; `0` or `nil` disables draining. Set the window above the load balancer's deregistration delay for zero-downtime rollouts.

### Fixed

- **`boundary-platform`**: The in-memory rate-limit fallback is now heap-bounded and the bundled prod/acc configs ship rate limiting disabled (BOU-87 follow-up). Previously `check-rate-limit-memory` only pruned a client's timestamp vector when that same client returned and never evicted old client keys, so high-cardinality client ids (rotating API keys, many remote addresses) could grow the process-global state map without limit on a long-running node — and prod/acc enabled rate limiting by default while `:boundary/cache` was inactive, silently selecting that fallback in production. The fallback is now bounded by a hard cap (`max-tracked-clients` = 10000): before recording a new client at the cap it sweeps clients with no in-window requests and, if the map is still full (every client in-window), evicts the least-recently-active client — so even a sustained stream of fresh client ids can't grow it past the cap. The read-modify-write is atomic inside one `swap!`. prod/acc default to `:enabled? false` and wire `HTTP_RATE_LIMIT_ENABLED` (via aero `#boolean`) so operators can enable it with an env var once an active Redis cache is in place, rather than editing EDN.

- **`boundary-platform`**: The `http-rate-limit` interceptor now actually blocks over-limit requests (BOU-87). Its rejection set `:response` but not `:halt?`, so `run-pipeline` continued to the downstream ring-handler, which overwrote the `429` with the handler's `200` — the limit was counted but never enforced. The rejection now sets `:halt? true` (matching `http-csrf-protection`), short-circuiting the pipeline.

- **`boundary-payments`**: Stripe Checkout Session creation is now diagnosable and blank-safe (BOU-127, #216). `create-checkout-session` logs the Stripe error reason (type/code/param/message) on any non-2xx response instead of only `status=%d id=%s`, so a 400 is no longer opaque. `stripe-checkout-params` builds `success_url`/`cancel_url` blank-safely — an empty/whitespace override (e.g. an unset `PUBLIC_BASE_URL` upstream) no longer wins over `redirect-url` via `(or "" redirect-url)` and produces an empty `success_url` that Stripe rejects with a 400.

## [1.0.1-alpha-28] - 2026-06-09

### Added

- **`boundary-push`**: Comprehensive developer documentation for the push notification library (BOU-44). `AGENTS.md` now covers all five protocols (`IPushService`, `IFCMProvider`, `IAPNsProvider`, `IDeviceTokenStore`, `IPushAnalyticsStore`), Integrant wiring keys, HTTP routes, job handler arg shapes, HMAC callback flow, DB table overview, and REPL smoke checks. `README.md` corrects the Integrant configuration (actual `ig/init-key` dispatch keys), fixes API function names (`register-device!` / `unregister-device!` / `get-user-devices`), and adds the missing `push_analytics_events` migration to the DDL reference.
- **`boundary-devtools`**: Unified error-code catalogue — `bb guide error BND-201` and `bb guide error BND-601` now return title, cause, and fix instead of "Unknown error code" (BOU-49). `error_catalog.edn` (`libs/devtools/resources/`) is the single source of truth consumed by both the JVM runtime (`boundary.devtools.error-codes`, moved out of `core/` so I/O is permitted) and the Babashka CLI (`bb guide`). BND-0xx tooling codes are retired in favour of the BND-1xx..7xx range scheme: BND-1xx configuration, BND-2xx validation, BND-3xx persistence, BND-4xx auth, BND-5xx interceptor, BND-6xx FC/IS violations, BND-7xx tooling/build (new — circular deps BND-701, admin entity config BND-702, module not wired BND-703, migration version conflict BND-305). `bb guide error` (no code) lists all codes grouped by category in numerical range order.

- **`boundary-platform`**: `discover-migration-dirs` now scans each resolved migration directory after discovery and emits a `WARN` log for any subdirectory that contains `.sql` files (e.g. `migrations/tenant/`). Catches the class of misconfiguration where tenant-scoped migrations are placed inside the public migration root and silently applied to the wrong schema; the warning fires on the first `clojure -M:migrate up` run rather than producing a hard-to-diagnose data error later.
- **`boundary-admin`**: Proportional list-view column widths derived from field `:type` plus a field-name heuristic, replacing the previous even distribution where a boolean column got the same width as a `name` or `description` column (BOU-46). Weights: boolean=1, enum/numeric/uuid/json=2, date/instant=3, text=6; string columns default to 3, with name-like fields (`name`, `title`, `email`, …) widened to 4 and long-form fields (`description`, `address`, `comment`, …) to 6. An optional `:width` key on a field config (a positive integer weight) overrides the computed default. Widths are emitted as proportional `width:N%` on the table `<colgroup>` and resolved deterministically at render time — no runtime AI.
- **`boundary-ai`**: `bb ai admin-entity` now suggests `:width` values in generated admin entity EDN configs (BOU-48, layer 3 of the column-width system). The AI is taught the same type/name weight table used by the runtime heuristic and only emits `:width` for fields whose semantics differ from the default — e.g. `:sku`, `:code`, `:ref`, `:barcode` get `{:width 1}` (narrow identifiers) while `:description` and `:name` are left without `:width` because the heuristic already assigns them correct weights. The result is a static `:width` in the generated EDN with no AI involvement on the hot render path.

### Changed

- **`boundary-platform`**: The default HTTP interceptor stack is no longer skipped for `:no-doc` routes. Interceptor application is now controlled by an explicit per-route `:skip-interceptors?` flag (set only on genuinely-internal endpoints such as health checks); `:no-doc` once again means only "exclude from the Swagger spec". As a result every `/web` route now runs the full stack — request logging, metrics, error reporting, correlation header, CSRF, and security headers — where previously it ran none. The most visible effect is that HTML pages now carry the security headers (CSP, HSTS, `X-Frame-Options`, `X-Content-Type-Options`, …) they were silently missing. The shipped CSP allows `'unsafe-inline'`/`'unsafe-eval'` for HTMX/Alpine and all UI assets are self-hosted, so rendering is unaffected (BOU-43).
- **`boundary-platform`**: Replaced the `ring/ring-anti-forgery` dependency with `buddy/buddy-core`. CSRF tokens are generated and verified directly (HMAC-SHA256, constant-time) rather than via Ring's session-backed anti-forgery middleware, which did not fit the framework's cookie/header session model (BOU-43).

### Fixed

- **`boundary-cache`**: The Redis adapter now serializes values with Nippy instead of JSON, fixing `class java.lang.String cannot be cast to class java.time.temporal.Temporal` for cached `java.time` values (BOU-47). JSON is lossy — `Temporal` values became ISO-8601 strings, keywords became strings, and sets became vectors — and the loss surfaced only against Redis since the in-memory adapter stores values by reference. Nippy round-trips keywords, sets, ratios and `java.time`/Temporal values intact, matching the in-memory adapter.
- **`boundary-cache`**: The Redis adapter treats unreadable entries (values written by the previous JSON format, or otherwise non-Nippy bytes) as a cache miss instead of throwing, so the cache self-heals on rollout. Note: the on-the-wire format changes from JSON to Nippy; flushing the cache namespace on deploy is still recommended to avoid log noise from stale reads (BOU-47).

### Security

- **`boundary-platform`**: Real CSRF protection for session-authenticated, state-changing requests (POST/PUT/DELETE/PATCH), replacing a stub that always passed (BOU-43). Enforcement is **opt-in** — the library default is `:enabled? false`, so upgrading the framework cannot start rejecting requests from consumers that do not yet emit tokens; each app enables it explicitly after emitting tokens (BOU-56). When enabled, a request is validated — `403` on a missing or invalid token — when the path is not exempt and the request is either session-authenticated (`session-token` cookie / `X-Session-Token` header) or a `/web` route. This protects `/web`, `/web/admin`, and any session-authenticated `/api` route; token-auth API clients that send no session cookie are not CSRF-vulnerable and are not checked. Details:
  - Tokens are signed, session-bound double-submit values (`base64url(nonce).base64url(HMAC-SHA256(secret, nonce ‖ binding))`); authenticated requests bind to the session, unauthenticated `/web` flows (login, register, MFA) bind to a `SameSite=Strict` `csrf-session` cookie minted on the page GET.
  - Tokens are emitted with no per-handler wiring. HTMX requests pick up the token either from `(boundary.platform.core.csrf/hx-headers)` merged onto an element (e.g. `<body>`, inherited by all `hx-*` requests) or from the shared page layout's `<meta name="csrf-token">` tag plus a global `htmx:configRequest` listener that attaches `X-CSRF-Token` to every HTMX request. Plain `<form method=post>` forms include a hidden field via `(boundary.platform.core.csrf/hidden-field)`.
  - Configured under `:boundary/http :security :csrf {:enabled? :secret :exempt-paths}`; the library default is opt-in (`:enabled? false`). The bundled app enables it explicitly in dev/prod/acc (prod/acc require `JWT_SECRET` from the environment); the secret otherwise falls back to `JWT_SECRET`. List webhooks/callbacks (which cannot carry a token) under `:exempt-paths` (a trailing `/*` matches by path-segment prefix). Startup **fails loud**: if CSRF is enabled with a blank secret the system wiring throws and the app refuses to boot, rather than starting with the interceptor failing open (running unvalidated). **BREAKING (BOU-56):** this replaces the previous warn-and-continue behavior — an app that set `:enabled? true` but left `JWT_SECRET`/`:secret` unset used to boot (CSRF silently disabled) and will now fail to start. Set the secret in the environment before upgrading.

## [1.0.1-alpha-26] - 2026-05-30

### Added

- **`boundary-audience`**: New audience segmentation library (`libs/audience/`) with declarative, rule-based segment definitions. Features include:
  - `defaudience` macro for code-defined segments with seven built-in filter types (demographics, location, role, account-tenure, last-active, behavior, feature-usage)
  - Hybrid SQL + predicate evaluation pipeline — SQL-eligible filters are pushed to the database, remaining filters run as Clojure predicates over the candidate set
  - AND/OR/NOT segment composition with circular-reference detection
  - Dynamic (DB-persisted) segments via JSON with schema validation that rejects fn-typed values
  - Cached membership results in `audience_memberships` table with configurable per-segment TTL
  - Builder UI served via HTMX with Replicant widget mount points for filter panel and composition builder
  - REST + web endpoints: CRUD, preview with count + sample, evaluate + cache, member listing
  - Custom filter type extensibility via `filter->sql` and `filter->predicate` multimethods
  - Integrant wiring with `IAudienceResolver`, `IAudienceRepository`, and `IAudienceCache` components
- **`boundary-realtime`**: Optional `:on-open` callback for `websocket-handler` — `(fn [connection-id])` invoked after a successful connect, for subscribing connections to topics based on the authenticated user's roles. Exceptions thrown by the callback are logged and swallowed, so they do not abort the connection.
- **`boundary-push`**: New push notification library (`libs/push/`) with multi-platform delivery via FCM (Firebase Cloud Messaging) and APNs (Apple Push Notification service). Features include:
  - `defpush` macro for declarative notification definitions with i18n locale maps, deep links, priority, TTL, collapse keys, and retry configuration
  - Platform-specific provider protocols (`IFCMProvider`, `IAPNsProvider`) behind unified `IPushService` orchestrator
  - Device token management — registration, rotation, soft-deactivation, and stale token cleanup
  - HMAC-secured analytics callback endpoint for client-reported delivery/open tracking
  - Error classification (retryable/permanent/token-invalid/rate-limited) for intelligent retry decisions
  - Async parallel delivery via `sendAsync` + `CompletableFuture` for both FCM and APNs
  - Job-based reliable delivery via hard dependency on `boundary-jobs`
  - REST endpoints: device CRUD (`/api/push/devices`), callback (`/api/push/callback`), stats (`/api/push/stats/:id`)
  - Database migrations for `push_device_tokens`, `push_send_log`, `push_analytics_events` with multi-tenant support
  - Mock providers for dev/test, Integrant wiring for all components
  - 41 tests, 118 assertions covering unit, integration, and contract layers
- **`boundary-user`**: Welcome email on admin user creation — optional `send-welcome` checkbox triggers email via `ISmtpProvider` with graceful failure handling.
- **`boundary-user`**: Dashboard extensibility via `:dashboard-extra-cards` config for injecting custom Hiccup cards into the user dashboard.
- **`boundary-ui-style`**: Cross-page toast notification system via `X-Toast` response header + `sessionStorage`, works across all page layouts (base, pilot, admin-pilot).

### Fixed

- **`boundary-cache`**: Deterministic LRU eviction — replaced timestamp-based ordering with monotonic access counter. Fixes non-deterministic eviction when entries are created within the same millisecond.
- **`boundary-user`**: XSS in `create-user-htmx-handler` inline `<script>` — added `escape-js-string` to sanitize `return-to` URL, toast JSON, and user name before interpolation. Prevents quote-breaking and `</script>` tag injection.
- **`boundary-admin`**: Toast JSON injection via entity labels in delete/bulk-delete handlers — added `escape-json-string` to sanitize label values in `X-Toast` and `HX-Trigger` headers.
- **`boundary-admin`**: Split-table soft-delete now correctly writes `deleted_at` to both primary and secondary tables in a transaction, fixing `column "deleted_at" does not exist` errors.
- **`boundary-admin`**: Added config validation for split-table entities missing `:create-redirect-url`, failing early with a clear error instead of a `StreamableResponseBody` crash.
- **`boundary-admin`**: Added `log/error` to create-entity exception handler (previously swallowed silently).
- **`boundary-admin`**: Added missing `deleted_at` column to `users` test DDL for embedded PostgreSQL integration tests, fixing 12 pre-existing test errors.
- **`boundary-user`**: Restored 500 status code for server errors in `create-user-htmx-handler` (was incorrectly returning 200).
- **`boundary-user`**: Fixed arity mismatch in `create-user-htmx-handler` test calls — handler signature changed to `[user-service email-sender config]` but tests were not updated.
- **`boundary-ui-style`**: Removed duplicate XHR monkey-patch from `admin-ux.js` — `components.js` already handles `X-Toast` capture for all bundles.
- **`boundary-ui-style`**: Increased horizontal padding on table pagination for better alignment.

### Fixed (CI)

- **`ci`**: Replaced `:local/root` dep in `bb.edn` with direct `:paths` entry for `libs/tools/src`, preventing `deps.clj` from triggering a Clojure tools download that times out on CI runners.

## [1.0.1-alpha-23] - 2026-05-18

### Fixed

- **`boundary-admin`**: Auto-introspect secondary table fields when `:split-table-update` is configured, so split-table entities no longer require manual field definitions (#158).
- **`boundary-admin`**: Auto-expand SELECT columns for join queries in split-table setups, ensuring all fields from both tables are fetched (#158).
- **`boundary-admin`**: Auto-hide `tsvector` generated columns from entity forms and list views (#158).
- **`boundary-admin`**: Skip required validation for boolean fields, which default to `false` rather than `NULL` (#158).
- **`boundary-admin`**: Fixed swapped primary/secondary table alias mapping in `resolve-query-config`, which caused wrong SQL column qualifiers for split-table entities (#158).
- **`boundary-admin`**: Fixed snake_case→kebab-case mismatch in SELECT deduplication that caused duplicate columns in split-table join queries (#158).
- **`boundary-admin`**: Fixed split-table SELECT auto-expansion assigning columns to wrong table alias when `:secondary-table` maps to the `:from` table in query-overrides (e.g., `a.tenant_id` instead of `u.tenant_id`). Alias is now resolved by matching `:secondary-table` against `:from`/`:join` table names (#158).

### Added

- **`boundary-admin`**: Embedded PostgreSQL test infrastructure (`io.zonky.test/embedded-postgres`) for `admin-user-operations-test`. Split-table tests with `tenant_id` and other PG-specific columns now run against a real PostgreSQL instance instead of H2, fixing 8 pre-existing test errors.
- **`boundary-admin`**: New test helper namespace `boundary.admin.test.embedded-pg` with `start!`/`stop!`/`db-context`/`with-embedded-pg` for reusable embedded PG lifecycle in tests.

### Fixed (CI)

- **`ci`**: Removed non-existent `:db/h2` alias from all CI test commands. H2 and embedded PostgreSQL deps are already in the `:test` alias; the phantom alias was silently ignored but produced warnings.

### Changed

- Upgraded 10 dependencies to latest versions: ZXing 3.5.4, MySQL Connector/J 9.7.0, nREPL 1.7.0, PostgreSQL 42.7.11, SQLite JDBC 3.53.1.0, Jedis 7.5.0, AWS SDK 2.44.1, spel 0.9.7.
- Aligned `cheshire` version in `boundary-cli` from 5.12.0 to 6.2.0 (matches rest of monorepo).
- Aligned `org.clojure/clojure` in `:build` alias from 1.12.3 to 1.12.4.

## [1.0.1-alpha-22] - 2026-05-05

### Fixed

- **`boundary-admin`**: `schema_repository/get-entity-config` now uses `:table-name` from manual entity config when fetching table metadata, so entities whose key differs from their table name (e.g. `:users` → `auth_users`) resolve correctly (BOU-28).
- **`boundary-admin`**: `bulk-delete-entities` now targets `:soft-delete-table` instead of `:table-name` when soft-deleting, fixing bulk deletes in split-table setups (BOU-28).
- **`boundary-admin`**: `update-entity` and `update-entity-field` now use `execute-update!` for DML statements instead of `execute-one!`, fixing UPDATE execution in both split-table and single-table paths (BOU-28).
- **`boundary-admin`**: Added `:soft-delete true` to the default `users` admin entity config so soft-delete is enabled out of the box (BOU-28).
- **`boundary-tools`**: `bb create-admin` now works in freshly generated projects (BOU-27). The command previously shelled out to `clojure -M:cli:db` which requires `boundary.cli` — a monorepo-only namespace never included in published libraries. Replaced with `clojure -M:user-cli` which calls `boundary.user.shell.cli-entry/run-cli!` directly via `-e` eval, requiring no unpublished code.
- **`boundary-cli`**: Generated `deps.edn` now includes a `:user-cli` alias with all four JDBC drivers (SQLite, PostgreSQL, H2, MySQL) so `bb create-admin` works regardless of which database adapter the project is configured to use.
- **`boundary-cli`**: Generated `config.clj` now defines `user-validation-config`, which `boundary.user.shell.cli-entry` resolves at runtime via `requiring-resolve`.
- **`boundary-tools`**: `bb create-admin` passes the target environment via `BND_ENV` environment variable instead of `-J-Denv=`, matching how `boundary.config/load-config` actually reads the active profile.

## [1.0.1-alpha-21] - 2026-05-04

### Fixed

- **`boundary-user`**: MFA QR code is now generated locally using the ZXing library (`com.google.zxing/core` and `com.google.zxing/javase` 3.5.3) instead of calling the external `api.qrserver.com` service. `generate-qr-code-data-url` returns a `data:image/png;base64,…` URL that works in `<img src>` without any network dependency (#148).
- **`build` (all 25 libraries)**: Each library JAR now embeds a `cljdoc.edn` file containing `{:cljdoc/root "libs/<name>"}`. Without this hint cljdoc defaulted to the repo root and could not find source files located under `libs/{name}/src`, breaking all Clojars cljdoc links (BOU-26, #149).

### Changed

- All 25 libraries bumped to `1.0.1-alpha-21` to re-align lockstep versioning.

## [1.0.1-alpha-20] - 2026-05-01

### Fixed

- **`boundary-cli`**: `boundary new` now generates a full `boundary-tools` task suite in `bb.edn` instead of a minimal 3-task config. The old template used broken `(clojure ["-M:repl-clj"])` syntax that caused `FileNotFoundException: [-M:repl-clj]` on `bb repl`.
- **`boundary-cli`**: Generated `deps.edn` now uses `:repl` alias (consistent with generated-project convention; monorepo uses `:repl-clj`).

### Changed

- All 23 libraries bumped to `1.0.1-alpha-20` to re-align lockstep versioning.

## [1.0.1-alpha-14] - 2026-04-25

### Fixed

- **`boundary-tools`**: `bb scaffold generate` now works in projects created from `boundary-starter` — scaffolder is injected via `-Sdeps` instead of requiring it on the classpath.
- **`boundary-tools`**: `bb smoke-check` no longer fails in generated projects — removed monorepo-only `:docs-lint` alias from required checks.
- **`boundary-tools`**: `bb check` linting no longer includes `libs/*/src libs/*/test` paths when not in the monorepo.
- **`boundary-tools`**: `bb install-hooks` gives a friendly message instead of a Java exception when run outside a git repository.
- **`boundary-tools`**: AI CLI (`bb ai`) falls back to environment variables (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `OLLAMA_URL`) when config has `:provider :no-op` or no AI config is present.
- **`boundary-ai`**: OpenAI-compatible base URLs with a trailing `/v1` suffix no longer produce double `/v1/v1/chat/completions` paths.
- **`boundary-scaffolder`**: Generated `deps.edn` now includes `:clj-kondo` and `:migrate` aliases with all four database drivers (SQLite, PostgreSQL, H2, MySQL).

## [1.0.1-alpha-13] - 2026-04-20

### Added

#### `boundary-devtools` — DX Vision: 6-phase developer experience overhaul
- **Phase 1 — Zero-Friction Onboarding**: Error codes (`BND-*`) with structured messages, ADRs for devtools guidance engine, REPL command center, dev dashboard, error experience, and progressive learning (ADR-024 through ADR-029).
- **Phase 2 — REPL Power**: Introspection tools (`boundary.devtools.core.introspection`), schema exploration (`schema-tools`), documentation lookup (`documentation`), and guidance engine (`guidance`). REPL namespace (`boundary.devtools.shell.repl`) with unified API.
- **Phase 3 — Error Experience**: Error classifier, enricher, and formatter for human-friendly Clojure error messages. Auto-fix suggestions (`boundary.devtools.core.auto_fix`), stacktrace parser, FC/IS checker, HTTP error middleware, and REPL error handler.
- **Phase 4 — Dev Dashboard**: Browser-based dashboard (`localhost:9090`) with pages for system overview, routes, schemas, database, errors, requests, and docs. Hiccup-rendered with custom CSS, served via Ring.
- **Phase 5 — Advanced REPL**: Request/response recording (`boundary.devtools.core.recording`), route testing (`router`), and rapid prototyping (`prototype`). Shell adapters for file-based recording persistence and route simulation.
- **Phase 6 — Dashboard Extensions + AI**: Config editor, security analyzer, jobs dashboard page. AI-powered REPL helpers for code explanation, refactoring suggestions, and documentation generation.

#### `boundary-ai` — REPL AI integration (Phase 6)
- New AI-powered REPL functions: `explain-code`, `suggest-refactor`, `generate-docs` in `boundary.ai.shell.repl`.
- Prompt builders for Phase 6 features in `boundary.ai.core.prompts`.

### Fixed

#### `boundary-cache` — LRU eviction bug (#137)
- **LRU eviction evicting wrong entry when timestamps are identical**: Fixed eviction logic in `boundary.cache.shell.adapters.in_memory` to correctly identify the least-recently-used entry when multiple entries share the same timestamp.

#### `boundary-tools` — BOU-15 deprecated wrapper usage scanner (BOU-15)
- **Detect `:refer`'d deprecated symbols**: `normalize-require-spec` now extracts `:refer [sym ...]` vectors alongside `:as` aliases. A new `extract-referred-symbols` function maps directly referred symbol names to their source namespace. `find-qualified-call-sites` runs a second regex pass for bare `(symbol ...)` call sites, so usage like `(:require [boundary.search.core.index :refer [build-document]])` is no longer silently missed.
- **Detect fully-qualified deprecated calls**: `find-qualified-call-sites` now unconditionally searches for `(namespace/symbol ...)` patterns regardless of whether the file has an alias or `:refer` entry. Calls like `(boundary.search.core.index/build-document ...)` are now correctly reported.

#### `ci` — E2E job disabled
- Disabled the `e2e` CI job with `if: false` to reduce pipeline run time. Tests can be run manually when needed.

### Changed

#### Security — Alpine.js CSP build (#129)
- Migrated to Alpine.js CSP-compatible build to comply with Content Security Policy. Hardened CI pipeline with stricter security checks.

#### Chore — Project data cleanup (#130)
- Moved `boundary-tools` into `libs/tools/` to follow monorepo convention. Removed redundant top-level `boundary-tools/` directory.

## [1.0.1-alpha-12] - 2026-04-06

### Added

#### `boundary-e2e` — Admin UI end-to-end test suite (BOU-10)
- **19 Clojure/spel e2e tests** for the admin Users and Tenants UI — list overviews, detail/edit forms, search with HTMX fragment updates, access control, and soft-delete.
- Shared admin helper namespace (`boundary.e2e.helpers.admin`) with `login-as-admin!`, `login-as-user!`, two-phase HTMX settle waiting (`install-htmx-settle-listener!` / `await-htmx-settle!`), and table/form query utilities.
- Tests build on BOU-9 auth helpers and `with-fresh-seed` fixture for isolated H2 state per test.

### Fixed

#### `platform` — Compile-time PostgreSQL class references
- **Removed compile-time `(:import [org.postgresql.util PGobject])` and `(instance? org.postgresql.util.PSQLException ...)`** from `boundary.user.shell.service`, `boundary.tenant.shell.persistence`, and `boundary.tenant.shell.invite-persistence`. Replaced with runtime class name checks so the REPL starts without the `:db` alias on the classpath.

#### `admin` — Table view UX improvements
- **Single-click row navigation restored**: Clicking any data cell now navigates to the edit form. Previously, editable cells blocked single-click navigation due to an overly broad exclusion in the click handler.
- **Single-click / double-click coexistence**: Added 250ms debounce so single-click navigates to the edit form while double-click triggers inline field editing without conflict.
- **Removed redundant edit action icon**: The per-row edit icon button was removed since single-click navigation covers this. The chevron-right navigation hint is retained on hover.

### Changed

#### `admin` — Compact table view layout
- **Sticky pagination footer**: Pagination bar now sticks to the bottom of the viewport when scrolling long tables, always visible without scrolling down.
- **Compact toolbar**: Replaced the large gradient hero section with a single-row toolbar combining title, record count badge, search, and action buttons — reclaiming ~120px of vertical space.
- **Action buttons right-aligned**: Delete, refresh, and create buttons are now pushed to the right side of the toolbar for visual balance.

#### `admin` — Collapsible sidebar
- **Collapsible sidebar**: Sidebar can now be collapsed to a 64px icon-only mode via toggle button or Ctrl+B keyboard shortcut. State is persisted to localStorage.
- **Hover expand**: Hovering over the collapsed sidebar temporarily expands it; moving away collapses it again. Pin button keeps it expanded.
- **CSP-safe Alpine.js store**: Moved sidebar Alpine.js store initialization from inline script to external `admin-ux.js` to comply with Content Security Policy.
- **Script load order**: `admin-ux.js` now loads before `alpine.min.js` so the sidebar store is registered before Alpine initializes.

### Added

#### `boundary-e2e` — end-to-end test suite for login sequence
- **33 Clojure/spel e2e tests** covering `/web/login`, `/web/register`, and `/api/v1/auth/*` — browser automation + API testing via [spel](https://github.com/Blockether/spel) (Playwright Java wrapper). No Node.js/npm/TypeScript introduced.
- New sub-library `libs/e2e/` with `com.blockether/spel` dependency, isolated behind opt-in `:e2e` alias — normal `clojure -M:test` runs are unaffected.
- `bb e2e`: orchestrator task that starts the app in `:test` profile on port 3100, runs the kaocha `:e2e` suite, and tears down the server.
- `bb run-e2e-server`: standalone task for manual debugging against the test-profile server.
- Test-only `POST /test/reset` endpoint (behind `:test/reset-endpoint-enabled?` config flag) that truncates H2 and re-seeds baseline tenant/users via production services. Guarded by startup assertion (throws in prod/acc) and `bb doctor` check.
- Kaocha `:e2e` suite in `tests.e2e.edn` with `^:e2e` metadata filtering.
- CI: new `e2e` job in `.github/workflows/ci.yml` with Playwright browser cache.

### Fixed

#### `boundary-user` — 5 auth/session bugs discovered by e2e tests
- **MFA handlers**: read `[:session :user :id]` instead of `[:user :id]` from the request — all 4 MFA endpoints (`setup`, `enable`, `disable`, `status`) always returned 500. Fixed by reading `(:user request)` directly.
- **Remember-me checkbox**: `login-submit-handler` checked for `"on"` but the `ui/checkbox` component submitted `"true"` — remember-me never activated. Fixed by accepting any truthy form value.
- **Session validation after login redirect**: `string->instant` in `boundary.core.utils.type-conversion` did not handle `java.time.OffsetDateTime` (returned by H2 for `TIMESTAMP WITH TIME ZONE` columns) — caused NPE in `is-session-valid?`, bouncing users back to login after successful authentication. Fixed by adding `OffsetDateTime` handling.
- **Account lockout not enforced**: `should-allow-login-attempt?` and `calculate-failed-login-consequences` existed in the core layer but were never called from the service layer. Fixed by adding a service-level lockout gate that checks the threshold before delegating to `authenticate-user`. Only true lockout (`:retry-after` present) short-circuits — deactivated/deleted accounts fall through to the normal auth flow to preserve their own error semantics.
- **Session tokens break URL paths**: standard base64 encoding produced `+`, `/`, `=` characters that caused Jetty 400 errors on `GET/DELETE /api/v1/sessions/:token`. Fixed by switching `generate-session-token` to URL-safe base64 (`Base64/getUrlEncoder` without padding). **Breaking change**: existing sessions with old-format tokens will fail validation — users must re-login after deploy.

### Changed

#### `boundary-tools` — 4 new developer helper tools

##### `bb doctor` — Config Doctor (rule-based)
- **Rule-based config validation**: 6 checks against `config.edn` and project files — no AI required.
- `env-refs` (error): detects `#env VAR` references in the `:active` section without `#or` fallback that are not set in the environment.
- `providers` (error): validates `:provider` values against known sets (logging, metrics, error-reporting, payments, AI, cache).
- `jwt-secret` (error): verifies `JWT_SECRET` is set when the user module is active.
- `admin-parity` (warn): checks that admin entity EDN files exist in both `dev/admin/` and `test/admin/`.
- `prod-placeholders` (error): flags placeholder values (`company.com`, `example.com`, `TODO`, `CHANGEME`) in prod/acc configs.
- `wiring-requires` (warn): verifies active Integrant modules have their `module-wiring` require in `wiring.clj`.
- CLI: `bb doctor [--env dev|prod|acc|all] [--ci]`. `--ci` exits non-zero on any error for CI pipelines.
- Structured output with pass/warn/error indicators and actionable fix suggestions.
- New namespace: `boundary.tools.doctor`.

##### `bb setup` — Config Setup Wizard (templates + optional AI)
- **Interactive config setup wizard** with three modes: guided prompts, CLI flags (`--database postgresql --payment stripe`), or AI-powered natural language (`bb setup ai "PostgreSQL with Stripe"`).
- Generates `resources/conf/dev/config.edn`, `resources/conf/test/config.edn`, and `.env.example` from template fragments.
- Component templates for all supported databases (PostgreSQL, SQLite, H2, MySQL), AI providers (Ollama, Anthropic, OpenAI), payment providers (Mock, Stripe, Mollie), cache (Redis, in-memory), and email (SMTP).
- Test config always uses H2 in-memory + mock/no-op providers for fast isolated tests.
- AI mode delegates to `boundary.ai.shell.cli-entry setup-parse`; falls back to interactive if no AI provider is available.
- New namespace: `boundary.tools.setup`.

##### `bb scaffold integrate` — Module Integration (rule-based)
- **Automated module wiring** after `bb scaffold generate`: patches `deps.edn` (source/test paths), `tests.edn` (per-library suite), and `wiring.clj` (module-wiring require).
- `--dry-run` mode previews all changes without writing files.
- Detects already-integrated modules (idempotent).
- Generates Integrant config snippet for manual insertion (config.edn uses Aero reader tags that can't be safely modified programmatically).
- Accessible via both `bb scaffold integrate <module>` and `bb scaffold:integrate <module>`.
- New namespace: `boundary.tools.integrate`.

##### `bb ai admin-entity` — Admin Entity Generator (AI-powered)
- **AI-powered admin entity EDN generation** from natural language descriptions (`bb ai admin-entity "products with name, price, status"`).
- Discovers existing admin entities from `resources/conf/dev/admin/` and includes them as examples in the prompt for style consistency.
- Preview + confirm flow; `--yes` flag for non-interactive use.
- Writes to both `resources/conf/dev/admin/<entity>.edn` and `resources/conf/test/admin/<entity>.edn`.
- Prints post-generation instructions (allowlist registration, `#include` directive).
- Babashka wrapper: `boundary.tools.admin_entity`. Clojure-side additions:
  - `boundary.ai.core.prompts`: `admin-entity-messages`, `setup-parse-messages` prompt builders.
  - `boundary.ai.shell.service`: `generate-admin-entity`, `parse-setup-description` orchestration functions.
  - `boundary.ai.shell.cli-entry`: `cmd-admin-entity`, `cmd-setup-parse` subcommands.

##### CI / developer experience
- `bb.edn`: 3 new tasks registered (`doctor`, `setup`, `scaffold:integrate`) + 3 new requires (`boundary.tools.doctor`, `boundary.tools.setup`, `boundary.tools.integrate`).
- `bb ai` help text and dispatch updated with `admin-entity` and `setup-parse` subcommands.
- `bb scaffold` help text and dispatch updated with `integrate` subcommand.
- `AGENTS.md` (root): new tools added to Quick Reference and namespace table.
- `CLAUDE.md`: new commands added to Scripting section.
- `boundary-tools/AGENTS.md`: comprehensive documentation for all 4 tools with examples, tables, and workflow guides.
- `libs/ai/AGENTS.md`: features list updated to 7, service API examples added, 3 new pitfalls documented (#9–#11).
#### `boundary-realtime` — Ring WebSocket handler
- **Ring 1.15 WebSocket upgrade handler** (`boundary.realtime.shell.handlers.ring-websocket`): bridges Ring's map-based `::ring.websocket/listener` response to the existing `IRealtimeService` connect/disconnect lifecycle. JWT authentication via `token` query parameter; on-open creates adapter and registers connection, on-close/on-error triggers disconnect cleanup.
- `websocket-handler` accepts keyword options: `:token-param` (default `"token"`) and `:on-message` for optional client→server bidirectional messaging.
- `ring/ring-core 1.15.3` added to `libs/realtime/deps.edn`.
- 6 integration tests covering: missing token → 400, listener response structure, custom token param, on-open registration, on-close cleanup, on-error cleanup.

#### `boundary-payments` — new library
- **Multi-provider payment abstraction**: `IPaymentProvider` protocol in `boundary.payments.ports` with `create-checkout-session`, `get-payment-status`, `process-webhook`, and `verify-webhook-signature` methods. Implementations: `StripePaymentProvider`, `MolliePaymentProvider`, `MockPaymentProvider` (development/tests).
- **Malli schemas**: `CheckoutRequest`, `CheckoutResult`, `PaymentStatusResult` (`:pending`/`:paid`/`:failed`/`:cancelled`), `WebhookResult` (`:payment.paid`/`:payment.failed`/`:payment.cancelled`/`:payment.authorized`).
- **Pure core layer** (`boundary.payments.core.provider`): `cents->euro`, `normalize-event-type`, `mollie-status->event-type`, `mollie-status->payment-status`, `stripe-event->event-type`.
- **Stripe adapter**: Checkout Session creation with `payment_intent_data[metadata][checkout_id]` for webhook correlation, HMAC-SHA256 signature verification with constant-time comparison, 300s timestamp tolerance, graceful handling of malformed `Stripe-Signature` headers.
- **Mollie adapter**: Payment creation via Mollie API v2, status polling via `get-payment-status`, form-POST webhook processing with payment fetch-back verification.
- **Integrant component**: `:boundary/payment-provider` with `:provider` (`:mock`/`:mollie`/`:stripe`), `:api-key`, `:webhook-secret`, `:webhook-base-url`.
- **Application wiring**: `payments-module-config` in `boundary.config`, `boundary.payments.shell.module-wiring` loaded via platform wiring, `boundary/payments` dependency added to `libs/platform/deps.edn`.
- 19 tests, 111 assertions, 0 failures (`^:unit` + `^:integration`).
- `libs/payments/deps.edn`: standalone library with `clj-http`, `cheshire`, `malli`, `integrant`, `tools.logging`.

#### `boundary-ai` — new library (Phase 19 of Boundary Roadmap)
- **Multi-provider AI abstraction**: `IAIProvider` protocol in `boundary.ai.ports` with `complete`, `complete-json`, and `provider-name` methods. Implementations: `OllamaProvider` (offline-first, no API key), `AnthropicProvider`, `OpenAIProvider`, `NoOpProvider` (test stub).
- **Automatic provider fallback**: configure a `:fallback` provider in `:boundary/ai-service`; if the primary fails, the fallback is used transparently.
- **Feature 1 — NL Scaffolding** (`bb scaffold ai "<description>" [--yes]`): parses a natural language module description into a validated `ModuleGenerationRequest` spec and delegates to the existing scaffolder pipeline. Preview + confirm by default; use `--yes` for non-interactive generation.
- **Feature 2 — Error Explainer** (`bb ai explain`, `(ai/explain *e)`): reads a Clojure/Boundary stack trace, extracts referenced source files, and returns a structured root-cause + fix-suggestion using framework-specific system prompts.
- **Feature 3 — Test Generator** (`bb ai gen-tests <file>`): reads a source file, detects test type (`:unit` for `core/`, `:contract` for `adapters/`, `:integration` otherwise), and generates a complete Kaocha-compatible test namespace.
- **Feature 4 — SQL Copilot** (`bb ai sql "<description>"`, `(ai/sql "...")`): translates a natural language query description into HoneySQL map + explanation + raw SQL preview. Auto-discovers schema context from `schema.clj` files.
- **Feature 5 — Documentation Wizard** (`bb ai docs --module <path> --type agents|openapi|readme`): generates AGENTS.md developer guides, OpenAPI 3.x YAML, or README.md from source files.
- **REPL helpers** (`boundary.ai.shell.repl`): `(ai/explain *e)`, `(ai/sql "...")`, `(ai/gen-tests "path/to/file.clj")` — bind service once with `(ai/set-service! system-service)`.
- **Integrant component**: `:boundary/ai-service` with `:provider`, `:model`, `:base-url`/`:api-key`, and optional `:fallback` sub-config.
- **Malli schemas**: `Message`, `AIRequest`, `AIResponse`, `ProviderConfig`, `AIConfig`.
- **Pure core layer** (`boundary.ai.core.*`): `prompts.clj` (system + user prompt builders for all 5 features), `context.clj` (module name extraction, stack trace parsing, function signature discovery, schema context), `parsing.clj` (JSON response parser, module spec → CLI args converter, SQL + test code extractors).
- 26 tests, 88 assertions, 0 failures (`^:unit` + `^:integration`).
- `libs/ai/AGENTS.md`: 7-section developer guide covering provider setup, REPL usage, CLI reference, common pitfalls (8 patterns), testing commands.
- `libs/ai/deps.edn`: standalone library with `clj-http`, `cheshire`, `malli`, `integrant`, `tools.logging`.

#### CI / developer experience
- `.github/workflows/ci.yml`: `test-ai` job added (`needs: lint`); `libs/ai/src` added to the lint step; `test-ai` wired into `test-summary`.
- `.github/workflows/publish.yml`: `boundary-ai` added to Layer 4 (standalone, no inter-library dependencies); updated release body and step summary.
- `scripts/ai.clj`: new Babashka script — `bb ai explain`, `bb ai gen-tests`, `bb ai sql`, `bb ai docs`.
- `scripts/scaffold.clj`: `bb scaffold ai "<description>"` subcommand added.
- `bb.edn`: `ai` task added.
- `AGENTS.md` and `CLAUDE.md`: `ai` added to library listing, test command reference, Babashka commands, and Library-Specific Guides table. Version bumped to 3.5.0.
- `resources/conf/dev/config.edn`: `:boundary/ai-service` added (Ollama primary, Anthropic fallback).
- `resources/conf/test/config.edn`: `:boundary/ai-service {:provider :no-op}` for test isolation.

#### `boundary-calendar` — new library (Phase 2 / Q3 2026 roadmap)
- **`defevent` macro** and in-process registry (atom-backed, same pattern as `defreport` in `boundary-reports`): register named event type schemas at load time; `get-event-type`, `list-event-types`, `clear-registry!`.
- **`boundary.calendar.schema`**: Malli schemas — `EventData`, `EventDef`, `OccurrenceResult`, `ConflictResult`; helpers `valid-event?`, `explain-event`, `valid-event-def?`.
- **`boundary.calendar.core.event`**: pure helpers — `duration`, `all-day?`, `within-range?`.
- **`boundary.calendar.core.recurrence`**: DST-aware RRULE expansion via ical4j 4.x `Recur` with `ZonedDateTime` seeds; `recurring?`, `occurrences`, `next-occurrence`, `expand-event`.
- **`boundary.calendar.core.conflict`**: pairwise conflict detection — `overlaps?`, `conflicts?`, `find-conflicts` (returns `ConflictResult` maps with `:overlap-start`/`:overlap-end`).
- **`boundary.calendar.core.ui`**: pure Hiccup calendar views — `event-badge`, `day-cell`, `month-view`, `week-view`, `mini-calendar`.
- **`boundary.calendar.ports`**: `CalendarAdapterProtocol` (`export-ical`, `import-ical`).
- **`boundary.calendar.shell.adapters.ical`**: `ICalAdapter` backed by `org.mnode.ical4j/ical4j 4.0.3`; TZID extracted via regex from property text (ical4j 4.x creates synthetic zone IDs internally).
- **`boundary.calendar.shell.service`**: public API — `export-ical`, `import-ical`, `ical-feed-response` (returns Ring response with `Content-Type: text/calendar; charset=utf-8`).
- 30 tests, 87 assertions, 0 failures (`^:unit` + `^:integration` round-trip).
- `libs/calendar/AGENTS.md`: 11-section developer guide covering DST pitfalls, RRULE examples, ical4j 4.x API notes, registry pollution warning, REPL smoke check.
- `docs-site/content/guides/calendar.adoc` (weight 68): user-facing how-to guide.
- `docs-site/content/api/calendar.adoc` (weight 50): complete function API reference.
- `dev-docs/adr/ADR-011-calendar-library.adoc`: architecture decision record (7 decisions, alternatives considered).

#### `boundary-reports` — added to CI (was missing)
- `test-reports` job added to `.github/workflows/ci.yml`; `libs/reports/src` added to the lint step.

#### CI / developer experience
- `.github/workflows/ci.yml`: `test-calendar` and `test-reports` jobs added (both `needs: lint`; standalone, no inter-library dependencies). Both wired into `test-summary`.
- `AGENTS.md` and `CLAUDE.md`: `reports` and `calendar` added to library listing, test command reference, and Library-Specific Guides table. New **"Adding a New Library to CI"** checklist section in `AGENTS.md`.

#### `boundary-workflow` — new library (Phase 2 / Q3 2026 roadmap)
- **`defworkflow` macro** and in-process registry: declare state machine definitions as data; `get-workflow`, `list-workflows`, `clear-registry!`.
- **`boundary.workflow.schema`**: Malli schemas — `WorkflowDefinition`, `WorkflowInstance`, `TransitionDef`, `AuditEntry`; state/transition validation at definition time.
- **`boundary.workflow.core.machine`**: pure state machine logic — `can-transition?`, `find-transition`, permission checks against `:required-permissions`, guard evaluation.
- **`boundary.workflow.core.transitions`**: `available-transitions-with-status` — returns all candidate transitions with `:enabled?`, `:label`, `:reason` for a given state and actor-roles.
- **`boundary.workflow.core.audit`**: pure audit entry constructors.
- **`boundary.workflow.ports`**: `IWorkflowStore`, `IWorkflowEngine`, `IWorkflowRegistry` protocols.
- **`boundary.workflow.shell.persistence`**: DB persistence via next.jdbc + HoneySQL (`IWorkflowStore` implementation).
- **`boundary.workflow.shell.service`**: orchestration — load → validate → persist → side-effects; `create-workflow-service` factory accepts optional `job-queue` and `guard-registry`.
- **Guard registry**: functions registered at service creation time; receive transition `:context` map; return boolean.
- **Side effects**: job-type keywords on `TransitionDef` (`:side-effects [:notify-user]`); enqueued via `boundary-jobs` after successful transition; silently skipped if no job queue configured.
- **`boundary.workflow.shell.http`**: REST API — `POST /workflow/instances` (start), `POST /workflow/instances/:id/transition`, `GET /workflow/instances/:id` (state + `availableTransitions`), `GET /workflow/instances/:id/audit`.
- **`boundary.workflow.shell.module-wiring`**: Integrant `:boundary/workflow` key; depends on `:boundary/database-context` (required) and `:boundary/job-queue` (optional).
- `libs/workflow/AGENTS.md`: developer guide covering `defworkflow` syntax, guards, side effects, auto-transitions, hooks, and Integrant wiring.
- `docs-site/content/guides/workflow.adoc`: user-facing how-to guide.

#### `boundary-workflow` — lifecycle hooks, auto-transitions, available-transitions
- `:hooks` map on `WorkflowDefinition`: supports `:on-enter-<state>`, `:on-exit-<state>`, and `:on-any-transition` keys. Hooks receive the updated `WorkflowInstance` and fire synchronously after each successful transition (after the audit entry is saved). Exceptions are caught and logged; they do not roll back the transition.
- `:auto? true` on `TransitionDef`: marks a transition as system-initiated. `process-auto-transitions!` port method fires all eligible auto-transitions for a given workflow; uses `[:system]` actor-roles (no user permission check). Returns `{:attempted :processed :failed}` counts.
- `available-transitions` port method: returns candidate transitions with `:enabled?`, `:label`, and `:reason` fields for the current state and actor-roles. Exposed on the `GET /api/workflow/instances/:id` HTTP response as `availableTransitions`.
- `:label` on `TransitionDef` and `:state-config` map on `WorkflowDefinition` for human-readable display names.
- `available-transitions-with-status` pure function in `boundary.workflow.core.transitions`.

#### `boundary-search` — filter support
- `:filters` key on `SearchDefinition`: declares filterable keyword dimensions (e.g. `[:tenant-id :category-id]`).
- `:filter-values` opt in `index-document!` and `build-document`: stores filter data as compact JSON in a new `filters TEXT` column.
- Filter SQL at search time: PostgreSQL uses `d.filters::jsonb->>'key' = ?`; H2/SQLite uses `INSTR(filters, '"key":"val"') > 0` (H2 2.4.x has no JDBC JSON function support).
- `filter-key->json-key` utility in `boundary.search.core.index` (kebab → snake conversion for JSON storage).
- Migration: `resources/migrations/20260312000000-search-filters.{up,down}.sql`.

#### `boundary-admin` — Admin UI Frontend Redesign ("Refined Editorial")

##### Typography & Self-Hosted Fonts
- **DM Sans** (display/body) + **JetBrains Mono** (code/monospace) replace generic system fonts.
- Self-hosted woff2 files under `/fonts/` for CSP compliance (`font-src 'self'`); no external CDN dependency.
- New `fonts.css` with variable-weight `@font-face` declarations (DM Sans 300–700, JetBrains Mono 400–600).
- Font stack tokens updated in `boundary-tokens.css`: `--font-sans`, `--font-display`, `--font-mono`.

##### Design Token Refinements
- **Shadows**: Multi-layered, softer box-shadows (`--shadow-sm` through `--shadow-2xl`) for modern depth.
- **Border radii**: Slightly rounder (`--radius-sm` 6px, `--radius-md` 8px, `--radius-lg` 12px, `--radius-xl` 16px).
- **Transitions**: Spring-like easing curves (`--transition-fast`, `--transition-normal`, `--transition-slow`, `--transition-bounce`).
- **Surface colors**: Warmer light-mode palette (stone tones instead of blue-tinted slate).
- New tokens: `--shadow-card-hover`, `--shadow-inner-glow`, `--tracking-tight`, `--tracking-tighter`, `--topbar-backdrop`.

##### Admin Shell & Sidebar Polish
- Sidebar: subtle gradient background, smooth left-border accent indicator on active/hover nav items.
- Topbar: frosted glass effect with `backdrop-filter: blur(12px) saturate(180%)`.
- Dark mode variants for both sidebar and topbar.

##### Dashboard Redesign
- Hero section with gradient background, decorative radial glow, tighter heading typography.
- Entity cards with hover elevation (`translateY(-2px)`), icon color inversion on hover.
- New `.entity-card-link`, `.entity-card-icon`, `.entity-card-title`, `.entity-card-count`, `.entity-card-description` classes.

##### Entity List Page Polish
- Table wrapper with border-radius, subtle shadow, and overflow hidden.
- Header row: uppercase, letter-spaced, muted color for clean data-grid appearance.
- Row hover with primary-faint background; subtle zebra striping; last-row border removal.
- Search toolbar: unified container with focus ring animation.
- Pagination: pill-style buttons with hover highlight.

##### Form Styling Enhancements
- Form cards with clean borders and radius.
- Input fields: smooth focus transition with 3px primary-faint ring.
- Error state with red border and error-bg ring.
- Labels, help text, and required indicators refined.
- Primary buttons with subtle shadow and hover lift (`translateY(-1px)`).

##### Micro-Interactions & Animation
- `fadeInUp` keyframe for page content entry with staggered delays.
- `tableRowReveal` keyframe for HTMX-loaded table rows (staggered first 10 rows).
- Sidebar nav: background slide animation via `::after` pseudo-element with `scaleX` transform.
- Entity card grid: staggered reveal (50ms intervals, up to 8 cards).
- `@media (prefers-reduced-motion: reduce)` disables all animations.

##### Dark Mode Refinement
- Warmer dark surfaces with blue undertone (`#0c0f17` base).
- Softer borders using `rgba(255,255,255,0.08)`.
- Colored shadow glow on card hover in dark mode.
- Table row hover uses primary color at 6% opacity.

##### Badge & Status Component Polish
- Dot indicator (6px circle) before status text for success/error states.
- Pill-shaped badges with `border-radius: var(--radius-full)`.

#### `boundary-admin` — UX Enhancements (6 features)

##### 1. HTMX Loading Progress Bar
- Fixed top-of-page progress bar appears during all HTMX requests.
- Animated gradient stripe with smooth show/hide transitions.
- Wired to `htmx:beforeRequest` / `htmx:afterRequest` / `htmx:responseError` events.

##### 2. Toast Notification System
- Slide-in toast notifications with 4 variants: success, error, warning, info.
- Each variant has a distinct SVG icon and color scheme.
- Auto-dismiss after configurable duration (default 4s) with progress bar indicator.
- Triggered via HTMX `HX-Trigger: {"showToast": {...}}` response header or `window.AdminUX.showToast()` JS API.
- Server-rendered flash messages (`.alert-success`, `.alert-error`, etc.) auto-converted to toasts on page load.
- `escapeHtml()` prevents XSS in toast title/message content.

##### 3. Clickable Table Rows
- Entity list table rows navigate to detail page on click (`data-href` attribute).
- Smart exclusion: clicks on `a`, `button`, `input`, `select`, `textarea`, `.actions-cell`, `.checkbox-cell`, and `td.editable` are ignored (preserves inline editing).
- Visual hover feedback with chevron navigation hint.

##### 4. Skeleton Loading Screens
- Table bodies replaced with shimmer-animated skeleton rows during HTMX fetch.
- Column count auto-detected from `<thead>`.
- Original table content saved and **restored on HTMX error** (no permanent skeleton state).
- 6 width variants (`w-full`, `w-3-4`, `w-1-2`, `w-1-3`, `w-1-4`) for visual variety.

##### 5. Styled Delete Confirmation Modal
- Custom modal replaces native `window.confirm()` for all delete operations.
- Intercepts `htmx:confirm` event on elements with `hx-delete` or `.danger` class.
- Danger icon, title, message, Cancel/Delete buttons with focus trap.
- Close on Escape key, backdrop click, or Cancel button.
- Translated labels read from `data-confirm-title`, `data-confirm-cancel`, `data-confirm-label` attributes (server-rendered via `[:t ...]` i18n markers); falls back to English.

##### 6. Accessibility — Reduced Motion Support
- `removeAfterAnimation()` helper checks `prefers-reduced-motion: reduce` and removes DOM elements immediately instead of waiting for `animationend` (which never fires when `animation: none`).
- Applied to toast dismissal and modal close.
- All CSS animations gated behind `@media (prefers-reduced-motion: reduce)`.

#### `boundary-admin` — Delete Flow Improvements
- Delete handler now uses `HX-Redirect` instead of empty response with `HX-Trigger`.
- `return_to` query parameter preserved through the delete flow for context-aware redirect.
- **Open redirect prevention**: `return_to` validated to start with `/web/admin/`; invalid values fall back to entity list.

#### `boundary-admin` — Pagination Enhancements
- `page-window` algorithm improved: single-page gaps show the actual page number instead of an ellipsis (e.g. `1 2 3 ... 8` instead of `1 ... 3 ... 8` when page 2 is the only gap).
- All pagination labels internationalized with `[:t ...]` markers (8 new i18n keys).

#### `boundary-i18n` — New Translation Keys
- 12 new keys added to both `en.edn` and `nl.edn`:
  - Pagination: `:admin/pagination-showing`, `:admin/pagination-of`, `:admin/pagination-label`, `:admin/pagination-first-page`, `:admin/pagination-previous-page`, `:admin/pagination-next-page`, `:admin/pagination-last-page`, `:admin/pagination-page`.
  - Modal buttons: `:admin/modal-button-cancel`, `:admin/modal-button-delete`.

#### `boundary-admin` — tenant entity + dashboard stats
- **Tenant admin entity config**: `resources/conf/{dev,test}/admin/tenants.edn` — list/search fields, status enum filter (active/suspended/deleted), field groups (Identity, State, Settings), readonly system fields.
- **Dashboard entity counts**: `admin-home-handler` now calls `count-entities` for each registered entity and passes the stats map to `admin-home`, so entity tiles show real counts instead of always displaying "0".
- Tenants registered in dev and test config allowlists (`#{:users :tenants}`).

#### `boundary-tenant` — convenience functions and protocol extension
- `tenant-provisioned?` public function in `boundary.tenant.shell.provisioning`: checks if a tenant's schema exists in PostgreSQL; returns `false` for non-PostgreSQL databases; throws on missing `:schema-name`.
- `list-tenant-schemas` public function in `boundary.tenant.shell.provisioning`: lists all `tenant_*` schemas in PostgreSQL; returns empty vector for non-PostgreSQL databases.
- `ITenantSchemaProvider` protocol extended with `tenant-provisioned?` and `list-tenant-schemas` methods; `TenantSchemaProvider` record updated to implement both.
- `dev-docs/adr/ADR-020-tenant-database-scope.adoc`: decision to keep tenant provisioning PostgreSQL-only; MySQL/SQLite version promises removed from README.

### Changed

- `boundary-admin` UI theme evolved from "Cyberpunk Professionalism" (Geist + Indigo/Lime) to **"Refined Editorial"** (DM Sans + JetBrains Mono, warmer surfaces, layered shadows, spring-eased transitions). Dark mode refined with blue-tinted surfaces and colored shadow glows.
- `boundary-admin` entity card markup restructured: icon standalone on its own line, then title, description, and count as metadata.
- `boundary-admin` delete handler: returns `HX-Redirect` header instead of empty body with `HX-Trigger: entityDeleted`.
- `boundary-admin` event listeners: all 7 `document.body.addEventListener` calls changed to `document.addEventListener` to survive HTMX body swaps (`hx-target="body" hx-swap="outerHTML"`).
- `boundary-ui-style` CSS bundle: `fonts.css` added as first entry in `admin-pilot-css`; `admin-ux.js` added to `admin-pilot-js`.
- `boundary-ui-style` keyboard.js: confirm modal escape handling added; debug mode disabled.
- `boundary-tenant` promoted from "Active" to "Stable" in `PROJECT_STATUS.adoc`. All convenience functions documented in README are now implemented; 70 tests, 474 assertions, 0 failures.
- `boundary-tenant` README: fixed middleware naming (`wrap-tenant-resolver` → `wrap-tenant-resolution`), removed non-existent `wrap-require-tenant` (use `:require-tenant? true` option instead), clarified middleware locations (platform lib vs tenant lib), replaced MySQL/SQLite roadmap promises with ADR-020 reference.
- `boundary-tenant` integration tests: removed stale "DEFERRED" comment — tests pass with mock observability services and H2 in-memory DB.
- `boundary-external` promoted from "In Development" to "Active" (Twilio, SMTP/IMAP adapters production-capable). Stripe moved to `boundary-payments`.
- Root `AGENTS.md` updated: `workflow` and `search` added to library structure, test commands, and Library-Specific Guides table. Version bumped to 3.3.0.
- `libs/workflow/AGENTS.md` and `libs/search/AGENTS.md` updated to document all new features.
- `docs-site/content/guides/workflow.adoc` and `docs-site/content/guides/search.adoc` updated with new API examples, filter DDL, migration notes, and hook/auto-transition reference.

### New Files

- `libs/ui-style/resources/public/js/admin-ux.js` — Central JS for all 5 UX features (~340 lines).
- `libs/ui-style/resources/public/css/fonts.css` — Self-hosted `@font-face` declarations.
- `libs/ui-style/resources/public/fonts/dm-sans-latin.woff2` (63 KB).
- `libs/ui-style/resources/public/fonts/dm-sans-italic-latin.woff2` (76 KB).
- `libs/ui-style/resources/public/fonts/jetbrains-mono-latin.woff2` (31 KB).

### Tests

- 3044 tests, 15742 assertions, 0 failures across all libraries (`clojure -M:test:db/h2`).
- 113 admin tests, 823 assertions, 0 failures (`clojure -M:test:db/h2 :admin`).
- New test namespaces: `workflow.core.transitions-test` (available-transitions-with-status), `workflow.shell.service-test` (hooks, auto-transitions), `search.core.query-test` (filter SQL), `search.shell.persistence-test` (filter round-trip).

---

## [1.0.0-alpha] - 2026-02-14

### 🎉 Initial Release

The first production-ready release of the Boundary Framework - a batteries-included web framework for Clojure that brings Django's productivity and Rails' conventions with functional programming rigor.

### Architecture

#### Functional Core / Imperative Shell (FC/IS)
- **Pure business logic** in `core/` namespaces (no side effects)
- **I/O and side effects** in `shell/` namespaces
- **Protocol definitions** in `ports.clj` for dependency injection
- **Consistent module structure** across all libraries

#### Library Organization (Monorepo)
- **13 independently publishable libraries** via Clojars
- **Modular design** - use only what you need
- **Zero lock-in** - each library is a standard deps.edn dependency

### Core Libraries

#### `boundary-core` (0.1.0)
Foundation library with essential utilities:
- **Validation**: Malli-based schema validation with human-readable error messages
- **Interceptors**: Declarative cross-cutting concerns
- **Utilities**: Case conversion (kebab-case ↔ snake_case), type conversion, PII redaction
- **Feature flags**: Runtime feature toggles

#### `boundary-observability` (0.1.0)
Multi-provider observability infrastructure:
- **Logging**: Structured logging with Datadog and stdout adapters
- **Metrics**: Counter, gauge, histogram, summary (Datadog StatsD protocol)
- **Error reporting**: Sentry integration with PII redaction
- **Audit logging**: Security and compliance event tracking
- **Interceptor pattern**: Automatic breadcrumbs, logging, metrics (50% code reduction)

#### `boundary-platform` (0.1.0)
HTTP and database infrastructure:
- **HTTP server**: Jetty-based with Integrant lifecycle
- **Routing**: Reitit with normalized route format
- **Database**: HikariCP connection pooling, next.jdbc integration
- **Migrations**: Flyway-based schema migrations
- **CLI**: Command-line interface utilities
- **HTTP interceptors**: Auth, rate limiting, audit (declarative)

#### `boundary-user` (0.1.0)
Authentication and authorization:
- **JWT authentication**: Secure token-based auth
- **Password security**: bcrypt hashing with configurable rounds
- **Multi-Factor Authentication (MFA)**: TOTP-based 2FA (production-ready)
- **Role-based access control (RBAC)**: Fine-grained permissions
- **User management**: CRUD operations with soft delete
- **Account security**: Login attempt tracking, account lockout (5 failures = 15min lockout)

#### `boundary-admin` (0.1.0)
Auto-generated CRUD admin interface (Django Admin for Clojure):
- **Schema introspection**: Auto-detect entity config from database schema
- **Zero-config CRUD**: Create, read, update, delete with no boilerplate
- **Search and filtering**: Full-text search across configurable fields
- **Pagination**: Offset-based with page size control
- **Sorting**: Multi-column sorting (client-side)
- **Field widgets**: Auto-inferred form inputs (text, email, checkbox, select, textarea, date, datetime)
- **Field grouping**: Organize forms with collapsible sections
- **Soft delete support**: Respect `deleted_at` columns
- **Permissions**: Role-based access (admin-only by default, Week 2+ entity-level permissions)
- **HTMX-powered**: Server-side rendering with progressive enhancement
- **Cyberpunk Professionalism UI**: Indigo (#4f46e5) + Lime (#65a30d), Geist fonts, dark mode

#### `boundary-storage` (0.1.0)
File storage abstraction:
- **Local storage**: File system-based storage
- **S3 storage**: Amazon S3 integration (not included in 1.0.0)
- **Validation**: File size, content type, extension validation
- **Security**: Filename sanitization, path traversal prevention
- **Signed URLs**: Temporary access links

#### `boundary-scaffolder` (0.1.0)
Production-ready module generator:
- **Complete module generation**: 9 source files (core, shell, ports, schema, wiring)
- **Test generation**: 3 test files (unit, integration, contract)
- **Migration generation**: 1 Flyway migration file
- **FC/IS architecture**: Zero linting errors, follows all conventions
- **Entity support**: Multi-field entities with types (string, integer, decimal, boolean, text, date, datetime, uuid, json)
- **Field constraints**: Required, unique, indexed

#### `boundary-cache` (0.1.0)
Distributed caching:
- **Redis adapter**: Production-ready caching
- **In-memory adapter**: Development and testing
- **TTL support**: Automatic expiration
- **Tenant scoping**: Multi-tenant cache isolation
- **Atomic operations**: Thread-safe cache access

#### `boundary-jobs` (0.1.0)
Background job processing:
- **In-memory queue**: Development and testing (Redis adapter planned)
- **Job lifecycle**: Enqueue, dequeue, retry, dead letter queue
- **Tenant context**: Multi-tenant job isolation
- **Priority queues**: High, normal, low priority
- **Scheduled jobs**: Future execution with `run-at` timestamp
- **Worker pool**: Parallel job processing with configurable concurrency
- **Retry logic**: Exponential backoff (1s, 2s, 4s, 8s, 16s)

#### `boundary-realtime` (0.1.0)
WebSocket-based real-time communication:
- **JWT authentication**: Secure WebSocket connections via boundary/user
- **Point-to-point messaging**: Send to specific user across all devices
- **Broadcast messaging**: Send to all connections
- **Role-based routing**: Send to users with specific role
- **Topic-based pub/sub**: Subscribe to arbitrary topics
- **Connection registry**: Track active WebSocket connections
- **Production-ready**: Phoenix Channels for Clojure

#### `boundary-tenant` (0.1.0)
Multi-tenancy infrastructure:
- **Tenant management**: CRUD operations for tenant entities
- **PostgreSQL schema isolation**: Per-tenant database schemas
- **Tenant context**: Thread-local tenant resolution
- **Job integration**: Tenant-scoped background jobs
- **Cache integration**: Tenant-scoped caching
- **Lifecycle**: Create, provision, suspend, activate, delete

#### `boundary-email` (0.1.0)
Email infrastructure:
- **SMTP adapter**: Production-ready email sending
- **Email preparation**: Validation, header formatting, recipient normalization
- **Async support**: Non-blocking email delivery
- **Attachment support**: File attachments via multipart/mixed

#### `boundary-external` (0.1.0) - **In Development**
External service adapters:
- **Skeleton implementation**: Not production-ready
- **Week 2+ roadmap**: HTTP client, API adapters, webhooks

### Features

#### Auto-CRUD Admin Interface
- **Django Admin for Clojure**: Auto-generated CRUD UIs from database schema
- **Zero boilerplate**: No manual form definitions required
- **Schema introspection**: Automatically detects entity structure, primary keys, soft delete
- **Customizable**: Override auto-detected config with manual settings
- **Field ordering**: Control form field display order via `:field-order`
- **Field grouping**: Organize forms into collapsible sections via `:field-groups`
- **Widget inference**: Smart form inputs based on field names and types
- **Relationship detection (Week 2+)**: Foreign key relationships, belongs-to, has-many

#### Multi-Factor Authentication (MFA)
- **TOTP-based**: RFC 6238 compliant Time-based One-Time Passwords
- **QR code generation**: Easy mobile app pairing
- **Backup codes**: 10 single-use recovery codes per user
- **Grace period**: 7-day enrollment window after setup
- **Login flow**: Email/password + TOTP code
- **API endpoints**: `/api/auth/mfa/setup`, `/api/auth/mfa/enable`, `/api/auth/mfa/verify`
- **Status**: ✅ Production Ready

#### HTTP Interceptors
- **Declarative pattern**: Auth, rate limiting, audit as route metadata
- **Three phases**: `:enter` (request), `:leave` (response), `:error` (exception)
- **Built-in interceptors**: Request logging, metrics, error reporting, correlation IDs
- **Composable**: Stack multiple interceptors per route
- **Example**:
  ```clojure
  {:path "/api/admin"
   :methods {:post {:handler 'handlers/create-resource
                    :interceptors ['auth/require-admin 'audit/log-action]
                    :summary "Create admin resource"}}}
  ```

#### Observability Interceptor Pattern
- **Multi-layer**: Service layer + persistence layer
- **Automatic**: Logging, metrics, error reporting, breadcrumbs
- **50% code reduction**: Eliminates boilerplate in 31/31 methods (user module)
- **Consistent error handling**: Standardized across all operations
- **Example**:
  ```clojure
  (defn create-user [this user-data]
    (service-interceptors/execute-service-operation
     :create-user
     {:user-data user-data}
     (fn [{:keys [params]}]
       ;; Business logic here - observability automatic
       (let [user (user-core/prepare-user (:user-data params))]
         (.create-user repository user)))))
  ```

#### API Pagination
- **Offset-based**: `limit` and `offset` parameters
- **RFC 5988 Link headers**: `first`, `prev`, `next`, `last` relations
- **Cursor-based (Week 2+)**: Planned for large datasets

#### Configuration Management
- **Aero-based**: Environment-specific profiles (`dev`, `test`, `prod`)
- **`#include` support**: Modular config files per module
- **Environment variables**: Override via `BND_ENV`
- **Example**: Admin entity configs in `resources/conf/{env}/admin/{module}.edn`

#### Database Support
- **Development**: SQLite (zero-config)
- **Testing**: H2 in-memory (via `:test` alias)
- **Production**: PostgreSQL (with schema isolation for multi-tenancy)
- **Migrations**: Flyway-based with `clojure -M:migrate up`

### Documentation

#### Comprehensive Documentation Site
- **Hugo-powered**: Static site generator with AsciiDoc support
- **Content**:
  - **Architecture Decision Records (ADRs)**: 8 documents
  - **Architecture guides**: 18 documents (FC/IS, ports/adapters, module structure)
  - **User guides**: 23 documents (authentication, admin, storage, MFA)
  - **API reference**: Complete API documentation
  - **Examples**: 5 code examples
  - **Getting started**: 6 onboarding guides
- **Deployed**: GitHub Pages at `https://thijs-creemers.github.io/boundary/`
- **Local dev**: `hugo server` in `docs-site/` directory

#### Developer Resources
- **AGENTS.md**: Complete developer guide (commands, patterns, conventions, troubleshooting)
- **Interactive Cheat Sheet**: `docs/cheatsheet.html` with client-side search, copy-to-clipboard
- **README.md**: Elevator pitches for developers (148 words) and management (94 words)
- **Scaffolder README**: Complete module generation workflow

#### Key Documentation Files
- **Architecture guides**: FC/IS patterns, design decisions
- **MFA Setup Guide**: Multi-factor authentication integration
- **API Pagination**: Offset and cursor pagination
- **Observability Integration**: Custom adapters, configuration
- **HTTP Interceptors**: Technical specification (ADR-010)
- **PRD**: Product vision and requirements

### Naming Conventions

#### ✅ ALWAYS Use kebab-case Internally
- **All Clojure code**: `:password-hash`, `:created-at`
- **Database (at boundary only)**: `password_hash`, `created_at`
- **API (at boundary only)**: `passwordHash`, `createdAt`
- **Conversion utilities**: `snake-case->kebab-case-map`, `kebab-case->snake-case-map`

**Why**: Recent bug caused authentication failures because service layer used `:password_hash` but entities had `:password-hash`. This convention prevents such mismatches.

### Testing

#### Comprehensive Test Suite
- **Test types**:
  - **Unit tests**: Pure functions, no mocks (`:unit` metadata)
  - **Integration tests**: Service with mocked deps (`:integration` metadata)
  - **Contract tests**: Adapters with real DB (`:contract` metadata)
- **Test commands**:
  ```bash
  clojure -M:test:db/h2                    # All tests
  clojure -M:test:db/h2 :core              # Core library
  clojure -M:test:db/h2 --focus-meta :unit # Unit tests only
  clojure -M:test:db/h2 --watch :core      # Watch mode
  ```
- **Coverage**: ~90-95% docstring coverage, comprehensive test coverage

#### Validation Snapshot Testing
- **Graph generation**: Visualize validation rules
- **Coverage reports**: Per-module validation coverage
- **Commands**:
  ```bash
  clojure -M:repl-clj <<'EOF'
  (require '[boundary.shared.tools.validation.repl :as v])
  (spit "build/validation-user.dot" (v/rules->dot {:modules #{:user}}))
  (System/exit 0)
  EOF
  dot -Tpng build/validation-user.dot -o docs/diagrams/validation-user.png
  ```

### Design System

#### Cyberpunk Professionalism
- **Primary color**: Indigo #4f46e5 (5.2:1 contrast on white ✅ WCAG AA)
- **Accent color**: Lime #65a30d (4.6:1 contrast ✅ WCAG AA)
- **Typography**: Geist font family (SIL Open Font License, loaded via jsDelivr CDN)
- **Dark mode**: Gray-12 #030712 base with neon glows
- **Design tokens**: Open Props CSS (`resources/public/css/tokens-openprops.css`)
- **Status colors**: All WCAG AA compliant

#### UI Technologies
- **Hiccup**: Server-side HTML generation (no build step)
- **HTMX**: Progressive enhancement for dynamic interactions
- **Pico CSS**: Base framework
- **Lucide Icons**: Icon system (50+ icons)

### Publishing Infrastructure

#### GitHub Actions Workflow
- **File**: `.github/workflows/publish.yml` (304 lines)
- **Triggers**: Manual dispatch + git tag `v*`
- **Libraries published**: 12 libraries in dependency order
- **Version strategy**: Lockstep versioning (all libraries at 1.0.0)
- **Status**: ✅ Ready (blocked on GitHub Secrets configuration)

#### Clojars Publishing
- **Organization**: `io.github.thijs-creemers`
- **Credentials**: Username `thijs-creemers` (password via GitHub Secrets)
- **Libraries**:
  - `boundary-core` → `io.github.thijs-creemers/boundary-core`
  - `boundary-observability` → `io.github.thijs-creemers/boundary-observability`
  - `boundary-platform` → `io.github.thijs-creemers/boundary-platform`
  - `boundary-user` → `io.github.thijs-creemers/boundary-user`
  - `boundary-admin` → `io.github.thijs-creemers/boundary-admin`
  - `boundary-storage` → `io.github.thijs-creemers/boundary-storage`
  - `boundary-scaffolder` → `io.github.thijs-creemers/boundary-scaffolder`
  - `boundary-cache` → `io.github.thijs-creemers/boundary-cache`
  - `boundary-jobs` → `io.github.thijs-creemers/boundary-jobs`
  - `boundary-tenant` → `io.github.thijs-creemers/boundary-tenant`
  - `boundary-email` → `io.github.thijs-creemers/boundary-email`
  - `boundary-external` → `io.github.thijs-creemers/boundary-external` (skeleton, not production-ready)

### Quick Start

#### Try Boundary (Recommended)
Use the [boundary-starter](https://github.com/thijs-creemers/boundary-starter) template:
```bash
git clone https://github.com/thijs-creemers/boundary-starter
cd boundary-starter
export JWT_SECRET="change-me-dev-secret-min-32-chars"
export BND_ENV="development"
clojure -M:repl-clj
```

In REPL:
```clojure
(require '[integrant.repl :as ig-repl])
(ig-repl/go)  ;; Visit http://localhost:3000
```

**What you get**:
- ✅ SQLite database (zero-config)
- ✅ HTTP server on port 3000
- ✅ Complete Integrant system
- ✅ REPL-driven development
- ✅ Production-ready Dockerfile

#### Using Individual Libraries
```clojure
;; deps.edn
{:deps {io.github.thijs-creemers/boundary-core {:mvn/version "1.0.0"}
        io.github.thijs-creemers/boundary-platform {:mvn/version "1.0.0"}
        io.github.thijs-creemers/boundary-user {:mvn/version "1.0.0"}
        io.github.thijs-creemers/boundary-admin {:mvn/version "1.0.0"}}}
```

### Deployment

#### Standalone JAR
```bash
clojure -T:build clean && clojure -T:build uber
java -jar target/boundary-*.jar server
```

#### Docker
Use provided `Dockerfile` in boundary-starter template.

#### Environment Variables
```bash
export JWT_SECRET="production-secret-min-32-chars"
export BND_ENV="production"
export DB_PASSWORD="secure_password"
export DATABASE_URL="jdbc:postgresql://localhost:5432/boundary"
```

### Known Issues and Limitations

#### Week 1 Limitations (To be addressed in Week 2+)
- **Admin permissions**: Entity-level and field-level permissions not yet implemented (admin-only)
- **Admin relationships**: Foreign key relationships not auto-detected
- **Composite primary keys**: Not fully supported in admin interface
- **Denylist mode**: Entity discovery only supports allowlist mode
- **Cursor-based pagination**: Not yet implemented (offset-based only)
- **Redis job queue**: In-memory only (Redis adapter planned)
- **External library**: Skeleton implementation, not production-ready

#### Pre-existing LSP Errors (Not Critical)
- **tenant/provisioning.clj**: Unresolved symbol `tx` (15 occurrences)
- **user/user_property_test.clj**: Unresolved test function symbols (17 occurrences)
- **platform/core_test.clj**: Unresolved symbol `tx-ctx` (5 occurrences)

These are false positives from clj-kondo's static analysis and do not affect runtime behavior.

#### Linting Warnings (Non-Critical)
- **Redundant `let` expressions**: 3 warnings in test files (cosmetic issue)

### Migration Guide

#### Not Applicable (First Release)
This is the initial 1.0.0 release. No migration from previous versions.

### Dependencies

#### Key Libraries
- **Clojure**: 1.12.0
- **Integrant**: 0.13.2 (lifecycle management)
- **Aero**: 1.1.6 (configuration)
- **Malli**: 0.16.4 (validation)
- **Reitit**: 0.7.2 (routing)
- **next.jdbc**: 1.3.955 (database)
- **HikariCP**: 6.2.1 (connection pooling)
- **Flyway**: 11.1.0 (migrations)
- **buddy**: 2.0.0 (authentication, JWT)
- **bcrypt**: 0.4.1 (password hashing)

#### Database Drivers
- **H2**: 2.3.232 (testing)
- **PostgreSQL**: 42.7.4 (production)
- **SQLite**: 3.47.2.0 (development)

### Contributors

- **Thijs Creemers** ([@thijs-creemers](https://github.com/thijs-creemers)) - Creator and maintainer

### License

Copyright 2024-2025 Thijs Creemers.

Distributed under the [Eclipse Public License 2.0](./LICENSE).

### Acknowledgments

#### Inspirations
- **Django** (Python): Admin interface, conventions over configuration
- **Ruby on Rails**: Rapid development, batteries-included philosophy
- **Spring Boot** (Java): Production-ready infrastructure
- **Luminus** (Clojure): Web development patterns (not compared, superseded by Boundary)
- **Kit** (Clojure): Module system (not compared, superseded by Boundary)

#### Design Patterns
- **Functional Core / Imperative Shell**: Gary Bernhardt's "Boundaries" talk
- **Ports and Adapters**: Alistair Cockburn's Hexagonal Architecture
- **Problem Details (RFC 7807)**: HTTP API error responses

### Roadmap

#### Week 2+ Features (Post-1.0.0)
- **Admin enhancements**:
  - Entity-level permissions (custom per-entity access rules)
  - Field-level permissions (hide/show fields based on user)
  - Record-level permissions (row-level security)
  - Permission groups (reusable permission sets)
  - Relationship detection (foreign keys, belongs-to, has-many)
  - Composite primary key support
  - Denylist entity discovery mode
- **Pagination**:
  - Cursor-based pagination (for large datasets)
- **Job processing**:
  - Redis queue adapter (distributed job processing)
- **External library**:
  - HTTP client adapter
  - API client framework
  - Webhook handling
- **Database support**:
  - MySQL adapter
  - SQLite adapter improvements
- **Validation**:
  - Validation graph visualization improvements
  - Cross-field validation
- **Testing**:
  - Property-based testing examples
  - Integration test helpers

---

## Version History

- **[1.0.1-alpha-23]** - 2026-05-18: Admin split-table fixes, embedded PostgreSQL tests, dependency upgrades, CI fix
- **[1.0.1-alpha-20]** - 2026-05-01: Fix `boundary new` bb.edn template — full boundary-tools task suite, version re-alignment
- **[1.0.1-alpha-14]** - 2026-04-25: Bug fixes — scaffolder in generated projects, AI CLI env fallback, OpenAI double /v1 path, smoke-check / linting in non-monorepo projects
- **[1.0.1-alpha-13]** - 2026-04-20: DX Vision (devtools, dev dashboard, REPL power, error experience, AI integration), LRU cache fix, CSP hardening
- **[1.0.1-alpha-12]** - 2026-04-06: E2E testing, admin UI improvements, auth bug fixes, quality gates, version bump
- **[1.0.0-alpha]** - 2026-02-14: Initial production release

[1.0.1-alpha-23]: https://github.com/thijs-creemers/boundary/releases/tag/1.0.1-alpha-23
[1.0.1-alpha-20]: https://github.com/thijs-creemers/boundary/releases/tag/1.0.1-alpha-20
[1.0.1-alpha-14]: https://github.com/thijs-creemers/boundary/releases/tag/v1.0.1-alpha-14
[1.0.1-alpha-13]: https://github.com/thijs-creemers/boundary/releases/tag/v1.0.1-alpha-13
[1.0.1-alpha-12]: https://github.com/thijs-creemers/boundary/releases/tag/v1.0.1-alpha-12
[1.0.0-alpha]: https://github.com/thijs-creemers/boundary/releases/tag/v1.0.0-alpha
