# wagoe/user

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-user.svg)](https://clojars.org/com.wagoe/wagoe-user)

Complete user management and authentication system with MFA support, session management, and pre-built web UI.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {com.wagoe/wagoe-user {:mvn/version "1.1.0-rc-1"}}}
```

**Leiningen**:
```clojure
[com.wagoe/wagoe-user "1.1.0-rc-1"]
```

## Features

| Feature | Description |
|---------|-------------|
| **User Management** | Full CRUD operations for user accounts |
| **Authentication** | Password hashing (bcrypt) and JWT tokens |
| **Session Management** | Secure session handling with expiration |
| **Multi-Factor Auth** | TOTP-based MFA with QR code setup |
| **Audit Logging** | Track all user actions for compliance |
| **Account Security** | Login lockout after failed attempts |
| **Web UI** | Pre-built login, registration, and profile pages |

## Requirements

- Clojure 1.12+
- wagoe/platform
- wagoe/observability
- wagoe/core

## Quick Start

### Module Registration

```clojure
(ns myapp.main
  (:require [wagoe.user.shell.module-wiring]  ; Auto-registers module
            [wagoe.platform.shell.system.wiring :as wiring]
            [wagoe.config :as config]
            [myapp.system-config :as sys-config]))   ; your Integrant config

(defn -main [& args]
  (wiring/start! (sys-config/ig-config (config/load-config))))
```

### Configuration

```clojure
;; config.edn, under :active. JWT_SECRET (≥ 32 chars) comes from the environment.
{:wagoe/user
 {:auth {:default-session-hours      24
         :max-failed-attempts        5
         :lockout-duration-minutes   15
         :mfa-reset-enrolment-hours  72}}}
```

### User Service API

```clojure
(ns myapp.handlers
  (:require [wagoe.user.ports :as user-ports]))

;; Create user
(user-ports/register-user user-service
  {:email "john@example.com"
   :password "SecureP@ss123"
   :name "John Doe"})

;; Authenticate
(user-ports/authenticate-user user-service
  {:email "john@example.com"
   :password "SecureP@ss123"})
;; => {:authenticated true :user {...} :session {...} :session-token "..." :jwt-token "eyJhbG..."}

;; Authenticate with MFA
(user-ports/authenticate-user user-service
  {:email "john@example.com"
   :password "SecureP@ss123"
   :mfa-code "123456"})
```

### MFA Setup

```clojure
(require '[wagoe.user.shell.mfa :as mfa])   ; mfa-service is :wagoe/mfa-service

;; Generate MFA secret, QR code and backup codes
(mfa/setup-mfa mfa-service user-id)

;; Enable MFA (requires verification)
(mfa/enable-mfa mfa-service user-id secret backup-codes "123456")

;; Disable MFA: proof is {:password ...} or {:code ...}
(mfa/disable-mfa mfa-service user-id {:password "..."} {:ip-address ip :user-agent ua})
```

### Session Management

```clojure
;; Sessions are created by authenticate-user. Validate one:
(user-ports/validate-session user-service session-token)
;; => the session with its :user, or nil for an unknown token

;; Revoke it
(user-ports/logout-user user-service session-token)
```

### Pre-built Web Routes

The module registers these routes automatically:

| Path | Method | Description |
|------|--------|-------------|
| `/web/login` | GET | Login page |
| `/web/login` | POST | Login action |
| `/web/logout` | POST | Logout action |
| `/web/register` | GET | Registration page (not mounted with `:registration false`) |
| `/web/register` | POST | Registration action (not mounted with `:registration false`) |
| `/web/profile` | GET | User profile page |
| `/web/profile` | POST | Update profile |
| `/web/profile/mfa/setup` | GET, POST | MFA setup page; start setup |
| `/web/profile/mfa/verify` | POST | Verify the code and enable MFA |
| `/web/profile/mfa/disable` | GET, POST | Disable MFA |

### API Routes

| Path | Method | Description |
|------|--------|-------------|
| `/api/v1/auth/login` | POST | Authenticate user |
| `/api/v1/sessions` | POST | Create session (same as login) |
| `/api/v1/sessions/:token` | GET | Validate session |
| `/api/v1/sessions/:token` | DELETE | Invalidate session (logout) |
| `/api/v1/auth/mfa/setup` | POST | Generate MFA secret |
| `/api/v1/auth/mfa/enable` | POST | Enable MFA |
| `/api/v1/auth/mfa/disable` | POST | Disable MFA; needs `password` or a TOTP/backup `code` |
| `/api/v1/auth/mfa/status` | GET | MFA status |
| `/api/v1/users` | GET, POST | List or create users (admin) |
| `/api/v1/users/:id` | GET | Get user (self or admin) |
| `/api/v1/users/:id` | PUT, DELETE | Update or soft-delete user (admin) |

## Module Structure

```
libs/user/src/wagoe/user/
├── core/
│   ├── user.clj             # User business logic (pure)
│   ├── authentication.clj   # Auth logic (pure)
│   ├── mfa.clj              # MFA logic (pure)
│   ├── group.clj            # Groups, roles, rights (pure)
│   └── session.clj          # Session logic (pure)
├── ports.clj                # Service protocols
├── schema.clj               # Malli schemas
└── shell/
    ├── service.clj          # User service implementation
    ├── http.clj             # HTTP handlers
    ├── persistence.clj      # Database adapter
    └── module_wiring.clj    # Integrant config
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `wagoe/platform` | 1.1.0-rc-1 | HTTP, database |
| `buddy-hashers` | 2.0.167 | Password hashing |
| `buddy-sign` | 3.6.1-359 | JWT tokens |
| `one-time` | 0.9.0 | TOTP generation |
| `hiccup` | 2.0.0 | HTML templates |

## Database Schema

`initialize-user-schema!` (`shell/persistence.clj`) creates the tables from the
Malli schemas: `auth_users` (credentials, MFA, lockout), `users` (profiles),
`user_sessions` (SHA-256 of each token), `user_audit_log`, `user_identities`
(OIDC), and the group tables `user_groups`, `user_group_members`, `user_roles`,
`user_group_roles`, `user_group_grants`. It runs as a library migration
(`resources/wagoe/user/migrations/`).

## Relationship to Other Libraries

```
┌─────────────────────────────────────────┐
│              wagoe/admin             │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│              wagoe/user              │
│   (authentication, sessions, MFA)       │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│           wagoe/platform             │
└─────────────────────────────────────────┘
```

## Development

```bash
# Run tests (from the monorepo root)
clojure -M:test :user

# Lint
clojure -M:clj-kondo --lint libs/user/src libs/user/test
```

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
