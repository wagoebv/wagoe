# Reports Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

PDF, Excel, and Word report generation with Hiccup-style templates and declarative sections. Saves 5–8 days of boilerplate per project. Supports sync generation and optional async via wagoe-jobs.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.reports.core.report` | Pure helpers (`format-cell*`, `map-columns*`, `build-table-rows*`, `build-sections-hiccup*`, `prepare-report`, `resolve-data`) |
| `wagoe.reports.shell.registry` | Definition registry + `defreport` macro (`register-report!`, `get-report`, `list-reports`, `clear-registry!`) — mutable process state, lives in the shell |
| `wagoe.reports.ports` | Protocol: `ReportGeneratorProtocol` (`generate!`, `supported-type?`) |
| `wagoe.reports.schema` | Malli schemas: `ColumnDef`, `SectionDef`, `ReportDefinition`, `ReportOutput` |
| `wagoe.reports.shell.adapters.pdf` | OpenHTMLtoPDF adapter — Hiccup → HTML → PDF bytes |
| `wagoe.reports.shell.adapters.excel` | docjure adapter — column defs + data → XLSX bytes |
| `wagoe.reports.shell.adapters.word` | Apache POI XWPF adapter — sections → DOCX bytes |
| `wagoe.reports.shell.service` | Public convenience API: `generate`, `generate-async` |
| `wagoe.reports.shell.jobs-integration` | Optional wagoe-jobs integration for async generation |

## `defreport` Macro Usage

### PDF report with a template function

```clojure
(require '[wagoe.reports.shell.registry :as registry])
(require '[wagoe.reports.shell.service :as reports])

(registry/defreport invoice-report
  {:id        :invoice-report
   :type      :pdf
   :page-size :a4
   :filename  "invoice.pdf"
   :template  (fn [data]
                [:html
                 [:head [:title "Invoice"]]
                 [:body
                  [:h1 "Invoice #" (:invoice-number data)]
                  [:table
                   [:thead [:tr [:th "Item"] [:th "Amount"]]]
                   [:tbody
                    (for [line (:lines data)]
                      [:tr [:td (:description line)]
                           [:td (str "€ " (:amount line))]])]]]]])})

;; Synchronous generation
(def output (reports/generate invoice-report {:invoice-id 42}))
;; => {:bytes #bytes[...] :type :pdf :filename "invoice.pdf"}

;; Serve from Ring handler
{:status  200
 :headers {"Content-Type"        "application/pdf"
           "Content-Disposition" "attachment; filename=\"invoice.pdf\""}
 :body    (java.io.ByteArrayInputStream. (:bytes output))}
```

### Excel report with declarative sections

```clojure
(registry/defreport sales-report
  {:id          :sales-report
   :type        :excel
   :filename    "sales.xlsx"
   :data-source (fn [opts]
                  ;; e.g. query DB by date range from opts
                  (db/find-sales (:from opts) (:to opts)))
   :sections    [{:type    :table
                  :columns [{:key :product  :label "Product"}
                             {:key :quantity :label "Qty"    :format :number}
                             {:key :revenue  :label "Revenue" :format :currency}
                             {:key :date     :label "Date"    :format :date}]}]})

;; Generate — :data-source is called with opts
(def output (reports/generate sales-report {:from "2026-01-01" :to "2026-03-31"}))
```

### Multi-sheet Excel with explicit :sheets

```clojure
(registry/defreport quarterly-report
  {:id     :quarterly-report
   :type   :excel
   :sheets [{:name    "Q1 Sales"
             :columns [{:key :name  :label "Product"}
                       {:key :total :label "Total" :format :currency}]
             :data    [{:name "Widget" :total 1200.00}]}
            {:name    "Summary"
             :columns [{:key :metric :label "Metric"}
                       {:key :value  :label "Value" :format :number}]
             :data    [{:metric "Total Revenue" :value 1200.00}]}]})
```

### Word report with declarative sections

```clojure
(registry/defreport contract-report
  {:id          :contract-report
   :type        :word
   :filename    "contract.docx"
   :data-source (fn [opts]
                  (db/find-contract (:contract-id opts)))
   :sections    [{:type    :header
                  :content "Service Agreement"}
                 {:type    :paragraph
                  :content "This agreement is entered into between the parties below."}
                 {:type    :table
                  :columns [{:key :party :label "Party"}
                             {:key :role  :label "Role"}]}
                 {:type :spacer}
                 {:type    :footer
                  :content "Confidential — not for distribution"}]})

;; Generate — :data-source is called with opts
(def output (reports/generate contract-report {:contract-id 7}))
;; => {:bytes #bytes[...] :type :word :filename "contract.docx"}
```

> **Note:** `:template` is PDF-only. Word reports always use the `:sections` API.

## HTTP Handler Integration

```clojure
(require '[wagoe.reports.shell.service :as reports])

(defn export-pdf-handler [request]
  (let [invoice-id (-> request :path-params :id parse-long)
        output     (reports/generate invoice-report {:invoice-id invoice-id})]
    {:status  200
     :headers {"Content-Type"        "application/pdf"
               "Content-Disposition" (str "attachment; filename=\"" (:filename output) "\"")}
     :body    (java.io.ByteArrayInputStream. (:bytes output))}))

(defn export-excel-handler [request]
  (let [opts   (-> request :query-params)
        output (reports/generate sales-report opts)]
    {:status  200
     :headers {"Content-Type"        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
               "Content-Disposition" (str "attachment; filename=\"" (:filename output) "\"")}
     :body    (java.io.ByteArrayInputStream. (:bytes output))}))

(defn export-word-handler [request]
  (let [contract-id (-> request :path-params :id parse-long)
        output      (reports/generate contract-report {:contract-id contract-id})]
    {:status  200
     :headers {"Content-Type"        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
               "Content-Disposition" (str "attachment; filename=\"" (:filename output) "\"")}
     :body    (java.io.ByteArrayInputStream. (:bytes output))}))
```

## Async Generation via wagoe-jobs

```clojure
(require '[wagoe.reports.shell.service :as reports])
(require '[wagoe.reports.shell.jobs-integration :as jobs])

;; Register handler at startup (in your Integrant system)
(jobs/register-report-job-handler! job-registry)

;; Queue in a handler
(defn trigger-report-handler [request]
  (let [job-id (reports/generate-async quarterly-report
                 {:from "2026-01-01" :to "2026-03-31"
                  :notify-email "finance@example.com"})]
    {:status 202
     :body   {:job-id job-id :message "Report queued"}}))
```

## Custom CSS for PDF

Pass `:css` in opts to append additional styles after the default stylesheet:

```clojure
(reports/generate invoice-report
  {:invoice-id 42
   :css "body { font-family: Georgia; } h1 { color: #003366; }"})
```

Or override `:page-size` in the report definition:
```clojure
{:id :wide-report :type :pdf :page-size :a4-landscape ...}
```

Set the matching CSS `@page { size: A4 landscape; }` in your custom CSS string.

## Supported Section Types

| Type | Required keys | PDF | Excel | Word | Description |
|------|--------------|-----|-------|------|-------------|
| `:header` | `:content` | Hiccup inside `<header>` | — | Heading1 paragraph | Section heading |
| `:paragraph` | `:content` (string) | — | — | Plain paragraph | Body text (Word-only) |
| `:table` | `:columns` (vector of ColumnDef) | `<table>` with header row | Worksheet rows | XWPF table | Data table |
| `:footer` | `:content` | Hiccup inside `<footer>` | — | Italic 8pt paragraph | Footer note |
| `:spacer` | — | `<div class="spacer">` (12pt) | — | Blank paragraph | Visual gap |

> **`:paragraph`** — Word-only section type. `:content` must be a plain string (not Hiccup).
> **`:template`** — PDF-only. Word and Excel reports always use `:sections`.

## Column Format Types

| Format | Input | Output |
|--------|-------|--------|
| `:string` | any | `(str value)` |
| `:number` | numeric | `(double value)` |
| `:currency` | numeric | `"€ 1.234,56"` (nl-NL locale) |
| `:date` | Date/Instant/LocalDate | `"2026-03-13"` (ISO-8601) |

## Common Pitfalls

1. **HTML entities in Hiccup** — use `&amp;`, `&lt;` etc. inside attribute strings;
   the hiccup renderer handles escaping of element content, but raw strings in attributes are passed through.

2. **Page-size CSS** — OpenHTMLtoPDF reads `@page { size: A4; }` from the CSS, NOT the `:page-size` key directly.
   The default CSS sets `size: A4`. Pass custom CSS with `:css` to override.

3. **FC/IS violation** — `generate` and `generate-async` live in `shell/service.clj`,
   NOT in `core/report.clj`. Core must stay pure. Do not import shell namespaces from core.

4. **Large reports** — for > 10k rows, use `:data-source` + async generation to avoid blocking
   the HTTP thread. `generate-async` queues a wagoe-jobs job.

5. **Missing :columns for Excel sections** — if no `:columns` key is found in the first `:table`
   section, `create-excel-generator` will attempt to map `nil` columns and produce an empty sheet.
   Always provide `:columns` in table sections when using declarative mode.

6. **docjure sheet names** — sheet names must be ≤ 31 characters and must not contain `[]:*?/\`.

## Testing

```bash
# All reports tests
clojure -M:test:db/h2 :reports

# Unit tests only (no backend required)
clojure -M:test:db/h2 --focus-meta :unit

# Integration tests (real PDF/Excel generation)
clojure -M:test:db/h2 --focus-meta :integration

# Lint
clojure -M:clj-kondo --lint libs/reports/src libs/reports/test
```

## REPL Smoke Check

```clojure
(require '[wagoe.reports.shell.registry :as registry])
(require '[wagoe.reports.shell.service :as reports])

(registry/defreport smoke-report
  {:id       :smoke-report
   :type     :pdf
   :template (fn [_] [:html [:body [:h1 "Smoke Test"]]])})

(def out (reports/generate smoke-report {}))
;; Verify magic bytes
(= (int \%) (aget ^bytes (:bytes out) 0))  ;; => true

;; Save to disk
(require '[clojure.java.io :as io])
(with-open [os (io/output-stream "/tmp/smoke.pdf")]
  (.write os (:bytes out)))
;; Open /tmp/smoke.pdf in a PDF viewer
```

## Links

- [Root AGENTS Guide](../../AGENTS.md)
- [OpenHTMLtoPDF](https://github.com/danfickle/openhtmltopdf)
- [docjure](https://github.com/mjul/docjure)
