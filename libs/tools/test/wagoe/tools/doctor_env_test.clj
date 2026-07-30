(ns wagoe.tools.doctor-env-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.doctor-env :as doctor-env]
            [wagoe.tools.doctor :as doctor]))

;; =============================================================================
;; parse-version
;; =============================================================================

(deftest ^:unit parse-version-test
  (testing "parses Java version strings"
    (is (= 21 (#'doctor-env/parse-version
               "openjdk version \"21.0.2\" 2024-01-16"
               #"(?:version\s+\"?)(\d+)")))
    (is (= 17 (#'doctor-env/parse-version
               "openjdk version \"17.0.10\" 2024-01-16"
               #"(?:version\s+\"?)(\d+)"))))

  (testing "returns nil for unparseable input"
    (is (nil? (#'doctor-env/parse-version nil #"(\d+)")))
    (is (nil? (#'doctor-env/parse-version "no version here" #"version\s+(\d+)")))))

;; =============================================================================
;; Argument parsing
;; =============================================================================

(deftest ^:unit parse-args-test
  (testing "defaults when no args"
    (let [opts (#'doctor-env/parse-args [])]
      (is (false? (:ci opts)))
      (is (nil? (:help opts)))))

  (testing "parses --ci"
    (is (true? (:ci (#'doctor-env/parse-args ["--ci"])))))

  (testing "parses --help"
    (is (true? (:help (#'doctor-env/parse-args ["--help"]))))))

;; =============================================================================
;; Check functions (these hit real system state, so we verify structure)
;; =============================================================================

(deftest ^:unit check-functions-return-valid-maps
  (testing "each check returns a map with :id, :level, :msg"
    (doseq [[check-name check-fn] {"java"       doctor-env/check-java
                                   "clojure"    doctor-env/check-clojure-cli
                                   "babashka"   doctor-env/check-babashka
                                   "node"       doctor-env/check-node
                                   "ports"      doctor-env/check-ports
                                   "kondo"      doctor-env/check-clj-kondo
                                   "ai"         doctor-env/check-ai-providers}]
      (let [result (check-fn)]
        (is (keyword? (:id result)) (str check-name " missing :id"))
        (is (contains? #{:pass :warn :error} (:level result))
            (str check-name " has invalid :level " (:level result)))
        (is (string? (:msg result)) (str check-name " missing :msg"))))))

;; =============================================================================
;; Doctor --all flag
;; =============================================================================

(deftest ^:unit doctor-parse-args-all-flag
  (testing "doctor parses --all flag"
    (let [opts (#'wagoe.tools.doctor/parse-args ["--all"])]
      (is (true? (:all opts)))))

  (testing "doctor --all combined with --ci"
    (let [opts (#'wagoe.tools.doctor/parse-args ["--all" "--ci"])]
      (is (true? (:all opts)))
      (is (true? (:ci opts)))))

  (testing "doctor defaults to no --all"
    (let [opts (#'wagoe.tools.doctor/parse-args [])]
      (is (false? (:all opts))))))
