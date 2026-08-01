(ns wagoe.tools.gate-firing-test
  "Proof that each quality gate still detects something (BOU-250).

   The gates already have unit tests, but they feed a detector a string and
   assert on the return value. That proves the predicate works. It does not
   prove the gate would notice a real violation, because the two ways a gate
   goes quiet are both outside the predicate:

     - it scans nothing (docs-lint's alias check was live but its file list
       never included dev-docs, CONTRIBUTING.md or most libs/*/AGENTS.md);
     - it is wired to nothing (CI's Test wagoe/push ran `-M:test:db/h2`, a
       dead alias, and reported green over zero tests for months).

   So these tests plant a violation and assert the gate reports it. Where a
   gate can be pointed at a directory, the fixture is a real tree on disk, so
   discovery is exercised alongside detection.

   `-main` is never called: every gate exits the process. That is why each
   gate needs a seam that returns a verdict — adding one is part of bringing a
   gate under this test."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.tools.check :as check]
            [wagoe.tools.check-deps :as check-deps]
            [wagoe.tools.check-fcis :as check-fcis]
            [wagoe.tools.check-hygiene :as check-hygiene]
            [wagoe.tools.check-tests :as check-tests]
            [wagoe.tools.docs-lint :as docs-lint]))

;; =============================================================================
;; Fixture helpers
;; =============================================================================

(defn- temp-dir
  "A fresh directory under the system temp dir. Named from `label` and the
   caller-supplied `n` rather than a random value, so a leftover directory can
   be traced back to the test that made it."
  [label n]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "wagoe-gate-" label "-" n))]
    (.mkdirs d)
    d))

(defn- spit-file!
  "Write `content` to `path` under `root`, creating parent directories."
  [root path content]
  (let [f (io/file root path)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- delete-tree! [^java.io.File f]
  (when (.isDirectory f)
    (run! delete-tree! (.listFiles f)))
  (.delete f))

;; =============================================================================
;; check:hygiene
;; =============================================================================

(deftest ^:unit hygiene-gate-fires-test
  (testing "backup cruft is detected"
    (let [cruft (check-hygiene/cruft-files
                 ["src/wagoe/core.clj"
                  "src/wagoe/core.clj.bak"
                  "src/wagoe/core.clj.bak2"
                  "libs/user/src/user.clj.backup"
                  "docs/notes.orig"
                  "dev/scratch.clj~"])]
      (is (= ["dev/scratch.clj~"
              "docs/notes.orig"
              "libs/user/src/user.clj.backup"
              "src/wagoe/core.clj.bak"
              "src/wagoe/core.clj.bak2"]
             cruft)
          "every backup spelling must be caught, and the output is sorted")))

  (testing "clean input yields no findings"
    (is (empty? (check-hygiene/cruft-files
                 ["src/wagoe/core.clj" "test/wagoe/core_test.clj"]))))

  (testing "the .skip convention is deliberately allowed"
    ;; A disabled test is intentional, not cruft. Asserted so a future widening
    ;; of the pattern cannot silently start rejecting it.
    (is (empty? (check-hygiene/cruft-files ["test/wagoe/slow_test.clj.skip"])))))

;; =============================================================================
;; check:deps
;; =============================================================================

(deftest ^:unit deps-gate-fires-test
  (testing "a dependency cycle is detected"
    (let [cycles (check-deps/find-all-cycles {"a" #{"b"} "b" #{"a"}})]
      (is (seq cycles) "a two-node cycle must be reported")))

  (testing "a longer cycle is detected"
    (is (seq (check-deps/find-all-cycles {"a" #{"b"} "b" #{"c"} "c" #{"a"}}))))

  (testing "an acyclic graph yields nothing"
    (is (empty? (check-deps/find-all-cycles {"a" #{"b"} "b" #{"c"} "c" #{}}))))

  (testing "a dependency on core is detected in both the declared and actual graph"
    (let [violations (check-deps/check-core-independence
                      {"core" #{"platform"}}
                      {"core" #{"user"}})]
      (is (= #{:core-declared-dep :core-actual-dep} (set (map :type violations)))
          "core must stay dependency-free in both graphs")))

  (testing "an independent core yields nothing"
    (is (empty? (check-deps/check-core-independence {"core" #{}} {"core" #{}})))))

;; =============================================================================
;; check:fcis — discovery AND detection, against a real tree
;; =============================================================================

(deftest ^:unit fcis-gate-fires-test
  (let [root (temp-dir "fcis" 1)]
    (try
      ;; Mirrors the monorepo layout the gate claims to cover.
      (spit-file! root "libs/demo/src/wagoe/demo/core/logic.clj"
                  "(ns wagoe.demo.core.logic\n  (:require [wagoe.demo.shell.persistence :as db]))\n")
      (spit-file! root "libs/demo/src/wagoe/demo/core/clean.clj"
                  "(ns wagoe.demo.core.clean)\n(defn add [a b] (+ a b))\n")
      ;; A shell file must be ignored — the gate polices core/ only.
      (spit-file! root "libs/demo/src/wagoe/demo/shell/service.clj"
                  "(ns wagoe.demo.shell.service\n  (:require [clojure.java.io :as io]))\n")

      (let [found (check-fcis/core-source-paths root)
            names (set (map #(.getName ^java.io.File %) found))]
        (testing "discovery finds core files under the project root"
          (is (contains? names "logic.clj"))
          (is (contains? names "clean.clj"))
          (is (not (contains? names "service.clj"))
              "shell files are outside this gate's remit"))

        (testing "a core namespace requiring shell is reported"
          (let [violating (first (filter #(= "logic.clj" (.getName ^java.io.File %)) found))
                violations (check-fcis/check-file violating {:allow-throw #{}
                                                             :allow-mutable-state #{}})]
            (is (seq violations)
                "core requiring shell.persistence is the canonical FC/IS violation")))

        (testing "a pure core namespace is not reported"
          (let [clean (first (filter #(= "clean.clj" (.getName ^java.io.File %)) found))]
            (is (empty? (check-fcis/check-file clean {:allow-throw #{}
                                                      :allow-mutable-state #{}}))))))
      (finally (delete-tree! root)))))

;; =============================================================================
;; check:placeholder-tests
;; =============================================================================

(deftest ^:unit placeholder-tests-gate-fires-test
  (testing "a placeholder assertion is detected"
    (let [hits (check-tests/scan-content
                "test.clj"
                "(deftest thing-test\n  (is true))\n")]
      (is (seq hits) "(is true) is the assertion this gate exists to reject")))

  (testing "a real assertion is not flagged"
    (is (empty? (check-tests/scan-content
                 "test.clj"
                 "(deftest thing-test\n  (is (= 2 (+ 1 1))))\n"))))

  (testing "a placeholder inside a comment is not flagged"
    ;; The gate strips comments and strings first; asserted so that stripping
    ;; cannot regress into either direction unnoticed.
    (is (empty? (check-tests/scan-content
                 "test.clj"
                 "(deftest thing-test\n  ;; was (is true)\n  (is (= 1 1)))\n")))))

;; =============================================================================
;; docs:lint
;; =============================================================================

(deftest ^:unit docs-lint-gate-fires-test
  (testing "an alias absent from every known set is a failing finding"
    (is (seq (docs-lint/failing-warnings
              {:warnings [{:type :unknown-alias :file "AGENTS.md" :line 1
                           :message "Unknown deps.edn alias: :db/h2"}]}))))

  (testing "broken links and unknown namespaces are reported but do not fail"
    ;; Deliberate: that debt is BOU-253's, and failing on it would hold CI red
    ;; without anyone able to clear it in passing.
    (is (empty? (docs-lint/failing-warnings
                 {:warnings [{:type :broken-link :file "x.adoc"}
                             {:type :unknown-namespace :file "y.adoc"}]}))))

  (testing "the generated-project alias set is part of the known aliases"
    ;; `wagoe new` projects define :repl and have no :repl-clj. Validating their
    ;; documentation against the monorepo alone reported correct commands as
    ;; broken, and the obvious fix broke the quickstart (PR #345).
    (let [template (docs-lint/discover-template-aliases)]
      (is (contains? template :repl)
          "the template's :repl must be known, or quickstart docs go red")
      (is (not (contains? template :extra-deps))
          "nested keys inside an alias body are not aliases"))))

;; =============================================================================
;; The meta-gate: every gate must be represented above
;; =============================================================================

(def gates-with-firing-tests
  "Gate ids from `check/all-checks` that this namespace proves can still fire.

   Adding a gate to `all-checks` without adding it here fails the test below.
   That is the point: a gate nobody can prove fires is indistinguishable from
   one that does not run."
  #{:hygiene :deps :fcis :placeholder-tests :docs-lint})

(def gates-without-firing-tests
  "Gates that cannot yet be proven to fire, each with the reason.

   This is a to-do list with a test attached, not an exemption: entries move to
   `gates-with-firing-tests` as seams are added. Listing them here keeps the
   count honest — the alternative is a green suite that implies coverage the
   repo does not have."
  {:ports             "collect-violations is public; needs a fixture tree like the fcis test"
   :test-meta         "check-deftest-metadata prints and exits; no verdict seam"
   :test-tags         "check-test-tags prints and exits; no verdict seam"
   :agents            "agents-gen --check compares generated sections only; seam unclear"
   :poms              "check-lib is public but needs a fixture lib tree"
   :no-boundary       "shells out to git grep; needs a fixture repo"
   :linting           "clj-kondo is third-party; firing is its own concern"
   :doctor            "validates config; needs a fixture config tree"})

(deftest ^:unit every-gate-is-accounted-for-test
  (let [declared (set (map :id check/all-checks))
        covered  (into gates-with-firing-tests (keys gates-without-firing-tests))]

    (testing "every gate in all-checks is either proven or explicitly listed as unproven"
      (is (empty? (remove covered declared))
          (str "Gate(s) added to all-checks with no firing test and no entry in "
               "gates-without-firing-tests: " (pr-str (remove covered declared))
               ". Add a test above, or record why it cannot be tested yet.")))

    (testing "the unproven list does not name gates that no longer exist"
      (is (empty? (remove declared covered))
          (str "Stale entries: " (pr-str (remove declared covered)))))

    (testing "the two lists are disjoint"
      (is (empty? (filter gates-with-firing-tests (keys gates-without-firing-tests)))))

    (testing "every unproven gate carries a reason"
      (doseq [[gate reason] gates-without-firing-tests]
        (is (and (string? reason) (not (str/blank? reason)))
            (str gate " must say why it cannot be proven to fire"))))))
