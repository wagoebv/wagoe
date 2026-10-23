(ns wagoe.tools.ci-changes-test
  "A docs-only pull request skips the code jobs, so `docs-only?` must never
   call a file docs-only that a code test reads (BOU-615)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.tools.ci-changes :as sut]))

(deftest ^:unit docs-only-is-narrow
  (testing "ADRs and the root docs are docs-only"
    (is (sut/docs-only? ["dev-docs/adr/ADR-039-database-adapter-contract.adoc" "CHANGELOG.md"]))
    (is (sut/docs-only? ["README.md" "UPGRADING.md"])))
  (testing "anything a code test reads, or any code, is not"
    (doseq [p ["AGENTS.md" "libs/workflow/README.md" "docs/modules/libraries/pages/workflow.adoc"
               "libs/core/src/wagoe/core/x.clj" ".github/workflows/ci.yml" "dev-docs/diagram.png"]]
      (is (not (sut/docs-only? ["CHANGELOG.md" p])) p)))
  (testing "an empty diff runs everything"
    (is (not (sut/docs-only? [])))))

(def ^:private mentions-read-elsewhere
  "Code tests naming a docs-only file without reading the root copy."
  {"libs/platform/test/wagoe/platform/shell/database/migrations_test.clj"
   "writes a scratch README.md into a temp migrations dir"
   "libs/workflow/test/wagoe/workflow/shell/service_test.clj"
   "reads libs/workflow/README.md, relative to the library"
   "libs/wagoe-cli/test/wagoe/cli/new_test.clj"
   "checks CLAUDE.md in a generated project"
   "libs/storage/test/wagoe/storage/schema_test.clj"
   "reads libs/storage's READMEs, not the root one"
   "libs/storage/test/wagoe/storage/readme_test.clj"
   "checks the calls in libs/storage's READMEs resolve"})

(def ^:private docs-only-names
  #"dev-docs|(CHANGELOG|README|UPGRADING|CONTRIBUTING|BUILD|CLAUDE)\.md")

(deftest ^:unit no-code-test-reads-a-docs-only-file
  ;; Tools tests run on every PR, so only the others count. A new mention
  ;; fails here until someone decides whether the file stays docs-only.
  (let [root  (if (fs/exists? "libs/tools") "." "../..")
        files (for [dir  ["test" "e2e"]
                    :let [d (fs/path root dir)] :when (fs/exists? d)
                    f    (fs/glob d "**.clj")]
                f)
        libs  (for [lib  (fs/list-dir (fs/path root "libs"))
                    :when (not= "tools" (str (fs/file-name lib)))
                    sub  ["test" "test-pg"]
                    :let [d (fs/path lib sub)] :when (fs/exists? d)
                    f    (fs/glob d "**.clj")]
                f)
        found (for [f (concat files libs)
                    :let [rel (str/replace (str (fs/relativize root f)) #"^\./" "")]
                    :when (and (re-find docs-only-names (slurp (fs/file f)))
                               (not (contains? mentions-read-elsewhere rel)))]
                rel)]
    (is (seq libs) "found no library tests; the check below would pass vacuously")
    (is (empty? found) (str "code tests name a docs-only file: " (vec found)))))

(def ^:private graph
  {"core"   {:src #{}       :test #{}}
   "user"   {:src #{"core"} :test #{}}
   "admin"  {:src #{"user"} :test #{"geo"}}
   "geo"    {:src #{"core"} :test #{}}
   "search" {:src #{}       :test #{"admin"}}})

(deftest ^:unit libs-to-test-follows-what-a-test-run-loads
  (testing "a change runs the libraries whose tests load it, through :src"
    (is (= #{"core" "user" "admin" "geo" "search"} (sut/libs-to-test graph ["libs/core/src/x.clj"])))
    (is (= #{"user" "admin" "search"} (sut/libs-to-test graph ["libs/user/src/x.clj"]))))
  (testing "a test-only use is not followed further"
    (is (= #{"geo" "admin"} (sut/libs-to-test graph ["libs/geo/src/x.clj"]))))
  (testing "docs-only files do not widen the set"
    (is (= #{"geo" "admin"} (sut/libs-to-test graph ["libs/geo/src/x.clj" "CHANGELOG.md"]))))
  (testing "anything outside libs/, or an empty diff, runs every library"
    (doseq [paths [["libs/geo/src/x.clj" "deps.edn"] [".github/workflows/ci.yml"] []]]
      (is (= (set (keys graph)) (sut/libs-to-test graph paths)) (pr-str paths)))))

(deftest ^:unit gated-jobs-name-a-library
  (is (= {"test-geo" "geo"}
         (sut/gated-jobs "jobs:\n  test-geo:\n    if: contains(format(' {0} ', needs.changes.outputs.libs), ' geo ')\n  lint:\n    if: always()\n"
                         #{"geo"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/gated-jobs "jobs:\n  test-goe:\n    if: contains(format(' {0} ', needs.changes.outputs.libs), ' goe ')\n"
                               #{"geo"}))))

(def ^:private root (if (fs/exists? "libs/tools") "." "../.."))

(deftest ^:unit every-library-test-job-is-gated-on-its-own-library
  (let [graph (sut/dependency-graph root)
        gated (sut/gated-jobs (slurp (str root "/.github/workflows/ci.yml")) (set (keys graph)))]
    (is (<= 30 (count gated)) "the per-library jobs lost their gate")
    (doseq [[job lib] gated]
      (is (#{(str "test-" lib) ({"test" "test-test-kit" "wagoe-mcp" "test-mcp"} lib)} job)
          (str job " is gated on " lib)))))

(deftest ^:unit platform-reaches-devtools-in-one-place
  ;; The ["platform" "devtools"] false edge rests on this: request capture,
  ;; dev-profile only, and skipped when devtools fails to load. A second use
  ;; makes the cut wrong; decide again whether it holds.
  (let [uses (for [f (fs/glob (fs/path root "libs/platform/src") "**.clj")
                   m (re-seq #"wagoe\.devtools\." (slurp (fs/file f)))]
               m)]
    (is (= 1 (count uses)))))

(deftest ^:unit first-run-smoke-runs-for-what-only-it-tests
  (testing "the installer, CLI, tools and quickstart page run it"
    (doseq [p ["scripts/install.sh" "libs/wagoe-cli/src/wagoe/cli/new.clj" "libs/tools/bb.edn"
               "docs/modules/getting-started/pages/quickstart.adoc" ".github/workflows/ci.yml"]]
      (is (sut/first-run? ["libs/geo/src/x.clj" p]) p)))
  (testing "a library change does not"
    (is (not (sut/first-run? ["libs/admin/src/x.clj" "libs/geo/test/x_test.clj"]))))
  (testing "an empty diff does"
    (is (sut/first-run? []))))

(deftest ^:unit first-run-paths-exist
  (doseq [p ["scripts/install.sh" "scripts/first-run-smoke.sh" "libs/wagoe-cli" "libs/tools"
             "libs/ai" "libs/scaffolder"
             "docs/modules/getting-started/pages/quickstart.adoc" ".github/workflows/ci.yml"]]
    (is (fs/exists? (fs/path root p)) (str p " moved; update first-run-paths"))))
