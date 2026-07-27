(ns wagoe.devtools.core.state-analyzer
  "Pure functions for analyzing project state.
   Determines 'what should you do next?' based on project state data.
   No I/O — accepts pre-collected state data as arguments."
  (:require [clojure.set]
            [clojure.string :as str]))

;; =============================================================================
;; State analysis
;; =============================================================================

(defn analyze-modules
  "Analyze module state. Returns findings about unintegrated modules.
   `scaffolded-modules` - set of module names found in libs/
   `integrated-modules` - set of module names found in deps.edn paths"
  [scaffolded-modules integrated-modules]
  (let [unintegrated (clojure.set/difference scaffolded-modules integrated-modules)]
    (when (seq unintegrated)
      {:type    :unintegrated-modules
       :level   :warn
       :count   (count unintegrated)
       :modules unintegrated
       :msg     (str (count unintegrated) " unintegrated module"
                     (when (> (count unintegrated) 1) "s")
                     ": " (str/join ", " (sort unintegrated)))
       :fix     (str/join "\n" (map #(str "  bb scaffold integrate " %) (sort unintegrated)))})))

(defn analyze-migrations
  "Analyze migration state. Returns findings about pending migrations.
   `migration-status` - map with :applied and :pending counts"
  [{:keys [pending pending-names]}]
  (when (and pending (pos? pending))
    {:type          :pending-migrations
     :level         :warn
     :count         pending
     :pending-names pending-names
     :msg           (str pending " pending migration" (when (> pending 1) "s")
                         (when (seq pending-names)
                           (str ": " (str/join ", " pending-names))))
     :fix           "  bb migrate up"}))

(defn analyze-seeds
  "Analyze seed data state.
   `seed-file-exists?` - whether resources/seeds/dev.edn exists"
  [seed-file-exists?]
  (when-not seed-file-exists?
    {:type  :missing-seeds
     :level :warn
     :msg   "No seed data defined"
     :fix   "  Create: resources/seeds/dev.edn"}))

(defn analyze-tests
  "Analyze test state.
   `test-result` - map with :total, :pass, :fail, :error"
  [{:keys [total fail error]}]
  (cond
    (nil? total)
    nil

    (and (zero? (or fail 0)) (zero? (or error 0)))
    {:type  :tests-passing
     :level :pass
     :msg   (str "All tests passing (" total " tests)")
     :fix   nil}

    :else
    {:type  :tests-failing
     :level :error
     :msg   (str (+ (or fail 0) (or error 0)) " test failure"
                 (when (> (+ (or fail 0) (or error 0)) 1) "s")
                 " (" total " total)")
     :fix   "  clojure -M:test:db/h2"}))

(defn analyze-lint
  "Analyze lint state.
   `lint-errors` - number of lint errors"
  [lint-errors]
  (if (zero? lint-errors)
    {:type :lint-clean :level :pass :msg "No linting errors" :fix nil}
    {:type  :lint-errors
     :level :warn
     :msg   (str lint-errors " linting error" (when (> lint-errors 1) "s"))
     :fix   "  clojure -M:clj-kondo --lint src test libs/*/src libs/*/test"}))

;; =============================================================================
;; Formatting
;; =============================================================================

(defn format-findings
  "Format a seq of findings into a 'next steps' display string."
  [findings module-count]
  (let [icon  (fn [level]
                (case level
                  :pass  "\u2713"
                  :warn  "\u26A0"
                  :error "\u2717"
                  "?"))
        lines (cond-> ["Your project has:"
                       (str "  \u2713 " module-count " module"
                            (when (> module-count 1) "s"))]
                true (into (map (fn [{:keys [level msg fix]}]
                                  (str "  " (icon level) " " msg
                                       (when fix (str "\n    \u2192 " (str/trim fix)))))
                                (remove nil? findings))))]
    (str/join "\n" lines)))
