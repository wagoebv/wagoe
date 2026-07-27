# boundary/user

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/org.wagoe/wagoe-user.svg)](https://clojars.org/org.wagoe/wagoe-user)

Complete user management and authentication system with MFA support, session management, and pre-built web UI.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {org.wagoe/wagoe-user {:mvn/version "1.0.0-beta-1"}}}
```

**Leiningen**:
```clojure
[org.wagoe/wagoe-user "1.0.0-beta-1"]
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
- boundary/platform
- boundary/observability
- boundary/core

## Quick Start

### Module Registration

```clojure
(ns myapp.main
  (:require [wagoe.user.shell.module-wiring]  ; Auto-registers module
            [wagoe.platform.shell.system.wiring :as wiring]))

(defn -main [& args]
  (wiring/start!))
```

### Configuration

```clojure
;; config.edn
{:wagoe/user-service
 {:jwt-secret #env JWT_SECRET
  :jwt-expiration-hours 24
  :session-expiration-hours 168  ; 7 days
  :password-min-length 8
  :lockout-threshold 5
  :lockout-duration-minutes 15
  :mfa-enabled? true}
 
 :wagoe/user-repository
 {:db-context #ig/ref :wagoe/db-context}}
```

### User Service API

```clojure
(ns myapp.handlers
  (:require [wagoe.user.ports :as user-ports]))

;; Create user
(user-ports/create-user user-service
  {:email "john@example.com"
   :password "SecureP@ss123"
   :name "John Doe"
   :role :user})

;; Authenticate
(user-ports/authenticate user-service
  {:email "john@example.com"
   :password "SecureP@ss123"})
;; => {:token "eyJhbG..." :user {...}}

;; Authenticate with MFA
(user-ports/authenticate user-service
  {:email "john@example.com"
   :password "SecureP@ss123"
   :mfa-code "123456"})
```

### MFA Setup

```clojure
;; Generate MFA secret and QR code
(user-ports/setup-mfa user-service user-id)
;; => {:secret "JBSWY3DPEHPK3PXP" :qr-code-url "otpauth://..."}

;; Enable MFA (requires verification)
(user-ports/enable-mfa user-service user-id
  {:secret "JBSWY3DPEHPK3PXP"
   :verification-code "123456"})

;; Disable MFA
(user-ports/disable-mfa user-service user-id)
```

### Session Management

```clojure
;; Create session
(user-ports/create-session session-service user-id {:ip "192.168.1.1"})
;; => {:session-id "..." :expires-at #inst "..."}

;; Validate session
(user-ports/validate-session session-service session-id)
;; => {:valid? true :user-id "..."}

;; Revoke session
(user-ports/revoke-session session-service session-id)
```

### Pre-built Web Routes

The module registers these routes automatically:

| Path | Method | Description |
|------|--------|-------------|
| `/web/login` | GET | Login page |
| `/web/login` | POST | Login action |
| `/web/logout` | POST | Logout action |
| `/web/register` | GET | Registration page |
| `/web/register` | POST | Registration action |
| `/web/profile` | GET | User profile page |
| `/web/profile` | PUT | Update profile |
| `/web/mfa/setup` | GET | MFA setup page |
| `/web/mfa/enable` | POST | Enable MFA |
| `/web/mfa/disable` | POST | Disable MFA |

### API Routes

| Path | Method | Description |
|------|--------|-------------|
| `/api/auth/login` | POST | Authenticate user |
| `/api/auth/logout` | POST | Invalidate session |
| `/api/auth/refresh` | POST | Refresh JWT token |
| `/api/auth/mfa/setup` | POST | Generate MFA secret |
| `/api/auth/mfa/enable` | POST | Enable MFA |
| `/api/auth/mfa/disable` | POST | Disable MFA |
| `/api/users` | GET | List users (admin) |
| `/api/users/:id` | GET | Get user |
| `/api/users/:id` | PUT | Update user |

## Module Structure

```
libs/user/src/wagoe/user/
├── core/
│   ├── user.clj             # User business logic (pure)
│   ├── authentication.clj   # Auth logic (pure)
│   ├── mfa.clj              # MFA logic (pure)
│   └── session.clj          # Session logic (pure)
├── ports.clj                # Service protocols
├── schema.clj               # Malli schemas
└── shell/
    ├── service.clj          # User service implementation
    ├── http.clj             # HTTP handlers
    ├── persistence.clj      # Database adapter
    └── module-wiring.clj    # Integrant config
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `boundary/platform` | 1.0.0-beta-1 | HTTP, database |
| `buddy-hashers` | 2.0.167 | Password hashing |
| `buddy-sign` | 3.6.1-359 | JWT tokens |
| `one-time` | 0.8.0 | TOTP generation |
| `hiccup` | 2.0.0 | HTML templates |

## Database Schema

```sql
CREATE TABLE users (
  id TEXT PRIMARY KEY,
  email TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  name TEXT,
  role TEXT DEFAULT 'user',
  mfa_enabled BOOLEAN DEFAULT FALSE,
  mfa_secret TEXT,
  failed_login_count INTEGER DEFAULT 0,
  lockout_until TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  deleted_at TEXT
);

CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  token_hash TEXT NOT NULL,
  ip_address TEXT,
  user_agent TEXT,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL
);
```

## Relationship to Other Libraries

```
┌─────────────────────────────────────────┐
│              boundary/admin             │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│              boundary/user              │
│   (authentication, sessions, MFA)       │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│           boundary/platform             │
└─────────────────────────────────────────┘
```

## Development

```bash
# Run tests
cd libs/user
clojure -M:test

# Lint
clojure -M:clj-kondo --lint src test
```

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
