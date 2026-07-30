(ns wagoe.reports.core.report
  "Pure report logic: cell/row formatting, section rendering, and report
   preparation.

   The definition registry and the `defreport` macro live in the shell
   (wagoe.reports.shell.registry) — this namespace holds no mutable state.

   FC/IS rule: no I/O here. All side effects (file writing, HTTP responses,
   calling :data-source) live in the shell layer."
  (:require [wagoe.reports.schema :as schema]))

;; =============================================================================
;; Pure cell / row helpers
;; =============================================================================

(defn format-cell*
  "Format a single cell value according to format-type.

   Supported format-types:
     :date     - converts java.util.Date / java.time.Instant / LocalDate to ISO string
     :number   - coerces to double, falls back to \"\" on nil
     :currency - formats as EUR with thousands separator, e.g. \"€ 1.234,56\"
     :string   - calls str on value (default)
     nil       - treated as :string

   Returns a string or number suitable for use in Hiccup or POI cells."
  [value format-type {:keys [zone-id]}]
  (case format-type
    :date
    (cond
      (nil? value) ""
      (instance? java.util.Date value)
      (.format java.time.format.DateTimeFormatter/ISO_LOCAL_DATE
               (.toLocalDate (.atZone (.toInstant value)
                                      zone-id)))
      (instance? java.time.Instant value)
      (.format java.time.format.DateTimeFormatter/ISO_LOCAL_DATE
               (.atZone value zone-id))
      (instance? java.time.LocalDate value)
      (.format value java.time.format.DateTimeFormatter/ISO_LOCAL_DATE)
      :else (str value))

    :number
    (if (nil? value) 0.0 (double value))

    :currency
    (if (nil? value)
      "€ 0,00"
      (let [amount (double value)
            fmt    (java.text.NumberFormat/getInstance (java.util.Locale/forLanguageTag "nl-NL"))
            _      (doto fmt
                     (.setMinimumFractionDigits 2)
                     (.setMaximumFractionDigits 2))]
        (str "€ " (.format fmt amount))))

    ;; :string or nil
    (if (nil? value) "" (str value))))

(defn map-columns*
  "Map a single data record through a vector of ColumnDef maps.

   Returns a vector of formatted values in column order.

   Example:
     (map-columns* [{:key :name :label \"Name\"} {:key :price :label \"Price\" :format :currency}]
                  {:name \"Widget\" :price 9.99})
     ;=> [\"Widget\" \"€ 9,99\"]"
  [columns record formatting-context]
  (mapv (fn [{:keys [key format]}]
          (format-cell* (get record key) format formatting-context))
        columns))

(defn build-table-rows*
  "Build a Hiccup [:tbody ...] from a collection of data records and column defs.

   Each record becomes a [:tr ...] with one [:td ...] per column.
   Applies :align as an inline style when present.

   Returns:
     [:tbody [:tr [:td ...] ...] ...]"
  [columns data formatting-context]
  (into [:tbody]
        (map (fn [record]
               (into [:tr]
                     (map (fn [{:keys [key format align]}]
                            (let [style (when align
                                          (str "text-align:" (name align)))]
                              [:td (cond-> {} style (assoc :style style))
                               (format-cell* (get record key) format formatting-context)]))
                          columns)))
             data)))

(defn build-sections-hiccup*
  "Build Hiccup from a vector of SectionDef maps and the report data.

   Supports section types:
     :header  - renders :content Hiccup as-is inside a [:header ...]
     :table   - renders :columns + data as a table
     :footer  - renders :content Hiccup as-is inside a [:footer ...]
     :spacer  - renders an empty [:div.spacer ...]

   Returns:
     [:html [:head ...] [:body ...]]"
  [sections data formatting-context]
  (let [body-content
        (mapv (fn [{:keys [type content columns]}]
                (case type
                  :header  [:header content]
                  :table   (let [header-row (into [:tr]
                                                  (map (fn [{:keys [label align]}]
                                                         (let [style (when align
                                                                       (str "text-align:" (name align)))]
                                                           [:th (cond-> {} style (assoc :style style))
                                                            label]))
                                                       columns))]
                             [:table
                              [:thead header-row]
                              (build-table-rows* columns data formatting-context)])
                  :footer  [:footer content]
                  :spacer  [:div.spacer]))
              sections)]
    [:html
     [:head
      [:meta {:charset "UTF-8"}]]
     (into [:body] body-content)]))

;; =============================================================================
;; Report preparation (pure)
;; =============================================================================

(defn resolve-data
  "Call the :data-source fn with opts, returning the result.
   Returns nil if no :data-source is defined.

   NOTE: This is a side-effecting call (queries DB, calls API, etc.).
   It lives here as a named helper so shell code can call it explicitly
   and tests can stub the :data-source fn."
  [report-def opts]
  (when-let [ds (:data-source report-def)]
    (ds opts)))

(defn prepare-report
  "Validate a report definition and return {:definition ... :errors ...}.

   Pure — does NOT call :data-source or generate bytes.
   Returns:
     {:definition report-def  :valid? true  :errors []}
     {:definition report-def  :valid? false :errors [...malli-errors...]}"
  [report-def]
  (let [errors (schema/explain-report-def report-def)]
    {:definition report-def
     :valid?     (nil? errors)
     :errors     (or (:errors errors) [])}))
