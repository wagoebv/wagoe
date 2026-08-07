(ns wagoe.tools.check-branch-protection-test
  "BOU-277: main required 13 status checks that no job has ever reported.

   They were the job *keys* from ci.yml — `lint`, `test-core` — while GitHub
   reports a check under the job's `name:`, and every job here has had one
   since Phase 0. So the contexts never matched anything, and were wrong from
   the day they were set rather than broken by any later rename.

   What makes it worth a gate is the failure mode: a required check that never
   reports is indistinguishable from one that always passes. While
   `enforce_admins` was off, the owner's override absorbed 13 permanently
   pending checks on every merge. Nothing failed; nothing said anything."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-yaml.core :as yaml]
            [wagoe.tools.check-branch-protection :as sut]))

(def ^:private ci-style
  "A workflow shaped like ci.yml: `on: push` with no filter, jobs with names."
  (yaml/parse-string
   (str "name: CI\n"
        "on:\n"
        "  push:\n"
        "jobs:\n"
        "  lint:\n"
        "    name: Lint\n"
        "  test-core:\n"
        "    name: Test wagoe/core\n"
        "  no-explicit-name:\n"
        "    runs-on: ubuntu-latest\n")))

(deftest ^:unit a-context-is-the-job-name-not-the-key
  ;; The whole defect in one assertion.
  (testing "an explicit name: wins over the key"
    (is (= "Lint" (sut/job-context :lint {:name "Lint"})))
    (is (= "Test wagoe/core" (sut/job-context :test-core {:name "Test wagoe/core"}))))

  (testing "the key is used only when there is no name:"
    (is (= "no-explicit-name" (sut/job-context :no-explicit-name {:runs-on "ubuntu-latest"}))))

  (testing "a workflow's contexts are the names, never the keys"
    (let [ctx (sut/workflow-contexts ci-style)]
      (is (contains? ctx "Lint"))
      (is (contains? ctx "Test wagoe/core"))
      (is (not (contains? ctx "test-core"))
          "requiring the key is what produced a permanently pending check")
      (is (contains? ctx "no-explicit-name")))))

(deftest ^:unit only-workflows-that-run-on-a-pr-can-be-required
  ;; Requiring a context from a nightly workflow reintroduces the same defect
  ;; by a different route: it never appears on a PR, so it can never be met.
  (testing "push with no filter, and pull_request, are visible"
    (is (sut/pr-visible-workflow? ci-style)
        "`push:` parses to nil, and treating that as absent hid every workflow")
    (is (sut/pr-visible-workflow?
         (yaml/parse-string "on:\n  pull_request:\njobs:\n  a:\n    name: A\n"))))

  (testing "a push filtered to tags is not"
    ;; publish.yml runs on release tags, never on a PR branch.
    (is (not (sut/pr-visible-workflow?
              (yaml/parse-string
               (str "on:\n  push:\n    tags:\n      - '[0-9]+.[0-9]+.[0-9]+'\n"
                    "jobs:\n  publish:\n    name: Publish\n"))))))

  (testing "schedule and workflow_dispatch are not"
    ;; brand-canary.yml and first-run-matrix.yml are nightly.
    (is (not (sut/pr-visible-workflow?
              (yaml/parse-string
               "on:\n  schedule:\n    - cron: '0 3 * * *'\njobs:\n  a:\n    name: A\n")))))

  (testing "a workflow that cannot run on a PR contributes no contexts"
    (is (empty? (sut/workflow-contexts
                 (yaml/parse-string
                  "on:\n  schedule:\n    - cron: '0 3 * * *'\njobs:\n  a:\n    name: A\n"))))))

(deftest ^:unit matrix-generated-names-are-not-treated-as-contexts
  ;; `Smoke — ${{ matrix.image }}` becomes one check per image at run time; the
  ;; literal is never reported. This looked like the hard case for the ticket
  ;; and is not one — the matrix workflow is nightly, so its jobs cannot be
  ;; required anyway. Excluded regardless, so a future PR-visible matrix job
  ;; cannot be compared against a string GitHub will never send.
  (testing "an unexpanded expression is excluded"
    (let [wf (yaml/parse-string
              (str "on:\n  push:\njobs:\n"
                   "  smoke:\n    name: Smoke — ${{ matrix.image }}\n"
                   "  plain:\n    name: Plain\n"))]
      (is (= #{"Plain"} (sut/workflow-contexts wf))))))

(deftest ^:unit reconcile-finds-what-happened-this-morning
  (let [emitted {"Lint" #{"ci.yml"} "Test wagoe/core" #{"ci.yml"}
                 "Test wagoe/admin" #{"ci.yml"} "All Tests Passed" #{"ci.yml"}}]

    (testing "required contexts no job emits are phantom"
      ;; The state main was actually in: job keys, not names.
      (let [{:keys [phantom]} (sut/reconcile #{"lint" "test-core" "test-admin"} emitted)]
        (is (= ["lint" "test-admin" "test-core"] phantom)
            "every one of these was blocking a merge while never able to fail")))

    (testing "a correct set is clean"
      (let [{:keys [phantom]} (sut/reconcile #{"Lint" "All Tests Passed"} emitted)]
        (is (empty? phantom))))

    (testing "test jobs outside the required set are reported, not failed"
      ;; Which jobs to require is policy; `All Tests Passed` covers them via
      ;; `needs:`. Worth surfacing so adding a library is a decision rather than
      ;; a silent widening of what cannot block.
      (let [{:keys [unguarded phantom]} (sut/reconcile #{"Lint" "All Tests Passed"} emitted)]
        (is (= ["Test wagoe/admin" "Test wagoe/core"] unguarded))
        (is (empty? phantom) "unguarded is a note, not a failure")))))

(deftest ^:unit the-repository-is-currently-consistent
  ;; The regression guard. Runs against the real workflows and the real
  ;; protection settings; skips rather than lies when it cannot read them.
  (testing "every required context is emitted by a PR-visible job"
    (let [required (sut/required-contexts)]
      (if (= :unavailable required)
        ;; Assert the skip rather than `(is true)`: this has to distinguish
        ;; "could not read" from "read and found nothing", and a placeholder
        ;; assertion would pass either way — the vacuous-gate problem this
        ;; whole ticket is about, in the test for it.
        (is (= :unavailable required)
            "gh has no admin-scoped token here, so protection cannot be read")
        (let [{:keys [phantom]} (sut/reconcile required (sut/emitted-contexts))]
          (is (empty? phantom)
              (str "required but never reported: " (pr-str phantom))))))))

(deftest ^:unit ci-yml-is-the-only-source-of-pr-checks
  ;; Measured against PR #368: ci.yml declared 51 jobs and GitHub reported 51
  ;; checks, with no difference in either direction. If another workflow starts
  ;; running on PRs this fails, which is the moment to revisit the assumption.
  (testing "exactly one workflow is PR-visible"
    (let [visible (->> (sut/workflow-files)
                       (filter #(sut/pr-visible-workflow? (yaml/parse-string (slurp %))))
                       (map #(.getName %)))]
      (is (= ["ci.yml"] visible)))))
