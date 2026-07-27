(ns wagoe.reports.shell.adapters.pdf
  "PDF generation adapter using OpenHTMLtoPDF.

   Three-step pipeline:
     1. Render Hiccup → HTML string (via hiccup.core/html)
     2. Inject default CSS from resources/wagoe/reports/default.css
     3. Pass HTML to PdfRendererBuilder → ByteArrayOutputStream → bytes

   Usage:
     (def pdf-gen (create-pdf-generator))
     (generate! pdf-gen my-report-def data {})
     ;; => {:bytes #bytes[...] :type :pdf :filename \"my-report.pdf\"}"
  (:require [wagoe.reports.core.report :as core]
            [wagoe.reports.ports :as ports]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [hiccup.core :as h])
  (:import [com.openhtmltopdf.pdfboxout PdfRendererBuilder]
           [java.io ByteArrayOutputStream]))

;; =============================================================================
;; CSS helper
;; =============================================================================

(defn- default-css
  "Read the bundled default.css from the classpath. Returns a CSS string."
  []
  (if-let [url (io/resource "wagoe/reports/default.css")]
    (slurp url)
    ""))

(defn- inject-css
  "Wrap hiccup body content with an HTML5 document that includes the CSS.

   If hiccup-form is already [:html ...] we inject a <style> tag into <head>.
   Otherwise we wrap the content in [:html [:head ...] [:body ...]].

   css-override (optional string): additional CSS appended after default styles."
  [hiccup-form css-override]
  (let [css     (str (default-css) "\n" (or css-override ""))
        style   [:style {:type "text/css"} css]]
    (if (and (vector? hiccup-form) (= :html (first hiccup-form)))
      ;; Already an :html root — inject <style> into <head>
      (let [[tag & children] hiccup-form
            head-idx (first (keep-indexed (fn [i c]
                                            (when (and (vector? c) (= :head (first c))) i))
                                          children))]
        (if head-idx
          (into [tag]
                (map-indexed (fn [i c]
                               (if (= i head-idx)
                                 (conj c style)
                                 c))
                             children))
          ;; No <head> found — prepend one
          (into [tag [:head style]] children)))
      ;; Plain hiccup — wrap in full HTML document
      [:html [:head style] [:body hiccup-form]])))

;; =============================================================================
;; OpenHTMLtoPDF generator record
;; =============================================================================

(defrecord OpenHtmlToPdfGenerator [])

(extend-protocol ports/ReportGeneratorProtocol
  OpenHtmlToPdfGenerator

  (generate! [_this report-def data opts]
    (log/debug "Generating PDF report" {:id (:id report-def)})
    (let [css-override (:css opts)
          formatting-context {:zone-id (or (:zone-id opts) (java.time.ZoneId/systemDefault))}
          hiccup-form  (if-let [tmpl (:template report-def)]
                         (tmpl data)
                         (core/build-sections-hiccup* (:sections report-def) data formatting-context))
          full-hiccup  (inject-css hiccup-form css-override)
          html-str     (str "<!DOCTYPE html>" (h/html full-hiccup))
          baos         (ByteArrayOutputStream.)
          builder      (doto (PdfRendererBuilder.)
                         (.withHtmlContent html-str nil)
                         (.toStream baos))]
      (.run builder)
      (let [result {:bytes    (.toByteArray baos)
                    :type     :pdf
                    :filename (or (:filename report-def)
                                  (str (name (:id report-def)) ".pdf"))}]
        (log/debug "PDF report generated"
                   {:id       (:id report-def)
                    :filename (:filename result)
                    :bytes    (count (:bytes result))})
        result)))

  (supported-type? [_ t] (= t :pdf)))

(defn create-pdf-generator
  "Create a new OpenHTMLtoPDF-backed PDF generator."
  []
  (->OpenHtmlToPdfGenerator))
