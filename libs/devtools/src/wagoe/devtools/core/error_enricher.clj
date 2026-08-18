(ns wagoe.devtools.core.error-enricher
  "Enrich classified errors with stacktrace, suggestions, fix info, and URLs.
   Pure functions — no I/O, no side effects.

   Each sub-call is wrapped in try/catch for self-protection:
   if any enrichment step fails, that field is omitted rather than
   crashing the pipeline."
  (:require [wagoe.devtools.core.stacktrace :as stacktrace]
            [wagoe.devtools.core.auto-fix :as auto-fix]
            [wagoe.core.validation.messages :as messages]))

(defn- safe-call
  "Call f, returning its result or nil if it throws."
  [f]
  (try (f) (catch Exception _ nil)))

(defn enrich
  "Enrich a classified error map with additional context.

   Adds:
   - :stacktrace — filtered/reordered stack trace
   - :suggestions — 'Did you mean?' suggestions (when applicable)
   - :fix — auto-fix descriptor or nil
   - :dashboard-url — link to dev dashboard error page
   - :docs-url — where to read about this code (`bb guide error <code>`)

   Each field is independently protected: if a sub-call fails,
   that field is omitted from the result."
  [{:keys [code exception data] :as classified}]
  (let [trace       (safe-call #(when exception (stacktrace/filter-stacktrace exception)))
        fix         (safe-call #(auto-fix/match-fix classified))
        suggestions (safe-call
                     #(when (and data (:value data) (:allowed-values data))
                        (let [suggestion (messages/suggest-similar-value
                                          (str (:value data))
                                          (map str (:allowed-values data))
                                          {})]
                          (when suggestion
                            [(messages/create-did-you-mean-suggestion
                              {:allowed (:allowed-values data)
                               :suggestion suggestion})]))))
        dashboard   (when code "http://localhost:9999/dashboard/errors")
        ;; `bb guide error <code>` reads the same catalogue this enricher does,
        ;; so it always answers. The URL that used to be here — wagoe.dev — is
        ;; not a domain this project owns (wagoe.org is), and since BOU-321 the
        ;; value is handed to HTTP clients rather than only printed in a REPL.
        docs        (when code (str "bb guide error " code))]
    (cond-> classified
      trace       (assoc :stacktrace trace)
      fix         (assoc :fix fix)
      suggestions (assoc :suggestions suggestions)
      dashboard   (assoc :dashboard-url dashboard)
      docs        (assoc :docs-url docs))))
