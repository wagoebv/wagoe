#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/check.clj
;;
;; Unified Quality Check — aggregates all quality checks into one command.
;;
;; Usage (via bb.edn task):
;;   bb check                      # Run all quality checks
;;   bb check --quick              # Run only FC/IS and dependency checks
;;   bb check --fix                # Pass --fix to kondo linter
;;   bb check --ci                 # Exit non-zero on any check failure
;;   bb check --help               # Print usage

(ns wagoe.tools.check
  (:require [wagoe.tools.ansi :refer [bold green red dim]]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Check definitions
;; =============================================================================

(defn lib-lint-paths
  "Enumerate the existing libs/<lib>/src and libs/<lib>/test directories as
   concrete paths. babashka.process/shell tokenizes but does not invoke a
   system shell, so an unexpanded glob like \"libs/*/src\" would reach clj-kondo
   verbatim and fail with \"file does not exist\". Expanding here guarantees only
   real paths are passed. Returns src paths first, then test paths (sorted)."
  []
  (let [libs-dir (io/file "libs")]
    (when (.isDirectory libs-dir)
      (let [libs (->> (.listFiles libs-dir)
                      (filter #(.isDirectory ^java.io.File %))
                      (sort-by #(.getName ^java.io.File %)))
            sub-dirs (fn [sub]
                       (->> libs
                            (map #(io/file % sub))
                            (filter #(.isDirectory ^java.io.File %))
                            (map #(.getPath ^java.io.File %))))]
        (into (vec (sub-dirs "src")) (sub-dirs "test"))))))

(def ^:private top-level-lint-paths
  "Non-lib source roots to lint, matching the ci.yml lint job.

   `dev` and `scripts` are not optional extras. Leaving them out made this
   check lint *less* than CI: a var defined in dev/ looked unresolved from a
   test that uses it, so `bb check` reported a warning CI did not — and, worse,
   a genuine error in dev/, scripts/ or examples/ would pass locally and only
   surface in CI. ci.yml's own comment records that this exact gap once hid a
   dev/ arity error.

   Keep this in step with the `--lint` paths in .github/workflows/ci.yml."
  ["src" "test" "dev" "scripts" "examples/todo/src"])

(defn linting-cmd
  "Build the clj-kondo lint command with concrete, existing source paths."
  []
  (into (into ["clojure" "-M:clj-kondo" "--lint"]
              (filter #(.exists (io/file %)) top-level-lint-paths))
        (lib-lint-paths)))

(def all-checks
  [{:id    :fcis
    :label "FC/IS boundaries"
    :cmd   ["bb" "check:fcis"]}
   {:id    :deps
    :label "Dependency direction"
    :cmd   ["bb" "check:deps"]}
   {:id    :ports
    :label "Ports / hexagonal"
    :cmd   ["bb" "check:ports"]}
   {:id    :placeholder-tests
    :label "Placeholder tests"
    :cmd   ["bb" "check:placeholder-tests"]}
   {:id    :test-meta
    :label "Deftest metadata placement"
    :cmd   ["bb" "check:test-meta"]}
   {:id    :test-tags
    :label "Deftest pyramid tags"
    :cmd   ["bb" "check:test-tags"]}
   {:id    :hygiene
    :label "Repo hygiene (no backup files)"
    :cmd   ["bb" "check:hygiene"]}
   ;; Documented library counts and publishing claims, locked to
   ;; wagoe.tools.deploy/all-libs. Added because a single review pass found the
   ;; count written five different ways and `libs/tools` called unpublished in
   ;; six places while it was on Clojars — drift a human sweep took three
   ;; attempts to clear (PR #351).
   {:id    :doc-counts
    :label "Documented library counts"
    :cmd   ["bb" "check:doc-counts"]}
   {:id    :agents
    :label "AGENTS.md drift"
    :cmd   ["bb" "check:agents"]}
   {:id    :poms
    :label "POM dependency completeness"
    :cmd   ["bb" "check:poms"]}
   {:id    :no-boundary
    :label "Rename gate (no residual boundary tokens)"
    :cmd   ["bb" "check:no-boundary"]}
   ;; Fails only on a documented command naming a deps.edn alias that does not
   ;; exist; broken links and unknown namespaces are reported but do not fail.
   ;; Added because CI was the only place this ran, so a dead alias survived
   ;; every local `bb check` (BOU-257).
   {:id    :docs-lint
    :label "Docs drift (commands name real aliases)"
    :cmd   ["bb" "docs:lint"]}
   {:id    :linting
    :label "Linting"
    :cmd   (linting-cmd)}
   {:id    :doctor
    :label "Config doctor"
    :cmd   ["bb" "doctor"]}])

(def quick-check-ids
  "Check IDs included in --quick mode."
  #{:fcis :deps :ports})

;; =============================================================================
;; Check execution
;; =============================================================================

(defn- run-check
  "Run a single check subprocess. Returns a result map with :id, :label, :exit, :duration-ms, and :output."
  [{:keys [id label cmd]} opts]
  (let [cmd (if (and (= id :linting) (:fix opts))
              (conj (vec cmd) "--fix")
              cmd)
        start   (System/currentTimeMillis)
        result  (process/shell {:continue true
                                :out :string
                                :err :string}
                               (str/join " " cmd))
        end     (System/currentTimeMillis)]
    {:id          id
     :label       label
     :exit        (:exit result)
     :duration-ms (- end start)
     :output      (str (:out result) (:err result))}))

(defn- format-duration
  "Format milliseconds as seconds with one decimal place."
  [ms]
  (format "%.1fs" (/ ms 1000.0)))

(defn- print-check-result
  "Print the result of a single check as it completes."
  [{:keys [label exit duration-ms output]}]
  (let [icon     (if (zero? exit) (green "✓") (red "✗"))
        duration (format-duration duration-ms)
        padded   (format "%-22s" label)]
    (println (str icon " " padded " (" duration ")"))
    (when (not (zero? exit))
      (let [lines    (str/split-lines (str/trim output))
            ;; Show a brief summary — last few meaningful lines
            summary  (->> lines
                          (remove str/blank?)
                          (take-last 10))]
        (doseq [line summary]
          (println (dim (str "  " line))))))))

;; =============================================================================
;; Argument parsing
;; =============================================================================

(defn- parse-args [args]
  (loop [[flag & more :as remaining] args
         opts {:quick false :fix false :ci false}]
    (cond
      (empty? remaining) opts
      (or (= flag "--help") (= flag "-h")) (assoc opts :help true)
      (= flag "--quick") (recur more (assoc opts :quick true))
      (= flag "--fix")   (recur more (assoc opts :fix true))
      (= flag "--ci")    (recur more (assoc opts :ci true))
      :else              (recur more opts))))

(defn- print-help []
  (println (bold "bb check") " — Unified quality check for Wagoe")
  (println)
  (println "Usage:")
  (println "  bb check                 Run all quality checks")
  (println "  bb check --quick         Run only FC/IS and dependency checks (fast)")
  (println "  bb check --fix           Pass --fix to kondo linter")
  (println "  bb check --ci            Exit non-zero on any check failure")
  (println)
  (println "Checks:")
  (println "  fcis               FC/IS boundary enforcement")
  (println "  deps               Library dependency direction + cycle detection")
  (println "  ports              Hexagonal boundaries: ports.clj presence + protocol usage")
  (println "  placeholder-tests  Detect (is true) placeholder assertions")
  (println "  linting            clj-kondo lint across all sources")
  (println "  doctor             Config doctor validation"))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& args]
  (let [opts (parse-args args)]
    (when (:help opts)
      (print-help)
      (System/exit 0))
    (let [checks  (if (:quick opts)
                    (filter #(contains? quick-check-ids (:id %)) all-checks)
                    all-checks)
          _       (do (println)
                      (println (bold "Wagoe Quality Check"))
                      (println (dim "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"))
                      (println))
          start   (System/currentTimeMillis)
          results (mapv (fn [check]
                          (let [result (run-check check opts)]
                            (print-check-result result)
                            result))
                        checks)
          end     (System/currentTimeMillis)
          passed  (count (filter #(zero? (:exit %)) results))
          failed  (count (filter #(not (zero? (:exit %))) results))
          total   (format-duration (- end start))]
      (println)
      (println (str "Summary: " (green (str passed " passed"))
                    ", " (red (str failed " failed"))
                    " (" total " total)"))
      (println)
      (when (and (:ci opts) (pos? failed))
        (System/exit 1)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
