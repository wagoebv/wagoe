# Core Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Foundation library shared by every other Wagoe module: pure validation, case
conversion, type coercion, the interceptor pipeline engine, PII redaction, and
feature flags. It sits at the bottom of the dependency graph — **it depends only
on `org.clojure/clojure` and `metosin/malli`, and no Wagoe library depends
*downward* into anything else from here.** There is **no `shell/`, no
`ports.clj`, no I/O**; everything is a pure function (the interceptor/generator
engines are the only namespaces tagged `^:wagoe/allow-throw`, because
throwing *is* their error model).

The four namespaces below carry almost all the traffic: `utils.type-conversion`,
`utils.case-conversion`, `interceptor`, and the `validation.*` family.

---

## Key Namespaces

### Case + type conversion (persistence / API boundary)

| Namespace | Key fns |
|-----------|---------|
| `wagoe.core.utils.case-conversion` | `snake-case->kebab-case-map`, `kebab-case->snake-case-map`, `camel-case->kebab-case-map`, `kebab-case->camel-case-map` (all nil-safe map transforms); string variants `*-string`; keyword variant `kebab-case->snake-case-keyword`; `deep-transform-keys` for nested structures |
| `wagoe.core.utils.type-conversion` | `uuid->string` / `string->uuid`, `instant->string` / `string->instant` (handles `Instant`, `OffsetDateTime`, `java.sql.Timestamp`, ISO strings), `keyword->string` / `string->keyword`, `boolean->int` / `int->boolean`, `string->boolean` / `string->int` / `string->enum`; CLI parsers `parse-uuid-string`, `parse-int` (positive-only), `parse-bool`. `snake-case->kebab-case` / `kebab-case->snake-case` delegate to `case-conversion` |

All conversions are **nil-safe** and return `nil` (or the input unchanged, for
the `string->*` coercers) rather than throwing on bad input — the CLI parsers
return `nil` for invalid input by design.

### Validation framework

| Namespace | Key fns / vars |
|-----------|----------------|
| `wagoe.core.utils.validation` | Value predicates the CLI validates arguments with: `valid-output-format?` (the user CLI validates `--format` with it) and `valid-uuid?` (no caller in this repository). Its copy of the validate/result API was removed in BOU-323 — schema validation goes through `wagoe.core.validation`, result shapes through `wagoe.core.validation.result` |
| `wagoe.core.validation` | Cached compiled `validator` / `explainer` / `decoder` (memoized), `valid?`, `explain`. Nothing else: the second copy of the result API, and the `WAG_DEVEX_VALIDATION` fork that chose a return shape at runtime, were removed in BOU-323 |
| `wagoe.core.validation.result` | Canonical result format: `success-result`, `failure-result`, `error-map`, `warning-map`, predicates `validation-passed?` / `validation-failed?` / `has-warnings?`, accessors `get-errors` / `get-warnings` / `get-validated-data`, grouping `errors-by-field` / `errors-by-code`, `first-error`, `error-count`, `merge-results`, `add-error` / `add-warning` |
| `wagoe.core.validation.codes` | Error-code catalog: `common-error-codes`, `user-error-codes`, `billing-error-codes`, `workflow-error-codes`, merged `error-code-catalog`; lookups `get-error-code-info`, `error-code-exists?`, `get-error-codes-by-category`, `get-error-codes-for-field`, `suggest-error-code` |
| `wagoe.core.validation.messages` | Templating + "did you mean?" engine (Damerau-Levenshtein): `render-message`, `render-suggestion`, `enhance-error`, `interpolate-template`, `suggest-similar-value`, `format-field-name`, hint builders `create-did-you-mean-suggestion` / `create-expected-value-hint` / `create-range-hint` / `create-length-hint` / `create-dependency-hint` |
| `wagoe.core.validation.context` | Operation/role/tenant-aware messaging: `render-contextual-message`, `enhance-error-with-context`, `generate-example-payload`, `get-next-steps`, `apply-operation-context` / `apply-role-context` / `apply-tenant-context` |
| `wagoe.core.validation.registry` | **Pure** rule helpers only — `valid-rule?`, `validate-rule`, `find-duplicate-rule-ids`, `find-conflicting-rules`. The stateful in-process registry lives in the shell: `wagoe.platform.shell.validation-registry` |
| `wagoe.core.validation.coverage` | Pure coverage stats: `compute`, `merge-executions`, `edn-report`, `human-report`, `summary-line`, `compare-coverage`, `filter-by-module` |
| `wagoe.core.validation.generators` | Property-based test data (deterministic via seed): `gen-valid` / `gen-valid-one`, `gen-invalid` / `gen-invalid-one`, `gen-boundaries`, `gen-for-rule`, `gen-for-module`, `resolve-schema`, `violation-types` |
| `wagoe.core.validation.behavior` | Scenario DSL for validation testing: mutations `remove-field` / `set-field` / `replace-type` / `out-of-range`, `execute-scenario`, `compile-scenarios`, `defbehavior-suite` macro, templates `missing-required-field-template` / `wrong-format-template` / `valid-data-template` |
| `wagoe.core.validation.snapshot` | Snapshot testing: `capture`, `stable-serialize`, `parse-snapshot`, `path-for`, `compare-snapshots` (alias `diff`), `format-diff` |
| `wagoe.core.schema` | Reference Malli schemas `ValidationResult` and `InterceptorContext` (documentation shapes; runtime validation lives in `validation.result` / `interceptor-context`) |

### Interceptor pipeline

| Namespace | Key fns / vars |
|-----------|----------------|
| `wagoe.core.interceptor` | Pipeline engine: `run-pipeline`, phase runners `run-enter-phase` / `run-leave-phase` / `run-error-phase`, `create-pipeline` / `validate-pipeline` / `validate-interceptor`, `halt-pipeline`, context helpers `update-context` / `get-from-context` |
| `wagoe.core.interceptor-context` | Context schema (`Context`, `Request`, `Result`, `Response`, `SystemDeps`, …) + `validate-context` / `validate-context!`; constructors `create-initial-context`, `create-http-context`, `create-cli-context`, `create-service-context`; accessors `get-operation` / `get-logger` / `get-metrics` / `get-request-data` / `get-result` / `success?` / `has-errors?` / `halted?`; mutators `record-start-time` / `record-end-time` / `add-breadcrumb` / `add-validation-error` / `fail-with-exception`; `default-error-mappings` (exception-type → `[http-status title]`); debug `context-summary` / `sanitize-context-for-logging` |

### Feature flags

| Namespace | Key fns / vars |
|-----------|----------------|
| `wagoe.core.config.feature-flags` | `enabled?`, `all-flags`, `flag-info`, `add-flags-to-config`, `parse-bool`, `get-env-value`; `known-flags` registry (`:devex-validation` → `WAG_DEVEX_VALIDATION`, `:structured-logging` → `WAG_STRUCTURED_LOGGING`) |

### PII redaction

| Namespace | Key fns / vars |
|-----------|----------------|
| `wagoe.core.utils.pii-redaction` | `apply-redaction` (redacts `:tags`/`:extra` in an error-report context), `redact-pii` (recursive), `redact-pii-value`, `build-redact-state`, `mask-email`, `email-string?`, `normalize-key-name`, `default-redact-keys` (password/token/secret/email/ssn/credit-card, …). Consumed by observability error-reporting adapters |

---

## Case Conversion at the Persistence / API Wagoe

The single most-used pattern in the monorepo. Convert **only** at boundaries;
keep everything internal kebab-case.

```clojure
(require '[wagoe.core.utils.case-conversion :as cc]
         '[wagoe.core.utils.type-conversion :as tc])

;; DB row -> internal entity (snake_case keys -> kebab-case)
(defn db->entity [row]
  (-> (cc/snake-case->kebab-case-map row)
      (update :id         tc/string->uuid)
      (update :created-at tc/string->instant)))

;; internal entity -> DB row (kebab-case -> snake_case)
(defn entity->db [entity]
  (-> entity
      (update :id         tc/uuid->string)
      (update :created-at tc/instant->string)
      cc/kebab-case->snake-case-map))

;; internal entity -> JSON API (kebab-case -> camelCase)
(cc/kebab-case->camel-case-map {:user-id "u1" :tenant-id "t1"})
;; => {:userId "u1" :tenantId "t1"}
```

## Running a Validation

```clojure
(require '[wagoe.core.validation :as v]
         '[malli.transform :as mt])

;; Decode then validate. Both compilers are cached on the schema, so this is
;; one compile per schema for the life of the process, not one per call.
(let [coerced ((v/decoder UserSchema mt/string-transformer) data)]
  (if (v/valid? UserSchema coerced)
    coerced
    (v/explain UserSchema coerced)))
```

Building a result map for a caller? That shape lives in
`wagoe.core.validation.result` — `success-result`, `failure-result`,
`error-map`. There is one implementation of each; BOU-323 removed the copies
that used to live in `wagoe.core.validation` and `wagoe.core.utils.validation`.

## Interceptor Pipeline

`:enter` runs forward, `:leave` runs in reverse on success, `:error` runs in
reverse when a throwable escapes. Forward execution short-circuits as soon as an
interceptor sets `:halt? true` **or** puts a `:response` on the context (a
response terminates the enter chain — Pedestal/Sieppari semantics), and the
`:leave` phase still runs for interceptors that already executed.

```clojure
(require '[wagoe.core.interceptor :as ic])

(ic/run-pipeline
  {:op :example :system {}}
  [{:name :auth  :enter (fn [ctx] (if authed? ctx (ic/halt-pipeline ctx {:status 401})))}
   {:name :log   :enter identity :leave (fn [ctx] (log! ctx) ctx)}
   {:name :work  :enter do-work}])
;; enter:  auth -> log -> work    (stops early if auth halts)
;; leave:  work -> log -> auth
```

Domain `ExceptionInfo` (with `:type`/`:message` in ex-data) is **re-thrown
unchanged** through `:error`; only non-domain throwables get wrapped with
interceptor metadata.

---

## Common Pitfalls

- **kebab/snake boundary rule (central).** Never let snake_case or camelCase keys
  leak into internal code — a mismatched `:password_hash` vs `:password-hash`
  once caused silent auth failures. Convert at the DB/API edge only, via the
  `case-conversion` map helpers.
- **Everything here must stay pure.** No I/O, no logging, no `defonce`/`atom`,
  no `Instant/now` / `UUID/randomUUID` inside these helpers — inject time/ids
  from the caller. Stateful registries live in the shell
  (`wagoe.platform.shell.validation-registry`), not in
  `validation.registry`. `bb check:fcis` enforces this.
- **`string->int` / `string->boolean` pass through on failure** (return the
  original value), while the CLI `parse-int` / `parse-bool` / `parse-uuid-string`
  return `nil`. Pick the right one for your call site.
- **One namespace per job, since BOU-323.** `wagoe.core.validation` caches
  compiled Malli validators; `wagoe.core.validation.result` owns the result
  shape; `wagoe.core.utils.validation` is two value predicates. Two of the
  three used to define `validate-with-transform`, and the one in
  `wagoe.core.validation` chose its return shape at runtime from
  `WAG_DEVEX_VALIDATION` — both are gone.
- **`default-error-mappings` lives in `interceptor-context`**, inlined there on
  purpose to avoid a `core → platform` circular dependency — do not re-home it.

---

## Testing

```bash
# All core tests (pure/unit — no DB, fast)
clojure -M:test :core

# Unit metadata filter (all core tests are :unit)
clojure -M:test :core --focus-meta :unit

# Update validation snapshots when intended output changes
UPDATE_SNAPSHOTS=true clojure -M:test \
  --focus wagoe.core.validation.snapshot-test
```

---

## Links

- [Library README](README.md)
- [Root AGENTS Guide](../../AGENTS.md)
