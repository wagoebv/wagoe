---
name: wagoe-i18n
description: Externalise user-visible strings in a Wagoe project into the translation catalogue and keep locales in parity. Use when adding UI text, when a string appears untranslated, when `bb i18n:scan` fails, or when asked to add or audit a language. Covers the `[:t :key]` marker convention, where markers may and may not appear, the en/nl catalogues, and what the scan does not see.
---

# Wagoe — internationalisation

Translation keys live as `[:t :key]` **data markers** inside Hiccup. A
shell-layer `postwalk` resolves them just before HTML is emitted, so `core/`
stays pure and never needs a locale (ADR-013).

```clojure
[:t :user/sign-in]                        ; lookup
[:t :user/greeting {:name "Alice"}]       ; interpolation — {name} in the value
[:t :user/items {:n 3} 3]                 ; plural, 4th arg is the count
```

```bash
bb i18n:scan      # CI gate — exits 1 on unexternalised literals
bb i18n:missing   # keys in en.edn absent from another locale
bb i18n:unused    # catalogue keys nothing references
bb i18n:find "Sign in"   # search catalogue and source together
```

Catalogues: `libs/i18n/resources/wagoe/i18n/translations/{en,nl}.edn`.

## Adding a string

1. Add the key to **`en.edn` first**, then `nl.edn`. `bb i18n:missing` compares
   the others against `en.edn`, so a key added only to `nl.edn` is invisible
   to it.
2. Use `[:t :key]` in the Hiccup instead of the literal.
3. `bb i18n:scan` — must exit 0.
4. `bb i18n:missing` — must report no gaps.

Namespace the key by module: `:common/*` for anything shared, otherwise
`:user/*`, `:admin/*`, `:search/*`, `:calendar/*`, `:workflow/*`.

## A marker works anywhere the value reaches Hiccup

Including places that do not look like markup. All of these resolve, because
the renderer walks the whole tree:

```clojure
[:nav {:aria-label [:t :common/aria-breadcrumb]} …]   ; attribute value
[:title [:t :user/detail-page-title {:name n}]]       ; page title
(ui/select-field :role [[:admin [:t :common/role-admin]] …] v)  ; option labels
(ui/badge [:t :search/badge-weight {:weight w}] opts) ; component argument
```

The test is not "is this Hiccup" but "does this value end up inside the tree
the shell renders". If a helper puts its argument into a vector, a marker
works; if it calls `str` on it, it does not — you would render the literal
text `[:t :some/key]` into the page.

**Markers nest.** Parameters resolve before the sentence that uses them, which
is how word order can change between languages:

```clojure
[:t :user/session-device {:browser [:t :user/browser-chrome]
                          :device  [:t :user/device-desktop]}]
;; en: "Chrome on Desktop"   nl: "Chrome op Desktop"
```

This is the right shape for a pure `core/` function that would otherwise return
an English sentence: return the marker, let the shell resolve it.

## What `bb i18n:scan` does not see

Treat a clean scan as one check passed, not as proof the UI is translated.

- **Only `**/core/ui.clj`.** Hiccup in `shell/web_handlers.clj`, admin views or
  anywhere else is never looked at.
- **Only capitalised prose** — `^[A-Z][A-Za-z ]{3,}`. `(str n " hours ago")` is
  unexternalised English and is not reported, because matching lowercase turns
  every map key and option name into a finding. When you touch a file, read it
  for lowercase fragments yourself.
- **Not `.js`, `.css`, `onclick` handlers or `confirm(...)` strings.** Inline
  JavaScript in an attribute is a string to the scanner.

It is a required CI job, and for a long time it could not fail: its docstring
filter was `(not (str/includes? line "\""))`, which no line holding a string
literal satisfies. Fixed, with tests that plant a violation.

A second false negative came from the same habit of reasoning per line rather
than per literal — a line with a marker on it had all its *other* literals
suppressed, which hid five real cases of

```clojure
(ui/submit-button [:t :user/button-update] {:loading-text "Updating..."})
```

Both are now decided from the enclosing form. If the scan reports OK on a file
you know has literals, check it is under `core/ui.clj` and that the string is
capitalised prose before assuming the gate is broken again.

## `bb i18n:unused` is a question, not an instruction

It reports keys nothing references. That has two causes and they need opposite
responses:

- **Genuinely dead** — delete from every locale.
- **Intended and never wired up** — the code still emits English. The
  `:user/browser-*` and `:user/device-*` keys sat "unused" for months while
  `parse-user-agent` returned the string `"Chrome on Desktop"`. The fix was to
  use the keys, not to delete them.

Before deleting, `bb i18n:find` the key and read the code that should have
used it. A key whose English value appears verbatim in a source file is the
second case.

Keys built at runtime (`(keyword "user" (str "browser-" x))`) would also read
as unused. Nothing in this repository does that today, but a dynamic lookup is
worth grepping for before a bulk delete.

## Steps

1. `bb i18n:scan` and `bb i18n:missing` first — know the starting point.
2. For each literal: add the key to `en.edn`, then `nl.edn`, then replace the
   literal with `[:t :key]`.
3. If the string is assembled with `str` in a `core/` function, move the
   sentence into the catalogue and pass the parts as parameters — do not
   concatenate translated fragments, word order is not universal.
4. `bb i18n:scan` — exits 0.
5. `bb i18n:missing` — no gaps.
6. Run the module's tests. Tests that assert on unresolved Hiccup will see the
   marker rather than the old text; update them to assert the key, which is
   what `core/` now produces.
7. `bb check` before committing.

## What this does not cover

Locale selection, middleware wiring and the plural rules live in
`libs/i18n/AGENTS.md`. Nothing here changes which locale a request gets — that
comes from the user's `:language` preference via the i18n middleware.
