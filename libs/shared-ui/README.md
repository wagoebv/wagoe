# wagoe/shared-ui

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-shared-ui.svg)](https://clojars.org/com.wagoe/wagoe-shared-ui)

Hiccup primitives shared by the libraries that render HTML — admin, user,
search, calendar and workflow all draw their forms, tables and buttons from
here, so a Wagoe application looks like one application.

Pure functions from data to Hiccup. No I/O, no state, no HTTP.

## Installation

**deps.edn**:
```clojure
{:deps {com.wagoe/wagoe-shared-ui {:mvn/version "1.0.0-rc-1"}}}
```

## Features

| Namespace | What it holds |
|-----------|---------------|
| `core.components` | Form controls — text, email, password, textarea, select, checkbox, button |
| `core.table` | Sortable headers, pagination, and the query-param plumbing behind them |
| `core.layout` | Page scaffolding shared by the module UIs |
| `core.icons` | The icon set those components reference |
| `core.alpine` | Alpine.js attribute helpers, for the interactions HTMX does not cover |
| `core.validation` | Rendering of field errors, so every module reports them the same way |

## Quick Start

```clojure
(require '[wagoe.shared.ui.core.components :as ui])

;; Each input takes the field key and its current value, then an options map.
;; form-field wraps one in its label and any validation errors.
[:form {:method "post" :action "/users"}
 (ui/form-field :name "Name"
                (ui/text-input :name (:name params) {:required true})
                (:name errors))
 (ui/form-field :email "Email"
                (ui/email-input :email (:email params))
                (:email errors))
 (ui/button "Save" {:type "submit"})]
```

## A note on the public surface

This library has no `ports.clj` and no schema, and it is the one place where
`core.*` *is* the supported API — there is nothing else to depend on. Treat
those functions the way you would a protocol; see
[Stability & Versioning](../../docs/modules/ROOT/pages/stability.adoc).

## Testing

```bash
clojure -M:test :shared-ui
```

## Documentation

- [shared-ui library guide](../../docs/modules/libraries/pages/shared-ui.adoc) — narrative documentation.
- [ui-style](../ui-style/README.md) — the CSS and JS bundles these components are styled by.

This library has no `AGENTS.md`: its whole surface is the namespaces listed
above, and the guide covers them.

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
