# UI Style Library — Development Guide

> Central app-wide styling contract for Wagoe: named CSS/JS asset bundles,
> design tokens, and the shared static assets served to every page.
>
> For general conventions, testing commands, and FC/IS patterns, see the
> [root AGENTS.md](../../AGENTS.md).

## Purpose

`ui-style` is the single source of truth for **which** stylesheet and script
files a page loads and **in what order**. Layout namespaces pick a bundle key
(`:pilot`, the default, or `:admin-pilot`; `:base` is deprecated); feature namespaces never hardcode CSS/JS
lists. The library also ships the actual asset files (CSS, JS, fonts, icons)
under `resources/public/`, which the platform serves from the classpath.

This is a **thin, pure data library** — no core/shell/ports split, no Integrant
components, no schema. It is one namespace of vectors and two lookup functions,
plus a `resources/` asset tree.

## Key namespace

| Namespace | Responsibility |
|-----------|----------------|
| `wagoe.ui-style` | Bundle registries (`css-bundles`, `js-bundles`) + `bundle` / `js-bundle` lookup fns. This is the entire public API. |

## Public API

Bundle vars — each a vector of `/`-rooted asset paths, in load order:

| Var | Bundle | Contents (load order) |
|-----|--------|-----------------------|
| `base-css` | `:base` | deprecated: pico → wagoe-tokens → tokens-openprops → app |
| `pilot-css` | `:pilot` | wagoe-tokens → app → daisy-admin |
| `admin-pilot-css` | `:admin-pilot` | fonts → wagoe-tokens → admin → app → daisy-admin → wagoe-admin |
| `base-js` / `pilot-js` | `:base` / `:pilot` | theme → components → alpine → htmx → htmx-ext-sse → forms (`base-js` aliases `pilot-js`) |
| `admin-pilot-js` | `:admin-pilot` | theme → components → admin-ux → alpine → htmx → htmx-ext-sse → forms → keyboard |

Lookup functions:

```clojure
(require '[wagoe.ui-style :as ui-style])

(ui-style/bundle :pilot)          ;; => ["/css/wagoe-tokens.css" "/css/app.css" "/css/daisy-admin.css"]
(ui-style/js-bundle :admin-pilot) ;; => ["/js/theme.js" "/js/components.js" ...]

;; Unknown keys fall back to the :pilot stack (never nil):
(ui-style/bundle :nope)           ;; => pilot-css
(ui-style/js-bundle :nope)        ;; => pilot-js
```

Recommended bundle per page type:

- `:pilot` — the default; what `page-layout` loads
- `:admin-pilot` — admin CRUD pages
- `:base` — deprecated (pico + open-props), removed in 2.0

daisyUI/Tailwind on `wagoe-tokens.css` is the one CSS system. Pages on the
default layout carry `body.wagoe-default`, which gives bare headings, lists,
tables and buttons the styling pico used to (`resources/tailwind/admin-pilot.css`).

## Assets shipped (`resources/public/`)

- **CSS** (`css/`): `wagoe-tokens.css` (design tokens), `app.css`, `admin.css`,
  `daisy-admin.css` (Tailwind/daisyUI compiled output), `wagoe-admin.css`
  (wagoe.org tokens and the admin display-role styles, ADR-040), `fonts.css`, and
  the deprecated `pico.min.css`, `tokens-openprops.css` and `vendor/open-props/*`.
- **JS** (`js/`): `theme.js`, `components.js`, `admin-ux.js`, `forms.js`,
  `keyboard.js`, `init.js`, plus vendored `alpine.min.js`, `htmx.min.js` and
  `htmx-ext-sse.min.js` (htmx's `sse` extension, 2.2.4; must load after htmx).
- **Fonts** (`fonts/`): DM Sans, Inter, Space Grotesk + JetBrains Mono woff2.
- **Assets** (`assets/`): Wagoe light/dark logo + icon PNGs.
- **Tailwind source** (`resources/tailwind/admin-pilot.css`): input compiled to
  `css/daisy-admin.css`.

Paths in the bundle vectors are root-relative (`/css/...`, `/js/...`). The
platform serves them from the classpath via Ring `wrap-resource "public"`
(see `libs/platform/src/wagoe/platform/shell/http/reitit_router.clj`), so
`/css/app.css` resolves to `resources/public/css/app.css`.

## How it's consumed

Only layout namespaces reach for `ui-style`; feature code goes through the
shared layout entry points instead.

- `wagoe.shared.ui.core.layout` (lib `shared-ui`) binds `pilot-css`,
  `admin-pilot-css`, and the matching `*-js` vars from
  `ui-style/bundle` / `ui-style/js-bundle`, then injects them into the page
  `<head>`. Its `pilot-page-layout` / `admin-pilot-page-layout` helpers are what
  modules call.
- `wagoe.devtools.shell.dashboard.layout` uses `(ui-style/js-bundle :pilot)`.

Example (feature namespace — no CSS list of its own):

```clojure
(ns my.module.core.ui
  (:require [wagoe.shared.ui.core.layout :as layout]))

(defn page [opts]
  (layout/pilot-page-layout "My Page" [:div "Content"] opts))
```

## Config / wiring

None. No Integrant keys, no `config.edn` entries. Depend on the library and
require the namespace. `deps.edn` has a single runtime dep (`org.clojure/clojure`)
and `:paths ["src" "resources"]` so the assets travel with the jar.

## Common pitfalls

1. **Do not hardcode CSS/JS file lists in feature modules.** Choose a bundle key
   in the layout namespace; add/remove files only by editing the bundle vectors
   in `wagoe.ui-style`.
2. **JS load order is load-bearing.** `components.js` (and `admin-ux.js` for the
   admin bundle) must load *before* `alpine.min.js` — they register Alpine
   components/stores on the `alpine:init` event. Preserve the vector order when
   editing.
3. **Theme changes go in token files.** Adjust `wagoe-tokens.css`, not
   one-off colors in component CSS.
4. **`daisy-admin.css` is generated.** Edit `resources/tailwind/admin-pilot.css`
   and recompile (`npm run build:css:admin` from the monorepo root); do not
   hand-edit the compiled output.
5. **Bundle lookups never return nil** — unknown keys fall back to `:pilot`. Don't
   add nil-guards around `bundle` / `js-bundle`; a wrong key silently yields the
   default stack, so verify the key if styling looks off.
6. **New asset file → add it to a bundle vector**, otherwise it ships in the jar
   but is never referenced by any page.

## Testing

```bash
clojure -M:test :ui-style
```

Tests live in `test/wagoe/ui_style_test.clj` (tagged `^:unit`) and cover
bundle-key selection and the pilot fallback for unknown keys. When you add or
reorder a bundle, update the corresponding assertion.
