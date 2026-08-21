(ns wagoe.tools.check-isolation-test
  "BOU-304/BOU-291: \"30 independently publishable libraries\" was documented,
   not checked. No CI job ever built a library against its own deps.edn.

   The obvious gate — compile each library in isolation — turns out not to
   prove the claim. Measured before this was written: 30 of 31 libraries
   compile clean against their own deps.edn, `realtime` among them, while
   `realtime` is the library the assessment names as broken. Its
   `jwt_adapter.clj` requires `wagoe.user.shell.auth` at the top level *inside a
   try/catch*, so the require runs, fails, and is swallowed. The namespace loads;
   the adapter throws on first use, from Clojars, in a user's application.

   So a compile job answers \"does it load\", and the claim is \"does it work
   without the libraries it does not declare\". This gate answers the second
   one, statically: a library may not reach for a namespace it neither owns nor
   declares, whichever loading trick it uses to get there."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.tools.check-isolation :as sut]))

;; =============================================================================
;; Who owns which namespace
;; =============================================================================

(deftest ^:unit ownership-is-read-from-the-tree-not-from-the-directory-name
  ;; There is no string rule that maps all 31: `shared-ui` owns `wagoe.shared`,
  ;; `wagoe-cli` owns `wagoe.cli`, `ui-style` owns `wagoe.ui-style`. A convention
  ;; the tree does not follow is a convention that has to be maintained by hand,
  ;; which is what every other drift in this epic started as.
  (let [owners (sut/namespace-owners)]
    (testing "the awkward cases resolve to the right library"
      (is (= "shared-ui" (get owners "wagoe.shared")))
      (is (= "wagoe-cli" (get owners "wagoe.cli")))
      (is (= "wagoe-mcp" (get owners "wagoe.mcp")))
      (is (= "ui-style"  (get owners "wagoe.ui-style"))))

    (testing "and the ordinary ones do too"
      (is (= "realtime" (get owners "wagoe.realtime")))
      (is (= "user"     (get owners "wagoe.user"))))

    (testing "the map covers the whole tree"
      (is (<= 30 (count owners))))))

;; =============================================================================
;; The namespace list the isolated-build matrix loads
;; =============================================================================

(deftest ^:unit namespaces-are-munged-the-way-clojure-munges-them
  ;; This started as `find | sed -e 's|\.cljc\?$||' …` in the workflow. `\?` is
  ;; GNU basic-regex: BSD sed leaves the extension on, so the emitted form was
  ;; `(require 'wagoe.shared.ui.core.alpine.clj)`. Green on the Ubuntu runner,
  ;; broken for anyone reproducing the job locally — the same portability trap
  ;; `bb bump` removed from the release procedure.
  (testing "the extension is stripped and underscores become hyphens"
    (let [nss (sut/namespaces-of "shared-ui")]
      (is (seq nss))
      (is (not-any? #(str/ends-with? % ".clj") nss))
      (is (not-any? #(str/includes? % "_") nss))
      (is (every? #(str/starts-with? % "wagoe.") nss))))

  (testing "a hyphenated library directory munges back to a hyphenated namespace"
    ;; libs/ui-style/src/wagoe/ui_style.clj — the directory keeps the hyphen,
    ;; the file takes an underscore, and the namespace has the hyphen back.
    ;; Membership rather than equality: this is about the munging, and pinning
    ;; the whole list made adding a second namespace to the library a failure.
    (is (some #{"wagoe.ui-style"} (sut/namespaces-of "ui-style")))
    (is (not-any? #(str/includes? % "_") (sut/namespaces-of "ui-style"))))

  (testing "the require form loads all of them"
    (let [form (sut/require-form "ui-style")]
      (is (str/starts-with? form "(do "))
      (is (str/includes? form "(require 'wagoe.ui-style)"))))

  (testing "a library with no src/ yields no form rather than a broken one"
    ;; libs/e2e carries a deps.edn and no namespaces. A job that failed on it
    ;; would be reporting the wrong thing.
    (is (= "" (sut/require-form "e2e")))))

;; =============================================================================
;; What counts as reaching outside the library
;; =============================================================================

(deftest ^:unit a-dynamic-load-of-another-librarys-namespace-is-a-finding
  ;; realtime's actual code, which compiles clean.
  (let [text (str "(try\n"
                  "  (require '[wagoe.user.shell.auth :as user-auth])\n"
                  "  (catch Exception _e nil))\n")]
    (testing "a require hidden in a try/catch is still a dependency"
      (let [[f :as fs*] (sut/smuggle-findings "realtime" #{} text "jwt_adapter.clj")]
        (is (= 1 (count fs*)))
        (is (= "wagoe.user.shell.auth" (:namespace f)))
        (is (= "user" (:lib f)))
        (is (= 2 (:line f)))))

    (testing "and declaring the dependency clears it"
      (is (empty? (sut/smuggle-findings "realtime" #{"user"} text "jwt_adapter.clj"))))))

(deftest ^:unit a-static-require-counts-even-though-it-is-not-dynamic
  ;; Review finding. The gate read quoted symbols only, on the reasoning that
  ;; the isolated-build matrix covers static requires. That reasoning has a hole
  ;; in exactly one place, and it is the place it can least afford: `libs/tools`
  ;; is excluded from the matrix, because its runtime is Babashka rather than
  ;; the JVM. A normal undeclared `:require` there passed both jobs.
  (testing "an ns-form require of another library is a finding"
    (let [[f] (sut/smuggle-findings
               "tools" #{}
               "(ns wagoe.tools.x\n  (:require [wagoe.user.shell.auth :as a]))"
               "x.clj")]
      (is (= "wagoe.user.shell.auth" (:namespace f)))
      (is (= 2 (:line f)))))

  (testing "a fully qualified call is a finding too"
    ;; No require in sight, and still a dependency.
    (is (= ["wagoe.user.shell.auth"]
           (map :namespace (sut/smuggle-findings
                            "tools" #{} "(wagoe.user.shell.auth/validate token)" "x.clj")))))

  (testing "and declaring it clears both"
    (is (empty? (sut/smuggle-findings
                 "tools" #{"user"}
                 "(ns x (:require [wagoe.user.shell.auth :as a]))\n(wagoe.user.shell.auth/v 1)"
                 "x.clj")))))

(deftest ^:unit code-only-does-not-lose-track-of-the-file
  ;; Both of these desynchronised the scanner, and both were found by the gate
  ;; reporting things that were not there.
  (testing "a character literal is not the start of a string"
    ;; Clojure writes a literal double-quote as backslash-quote. Read as an
    ;; opening quote, everything after it becomes 'string' and the rest of the
    ;; file is scanned wrong — which is how this namespace's own docstrings were
    ;; handed back as code and reported against libs/tools.
    (let [text (str "(defn f [] (= c \\\" ))\n"
                    "(defn g \"doc: (require '[wagoe.user.indoc :as d])\" [] nil)\n"
                    "(require '[wagoe.user.real :as r])\n")]
      (is (= ["wagoe.user.real"]
             (map :namespace (sut/smuggle-findings "tools" #{} text "x.clj"))))))

  (testing "a (comment …) block is read but never evaluated"
    ;; platform's ports/http.clj carries a worked example in one, naming four
    ;; wagoe.user namespaces. They reached the burn-down list as a dependency
    ;; platform 'has to invert', with a justification written for something that
    ;; does not happen.
    (is (empty? (sut/smuggle-findings
                 "platform" #{}
                 (str "(comment\n"
                      "  (def routes [{:handler 'wagoe.user.shell.handlers/list}])\n"
                      "  (def s wagoe.user.schema/Query))\n")
                 "http.clj"))))

  (testing "a paren inside a string does not end the block early"
    ;; If it did, everything after the example would be scanned as code again.
    (is (empty? (sut/smuggle-findings
                 "platform" #{}
                 (str "(comment\n"
                      "  (println \")\")\n"
                      "  wagoe.user.after/thing)\n")
                 "http.clj")))))

(deftest ^:unit every-loading-trick-is-covered
  ;; Each of these appears in the tree. Matching only `require` would leave the
  ;; other three as the way round the gate — and a gate with a documented way
  ;; round it is decoration.
  (doseq [[label form] [["require"           "(require '[wagoe.user.shell.auth :as a])"]
                        ["bare require"      "(require 'wagoe.user.shell.auth)"]
                        ["the-ns"            "(the-ns 'wagoe.user.shell.auth)"]
                        ["resolve"           "(resolve 'wagoe.user.shell.auth/validate)"]
                        ["requiring-resolve" "(requiring-resolve 'wagoe.user.shell.auth/validate)"]]]
    (testing label
      (is (seq (sut/smuggle-findings "realtime" #{} form "f.clj"))
          (str label " is a way to reach another library")))))

(deftest ^:unit a-library-may-reach-into-itself
  (testing "its own namespaces are not findings"
    (is (empty? (sut/smuggle-findings
                 "realtime" #{}
                 "(requiring-resolve 'wagoe.realtime.shell.server/start)" "f.clj")))))

(deftest ^:unit a-namespace-no-library-owns-is-still-a-finding
  ;; `wagoe.test-support` lives in the application, in no library. A published
  ;; library reaching for it is broken in the worst way available — that is the
  ;; one namespace a downstream user certainly does not have. `wagoe.config` was
  ;; the other, until BOU-306 made it a library.
  (let [[f] (sut/smuggle-findings "platform" #{} "(require 'wagoe.test-support.shell.reset)" "f.clj")]
    (is (= "wagoe.test-support.shell.reset" (:namespace f)))
    (is (nil? (:lib f)) "no library owns it")))

(deftest ^:unit keywords-and-prose-are-not-dependencies
  ;; The reason this gate reads loading forms rather than every `wagoe.*` string.
  ;; A first pass over the tree that matched any occurrence reported 19 of 31
  ;; libraries, almost all of it Integrant keys and docstrings.
  (testing "an Integrant key names a component, not a namespace to load"
    (is (empty? (sut/smuggle-findings
                 "platform" #{} "{:wagoe.observability/logger {}}" "config.clj"))))

  (testing "a docstring may mention another library"
    (is (empty? (sut/smuggle-findings
                 "realtime" #{}
                 "(defn f \"Delegates to wagoe.user.shell.auth when present.\" [] nil)"
                 "f.clj"))))

  (testing "a commented-out require is not a dependency"
    ;; Found by the gate reporting its own docstring. Only a whole-line comment
    ;; is skipped — a trailing `; note` after real code must not take the code
    ;; with it, because a false negative is the one thing this gate cannot have.
    (is (empty? (sut/smuggle-findings
                 "realtime" #{} ";; (require '[wagoe.user.shell.auth :as a])" "f.clj")))
    (is (seq (sut/smuggle-findings
              "realtime" #{}
              "(require '[wagoe.user.shell.auth :as a]) ; still a dependency"
              "f.clj"))))

  (testing "and so may an error message"
    (is (empty? (sut/smuggle-findings
                 "realtime" #{}
                 "(throw (ex-info \"wagoe.user.shell.auth/validate not found\" {}))"
                 "f.clj")))))

;; =============================================================================
;; Allowlist
;; =============================================================================

(deftest ^:unit an-exemption-must-say-why
  ;; Same rule as check-doc-counts: an allowlist entry with no stated reason is
  ;; indistinguishable from an unfixed bug (BOU-250).
  (testing "an entry without :why is rejected, not honoured"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/parse-allowlist {:allow [{:lib "realtime" :namespace "wagoe.user"}]}))))

  (testing "an entry with :why is honoured"
    (is (= #{["realtime" "wagoe.user"]}
           (sut/parse-allowlist
            {:allow [{:lib "realtime" :namespace "wagoe.user" :why "burn-down: BOU-305"}]})))))

;; =============================================================================
;; The repository, right now
;; =============================================================================

(deftest ^:unit the-gate-reads-the-real-tree
  (testing "it finds libraries to check"
    (is (<= 30 (count (sut/libs)))))

  (testing "and the burn-down list is what the allowlist says it is"
    ;; Not asserting zero: this gate ships red-by-default with a justified
    ;; allowlist, and BOU-305/306/307 empty it. Asserting that findings and
    ;; allowlist agree is what makes the list a burn-down rather than a drawer.
    (let [unexplained (sut/unexplained-findings)]
      ;; :lib* is the library doing the smuggling; :lib is the one that owns the
      ;; namespace. Reporting :lib here sent the reader to the wrong library.
      (is (empty? unexplained)
          (str "smuggled dependencies with no allowlist entry: "
               (pr-str (map (juxt :lib* :namespace) unexplained)))))))

(deftest ^:unit matrix-covers-every-library
  ;; The isolated-build matrix lists its libraries by hand — a YAML matrix
  ;; cannot glob. That is one hand-maintained copy of a fact the tree already
  ;; holds, which is the drift every other gate in this epic exists to stop, so
  ;; it gets the same treatment: the copy is checked against the source.
  ;;
  ;; Without this, a library added under libs/ gets no isolated build and
  ;; nothing says so — check:branch-protection reads job keys, not matrix
  ;; values, so the job list still looks complete.
  (let [yaml     (slurp ".github/workflows/ci.yml")
        block    (second (re-find #"(?s)check-isolation-matrix:.*?lib:\s*\[(.*?)\]" yaml))
        in-ci    (set (remove str/blank? (map str/trim (str/split (str block) #"[,\s]+"))))
        excluded #{"tools" "e2e"}
        expected (remove excluded (sut/libs))]

    (testing "the matrix was found in the workflow at all"
      ;; A regex that stops matching would otherwise make this test pass by
      ;; comparing two empty sets.
      (is (seq in-ci)))

    (testing "every library has a cell, except the two documented exclusions"
      (is (empty? (remove in-ci expected))
          (str "libraries with no isolated build: " (pr-str (remove in-ci expected)))))

    (testing "and the matrix names no library that no longer exists"
      (is (empty? (remove (set (sut/libs)) in-ci))
          (str "cells for libraries not in libs/: "
               (pr-str (remove (set (sut/libs)) in-ci)))))

    (testing "the exclusions are deliberate, not a gap"
      ;; tools' runtime is bb, not the JVM; e2e has no src/. Named rather than
      ;; counted, so a third exclusion has to be a decision.
      (is (= #{"tools" "e2e"} (set (remove in-ci (sut/libs))))))

    (testing "and tools, which cannot be a cell, is loaded in isolation anyway"
      ;; It is the one library with no matrix cell, so without this step its
      ;; isolation rests entirely on the static gate.
      (is (str/includes? yaml "bb --classpath src -e")
          "the check-isolation job must still load libs/tools against its own src"))))

(deftest ^:unit the-known-offender-stays-fixed
  ;; realtime is the case this gate was built for. BOU-305 removed the smuggled
  ;; adapter, so the assertion is inverted from what it was — deliberately, and
  ;; here rather than deleted, because the regression is easy to reintroduce:
  ;; anything that reaches for auth from realtime again shows up right here.
  ;; not-any? passes on an empty seq, so the guard needs a precondition or it
  ;; disarms itself the moment the scanner stops scanning. Guarded on realtime
  ;; having source at all rather than on findings existing — findings go to zero
  ;; when the burn-down finishes, and this assertion has to outlive that.
  (is (seq (sut/namespaces-of "realtime")) "realtime's source must be reachable")
  (is (not-any? #(and (= "realtime" (:lib* %))
                      (str/starts-with? (:namespace %) "wagoe.user"))
                (sut/all-findings))))
