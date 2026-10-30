# User Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Authentication and authorization domain: user lifecycle, credentials, sessions/tokens, MFA flows, and audit logging.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.user.core.user` | Pure user-domain business logic |
| `wagoe.user.core.mfa` | Pure MFA setup/verification logic |
| `wagoe.user.core.oidc` | Pure OIDC decisions: id-token claims, callback state, account linking |
| `wagoe.user.shell.oidc` | OIDC code flow with PKCE: discovery, JWKS, token exchange, sign-in |
| `wagoe.user.shell.service` | Service-layer orchestration, validation, and `*audit-context*` binding |
| `wagoe.user.shell.http` | Auth/user HTTP handlers |
| `wagoe.user.shell.http-interceptors` | Auth, authorization, and audit interceptors |
| `wagoe.user.ports` | `IUserRepository`, `IUserSessionRepository`, `IUserAuditRepository`, `IUserService`, `IUserGroupRepository`, `IUserGroupService` protocols |
| `wagoe.user.core.group` | Pure rights: `user-grants`, `holds?`, `grants-for`, `authorized?`, `assignment-refusal`, `role-refusal`, `users-with-permission`, `import-changes` |
| `wagoe.user.schema` | Malli schemas for User, Session, and request/response types |

---

## UI Contract

User web pages must use shared layout functions that already apply the central bundle contract from `wagoe.ui-style`.

Rules:
- Pages a signed-in user reaches render in the admin shell (`wagoe.shared.ui.core.shell/shell-page`);
  `layout/pilot-page-layout` is for sign-in, registration and error pages.
- Do not pass module-local hardcoded `:css [...]` lists from user feature namespaces.
- Keep form/table/badge visuals on shared classes and token variables (no per-page color overrides).

Reference:
- `libs/ui-style/README.md` (bundle keys and style contract)

---

## Authentication Flow

### Create → Login → Use Token → Logout

```bash
# 1. Create a user — admin only; self-service sign-up is /web/register
curl -X POST http://localhost:3000/api/v1/users \
  -H "Authorization: Bearer <admin jwtToken>" \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "SecurePass123!", "role": "user"}'

# 2. Login — returns sessionToken and jwtToken. Wrong credentials answer 401, a locked
#    account 429 with Retry-After; 200 with requiresMfa means resend with mfaCode.
curl -X POST http://localhost:3000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "SecurePass123!"}'

# 3. Use token on protected endpoints
curl -H "Authorization: Bearer <jwtToken>" \
  http://localhost:3000/api/v1/users

# 4. Logout (invalidates the session; a JWT stays valid until it expires)
curl -X DELETE http://localhost:3000/api/v1/sessions/<sessionToken>
```

### Token model — DB sessions are canonical

The module carries **two** token mechanisms; know which you are using:

- **DB-backed session tokens** (`wagoe.user.shell.middleware/session-authentication-middleware`)
  are the **canonical, horizontally-safe** mechanism. They are stored, so they
  can be **revoked immediately** (logout, `invalidate-session`,
  `invalidate-all-user-sessions`) and any replica validates them against the DB.
  Prefer these for request authentication. The table holds each token's
  SHA-256, never the token; the UI names sessions by id (BOU-696).
- **Stateless HS256 JWTs** (`auth/create-jwt-token` / `validate-jwt-token`) are
  **not stored** and therefore **cannot be revoked before expiry** — a logout
  does not invalidate an outstanding JWT. Use only for short-lived, stateless
  cases where that trade-off is acceptable. The middleware does read the
  token's user, so a deactivated user's JWT is refused (BOU-668).

Signing keys:

- `JWT_SECRET` — HMAC key for JWTs. Required, **≥ 32 chars**, validated at
  startup (`auth/validate-jwt-secret!` fails boot fast). The algorithm is
  **pinned to HS256** on both sign and verify (no `alg` downgrade).
- `CSRF_SECRET` — separate secret for CSRF token signing. Falls back to
  `JWT_SECRET` when unset, but set it independently so rotating one key does not
  invalidate the other.

---

## Service Layer

### UserService Record

```clojure
(defrecord UserService [user-repository
                        session-repository
                        audit-repository
                        validation-config
                        auth-service])

;; Factory function
(create-user-service repo session-repo audit-repo validation-cfg auth-svc)
```

### Key Service Methods

```clojure
;; Registration — validates, hashes password, persists, creates audit log
(register-user service user-data)

;; Authentication — delegates to auth-service, creates session and audit log
(authenticate-user service {:email "..." :password "..."})

;; Session validation — reads the database every time; writes the access time
;; at most once a minute, or when it extends the expiry (BOU-666)
(validate-session service session-token)

;; Logout — invalidates session, creates audit log
(logout-user service session-token)

;; Profile update — uses *audit-context* for actor attribution
(update-user-profile service user-entity)

;; Password change — validates current password, hashes new, audits
(change-password service user-id current-password new-password)
```

---

## *audit-context* Dynamic Var

The `*audit-context*` dynamic var carries per-request audit metadata (actor, IP, user-agent) through the service layer without threading it through every function signature.

### Binding from an HTTP handler

```clojure
(require '[wagoe.user.shell.service :as user-service])

;; In your HTTP handler or interceptor :enter phase:
(user-service/with-audit-context
  {:actor-id    (get-in request [:session :user :id])
   :actor-email (get-in request [:session :user :email])
   ;; Not the raw X-Forwarded-For header: the client writes it.
   :ip-address  (wagoe.platform.core.http.client-ip/request-ip request)
   :user-agent  (get-in request [:headers "user-agent"])}
  (fn []
    ;; All service calls within f see the bound context
    (user-service/update-user-profile service updated-entity)))
```

### Structure

```clojure
;; Expected keys in *audit-context*
{:actor-id    uuid   ; User performing the action
 :actor-email string ; Human-readable actor identity
 :ip-address  string ; Request source IP
 :user-agent  string ; Client user-agent string}
```

If `*audit-context*` is `nil` (e.g., in CLI or background jobs), the service falls back to a system actor (`:default-actor-id`, `:default-actor-email "system"`).

---

## HTTP Interceptors

### Pre-built Interceptor Stacks

```clojure
(require '[wagoe.user.shell.http-interceptors :as auth-interceptors])

;; Add to route :interceptors
admin-endpoint-stack   ; [require-authenticated, require-admin, log-action]
user-endpoint-stack    ; [require-authenticated, log-action]
public-endpoint-stack  ; [log-action]

;; Custom stack
(create-custom-stack {:auth  require-authenticated
                      :authz require-admin
                      :audit log-all-actions})
```

### Individual Interceptors

```clojure
;; Authentication
require-authenticated    ; 401 if no session user
require-unauthenticated  ; 403 if session user exists (login/register pages)

;; Authorization
require-admin            ; 403 unless role = "admin"
(require-role :moderator); Factory function for any role
require-self-or-admin    ; 403 unless user is accessing own resource or is admin

;; Audit
log-action               ; Logs successful (2xx) actions in :leave phase
log-all-actions          ; Logs all actions including failures
```

### Applying to Routes

```clojure
;; Protect a route — apply per-method. The path is relative: /api/v1 is added
;; when the module's :api contribution is mounted.
["/users/:id"
 {:get    {:handler      get-user-handler
           :interceptors user-endpoint-stack}
  :put    {:handler      update-user-handler
           :interceptors [require-authenticated
                          require-self-or-admin
                          log-action]}
  :delete {:handler      delete-user-handler
           :interceptors admin-endpoint-stack}}]
```

---

## JWT Handling

JWT tokens are issued on login and validated on each request:

```clojure
;; JWT_SECRET env var must be set (minimum 32 characters)
export JWT_SECRET="dev-secret-at-least-32-characters-long-here"

;; Token structure (decoded)
{:sub      string  ; the user id
 :email    string
 :role     string
 :iat      unix-timestamp
 :exp      unix-timestamp}
```

Session tokens are stored in the database. Sessions and users are never cached, so a revoked session or deactivated user is refused on the next request (BOU-663).

`deactivate-user` sets `active` false and the user stays listed; `delete-user` sets `deleted_at` and hides them. Both end sessions and keep the last admin (BOU-681).

The last-admin guard counts and writes in one transaction that holds the admin rows, so two admins demoting each other cannot leave none. `delete-users` judges the whole set first and deletes all or none (BOU-673).

A database error while reading a session or a token's user is a 500, not a 401: only a refused credential is (BOU-666).

---

## Multi-Factor Authentication (MFA)

### Setup Flow

```bash
# Step 1: Generate TOTP secret and QR code
curl -X POST http://localhost:3000/api/v1/auth/mfa/setup \
  -H "Authorization: Bearer <token>"
# Returns: {secret, qrCodeUrl, backupCodes}

# Step 2: Scan QR with Google Authenticator / Authy

# Step 3: Enable MFA with verification code
curl -X POST http://localhost:3000/api/v1/auth/mfa/enable \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"secret": "...", "backupCodes": ["..."], "verificationCode": "123456"}'
```

### Login with MFA Enabled

```bash
# Include mfaCode in login request
curl -X POST http://localhost:3000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "...", "mfaCode": "123456"}'
```

### Deciding Per Login

`:wagoe/user {:auth {:mfa-required? my.app/mfa-required?}}` names a `(fn [user login-context] bool)`, resolved at boot. It replaces the default `(:mfa-enabled user)` for password and OIDC logins (OIDC adds `:provider` to the context). False waives the code without touching the user's MFA settings. True for a user without MFA refuses the login with `:mfa-enrolment-required` (`:oidc/mfa-enrolment-required` for OIDC); only a user an admin reset (within `:auth :mfa-reset-enrolment-hours`, 72, of `:mfa-reset-at`) or one just registered is sent instead to `/web/login/mfa-setup`, with a sealed ticket holding a password fingerprint (`shell/mfa_enrolment.clj`), to set one up; enabling MFA clears `:mfa-reset-at`. The hook never sees password or MFA secrets, and `:ip-address` is the client IP (`:wagoe/http :trusted-proxies` decides it behind a proxy), or nil when the proxy did not name the client. Match it with `client-ip/in-any?`, which is false for nil, so an office-network waiver still asks for the code. `:auth` is passed whole as the auth service's config, CLI included.

To read state a component holds, such as allowed networks kept in the database, contribute the hook from an app module instead of naming a var: its `ig-config` returns `:auth {:mfa-required? (ig/ref :my.app/mfa-policy)}`, and the component is a `(fn [user login-context])` or implements `ports/IMfaPolicy`. Each system then runs its own component, so two systems in one JVM do not share state. A hook set both in config and by a module, or by two modules, fails the boot.

Do not waive MFA by making the repository report `:mfa-enabled false`: login writes the user back, which turns MFA off in the database.

### Backup Codes

Generated on MFA setup. Each code is single-use. Use when the authenticator app is unavailable:

```bash
curl -X POST http://localhost:3000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "...", "mfaCode": "BACKUP-CODE"}'
```

**Details**: See [MFA Setup Guide](../../docs/modules/guides/pages/authentication.adoc) for complete MFA setup guide

---

## Project Setup — Creating the First Admin User

Use `bb create-admin` to bootstrap an admin account after setting up a new project. It wraps the Wagoe CLI in an interactive wizard.

```bash
# Interactive wizard (prompts for email, name, and password)
bb create-admin

# Specify environment (default: dev)
bb create-admin --env prod

# Skip email/name prompts — only the password is asked interactively
bb create-admin --email admin@myapp.com --name "Admin User"

# Full help
bb create-admin --help
```

**Pre-requisite**: run database migrations first.

```bash
clojure -M:migrate up
bb create-admin
```

The password is never passed on the command line. The wizard delegates to the Wagoe CLI's `--password-prompt` option, which reads it via a hidden TTY prompt and validates it against the configured password policy.

The wizard accepts `--env dev|test|acc|prod` to pick the Aero config profile and the correct database connection.

---

## Landing Page and Dashboard

Five `:wagoe/user` keys, all checked at boot; a bad value fails it.

```clojure
:wagoe/user
{;; Where a signed-in user lands: paths by role, `:default` for the rest,
 ;; `:default` defaults to the dashboard, the admin's home at /web/admin/.
 :login-redirect {:user "/web/overzicht" :default "/web/overzicht"}
 ;; or a (fn [user login-context]), resolved at boot; nil or an off-site path
 ;; means the default:   :login-redirect my.app.web/landing

 ;; /web/dashboard redirects to the dashboard (/web/profile without the
 ;; admin module). false: it is not mounted, so
 ;; the app can route it. {:handler my.app.web/dashboard}: that Ring handler
 ;; serves it, signed in. Either way users then land on /web/dashboard.
 :dashboard false

 ;; Where the layouts' logo, "back" and dashboard links point. Defaults to
 ;; /web/dashboard, or /web with :dashboard false.
 :home "/web/overzicht"

 ;; Where / and /web redirect everyone, signed in or not. Unset: / goes to
 ;; login (or /web/users), /web shows the welcome page or the user's landing.
 :landing "/web/welkom"

 ;; false: /web/register is not mounted and the login page drops its
 ;; "Create Account" link; accounts come from an admin, an import, or an OIDC
 ;; provider with :auto-create? true. Default true.
 :registration false}
```

`:login-redirect` applies to password login, MFA completion, OIDC sign-in and
`/web` for a signed-in user, unless `:landing` is set. A safe `return-to` still wins. The function's
login context has `:ip-address` (the client IP), `:user-agent` and the Ring
`:request`. An app module can contribute it as `:auth {:login-redirect (ig/ref …)}`
instead, a function or an `ports/ILoginRedirect`, like `:mfa-required?` above. With
`:dashboard false` and no `:login-redirect`, users land on `/web/dashboard`, so
serve it or point the redirect elsewhere. `:home` is bound per request
(`wagoe.shared.ui.core.home/*path*`), so the shared, admin, search and workflow
layouts read it without a config argument; `/web`, its fallback, sends a
signed-in user on to their landing path, or to `:landing` when that is set.

`:dashboard-extra-cards` at the top of the config is deprecated until 2.0: the
admin dashboard shows its Hiccup cards after its sections, and boot warns.
Without the admin module they show nowhere. Give
a module's nav item a `:description` instead.

`:landing` must be served by a route marked `:public true`, or a signed-out
visitor is sent to log in. The user module contributes `/` as a `:static`
route for it; the platform drops its own `/` whenever a module's `:static`
routes claim `/`, so an app without the user module can do the same.

## Login Page Layout

`:wagoe/user {:login-layout my.app.web/login-layout}` puts the login, MFA
(password and OIDC), OIDC-continue and registration pages in the app's page.
It is a `(fn [title body opts])` returning the whole page, resolved at boot;
`body` is Wagoe's form card (fields, CSRF field, errors), without Wagoe's logo
and subtitle. `opts`: `:page` (`:login` `:mfa` `:oidc-mfa` `:oidc-continue`
`:register` `:mfa-enrolment`), `:request`, `:user`, `:flash`, `:return-to`, `:logo-url`. A
module can contribute it as `:auth {:login-layout (ig/ref …)}`, a function or
a `ports/ILoginLayout`. Unset, the pages render as before
(`core.ui/auth-page`). The "Create Account" link lives in `login-form`, not the
layout.

## Profile Page Layout

`:wagoe/user {:profile-layout my.app.web/profile-layout}` does the same for
`/web/profile` and its MFA setup, backup-code and disable pages: a
`(fn [title body opts])`, or `:auth {:profile-layout …}` / `ports/IProfileLayout`
from a module. `opts`: `:user`, `:flash`, `:request`. The page must load htmx
and carry the CSRF meta tag, as the cards post through htmx; link
`/css/profile.css` (scoped to `.profile-page`, `--profile-*` properties) or
style the cards yourself; each card is also `.profile-card--<name>` (`info`,
`preferences`, `password`, `mfa`, `sessions`, `data`), to hide one. Unset, they
stay in the admin shell.

## Session Pruning

An expired session is invisible to every read — `find-session-by-token` and
`find-sessions-by-user` filter on `expires_at` and `revoked_at` — but its row
stays. `:wagoe/session-pruner` is what removes it; without a schedule
`user_sessions` grows with every login, for ever (BOU-429).

```clojure
:wagoe/session-pruner
{:enable-pruning true   ; false stops pruning entirely
 :retention-days 30     ; how long an expired session is kept, counted from expiry
 :interval-hours 6}     ; how often the prune runs
```

It lives in the user module rather than in jobs, because jobs is optional and
this table is not. Every replica runs its own timer; the DELETE is idempotent,
so two of them deleting the same rows is that DELETE twice.

A revoked session is pruned on the same terms as any other: revoking hides it,
expiry plus retention removes it.

## Groups, roles and assignments

ADR-041 §2–3. `:wagoe/user-group-service` implements `IUserGroupService`. An
assignment is group + role + scope (`{}` is everywhere). A user's grants,
`(user-grants svc user)`, are the permissions of the roles of their groups, each
`{:permission :scope :group-id :group-name :role}`, read on every call;
`wagoe.user.core.group` has `holds?`, `grants-for` and `authorized?`. The admin
pages are `/web/admin/groups` (`group-routes`, mounted beside the admin UI, admins
only).

The user module's own routes say `:public`, `:signed-in` or `:self`, so
`:wagoe/authorization :enforce` asks none of them a permission. Guide:
`docs/modules/guides/pages/authorization.adoc`. For administrators:
`docs/modules/guides/pages/managing-access.adoc`.

- Roles: code roles come from the permission registry (`:permission-registry`);
  managed roles (`create-role`, `update-role`, `delete-role`) are stored in
  `user_roles` and may name only registry permissions and no code role's name.
  `list-roles` gives both, with `:source` `:code` or `:managed`.
- `add-group-role` / `remove-group-role` change assignments (`user_group_roles`,
  the role stored by name). The scope is checked against the group service's
  scope schema; it is plain data and the platform resolves it (Scopes, platform
  AGENTS.md). An assignment taking a value from a `:user/<name>` source no
  module registers fails boot.
- Everyone (`core.group/everyone-id`) is built in: every signed-in user is in it
  without a row, new signups included. It cannot be renamed, deleted or given
  members, and its page shows roles only.
- `:group-membership` hook, `(fn [user] #{group-name …})` or an
  `IGroupMembership` component, set in `:wagoe/user` or a module's `:auth`: the
  groups an identity provider puts the user in. They count as membership and are
  not stored; a name with no group gives nothing. `permission-holders` does not
  see them.
- `:role` is `:admin` or nil. Rights come from groups. The column still stores
  `"user"` for no role so the previous version reads it, and `:user` is accepted on
  input as no role until 2.0. Only a platform admin may set or clear `:admin`
  (`core.user/admin-change-refusal`, checked in the service against the bound
  actor); without an actor (CLI) it is allowed.
- Migration (`group-service/migrate!`, at group-service init, idempotent): the
  first boot creates Everyone and, when users other than admins exist, gives it every module's
  `editor` code role except the admin's; users stored as `viewer` join `Readers`
  with every module's `viewer` role and keep no role. Neither gets a role holding
  a permission already checked as a grant (`core.group/gated-permissions`:
  declared by a module, in the groups schema, or granted), such as
  `:workflow/read`; each `user_group_grants`
  row not yet marked becomes an assignment of a managed role named after its
  permission, keeping the row's id, and is marked `migrated_at`. Rows stay until
  2.0 for nodes on the previous version during a rolling deploy; rights are read
  from assignments only. Permissions no module declares stay in their roles and
  are logged.
- `add-group-grant` / `remove-group-grant` and import `:grants` are deprecated:
  each grant becomes the managed role of its permission. Its permission is
  checked against `:groups :permissions` when set, else the registry
  (`with-registry-permissions`), else any keyword (no registry). A code role's
  name is refused, and such a stored grant is not migrated but logged: the
  code role would shadow the managed one.
- `:wagoe/user {:groups {:permissions <schema> :scope <schema>}}`, Malli or a
  symbol naming one, resolved in `ig-config`; a bad one fails boot. The
  permissions enum is declared into the registry until 2.0.
- The group page adds a member through a user lookup (`/:id/members/options`)
  and lists the hook's members read-only on request (`provider-members` runs
  the hook for every active user). Each declared
  scope key is a field: a lookup reading `/web/admin/<entity>/lookup` when it
  has `:wagoe/entity`, and a choice of the key's user sources; its value is
  decoded with the scope schema. The scope can be typed as EDN too, merged with the keys; a refused
  assignment comes back filled in.
- `/web/admin/roles` (code roles locked, managed ones a checklist of the
  registry, a code role duplicated), `/web/admin/permissions` and
  `/web/users/:id/rights` (`shell.rights-handlers`, `core.rights-ui`): role and
  permission names travel as `?name=`, since they hold a slash. Each shows who
  holds it (`role-holders`); the Rights tab resolves a user-sourced scope and its key's `:expand`, as
  enforcement does. `core.rights-ui/detail-tabs` is the one list of a user's tabs; `admin/users.edn` names it.
- `/web/users/:id/explain?permission=..&scope.<key>=..` answers why a user may or may not (BOU-851), through
  `IAccessExplain`: `wagoe.platform.shell.authorization/explain` reads and resolves the rights as the interceptor
  does and `core.scope/explain` decides with `route-decision`; a property test holds the two equal. A 403 from the
  interceptor links to it.
- Every group, assignment and managed-role change on the admin pages posts twice (BOU-852): first a preview
  (`IRightsPreview/preview-change`), then the same fields with `confirm=true`. The diff is
  `core.scope/rights-diff` on rights resolved by `resolve-rights`; the group service writes each user's diff into
  the change's audit entries under `:changes :effective`. Other members' rights are read in one
  `find-role-holders` query; identity-provider groups count only for the user the change names.
- `/web/users/:id/grant?permission=..&scope.<key>=..` gives a user one permission through groups, never directly
  (BOU-853). `IGrantOptions/grant-options` lists joining a group that holds it, a narrower assignment to one of
  theirs, a managed role holding only it, and a new group of one, each a `preview-change`, ranked by
  `core.grant/rank`. Joins and assignments apply through the group pages; the new group needs a name and a purpose.
  `/web/admin/permissions/grant` runs it for users pasted from `:report` log lines, which are not stored.
  An option whose preview is refused carries `:refused` and offers nothing to apply; the new group is made in one
  transaction by `IGrantGroup/create-grant-group` (BOU-863).
- A membership or an assignment may end (BOU-856): `:expires-at` on `add-group-role`'s assignment,
  `ITimeBoundAccess/add-group-member-until` for a membership. Every `IUserGroupRepository` read leaves an expired
  row out, so enforcement, explain, previews and the wizard stop counting it at once. The recurring job
  `:wagoe.user/expire-access` (every 15 minutes, on `:wagoe/jobs`; `:wagoe/jobs :recurring` overrides it) deletes it
  through `IAccessExpiry/remove-expired` and audits a `:group-leave` or `:grant-remove` with `:reason "expired"`.
- The group service takes `:permission-registry`. When the registry declares
  scope keys, the scope schema is `wagoe.platform.core.scope/scope-schema` of
  them (`with-registry-scopes`); a `:map` `:groups :scope` is declared into the
  registry by `declared-scopes`. See platform AGENTS.md, Scopes.
- Audit is per user: `:group-join`/`:group-leave` for the member,
  `:grant-add`/`:grant-remove` for every member when an assignment or a managed
  role's permissions change, so a change in an empty group writes nothing. A
  change to Everyone writes one entry, for the actor.
- Tables `user_groups`, `user_group_members`, `user_roles`, `user_group_roles`, and
  `user_group_grants` (kept, marked, until 2.0). Keys cascade from `auth_users`
  and `user_groups`; the repository deletes explicitly too, for SQLite connections
  without foreign keys. `scope` is EDN text; `edn-value?` refuses what would not
  read back, an `Instant` for one.
- A soft-deleted user holds nothing, Everyone included, and keeps their
  memberships for a restore.
- `(permission-holders svc permission)`: `{:user-id :permission :scope}` per
  active, not soft-deleted holder through a stored membership or Everyone,
  distinct. Filter the scope with `core.group/users-with-permission`.
- `(import-groups! svc groups {:replace? bool})` upserts groups by name, with
  `:roles [{:role :scope}]`. It checks every group, assignment and member first,
  then writes and audits in one transaction. Additive unless `:replace?`.
  `ensure-tables` runs `initialize-user-schema!` when the tables are missing, so an
  app migration can run it before the user library's. A concurrent import that
  loses the race gets `:conflict`; run it again.
- `user_groups.name` is unique (`uk_user_groups_name`): create, rename and import
  answer `:conflict` with `:field :name`. Where names already repeat, boot logs
  them at ERROR and goes on without the key.

## Turning the web UI off

For an API-only deployment: `:wagoe/settings {:features {:user-web-ui {:enabled? false}}}`,
or `WAG_WEB_UI_ENABLED=false` with the shipped configs. `web-ui-enabled?` in
`wagoe.user.shell.http` is the one check; `user-routes` and `group-routes` use it.

Removed:
- `/web` landing page, `/web/login`, `/web/register`, `/web/logout`
- `/web/dashboard`, `/web/profile/**` (incl. MFA setup/disable pages)
- `/web/users/**` (user management, sessions), `/web/audit`
- `/web/admin/groups/**`

Kept:
- `/api/v1/auth/login`, `/api/v1/sessions`, `/api/v1/sessions/:token`
- `/api/v1/users/**`, `/api/v1/auth/mfa/**`
- OIDC sign-in under `/web/auth/oidc/**`, when providers are configured. It lands
  on the admin's dashboard by default; point `:login-redirect` elsewhere.
- Not this module: the platform's `/` redirect still points at `/web/login`, and
  the admin UI (`:wagoe/admin`) stays mounted; leave it out of `:active` too.
- Groups have no API; manage them through `IUserGroupService`.

## The library's own tables

`auth_users`, `users`, `user_sessions` and `user_audit_log` ship as a library
migration (`wagoe/user/migrations/`, BOU-611) that runs `initialize-user-schema!`,
the definition boot uses. Other libraries' migrations can therefore reference
them. Change them in `initialize-user-schema!`, never in a second copy.

## Gotchas

- `JWT_SECRET` must be set (≥ 32 chars) for all auth-related tests and runtime operations.
- Keep internal keys kebab-case; convert snake_case/camelCase only at DB/API boundaries.
- `*audit-context*` is `nil` by default — always use `with-audit-context` in HTTP handlers.
- `defrecord` changes require `(ig-repl/halt)` + `(ig-repl/go)`, not just `(ig-repl/reset)`.

---

## Testing

```bash
# Run user library tests
clojure -M:test :user

# JWT secret required for auth tests
JWT_SECRET="dev-secret-at-least-32-characters-long" clojure -M:test :user

# Unit tests only (fast, no DB)
clojure -M:test :user --focus-meta :unit

# Update validation snapshots
UPDATE_SNAPSHOTS=true clojure -M:test :user \
  --focus wagoe.user.core.user-validation-snapshot-test
```

---

## Links

- [Library README](README.md)
- [MFA Setup Guide](../../docs/modules/guides/pages/authentication.adoc)
- [Root AGENTS Guide](../../AGENTS.md)
