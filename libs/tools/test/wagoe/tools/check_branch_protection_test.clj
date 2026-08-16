(ns wagoe.tools.check-branch-protection-test
  "BOU-277: main required 13 status checks that no job has ever reported.

   They were the job *keys* from ci.yml — `lint`, `test-core` — while GitHub
   reports a check under the job's `name:`, and every job here has had one
   since Phase 0. So the contexts never matched anything, and were wrong from
   the day they were set rather than broken by any later rename.

   What makes it worth a gate is the failure mode: a required check that never
   reports is indistinguishable from one that always passes. While
   `enforce_admins` was off, an admin override absorbed 13 permanently pending
   checks on every merge. Nothing failed; nothing said anything.

   The first version of this gate read protection over the API, which needed an
   admin-scoped token stored in the repository. It also turned out that 12 of
   the 14 required contexts were redundant — the summary job already depended
   on them. Requiring one context, and checking here that it covers everything,
   removes both the credential and most of the drift surface."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-yaml.core :as yaml]
            [wagoe.tools.check-branch-protection :as sut]))

(defn- wf [yaml-str] (yaml/parse-string yaml-str))

(deftest ^:unit a-context-is-the-job-name-not-the-key
  ;; The original defect in one assertion.
  (testing "an explicit name: wins over the key"
    (is (= "Lint" (sut/job-context :lint {:name "Lint"})))
    (is (= "All Tests Passed" (sut/job-context :test-summary {:name "All Tests Passed"}))))

  (testing "the key is used only when there is no name:"
    (is (= "no-explicit-name" (sut/job-context :no-explicit-name {:runs-on "ubuntu-latest"})))))

(deftest ^:unit a-job-outside-the-summary-cannot-block-a-merge
  ;; Branch protection requires exactly one context. Anything the summary does
  ;; not reach can go red while that context goes green.
  (testing "a job missing from the summary's needs is reported"
    (let [{:keys [missing]}
          (sut/summary-covers
           (wf (str "jobs:\n"
                    "  test-summary:\n    name: All Tests Passed\n    needs: [a]\n"
                    "  a:\n    name: A\n"
                    "  orphan:\n    name: Orphan\n")))]
      (is (= ["orphan"] missing))))

  (testing "a fully covered workflow is clean"
    (let [{:keys [missing]}
          (sut/summary-covers
           (wf (str "jobs:\n"
                    "  test-summary:\n    name: All Tests Passed\n    needs: [a, b]\n"
                    "  a:\n    name: A\n"
                    "  b:\n    name: B\n")))]
      (is (empty? missing)))))

(deftest ^:unit coverage-is-transitive
  ;; `warm-deps` is not in the summary's needs, but `lint` needs it — so a
  ;; warm-deps failure fails lint and therefore the summary. A flat check called
  ;; it unguarded, which would have pushed a redundant entry into `needs:` to
  ;; satisfy the wrong model.
  (testing "a job reached through another job is guarded"
    (let [{:keys [missing]}
          (sut/summary-covers
           (wf (str "jobs:\n"
                    "  test-summary:\n    name: All Tests Passed\n    needs: [lint]\n"
                    "  lint:\n    name: Lint\n    needs: [warm-deps]\n"
                    "  warm-deps:\n    name: Warm Dependency Cache\n")))]
      (is (empty? missing)
          "warm-deps fails lint, which fails the summary")))

  (testing "depth beyond one hop still counts"
    (let [{:keys [missing]}
          (sut/summary-covers
           (wf (str "jobs:\n"
                    "  test-summary:\n    name: All Tests Passed\n    needs: [a]\n"
                    "  a:\n    name: A\n    needs: [b]\n"
                    "  b:\n    name: B\n    needs: [c]\n"
                    "  c:\n    name: C\n")))]
      (is (empty? missing))))

  (testing "a bare string needs: is a dependency, not a sequence of characters"
    ;; YAML allows `needs: lint`. Mapping `name` over the string yields its
    ;; characters, which would silently drop the real dependency.
    (let [{:keys [missing]}
          (sut/summary-covers
           (wf (str "jobs:\n"
                    "  test-summary:\n    name: All Tests Passed\n    needs: [a]\n"
                    "  a:\n    name: A\n    needs: warm-deps\n"
                    "  warm-deps:\n    name: Warm\n")))]
      (is (empty? missing)))))

(deftest ^:unit a-parked-job-is-not-required
  ;; e2e carries `if: false` — it needs a live server CI does not start, and
  ;; reports as skipped. Requiring a job that never runs would recreate the
  ;; original defect: a context that can never be satisfied.
  (testing "an `if: false` job is reported as parked, not as unguarded"
    (let [{:keys [missing disabled]}
          (sut/summary-covers
           (wf (str "jobs:\n"
                    "  test-summary:\n    name: All Tests Passed\n    needs: [a]\n"
                    "  a:\n    name: A\n"
                    "  e2e:\n    name: E2E\n    if: false\n")))]
      (is (empty? missing))
      (is (= ["e2e"] disabled))))

  (testing "a job with a real condition is still required"
    ;; Only a literal `false` parks a job. A conditional that can be true still
    ;; runs, so it must be covered.
    (let [{:keys [missing disabled]}
          (sut/summary-covers
           (wf (str "jobs:\n"
                    "  test-summary:\n    name: All Tests Passed\n    needs: [a]\n"
                    "  a:\n    name: A\n"
                    "  sometimes:\n    name: Sometimes\n    if: github.ref == 'refs/heads/main'\n")))]
      (is (= ["sometimes"] missing))
      (is (empty? disabled)))))

(deftest ^:unit renaming-the-summary-breaks-the-required-context
  ;; Branch protection requires the string in `summary-job-name`. Renaming the
  ;; job without changing protection recreates the original defect exactly.
  (testing "the name in ci.yml must match what protection requires"
    (let [{:keys [summary-name]}
          (sut/summary-covers
           (wf (str "jobs:\n  test-summary:\n    name: Renamed Summary\n    needs: [a]\n"
                    "  a:\n    name: A\n")))]
      (is (not= sut/summary-job-name summary-name)
          "this mismatch is what -main exits 1 on"))))

(deftest ^:unit the-on-block-is-read-under-both-keys-yaml-gives-it
  ;; YAML 1.1 reads the bare word `on` as the boolean true, so SnakeYAML parses
  ;; `on:` to the key `true` rather than `:on`. A reader that looks only for
  ;; `:on` finds nothing and — worse — concludes the workflow has no triggers at
  ;; all, which reads as "no pull_request trigger" and fires this gate on a
  ;; correct file. Quoted (`"on":`) and unquoted must both work.
  (testing "the unquoted key YAML turns into true"
    (is (= #{:push :pull_request}
           (set (keys (sut/workflow-triggers
                       (wf "on:\n  push:\n    branches: [main]\n  pull_request:\n")))))))

  (testing "the quoted key, which stays a string"
    (is (= #{:push :pull_request}
           (set (keys (sut/workflow-triggers
                       (wf "\"on\":\n  push:\n    branches: [main]\n  pull_request:\n"))))))))

(deftest ^:unit a-workflow-without-a-pull-request-trigger-runs-no-ci-on-fork-prs
  ;; BOU-315: ci.yml triggered on `push:` only. Same-repo branches were covered,
  ;; but a fork PR ran zero jobs — and the required "All Tests Passed" context
  ;; is reported by a job that never started, so it sits pending rather than
  ;; failing. Combined with the publish gap, code could reach main having never
  ;; been tested.
  (testing "push-only is reported"
    (is (seq (sut/trigger-findings (wf "on:\n  push:\n")))))

  (testing "adding pull_request clears it"
    (is (empty? (sut/trigger-findings
                 (wf "on:\n  push:\n    branches: [main]\n  pull_request:\n")))))

  (testing "a workflow with no triggers at all is reported, not silently passed"
    ;; The nil-tolerance failure mode from BOU-250: a lockstep check that passes
    ;; when it cannot read what it compares against has stopped checking.
    (is (seq (sut/trigger-findings (wf "jobs:\n  a:\n    name: A\n"))))))

(deftest ^:unit push-must-be-scoped-so-same-repo-prs-do-not-run-ci-twice
  ;; An unscoped `push:` fires on every branch. With pull_request added, a
  ;; same-repo PR then runs the whole 58-job pipeline twice — once for the push,
  ;; once for the PR — under two different concurrency groups, so neither
  ;; cancels the other.
  (testing "unscoped push alongside pull_request is reported"
    (is (seq (sut/trigger-findings (wf "on:\n  push:\n  pull_request:\n")))))

  (testing "push scoped to main is clean"
    (is (empty? (sut/trigger-findings
                 (wf "on:\n  push:\n    branches: [main]\n  pull_request:\n"))))))

(deftest ^:unit publishing-must-require-the-same-context-merging-does
  ;; BOU-314: publish.yml guarded the *shape* of a release — the tag agrees with
  ;; source, nothing is half-published, everything landed — and never asked
  ;; whether the code works. A tag on a red or untested commit shipped 30
  ;; immutable Clojars coordinates, which cannot be recalled.
  (testing "a publish job with no CI guard is reported"
    (is (seq (sut/publish-findings
              (wf (str "jobs:\n  publish:\n    steps:\n"
                       "      - name: Checkout\n        uses: actions/checkout@v6\n"
                       "      - name: Publish\n        run: bb deploy --missing\n"))))))

  (testing "a guard naming the required context clears it"
    (is (empty? (sut/publish-findings
                 (wf (str "jobs:\n  publish:\n    steps:\n"
                          "      - name: Guard\n        run: |\n"
                          "          check=\"All Tests Passed\"\n"
                          "      - name: Publish\n        run: bb deploy --missing\n"))))))

  (testing "a guard that runs after the deploy is reported"
    ;; Verifying the release you already published is not a gate.
    (is (seq (sut/publish-findings
              (wf (str "jobs:\n  publish:\n    steps:\n"
                       "      - name: Publish\n        run: bb deploy --missing\n"
                       "      - name: Guard\n        run: |\n"
                       "          check=\"All Tests Passed\"\n"))))))

  (testing "`bb deploy --check-versions` is not a publishing step"
    ;; It reads versions and deploys nothing. Treating any `bb deploy` as the
    ;; publish would place the guard requirement one step too early and report
    ;; a correctly-ordered workflow.
    (is (empty? (sut/publish-findings
                 (wf (str "jobs:\n  publish:\n    steps:\n"
                          "      - name: Versions\n        run: bb deploy --check-versions 1.0.0\n"
                          "      - name: Guard\n        run: |\n"
                          "          check=\"All Tests Passed\"\n"
                          "      - name: Publish\n        run: bb deploy --missing\n"))))))

  (testing "a workflow with no publish job at all is reported, not silently passed"
    (is (seq (sut/publish-findings (wf "jobs:\n  something-else:\n    steps: []\n"))))))

(deftest ^:unit the-repository-is-currently-consistent
  ;; The regression guard, against the real ci.yml. No token, no API — which is
  ;; the whole point of requiring one context instead of fourteen.
  (let [{:keys [missing summary-name disabled]} (sut/summary-covers (sut/ci-workflow))]

    (testing "the summary job is named what branch protection requires"
      (is (= sut/summary-job-name summary-name)))

    (testing "every job that can run is covered"
      (is (empty? missing)
          (str "jobs that could fail without blocking a merge: " (pr-str missing))))

    (testing "and the only parked job is the one we expect"
      ;; Named rather than counted: a second parked job should be a decision,
      ;; not something that slips in behind a number.
      (is (= ["e2e"] disabled))))

  (testing "and every pull request — fork or not — actually triggers it"
    (is (empty? (sut/trigger-findings (sut/ci-workflow)))))

  (testing "and publishing requires the same context merging does"
    (is (empty? (sut/publish-findings (sut/publish-workflow))))))
