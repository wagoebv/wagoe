# UI Style Library — Development Guide

> Central app-wide styling contract for Wagoe: named CSS/JS asset bundles,
> design tokens, and the shared static assets served to every page.
>
> For general conventions, testing commands, and FC/IS patterns, see the
> [root AGENTS.md](../../AGENTS.md).

## Purpose

`ui-style` is the single source of truth for **which** stylesheet and script
files a page loads and **in what order**. Layout namespaces pick a bundle key
(`:base` / `:pilot` / `:admin-pilot`); feature namespaces never hardcode CSS/JS
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
| `base-css` | `:base` | pico → boundary-tokens → tokens-openprops → app |
| `pilot-css` | `:pilot` | boundary-tokens → app → daisy-admin |
| `admin-pilot-css` | `:admin-pilot` | fonts → boundary-tokens → admin → app → daisy-admin |
| `base-js` / `pilot-js` | `:base` / `:pilot` | theme → components → alpine → htmx (`pilot-js` aliases `base-js`) |
| `admin-pilot-js` | `:admin-pilot` | theme → components → admin-ux → alpine → htmx → forms → keyboard |

Lookup functions:

```clojure
(require '[wagoe.ui-style :as ui-style])

(ui-style/bundle :pilot)          ;; => ["/css/boundary-tokens.css" "/css/app.css" "/css/daisy-admin.css"]
(ui-style/js-bundle :admin-pilot) ;; => ["/js/theme.js" "/js/components.js" ...]

;; Unknown keys fall back to the :base stack (never nil):
(ui-style/bundle :nope)           ;; => base-css
(ui-style/js-bundle :nope)        ;; => base-js
```

Recommended bundle per page type:

- `:base` — public / auth / basic pages
- `:pilot` — user / profile / audit "pilot" pages
- `:admin-pilot` — admin CRUD pages

## Assets shipped (`resources/public/`)

- **CSS** (`css/`): `pico.min.css` (vendored), `boundary-tokens.css` +
  `tokens-openprops.css` (design tokens), `app.css`, `admin.css`,
  `daisy-admin.css` (Tailwind/daisyUI compiled output), `fonts.css`, and
  `vendor/open-props/*`.
- **JS** (`js/`): `theme.js`, `components.js`, `admin-ux.js`, `forms.js`,
  `keyboard.js`, `init.js`, plus vendored `alpine.min.js` and `htmx.min.js`.
- **Fonts** (`fonts/`): DM Sans + JetBrains Mono woff2.
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

- `wagoe.shared.ui.core.layout` (lib `shared-ui`) binds `default-css`,
  `pilot-css`, `admin-pilot-css`, and the matching `*-js` vars from
  `ui-style/bundle` / `ui-style/js-bundle`, then injects them into the page
  `<head>`. Its `pilot-page-layout` / `admin-pilot-page-layout` helpers are what
  modules call.
- `wagoe.devtools.shell.dashboard.layout` uses `(ui-style/js-bundle :base)`.

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
3. **Theme changes go in token files.** Adjust `boundary-tokens.css` /
   `tokens-openprops.css`, not one-off colors in component CSS.
4. **`daisy-admin.css` is generated.** Edit `resources/tailwind/admin-pilot.css`
   and recompile (`npm run build:css:admin` from the monorepo root); do not
   hand-edit the compiled output.
5. **Bundle lookups never return nil** — unknown keys fall back to `:base`. Don't
   add nil-guards around `bundle` / `js-bundle`; a wrong key silently yields the
   base stack, so verify the key if styling looks off.
6. **New asset file → add it to a bundle vector**, otherwise it ships in the jar
   but is never referenced by any page.

## Testing

```bash
clojure -M:test:db/h2 :ui-style
```

Tests live in `test/wagoe/ui_style_test.clj` (tagged `^:unit`) and cover
bundle-key selection and the base-fallback for unknown keys. When you add or
reorder a bundle, update the corresponding assertion.
