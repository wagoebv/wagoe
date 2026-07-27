# boundary/core

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/org.wagoe/wagoe-core.svg)](https://clojars.org/org.wagoe/wagoe-core)

Foundation library providing validation, utilities, and interceptor framework for the Boundary ecosystem.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {org.wagoe/wagoe-core {:mvn/version "1.0.0-beta-1"}}}
```

**Leiningen**:
```clojure
[org.wagoe/wagoe-core "1.0.0-beta-1"]
```

## Features

| Feature | Description |
|---------|-------------|
| **Validation Framework** | Malli-based validation with behavior-driven testing support |
| **Case Conversion** | Bidirectional kebab-case ↔ snake_case ↔ camelCase utilities |
| **Type Conversion** | Safe UUID, Instant, BigDecimal conversions with error handling |
| **PII Redaction** | Automatic sensitive data masking for logs and error reports |
| **Interceptor Pipeline** | Composable request/response interceptor framework |
| **Feature Flags** | Runtime feature toggle configuration |

## Requirements

- Clojure 1.12+
- No external dependencies beyond Malli

## Quick Start

### Case Conversion

```clojure
(ns myapp.core
  (:require [wagoe.core.utils.case-conversion :as case]))

;; kebab-case to snake_case (for database)
(case/kebab-case->snake-case-map {:user-id 123 :first-name "John"})
;; => {:user_id 123 :first_name "John"}

;; snake_case to kebab-case (from database)
(case/snake-case->kebab-case-map {:user_id 123 :first_name "John"})
;; => {:user-id 123 :first-name "John"}

;; kebab-case to camelCase (for JSON API)
(case/kebab-case->camel-case-map {:user-id 123 :first-name "John"})
;; => {:userId 123 :firstName "John"}
```

### Validation

```clojure
(ns myapp.validation
  (:require [wagoe.core.validation :as v]))

;; Define schema
(def user-schema
  [:map
   [:email [:re #"^[^@]+@[^@]+$"]]
   [:name [:string {:min 1 :max 100}]]
   [:age [:int {:min 0 :max 150}]]])

;; Validate data
(v/validate user-schema {:email "john@example.com" :name "John" :age 30})
;; => {:valid? true :data {...}}

(v/validate user-schema {:email "invalid" :name "" :age -1})
;; => {:valid? false :errors [...]}
```

### Type Conversion

```clojure
(ns myapp.types
  (:require [wagoe.core.utils.type-conversion :as tc]))

;; Safe UUID parsing
(tc/string->uuid "550e8400-e29b-41d4-a716-446655440000")
;; => #uuid "550e8400-e29b-41d4-a716-446655440000"

(tc/string->uuid "invalid")
;; => nil

;; Instant conversion
(tc/string->instant "2024-01-15T10:30:00Z")
;; => #inst "2024-01-15T10:30:00.000-00:00"
```

### PII Redaction

```clojure
(ns myapp.logging
  (:require [wagoe.core.utils.pii-redaction :as pii]))

;; Redact sensitive fields before logging
(pii/redact-map {:email "john@example.com" :password "secret123" :name "John"})
;; => {:email "[REDACTED]" :password "[REDACTED]" :name "John"}
```

## Module Structure

```
libs/core/src/wagoe/core/
├── validation.clj           # Malli validation utilities
├── interceptors/            # Interceptor framework
│   ├── core.clj
│   └── chain.clj
└── utils/
    ├── case-conversion.clj  # Case conversion utilities
    ├── type-conversion.clj  # Type parsing and conversion
    └── pii-redaction.clj    # Sensitive data handling
```

## Dependencies

This library has minimal dependencies:

| Dependency | Version | Purpose |
|------------|---------|---------|
| `metosin/malli` | 0.20.0 | Schema validation |

## Relationship to Other Libraries

`boundary/core` is the foundation layer with **no dependencies** on other Boundary libraries:

```
┌─────────────────────────────────────────┐
│           Other Boundary libs           │
│  (observability, platform, user, etc.)  │
└─────────────────┬───────────────────────┘
                  │ depends on
                  ▼
┌─────────────────────────────────────────┐
│            boundary/core                │
│   (validation, utils, interceptors)     │
└─────────────────────────────────────────┘
```

## Development

```bash
# Run tests
cd libs/core
clojure -M:test

# Lint
clojure -M:clj-kondo --lint src test

# Build JAR
clojure -T:build jar
```

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
