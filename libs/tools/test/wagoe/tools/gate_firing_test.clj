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
            [clj-yaml.core :as yaml]
            [wagoe.tools.check-branch-protection :as check-bp]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :as process]
            [check-no-boundary]
            [wagoe.tools.check :as check]
            [wagoe.tools.check-deps :as check-deps]
            [wagoe.tools.check-fcis :as check-fcis]
            [wagoe.tools.check-hygiene :as check-hygiene]
            [wagoe.tools.check-changelog :as check-changelog]
            [wagoe.tools.check-doc-counts :as check-doc-counts]
            [wagoe.tools.check-versions :as check-versions]
            [wagoe.tools.check-poms :as check-poms]
            [wagoe.tools.check-ports :as check-ports]
            [wagoe.tools.check-tests :as check-tests]
            [wagoe.tools.docs-lint :as docs-lint]
            [wagoe.tools.doctor :as doctor]
            [agents-gen :as agents-gen]))

;; =============================================================================
;; Fixture helpers
;; =============================================================================

(declare delete-tree!)

(defn- temp-dir
  "A guaranteed-empty directory under the system temp dir.

   Named from `label` and `n` rather than randomly, so a leftover directory can
   be traced back to the test that made it — but the name is only useful if it
   cannot also carry state between runs. A run interrupted before its `finally`
   leaves the tree behind, and the next run would then start against those
   files: a stale ports.clj, for instance, means the ports test no longer
   exercises the missing-ports case at all.

   So any existing directory is removed first, and both the removal and the
   creation are checked. A fixture helper that silently proceeds on a
   half-deleted tree is the same failure this namespace exists to catch.

   `throw` rather than `assert`: assertions compile away when *assert* is
   false, and a precondition that can be switched off is not a precondition —
   which is the whole subject of this namespace."
  [label n]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "wagoe-gate-" label "-" n))]
    (when (.exists d)
      (delete-tree! d))
    (when (.exists d)
      (throw (ex-info "could not clear stale fixture dir; the test would run against leftovers"
                      {:dir (str d)})))
    (when-not (.mkdirs d)
      (throw (ex-info "could not create fixture dir" {:dir (str d)})))
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
;; check:doc-counts
;; =============================================================================

(deftest ^:unit doc-counts-gate-fires-test
  (testing "a stale count is detected"
    (is (seq (check-doc-counts/count-findings
              "README.md" "Wagoe is a monorepo of 23 independently publishable libraries" 29))))

  (testing "a stale publishing claim is detected"
    (is (seq (check-doc-counts/publishing-findings
              "libs/tools/AGENTS.md"
              "`wagoe-tools` itself is not published — it is a monorepo-internal tool."
              #{"tools" "wagoe-tools"}))))

  (testing "the gate discovers real documentation, not an empty file list"
    ;; The failure this guards against is the gate scanning nothing and
    ;; reporting clean — docs-lint's alias check was live for months while its
    ;; file list omitted most of the repo (BOU-250).
    (let [docs (check-doc-counts/tracked-docs)]
      (is (< 20 (count docs))
          "expected the repo's documentation, so a passing run means something")
      (is (some #(= "README.md" %) docs))
      (is (not-any? #(= "CHANGELOG.md" %) docs)
          "historical counts must stay out of scope")))

  (testing "the repository currently satisfies the gate"
    ;; Belt and braces: the assertions above prove detection on synthetic input.
    ;; This proves the real tree is clean, so a regression shows up here too.
    ;; scan-opts, not a hand-built copy of it — a test that assembles its own
    ;; options can pass while the real run fails on one it forgot to set, which
    ;; is how this assertion first went green against a failing gate.
    (is (empty? (check-doc-counts/scan (check-doc-counts/tracked-docs)
                                       (check-doc-counts/scan-opts))))))

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
;; check:test-meta
;; =============================================================================

(deftest ^:unit test-meta-gate-fires-test
  (testing "metadata after the deftest name is detected"
    ;; The reader attaches it to the body form, not the var, so --focus-meta
    ;; silently skips the test — green, and never run. BOU-184.
    (is (seq (check-tests/scan-content-meta
              "t.clj" "(deftest thing-test\n  ^:unit\n  (is (= 1 1)))\n"))))

  (testing "metadata before the name is correct and not flagged"
    (is (empty? (check-tests/scan-content-meta
                 "t.clj" "(deftest ^:unit thing-test\n  (is (= 1 1)))\n")))))

;; =============================================================================
;; check:test-tags
;; =============================================================================

(deftest ^:unit test-tags-gate-fires-test
  (testing "a deftest with no pyramid tag is reported"
    (is (seq (check-tests/scan-content-tags
              "t.clj" "(deftest ^:slow thing-test\n  (is (= 1 1)))\n"))))

  (testing "a deftest with two pyramid tags is reported"
    (is (seq (check-tests/scan-content-tags
              "t.clj" "(deftest ^:unit ^:integration thing-test\n  (is (= 1 1)))\n"))))

  (testing "exactly one pyramid tag passes"
    (is (empty? (check-tests/scan-content-tags
                 "t.clj" "(deftest ^:unit thing-test\n  (is (= 1 1)))\n")))))

;; =============================================================================
;; check:ports — discovery AND detection, against a real tree
;; =============================================================================

(deftest ^:unit ports-gate-fires-test
  (let [root   (temp-dir "ports" 1)
        config {:allow-missing-ports #{} :allow-direct #{}}]
    (try
      ;; A module is core/ + shell/. This one has no ports.clj.
      (spit-file! root "libs/demo/src/wagoe/demo/core/logic.clj"
                  "(ns wagoe.demo.core.logic)\n")
      (spit-file! root "libs/demo/src/wagoe/demo/shell/service.clj"
                  "(ns wagoe.demo.shell.service)\n")

      (let [roots [(io/file root "libs/demo/src")]]
        (testing "the fixture module is discovered"
          ;; collect-violations returns {:modules n :violations [...]}, not a
          ;; seq. Asserting on the map itself is always truthy — the first
          ;; version of this test did exactly that and would have passed with
          ;; zero violations.
          (is (= 1 (:modules (check-ports/collect-violations config roots)))))

        (testing "a module without ports.clj is reported"
          (is (seq (:violations (check-ports/collect-violations config roots)))
              "missing ports.clj is the gate's core rule"))

        (testing "adding ports.clj clears it"
          (spit-file! root "libs/demo/src/wagoe/demo/ports.clj"
                      "(ns wagoe.demo.ports)\n(defprotocol Demo (do-it [this]))\n")
          (is (empty? (:violations (check-ports/collect-violations config roots))))))
      (finally (delete-tree! root)))))

;; =============================================================================
;; check:poms
;; =============================================================================

(deftest ^:unit poms-gate-fires-test
  (let [root (temp-dir "poms" 1)]
    (try
      (testing "a publishable build.clj that skips pom-basis is reported"
        (let [dir (io/file root "bad")]
          (spit-file! root "bad/build.clj"
                      (str "(ns build)\n"
                           "(def lib 'com.wagoe/wagoe-bad)\n"
                           "(defn jar [_]\n"
                           "  (b/write-pom {:basis basis :lib lib}))\n"))
          (spit-file! root "bad/deps.edn"
                      "{:deps {wagoe/core {:local/root \"../core\"}}}\n")
          (is (:violation? (check-poms/check-lib ["bad" dir]))
              "write-pom without build-shared/pom-basis drops inter-Wagoe deps")))

      (testing "a build.clj using pom-basis is not reported"
        (let [dir (io/file root "good")]
          (spit-file! root "good/build.clj"
                      (str "(ns build)\n"
                           "(def lib 'com.wagoe/wagoe-good)\n"
                           "(load-file \"../build_shared.clj\")\n"
                           "(def basis (build-shared/pom-basis version))\n"
                           "(defn jar [_]\n"
                           "  (b/write-pom {:basis basis :lib lib}))\n"))
          (spit-file! root "good/deps.edn"
                      "{:deps {wagoe/core {:local/root \"../core\"}}}\n")
          (is (not (:violation? (check-poms/check-lib ["good" dir]))))))
      (finally (delete-tree! root)))))

;; =============================================================================
;; check:agents
;; =============================================================================

(deftest ^:unit agents-gate-fires-test
  (testing "a target whose content differs from the render is reported as drifted"
    (is (= ["AGENTS.md"]
           (agents-gen/drifted-files
            [{:file "AGENTS.md" :current "stale" :rendered "fresh"}
             {:file "libs/core/AGENTS.md" :current "same" :rendered "same"}]))))

  (testing "identical content is not drift"
    (is (empty? (agents-gen/drifted-files
                 [{:file "AGENTS.md" :current "x" :rendered "x"}])))))

;; =============================================================================
;; check:doctor
;; =============================================================================

(defn- levels
  "The :level values of a doctor check's results.

   doctor returns a result per check including passes, so `(seq results)` is
   always truthy — the first version of this test asserted exactly that and
   would have passed no matter what the check decided."
  [results]
  (set (map :level results)))

(deftest ^:unit doctor-gate-fires-test
  (let [user-active {:wagoe/user-service {}}]
    (testing "a JWT_SECRET below the 32-character minimum is an error"
      (is (contains? (levels (doctor/check-jwt-secret user-active {"JWT_SECRET" "too-short"}))
                     :error)
          "a 9-character secret must not pass"))

    (testing "a missing JWT_SECRET is an error"
      (is (contains? (levels (doctor/check-jwt-secret user-active {})) :error)))

    (testing "a long enough secret passes"
      (is (= #{:pass}
             (levels (doctor/check-jwt-secret
                      user-active
                      {"JWT_SECRET" "ci-test-secret-minimum-32-characters"})))))))

;; =============================================================================
;; Linting — the invocation, not clj-kondo itself
;; =============================================================================

(deftest ^:unit linting-gate-lints-the-libs-test
  ;; clj-kondo's own detection is not ours to test. What can rot is the path
  ;; list: if lib discovery returns nothing, the gate still runs and still
  ;; passes, having linted only src and test. That is this gate's silent-pass
  ;; mode, so it is what the test pins.
  (let [cmd (check/linting-cmd)]
    (testing "the command lints the monorepo roots"
      (is (some #{"src"} cmd))
      (is (some #{"test"} cmd)))

    (testing "library source paths are included"
      (let [lib-paths (filter #(str/starts-with? % "libs/") cmd)]
        (is (seq lib-paths) "no libs/ paths means the gate lints almost nothing")
        (is (some #(str/ends-with? % "/src") lib-paths))
        (is (some #(str/ends-with? % "/test") lib-paths))))

    (testing "every path handed to clj-kondo exists"
      ;; A non-existent path makes clj-kondo error rather than lint, which
      ;; reads as a gate failure for the wrong reason.
      (doseq [p (filter #(str/starts-with? % "libs/") cmd)]
        (is (.exists (io/file p)) (str p " does not exist"))))))

;; =============================================================================
;; check:no-boundary — against a throwaway repo
;; =============================================================================

(defn- git! [dir & args]
  (apply process/shell {:dir (str dir) :out :string :err :string} "git" args))

;; Split so this source file contains no literal the rename gate would flag.
;; See the probe map below for why that matters.
(def ^:private brand (str "bound" "ary"))
(def ^:private new-brand (str "wag" "oe"))

(deftest ^:unit no-boundary-gate-fires-test
  ;; The only gate that needs a real git repo: it works by `git grep` over
  ;; tracked files, so nothing can be planted for it without one. That was the
  ;; reason it was the last gate left unproven.
  (let [root (temp-dir "noboundary" 1)]
    (try
      (git! root "init" "-q")
      (git! root "config" "user.email" "t@example.com")
      (git! root "config" "user.name" "t")
      (spit-file! root "src/app/clean.clj" "(ns app.clean)\n(defn go [] :ok)\n")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "clean")

      (binding [check-no-boundary/*repo-dir* root]
        (testing "a clean tree yields no hits"
          (is (empty? (check-no-boundary/all-hit-paths))))

        (testing "each hard group detects its own token family"
          ;; One planted violation per group, so a group whose pattern rots
          ;; cannot hide behind another group's hit.
          ;;
          ;; The probes are ASSEMBLED rather than written as literals. Spelled
          ;; out, this file would carry one residual of every kind and the gate
          ;; would flag its own test fixtures — seven hits, in the namespace
          ;; that exists to prove the gate works. Allowlisting the file was the
          ;; obvious way out and the wrong one: it would switch the gate off
          ;; for everything else here too. What lands on disk is identical.
          (doseq [[group content] {:ns     (str "(ns app.x (:require [" brand ".core :as c]))")
                                   :keys   (str "{:" brand "/user-service {}}")
                                   :coords (str "{:deps {org." brand "-app/" brand "-core {}}}")
                                   :env    (str "(System/getenv \"" (str/upper-case brand) "_TENANT_ID\")")
                                   :group  (str "{:deps {org." new-brand "/" new-brand "-core {}}}")
                                   :dirs   (str ";; see libs/" brand "-cli/src")
                                   :urls   (str ";; https://" brand "-app.org/docs")}]
            (let [f (str "src/app/" (name group) "_probe.clj")]
              (spit-file! root f content)
              (git! root "add" "-A")
              (let [hits (check-no-boundary/grep-group
                          (check-no-boundary/token-defs group) #{})]
                (is (seq hits)
                    (str "group " group " no longer matches its own token"))))))

        (testing "the allowlist suppresses a planted hit"
          (let [before (count (check-no-boundary/all-hit-paths))]
            (is (pos? before))
            (is (empty? (check-no-boundary/grep-group
                         (check-no-boundary/token-defs :ns) #{"src/"}))
                "an allowlist prefix covering the file must silence it"))))
      (finally (delete-tree! root)))))

;; =============================================================================
;; Allowlists — every exemption must still be load-bearing
;; =============================================================================
;;
;; The rename gate's path allowlist audits itself on every run. The two
;; hardcoded singletons cannot: they live in source, so a stale one would just
;; sit there exempting nothing. These tests are their audit.
;;
;; The other three allowlists are empty by design and stay that way under the
;; check below, so emptiness cannot rot into "someone added an entry and moved
;; on": the fcis config (:allow-throw, :allow-mutable-state — real exceptions
;; live inline as ns metadata), the test-tags config (:allow-untagged, BOU-166
;; complete), and check-fcis's allowed-fq-violations.
;;
;; Those configs are named by path in the test bodies rather than spelled out
;; here, because the rename gate's namespace pattern — the old brand followed
;; by a dot and a letter — matches its own config FILENAME, which ends in
;; "-<brand>.edn" and so reads as a namespace reference. A limitation of
;; matching brand tokens by regex. Not worth an exemption: allowlisting this
;; file would switch the gate off for the whole test namespace.

(deftest ^:unit allowlist-audit-is-order-independent-test
  ;; Allowlist matching is a prefix test over the whole list, so "would removing
  ;; this entry expose a hit" cannot depend on where the entry sits. The first
  ;; version of the audit accumulated claims left-to-right and therefore called
  ;; a specific entry load-bearing whenever a BROADER one happened to come
  ;; after it — a redundant entry that would sit there forever.
  ;; Entries must exist on disk (the audit checks that first), so these are real
  ;; repo paths with the hit set mocked around them.
  (with-redefs [check-no-boundary/all-hit-paths
                (fn [] #{"libs/tools/deps.edn" "libs/tools/build.clj"})]
    (testing "a nested entry is redundant whether the broader one precedes or follows"
      (doseq [order [["libs/tools/deps.edn" "libs/"] ["libs/" "libs/tools/deps.edn"]]]
        (let [{:keys [load-bearing redundant]} (check-no-boundary/audit-allowlist order)]
          (is (= ["libs/"] load-bearing)
              (str "order " (pr-str order) ": only the broader entry earns its place"))
          (is (= ["libs/tools/deps.edn"] (mapv first redundant))
              (str "order " (pr-str order) ": the nested entry is redundant either way")))))

    (testing "an exact duplicate is reported once, not twice"
      ;; Under the order-independent test each copy is covered by the other, so
      ;; a naive implementation flags both and neither can be removed without
      ;; the verdict flipping. The first occurrence keeps its place.
      (let [{:keys [load-bearing redundant]}
            (check-no-boundary/audit-allowlist ["libs/" "libs/"])]
        (is (= ["libs/"] load-bearing))
        (is (= 1 (count redundant)))))

    (testing "entries covering disjoint hits are all load-bearing"
      (is (= ["libs/tools/deps.edn" "libs/tools/build.clj"]
             (:load-bearing (check-no-boundary/audit-allowlist
                             ["libs/tools/deps.edn" "libs/tools/build.clj"])))))))

(deftest ^:unit ports-allowlist-is-load-bearing-test
  (testing "wagoe.platform genuinely still has no ports.clj"
    ;; The builtin allowlist exempts it. If platform ever gains ports.clj the
    ;; entry becomes inert and should be deleted — but nothing would say so,
    ;; because an exemption for a module that no longer violates is silent.
    (is (not (.exists (io/file "libs/platform/src/wagoe/platform/ports.clj")))
        (str "libs/platform now has ports.clj — remove \"wagoe.platform\" from "
             "builtin-allow-missing-ports in check_ports.clj"))))

(deftest ^:unit deps-allowlist-is-load-bearing-test
  (testing "platform genuinely still does not declare external"
    ;; Same reasoning. The entry exists because declaring it would create a
    ;; circular :local/root that tools.deps rejects; once the external->platform
    ;; coupling is broken the exemption must go.
    (let [deps (slurp "libs/platform/deps.edn")]
      (is (not (str/includes? deps "wagoe/external"))
          (str "libs/platform/deps.edn now declares external — remove "
               "[\"platform\" \"external\"] from allowed-undeclared-deps")))))

(deftest ^:unit empty-allowlists-stay-empty-test
  (testing "the allowlists that are complete carry no entries"
    (doseq [[file ks] {".wagoe/check-fcis.edn"      [:allow-throw :allow-mutable-state]
                       ".wagoe/check-test-tags.edn" [:allow-untagged]}]
      (let [cfg (edn/read-string (slurp file))]
        (doseq [k ks]
          (is (empty? (get cfg k))
              (str file " " k " gained an entry. That may be correct — but it is "
                   "debt, so it needs a ticket and a removal plan, not just a line "
                   "in a file.")))))))

;; =============================================================================
;; The meta-gate: every gate must be represented above
;; =============================================================================

(deftest ^:unit branch-protection-gate-fires-test
  (testing "a job the summary does not reach is detected"
    ;; Branch protection requires one context, `All Tests Passed`. A job
    ;; outside its `needs:` can fail while that context goes green.
    (let [{:keys [missing]}
          (check-bp/summary-covers
           (yaml/parse-string
            (str "jobs:\n"
                 "  test-summary:\n    name: All Tests Passed\n    needs: [a]\n"
                 "  a:\n    name: A\n"
                 "  orphan:\n    name: Orphan\n")))]
      (is (= ["orphan"] missing))))

  (testing "a renamed summary job is detected"
    ;; Renaming it without changing protection recreates the original defect:
    ;; a required context nothing reports.
    (let [{:keys [summary-name]}
          (check-bp/summary-covers
           (yaml/parse-string
            "jobs:\n  test-summary:\n    name: Renamed\n    needs: [a]\n  a:\n    name: A\n"))]
      (is (not= check-bp/summary-job-name summary-name))))

  (testing "the gate reads the real workflow, not an empty job map"
    ;; The failure this guards against is the one BOU-250 found in docs-lint:
    ;; a live check scanning nothing and reporting clean.
    (let [wf (check-bp/ci-workflow)]
      (is (< 40 (count (:jobs wf)))
          "expected ci.yml's jobs, so a passing run means something")))

  (testing "the repository currently satisfies the gate"
    (let [{:keys [missing summary-name]} (check-bp/summary-covers (check-bp/ci-workflow))]
      (is (= check-bp/summary-job-name summary-name))
      (is (empty? missing)))))

(def gates-with-firing-tests
  "Gate ids from `check/all-checks` that this namespace proves can still fire.

   Adding a gate to `all-checks` without adding it here fails the test below.
   That is the point: a gate nobody can prove fires is indistinguishable from
   one that does not run."
  #{:hygiene :deps :fcis :placeholder-tests :docs-lint
    :test-meta :test-tags :ports :poms :agents :doctor :linting :no-boundary
    :doc-counts :branch-protection :versions :changelog})

;; =============================================================================
;; check:changelog
;; =============================================================================

(deftest ^:unit changelog-gate-fires-test
  ;; The shape it exists for: PR #395 changed the order jobs come off the queue
  ;; on two of three backends and touched no changelog. Thirty PRs in eleven
  ;; days looked like this.
  (testing "shipped source with no entry is reported"
    (is (= {:files ["libs/jobs/src/wagoe/jobs/shell/adapters/in_memory.clj"
                    "libs/jobs/src/wagoe/jobs/shell/adapters/redis.clj"]}
           (check-changelog/verdict
            ["libs/jobs/src/wagoe/jobs/shell/adapters/in_memory.clj"
             "libs/jobs/src/wagoe/jobs/shell/adapters/redis.clj"
             "libs/jobs/test/wagoe/jobs/adapter_surface_test.clj"
             ".github/workflows/ci.yml"]
            false))))

  (testing "an entry satisfies it"
    (is (nil? (check-changelog/verdict
               ["libs/jobs/src/wagoe/jobs/shell/adapters/redis.clj" "CHANGELOG.md"]
               false))))

  (testing "and a branch that ships nothing is not asked for one"
    ;; Docs, tests and CI are not shipped source. A gate that demanded an entry
    ;; for those would be worked around rather than obeyed.
    (is (nil? (check-changelog/verdict
               ["docs/modules/architecture/pages/scaling.adoc"
                "libs/cache/test/wagoe/cache/adapter_surface_test.clj"
                ".github/workflows/ci.yml"]
               false)))))

(deftest ^:unit versions-gate-fires-test
  ;; The shape the ticket names: a bump covers deps.edn and misses bb.edn, so
  ;; one location lags while the rest move on.
  (let [bumped (fn [v what file] {:file file :version v :what what})]

    (testing "a lagging pin is reported against the majority"
      (let [r (check-versions/disagreements
               [(bumped "1.0.0-beta-5" "build.clj" "libs/core/build.clj")
                (bumped "1.0.0-beta-5" "build.clj" "libs/user/build.clj")
                (bumped "1.0.1-alpha-32" "com.wagoe pin" "bb.edn")])]
        (is (= "1.0.0-beta-5" (:consensus r)))
        (is (= ["bb.edn"] (map :file (:offenders r))))))

    (testing "agreement is not a finding"
      (is (nil? (check-versions/disagreements
                 [(bumped "1.0.0-beta-5" "build.clj" "libs/core/build.clj")
                  (bumped "1.0.0-beta-5" "com.wagoe pin" "bb.edn")]))))

    (testing "more than one straggler is reported, sorted"
      (let [r (check-versions/disagreements
               [(bumped "2.0.0" "build.clj" "libs/a/build.clj")
                (bumped "2.0.0" "build.clj" "libs/b/build.clj")
                (bumped "2.0.0" "build.clj" "libs/c/build.clj")
                (bumped "1.9.0" "com.wagoe pin" "z-bb.edn")
                (bumped "1.9.0" "catalogue-version" "a-catalogue.edn")])]
        (is (= ["a-catalogue.edn" "z-bb.edn"] (map :file (:offenders r))))))

    (testing "the real repository is discovered, not just the fixture"
      ;; Without this the gate could pass by finding nothing at all.
      (is (<= 20 (count (check-versions/version-sources)))))

    (testing "every version-bearing kind is actually scanned"
      ;; The first version of this gate read one key from the module catalogue
      ;; and ignored the other 24 — :cli-version and a per-module :version for
      ;; each addable module, which is what `wagoe add` pins. It could pass
      ;; while the CLI emitted a stale dependency, which is the failure the
      ;; gate exists for. Asserting the kinds, not a total, so adding a module
      ;; does not need this number changed.
      (let [kinds (set (map :what (check-versions/version-sources)))]
        (doseq [k ["build.clj" "generated-project pin" "com.wagoe pin"
                   ":catalogue-version" ":cli-version" ":version"]]
          (is (contains? kinds k) (str k " is not scanned by version-sources")))))

    (testing "the catalogue's per-module versions are all covered, not just one"
      (let [n (count (filter #(= ":version" (:what %)) (check-versions/version-sources)))]
        (is (<= 20 n) (str "only " n " per-module :version entries scanned"))))))

(def gates-without-firing-tests
  "Gates that cannot yet be proven to fire, each with the reason.

   Empty as of BOU-250 — every gate in `all-checks` is now proven. Kept rather
   than deleted because it is the honest place for the next gate that arrives
   without a seam: the alternative is quietly leaving it out of both lists,
   which the test below forbids. An entry here is a to-do, not an exemption."
  {})

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
