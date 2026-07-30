(ns wagoe.reports.schema
  "Malli validation schemas for the reports module."
  (:require [malli.core :as m]))

;; =============================================================================
;; Column Definition
;; =============================================================================

(def ColumnDef
  "Column definition for table sections."
  [:map
   [:key    :keyword]
   [:label  :string]
   [:width  {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:format {:optional true} [:enum :string :date :number :currency]]
   [:align  {:optional true} [:enum :left :center :right]]])

;; =============================================================================
;; Section Definition
;; =============================================================================

(def SectionDef
  "Section types for declarative reports."
  [:map
   [:type    [:enum :header :table :footer :spacer :paragraph]]
   [:content {:optional true} :any]                       ; Hiccup for header/footer
   [:columns {:optional true} [:vector ColumnDef]]])      ; for :table type

;; =============================================================================
;; Report Definition
;; =============================================================================

(def ReportDefinition
  "Top-level report definition.
   Either :template (fn [data] -> hiccup) or :sections (declarative) must be provided."
  [:map
   [:id          :keyword]
   [:type        [:enum :pdf :excel :word]]
   [:page-size   {:optional true} [:enum :a4 :a4-landscape :a3 :letter]]
   [:filename    {:optional true} :string]
   [:template    {:optional true} fn?]       ; (fn [data] -> hiccup)
   [:sections    {:optional true} [:vector SectionDef]]
   [:data-source {:optional true} fn?]])     ; (fn [opts] -> coll)

;; =============================================================================
;; Report Output
;; =============================================================================

(def ReportOutput
  "Result of a report generation call."
  [:map
   [:bytes    bytes?]
   [:type     [:enum :pdf :excel :word]]
   [:filename :string]])

;; =============================================================================
;; Validation helpers
;; =============================================================================

(def ^:private report-definition-validator (m/validator ReportDefinition))
(def ^:private report-definition-explainer (m/explainer ReportDefinition))
(def ^:private report-output-validator (m/validator ReportOutput))

(defn valid-report-def?
  "Returns true if the given map satisfies ReportDefinition schema."
  [report-def]
  (report-definition-validator report-def))

(defn explain-report-def
  "Returns human-readable validation errors for a report definition."
  [report-def]
  (report-definition-explainer report-def))

(defn valid-report-output?
  "Returns true if the given map satisfies ReportOutput schema."
  [output]
  (report-output-validator output))
