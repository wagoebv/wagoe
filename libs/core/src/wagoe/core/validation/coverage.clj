(ns wagoe.core.validation.coverage
  "Pure coverage computation and reporting for validation rules.

  All functions are pure with no side effects. Data is injected via function parameters.
  File I/O belongs in test helpers or Kaocha plugins.

  Example usage:

    ;; Compute coverage
    (def result (compute {:registered #{:user.email/required :user.name/required}
                         :executed #{:user.email/required}
                         :by-module {:user #{:user.email/required :user.name/required}}}))
    ;; => {:total 2 :executed 1 :pct 50.0 :per-module {...} :missing #{:user.name/required}}

    ;; Generate human-readable report
    (human-report result)
    ;; => \"Coverage: 50.0% (1/2)\n  Module: user - 50.0% (1/2)\n  Missing: :user.name/required\"

    ;; Generate EDN report for file export
    (edn-report result)
    ;; => {:coverage 50.0 :total 2 :executed 1 :timestamp \"...\" ...}"
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Coverage Computation (Pure)
;; -----------------------------------------------------------------------------

(defn compute
  "Compute validation rule coverage statistics.

  Pure function that computes coverage metrics from registered and executed rule sets.

  Args:
    data - Map with:
           :registered - Set of all registered rule IDs
           :executed   - Set of executed rule IDs
           :by-module  - Map of {module-kw #{rule-ids...}} (optional)

  Returns:
    Coverage map:
    {:total int       - Total registered rules
     :executed int    - Number of executed rules
     :pct float       - Percentage (0-100)
     :per-module map  - Per-module breakdown {module {:total n :executed n :pct float}}
     :missing set     - Set of unexecuted rule IDs}

  Example:
    (compute {:registered #{:user.email/required :user.name/required :billing.amount/positive}
              :executed #{:user.email/required}
              :by-module {:user #{:user.email/required :user.name/required}
                         :billing #{:billing.amount/positive}}})
    ;; => {:total 3 :executed 1 :pct 33.33
    ;;     :per-module {:user {:total 2 :executed 1 :pct 50.0}
    ;;                  :billing {:total 1 :executed 0 :pct 0.0}}
    ;;     :missing #{:user.name/required :billing.amount/positive}}"
  [{:keys [registered executed by-module] :or {by-module {}}}]
  (let [registered (or registered #{})
        executed (or executed #{})
        total (count registered)
        executed-count (count (set/intersection registered executed))
        pct (if (zero? total) 0.0 (* 100.0 (/ executed-count total)))
        missing (set/difference registered executed)
        ;; Per-module breakdown
        per-module (reduce-kv
                    (fn [acc module module-rules]
                      (let [module-total (count module-rules)
                            module-executed (count (set/intersection module-rules executed))
                            module-pct (if (zero? module-total)
                                         0.0
                                         (* 100.0 (/ module-executed module-total)))]
                        (assoc acc module
                               {:total module-total
                                :executed module-executed
                                :pct module-pct
                                :missing (set/difference module-rules executed)})))
                    {}
                    by-module)]
    {:total total
     :executed executed-count
     :pct pct
     :per-module per-module
     :missing missing}))

(defn merge-executions
  "Merge multiple execution sets deterministically.

  Args:
    execution-sets - Sequence of sets containing executed rule IDs

  Returns:
    Merged set of all executed rule IDs.

  Example:
    (merge-executions [#{:user.email/required} #{:user.name/required}])
    ;; => #{:user.email/required :user.name/required}"
  [execution-sets]
  (reduce set/union #{} execution-sets))

;; -----------------------------------------------------------------------------
;; Report Generation (Pure)
;; -----------------------------------------------------------------------------

(defn edn-report
  "Generate EDN-serializable coverage report.

  Args:
    coverage - Coverage map from compute
    opts     - Options map:
               :timestamp - Timestamp string (optional)
               :metadata  - Additional metadata (optional)

  Returns:
    EDN-serializable map ready for writing to disk.

  Example:
    (edn-report {:total 10 :executed 8 :pct 80.0 ...}
                {:timestamp \"2025-01-04T13:47:21Z\" :metadata {:run-id \"test-1\"}})
    ;; => {:coverage 80.0 :total 10 :executed 8 :timestamp \"...\" :metadata {...}}"
  [coverage opts]
  (let [timestamp (or (:timestamp opts) "unknown")
        metadata (or (:metadata opts) {})]
    (merge
     {:coverage (:pct coverage)
      :total (:total coverage)
      :executed (:executed coverage)
      :missing (vec (sort (:missing coverage)))
      :timestamp timestamp}
     (when (seq (:per-module coverage))
       {:by-module (into (sorted-map)
                         (map (fn [[module stats]]
                                [module {:coverage (:pct stats)
                                         :total (:total stats)
                                         :executed (:executed stats)
                                         :missing (vec (sort (:missing stats)))}])
                              (:per-module coverage)))})
     (when (seq metadata)
       {:metadata metadata}))))

(defn- format-percentage
  "Format percentage with 1 decimal place."
  [pct]
  (String/format java.util.Locale/US "%.1f%%" (into-array Object [pct])))

(defn human-report
  "Generate human-readable coverage report.

  Args:
    coverage - Coverage map from compute
    opts     - Options map:
               :show-missing - Show missing rules (default true)
               :show-modules - Show per-module breakdown (default true)

  Returns:
    Formatted string report.

  Example:
    (human-report {:total 10 :executed 8 :pct 80.0
                   :per-module {:user {:total 5 :executed 4 :pct 80.0}}
                   :missing #{:user.email/format}})
    ;; => \"Validation Coverage Report
    ;;     ========================
    ;;     Overall: 80.0% (8/10)
    ;;     
    ;;     By Module:
    ;;       user: 80.0% (4/5)
    ;;     
    ;;     Missing Rules:
    ;;       :user.email/format\""
  [coverage {:keys [show-missing show-modules] :or {show-missing true show-modules true}}]
  (let [lines ["Validation Coverage Report"
               "========================"
               (str "Overall: " (format-percentage (:pct coverage))
                    " (" (:executed coverage) "/" (:total coverage) ")")]
        ;; Add module breakdown
        module-lines (when (and show-modules (seq (:per-module coverage)))
                       (concat
                        ["" "By Module:"]
                        (mapv (fn [[module stats]]
                                (str "  " (name module) ": "
                                     (format-percentage (:pct stats))
                                     " (" (:executed stats) "/" (:total stats) ")"))
                              (sort-by first (:per-module coverage)))))
        ;; Add missing rules
        missing-lines (when (and show-missing (seq (:missing coverage)))
                        (concat
                         ["" "Missing Rules:"]
                         (mapv #(str "  " %) (sort (:missing coverage)))))]
    (str/join "\n" (concat lines module-lines missing-lines))))

(defn summary-line
  "Generate a single-line coverage summary.

  Args:
    coverage - Coverage map from compute

  Returns:
    Single-line string summary.

  Example:
    (summary-line {:total 10 :executed 8 :pct 80.0})
    ;; => \"Coverage: 80.0% (8/10 rules executed)\""
  [coverage]
  (str "Coverage: " (format-percentage (:pct coverage))
       " (" (:executed coverage) "/" (:total coverage) " rules executed)"))

;; -----------------------------------------------------------------------------
;; Comparison and Analysis
;; -----------------------------------------------------------------------------

(defn compare-coverage
  "Compare two coverage results.

  Args:
    before - Coverage map from earlier run
    after  - Coverage map from later run

  Returns:
    Comparison map:
    {:delta-pct float      - Change in percentage
     :delta-executed int   - Change in executed count
     :improved? bool       - Whether coverage improved
     :new-rules set        - Newly executed rules
     :lost-rules set       - Previously executed but now missing}

  Example:
    (compare-coverage
      {:total 10 :executed 8 :missing #{:rule1 :rule2}}
      {:total 10 :executed 9 :missing #{:rule1}})
    ;; => {:delta-pct 10.0 :delta-executed 1 :improved? true
    ;;     :new-rules #{:rule2} :lost-rules #{}}"
  [before after]
  (let [delta-pct (- (:pct after) (:pct before))
        delta-executed (- (:executed after) (:executed before))
        improved? (pos? delta-pct)
        before-missing (or (:missing before) #{})
        after-missing (or (:missing after) #{})
        new-rules (clojure.set/difference before-missing after-missing)
        lost-rules (clojure.set/difference after-missing before-missing)]
    {:delta-pct delta-pct
     :delta-executed delta-executed
     :improved? improved?
     :new-rules new-rules
     :lost-rules lost-rules}))

(defn filter-by-module
  "Filter coverage data to specific modules.

  Args:
    coverage - Coverage map from compute
    modules  - Set or sequence of module keywords

  Returns:
    Filtered coverage map.

  Example:
    (filter-by-module coverage #{:user :billing})
    ;; => Coverage map with only user and billing modules"
  [coverage modules]
  (let [module-set (set modules)
        filtered-per-module (select-keys (:per-module coverage) module-set)
        filtered-missing (apply clojure.set/union
                                (map :missing (vals filtered-per-module)))
        filtered-total (reduce + 0 (map :total (vals filtered-per-module)))
        filtered-executed (reduce + 0 (map :executed (vals filtered-per-module)))
        filtered-pct (if (zero? filtered-total)
                       0.0
                       (* 100.0 (/ filtered-executed filtered-total)))]
    {:total filtered-total
     :executed filtered-executed
     :pct filtered-pct
     :per-module filtered-per-module
     :missing filtered-missing}))
