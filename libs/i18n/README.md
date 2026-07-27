# boundary/i18n

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/org.wagoe/wagoe-i18n.svg)](https://clojars.org/org.wagoe/wagoe-i18n)

Marker-based internationalisation for Boundary apps — translation keys live as `[:t :key]` data markers in Hiccup and are resolved to strings before HTML is emitted (ADR-013).

## Installation

**deps.edn** (recommended):
```clojure
{:deps {org.wagoe/wagoe-i18n {:mvn/version "1.0.0-beta-1"}}}
```

**Leiningen**:
```clojure
[org.wagoe/wagoe-i18n "1.0.0-beta-1"]
```

## Features

| Feature | Description |
|---------|-------------|
| **Marker syntax** | `[:t :key]` data markers embedded in Hiccup trees — no UI signature changes |
| **Interpolation** | Named params via a map: `[:t :key {:name "Alice"}]` → `"Hello {name}"` |
| **Plurals** | Count-aware lookup with `:zero`/`:one`/`:many` catalogue entries |
| **Locale chains** | user → tenant → default → `:en` fallback, then `(str key)` — never throws |
| **EDN catalogues** | One flat map per locale (`en.edn`, `nl.edn`, …) on the classpath |
| **`bb i18n` tooling** | `find`, `scan`, `missing`, `unused` for catalogue hygiene and CI gating |

## Quick Start

### Markers in Hiccup

```clojure
;; Simple lookup
[:t :user/sign-in]

;; With interpolation params (map)
[:t :user/greeting {:name "Alice"}]

;; With plural count (4th element)
[:t :user/items {:n 3} 3]
```

Markers are resolved by `wagoe.i18n.shell.render/resolve-markers` during `render`.
The translation function `wagoe.i18n.core.translate/t` is pure (no I/O, no exceptions):

```clojure
(t catalogue locale-chain :user/sign-in)
(t catalogue locale-chain :user/greeting {:name "Alice"})
(t catalogue locale-chain :user/items {:n 3} 3)
```

### Catalogue entries

`en.edn` is a flat map of qualified keyword → string or plural map:

```clojure
{:user/sign-in    "Sign In"
 :user/greeting   {:one "Hello {name}" :many "Hello everyone"}
 :user/items      {:zero "No items" :one "{n} item" :many "{n} items"}
 :common/button-cancel "Cancel"}
```

### Babashka tooling

```bash
bb i18n:find "Sign in"     # find a key by substring or exact keyword
bb i18n:scan               # CI gate — exit 1 if unexternalised literals remain
bb i18n:missing            # report keys present in en.edn but missing elsewhere
bb i18n:unused             # report catalogue keys not referenced in any source
```

## Documentation

- [AGENTS.md](AGENTS.md) — module reference: middleware wiring, locale chains, catalogue format, common pitfalls.
- [i18n library guide](../../docs/modules/libraries/pages/i18n.adoc) — narrative documentation.
- Governed by **ADR-013** (marker-based i18n).

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
