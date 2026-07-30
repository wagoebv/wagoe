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
  "The CI workflow source, or nil. `bb test:tools` runs from the repo root; a
   standalone run inside libs/tools does not — try both."
  []
  (let [cwd (System/getProperty "user.dir")]
    (some (fn [f] (when (.exists ^java.io.File f) (slurp f)))
          [(io/file cwd ".github" "workflows" "ci.yml")
           (io/file cwd ".." ".." ".github" "workflows" "ci.yml")])))

(defn- ci-invoked-gates
  "Gate names CI runs directly, from `run: bb check:<gate>` lines."
  [yaml]
  (->> (re-seq #"run:\s+bb\s+check:([a-z-]+)" yaml)
       (map second)
       set))

(deftest ^:unit every-aggregate-check-also-runs-in-ci
  (testing "each bb check:<gate> in all-checks has a CI job invoking it"
    (if-let [yaml (ci-workflow)]
      (let [ci (ci-invoked-gates yaml)]
        (doseq [{:keys [id cmd]} check/all-checks
                :when (and (= "bb" (first cmd))
                           (str/starts-with? (second cmd) "check:"))
                :let [gate (subs (second cmd) (count "check:"))]]
          (is (contains? ci gate)
              (str "check:" gate " (" id ") is in all-checks but no CI job runs it"))))
      (println "skipping: .github/workflows/ci.yml not reachable from" (System/getProperty "user.dir")))))

(deftest ^:unit every-ci-gate-is-also-in-the-aggregate-check
  (testing "each check:<gate> CI runs is reachable from `bb check`"
    (if-let [yaml (ci-workflow)]
      (let [aggregate (->> check/all-checks
                           (map :cmd)
                           (filter #(and (= "bb" (first %))
                                         (str/starts-with? (second %) "check:")))
                           (map #(subs (second %) (count "check:")))
                           set)]
        (doseq [gate (ci-invoked-gates yaml)]
          (is (contains? aggregate gate)
              (str "check:" gate " runs in CI but is missing from all-checks, so "
                   "`bb check --ci` passes without it"))))
      (println "skipping: .github/workflows/ci.yml not reachable"))))

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
