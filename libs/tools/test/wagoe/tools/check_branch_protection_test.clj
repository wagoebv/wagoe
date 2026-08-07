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
      (is (= ["e2e"] disabled)))))
