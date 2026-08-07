(ns wagoe.tools.check-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.check :as check]
            [wagoe.tools.test-all :as test-all]))

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

(defn- workflows-dir
  "The .github/workflows directory, wherever the test is run from."
  []
  (let [cwd (System/getProperty "user.dir")]
    (or (some (fn [f] (when (.isDirectory ^java.io.File f) f))
              [(io/file cwd ".github" "workflows")
               (io/file cwd ".." ".." ".github" "workflows")])
        (throw (ex-info "no .github/workflows — cannot verify the CI lockstep"
                        {:cwd cwd})))))

(defn- all-workflow-sources
  "Every workflow file's source, concatenated.

   All of them, not just ci.yml: a gate can legitimately live elsewhere.
   check:branch-protection does, because reading branch protection needs an
   admin-scoped token and ci.yml runs `on: push` — which on a same-repository
   branch executes that branch's workflow with secrets available. Scanning only
   ci.yml would have reported the gate as having no CI job at all."
  []
  (->> (.listFiles (workflows-dir))
       (filter #(and (.isFile ^java.io.File %)
                     (re-find #"\.ya?ml$" (.getName ^java.io.File %))))
       (map slurp)
       (str/join "\n")))

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
    (let [ci (ci-invoked-gates (all-workflow-sources))]
      (doseq [{:keys [id cmd]} check/all-checks
              :when (and (= "bb" (first cmd))
                         (str/starts-with? (second cmd) "check:"))
              :let [gate (subs (second cmd) (count "check:"))]]
        (is (contains? ci gate)
            (str "check:" gate " (" id ") is in all-checks but no CI job runs it"))))))

(defn- summary-needs
  "Job ids in the `All Tests Passed` summary's `needs:` list."
  [yaml]
  (some->> (re-find #"(?s)name: All Tests Passed.*?needs:\s*\[([^\]]+)\]" yaml)
           second
           (#(str/split % #",\s*"))
           (map str/trim)
           set))

(defn- gate-job-ids
  "Job ids whose step runs a `bb check:<gate>` command.

   The job id is the last `^  <id>:` line before the run step."
  [yaml]
  (let [lines (str/split-lines yaml)]
    (loop [[l & more] lines, current nil, found #{}]
      (if (nil? l)
        found
        (let [id (second (re-find #"^  ([a-z][a-z0-9-]*):\s*$" l))
              hit (re-find #"run:\s+bb\s+check:[a-z-]+" l)]
          (recur more (or id current) (if (and hit current) (conj found current) found)))))))

(deftest ^:unit no-secret-is-exposed-to-branch-controlled-workflows
  ;; ci.yml runs `on: push`. For a same-repository branch, GitHub executes *that
  ;; branch's* copy of the workflow with secrets available — so a contributor
  ;; who can push a branch could rewrite a step and exfiltrate whatever it
  ;; holds. BOU-277 first shipped BRANCH_PROTECTION_TOKEN, an admin-scoped
  ;; credential, in exactly that position.
  ;;
  ;; A workflow may use secrets only when every trigger runs trusted code:
  ;; pushes filtered to main, schedule, or workflow_dispatch.
  (testing "workflows that can run branch code reference no secrets"
    ;; Two ways a caller reaches a non-main copy of a workflow, and both were
    ;; hit in turn while building this: an unfiltered `push`, and
    ;; `workflow_dispatch`, which lets the caller pick any ref. Filtering the
    ;; push and leaving dispatch in place reopened the same path.
    (doseq [f (->> (.listFiles (workflows-dir))
                   (filter #(and (.isFile ^java.io.File %)
                                 (re-find #"\.ya?ml$" (.getName ^java.io.File %)))))
            :let [src  (slurp f)
                  name (.getName ^java.io.File f)
                  unfiltered-push?
                  (and (re-find #"(?m)^\s+push:" src)
                       (not (re-find #"(?s)push:\s*\n\s+branches:" src))
                       (not (re-find #"(?s)push:\s*\n\s+tags:" src)))
                  ;; Match a real trigger key, not the word in a comment.
                  ref-selectable?
                  (boolean (re-find #"(?m)^\s{2}workflow_(dispatch|call):" src))
                  runs-branch-code? (or unfiltered-push? ref-selectable?)
                  uses-secret? (boolean (re-find #"secrets\." src))
                  ;; A deployment environment is the standard mitigation: the
                  ;; job does not start until a required reviewer approves, so
                  ;; branch code cannot reach the secret unattended. publish.yml
                  ;; needs workflow_dispatch to cut a release and pins
                  ;; `environment: release`, which carries required_reviewers —
                  ;; verified against the API, not assumed.
                  gated? (boolean (re-find #"(?m)^\s+environment:" src))]]
      (is (not (and runs-branch-code? uses-secret? (not gated?)))
          (str name " can run a non-main copy of itself (unfiltered push: "
               unfiltered-push? ", ref-selectable: " ref-selectable?
               ") and references a secret with no `environment:` gate — branch "
               "code would run with that credential available"))))

  (testing "no workflow needs a repository secret to run a gate"
    ;; The branch-protection gate briefly used an admin-scoped token to read
    ;; protection over the API. Requiring one context instead of fourteen
    ;; removed the need for it: the check now reads ci.yml, which needs no
    ;; credential at all. Kept as an assertion because reintroducing an API
    ;; read is the obvious way to "improve" this gate later, and it would bring
    ;; the credential back with it.
    (is (not (.exists (io/file (workflows-dir) "branch-protection.yml")))
        "the secret-bearing workflow is gone; the gate runs inside ci.yml")))

(deftest ^:unit every-gate-job-blocks-the-summary
  ;; A gate job that CI runs but the summary does not `needs:` can fail while
  ;; `All Tests Passed` still goes green — and that summary is what branch
  ;; protection requires. The job exists, the gate runs, the failure is visible
  ;; in the checks list, and nothing stops the merge.
  ;;
  ;; BOU-277 shipped exactly that: a gate built to catch unenforceable gates,
  ;; itself unenforceable. `every-aggregate-check-also-runs-in-ci` above did not
  ;; catch it, because a job existing is not the same as a job blocking.
  (testing "the summary is discoverable — guards the assertion below"
    (let [needs (summary-needs (ci-workflow))]
      (is (seq needs) "found no `needs:` on All Tests Passed; the check below would pass vacuously")
      (is (< 40 (count needs)) "expected the full job list")))

  (testing "every job that runs a gate is in the summary's needs"
    (let [yaml  (ci-workflow)
          needs (summary-needs yaml)
          gates (gate-job-ids yaml)]
      (is (seq gates) "found no gate jobs; this would pass vacuously")
      (doseq [g (sort gates)]
        (is (contains? needs g)
            (str "job `" g "` runs a gate but is not in All Tests Passed needs — "
                 "it can fail without blocking a merge"))))))

(deftest ^:unit every-ci-gate-is-also-in-the-aggregate-check
  (testing "each check:<gate> CI runs is reachable from `bb check`"
    (let [aggregate (->> check/all-checks
                         (map :cmd)
                         (filter #(and (= "bb" (first %))
                                       (str/starts-with? (second %) "check:")))
                         (map #(subs (second %) (count "check:")))
                         set)]
      ;; Every workflow, matching the direction above. Reading only ci.yml
      ;; would miss a gate added elsewhere and never registered in all-checks —
      ;; it would run in CI while `bb check` knew nothing about it.
      (doseq [gate (ci-invoked-gates (all-workflow-sources))]
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
    (is (= #{:doc-counts :agents :poms :no-boundary :docs-lint :branch-protection}
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

(defn- tests-edn
  "The kaocha suite registry."
  []
  (let [cwd (System/getProperty "user.dir")]
    (if-let [f (some (fn [^java.io.File f] (when (.exists f) f))
                     [(io/file cwd "tests.edn") (io/file cwd ".." ".." "tests.edn")])]
      (edn/read-string {:readers {'kaocha/v1 identity}} (slurp f))
      (throw (ex-info "tests.edn not found — cannot verify the suite/CI lockstep" {:cwd cwd})))))

(defn- declared-suites
  "Suite ids in tests.edn, excluding the aggregate `:unit`."
  []
  (->> (:tests (tests-edn)) (map :id) (remove #{:unit}) (map name) set))

(defn- ci-run-suites
  "{suite-name alias-string} for each `clojure -M:test… :<suite>` CI runs."
  [yaml]
  (into {} (for [[_ alias suite] (re-seq #"run: clojure (-M:test[^ ]*) :([a-z0-9-]+)" yaml)]
             [suite alias])))

(deftest ^:unit every-suite-has-a-ci-job
  ;; A suite nobody runs is a test file nobody executes. `audience` and
  ;; `devtools` were in tests.edn with no CI job at all — found while splitting
  ;; the shared :test alias (BOU-260), because that work meant enumerating every
  ;; suite and running it.
  (let [declared (declared-suites)
        in-ci    (ci-run-suites (ci-workflow))]

    (testing "the registry and the workflow are both discoverable"
      (is (< 20 (count declared)) "found no suites; the assertions below would pass vacuously")
      (is (seq in-ci) "found no `clojure -M:test … :<suite>` lines"))

    (testing "every declared suite is run by CI"
      (is (empty? (remove in-ci declared))
          (str "suites with no CI job: " (pr-str (sort (remove in-ci declared))))))

    (testing "CI does not run a suite that no longer exists"
      (is (empty? (remove declared (keys in-ci)))
          (str "CI runs suites absent from tests.edn: "
               (pr-str (sort (remove declared (keys in-ci)))))))))

(deftest ^:unit suites-needing-heavy-deps-request-them
  ;; The heavy test dependencies moved out of the shared :test alias so 25
  ;; per-library jobs stop resolving ~106 MB they do not use (BOU-260). A suite
  ;; that needs one and does not ask for it fails on a missing class, under its
  ;; own name — better than the old failure, but still avoidable.
  ;;
  ;; Pinned by suite rather than derived: which suite touches embedded-postgres
  ;; is not greppable — platform and tenant reach it through the shared
  ;; test/support/embedded_pg.clj, not a direct import, which is how a first
  ;; pass missed both.
  (let [in-ci (ci-run-suites (ci-workflow))]
    (testing "embedded-postgres"
      (doseq [s ["admin" "platform" "tenant"]]
        (is (str/includes? (get in-ci s "") ":test/pg")
            (str s " uses EmbeddedPostgres and must request :test/pg"))))

    (testing "opentelemetry in-memory exporters"
      (is (str/includes? (get in-ci "observability" "") ":test/otel")))

    (testing "clj-http-lite"
      (is (str/includes? (get in-ci "devtools" "") ":test/http")))

    (testing "and nothing else pays for them"
      ;; The whole point: a suite that does not need the heavy deps must not
      ;; resolve them.
      (doseq [[s alias] in-ci
              :when (not (#{"admin" "platform" "tenant" "observability" "devtools"} s))]
        (is (= "-M:test" alias)
            (str s " requests " alias " but needs nothing beyond :test"))))))

(defn- deps-edn []
  (let [cwd (System/getProperty "user.dir")]
    (if-let [f (some (fn [^java.io.File f] (when (.exists f) f))
                     [(io/file cwd "deps.edn") (io/file cwd ".." ".." "deps.edn")])]
      (edn/read-string (slurp f))
      (throw (ex-info "deps.edn not found" {:cwd cwd})))))

(deftest ^:unit the-composed-alias-covers-every-split-alias
  ;; Splitting :test broke the documented main command: kaocha's default run
  ;; discovers every suite, so it hit ClassNotFoundException before a test ran.
  ;; `:test/all` composes the split aliases back for a full local run, and this
  ;; asserts it stays complete — a new narrow alias that nobody adds here would
  ;; break that command again, in the same way and just as silently.
  (let [aliases (:aliases (deps-edn))
        split   (filter #(str/starts-with? (str (symbol %)) "test/") (keys aliases))
        all     (set (keys (:extra-deps (:test/all aliases))))]

    (testing "the split aliases are discoverable"
      (is (<= 4 (count split))
          "expected :test/pg, :test/pg-mac, :test/otel, :test/http"))

    (testing "every dependency in a split alias is in :test/all"
      (doseq [a split
              :when (not= :test/all a)
              d (keys (:extra-deps (get aliases a)))]
        (is (contains? all d)
            (str d " is in " a " but missing from :test/all, so the documented "
                 "full-suite command would fail on it"))))

    (testing "and :test/all adds nothing that no suite asks for"
      ;; Otherwise it becomes a second place heavy deps accumulate, which is
      ;; how :test got this way.
      (let [in-split (set (mapcat #(keys (:extra-deps (get aliases %)))
                                  (remove #{:test/all} split)))]
        (is (empty? (remove in-split all))
            (str "in :test/all but in no narrow alias: "
                 (pr-str (sort (remove in-split all)))))))))

(deftest ^:unit test-all-uses-the-documented-alias
  ;; `bb test:all` and AGENTS.md must name the same thing. Assembling the list
  ;; separately in each is how they drift.
  (testing "the main surface runs the composed alias"
    (is (= "-M:test:test/all" test-all/main-suite-aliases))))
