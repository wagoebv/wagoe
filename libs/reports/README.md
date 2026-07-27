# boundary/reports

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/org.wagoe/wagoe-reports.svg)](https://clojars.org/org.wagoe/wagoe-reports)

Report generation library for the Wagoe Framework — produce PDF, Excel, and Word (DOCX) documents from a single declarative definition.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {org.wagoe/wagoe-reports {:mvn/version "1.0.0-beta-1"}}}
```

**Leiningen**:
```clojure
[org.wagoe/wagoe-reports "1.0.0-beta-1"]
```

## Features

| Feature | Description |
|---------|-------------|
| **PDF generation** | Hiccup → HTML → PDF via OpenHTMLtoPDF; custom CSS, page sizes |
| **Excel generation** | Column-based XLSX via docjure; single and multi-sheet support |
| **Word generation** | Declarative DOCX via Apache POI XWPF; no extra Maven deps |
| **`defreport` macro** | Register reports as data in an in-process registry (`wagoe.reports.shell.registry`) |
| **Declarative sections** | `:header`, `:paragraph`, `:table`, `:footer`, `:spacer` section types |
| **Column formats** | `:string`, `:number`, `:currency` (nl-NL), `:date` (ISO-8601) |
| **Async generation** | Optional wagoe-jobs integration for large, background reports |
| **FC/IS pattern** | Pure `core/` functions; all I/O in `shell/` adapters |

## Requirements

- Clojure 1.12+
- No additional Maven dependencies for Word (POI XWPF is a transitive dep via docjure)

## Quick Start

### PDF report with a template function

```clojure
(require '[wagoe.reports.shell.registry :as registry]
         '[wagoe.reports.shell.service :as reports])

(registry/defreport invoice-report
  {:id        :invoice-report
   :type      :pdf
   :page-size :a4
   :filename  "invoice.pdf"
   :template  (fn [data]
                [:html
                 [:body
                  [:h1 "Invoice #" (:invoice-number data)]
                  [:table
                   [:thead [:tr [:th "Item"] [:th "Amount"]]]
                   [:tbody
                    (for [line (:lines data)]
                      [:tr [:td (:description line)]
                           [:td (str "€ " (:amount line))]])]]]])})

(def output (reports/generate invoice-report {:invoice-id 42}))
;; => {:bytes #bytes[...] :type :pdf :filename "invoice.pdf"}

;; Serve from a Ring handler
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
   :data-source (fn [opts] (db/find-sales (:from opts) (:to opts)))
   :sections    [{:type    :table
                  :columns [{:key :product  :label "Product"}
                             {:key :quantity :label "Qty"    :format :number}
                             {:key :revenue  :label "Revenue" :format :currency}
                             {:key :date     :label "Date"    :format :date}]}]})

(def output (reports/generate sales-report {:from "2026-01-01" :to "2026-03-31"}))
;; => {:bytes #bytes[...] :type :excel :filename "sales.xlsx"}
```

### Word report with declarative sections

```clojure
(registry/defreport contract-report
  {:id          :contract-report
   :type        :word
   :filename    "contract.docx"
   :data-source (fn [opts] (db/find-contract (:contract-id opts)))
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

(def output (reports/generate contract-report {:contract-id 7}))
;; => {:bytes #bytes[...] :type :word :filename "contract.docx"}
```

### HTTP Content-Types

```clojure
;; PDF
{"Content-Type" "application/pdf"}

;; Excel
{"Content-Type" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}

;; Word
{"Content-Type" "application/vnd.openxmlformats-officedocument.wordprocessingml.document"}
```

### Async generation (requires wagoe-jobs)

```clojure
(require '[wagoe.reports.shell.service :as reports])

(defn export-handler [request]
  (let [job-id (reports/generate-async sales-report
                 {:from "2026-01-01" :to "2026-03-31"
                  :notify-email "finance@example.com"})]
    {:status 202
     :body   {:job-id job-id :message "Report queued"}}))
```

## Supported Section Types

| Type | Required keys | PDF | Excel | Word |
|------|--------------|-----|-------|------|
| `:header` | `:content` | Hiccup inside `<header>` | — | Heading1 paragraph |
| `:paragraph` | `:content` (string) | — | — | Plain paragraph |
| `:table` | `:columns` (vector of ColumnDef) | `<table>` | Worksheet rows | XWPF table |
| `:footer` | `:content` | Hiccup inside `<footer>` | — | Italic 8pt paragraph |
| `:spacer` | — | `<div class="spacer">` | — | Blank paragraph |

> `:template` (fn) is PDF-only. Excel and Word always use the `:sections` API.

## Module Structure

```
libs/reports/src/wagoe/reports/
├── core/
│   └── report.clj               # pure helpers (formatting, sections, prepare)
├── ports.clj                    # ReportGeneratorProtocol
├── schema.clj                   # Malli schemas
└── shell/
    ├── registry.clj            # defreport macro + in-process registry
    ├── adapters/
    │   ├── pdf.clj              # OpenHTMLtoPDF adapter
    │   ├── excel.clj            # docjure (Apache POI) adapter
    │   └── word.clj             # Apache POI XWPF adapter
    ├── service.clj              # Public API: generate, generate-async
    └── jobs_integration.clj     # Optional wagoe-jobs integration
```

## Protocol

```clojure
(defprotocol ReportGeneratorProtocol
  (generate! [this report-def data opts]
    "Generate report bytes. Returns {:bytes ... :type ... :filename ...}")
  (supported-type? [this t]
    "Return true if this generator handles report type t."))
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `com.openhtmltopdf/openhtmltopdf-pdfbox` | 1.0.10 | Hiccup → HTML → PDF |
| `dk.ative/docjure` | 1.14.0 | Excel generation (also brings in POI XWPF) |
| `metosin/malli` | 0.20.0 | Schema validation |
| `hiccup/hiccup` | 2.0.0 | HTML rendering for PDF templates |

Apache POI XWPF (Word) is included as a transitive dependency of docjure — no extra entry in `deps.edn` is required.

## Relationship to Other Libraries

```
┌─────────────────────────────────────────┐
│            Your Application             │
└─────────────────┬───────────────────────┘
                  │ uses
                  ▼
┌─────────────────────────────────────────┐
│            boundary/reports             │
│       (PDF, Excel, Word generation)     │
└──────────┬──────────────────────────────┘
           │ optional
           ▼
┌─────────────────────────────────────────┐
│             boundary/jobs               │
│      (async generation, retry)          │
└─────────────────────────────────────────┘
```

## Development

```bash
# Run all reports tests
clojure -M:test:db/h2 :reports

# Lint
clojure -M:clj-kondo --lint libs/reports/src libs/reports/test
```

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
