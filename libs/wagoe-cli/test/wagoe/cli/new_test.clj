(ns wagoe.cli.new-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wagoe.cli.new :as new]))

(deftest ^:unit validate-name-test
  (testing "valid kebab-case names are accepted"
    (is (nil? (new/validate-name "my-app")))
    (is (nil? (new/validate-name "myapp")))
    (is (nil? (new/validate-name "my-app-2"))))

  (testing "invalid names return an error string"
    (is (string? (new/validate-name "My-App")))    ; uppercase
    (is (string? (new/validate-name "123app")))    ; starts with digit
    (is (string? (new/validate-name "my.app")))    ; dot
    (is (string? (new/validate-name "")))           ; empty
    (is (string? (new/validate-name "my_app")))    ; underscore not allowed in project name
    (is (string? (new/validate-name "my-")))       ; trailing hyphen
    (is (string? (new/validate-name "my--app")))))  ; double hyphen

(deftest ^:unit name->ns-test
  (testing "converts hyphens to underscores"
    (is (= "my_app" (new/name->ns "my-app")))
    (is (= "myapp" (new/name->ns "myapp")))
    (is (= "my_long_name" (new/name->ns "my-long-name")))))

(deftest ^:integration generate-project-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-test-" (System/currentTimeMillis))]
    (try
      (testing "creates project directory"
        (new/generate! tmp "test-proj" {})
        (is (.exists (io/file tmp))))

      (testing "generates required files"
        (doseq [f ["deps.edn" "bb.edn" ".gitignore" ".env" ".env.example" "tests.edn"
                   "CLAUDE.md" "AGENTS.md"
                   ".claude/skills/boundary/SKILL.md"
                   "resources/conf/dev/config.edn"
                   "resources/conf/test/config.edn"
                   "src/boundary/config.clj"
                   "dev/user.clj"
                   "src/test_proj/system.clj"
                   ".mcp.json"
                   ".vscode/extensions.json"
                   ".githooks/pre-commit"]]
          (is (.exists (io/file tmp f)) (str "Missing: " f))))

      (testing "every generated .clj source parses (balanced delimiters, no template tokens)"
        ;; new-test previously only asserted file EXISTENCE — a template edit
        ;; with unbalanced parens or a stray {{token}} would ship a project
        ;; that fails on first load. Read-loop every rendered .clj to guard it.
        (doseq [f (->> (file-seq (io/file tmp))
                       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj"))))]
          (let [content (slurp f)]
            (is (not (str/includes? content "{{"))
                (str "Unrendered template token in " f))
            (is (try
                  (with-open [rdr (java.io.PushbackReader. (io/reader f))]
                    (loop [n 0]
                      (let [form (read {:read-cond :allow :eof ::eof} rdr)]
                        (if (= ::eof form) (pos? n) (recur (inc n))))))
                  (catch Exception e
                    (println "PARSE ERROR in" (str f) "—" (ex-message e))
                    false))
                (str f " must parse cleanly and contain at least one form")))))

      (testing ".env has a generated JWT_SECRET (no unreplaced placeholder)"
        (let [content (slurp (io/file tmp ".env"))]
          (is (str/includes? content "JWT_SECRET="))
          (is (not (str/includes? content "{{jwt-secret}}")))))

      (testing "substitutes project name in CLAUDE.md"
        (let [content (slurp (io/file tmp "CLAUDE.md"))]
          (is (str/includes? content "test-proj"))
          (is (not (str/includes? content "{{project-name}}")))))

      (testing "Claude Code skill points the agent at the scaffolder"
        (let [content (slurp (io/file tmp ".claude/skills/boundary/SKILL.md"))]
          (is (str/includes? content "bb scaffold"))
          (is (str/includes? content "name: boundary"))
          (is (not (str/includes? content "{{")))))

      (testing "sentinel comments are present in AGENTS.md"
        (let [content (slurp (io/file tmp "AGENTS.md"))]
          (is (str/includes? content "<!-- boundary:available-modules -->"))
          (is (str/includes? content "<!-- /boundary:available-modules -->"))
          (is (str/includes? content "<!-- boundary:installed-modules -->"))
          (is (str/includes? content "<!-- /boundary:installed-modules -->"))))

      (testing ".mcp.json wires the boundary MCP server via clojure -M:mcp"
        (let [content (slurp (io/file tmp ".mcp.json"))]
          (is (str/includes? content "\"-M:mcp\""))
          (is (str/includes? content "boundary"))
          (is (not (str/includes? content "{{")))))

      (testing "deps.edn has an :mcp alias with a resolved version"
        (let [content (slurp (io/file tmp "deps.edn"))]
          (is (str/includes? content ":mcp"))
          (is (str/includes? content "org.boundary-app/wagoe-mcp"))
          (is (not (str/includes? content "{{wagoe-mcp-version}}")))))

      (testing ":mcp alias lists mcp's full boundary closure"
        ;; wagoe-mcp's pom now declares its full boundary closure, but the
        ;; alias still enumerates it so -M:mcp also resolves against currently
        ;; published poms (pre-alpha-43). If a closure lib silently disappears
        ;; from the template, -M:mcp would fail to resolve at runtime — guard it.
        (let [content (slurp (io/file tmp "deps.edn"))]
          (doseq [lib ["boundary-ai" "boundary-devtools" "boundary-scaffolder"
                       "boundary-tools" "boundary-jobs"]]
            (is (str/includes? content (str "org.boundary-app/" lib))
                (str "Missing from :mcp closure: " lib)))))

      (testing "pre-commit hook is executable"
        (is (.canExecute (io/file tmp ".githooks/pre-commit"))))
      (finally
        ;; cleanup
        (doseq [f (reverse (file-seq (io/file tmp)))]
          (.delete f))))))

(defn- find-repo-root
  "Walk up from the working directory looking for .claude-plugin/marketplace.json.
   Returns the root as a File, or nil when running outside the monorepo."
  []
  (loop [dir (io/file (System/getProperty "user.dir"))]
    (when dir
      (if (.exists (io/file dir ".claude-plugin/marketplace.json"))
        dir
        (recur (.getParentFile dir))))))

(deftest ^:integration plugin-skill-in-sync-test
  (testing "claude-plugin SKILL.md is byte-identical to the project template"
    (if-let [root (find-repo-root)]
      (let [plugin-skill (io/file root "claude-plugin/skills/wagoe/SKILL.md")
            template     (io/resource "wagoe/cli/templates/claude-skill.md.tmpl")]
        (is (.exists plugin-skill) "Missing claude-plugin/skills/wagoe/SKILL.md")
        (is (some? template) "Missing claude-skill.md.tmpl resource")
        (when (and (.exists plugin-skill) template)
          (is (= (slurp template) (slurp plugin-skill))
              "claude-plugin/skills/wagoe/SKILL.md and libs/wagoe-cli/resources/boundary/cli/templates/claude-skill.md.tmpl must stay byte-identical — copy the template over the plugin file")))
      ;; Outside the monorepo (e.g. testing the published library) there is no
      ;; plugin copy to compare against — record the skip as a passing assertion.
      (is (nil? (find-repo-root))
          "Sync check skipped: monorepo root (.claude-plugin/marketplace.json) not found"))))

(deftest ^:integration directory-exists-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-exists-test")]
    (io/make-parents (io/file tmp "dummy.txt"))
    (spit (io/file tmp "dummy.txt") "x")
    (try
      (testing "non-empty directory without --force exits with error"
        (let [result (new/check-directory tmp false)]
          (is (= :non-empty result))))

      (testing "--force allows non-empty directory"
        (let [result (new/check-directory tmp true)]
          (is (= :ok result))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))]
          (.delete f))))))

(deftest ^:integration not-a-directory-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-file-test-" (System/currentTimeMillis))]
    (spit (io/file tmp) "x")
    (try
      (testing "existing regular file returns :not-a-dir"
        (is (= :not-a-dir (new/check-directory tmp false))))
      (finally
        (.delete (io/file tmp))))))

(deftest ^:unit git-bootstrap-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-git-test-" (System/currentTimeMillis))]
    (io/make-parents (io/file tmp "x"))
    (try
      (testing "a failing git runner is non-fatal and returns a warning"
        (let [boom (fn [& _] (throw (RuntimeException. "git missing")))
              result (new/git-bootstrap! tmp boom)]
          (is (false? (:ok? result)))
          (is (seq (:warnings result)))))

      (testing "a successful runner reports ok"
        (let [calls (atom [])
              ok    (fn [& args] (swap! calls conj (vec args)) {:exit 0 :out "" :err ""})
              result (new/git-bootstrap! tmp ok)]
          (is (true? (:ok? result)))
          ;; init, config hooksPath, add, commit  → 4 invocations
          (is (= 4 (count @calls)))
          (is (= "init" (second (first @calls))))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest ^:integration skip-git-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-skipgit-" (System/currentTimeMillis))]
    (try
      (testing "real bootstrap creates a .git directory"
        (new/generate! tmp "gitproj" {})
        (let [{:keys [ok? warnings]} (new/git-bootstrap! tmp)]
          ;; git (or its config, e.g. user.email) may be absent in CI images —
          ;; then the contract is a non-ok result carrying warnings, never a
          ;; throw. Assert whichever branch this environment lands in, so the
          ;; test can't pass with zero assertions.
          (if ok?
            (is (.exists (io/file tmp ".git")))
            (is (seq warnings) "failed bootstrap must report warnings"))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))
