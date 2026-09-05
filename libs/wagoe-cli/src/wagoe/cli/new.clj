(ns wagoe.cli.new
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [wagoe.cli.catalogue :as cat]
            [wagoe.cli.templates :as templates]))

;; Keep in sync with libs/tools/build.clj version
(def ^:private wagoe-tools-version "1.0.0-beta-7")

;; Keep in sync with libs/wagoe-mcp/build.clj version (release-bumped with wagoe-tools-version)
(def ^:private wagoe-mcp-version "1.0.0-beta-7")

(defn validate-name [n]
  (cond
    (str/blank? n)                            "Project name cannot be empty"
    (not (re-matches #"[a-z][a-z0-9]*(-[a-z0-9]+)*" n))  "Project name must be kebab-case (lowercase letters, digits, hyphens; must start with a letter)"
    :else nil))

(defn name->ns [n]
  (str/replace n "-" "_"))

(defn- write-file! [dir relative-path content]
  (let [f (io/file dir relative-path)]
    (io/make-parents f)
    (spit f content)))

(defn check-directory
  "Returns :ok, :empty-exists, :non-empty, or :not-a-dir. If force? is true, always :ok."
  [dir force?]
  (let [f (io/file dir)]
    (cond
      (not (.exists f))      :ok
      (not (.isDirectory f)) :not-a-dir
      force?                 :ok
      (empty? (.list f))     :empty-exists
      :else                  :non-empty)))

(defn- random-jwt-secret []
  (let [rng   (java.security.SecureRandom.)
        bytes (byte-array 32)]
    (.nextBytes rng bytes)
    (.encodeToString (java.util.Base64/getUrlEncoder) bytes)))

(defn generate!
  "Generate project files into dir.

   `opts` may carry `:with-user? false` to leave the user module out — see
   `--no-user` in `-main`."
  [dir project-name opts]
  (let [project-ns  (name->ns project-name)
        jwt-secret  (random-jwt-secret)
        with-user?  (not (false? (:with-user? opts)))
        subs        {;; The set `system-config` takes as :extra-modules — the
                     ;; modules an app enables in code rather than in config.
                     :user-modules             (if with-user? "#{:wagoe/user}" "#{}")
                     :project-name             project-name
                     :project-ns               project-ns
                     :jwt-secret               jwt-secret
                     :wagoe-tools-version   wagoe-tools-version
                     :wagoe-mcp-version     wagoe-mcp-version
                     :config-version        (:version (cat/find-module "config"))
                     :core-version             (:version (cat/find-module "core"))
                     :observability-version (:version (cat/find-module "observability"))
                     :platform-version      (:version (cat/find-module "platform"))
                     :user-version          (:version (cat/find-module "user"))
                     :cache-version         (:version (cat/find-module "cache"))
                     :admin-version         (:version (cat/find-module "admin"))
                     :ui-style-version      (:version (cat/find-module "ui-style"))
                     :tenant-version        (:version (cat/find-module "tenant"))
                     :workflow-version      (:version (cat/find-module "workflow"))
                     :search-version        (:version (cat/find-module "search"))
                     :external-version      (:version (cat/find-module "external"))
                     :payments-version      (:version (cat/find-module "payments"))
                     :i18n-version          (:version (cat/find-module "i18n"))
                     ;; Top-level rather than the :mcp alias, because
                     ;; `bb setup --ai-provider` writes :wagoe/ai-service into
                     ;; :active and the boot then needs the wiring on the
                     ;; *default* classpath (BOU-414).
                     :ai-version            (:version (cat/find-module "ai"))
                     ;; Dev-only, and it lands in the :repl alias — see
                     ;; deps.edn.tmpl.
                     :devtools-version      (:version (cat/find-module "devtools"))}
        files       {"deps.edn"                            "deps.edn.tmpl"
                     "bb.edn"                              "bb.edn.tmpl"
                     ".gitignore"                          "gitignore.tmpl"
                     ".env"                                "env.tmpl"
                     ".env.example"                        "env.example.tmpl"
                     "tests.edn"                           "tests.edn.tmpl"
                     "CLAUDE.md"                           "CLAUDE.md.tmpl"
                     "AGENTS.md"                           "AGENTS.md.tmpl"
                     ".claude/skills/wagoe/SKILL.md"    "claude-skill.md.tmpl"
                     "resources/conf/dev/config.edn"       "dev-config.edn.tmpl"
                     "resources/conf/test/config.edn"      "test-config.edn.tmpl"
                     ;; Under the project's own namespace, not wagoe's: this is
                     ;; the application's wiring, and a project called shop
                     ;; should not be defining namespaces in the framework's
                     ;; root (BOU-360).
                     (str "src/" project-ns "/system_config.clj") "config.clj.tmpl"
                     "dev/user.clj"                        "user.clj.tmpl"
                     (str "src/" project-ns "/system.clj") "system.clj.tmpl"
                     ;; Non-REPL entry point + the build path that uses it.
                     ;; Without these a generated project could only be started
                     ;; from an editor-connected REPL, so it could not be
                     ;; containerised, supervised, or smoke-tested (BOU-254).
                     (str "src/" project-ns "/main.clj")   "main.clj.tmpl"
                     "build.clj"                           "build.clj.tmpl"
                     "Dockerfile"                          "Dockerfile.tmpl"
                     ".mcp.json"                           "mcp.json.tmpl"
                     ".vscode/extensions.json"             "vscode-extensions.json.tmpl"
                     ".githooks/pre-commit"                "githook-pre-commit.tmpl"}]
    (doseq [[target tmpl] files]
      (write-file! dir target (templates/render (templates/read-template tmpl) subs)))
    (.setExecutable (io/file dir ".githooks/pre-commit") true false)))

(defn- run-git
  "Default git runner: shells out via clojure.java.shell. Returns the sh result map."
  [dir & args]
  (apply shell/sh (concat ["git" "-C" dir] args)))

(defn git-bootstrap!
  "Initialise a git repo in dir, point hooks at .githooks, and make an initial
   commit. Every step is non-fatal: on any failure, collect a warning and keep
   going. `run` is the git runner (injected for testing); defaults to run-git.
   Returns {:ok? bool :warnings [str]}.

   The initial commit uses --no-verify so the freshly-written .githooks/pre-commit
   (bb check:fcis + lint) does NOT fire here — that hook needs the project's deps
   resolved, which would force a network/maven download and defeat the
   fast/offline `wagoe new`. The gate is for subsequent human commits."
  ([dir] (git-bootstrap! dir run-git))
  ([dir run]
   (let [steps [["init"]
                ["config" "core.hooksPath" ".githooks"]
                ["add" "-A"]
                ["commit" "--no-verify" "-m" "Initial commit (wagoe new)"]]
         warnings (reduce
                   (fn [warns args]
                     (let [{:keys [exit err] :as r}
                           (try (apply run dir args)
                                (catch Exception e {:exit 1 :err (.getMessage e)}))]
                       (if (and (map? r) (zero? (or exit 1)))
                         warns
                         (conj warns (str "git " (str/join " " args) " failed: "
                                          (or (not-empty err) "non-zero exit"))))))
                   []
                   steps)]
     {:ok? (empty? warnings) :warnings warnings})))

(defn -main [args]
  (let [[project-name & flags] args
        force?     (boolean (some #{"--force"} flags))
        skip-git?  (boolean (some #{"--skip-git"} flags))
        ;; The user module brings auth, sessions, MFA and an audit trail, and
        ;; four tables to back them. Worth having by default and worth being
        ;; able to decline: an app with no accounts should not carry a login
        ;; page it never shows (BOU-234).
        with-user? (not (boolean (some #{"--no-user"} flags)))]
    (when-not project-name
      (println "Usage: wagoe new <project-name> [--force] [--skip-git] [--no-user]")
      (System/exit 1))
    (let [err (validate-name project-name)]
      (when err
        (println (str "Error: " err))
        (System/exit 1)))
    (let [dir            (str (System/getProperty "user.dir") "/" project-name)
          ;; True (unforced) state of the target, captured before we write. Used
          ;; to decide whether git bootstrap is safe: --force into a non-empty dir
          ;; must NOT git-init / `git add -A` over pre-existing, unrelated files.
          pre-existing?  (= :non-empty (check-directory dir false))
          status         (check-directory dir force?)]
      (case status
        :not-a-dir
        (do (println (str "Error: " project-name " already exists and is not a directory."))
            (System/exit 1))
        :non-empty
        (do (println (str "Error: Directory " project-name "/ already exists and is not empty."))
            (println "Use a different name, remove the directory, or pass --force.")
            (System/exit 1))
        :empty-exists nil
        :ok nil)
      (println (str "Creating " project-name "/..."))
      ;; A directory the user cannot write to is a permissions problem, not a
      ;; stack trace. check-directory above cannot catch it: the target does not
      ;; exist yet, so the failure surfaces from clojure.java.io/writer partway
      ;; through generate!. Found by the read-only case in
      ;; scripts/first-run-adversarial.sh, which could only run once that case
      ;; stopped skipping (BOU-232).
      (try
        (generate! dir project-name {:with-user? with-user?})
        (catch java.io.IOException e
          (println)
          (println (str "Error: cannot write to " dir))
          (println (str "  " (.getMessage e)))
          (println)
          (println "The directory is not writable. Pick a location you own, or")
          (println "check the permissions on the parent directory.")
          (System/exit 1)))
      (cond
        skip-git?     nil
        pre-existing? (println (str "  ⚠ Skipped git init: " project-name
                                    "/ already had files (--force). Initialise git yourself "
                                    "so unrelated files aren't committed."))
        :else
        (let [{:keys [ok? warnings]} (git-bootstrap! dir)]
          (doseq [w warnings] (println (str "  ⚠ " w)))
          (when-not ok?
            (println (str "  ⚠ git setup was incomplete (the files are written either way). "
                          "If git identity is unset, run: git -C " project-name
                          " config user.email you@example.com && git -C " project-name
                          " commit -m \"Initial commit\"")))))
      (println (str "\n✓ Project created: " project-name "/"))
      (println "\nCore modules installed: core, observability, platform, user")
      (println "\nOptional modules available — add any with:\n")
      (doseq [{:keys [description add-command]} (take 6 (cat/optional-modules))]
        (println (format "  %-25s %s" add-command description)))
      (println "  ... (wagoe list modules for full list)")
      (println "\nAI-ready: CLAUDE.md, AGENTS.md, a Claude Code skill, and a wired MCP server (.mcp.json) are included.")
      (println "Open Claude Code or Cursor here — the Wagoe MCP server is live, so the agent has Wagoe's tools immediately.")
      (println (str "\nNext:\n  cd " project-name
                    "\n  bb quickstart        # download deps, migrate, optional first module, start"
                    "\n\nIf anything looks wrong, `wagoe doctor` checks the project and tells"
                    "\nyou the one thing to fix. `bb guide` has the topic guides.")))))
