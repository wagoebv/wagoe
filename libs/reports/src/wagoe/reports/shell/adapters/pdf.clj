(ns wagoe.reports.shell.adapters.pdf
  "PDF generation adapter using OpenHTMLtoPDF.

   Three-step pipeline:
     1. Render Hiccup → HTML string (via hiccup2.core/html, which escapes
        strings; embed trusted markup with hiccup2.core/raw)
     2. Inject default CSS from resources/wagoe/reports/default.css
     3. Pass HTML to PdfRendererBuilder → ByteArrayOutputStream → bytes

   Usage:
     (def pdf-gen (create-pdf-generator))
     (generate! pdf-gen my-report-def data {})
     ;; => {:bytes #bytes[...] :type :pdf :filename \"my-report.pdf\"}"
  (:require [wagoe.reports.core.report :as core]
            [wagoe.reports.ports :as ports]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hiccup2.core :as h])
  (:import [com.openhtmltopdf.extend FSUriResolver]
           [com.openhtmltopdf.pdfboxout PdfRendererBuilder]
           [java.io ByteArrayOutputStream]
           [java.util IdentityHashMap]))

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
;; HTML rendering
;; =============================================================================

(def ^:private xml-illegal-chars
  ;; Characters XML 1.0 forbids even as references; the parser rejects them.
  #"[\x00-\x08\x0B\x0C\x0E-\x1F\uFFFE\uFFFF]|[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]")

(defn- render-html
  [hiccup-form]
  (-> (str "<!DOCTYPE html>" (h/html hiccup-form))
      (str/replace xml-illegal-chars "")))

;; =============================================================================
;; Untrusted data (BOU-687)
;; =============================================================================

(defn- data-colls
  "Every vector and map in data, by identity. A template that places one in
   its output is passing data through, not building markup."
  [data]
  (let [seen (IdentityHashMap.)]
    (letfn [(walk [x]
              (when (and (coll? x) (not (.containsKey seen x)))
                ;; Empty ones are shared instances, the same [] or {} a template writes.
                (when (and (or (vector? x) (map? x)) (seq x)) (.put seen x true))
                (run! walk (if (map? x) (mapcat identity x) x))))]
      (walk data))
    seen))

(defn- unsafe-attr? [k v]
  (let [k (str/lower-case (name k))]
    (or (str/starts-with? k "on")
        (and (= "href" k) (string? v)
             (str/starts-with? (str/lower-case (str/replace v #"[\x00-\x20]" "")) "javascript:")))))

(defn- neutralise
  "Render data vectors and maps as text, and drop on* attributes and
   javascript: hrefs. Template-built markup and h/raw pass through."
  [form ^IdentityHashMap data]
  (cond
    (.containsKey data form) (str form)
    (and (vector? form) (seq form))
    (let [[tag & more] form
          [attrs children] (if (and (map? (first more)) (not (.containsKey data (first more))))
                             [(into {} (remove (fn [[k v]] (unsafe-attr? k v))) (first more)) (rest more)]
                             [nil more])]
      (into (cond-> [tag] attrs (conj attrs)) (map #(neutralise % data)) children))
    (seq? form) (map #(neutralise % data) form)
    :else form))

(def ^:private data-uris-only
  "Resolves only data: URIs, so a report never fetches a URL or reads a file
   named in its content (BOU-675)."
  (reify FSUriResolver
    (resolveURI [_ _base uri]
      (when (and uri (.regionMatches ^String uri true 0 "data:" 0 5))
        uri))))

;; =============================================================================
;; OpenHTMLtoPDF generator record
;; =============================================================================

(defn- report-html [report-def data opts]
  (let [formatting-context {:zone-id (or (:zone-id opts) (java.time.ZoneId/systemDefault))}
        hiccup-form (if-let [tmpl (:template report-def)]
                      (tmpl data)
                      (core/build-sections-hiccup* (:sections report-def) data formatting-context))]
    (-> (neutralise hiccup-form (data-colls data))
        (inject-css (:css opts))
        render-html)))

(defrecord OpenHtmlToPdfGenerator [])

(extend-protocol ports/ReportGeneratorProtocol
  OpenHtmlToPdfGenerator

  (generate! [_this report-def data opts]
    (log/debug "Generating PDF report" {:id (:id report-def)})
    (let [html-str     (report-html report-def data opts)
          baos         (ByteArrayOutputStream.)
          builder      (doto (PdfRendererBuilder.)
                         (.useUriResolver data-uris-only)
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
