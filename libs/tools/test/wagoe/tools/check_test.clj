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
      (is (keyword? (:id c)) (str "check missing :id"))
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
