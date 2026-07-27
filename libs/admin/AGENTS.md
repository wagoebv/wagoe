# Admin Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Admin UI and CRUD management tooling for Wagoe entities, including HTMX-driven views, forms, and module-owned entity configuration.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.admin.core.ui` | Main admin UI rendering (tables, forms, layout composition) |
| `wagoe.shared.ui.core.icons` | Shared Lucide icon rendering used by admin and other modules |
| `wagoe.admin.shell.http` | Admin HTTP handlers and HTMX fragment endpoints |

## Admin Entity Configuration

Admin entity configurations are modular — each module owns its entity config in `resources/conf/{env}/admin/{module}.edn`.

**For the full configuration reference** (all keys, field types, filter operators, JOIN queries, has-many relations), see **[`README.md`](README.md)**.

```
resources/conf/dev/
├── config.edn              ← Main config, uses #include
└── admin/
    ├── users.edn
    ├── products.edn
    └── orders.edn
```

**Main config uses Aero's `#include`**:
```clojure
:wagoe/admin
{:enabled?         true
 :base-path        "/web/admin"
 :require-role     :admin
 :entity-discovery {:mode :allowlist :allowlist #{:users :products}}
 :entities         #merge [#include "admin/users.edn"
                           #include "admin/products.edn"]
 :pagination       {:default-page-size 20 :max-page-size 200}}
```

**Adding a new entity**: create `admin/{module}.edn` and add it to `:entities #merge [...]`.
Also add the entity keyword to `:entity-discovery :allowlist`.

---

## Column Widths in List View

List-view column widths are resolved in three layers:

**Layer 1 — type/name heuristic** (`list-column-weight` in `core/ui.clj`): width derived automatically from `:type` and field name. Default weights:

| Type / name pattern | Weight |
|---|---|
| `:boolean` | 1 |
| `:enum`, `:int`, `:decimal`, `:uuid`, `:json` | 2 |
| `:date`, `:instant` | 3 |
| `:string` + name matches description/bio/body/address/notes/comment/… | 6 |
| `:string` + name matches name/email/title/slug/url/label/… | 4 |
| `:string` (other) | 3 |
| `:text` | 6 |

**Layer 2 — manual `:width` override**: add `{:width N}` (positive int, min 1) to a field config to override the heuristic for that field. Use when the heuristic default is wrong for a specific domain field.

**Layer 3 — AI suggestion via `bb ai admin-entity`**: when generating an entity config, the AI reads the same defaults and only emits `:width` for fields whose semantics differ from layer 1 (e.g. `:sku` → `{:width 1}`, narrow code field). Prefer letting `bb ai admin-entity` set initial `:width` values; adjust manually if the rendered table needs further tuning.

```clojure
;; Layer 2 example — manually narrow a ref code column
{:ref {:type :string :label "Ref" :width 1}}

;; No :width needed — heuristic already gives :description weight 6
{:description {:type :string :label "Description"}}
```

---

## Common Configuration Gotchas

### 1. Entity Not Visible in Sidebar

**Cause**: Entity keyword not in `:entity-discovery :allowlist`.

```clojure
;; ❌ WRONG — products.edn exists but entity is not in allowlist
:entity-discovery {:mode :allowlist :allowlist #{:users}}

;; ✅ CORRECT
:entity-discovery {:mode :allowlist :allowlist #{:users :products}}
```

### 2. Filters Not Appearing for a Field

**Cause**: Field is not listed in `:fields` with `:filterable true`.

```clojure
;; ❌ WRONG — field exists but no filter in dropdown
{:status {:type :enum :label "Status"}}

;; ✅ CORRECT
{:status {:type :enum :label "Status" :filterable true
          :options [[:active "Active"] [:inactive "Inactive"]]}}
```

Note: `:filterable false` explicitly disables a field from the filter dropdown (use this for fields covered by free-text `:search-fields`).

### 3. query-overrides — WHERE Clause Hits Wrong Table

**Cause**: `:field-aliases` missing for a field used in search/filter/sort.

When you define `:query-overrides`, all fields used in `WHERE` or `ORDER BY` clauses must be listed in `:field-aliases` pointing to their qualified `table.column` reference. Without this, the query uses the bare keyword (e.g. `:email`) which is ambiguous when two tables have the same column name.

```clojure
;; ❌ WRONG — :email used in search but not aliased → ambiguous column error
:query-overrides
{:from   [[:auth_users :a]]
 :join   [[:users :u] [:= :a.id :u.id]]
 :select [:a.id :a.email :u.name]}
;; :search-fields [:email] → generates WHERE email ILIKE ... → ambiguous!

;; ✅ CORRECT — alias every field used in search/filter/sort
:query-overrides
{:from          [[:auth_users :a]]
 :join          [[:users :u] [:= :a.id :u.id]]
 :select        [:a.id :a.email :u.name]
 :field-aliases {:id    :a.id
                 :email :a.email
                 :name  :u.name}}
```

### 4. soft-delete with query-overrides — Wrong Table Updated

**Cause**: `:soft-delete-table` not specified when using `:query-overrides`.

The service soft-deletes by running `UPDATE <table> SET deleted_at = ? WHERE id = ?`. With query-overrides the primary table is inferred from `:from`, but if `deleted_at` lives on a different table (e.g. `auth_users` not `users`), you must specify it explicitly.

```clojure
:query-overrides
{:from              [[:auth_users :a]]
 :join              [[:users :u] [:= :a.id :u.id]]
 ...
 :soft-delete-table :auth_users}   ; ← required when deleted_at is not in first :from table
```

### 5. has-many — Child Not Shown

**Checklist**:
- `:entity` key must match the entity keyword in `:entity-discovery :allowlist`
- `:table` must be the actual DB table name (snake_case keyword)
- `:foreign-key` is the FK column on the **child** table (kebab-case)

```clojure
:has-many
[{:entity      :order-items    ; must be in allowlist
  :table       :order_items    ; DB table (snake_case)
  :foreign-key :order-id       ; FK on order_items table (kebab-case)
  :label       "Order Items"
  :fields      [:product-name :quantity]}]
```

---

## UI/Frontend Development

### Technology Stack

| Technology | Purpose | Location |
|------------|---------|----------|
| **Hiccup** | HTML generation | `libs/{library}/src/wagoe/{library}/core/ui.clj` |
| **HTMX** | Dynamic interactions | Inline attributes in Hiccup |
| **Pico CSS** | Base framework | `libs/ui-style/resources/public/css/` |
| **Lucide Icons** | Icon system | `libs/shared-ui/src/wagoe/shared/ui/core/icons.clj` |

### UI Architecture Principles

1. **Server-side rendering**: All HTML generated via Hiccup (no build step)
2. **Progressive enhancement**: HTMX for dynamic behavior
3. **Design tokens**: Stable contract in `libs/ui-style/resources/public/css/boundary-tokens.css` with optional theme override in `libs/ui-style/resources/public/css/tokens-openprops.css`
4. **Icon library**: Use Lucide icons, never emoji in UI (CLI emoji is OK)
5. **Bundle contract**: Use `wagoe.ui-style` bundles (via shared layout fns), never ad-hoc `:css [...]` lists in feature namespaces

### Common UI Patterns

#### REPL Reload for UI Changes

```bash
# After modifying any ui.clj file
clj-nrepl-eval -p <port> "(require '[integrant.repl :as ig-repl]) (ig-repl/reset)"
```

**Important**: UI changes require REPL reload to take effect.

#### JavaScript in Hiccup Attributes

```clojure
;; ❌ WRONG - Inconsistent or broken logic
[:input {:type "checkbox"
         :onchange "if (this.checked) { /* count all */ } else { /* show 0 */ }"}]

;; ❌ ALSO WRONG - Queries before DOM updates complete
[:input {:type "checkbox"
         :onchange "elements.forEach(el => el.checked = this.checked);
                    const checked = document.querySelectorAll('input:checked').length;
                    document.getElementById('count').textContent = checked + ' selected';"}]

;; ✅ CORRECT - Use setTimeout to query AFTER DOM updates
[:input {:type "checkbox"
         :onchange "elements.forEach(el => el.checked = this.checked);
                    setTimeout(() => {
                      const checked = document.querySelectorAll('input:checked').length;
                      document.getElementById('count').textContent = checked + ' selected';
                    }, 0);"}]
```

**Key Lesson**: When toggling multiple elements, always query the actual DOM state AFTER the update completes. Use `setTimeout(..., 0)` to defer the query to the next event loop tick, ensuring all `.checked` properties are updated first.

#### Icon Usage

```clojure
;; ❌ WRONG - Using emoji
[:button "🗑️ Delete"]

;; ✅ CORRECT - Using Lucide icons
[:button
 (icons/icon :trash {:size 18})
 " Delete"]

;; Available in: libs/shared-ui/src/wagoe/shared/ui/core/icons.clj
```

#### HTMX Loading States

```clojure
;; Add loading indicators to forms
[:form {:hx-post "/api/endpoint"
        :hx-indicator "#spinner"}
 [:button "Submit"]
 [:span#spinner.htmx-indicator "Loading..."]]
```

#### CSRF Tokens in Forms

State-changing requests are CSRF-protected (see platform AGENTS.md → CSRF Protection).

- **HTMX forms** (`hx-post`/`hx-put`/`hx-delete`) — nothing to do; the global
  `htmx:configRequest` listener attaches `X-CSRF-Token` from the page `<meta>` tag.
- **Plain `[:form {:method "post"}]`** — splice `(csrf/hidden-field)` as the first
  child (`require [wagoe.platform.core.csrf :as csrf]`). It reads the token bound
  for the current request, so no threading is needed:

```clojure
[:form {:method "post" :action "/web/logout"}
 (csrf/hidden-field)
 [:button "Logout"]]
```

### UI Component Hierarchy

```
libs/admin/src/wagoe/
├── shared/ui/core/
│   ├── layout.clj        # Page layouts, navigation
│   ├── icons.clj         # Icon definitions (50+ Lucide icons)
│   └── components.clj    # Reusable components
├── admin/core/
│   └── ui.clj            # Admin interface (tables, forms)

libs/{module}/src/wagoe/{module}/core/
└── ui.clj                # Module-specific UI components
```

### CSS Architecture

**Location**: `libs/ui-style/resources/public/css/`

```
css/
├── boundary-tokens.css          # Layer 2 — stable token contract (Wagoe defaults)
├── tokens-openprops.css         # Layer 3 — optional theme override (Cyberpunk Professionalism)
├── vendor/open-props/           # Vendored Open Props v1.7.23 (no CDN dependency)
│   ├── colors.min.css           #   Named color scales (--indigo-4, --lime-6, etc.)
│   ├── shadows.min.css          #   Shadow scale
│   ├── gradients.min.css        #   Gradient presets
│   ├── animations.min.css       #   Animation keyframes
│   ├── easings.min.css          #   Easing functions (--ease-out-3, --ease-spring-3)
│   ├── borders.min.css          #   Border radii (--radius-round)
│   └── sizes.min.css            #   Spacing scale (--size-1 … --size-fluid-5)
├── app.css                      # Layer 4 — component styles (uses tokens only)
└── admin.css                    # Layer 4 — admin interface styles (uses tokens only)
```

**Loading order** (set as default in `page-layout`):
```
pico.min.css          ← CSS reset / base HTML element styles
boundary-tokens.css   ← Token defaults (Wagoe navy/green palette)
tokens-openprops.css  ← Theme override (Cyberpunk Professionalism; load last to win)
app.css               ← Component styles
```

Use central bundle keys from `wagoe.ui-style`:
- `:base` for legacy/base pages
- `:pilot` for pilot user/profile/audit pages
- `:admin-pilot` for admin CRUD pages

**CSS Organisation Rules**:
1. Component CSS (`app.css`, `admin.css`) must only reference token variables — never hardcode values
2. All token variables are defined in `boundary-tokens.css`; the optional theme file overrides them
3. Dark mode is handled via `[data-theme="dark"]` and `@media (prefers-color-scheme: dark)` in both token files — no duplicate declarations in component CSS
4. Open Props vendor files are imported only by `tokens-openprops.css` — component CSS never imports them directly

**Example — writing component CSS**:
```css
/* ✅ Use design tokens */
.button {
  padding: var(--space-2) var(--space-4);
  background: var(--color-primary);
  border-radius: var(--radius-md);
  box-shadow: var(--glow-primary);        /* off in default theme, active in Cyberpunk */
  transition: all var(--transition-normal);
}

/* ❌ Don't hardcode values */
.button {
  padding: 8px 16px;
  background: #3b82f6;
  border-radius: 6px;
}
```

### Theming System

The theming system has two layers:

| File | Role |
|------|------|
| `boundary-tokens.css` | **Token contract** — every variable component CSS can reference. Neutral Wagoe navy/green defaults. Self-contained (no imports, works offline and in JAR deployments). |
| `tokens-openprops.css` | **Theme override** — re-assigns the same variables to the "Cyberpunk Professionalism" palette (indigo primary, lime accent, neon glows, gradients). Imports vendored Open Props for its named color scale. |

Because `tokens-openprops.css` is loaded after `boundary-tokens.css`, it wins on every variable it touches. Variables it doesn't touch keep their defaults from `boundary-tokens.css`.

**What Open Props provides** (vendored at `vendor/open-props/`):
- Named color scales: `--indigo-0` … `--indigo-12`, `--lime-0` … `--lime-12`, etc., each with HSL variants (`--indigo-4-hsl`)
- Shadow scale: `--shadow-2` … `--shadow-6`
- Easing functions: `--ease-out-1` … `--ease-out-5`, `--ease-spring-1` … `--ease-spring-5`
- Spacing: `--size-1` … `--size-fluid-5`
- Border radii: `--radius-round` (9999px)

**Reference** — tokens available for use in component CSS:

```
Colors       --color-primary, --color-accent, --color-secondary
             --color-success / -hover / -bg / -border
             --color-warning / -hover / -bg / -border
             --color-error   / -hover / -bg / -border
             --color-info    / -hover / -bg / -border
             --color-neutral-50 … --color-neutral-800

Surfaces     --surface-0 (base) … --surface-4 (elevated)
Text         --text-primary, --text-muted, --text-faint, --text-inverse
Borders      --border-default, --border-strong, --border-focus, --border-accent

Effects      --shadow-sm, --shadow-md, --shadow-lg, --shadow-xl, --shadow-2xl
             --shadow-focus
             --glow-primary, --glow-primary-strong (none in default, neon in Cyberpunk)
             --glow-accent, --glow-error, --glow-warning, --glow-info
             --gradient-hero, --gradient-accent, --gradient-subtle, --gradient-card

Typography   --font-sans, --font-display, --font-mono
             --text-xs … --text-4xl
             --font-normal, --font-medium, --font-semibold, --font-bold
             --leading-tight, --leading-normal, --leading-relaxed

Spacing      --space-0 … --space-20
Layout       --nav-width, --topbar-height, --content-max-width, --table-row-height
Radii        --radius-sm, --radius-md, --radius-lg, --radius-xl, --radius-full
Transitions  --transition-fast, --transition-normal, --transition-slow, --transition-bounce
Z-index      --z-dropdown … --z-toast
```

### Rolling Your Own Theme

To replace "Cyberpunk Professionalism" with a custom look:

**Option A — Edit `tokens-openprops.css` in place**

Override whichever variables you want; leave the rest untouched (they fall back to `boundary-tokens.css` defaults):
```css
/* my-theme additions inside tokens-openprops.css :root block */
--color-primary: #0d9488;          /* Teal instead of Indigo */
--color-accent:  #f59e0b;          /* Amber instead of Lime  */
--glow-primary:  none;             /* Remove neon glows      */
```

**Option B — Add a separate theme file**

1. Create `libs/ui-style/resources/public/css/my-brand.css`:
```css
/* Brand theme — overrides boundary-tokens.css defaults */
/* Import Open Props if you want its color scales */
@import "./vendor/open-props/colors.min.css";

:root {
  --color-primary:       var(--teal-6);
  --color-primary-hover: var(--teal-7);
  --color-accent:        var(--amber-6);
  /* ...override only what you need... */
}

[data-theme="dark"] {
  --color-primary: var(--teal-4);
  --color-accent:  var(--amber-4);
}
```

2. Pass it to `page-layout` via the `:css` opt (replaces the default list):
```clojure
(layout/page-layout
  "My Page" content
  {:css ["/css/pico.min.css"
         "/css/boundary-tokens.css"
         "/css/my-brand.css"          ; your theme, replaces tokens-openprops.css
         "/css/app.css"]})
```

**Option C — Remove the theme entirely**

Drop `tokens-openprops.css` from the `:css` list to get the default Wagoe navy/green palette from `boundary-tokens.css` with no Open Props dependency:
```clojure
{:css ["/css/pico.min.css" "/css/boundary-tokens.css" "/css/app.css"]}
```

**Tips for custom themes**:
- You only need to override variables that differ from your defaults; unoverridden variables cascade from `boundary-tokens.css`
- Always provide both `:root` and `[data-theme="dark"]` blocks for dark mode support
- Set `--glow-*: none` to disable neon glows in professional/corporate themes
- The `--font-sans` / `--font-display` tokens control typefaces — pair with a CDN or self-hosted font `@font-face`

---

## Gotchas

### 1. Checkbox value mismatch — `"true"` not `"on"`

**Problem**: Handler checks `(= "on" (get form-data "field"))` but checkbox sends `"true"`.

**Root Cause**: `ui/checkbox` renders `value="true"` (not `"on"`). With the hidden+checkbox pattern, a checked box sends both `"false"` and `"true"` — Ring parses as vector `["false" "true"]`.

**Solution**: Always handle all cases:
```clojure
(let [v (get form-data "send-welcome")]
  (or (= "on" v) (= "true" v)
      (and (sequential? v) (some #{"on" "true"} v))))
```

### 2. Form Parsing - Array Values from Checkboxes

**Problem**: Checkboxes using hidden field + checkbox pattern submit as arrays `["false", "true"]`.

**Symptom**: `ClassCastException: PersistentVector cannot be cast to CharSequence`

**Root Cause**: HTML forms with this pattern:
```html
<input type="hidden" name="active" value="false">
<input type="checkbox" name="active" value="true" checked>
```
Both values are submitted when checkbox is checked, resulting in an array.

**Solution**: Normalize array values in form parsing:
```clojure
(defn parse-form-params [params entity-config]
  (reduce-kv
    (fn [acc field-name value]
      (let [; Handle array values - take the last value
            normalized-value (if (vector? value)
                              (last value)
                              value)]
        ; ... rest of parsing logic using normalized-value
        ))
    {}
    params))
```

**Prevention**: Always assume form values might be arrays (Ring can submit multiple values for same field name).

### 2. Flash Messages - Map vs Sequence Confusion

**Problem**: Iterating over flash message map produces invalid Hiccup.

**Symptom**: `IllegalArgumentException: No implementation of method: :write-body-to-stream`

**Root Cause**: Code expecting sequence of messages, but receiving single map:
```clojure
;; ❌ WRONG - Iterating over map structure
(for [[type message] flash]  ; flash is {:type :error :message "..."}
  [:div {:class (str "alert-" type)} message])
;; Produces: [:div {:class "alert-type"} :error] [:div {:class "alert-message"} "..."]
```

**Solution**: Access map keys directly:
```clojure
;; ✅ CORRECT - Direct map access
(when flash
  [:div {:class (str "alert alert-" (name (:type flash)))}
   (:message flash)])
```

**Prevention**: Be explicit about data structure contracts. If flash is a map, document it and access it as a map. Don't iterate over maps unless you truly want key-value pairs.

### 3. HTMX Target Mismatch - Fragment Nesting

**Problem**: HTMX replaces target with response that contains duplicate parent elements.

**Symptom**: Clicking table header to sort adds extra filter box to page.

**Root Cause**:
- HTMX targets `#entity-table-container`
- Server returns `#filter-table-container` (which contains filter builder + table)
- HTMX replaces table with entire filter-table container
- Result: Nested duplicate filter builders

**Solution**: Ensure HTMX response matches the target selector:
```clojure
;; ❌ WRONG - Returning parent container when targeting child
(defn table-fragment-handler [request]
  (htmx-fragment-response
    [:div#filter-table-container     ; Parent
     (render-filter-builder ...)     ; Filter
     [:div#entity-table-container    ; Child (the actual target)
      (render-table ...)]]))

;; ✅ CORRECT - Return exactly what's targeted
(defn table-fragment-handler [request]
  (htmx-fragment-response
    [:div#entity-table-container     ; Match the hx-target
     (render-table ...)]))
```

**Prevention**:
- HTMX target selector should match the root element of the response
- Use `hx-target="#foo"` → response should have `[:div#foo ...]` as root
- Keep filter UI outside HTMX-updated regions if it shouldn't change

### 4. Split-Table Entities Missing `:create-redirect-url`

**Problem**: Clicking "New" on a split-table entity in admin crashes with `StreamableResponseBody` / `PersistentArrayMap` error.

**Symptom**: `java.lang.IllegalArgumentException: No implementation of method: :write-body-to-stream of protocol: #'ring.core.protocols/StreamableResponseBody found for class: clojure.lang.PersistentArrayMap`

**Root Cause**: When an entity has `:split-table-update` but no `:create-redirect-url`, the admin renders its own generic create form. On submit, `create-entity` throws `:cannot-create-split-table-entity` because the generic flow only writes to one table. The exception bubbles to Reitit's `exception/exception-middleware`, which returns a map body. Since the browser sent `Accept: text/html`, Muuntaja doesn't JSON-encode the map — Ring gets a raw `PersistentArrayMap` as body and crashes.

**Solution**: Always pair `:split-table-update` with `:create-redirect-url`:
```clojure
;; ❌ WRONG — split-table without create redirect
{:users
 {:split-table-update {:secondary-table  :auth_users
                       :secondary-fields #{:email :active}}}}

;; ✅ CORRECT — redirect to module's dedicated create flow
{:users
 {:create-redirect-url "/web/users/new"
  :split-table-update  {:secondary-table  :auth_users
                        :secondary-fields #{:email :active}}}}
```

**Prevention**: The admin `new-entity-handler` now validates this at startup and throws a clear config error if the combination is missing.

### 5. Direct Navigation to HTMX Fragment Endpoints

**Problem**: Refreshing page on HTMX fragment URL shows unstyled HTML.

**Symptom**: URL changes to `/web/admin/users/table?sort=...`, page loses CSS.

**Root Cause**: HTMX `hx-push-url` updates browser history with fragment endpoint URLs. When user refreshes, browser requests fragment (HTML without layout) as a full page.

**Solution**: Detect non-HTMX requests and redirect to full page:
```clojure
(defn entity-table-fragment-handler [request]
  (let [is-htmx? (get-in request [:headers "hx-request"])]
    ; Redirect non-HTMX requests back to full page
    (when-not is-htmx?
      (let [query-string (:query-string request)
            redirect-url (str "/web/admin/users"
                             (when query-string (str "?" query-string)))]
        (throw (ex-info "Redirect to full page"
                        {:type :redirect
                         :location redirect-url
                         :status 303}))))
    ; ... normal fragment response
    ))
```

**Prevention**:
- Always check `hx-request` header in fragment-only endpoints
- Redirect non-HTMX requests to corresponding full page routes
- Preserve query parameters in redirect for proper state restoration
- Ensure error interceptor handles `:redirect` type with proper HTTP redirects

---

## Additional UI Notes

- Keep inline JavaScript short. Move multi-step logic to `libs/ui-style/resources/public/js/`.
- Keep related event handlers consistent (`select-all` and item checkboxes should compute counts the same way).
- Use Lucide icons in UI; avoid emoji in interface components.

---

## Testing

```bash
clojure -M:test:db/h2 :admin
```

### UI Testing Checklist

When making UI changes, always test:

- [ ] **Desktop view** (1920x1080)
- [ ] **Mobile view** (375x667)
- [ ] **Dark mode** (toggle and verify all elements)
- [ ] **Keyboard navigation** (Tab, Enter, Escape)
- [ ] **Loading states** (HTMX indicators work)
- [ ] **Form validation** (error messages display)
- [ ] **JavaScript interactions** (event handlers work correctly)

## UI Development Workflow

1. **Modify Hiccup** in `src/{module}/core/ui.clj`
2. **Reload REPL** via `clj-nrepl-eval -p <port> "(ig-repl/reset)"`
3. **Refresh browser** (Cmd+R / Ctrl+R)
4. **Test in both light and dark mode**
5. **Test responsive behavior** (resize window)
6. **Commit changes** (after explicit user permission)

## Links

- [Library README](README.md) — full entity configuration reference (all keys, field types, filters, JOINs, has-many)
- [Root AGENTS Guide](../../AGENTS.md)
