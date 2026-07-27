# Core Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Foundation library shared by every other Boundary module: pure validation, case
conversion, type coercion, the interceptor pipeline engine, PII redaction, and
feature flags. It sits at the bottom of the dependency graph — **it depends only
on `org.clojure/clojure` and `metosin/malli`, and no Boundary library depends
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
| `boundary.core.utils.case-conversion` | `snake-case->kebab-case-map`, `kebab-case->snake-case-map`, `camel-case->kebab-case-map`, `kebab-case->camel-case-map` (all nil-safe map transforms); string variants `*-string`; keyword variant `kebab-case->snake-case-keyword`; `deep-transform-keys` for nested structures |
| `boundary.core.utils.type-conversion` | `uuid->string` / `string->uuid`, `instant->string` / `string->instant` (handles `Instant`, `OffsetDateTime`, `java.sql.Timestamp`, ISO strings), `keyword->string` / `string->keyword`, `boolean->int` / `int->boolean`, `string->boolean` / `string->int` / `string->enum`; CLI parsers `parse-uuid-string`, `parse-int` (positive-only), `parse-bool`. `snake-case->kebab-case` / `kebab-case->snake-case` delegate to `case-conversion` |

All conversions are **nil-safe** and return `nil` (or the input unchanged, for
the `string->*` coercers) rather than throwing on bad input — the CLI parsers
return `nil` for invalid input by design.

### Validation framework

| Namespace | Key fns / vars |
|-----------|----------------|
| `boundary.core.utils.validation` | `validate-with-transform` / `validate-cli-args` / `validate-request` (decode-then-validate a Malli schema → `{:valid? bool :data …}` / `{:valid? false :errors …}`); result accessors `validation-passed?`, `get-validation-errors`, `get-validated-data`; CLI helpers `valid-uuid?`, `valid-output-format?` |
| `boundary.core.validation` | Legacy/back-compat facade. Cached compiled `validator` / `explainer` / `decoder` (memoized), `valid?`, `explain`, `validate-with-transform` (switches to structured errors when `WAG_DEVEX_VALIDATION` is on), `success-result`, `failure-result`, `error-map`, `devex-enabled?` |
| `boundary.core.validation.result` | Canonical result format: `success-result`, `failure-result`, `error-map`, `warning-map`, predicates `validation-passed?` / `validation-failed?` / `has-warnings?`, accessors `get-errors` / `get-warnings` / `get-validated-data`, grouping `errors-by-field` / `errors-by-code`, `first-error`, `error-count`, `merge-results`, `add-error` / `add-warning`, `normalize-result` |
| `boundary.core.validation.codes` | Error-code catalog: `common-error-codes`, `user-error-codes`, `billing-error-codes`, `workflow-error-codes`, merged `error-code-catalog`; lookups `get-error-code-info`, `error-code-exists?`, `get-error-codes-by-category`, `get-error-codes-for-field`, `suggest-error-code` |
| `boundary.core.validation.messages` | Templating + "did you mean?" engine (Damerau-Levenshtein): `render-message`, `render-suggestion`, `enhance-error`, `interpolate-template`, `suggest-similar-value`, `format-field-name`, hint builders `create-did-you-mean-suggestion` / `create-expected-value-hint` / `create-range-hint` / `create-length-hint` / `create-dependency-hint` |
| `boundary.core.validation.context` | Operation/role/tenant-aware messaging: `render-contextual-message`, `enhance-error-with-context`, `generate-example-payload`, `get-next-steps`, `apply-operation-context` / `apply-role-context` / `apply-tenant-context` |
| `boundary.core.validation.registry` | **Pure** rule helpers only — `valid-rule?`, `validate-rule`, `find-duplicate-rule-ids`, `find-conflicting-rules`. The stateful in-process registry lives in the shell: `boundary.platform.shell.validation-registry` |
| `boundary.core.validation.coverage` | Pure coverage stats: `compute`, `merge-executions`, `edn-report`, `human-report`, `summary-line`, `compare-coverage`, `filter-by-module` |
| `boundary.core.validation.generators` | Property-based test data (deterministic via seed): `gen-valid` / `gen-valid-one`, `gen-invalid` / `gen-invalid-one`, `gen-boundaries`, `gen-for-rule`, `gen-for-module`, `resolve-schema`, `violation-types` |
| `boundary.core.validation.behavior` | Scenario DSL for validation testing: mutations `remove-field` / `set-field` / `replace-type` / `out-of-range`, `execute-scenario`, `compile-scenarios`, `defbehavior-suite` macro, templates `missing-required-field-template` / `wrong-format-template` / `valid-data-template` |
| `boundary.core.validation.snapshot` | Snapshot testing: `capture`, `stable-serialize`, `parse-snapshot`, `path-for`, `compare-snapshots` (alias `diff`), `format-diff` |
| `boundary.core.schema` | Reference Malli schemas `ValidationResult` and `InterceptorContext` (documentation shapes; runtime validation lives in `validation.result` / `interceptor-context`) |

### Interceptor pipeline

| Namespace | Key fns / vars |
|-----------|----------------|
| `boundary.core.interceptor` | Pipeline engine: `run-pipeline`, phase runners `run-enter-phase` / `run-leave-phase` / `run-error-phase`, `create-pipeline` / `validate-pipeline` / `validate-interceptor`, `halt-pipeline`, context helpers `update-context` / `get-from-context` |
| `boundary.core.interceptor-context` | Context schema (`Context`, `Request`, `Result`, `Response`, `SystemDeps`, …) + `validate-context` / `validate-context!`; constructors `create-initial-context`, `create-http-context`, `create-cli-context`, `create-service-context`; accessors `get-operation` / `get-logger` / `get-metrics` / `get-request-data` / `get-result` / `success?` / `has-errors?` / `halted?`; mutators `record-start-time` / `record-end-time` / `add-breadcrumb` / `add-validation-error` / `fail-with-exception`; `default-error-mappings` (exception-type → `[http-status title]`); debug `context-summary` / `sanitize-context-for-logging` |

### Feature flags

| Namespace | Key fns / vars |
|-----------|----------------|
| `boundary.core.config.feature-flags` | `enabled?`, `all-flags`, `flag-info`, `add-flags-to-config`, `parse-bool`, `get-env-value`; `known-flags` registry (`:devex-validation` → `WAG_DEVEX_VALIDATION`, `:structured-logging` → `WAG_STRUCTURED_LOGGING`) |

### PII redaction

| Namespace | Key fns / vars |
|-----------|----------------|
| `boundary.core.utils.pii-redaction` | `apply-redaction` (redacts `:tags`/`:extra` in an error-report context), `redact-pii` (recursive), `redact-pii-value`, `build-redact-state`, `mask-email`, `email-string?`, `normalize-key-name`, `default-redact-keys` (password/token/secret/email/ssn/credit-card, …). Consumed by observability error-reporting adapters |

---

## Case Conversion at the Persistence / API Boundary

The single most-used pattern in the monorepo. Convert **only** at boundaries;
keep everything internal kebab-case.

```clojure
(require '[boundary.core.utils.case-conversion :as cc]
         '[boundary.core.utils.type-conversion :as tc])

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
(require '[boundary.core.utils.validation :as v]
         '[malli.transform :as mt])

(v/validate-with-transform UserSchema data (mt/string-transformer))
;; => {:valid? true  :data <coerced>}    on success
;; => {:valid? false :errors <explain>}  on failure

(when-not (v/validation-passed? result)
  (v/get-validation-errors result))
```

## Interceptor Pipeline

`:enter` runs forward, `:leave` runs in reverse on success, `:error` runs in
reverse when a throwable escapes. Forward execution short-circuits as soon as an
interceptor sets `:halt? true` **or** puts a `:response` on the context (a
response terminates the enter chain — Pedestal/Sieppari semantics), and the
`:leave` phase still runs for interceptors that already executed.

```clojure
(require '[boundary.core.interceptor :as ic])

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
  (`boundary.platform.shell.validation-registry`), not in
  `validation.registry`. `bb check:fcis` enforces this.
- **`string->int` / `string->boolean` pass through on failure** (return the
  original value), while the CLI `parse-int` / `parse-bool` / `parse-uuid-string`
  return `nil`. Pick the right one for your call site.
- **Two `validate-with-transform`s exist.** `utils.validation` is the plain
  decode-then-validate helper; `boundary.core.validation` is the legacy facade
  that switches to structured errors under `WAG_DEVEX_VALIDATION`. Prefer the new
  `validation.result` API for new code.
- **`default-error-mappings` lives in `interceptor-context`**, inlined there on
  purpose to avoid a `core → platform` circular dependency — do not re-home it.

---

## Testing

```bash
# All core tests (pure/unit — no DB, fast)
clojure -M:test:db/h2 :core

# Unit metadata filter (all core tests are :unit)
clojure -M:test:db/h2 :core --focus-meta :unit

# Update validation snapshots when intended output changes
UPDATE_SNAPSHOTS=true clojure -M:test:db/h2 \
  --focus boundary.core.validation.snapshot-test
```

---

## Links

- [Library README](README.md)
- [Root AGENTS Guide](../../AGENTS.md)
