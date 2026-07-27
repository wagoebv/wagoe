(ns wagoe.admin.core.ui
  "Admin UI components using Hiccup for server-side HTML generation.

   This namespace is a thin FACADE: the implementation lives in focused
   sibling namespaces under `wagoe.admin.core.ui.*`. Every public function
   consumers rely on is re-exported here so `admin-ui/entity-list-page`,
   `ui/format-field-label`, etc. keep resolving unchanged.

   Implementation namespaces:
   - `wagoe.admin.core.ui.base`           — shared URL/field/column primitives
   - `wagoe.admin.core.ui.layout`         — shell, sidebar, home, error pages, dialog
   - `wagoe.admin.core.ui.list`           — entity list page, table, rows, search
   - `wagoe.admin.core.ui.filters`        — advanced filter builder
   - `wagoe.admin.core.ui.inline-editing` — inline (double-click) cell editing
   - `wagoe.admin.core.ui.detail`         — detail/edit page, form, field grouping

   See each impl namespace for the original docstrings on these functions."
  (:require [wagoe.admin.core.ui.base :as base]
            [wagoe.admin.core.ui.layout :as layout]
            [wagoe.admin.core.ui.list :as list]
            [wagoe.admin.core.ui.filters :as filters]
            [wagoe.admin.core.ui.inline-editing :as inline-editing]
            [wagoe.admin.core.ui.detail :as detail]))

;; =============================================================================
;; Re-exports (facade). Each `def` points at its focused implementation so
;; existing consumers (shell.http via `admin-ui/`, tests via `ui/`) keep
;; resolving these vars unchanged.
;; =============================================================================

;; --- base: URL helpers, field-value rendering, column sizing, utilities ---
(def entity-create-url base/entity-create-url)
(def render-field-value base/render-field-value)
(def list-column-weight base/list-column-weight)
(def list-column-styles base/list-column-styles)
(def format-field-label base/format-field-label)
(def get-field-errors base/get-field-errors)

;; --- layout: shell, sidebar, home, error pages, confirmation dialog, url util ---
(def admin-sidebar layout/admin-sidebar)
(def admin-shell layout/admin-shell)
(def admin-layout layout/admin-layout)
(def admin-home layout/admin-home)
(def confirm-delete-dialog layout/confirm-delete-dialog)
(def admin-forbidden-page layout/admin-forbidden-page)
(def admin-not-found-page layout/admin-not-found-page)
(def build-table-url layout/build-table-url)

;; --- list: search form, table row, table, list page ---
(def entity-search-form list/entity-search-form)
(def entity-table-row list/entity-table-row)
(def entity-table list/entity-table)
(def entity-list-page list/entity-list-page)

;; --- filters: advanced filter builder ---
(def get-operators-for-field-type filters/get-operators-for-field-type)
(def render-filter-value-inputs filters/render-filter-value-inputs)
(def render-filter-row filters/render-filter-row)
(def render-filter-builder filters/render-filter-builder)

;; --- inline-editing: double-click cell editing ---
(def render-inline-edit-cell inline-editing/render-inline-edit-cell)
(def render-inline-edit-form inline-editing/render-inline-edit-form)
(def render-inline-edit-form-with-error inline-editing/render-inline-edit-form-with-error)

;; --- detail: form widget, entity form, detail/new pages, related tables ---
(def render-field-widget detail/render-field-widget)
(def entity-form detail/entity-form)
(def parent-context-banner detail/parent-context-banner)
(def related-records-table detail/related-records-table)
(def entity-detail-page detail/entity-detail-page)
(def entity-new-page detail/entity-new-page)
