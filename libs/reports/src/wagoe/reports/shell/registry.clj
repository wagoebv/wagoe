(ns wagoe.reports.shell.registry
  "Load-time registry of report definitions.

   The registry is mutable process state, so it lives in the shell — the core
   (wagoe.reports.core.report) stays pure (report logic only). Definitions
   are registered at namespace load via the `defreport` macro and read at
   runtime by the report service/adapters.")

;; =============================================================================
;; Global definition registry (in-process)
;; =============================================================================

(defonce ^:private registry-atom (atom {}))

;; =============================================================================
;; Registry operations
;; =============================================================================

(defn register-report!
  "Register a report definition in the in-process registry.

   Args:
     definition - ReportDefinition map

   Returns the definition map."
  [definition]
  (swap! registry-atom assoc (:id definition) definition)
  definition)

(defn get-report
  "Look up a report definition by id.

   Returns the definition map or nil if not found."
  [id]
  (get @registry-atom id))

(defn list-reports
  "Return a vector of all registered report ids."
  []
  (vec (keys @registry-atom)))

(defn clear-registry!
  "Reset the registry to an empty map.

   Use in tests to avoid inter-test pollution."
  []
  (reset! registry-atom {}))

;; =============================================================================
;; defreport macro
;; =============================================================================

(defmacro defreport
  "Define and register a report.

   The body is a map literal that must satisfy ReportDefinition schema.
   After macro expansion the definition is automatically registered in the
   in-process registry so it is available via `get-report`.

   Example (PDF with template fn):

     (defreport invoice-report
       {:id        :invoice-report
        :type      :pdf
        :page-size :a4
        :filename  \"invoice.pdf\"
        :template  (fn [data]
                     [:html
                      [:body
                       [:h1 \"Invoice #\" (:invoice-number data)]
                       [:p \"Total: \" (:total data)]]])})

   Example (Excel with declarative sections):

     (defreport sales-report
       {:id       :sales-report
        :type     :excel
        :filename \"sales.xlsx\"
        :sections [{:type    :table
                    :columns [{:key :name  :label \"Product\"}
                               {:key :qty   :label \"Qty\"    :format :number}
                               {:key :price :label \"Price\"  :format :currency}]}]})

   The var `invoice-report` is bound to the definition map.
   The report is registered under :invoice-report."
  [sym definition-map]
  `(do
     (def ~sym ~definition-map)
     (register-report! ~sym)
     ~sym))
