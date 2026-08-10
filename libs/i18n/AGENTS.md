# wagoe-i18n — Internationalisation Library

`com.wagoe/wagoe-i18n` — ADR-013 marker-based i18n for Wagoe framework apps.

---

## Overview

Translation keys are expressed as `[:t :key]` data markers inside Hiccup trees.
A shell-layer `postwalk` (in `render.clj`) resolves them to strings before HTML is emitted.
No UI function signatures change — the translation function is injected via the Ring request map.

### Key design decisions

- **Pure core** — `wagoe.i18n.core.translate/t` has no I/O, no logging, no exceptions.
- **Marker syntax** — `[:t :key]` in Hiccup; resolved by `render/resolve-markers`.
- **Locale chain** — user locale → tenant locale → default locale → `:en` fallback → `(str key)`.
- **Graceful degradation** — missing key returns `(str key)` (e.g. `"user/sign-in"`), never throws.
- **EDN catalogues** — one file per locale: `wagoe/i18n/translations/en.edn`, `nl.edn`, etc.
- **Integrant component** — `:wagoe/i18n` loads catalogues at startup; injected into `:wagoe/http-handler`.

---

## Directory Layout

```
libs/i18n/
├── deps.edn
├── build.clj
├── resources/
│   └── wagoe/i18n/translations/
│       ├── en.edn          ← English catalogue (canonical)
│       └── nl.edn          ← Dutch catalogue
├── src/wagoe/i18n/
│   ├── schema.clj          ← Malli schema for I18nConfig
│   ├── ports.clj           ← ICatalogue protocol
│   ├── core/
│   │   └── translate.clj   ← Pure t/3 function
│   └── shell/
│       ├── catalogue.clj   ← load-catalogue, MapCatalogue
│       ├── middleware.clj  ← wrap-i18n Ring middleware
│       ├── render.clj      ← resolve-markers, render
│       └── module_wiring.clj  ← ig/init-key :wagoe/i18n
└── test/wagoe/i18n/
    ├── core/translate_test.clj
    └── shell/
        ├── render_test.clj
        └── catalogue_test.clj
```

---

## Marker Syntax

```clojure
;; Simple lookup
[:t :user/sign-in]

;; With interpolation params (map)
[:t :user/greeting {:name "Alice"}]

;; With plural count (4th element)
[:t :user/items {:n 3} 3]
```

Resolved by `wagoe.i18n.shell.render/resolve-markers` during `render`.

---

## Translation Function

`wagoe.i18n.core.translate/t` is a pure 3-5 arity function:

```clojure
(t catalogue locale-chain :user/sign-in)
(t catalogue locale-chain :user/greeting {:name "Alice"})
(t catalogue locale-chain :user/items {:n 3} 3)
```

`locale-chain` is built by `wrap-i18n` from: `[user-locale tenant-locale default :en]`.
In the current HTTP pipeline:

- user locale comes from `[:user :language]` (set by authentication middleware)
- tenant locale comes from `[:tenant :settings :language]`
- `[:tenant :default-language]` is also accepted as a backward-compatible fallback

---

## Catalogue Format

`en.edn` is a flat map of qualified keyword → string or plural map:

```clojure
{:user/sign-in    "Sign In"
 :user/greeting   {:one "Hello {name}" :many "Hello everyone"}
 :user/items      {:zero "No items" :one "{n} item" :many "{n} items"}
 :common/button-cancel "Cancel"}
```

**Namespacing conventions:**
- `:common/*` — shared across all modules (buttons, status labels, etc.)
- `:user/*` — user module UI
- `:admin/*` — admin module UI
- `:search/*` — search module UI
- `:calendar/*` — calendar module UI
- `:workflow/*` — workflow module UI

---

## Adding a New String — Workflow

1. **Add to `en.edn`** — choose the correct namespace, follow existing naming patterns.
2. **Add to `nl.edn`** — keep parity; run `bb i18n:missing` to check.
3. **Use marker in Hiccup** — `[:t :namespace/key]` instead of `"Hardcoded string"`.
4. **Run `bb i18n:scan`** — verifies no unexternalised literals remain in `core/ui.clj` files.

---

## Babashka Tooling

```bash
# Find a key by substring or exact keyword
bb i18n:find "Sign in"
bb i18n:find :user/sign-in

# Scan core/ui.clj files for unexternalised string literals (CI gate)
bb i18n:scan              # exits 0 if clean, 1 if violations found

# Report translation gaps (keys in en.edn but missing from other locales)
bb i18n:missing

# Report catalogue keys not referenced in any source file
bb i18n:unused
```

---

## Running Tests

```bash
# All i18n tests
clojure -M:test :i18n

# Unit tests only
clojure -M:test :i18n --focus-meta :unit

# Contract tests only (requires classpath i18n catalogues)
clojure -M:test :i18n --focus-meta :contract

# Linting
clojure -M:clj-kondo --lint libs/i18n/src libs/i18n/test
```

---

## Integrant Configuration

```edn
;; config.edn
:wagoe/i18n {:catalogue-path "wagoe/i18n/translations"
                :default-locale :en
                :dev?           true}   ; omit or false in production
```

The component exposes `{:catalogue cat :default-locale :en :dev? bool}`.

**Wiring it into the HTTP pipeline (BOU-131).** The middleware is now built by
this module as `:wagoe/i18n-http-middleware` and injected into
`:wagoe/http-handler` as `:i18n-middleware` — the shape BOU-200 established for
tenant. Platform no longer requires `wagoe.i18n.shell.middleware`, so an app
that translates nothing need not ship this library:

```clojure
:wagoe/i18n                 {:catalogue-path "wagoe/i18n/translations"
                             :default-locale :en}
:wagoe/i18n-http-middleware {:i18n (ig/ref :wagoe/i18n)}
:wagoe/http-handler         {… :i18n-middleware (ig/ref :wagoe/i18n-http-middleware)}
```

Passing the component directly as `:i18n` still works and logs a deprecation
warning. It is kept because dropping it would not error: handlers read
`(get request :i18n/t identity)`, so an upgraded app would silently render every
marker as its own keyword — `:user/sign-in` where "Sign in" belongs.

---

## Middleware

`wagoe.i18n.shell.middleware/wrap-i18n` injects into the request:

| Key | Type | Description |
|-----|------|-------------|
| `:i18n/locale-chain` | `[:nl :en]` | Ordered locales to try |
| `:i18n/t` | `(fn [key] ...)` | Translation function, also `(fn [key params])` and `(fn [key params n])` |

Handlers retrieve it via `(get request :i18n/t identity)`.

---

## Adding a New Locale

1. Add a new EDN file: `libs/i18n/resources/wagoe/i18n/translations/fr.edn`.
2. Update `load-catalogue` default locales if you want automatic discovery:
   ```clojure
   ;; shell/catalogue.clj
   (load-catalogue base-path [:en :nl :fr])
   ```
   Or pass it explicitly from config.
3. Run `bb i18n:missing` to see which keys need translating.

---

## Common Pitfalls

1. **Hiccup vs. HTML strings** — `render` passes strings through unchanged; markers only resolve in Hiccup vectors.
2. **`:i18n/t` not in request** — middleware must run before handlers; `(get request :i18n/t identity)` is only safe for plain strings. If a handler may render `[:t ...]` markers, prefer a 1/2/3-arity fallback function instead of bare `identity`.
3. **Namespace typos** — `:user/sign-i` (missing `n`) returns key string gracefully, but `bb i18n:unused` will flag it.
4. **Plural maps need `:many`** — `{:one "…"}` without `:many` will return `nil` for n>1 unless `:many` is present.
5. **Dev? mode** — when `:dev? true`, resolved markers are wrapped in `[:span {:data-i18n "..."}]` for browser inspection.
