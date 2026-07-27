(ns wagoe.devtools.core.error-formatter
  "Rich error output formatting for development mode.
   Pure functions — catalog data loaded once at namespace init via wagoe.devtools.error-codes."
  (:require [wagoe.devtools.error-codes :as codes]
            [wagoe.devtools.core.stacktrace :as stacktrace]
            [clojure.string :as str]))

;; =============================================================================
;; Error formatting
;; =============================================================================

(defn- separator [code title]
  (let [header  (str "\u2501\u2501\u2501 " code ": " title " ")
        padding (max 0 (- 65 (count header)))]
    (str header (apply str (repeat padding "\u2501")))))

(defn format-error
  "Format a rich error message for development output.
   `error-info` is a map with:
     :code       - BND-xxx error code string
     :handler    - handler keyword (optional)
     :schema     - schema name (optional)
     :errors     - seq of {:field :message :suggestion} maps (optional)
     :input      - the input data that caused the error (optional)
     :example    - example valid input (optional)
     :fix        - fix suggestion string (optional)
     :dashboard  - dashboard URL (optional)
     :docs       - documentation URL (optional)"
  [{:keys [code handler schema errors input example fix dashboard docs]}]
  (let [error-def  (codes/lookup code)
        title      (or (:title error-def) "Unknown Error")
        lines      (cond-> [(separator code title)]
                     handler (conj (str "Handler:  " handler))
                     schema  (conj (str "Schema:   " schema))
                     true    (conj "")
                     (seq errors)
                     (into (mapcat (fn [{:keys [field message suggestion]}]
                                     (cond-> [(str "  " field " \u2192 " message)]
                                       suggestion (conj (str "              " suggestion))))
                                   errors))
                     (seq errors) (conj "")
                     input   (conj (str "Input received:\n  " (pr-str input)))
                     input   (conj "")
                     example (conj (str "Expected shape (example):\n  " (pr-str example)))
                     example (conj "")
                     fix     (conj (str "Fix: " fix))
                     (or fix (:fix error-def))
                     (conj (when-not fix (str "Fix: " (:fix error-def))))
                     dashboard (conj (str "\nDashboard: " dashboard))
                     docs      (conj (str "Docs: " docs))
                     true    (conj (apply str (repeat 65 "\u2501"))))]
    (str/join "\n" (remove nil? lines))))

(defn format-config-error
  "Format a configuration error with specific fix instructions.
   `error-info` is a map with:
     :code      - BND-1xx error code
     :config-key - the config key path (e.g. :boundary/user-service :jwt-secret)
     :expected   - what was expected (e.g. \"#env JWT_SECRET\")
     :found      - what was found (e.g. nil)
     :reason     - why this is needed
     :fix        - specific fix instructions"
  [{:keys [code config-key expected found reason fix]}]
  (let [error-def (codes/lookup code)
        title     (or (:title error-def) "Configuration Error")]
    (str (separator code title) "\n"
         "Config key:  " config-key "\n"
         "Expected:    " expected "\n"
         "Found:       " (pr-str found) "\n"
         "\n"
         reason "\n"
         "\n"
         "Fix: " fix "\n"
         "\n"
         "     Run 'bb doctor' to check all config requirements.\n"
         (apply str (repeat 65 "\u2501")))))

(defn format-fcis-violation
  "Format an FC/IS boundary violation with refactoring guidance.
   `violation` is a map with:
     :source-ns   - the core namespace that has the violation
     :requires-ns - the shell namespace it incorrectly requires
     :module      - the module name"
  [{:keys [source-ns requires-ns module]}]
  (str (separator "BND-601" "FC/IS Boundary Violation") "\n"
       source-ns " requires " requires-ns "\n"
       "\n"
       "Why this matters: Core namespaces must be pure functions \u2014 no I/O,\n"
       "no database, no logging. This keeps your business logic testable\n"
       "and portable.\n"
       "\n"
       "Fix: Move the data access behind a port (protocol) in ports.clj,\n"
       "then have shell implement it.\n"
       "\n"
       "See: libs/" (or module "core") "/AGENTS.md \u00A7 FC/IS Architecture\n"
       (apply str (repeat 65 "\u2501"))))

(defn format-enriched-error
  "Format a fully enriched error map for rich development output.
   Combines the BND code header, stack trace, and auto-fix suggestion.

   opts (optional):
     :guidance-level — :full (default), :minimal, or :off
     At :off, auto-fix hints and dashboard/docs links are suppressed."
  ([enriched] (format-enriched-error enriched {}))
  ([{:keys [code stacktrace suggestions fix dashboard-url docs-url]}
    {:keys [guidance-level] :or {guidance-level :full}}]
   (let [error-def    (codes/lookup code)
         title        (or (:title error-def) "Error")
         show-hints?  (not= guidance-level :off)
         lines        (cond-> [(separator code title)]
                        (:description error-def)
                        (conj (:description error-def))

                        true (conj "")

                        stacktrace
                        (conj (stacktrace/format-stacktrace stacktrace))

                        stacktrace (conj "")

                        (seq suggestions)
                        (into (map #(str "  \u2192 " %) suggestions))

                        (seq suggestions) (conj "")

                        (:fix error-def)
                        (conj (str "Fix: " (:fix error-def)))

                        (and show-hints? fix)
                        (conj (str "\nAuto-fix: (fix!)  \u2014 " (:label fix)))

                        true (conj "")

                        (and show-hints? dashboard-url) (conj (str "Dashboard: " dashboard-url))
                        (and show-hints? docs-url)      (conj (str "Docs: " docs-url))

                        true (conj (apply str (repeat 65 "\u2501"))))]
     (str/join "\n" (remove nil? lines)))))

(defn format-unclassified-error
  "Format an unclassified error with a fallback message and AI hint."
  [^Throwable exception]
  (let [msg (or (.getMessage exception) "Unknown error")]
    (str "\u2501\u2501\u2501 Unclassified Error \u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\n"
         msg "\n"
         "\n"
         "This error is not recognized by Boundary's error catalog.\n"
         "Try: (explain *e) for AI-powered analysis\n"
         (apply str (repeat 65 "\u2501")))))
