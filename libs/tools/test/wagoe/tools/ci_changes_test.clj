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
   "reads libs/storage's READMEs, not the root one"})

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
