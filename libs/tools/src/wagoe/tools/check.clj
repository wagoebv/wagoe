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
    :scope :any
    :label "FC/IS boundaries"
    :cmd   ["bb" "check:fcis"]}
   {:id    :deps
    :scope :any
    :label "Dependency direction"
    :cmd   ["bb" "check:deps"]}
   {:id    :ports
    :scope :any
    :label "Ports / hexagonal"
    :cmd   ["bb" "check:ports"]}
   {:id    :placeholder-tests
    :scope :any
    :label "Placeholder tests"
    :cmd   ["bb" "check:placeholder-tests"]}
   {:id    :test-meta
    :scope :any
    :label "Deftest metadata placement"
    :cmd   ["bb" "check:test-meta"]}
   {:id    :test-tags
    :scope :any
    :label "Deftest pyramid tags"
    :cmd   ["bb" "check:test-tags"]}
   {:id    :hygiene
    :scope :any
    :label "Repo hygiene (no backup files)"
    :cmd   ["bb" "check:hygiene"]}
   ;; Documented library counts and publishing claims, locked to
   ;; wagoe.tools.deploy/all-libs. Added because a single review pass found the
   ;; count written five different ways and `libs/tools` called unpublished in
   ;; six places while it was on Clojars — drift a human sweep took three
   ;; attempts to clear (PR #351).
   {:id    :doc-counts
    :scope :monorepo
    :label "Documented library counts"
    :cmd   ["bb" "check:doc-counts"]}
   {:id    :versions
    :scope :monorepo
    :label "Suite version consistency"
    :cmd   ["bb" "check:versions"]}
   ;; Thirty PRs merged in eleven days without one CHANGELOG entry between
   ;; them, including a new library, a removed config key and a change to the
   ;; order jobs are dispatched in. :monorepo because a generated project keeps
   ;; its own changelog, if any, on its own terms.
   {:id    :changelog
    :scope :monorepo
    :label "Changelog covers shipped source"
    :cmd   ["bb" "check:changelog"]}
   ;; "30 independently publishable libraries" was documented and never
   ;; checked. The CI matrix job compiles each library against its own
   ;; deps.edn; this catches what a compile cannot see, which turned out to be
   ;; the part that matters — 30 of 31 compile clean, `realtime` among them,
   ;; while realtime's require sits in a try/catch that swallows the failure
   ;; (BOU-304). :monorepo because a generated project has no libs/ to isolate.
   {:id    :isolation
    :scope :monorepo
    :label "Libraries declare what they load"
    :cmd   ["bb" "check:isolation"]}
   ;; ADR-022 required a :type on every thrown ex-info in April and nothing
   ;; checked it; 59 shell throws had none by August. ADR-036 added the return
   ;; shapes. :monorepo because the allowlist is this repository's burn-down
   ;; list — a generated project has no such history (BOU-323).
   {:id    :error-shape
    :scope :monorepo
    :label "Errors carry their shape"
    :cmd   ["bb" "check:error-shape"]}
   {:id    :agents
    :scope :monorepo
    :label "AGENTS.md drift"
    :cmd   ["bb" "check:agents"]}
   {:id    :poms
    :scope :monorepo
    :label "POM dependency completeness"
    :cmd   ["bb" "check:poms"]}
   ;; Reads branch protection via `gh`, so it needs an admin-scoped token and
   ;; skips cleanly without one. :monorepo because a generated project has no
   ;; wagoebv/wagoe to reconcile against.
   {:id    :branch-protection
    :scope :monorepo
    :label "Branch protection vs CI job names"
    :cmd   ["bb" "check:branch-protection"]}
   {:id    :no-boundary
    :scope :monorepo
    :label "Rename gate (no residual boundary tokens)"
    :cmd   ["bb" "check:no-boundary"]}
   ;; Fails only on a documented command naming a deps.edn alias that does not
   ;; exist; broken links and unknown namespaces are reported but do not fail.
   ;; Added because CI was the only place this ran, so a dead alias survived
   ;; every local `bb check` (BOU-257).
   {:id    :docs-lint
    :scope :monorepo
    :label "Docs drift (commands name real aliases)"
    :cmd   ["bb" "docs:lint"]}
   {:id    :linting
    :scope :any
    :label "Linting"
    :cmd   (linting-cmd)}
   ;; --ci is not optional here. `bb doctor` prints its ✗ lines and exits 0;
   ;; only --ci makes it exit non-zero. Without it this row reported ✓ for a
   ;; config.edn that did not even parse, so the gate had never failed for any
   ;; config, valid or not (BOU-270). Every other checker in this registry exits
   ;; 1 on violations unconditionally — doctor is the one that needs the flag.
   {:id    :doctor
    :scope :any
    :label "Config doctor"
    :cmd   ["bb" "doctor" "--ci"]}])

(def quick-check-ids
  "Check IDs included in --quick mode."
  #{:fcis :deps :ports})

(defn framework-repo?
  "True when running inside the Wagoe monorepo rather than a generated project.

   Five checks only mean something here: doc-counts and poms compare against
   wagoe.tools.deploy/all-libs, agents diffs the agents knowledge base,
   no-boundary is a rename gate for this repo's own history, and docs:lint runs
   from the dev/ path. Generated projects define none of those bb tasks, so
   `bb check` used to invoke them, bb exited 1 on \"File does not exist\", and
   run-check reported them as violations the user could not act on (BOU-264).

   Keyed on deploy.clj because that is the file the framework-only checks are
   ultimately about — the registry of published libraries."
  []
  (.exists (io/file "libs/tools/src/wagoe/tools/deploy.clj")))

(defn applicable-checks
  "The checks to run, given whether this is the framework repo. Returns
   [to-run skipped] so the caller can name what it left out — a silently
   shorter list reads as a clean run."
  [checks framework?]
  (if framework?
    [checks []]
    [(filter #(= :any (:scope %)) checks)
     (remove #(= :any (:scope %)) checks)]))

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
    (let [selected (if (:quick opts)
                     (filter #(contains? quick-check-ids (:id %)) all-checks)
                     all-checks)
          [checks skipped] (applicable-checks selected (framework-repo?))
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
      ;; Name the skipped checks. A shorter list with no explanation is
      ;; indistinguishable from a clean run, which is how a gate stops being a
      ;; gate without anyone noticing.
      (when (seq skipped)
        (println)
        (println (dim (str "Skipped " (count skipped) " framework-only check(s): "
                           (str/join ", " (map (comp name :id) skipped)))))
        (println (dim "  These check the Wagoe repository itself — its published"))
        (println (dim "  library set, AGENTS.md sync and rename history — not your project.")))
      (println)
      (when (and (:ci opts) (pos? failed))
        (System/exit 1)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
