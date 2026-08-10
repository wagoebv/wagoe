(ns wagoe.cli.new-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
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
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/wagoe-test-" (System/currentTimeMillis))]
    (try
      (testing "creates project directory"
        (new/generate! tmp "test-proj" {})
        (is (.exists (io/file tmp))))

      (testing "generates required files"
        (doseq [f ["deps.edn" "bb.edn" ".gitignore" ".env" ".env.example" "tests.edn"
                   "CLAUDE.md" "AGENTS.md"
                   ".claude/skills/wagoe/SKILL.md"
                   "resources/conf/dev/config.edn"
                   "resources/conf/test/config.edn"
                   "src/wagoe/config.clj"
                   "dev/user.clj"
                   "src/test_proj/system.clj"
                   ;; The non-REPL entry point and the build path that uses it
                   ;; (BOU-254). Generated but unasserted until BOU-259.
                   "src/test_proj/main.clj"
                   "build.clj"
                   "Dockerfile"
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
        (let [content (slurp (io/file tmp ".claude/skills/wagoe/SKILL.md"))]
          (is (str/includes? content "bb scaffold"))
          (is (str/includes? content "name: wagoe"))
          (is (not (str/includes? content "{{")))))

      ;; BOU-259: these assertions came from the scaffolder's duplicate project
      ;; generator, which is gone. The AGENTS.md architecture contract now has
      ;; exactly one source — AGENTS.md.tmpl — so it is asserted here.
      (testing "AGENTS.md documents the FC/IS + ports architecture (BOU-80)"
        (let [content (slurp (io/file tmp "AGENTS.md"))]
          (is (str/includes? content "Functional Core"))
          (is (str/includes? content "Imperative Shell"))
          (is (str/includes? content "ports.clj"))
          ;; ports.clj is mandatory, not optional (BOU-80). Asserted on the
          ;; literal sentence rather than a loose /required/ match, so softening
          ;; the rule to "should" fails here.
          (is (str/includes? content "Every module MUST define `ports.clj`.")
              "AGENTS.md must state that ports.clj is mandatory")
          ;; web/HTTP layers must never require shell.persistence directly
          (is (str/includes? content "shell.persistence"))
          (is (str/includes? content "bb scaffold")
              "AGENTS.md must name bb scaffold as the module generator")))

      (testing "CLAUDE.md delegates the architecture rules to AGENTS.md"
        ;; Unlike the removed scaffolder copy, this template does not repeat the
        ;; rules — it imports them. The import is what must not be lost.
        (let [content (slurp (io/file tmp "CLAUDE.md"))]
          (is (str/includes? content "@AGENTS.md")
              "CLAUDE.md must import AGENTS.md, else Claude Code loses the FC/IS rules")))

      (testing "bb.edn wires the quality gates AGENTS.md tells developers to run"
        (let [content (slurp (io/file tmp "bb.edn"))]
          (is (str/includes? content "com.wagoe/wagoe-tools"))
          (is (str/includes? content "check:ports"))
          (is (str/includes? content "check:fcis"))))

      (testing "sentinel comments are present in AGENTS.md"
        (let [content (slurp (io/file tmp "AGENTS.md"))]
          (is (str/includes? content "<!-- wagoe:available-modules -->"))
          (is (str/includes? content "<!-- /wagoe:available-modules -->"))
          (is (str/includes? content "<!-- wagoe:installed-modules -->"))
          (is (str/includes? content "<!-- /wagoe:installed-modules -->"))))

      (testing ".mcp.json wires the wagoe MCP server via clojure -M:mcp"
        (let [content (slurp (io/file tmp ".mcp.json"))]
          (is (str/includes? content "\"-M:mcp\""))
          (is (str/includes? content "\"wagoe\""))
          (is (not (str/includes? content "{{")))))

      ;; BOU-266: the :user-cli alias used
      ;;   ["-e" "(require ...) (System/exit (e/run-cli! (vec *command-line-args*)))"]
      ;; so `clojure -M:user-cli create --email ...` became
      ;;   clojure.main -e "<expr>" create --email ...
      ;; clojure.main treats the first non-option argument as a script path and
      ;; binds *command-line-args* to what follows, so `create` was swallowed and
      ;; run-cli! got a vector starting with --email, which it rejected as an
      ;; unknown global option. `bb create-admin` could not create a user at all.
      ;; -m passes arguments to -main intact.
      (testing "the :user-cli alias uses -m, so the verb reaches the CLI"
        ;; Read the EDN rather than grep the text: the first version of this
        ;; assertion searched the alias slice for "*command-line-args*" and
        ;; tripped over the explanatory comment sitting above the alias.
        (let [deps      (edn/read-string (slurp (io/file tmp "deps.edn")))
              main-opts (get-in deps [:aliases :user-cli :main-opts])]
          (is (= ["-m" "wagoe.user.shell.cli-entry"] main-opts)
              ":user-cli must invoke -main via -m; with -e, clojure.main takes the
               first non-option argument as a script path and the verb is lost")))

      (testing "deps.edn has an :mcp alias with a resolved version"
        (let [content (slurp (io/file tmp "deps.edn"))]
          (is (str/includes? content ":mcp"))
          (is (str/includes? content "com.wagoe/wagoe-mcp"))
          (is (not (str/includes? content "{{wagoe-mcp-version}}")))))

      (testing ":mcp alias lists mcp's full wagoe closure"
        ;; wagoe-mcp's pom now declares its full wagoe closure, but the
        ;; alias still enumerates it so -M:mcp also resolves against currently
        ;; published poms (pre-alpha-43). If a closure lib silently disappears
        ;; from the template, -M:mcp would fail to resolve at runtime — guard it.
        (let [content (slurp (io/file tmp "deps.edn"))]
          (doseq [lib ["wagoe-ai" "wagoe-devtools" "wagoe-scaffolder"
                       "wagoe-tools" "wagoe-jobs"]]
            (is (str/includes? content (str "com.wagoe/" lib))
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
              "claude-plugin/skills/wagoe/SKILL.md and libs/wagoe-cli/resources/wagoe/cli/templates/claude-skill.md.tmpl must stay byte-identical — copy the template over the plugin file")))
      ;; Outside the monorepo (e.g. testing the published library) there is no
      ;; plugin copy to compare against — record the skip as a passing assertion.
      (is (nil? (find-repo-root))
          "Sync check skipped: monorepo root (.claude-plugin/marketplace.json) not found"))))

(deftest ^:integration directory-exists-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/wagoe-exists-test")]
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
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/wagoe-file-test-" (System/currentTimeMillis))]
    (spit (io/file tmp) "x")
    (try
      (testing "existing regular file returns :not-a-dir"
        (is (= :not-a-dir (new/check-directory tmp false))))
      (finally
        (.delete (io/file tmp))))))

(deftest ^:unit git-bootstrap-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/wagoe-git-test-" (System/currentTimeMillis))]
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
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/wagoe-skipgit-" (System/currentTimeMillis))]
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

;; =============================================================================
;; Integrant keys the generated config can add must be resolvable
;; =============================================================================

(deftest ^:unit every-conditional-integrant-key-can-be-built
  ;; config.clj.tmpl assembles the Integrant config by conditionally assoc'ing
  ;; keys. Each one needs an `ig/init-key` method at runtime, which comes from
  ;; one of two places: a `defmethod` in system.clj.tmpl, or a module-wiring
  ;; namespace the template requires.
  ;;
  ;; `:wagoe.external/smtp` had neither. It worked only because platform
  ;; required wagoe.external.shell.module-wiring on every app's behalf; when
  ;; that moved to the entry point, a generated project with email enabled
  ;; would have died at startup with "No method in multimethod 'init-key' for
  ;; dispatch value: :wagoe.external/smtp".
  (let [config (slurp (io/resource "wagoe/cli/templates/config.clj.tmpl"))
        system (slurp (io/resource "wagoe/cli/templates/system.clj.tmpl"))
        ;; Keys the config assembles conditionally: (assoc :some/key ...)
        assoc'd  (set (map second (re-seq #"\(assoc\s+(:[a-z][a-z0-9.-]*/[a-z][a-z0-9-]*)" config)))
        ;; Keys the generated project defines itself.
        defined  (set (map second (re-seq #"defmethod ig/init-key\s+(:[a-z][a-z0-9.-]*/[a-z][a-z0-9-]*)" system)))
        ;; Namespaces the config requires, statically or conditionally.
        required (set (map second (re-seq #"'?\[?(wagoe\.[a-z0-9.-]+\.shell\.module-wiring)\]?" config)))
        ;; A key like :wagoe.external/smtp is served by wagoe.external.*;
        ;; :wagoe/user-service by any wagoe.<lib>.* wiring, so the namespace
        ;; prefix is only decisive for dotted keys.
        covered? (fn [k]
                   (let [ns' (namespace (read-string k))]
                     (or (contains? defined k)
                         (if (str/includes? ns' ".")
                           (some #(str/starts-with? % (str ns' ".")) required)
                           (seq required)))))]

    (testing "the template parsed — otherwise this passes vacuously"
      (is (<= 8 (count assoc'd)) (str "only found " (pr-str assoc'd)))
      (is (seq defined))
      (is (seq required)))

    (testing "every conditionally added key is defined locally or required"
      (doseq [k (sort assoc'd)]
        (is (covered? k)
            (str k " is assoc'd into the Integrant config but nothing provides "
                 "an init-key: add a defmethod to system.clj.tmpl or require "
                 "its module-wiring in config.clj.tmpl"))))))

;; =============================================================================
;; --no-user
;; =============================================================================

(deftest ^:unit user-module-is-opt-out
  ;; The scaffold wired the user chain unconditionally, so every generated app
  ;; carried auth, sessions, MFA, an audit trail and four tables whether or not
  ;; it had accounts (BOU-234). Booting both variants: default serves
  ;; /web/login and creates auth_users/users/user_sessions/user_audit_log;
  ;; --no-user serves /health, 404s /web/login, creates no tables, and needs no
  ;; JWT_SECRET.
  (let [render (fn [opts]
                 (let [dir (str (System/getProperty "java.io.tmpdir")
                                "/wagoe-new-test-" (System/nanoTime))]
                   (try
                     (new/generate! dir "sample" opts)
                     (slurp (io/file dir "src/wagoe/config.clj"))
                     (finally
                       (doseq [f (reverse (file-seq (io/file dir)))] (.delete f))))))
        with    (render {})
        without (render {:with-user? false})]

    (testing "the flag reaches the generated config as a literal"
      (is (str/includes? with    "user?     true"))
      (is (str/includes? without "user?     false")))

    (testing "both variants still contain the chain — it is guarded, not deleted"
      ;; Guarding rather than templating the block out keeps the generated file
      ;; the same shape either way, so turning user back on is editing one word.
      (doseq [src [with without]]
        (is (str/includes? src ":wagoe/user-service"))
        (is (str/includes? src ":wagoe/user-db-schema"))))

    (testing "the user chain is behind the flag, not in the base map"
      ;; If it were unconditional again, `user?` would be unused and the
      ;; components would be built regardless of the literal.
      (is (str/includes? without "      user?\n      (assoc")))

    (testing "the HTTP handler only references user components when enabled"
      (is (str/includes? with "user?     (assoc :user-routes")))

    (testing "default is user-on"
      (is (= with (render {:with-user? true}))))))
