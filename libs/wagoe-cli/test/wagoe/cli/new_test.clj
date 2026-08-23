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
                   ;; Under the project namespace since BOU-360 — the app's
                   ;; own wiring is not the framework's code.
                   "src/test_proj/system_config.clj"
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

      (testing "devtools is on the :repl alias and nowhere else"
        ;; The dev classpath is the point: devtools carries a dashboard and a
        ;; Jetty adapter, so :deps would put all of it in the uberjar and in the
        ;; Dockerfile image (BOU-318).
        (let [deps (edn/read-string (slurp (io/file tmp "deps.edn")))]
          (is (get-in deps [:aliases :repl :extra-deps 'com.wagoe/wagoe-devtools])
              "`clojure -M:repl` must have devtools")
          (is (nil? (get-in deps [:deps 'com.wagoe/wagoe-devtools]))
              "and the production classpath must not")
          (is (not (str/includes? (slurp (io/file tmp "deps.edn")) "{{devtools-version}}"))
              "unrendered version placeholder")))

      (testing "dev/user.clj defines the helpers quickstart tells users to run"
        ;; `bb quickstart` closes with "run (status), run (commands)". They did
        ;; not exist: the generated dev/user.clj was go/reset/halt, so the
        ;; first instruction a new user follows answered "Unable to resolve
        ;; symbol: status" (BOU-319).
        (let [src   (slurp (io/file tmp "dev/user.clj"))
              forms (read-string (str "[" src "]"))
              defs  (set (keep #(when (and (seq? %) (= 'defn (first %))) (second %)) forms))]
          (doseq [helper '[go reset halt status modules routes config commands fix!]]
            (is (contains? defs helper) (str "dev/user.clj must define (" helper ")")))))

      (testing "and it survives devtools not being on the classpath"
        ;; A :require of devtools in the ns form would take (go) down with it,
        ;; and (go) has nothing to do with devtools. Read the ns form rather
        ;; than the text: `devtools` appears half a dozen times further down,
        ;; and a text search for it matched those.
        (let [src     (slurp (io/file tmp "dev/user.clj"))
              ns-form (some #(when (and (seq? %) (= 'ns (first %))) %)
                            (read-string (str "[" src "]")))
              required (->> (tree-seq coll? seq ns-form)
                            (filter symbol?)
                            (map str))]
          (is (not-any? #(str/includes? % "devtools") required)
              "devtools must not be in the ns form")
          (is (str/includes? src "requiring-resolve"))))

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

(deftest ^:unit the-template-emits-no-integrant-keys-of-its-own
  ;; It used to emit 41, and every one of them needed an init-key that the
  ;; template also had to remember to require — the pairing this test checked by
  ;; regex. Both halves moved into the libraries that own the keys, so the
  ;; generated file names none. What replaces this check is
  ;; `wagoe.system-config-test/every-emitted-key-has-an-init-key`, which boots
  ;; the real assembler rather than reading source with a regex.
  (let [config (slurp (io/resource "wagoe/cli/templates/config.clj.tmpl"))
        system (slurp (io/resource "wagoe/cli/templates/system.clj.tmpl"))]

    (testing "the templates were read — otherwise this passes vacuously"
      (is (str/includes? config "defn ig-config"))
      (is (str/includes? system ".system")))

    (testing "the generated config assembles rather than enumerates"
      (is (str/includes? config "system/system-config"))
      (is (empty? (map second (re-seq #"\(assoc\s+(:wagoe[a-z0-9.-]*/[a-z][a-z0-9-]*)" config)))
          "an assoc of a framework key here is a graph drifting from the module that owns it"))

    (testing "and the project's own system namespace starts empty"
      ;; Anchored: the docstring shows a defmethod as the example of what to
      ;; add, and an unanchored match would read that as a definition.
      (is (empty? (re-seq #"(?m)^\(defmethod ig/init-key" system))
          "an init-key for a framework key belongs in that framework library"))))

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
                     (slurp (io/file dir "src/sample/system_config.clj"))
                     (finally
                       (doseq [f (reverse (file-seq (io/file dir)))] (.delete f))))))
        with    (render {})
        without (render {:with-user? false})]

    (testing "the flag reaches the generated config as the set the assembler takes"
      ;; A set rather than a boolean since BOU-326: the user module is the one
      ;; an application switches on in code, and :extra-modules is how.
      (is (str/includes? with    "{:extra-modules #{:wagoe/user}"))
      (is (str/includes? without "{:extra-modules #{}")))

    (testing "and the app tells the assembler where its own modules live"
      ;; Without this the default wins and a module generated into
      ;; sample.product is never found (BOU-360).
      (is (str/includes? with ":base-ns       \"sample\"")))

    (testing "both variants are the same file apart from that set"
      ;; Turning the user module back on is editing one word, not restoring a
      ;; block the generator left out.
      (is (= (str/replace with "#{:wagoe/user}" "#{}") without)))

    (testing "default is user-on"
      (is (= with (render {:with-user? true}))))))

(deftest ^:integration generated-system-config-is-readable-clojure
  ;; A template is not compiled by anything until someone generates a project
  ;; and boots it, so an unbalanced paren in it survives every gate. Reading the
  ;; rendered file back is the cheapest check that it is Clojure at all — and
  ;; the merge/cond-> restructuring in BOU-311 is exactly the kind of edit that
  ;; gets this wrong.
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/wagoe-tmpl-" (System/currentTimeMillis))]
    (try
      (new/generate! tmp "test-proj" {})
      (let [src (slurp (io/file tmp "src/test_proj/system_config.clj"))]
        (testing "no placeholder survived rendering"
          (is (not (str/includes? src "{{")) "unrendered placeholder in generated config"))

        (testing "it parses"
          (let [forms (read-string (str "[" src "]"))]
            (is (< 1 (count forms)))
            (is (some #(and (seq? %) (= 'defn (first %)) (= 'ig-config (second %))) forms)
                "ig-config must survive as a defn")))

        (testing "and it assembles rather than enumerating"
          ;; Scaffolded-module discovery and the :module-routes it feeds the
          ;; HTTP handler both moved into `system-config`. Read forms, not the
          ;; text: `str/includes?` would be satisfied by the comment that
          ;; explains the call, so deleting the call and keeping the comment
          ;; would pass. What discovery does is
          ;; `wagoe.platform.shell.system.config-test`.
          (let [ig-form (some #(when (and (seq? %) (= 'defn (first %)) (= 'ig-config (second %))) %)
                              (read-string (str "[" src "]")))
                nodes   (tree-seq coll? seq ig-form)]
            (is (some #(and (symbol? %) (= "system-config" (name %))) nodes)
                "ig-config must call system-config")
            (is (some #{:extra-modules} nodes)
                "and tell it which modules this app enables in code"))))
      (finally
        (when (.exists (io/file tmp))
          (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f)))))))
