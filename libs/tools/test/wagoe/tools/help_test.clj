(ns wagoe.tools.help-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.check]
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
;; consumer project depending on wagoe-tools alone can run any bb task.
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

(deftest ^:unit the-framework-repo-is-not-asked-a-generated-projects-question
  ;; `bb guide next` reported wagoe-cli and wagoe-mcp as "unintegrated modules"
  ;; in this repository and told the reader to run `bb scaffold integrate` on
  ;; them. Both are deliberately standalone, and the monorepo puts its libraries
  ;; on :paths rather than in :deps, so the question does not apply here at all.
  ;;
  ;; It was suppressed by a hand-maintained list of library names to skip —
  ;; #{"tools" "devtools" "e2e"} — which had never heard of the two added since.
  ;; A test that asserted the list contained those three names could not have
  ;; caught that; this asserts the behaviour instead (BOU-324).
  (let [results (#'help/check-unintegrated-modules)]
    (is (= [:pass] (map :level results)))
    (is (re-find #":paths" (:msg (first results)))
        "and says why, rather than passing silently")

    (testing "nothing is named as needing integration"
      (is (not-any? #(re-find #"wagoe-cli|wagoe-mcp|scaffold integrate" (str (:msg %) (:fix %)))
                    results)))))

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

;; =============================================================================
;; What `bb guide` advertises depends on where it runs (BOU-325)
;; =============================================================================

(deftest ^:unit guide-does-not-offer-deployment-outside-the-framework-repo
  ;; `bb guide` ships in every generated project, and it printed a Deployment
  ;; section telling the user to run `bb deploy --all` — a task their bb.edn
  ;; does not define, about libraries that are not theirs. It publishes Wagoe
  ;; to Clojars.
  (testing "in a generated project the section is absent"
    (with-redefs [wagoe.tools.check/framework-repo? (constantly false)]
      (let [out (with-out-str (help/-main))]
        (is (not (str/includes? out "bb deploy")))
        (is (not (str/includes? out "Deployment:")))

        (testing "and what remains still tells the user what to do"
          (is (str/includes? out "bb scaffold"))
          (is (str/includes? out "bb check"))))))

  (testing "in the framework repo it is there"
    (with-redefs [wagoe.tools.check/framework-repo? (constantly true)]
      (is (str/includes? (with-out-str (help/-main)) "bb deploy --all")))))

(deftest ^:unit optional-things-are-not-reported-as-problems
  ;; A warning a reader cannot clear by doing anything sensible is what teaches
  ;; them to skim past the ones that matter. Both of these fired on every
  ;; freshly generated project (BOU-324).
  (testing "no migrations is normal — `wagoe new` writes none, and the user"
    ;; module creates its four tables through :wagoe/user-db-schema, not a
    ;; migration. The old fix text said to run `clojure -M:migrate up`, which
    ;; does nothing without a directory.
    (let [results (#'help/check-migrations)]
      (is (= [:pass] (map :level results)))
      (is (not-any? :fix results) "a pass has nothing to fix")))

  (testing "and no seed file is normal — `bb db:seed` reads one if it is there"
    (let [results (#'help/check-seeds)]
      (is (= [:pass] (map :level results)))
      (is (not-any? :fix results))))

  (testing "the message still says where each would go, so it teaches"
    (is (re-find #"resources/migrations/" (:msg (first (#'help/check-migrations)))))
    (is (re-find #"resources/seeds/dev\.edn" (:msg (first (#'help/check-seeds)))))))

(deftest ^:unit guide-next-is-silent-on-a-healthy-project
  ;; The ticket's own done criterion: "there is one answer, and it contains no
  ;; noise on a healthy project". This repository is one.
  (let [results (#'help/next-results)]
    (is (seq results) "otherwise this passes by checking nothing")
    (is (= #{:pass} (set (map :level results)))
        (str "not clean: "
             (pr-str (map :msg (remove #(= :pass (:level %)) results)))))))
