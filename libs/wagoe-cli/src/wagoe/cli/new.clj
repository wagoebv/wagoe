(ns wagoe.cli.new
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [wagoe.cli.catalogue :as cat]
            [wagoe.cli.templates :as templates]))

;; Keep in sync with libs/tools/build.clj version
(def ^:private wagoe-tools-version "1.0.0-beta-3")

;; Keep in sync with libs/wagoe-mcp/build.clj version (release-bumped with wagoe-tools-version)
(def ^:private wagoe-mcp-version "1.0.0-beta-3")

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
  "Generate project files into dir."
  [dir project-name _opts]
  (let [project-ns  (name->ns project-name)
        jwt-secret  (random-jwt-secret)
        subs        {:project-name             project-name
                     :project-ns               project-ns
                     :jwt-secret               jwt-secret
                     :wagoe-tools-version   wagoe-tools-version
                     :wagoe-mcp-version     wagoe-mcp-version
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
                     :i18n-version          (:version (cat/find-module "i18n"))}
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
                     "src/wagoe/config.clj"             "config.clj.tmpl"
                     "dev/user.clj"                        "user.clj.tmpl"
                     (str "src/" project-ns "/system.clj") "system.clj.tmpl"
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
        skip-git?  (boolean (some #{"--skip-git"} flags))]
    (when-not project-name
      (println "Usage: wagoe new <project-name> [--force] [--skip-git]")
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
      (generate! dir project-name {})
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
                    "\n  bb quickstart        # download deps, migrate, optional first module, start")))))
