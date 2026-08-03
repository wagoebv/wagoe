(ns wagoe.tools.check-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.check :as check]))

;; =============================================================================
;; Check definitions
;; =============================================================================

(deftest ^:unit all-checks-have-required-fields
  (testing "every check has :id, :label, :cmd"
    (doseq [c check/all-checks]
      (is (keyword? (:id c)) "check missing :id")
      (is (string? (:label c)) (str "check " (:id c) " missing :label"))
      (is (vector? (:cmd c)) (str "check " (:id c) " missing :cmd")))))

;; =============================================================================
;; Linting command — glob expansion (BOU-103)
;; =============================================================================

(deftest ^:unit linting-cmd-contains-no-unexpanded-glob
  (testing "no path contains a literal '*' wildcard"
    (doseq [arg (check/linting-cmd)]
      (is (not (str/includes? arg "*"))
          (str "linting cmd arg still contains an unexpanded glob: " arg)))))

(deftest ^:unit linting-cmd-paths-all-exist
  (testing "every lint path beyond the leading clojure invocation is an existing directory"
    ;; cmd = ["clojure" "-M:clj-kondo" "--lint" <path>...]
    (let [paths (drop 4 (check/linting-cmd))]
      (doseq [p paths]
        (is (.isDirectory (io/file p))
            (str "lint path does not exist: " p))))))

(deftest ^:unit lib-lint-paths-enumerates-existing-lib-dirs
  (testing "returns concrete libs/<lib>/src and libs/<lib>/test dirs when libs/ present"
    (let [paths (check/lib-lint-paths)]
      (when (.isDirectory (io/file "libs"))
        (is (seq paths) "expected at least one lib source path")
        (is (every? #(str/starts-with? % "libs/") paths))
        (is (every? #(.isDirectory (io/file %)) paths))))))

(deftest ^:unit quick-check-ids-are-subset-of-all-checks
  (testing "all quick-check-ids exist in all-checks"
    (let [all-ids (set (map :id check/all-checks))]
      (doseq [qid check/quick-check-ids]
        (is (contains? all-ids qid)
            (str "quick-check-id " qid " not found in all-checks"))))))

;; =============================================================================
;; all-checks <-> CI lockstep
;; =============================================================================
;;
;; CI invokes each gate as its own job (`run: bb check:<gate>`) rather than
;; calling `bb check`, so the workflow and `all-checks` are two hand-maintained
;; copies of the same registry — and they had silently drifted three ways:
;; check:no-boundary was in neither, check:poms ran only in CI, check:agents
;; only in `bb check`. A gate missing from `all-checks` means `bb check --ci`
;; passes locally without it; missing from CI means nothing enforces it on a PR.

(defn- ci-workflow
  "The CI workflow source. `bb test:tools` runs from the repo root; a standalone
   run inside libs/tools does not — try both, then give up loudly.

   Deliberately throws rather than returning nil: a lockstep test that quietly
   passes when it cannot find the file it compares against is the same
   stopped-checking failure it exists to prevent (BOU-250)."
  []
  (let [cwd        (System/getProperty "user.dir")
        candidates [(io/file cwd ".github" "workflows" "ci.yml")
                    (io/file cwd ".." ".." ".github" "workflows" "ci.yml")]]
    (or (some (fn [f] (when (.exists ^java.io.File f) (slurp f))) candidates)
        (throw (ex-info "ci.yml not found — cannot verify all-checks/CI lockstep"
                        {:cwd cwd :tried (mapv str candidates)})))))

(defn- ci-invoked-gates
  "Gate names CI runs directly, from `run: bb check:<gate>` lines."
  [yaml]
  (->> (re-seq #"run:\s+bb\s+check:([a-z-]+)" yaml)
       (map second)
       set))

(deftest ^:unit ci-workflow-is-discoverable-and-runs-gates
  (testing "the workflow is found and actually invokes gates — guards the two tests below"
    (is (seq (ci-invoked-gates (ci-workflow)))
        "found no `run: bb check:<gate>` lines; the lockstep tests below would pass vacuously")))

(deftest ^:unit every-aggregate-check-also-runs-in-ci
  (testing "each bb check:<gate> in all-checks has a CI job invoking it"
    (let [ci (ci-invoked-gates (ci-workflow))]
      (doseq [{:keys [id cmd]} check/all-checks
              :when (and (= "bb" (first cmd))
                         (str/starts-with? (second cmd) "check:"))
              :let [gate (subs (second cmd) (count "check:"))]]
        (is (contains? ci gate)
            (str "check:" gate " (" id ") is in all-checks but no CI job runs it"))))))

(deftest ^:unit every-ci-gate-is-also-in-the-aggregate-check
  (testing "each check:<gate> CI runs is reachable from `bb check`"
    (let [aggregate (->> check/all-checks
                         (map :cmd)
                         (filter #(and (= "bb" (first %))
                                       (str/starts-with? (second %) "check:")))
                         (map #(subs (second %) (count "check:")))
                         set)]
      (doseq [gate (ci-invoked-gates (ci-workflow))]
        (is (contains? aggregate gate)
            (str "check:" gate " runs in CI but is missing from all-checks, so "
                 "`bb check --ci` passes without it"))))))

;; =============================================================================
;; Argument parsing
;; =============================================================================

(deftest ^:unit parse-args-test
  (testing "defaults when no args"
    (let [opts (#'check/parse-args [])]
      (is (false? (:quick opts)))
      (is (false? (:fix opts)))
      (is (false? (:ci opts)))))

  (testing "parses --quick flag"
    (is (true? (:quick (#'check/parse-args ["--quick"])))))

  (testing "parses --fix flag"
    (is (true? (:fix (#'check/parse-args ["--fix"])))))

  (testing "parses --ci flag"
    (is (true? (:ci (#'check/parse-args ["--ci"])))))

  (testing "parses multiple flags"
    (let [opts (#'check/parse-args ["--quick" "--ci"])]
      (is (true? (:quick opts)))
      (is (true? (:ci opts)))
      (is (false? (:fix opts)))))

  (testing "parses --help flag"
    (is (true? (:help (#'check/parse-args ["--help"]))))))

;; =============================================================================
;; Scope — which checks can run outside this repo (BOU-264)
;; =============================================================================

;; `bb check` runs each check as a subprocess (`bb check:fcis`, ...). Five of
;; them only make sense in the Wagoe repo: doc-counts and poms compare against
;; wagoe.tools.deploy/all-libs, agents diffs resources/agents/knowledge.edn,
;; no-boundary is a rename gate for our own history, and docs:lint lives on the
;; dev/ path. In a generated project those tasks do not exist, so `bb check`
;; invoked them, bb exited 1 on "File does not exist", and run-check reported
;; eight violations the user could do nothing about.

(deftest ^:unit every-check-declares-a-scope
  (testing "scope is explicit, so a new check has to decide"
    (doseq [{:keys [id scope]} check/all-checks]
      (is (contains? #{:any :monorepo} scope)
          (str id " must declare :scope :any or :monorepo")))))

(deftest ^:unit monorepo-only-checks-are-the-expected-set
  (testing "the framework-only checks are exactly these"
    ;; Pinned deliberately: promoting one to :any means a generated project
    ;; will invoke it, so the task must exist in bb.edn.tmpl first — which the
    ;; test below enforces.
    (is (= #{:doc-counts :agents :poms :no-boundary :docs-lint}
           (set (map :id (remove #(= :any (:scope %)) check/all-checks)))))))

(deftest ^:unit portable-checks-exist-as-tasks-in-generated-projects
  (testing "every :any check names a bb task the project template defines"
    ;; This is the guard that was missing. The registry ships in wagoe-tools,
    ;; which every generated project depends on, while the task list lives in
    ;; a template in another library — two places, nothing comparing them.
    (let [tmpl (io/file "libs/wagoe-cli/resources/wagoe/cli/templates/bb.edn.tmpl")]
      (if-not (.exists tmpl)
        (is (not (.exists tmpl))
            "skipped: template not reachable from this working directory")
        (let [content   (slurp tmpl)
              ;; task keys look like `  check:fcis        {:doc ...`
              task-names (set (map second (re-seq #"(?m)^\s{2}([a-z][a-z0-9:_-]*)\s+\{" content)))
              portable   (filter #(= :any (:scope %)) check/all-checks)]
          (doseq [{:keys [id cmd]} portable]
            ;; Only subprocess checks that shell out to `bb <task>` are checked;
            ;; :linting runs clojure -M:clj-kondo, which is a deps.edn alias.
            (when (= "bb" (first cmd))
              (let [task (second cmd)]
                (is (contains? task-names task)
                    (str "check " id " runs `bb " task
                         "`, which bb.edn.tmpl does not define — "
                         "`bb check` would fail on it in a generated project"))))))))))

;; =============================================================================
;; Registry commands must be able to fail (BOU-270)
;; =============================================================================

;; `bb check` judges each check by its subprocess exit code. A checker that
;; reports problems and still exits 0 therefore produces a row that can never go
;; red. That is what happened to Config doctor: `bb doctor` prints its ✗ lines
;; and exits 0 — only `--ci` makes it exit non-zero — and the registry invoked
;; it bare. With a config.edn that did not parse:
;;
;;   $ bb check --ci
;;     ✓ Config doctor          (0.1s)
;;   Summary: 9 passed, 0 failed        (exit 0)
;;
;; gate_firing_test has a doctor-gate-fires-test, but it calls
;; doctor/check-jwt-secret directly and asserts it returns :error. That proves
;; the check *detects*; it cannot prove the gate *fails*, because the defect was
;; a missing flag on the command line rather than logic in the checker. Testing
;; a function cannot catch a wrong invocation — the same shape as BOU-266.
;;
;; So this asserts the command, not the checker.

(def ^:private ci-required-tools
  "Tools that report problems on stdout but exit 0 unless told otherwise.
   Invoking one of these without its flag yields a gate that cannot fail."
  {["bb" "doctor"] "--ci"})

(deftest ^:unit registry-commands-can-signal-failure
  (testing "no check shells a tool that would exit 0 on its own findings"
    (doseq [{:keys [id cmd]} check/all-checks]
      (when-let [flag (get ci-required-tools (vec (take 2 cmd)))]
        (is (some #{flag} cmd)
            (str "check " id " runs `" (str/join " " cmd) "`, which exits 0 even "
                 "when it reports errors — it needs " flag
                 ", or this row can never fail"))))))

(deftest ^:unit doctor-check-passes-ci
  (testing "the doctor entry specifically"
    (let [doctor (first (filter #(= :doctor (:id %)) check/all-checks))]
      (is (some? doctor) "the registry no longer has a :doctor check")
      (is (= ["bb" "doctor" "--ci"] (:cmd doctor))))))
