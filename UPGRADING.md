# Upgrading Wagoe

Migration notes for projects built on the Wagoe framework (generated with
`wagoe new` or hand-wired against the published `com.wagoe/*`
artifacts). Steps are ordered by impact; each names the change, the failure
mode you'd see without it, and the fix.

> **Quick check:** after bumping versions, run `bb doctor`. The
> `upgrade-wiring` and `wiring-requires` checks detect the stale-wiring cases
> below (including the silent one) in your own source tree.

## beta-5 → beta-6 (error shapes)

### 1. `wagoe.user.shell.auth/authenticate-user` and the MFA shell return `:error` as a map (BOU-323)

ADR-036 settled one shape for a failure that is an answer rather than an
exception: `{:success? false :error {:type <keyword> :message <string>}}`. Two
namespaces in `wagoe-user` moved to it.

**Before:**

```clojure
;; auth/authenticate-user
{:success? false :error :authentication-failed :message "Invalid credentials"}
;; mfa/enable-mfa
{:success? false :error "Invalid verification code"}
```

**After:**

```clojure
{:success? false :error {:type :authentication-failed :message "Invalid credentials"}}
{:success? false :error {:type :invalid-code :message "Invalid verification code"}}
```

`:retry-after` moved with it, from the top level into the `:error` map — it is
part of why the attempt failed.

**You are affected only if you call those shell functions directly.** The
service and HTTP layers were updated in the same change, so:

* `IUserService/authenticate-user` still answers
  `{:authenticated false :reason <keyword> :message <string> :retry-after …}`;
* `POST /api/auth/mfa/{setup,enable,disable}` still answer
  `{"error": "<message string>"}`.

Both are covered by tests that fail if the flattening breaks.

**Fix:** read `(get-in result [:error :type])` and
`(get-in result [:error :message])` where you read `(:error result)` and
`(:message result)` before.

### 2. Adapter `:error :type` values are keywords (BOU-323)

`wagoe-external`'s SMTP, IMAP and Twilio adapters, `wagoe-email`'s job
integration, `wagoe-jobs`' worker and `wagoe-reports`' job integration returned
a *string* in the `:type` of their `:error` map:

```clojure
;; before
{:success? false :error {:message "…" :type "SmtpError"}}
;; after
{:success? false :error {:message "…" :type :smtp-error}}
```

The full set: `:smtp-error`, `:smtp-connection-error`, `:imap-error`,
`:twilio-error`, `:network-error`, `:unexpected-error`, `:no-handler`,
`:email-job-error`, `:report-job-error`.

`wagoe-push`'s adapters moved too: their `:error` was a bare string where
`ports.clj` had always documented a map.

**Fix:** compare against the keyword. A caller that escalates can now rethrow
the `:error` map as a typed `ex-info` without translating a string first, which
is the point.

**Two things to know if you store these results:**

* `wagoe.jobs.schema/Job` and `wagoe.external.schema/EmailSendResult` declare
  `[:type keyword?]` now. Validating a *stored* job whose `:error :type` is
  still a string will fail — the schema describes what this version produces.
* Job rows written before the upgrade keep the old spelling. The Redis store
  restores `:error :type` as a keyword on read, so `"NoHandlerError"` comes back
  as `:NoHandlerError`, not `:no-handler`. A consumer that has to handle both
  should match on the old name explicitly for as long as those rows live.

The jobs worker also stopped putting a thrown exception's class name in
`:error :type`. That field is a keyword naming the kind of failure
(`:handler-error`); the class is `:error :exception-class`.

### 3. The duplicated validation API is gone (BOU-323)

Three namespaces defined the same functions. `wagoe.core.validation` and
`wagoe.core.utils.validation` each had their own `validate-with-transform`,
`validate-cli-args`, `validate-request` and result accessors, and
`wagoe.core.validation` also re-exported `success-result`, `failure-result` and
`error-map` from `wagoe.core.validation.result`.

Worse, `wagoe.core.validation/validate-with-transform` chose its **return
shape** at runtime from the `WAG_DEVEX_VALIDATION` flag: `{:valid? true :data …}`
with it off, a structured result with it on.

What each namespace is now:

* `wagoe.core.validation` — cached compiled `validator` / `explainer` /
  `decoder`, plus `valid?` and `explain`. That is the reason it exists:
  compiling a Malli validator costs about ten times running one.
* `wagoe.core.validation.result` — the result shape and its accessors.
* `wagoe.core.utils.validation` — `valid-uuid?` and `valid-output-format?`.

**Fix:** decode with `(validation/decoder schema transformer)`, validate with
`validation/valid?`, and build results with `wagoe.core.validation.result`. If
you relied on the flag-on behaviour, the structured constructors are what you
were getting; if you relied on the flag-off behaviour, `{:valid? …}` is a map
literal you can write yourself.

`devex-validation-enabled?` is gone from `wagoe.core.validation.result`. The
flag is still registered in `wagoe.core.config.feature-flags`; nothing reads it.

### 4. `wagoe.core.validation.result/normalize-result` and `legacy-result?` are gone (BOU-323)

They coerced between two result shapes at runtime, in the namespace that
defines the one shape. Nothing in the framework called them. If you did: a
result without `:warnings` is a valid result, and `get-warnings` already
answers `[]` for it.

### 5. `wagoe.user.shell.auth/change-user-password` is gone (BOU-323)

It had no callers, and it could not have had working ones: it called
`update-user` with three arguments where the repository protocol takes two, so
any call ended in an arity error. Password changes go through
`IUserService/change-password`, which throws typed errors
(`:user-not-found`, `:invalid-current-password`, `:password-policy-violation`).

## alpha-30 → alpha-42+ (Phase 2 refactors)

### 1. Tenant HTTP middleware is now wired by the app — **silent if missed** (BOU-200)

The platform `:wagoe/http-handler` no longer builds tenant/membership
middleware from a `:membership-service` key — it ignores that key entirely.
The tenant lib now provides a `:wagoe/tenant-http-middleware` component,
and the app injects it via the handler's generic `:extra-middleware` seam.

**Failure mode:** the system boots normally, but tenant resolution and
membership checks silently stop running. Multi-tenant isolation is gone with
no error. This is the highest-priority step.

```clojure
;; BEFORE (ignored since alpha-42)
:wagoe/http-handler
{:tenant-service     (ig/ref :wagoe/tenant-service)
 :membership-service (ig/ref :wagoe/membership-service)   ; <- dead key
 ...}

;; AFTER
:wagoe/tenant-http-middleware
{:tenant-service     (ig/ref :wagoe/tenant-service)
 :membership-service (ig/ref :wagoe/membership-service)
 :db-context         (ig/ref :wagoe/db-context)}

:wagoe/http-handler
{:tenant-service   (ig/ref :wagoe/tenant-service)          ; still used (readiness/test-reset)
 :extra-middleware (ig/ref :wagoe/tenant-http-middleware)
 ...}
```

### 2. The app must load feature module wiring (BOU-171/192/198)

Platform's `wagoe.platform.shell.system.wiring` no longer requires the
feature modules' `module-wiring` namespaces (user, admin, tenant, workflow,
search). The application that assembles the system owns those loads.

**Failure mode:** loud — Integrant init fails with a missing `init-key`
defmethod (e.g. `:wagoe/user-repository`).

```clojure
;; In your app's config/main namespace, alongside the platform wiring require:
(:require [wagoe.platform.shell.system.wiring]
          [wagoe.user.shell.module-wiring]        ; always (user is core)
          ;; per enabled module:
          [wagoe.tenant.shell.module-wiring]
          [wagoe.admin.shell.module-wiring]
          [wagoe.workflow.shell.module-wiring]
          [wagoe.search.shell.module-wiring])
```

Projects generated by `wagoe new` on alpha-42+ get this automatically
(conditional requires driven by the active config).

### 3. Tenant middleware namespaces moved out of platform (BOU-198)

**Failure mode:** loud — compile error on the old require.

| Old (platform) | New (tenant lib) |
|---|---|
| `wagoe.platform.shell.interfaces.http.tenant-middleware` | `wagoe.tenant.shell.tenant-middleware` |
| `wagoe.platform.shell.interfaces.http.membership-middleware` | `wagoe.tenant.shell.membership-middleware` |

Function names and signatures are unchanged (`wrap-multi-tenant`,
`wrap-tenant-resolution`, `wrap-tenant-schema`, `wrap-tenant-membership`).

### 4. Shared UI moved to its own artifact (BOU-193/194, BOU-202)

`wagoe.shared.ui.*` namespaces moved from the admin lib into a new
`com.wagoe/wagoe-shared-ui` artifact. The namespaces themselves are
unchanged, and since alpha-42 the published POMs declare their inter-Wagoe
dependencies, so `wagoe-user`/`wagoe-admin`/etc. pull `shared-ui` in
transitively.

**Action:** usually none. If your deps.edn hand-enumerates the Wagoe
closure (pre-alpha-42 template style), you can either add
`com.wagoe/wagoe-shared-ui` or — better — trim the list to the
modules you directly use and let the POMs resolve the rest.

### 5. User-route authorization hardening (BOU-190/191/197)

Behavior changes, no wiring required:

- `GET /api/v1/users` (list) and `PUT`/`DELETE /api/v1/users/:id` are
  admin-only; `GET /api/v1/users/:id` is self-or-admin. Non-admin cross-user
  requests now return **403**.
- Web user-management pages (`/web/users/...`) are mounted and admin-only;
  session views (`/users/:id/sessions*`) are self-or-admin.
- Password changes and profile-email changes now revoke the user's other
  sessions (session rotation).

If API clients relied on non-admin directory listing or cross-user reads,
they must authenticate as admin now.

## Verifying an upgrade

```bash
bb doctor            # config + stale-wiring checks (upgrade-wiring, wiring-requires)
clojure -M:test      # your project's test suite
```

Boot the system and confirm the log line `Adding multi-tenant middleware to
HTTP pipeline` appears if you use the tenant module — its absence after this
upgrade means step 1 was missed.
