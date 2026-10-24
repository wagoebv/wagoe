# wagoe/ui-style

Shared, framework-default UI style resources.

## UI Contract

All modules must consume style bundles from `wagoe.ui-style`.
Do not hardcode CSS file lists inside module/page namespaces.

Namespace/API:
- `wagoe.ui-style/pilot-css`
- `wagoe.ui-style/admin-pilot-css`
- `wagoe.ui-style/pilot-js`
- `wagoe.ui-style/admin-pilot-js`
- `(wagoe.ui-style/bundle :pilot|:admin-pilot)`
- `(wagoe.ui-style/js-bundle :pilot|:admin-pilot)`

Rules:
1. Forms are rendered with the same form classes and token variables across modules.
2. Tables are rendered with shared table classes and the same sort/hover/selection behavior.
3. Badges use shared badge classes and fixed-width variants where applicable.
4. Layout namespaces (`layout.clj`) decide which bundle to load; feature namespaces do not provide ad-hoc CSS stacks.
5. Theme overrides must be token-based (`wagoe-tokens.css`), not hardcoded colors in component CSS.

Recommended bundle usage:
- Everything but admin: `:pilot`, the default
- Admin CRUD pages: `:admin-pilot`
- Any other key, including the removed `:base`, serves `:pilot` and warns once

Example:
```clojure
(ns my.module.core.ui
  (:require [wagoe.shared.ui.core.layout :as layout]))

(defn page [opts]
  (layout/pilot-page-layout
   "My Page"
   [:div "Content"]
   opts))
```

Contains:
- Tailwind entries in `resources/tailwind/`, compiled into `resources/public/css/`:
  - `pilot.css` → `daisy-admin.css`, the `:pilot` bundle's daisyUI/Tailwind layer
  - `admin.css` → `wagoe-admin.css`, the `:admin-pilot` bundle's one stylesheet:
    fonts, tokens, `app.css`, `pilot-rules.css`, `user-menu.css` and
    `admin/wagoe-admin.css`, in that cascade order
- Shared CSS tokens/components:
  - `resources/public/css/wagoe-tokens.css`
  - `resources/public/css/app.css`
- Shared JavaScript assets:
  - `resources/public/js/theme.js`
  - `resources/public/js/alpine.min.js`
  - `resources/public/js/htmx.min.js`
  - `resources/public/js/htmx-ext-sse.min.js`
  - `resources/public/js/forms.js`
  - `resources/public/js/keyboard.js`

Build from monorepo root:

```bash
npm run build:css
```

Both compiled files are committed; CI fails when they are not what the
sources build.

## Module Migration Checklist

Use this checklist when onboarding a new module or converting an existing module UI:

1. Use shared layout entry points (`layout/page-layout`, `layout/pilot-page-layout`, `layout/admin-pilot-page-layout`), not custom HTML wrappers.
2. Pick one bundle key based on page type:
   - `:pilot` (the default) for everything but admin
   - `:admin-pilot` for admin CRUD pages
3. Do not define module-local `:css [...]` stacks in feature namespaces; let layout resolve bundles via `wagoe.ui-style`.
4. Render forms with shared form classes/components and token variables only (no hardcoded per-page colors).
5. Render tables with shared table classes/components so sort, hover, row states, and spacing stay consistent.
6. Use shared badge classes (including fixed-width badge variants where provided) for role/status/action/result labels.
7. If theme adjustments are needed, change `wagoe-tokens.css` instead of component one-offs.
