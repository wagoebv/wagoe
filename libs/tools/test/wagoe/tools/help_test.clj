(ns wagoe.tools.help-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.help :as help]))

;; =============================================================================
;; Error catalog
;; =============================================================================

(deftest ^:unit error-catalog-completeness
  (testing "all error codes have required fields"
    (doseq [[code entry] @help/error-catalog]
      (is (string? (:title entry)) (str code " missing :title"))
      (is (string? (:description entry)) (str code " missing :description"))
      (is (string? (:fix entry)) (str code " missing :fix"))))

  (testing "error codes follow BND-xxx format"
    (doseq [code (keys @help/error-catalog)]
      (is (re-matches #"BND-\d{3}" code)
          (str "invalid error code format: " code))))

  (testing "runtime codes are present"
    (is (some? (get @help/error-catalog "BND-201")) "BND-201 must resolve")
    (is (some? (get @help/error-catalog "BND-601")) "BND-601 must resolve"))

  (testing "both families present"
    (let [codes (set (keys @help/error-catalog))]
      (is (some #(str/starts-with? % "BND-1") codes) "BND-1xx config family")
      (is (some #(str/starts-with? % "BND-2") codes) "BND-2xx validation family")
      (is (some #(str/starts-with? % "BND-6") codes) "BND-6xx FC/IS family")
      (is (some #(str/starts-with? % "BND-7") codes) "BND-7xx tooling family"))))

;; BOU-76: namespace-load must not depend on the catalogue resource, so that a
;; consumer project depending on boundary-tools alone can run any bb task.
(deftest ^:unit read-catalog-degrades-gracefully
  (testing "missing resource yields {} instead of throwing (consumer w/o devtools)"
    (is (= {} (#'help/read-catalog nil))))

  (testing "present resource parses to a populated map"
    (is (seq (#'help/read-catalog (io/resource "wagoe/devtools/error_catalog.edn")))))

  (testing "error-catalog is a lazy delay, not eagerly realized at ns-load"
    (is (instance? clojure.lang.Delay help/error-catalog)))

  (testing "`bb guide error` degrades gracefully when catalogue is empty"
    (with-redefs [help/error-catalog (delay {})]
      (let [output (with-out-str (#'help/help-error "BND-003"))]
        (is (re-find #"(?i)not available" output))))))

;; =============================================================================
;; Topic functions
;; =============================================================================

(deftest ^:unit topic-fns-cover-all-documented-topics
  (testing "expected topics are registered"
    (let [topics (set (keys help/topic-fns))]
      (is (contains? topics "scaffold"))
      (is (contains? topics "testing"))
      (is (contains? topics "database"))
      (is (contains? topics "fcis"))
      (is (contains? topics "config")))))

;; =============================================================================
;; State analysis (pure functions via #')
;; =============================================================================

(deftest ^:unit integrated?-test
  (testing "detects module path in deps.edn text"
    (is (#'help/integrated? "\"libs/admin/src\"" "admin"))
    (is (not (#'help/integrated? "\"libs/admin/src\"" "user")))))

(deftest ^:unit non-module-libs-excludes-infrastructure
  (testing "tools, devtools, and e2e are excluded"
    (let [libs @#'help/non-module-libs]
      (is (contains? libs "tools"))
      (is (contains? libs "devtools"))
      (is (contains? libs "e2e")))))

;; =============================================================================
;; General help output
;; =============================================================================

(deftest ^:unit help-general-includes-new-commands
  (testing "general help lists all new Phase 1 commands"
    (let [output (with-out-str ((var help/help-general)))]
      (is (re-find #"bb quickstart" output) "missing quickstart")
      (is (re-find #"bb check\b" output) "missing check")
      (is (re-find #"bb doctor:env" output) "missing doctor:env")
      (is (re-find #"bb doctor --all" output) "missing doctor --all")
      (is (re-find #"bb db:status" output) "missing db:status")
      (is (re-find #"bb db:reset" output) "missing db:reset")
      (is (re-find #"bb db:seed" output) "missing db:seed"))))
