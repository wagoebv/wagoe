# User Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Authentication and authorization domain: user lifecycle, credentials, sessions/tokens, MFA flows, and audit logging.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.user.core.user` | Pure user-domain business logic |
| `wagoe.user.core.mfa` | Pure MFA setup/verification logic |
| `wagoe.user.shell.service` | Service-layer orchestration, validation, and `*audit-context*` binding |
| `wagoe.user.shell.http` | Auth/user HTTP handlers |
| `wagoe.user.shell.http-interceptors` | Auth, authorization, and audit interceptors |
| `wagoe.user.ports` | `IUserRepository`, `IUserSessionRepository` protocols |
| `wagoe.user.schema` | Malli schemas for User, Session, and request/response types |

---

## UI Contract

User web pages must use shared layout functions that already apply the central bundle contract from `wagoe.ui-style`.

Rules:
- Use `layout/pilot-page-layout` for user/profile/audit pages.
- Do not pass module-local hardcoded `:css [...]` lists from user feature namespaces.
- Keep form/table/badge visuals on shared classes and token variables (no per-page color overrides).

Reference:
- `libs/ui-style/README.md` (bundle keys and style contract)

---

## Authentication Flow

### Register → Login → Use Token → Logout

```bash
# 1. Register
curl -X POST http://localhost:3000/api/v1/users \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "SecurePass123!", "role": "user"}'

# 2. Login — returns accessToken
curl -X POST http://localhost:3000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "SecurePass123!"}'

# 3. Use token on protected endpoints
curl -H "Authorization: Bearer <accessToken>" \
  http://localhost:3000/api/v1/users

# 4. Logout (invalidates session)
curl -X POST http://localhost:3000/api/v1/auth/logout \
  -H "Authorization: Bearer <accessToken>"
```

### Token model — DB sessions are canonical

The module carries **two** token mechanisms; know which you are using:

- **DB-backed session tokens** (`wagoe.user.shell.middleware/validate-session`)
  are the **canonical, horizontally-safe** mechanism. They are stored, so they
  can be **revoked immediately** (logout, `invalidate-session`,
  `invalidate-all-user-sessions`) and any replica validates them against the DB.
  Prefer these for request authentication.
- **Stateless HS256 JWTs** (`auth/create-jwt-token` / `validate-jwt-token`) are
  **not stored** and therefore **cannot be revoked before expiry** — a logout
  does not invalidate an outstanding JWT. Use only for short-lived, stateless
  cases where that trade-off is acceptable.

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
                        auth-service
                        cache])            ; optional

;; Factory functions
(create-user-service repo session-repo audit-repo validation-cfg auth-svc)
(create-user-service repo session-repo audit-repo validation-cfg auth-svc cache)
```

### Key Service Methods

```clojure
;; Registration — validates, hashes password, persists, creates audit log
(register-user service user-data)

;; Authentication — delegates to auth-service, creates session and audit log
(authenticate-user service {:email "..." :password "..."})

;; Session validation — checks cache first, then database, updates access time
(validate-session service session-token)

;; Logout — invalidates session, clears cache, creates audit log
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
   :ip-address  (get-in request [:headers "x-forwarded-for"]
                        (:remote-addr request))
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
;; Protect a route — apply per-method
{:path    "/api/v1/users/:id"
 :methods {:get    {:handler      get-user-handler
                    :interceptors user-endpoint-stack}
           :put    {:handler      update-user-handler
                    :interceptors [require-authenticated
                                   require-self-or-admin
                                   log-action]}
           :delete {:handler      delete-user-handler
                    :interceptors admin-endpoint-stack}}}
```

---

## JWT Handling

JWT tokens are issued on login and validated on each request:

```clojure
;; JWT_SECRET env var must be set (minimum 32 characters)
export JWT_SECRET="dev-secret-at-least-32-characters-long-here"

;; Token structure (decoded)
{:user-id  uuid
 :email    string
 :role     string
 :exp      unix-timestamp}
```

Session tokens are stored in the database and optionally cached in Redis for fast validation. Cache TTL aligns with token expiry.

---

## Multi-Factor Authentication (MFA)

### Setup Flow

```bash
# Step 1: Generate TOTP secret and QR code
curl -X POST http://localhost:3000/api/auth/mfa/setup \
  -H "Authorization: Bearer <token>"
# Returns: {secret, qrCodeUrl, backupCodes}

# Step 2: Scan QR with Google Authenticator / Authy

# Step 3: Enable MFA with verification code
curl -X POST http://localhost:3000/api/auth/mfa/enable \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"secret": "...", "verificationCode": "123456"}'
```

### Login with MFA Enabled

```bash
# Include mfa-code in login request
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "...", "mfa-code": "123456"}'
```

### Backup Codes

Generated on MFA setup. Each code is single-use. Use when the authenticator app is unavailable:

```bash
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "...", "mfa-code": "BACKUP-CODE"}'
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

## Gotchas

- `JWT_SECRET` must be set (≥ 32 chars) for all auth-related tests and runtime operations.
- Keep internal keys kebab-case; convert snake_case/camelCase only at DB/API boundaries.
- `*audit-context*` is `nil` by default — always use `with-audit-context` in HTTP handlers.
- `defrecord` changes require `(ig-repl/halt)` + `(ig-repl/go)`, not just `(ig-repl/reset)`.
- Session cache and database must stay in sync — use `logout-user` to invalidate both.

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
UPDATE_SNAPSHOTS=true clojure -M:test \
  --focus wagoe.user.core.user-validation-snapshot-test
```

---

## Links

- [Library README](README.md)
- [MFA Setup Guide](../../docs/modules/guides/pages/authentication.adoc)
- [Root AGENTS Guide](../../AGENTS.md)
