# Upgrading Wagoe

Migration notes for projects built on the Wagoe framework (generated with
`wagoe new` or hand-wired against the published `com.wagoe/*`
artifacts). Steps are ordered by impact; each names the change, the failure
mode you'd see without it, and the fix.

> **Quick check:** after bumping versions, run `wagoe doctor`. Its
> `upgrade-wiring` check names any namespace that moved between releases and
> says where it went.

## 1.0.x → unreleased (OpenAPI 3.1)

### 1. API docs moved from Swagger 2.0 to OpenAPI 3.1 (BOU-462)

The document is at `/openapi.json`, and `/api-docs/` reads it. `/swagger.json`
serves the same 3.1 document until 2.0, so a client that
expects 2.0 there gets 3.1. Point it at `/openapi.json`.

Routes with raw parameter docs move from `:swagger` to `:openapi`, with the
type under `:schema`:

```clojure
;; Before
:swagger {:parameters [{:name "id" :in "path" :required true :type "string"}]
          :responses  {200 {:description "The product" :schema shown}}}
;; After
:openapi {:parameters [{:name "id" :in "path" :required true :schema {:type "string"}}]
          :responses  {200 {:description "The product"
                            :content {"application/json" {:schema shown}}}}}
```

Until you do, or until 2.0, the router translates `:swagger` parameters and
responses and warns at boot, so the
docs keep their inputs. A generated module's `shown` uses `malli.swagger`;
`malli.json-schema/transform` is the 3.1 equivalent. Routes with Malli
`:parameters` need nothing. The `:swagger-enabled` and `:swagger-data` router
config keys keep their names, but `:swagger-data` is now the 3.1 document's top
level: 2.0 keys there, such as `:securityDefinitions` and `:basePath`, move to
`:components {:securitySchemes ..}` and `:servers`.

`wagoe-platform` no longer brings in `metosin/reitit-swagger`. Add it to your
own deps if you require `reitit.swagger`.

### 2. The admin checks permissions under `:enforce` (BOU-774)

Nothing changes under `:wagoe/authorization {:mode :off}`, the default. Under
`:enforce` the admin admits a platform admin and holders of
`admin.<entity>/read|write`, not role `:admin`: a tenant `:admin` needs a group
with an `admin.<entity>/viewer` or `/editor` role. Run `:report` first; it logs
each refusal `:enforce` would make. Map scope keys on an entity with `:scope`
(see the admin docs). `:require-role` is deprecated and goes in 2.0; drop it.

### 3. Moving an existing app to `:enforce`

`bb upgrade authorization check|prepare|report|enforce` (or `wagoe upgrade
authorization`) moves an app from `:off` to `:enforce` and names each user who
would lose access. See
[Upgrading an existing app to permissions](docs/modules/guides/pages/upgrade-authorization.adoc).

### 4. The `:base` CSS bundle is gone; pages use `:pilot` (BOU-457, BOU-458)

Pages on the default layout render with daisyUI, not pico: restyle pages that
relied on pico with daisy classes. `base-css` and `base-js` no longer compile;
use `pilot-css` and `pilot-js`. `(ui-style/bundle :base)` still renders, but it
serves `:pilot` and warns once.

### 5. `:role` is `:admin` or nothing (BOU-771)

Rights come from groups. A user map's `:role` is `:admin` or `nil`; a stored
`user` or `viewer` reads as `nil`. At first boot the Everyone group, which
every signed-in user is in, gets every module's `editor` role when there are
users other than admins; users with role `:viewer` join a Readers group holding
every module's `viewer` role; group grants become roles. Former viewers are in
Everyone too, so under `:enforce` they can edit: trim Everyone's roles if they
should not. `wagoe upgrade authorization check` prints the plan. Only a
platform admin sets or clears `:admin`.

Code that checks `(= :user (:role u))` or `:viewer` never matches now: check
`(nil? (:role u))`, or better, a permission. A `:login-redirect` role map's
`:viewer` key is never used. Call `add-group-role`/`remove-group-role` where you
called `add-group-grant`/`remove-group-grant`, and import `:roles [{:role :scope}]`
rather than `:grants`.

### 6. Config keys that went (BOU-765, BOU-771, BOU-773, BOU-774, BOU-781, BOU-847)

All are still read until 2.0 unless noted; boot warns for most.

| Key | Now |
|---|---|
| `:wagoe/admin :require-role` | nothing reads it; delete it (step 2) |
| OIDC provider `:default-role` | put those users in a group |
| `:wagoe/user :groups :scope` | a module's `:authorization :scopes` |
| `:wagoe/geo-service :db` | `:cache? true` |
| `:db` on `:wagoe.push/device-store` and `:analytics-store` | `:db-ctx` |
| `:dashboard-extra-cards` | a `:description` on the module's nav item |
| dev dashboard `:ig-config-fn` | not needed; Apply uses your `set-prep!` fn |

`:export-requests-per-hour 0` means no limit now, and anything but a whole
number fails boot (BOU-759).

### 7. Run `wagoe upgrade` for the deprecations

Every `### Deprecated` entry has a `wagoe upgrade` note. `wagoe upgrade` prints
the rewrites it can make as a diff (`--apply` writes them) and lists the rest
with what to do by hand.

### 8. `environ`, `dotenv` and `javax.mail` are gone (BOU-457)

`wagoe-platform` no longer brings in `environ` or `com.lambdaisland/dotenv`.
Declare them in your own `deps.edn` if you require them.

`wagoe-external` moved to Jakarta Mail 2 (Angus Mail). Code using `javax.mail`
classes itself moves to `jakarta.mail`. SMTP and IMAP now check that the
server's certificate names `:host`; for a self-signed or misnamed one, set
`:ssl-trust` or `:check-server-identity? false` (BOU-741).

### 9. Turning off MFA needs the password or a code (BOU-782)

`POST /api/v1/auth/mfa/disable` without a body now fails. Send the user's
password or a current TOTP or backup code; wrong ones count towards the
lockout.

```json
{"password": "…"}
{"code": "123456"}
```

`wagoe.user.shell.mfa/disable-mfa` takes the same proof and the request
context: `(disable-mfa svc user-id {:password p} {:ip-address ip :user-agent ua})`.

### 10. `/web/users` pages moved into the admin (BOU-766, BOU-804)

`GET /web/users/:id` redirects to `/web/admin/users/:id`, which has the
Details, Sessions and Rights tabs. `PUT` and `DELETE /web/users/:id` are gone:
edit and soft-delete through the admin, delete permanently with
`POST /web/users/:id/hard-delete`. `POST /web/users/bulk` takes `ids[]`, not
`user-ids`. Update links, scripts and E2E tests that used them.

### 11. Group and role changes preview first (BOU-852)

A POST to the group, role or grant pages under `/web/admin/groups`,
`/web/admin/roles` and `/web/users/:id/grant/role|group` that changes rights
answers 200 with a preview and changes nothing. A script posting to them sends
`confirm=true`:

```bash
curl -X POST …/web/admin/groups/$ID/members -d user-id=$USER -d confirm=true …
```

### 12. Your own port implementations may need a new protocol (BOU-829)

Methods 1.1 adds to a stable port are new protocols beside the old one. An
app's own repository or service that lacks one Wagoe calls stops the boot with
`WGE-107`, naming it; `bb guide error WGE-107` explains. Implement the protocol
named.

### 13. Run the migrations (BOU-551, BOU-849)

`bb migrate up`: the tenant tables now ship as a library migration, and push
callback rows gain `callback_id`.

### 14. Smaller changes

- API clients of `POST /api/v1/auth/login` and `POST /api/v1/sessions` read the
  camelCase keys in `user` and `session` (`session.sessionToken`, not
  `session.session-token`). The kebab-case ones go in 2.0 (BOU-737, BOU-582).
- An IMAP fetch without `:limit` takes 100 messages; pass a larger `:limit`.
  `core.imap/filter-unread` and `filter-by-date` are gone. Pass the fetched
  message to `mark-read!` and `delete-message!`:
  `(mark-read! mb (:uid msg) msg)` (BOU-731).
- `GET /api/v1/push/devices/:token` takes `?app-id=`; a token under two app-ids
  answers 409 without it (BOU-847). Set `:callback-secret` in `:wagoe/push`, or
  push warns at boot and callbacks record nothing (BOU-844).
- `search/create-search-store` takes the adapter, not a db-type:
  `(create-search-store ds (:adapter db-ctx))` (BOU-450).
- Tenant `:not-supported` and `:unsupported-database` errors carry `:engine`
  (`:h2`), not `:dialect` or `:database-type` (BOU-450).
- A tenant update refuses `status: deleted`; use `DELETE /tenants/:id`. A
  revoked membership is deleted and answers 404 (BOU-611).
- Admin cells show a relation's title and an enum's `:options` label, not the
  UUID or raw value; update E2E selectors that matched either (ADR-040).
- The admin's CSS is one file, `/css/wagoe-admin.css`. A layout that lists the
  old files loads `(ui-style/bundle :admin-pilot)` instead (BOU-808).

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
flag is still registered in `wagoe.core.config.feature-flags`, and no production
namespace reads it — one test helper still reads the environment variable
directly.

Note on the result shape: `failure-result` puts `:errors` in a **vector**.
ADR-036 §2 asks for a map of field to messages and lists the vector form as
migration work that has not happened yet, so expect that key to change shape in
a later release.

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
wagoe doctor         # environment, config, commands, project setup — one next action
clojure -M:test      # your project's test suite
```

Boot the system and confirm the log line `Adding multi-tenant middleware to
HTTP pipeline` appears if you use the tenant module — its absence after this
upgrade means step 1 was missed.
