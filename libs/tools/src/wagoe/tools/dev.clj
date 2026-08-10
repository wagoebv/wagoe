#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/dev.clj
;;
;; Development utilities for Wagoe projects:
;;   - check-links    Validate local markdown links in AGENTS documentation
;;   - smoke-check    Verify deps.edn aliases and key tool entrypoints
;;   - migrate        Babashka passthrough to the standard migrate CLI
;;   - install-hooks  Configure git hooks path to .githooks

(ns wagoe.tools.dev
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :refer [shell]]))

;; =============================================================================
;; check-links — validate local markdown links in AGENTS documentation
;; =============================================================================

(def ^:private root-dir (io/file (System/getProperty "user.dir")))

(defn- iter-agents-files []
  (let [root-agents (io/file root-dir "AGENTS.md")
        libs-dir (io/file root-dir "libs")
        lib-agents (when (.exists libs-dir)
                     (->> (.listFiles libs-dir)
                          (filter #(.isDirectory %))
                          (sort-by #(.getName %))
                          (map #(io/file % "AGENTS.md"))))]
    (->> (cons root-agents lib-agents)
         (filter #(.exists %)))))

(defn- skippable? [link]
  (or (str/starts-with? link "http://")
      (str/starts-with? link "https://")
      (str/starts-with? link "mailto:")
      (str/starts-with? link "#")
      ;; Antora module-qualified xrefs — `xref:guides:create-module.adoc[]`.
      ;; Antora resolves these through its component/module map, not the
      ;; filesystem, so checking them as paths reports 120 false breakages.
      ;; A leading `./` or `../` is a real relative path and is not skipped.
      ;; Module names may be capitalised — Antora's default module is `ROOT`.
      (re-find #"^[A-Za-z][A-Za-z0-9_-]*:" link)))

(defn- link-base-dir
  "The directory a link in `file` resolves against.

   Antora's nav.adoc is the exception: its xrefs resolve against the module's
   `pages/` directory, not against the nav file's own location. Resolving them
   as ordinary relative paths reported every nav entry as broken."
  [file]
  (let [parent (.getParentFile file)]
    (if (= "nav.adoc" (.getName file))
      (let [pages (io/file parent "pages")]
        (if (.isDirectory pages) pages parent))
      parent)))

(defn- resolve-target [base-file link]
  (let [target (first (str/split link #"#"))]
    (if (str/starts-with? target "/")
      (io/file root-dir (subs target 1))
      (.getCanonicalFile (io/file (link-base-dir base-file) target)))))

(defn- iter-adoc-files
  "Every .adoc under dev-docs/ and docs/.

   These were never checked: the link pattern below matched markdown only, and
   dev-docs is 100% AsciiDoc — 591 `link:`/`xref:` macros and not one markdown
   link, so `bb check-links` reported a clean 63 links while never opening a
   single file there."
  []
  (->> [(io/file root-dir "dev-docs") (io/file root-dir "docs")]
       (filter #(.exists %))
       (mapcat #(file-seq %))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".adoc")))
       (sort-by #(.getPath %))))

(defn- doc-links
  "Local link targets in `content`, for both markdown and AsciiDoc.

   AsciiDoc writes `link:path[text]` and `xref:path[text]`; the markdown
   pattern cannot see either."
  [content]
  (->> (concat (map second (re-seq #"\[[^\]]+\]\(([^)]+)\)" content))
               (map second (re-seq #"(?:xref|link):([^\[\s]+)\[" content)))
       (map str/trim)
       (remove str/blank?)
       (remove skippable?)))

(defn- check-file [file]
  (let [content (slurp file)
        local-links (doc-links content)
        broken (->> local-links
                    (map (fn [link]
                           (let [target (resolve-target file link)]
                             (when-not (.exists target)
                               {:file file :link link :target target}))))
                    (remove nil?))]
    {:checked (count local-links)
     :broken broken}))

(defn check-links
  "Validate local links in AGENTS.md files and in the AsciiDoc documentation.
   Prints a summary and exits non-zero when broken links are found."
  []
  (let [agents (vec (iter-agents-files))
        adocs  (vec (iter-adoc-files))
        files  (into agents adocs)
        results (map check-file files)
        total-checked (reduce + (map :checked results))
        all-broken (vec (mapcat :broken results))]
    (println (str "AGENTS files checked: " (count agents)))
    (println (str "AsciiDoc files checked: " (count adocs)))
    (println (str "Local links checked: " total-checked))
    (println (str "Broken links: " (count all-broken)))
    (when (seq all-broken)
      (doseq [{:keys [file link target]} all-broken]
        (let [rel (.relativize (.toPath root-dir) (.toPath file))]
          (println (str "\n" rel "\n  -> " link "\n  => " (.getPath target))))))
    (System/exit (if (seq all-broken) 1 0))))

;; =============================================================================
;; smoke-check — verify deps.edn aliases and key tool entrypoints
;; =============================================================================

;; Aliases every Wagoe project must have.
(def ^:private required-aliases [:migrate :test])

;; The REPL alias name differs between the monorepo (:repl-clj) and generated
;; projects (:repl). Accept either so smoke-check works in both contexts.
(def ^:private repl-aliases #{:repl :repl-clj})

(defn- load-deps-aliases []
  (let [deps-file (io/file root-dir "deps.edn")
        content (slurp deps-file)
        deps (edn/read-string content)]
    (set (keys (:aliases deps)))))

(def ^:private jdbc-drivers
  "Artifacts that register a JDBC driver."
  #{'org.xerial/sqlite-jdbc 'org.postgresql/postgresql
    'com.h2database/h2 'com.mysql/mysql-connector-j})

(defn app-main-ns
  "The application's main namespace, read from the source tree.

   `src/wagoe/main.clj` here, `src/<project>/main.clj` in a generated project.
   Found rather than guessed: matching any `-m …\\.main` also caught
   `clj-kondo.main` and `depot.outdated.main`, which are tools that never touch
   a database.

   Returns the namespace string, or nil when there is no src/*/main.clj."
  [dir]
  (let [src (io/file dir "src")]
    (when (.isDirectory src)
      (some (fn [^java.io.File child]
              (when (and (.isDirectory child)
                         (.exists (io/file child "main.clj")))
                (str (str/replace (.getName child) "_" "-") ".main")))
            (sort (.listFiles src))))))

(defn boots-the-system?
  "Whether running this alias can build `:wagoe/db-context`.

   Derived from the alias rather than listed by hand — a hand-kept list is one
   edit away from omitting the alias that breaks.

   Two shapes, because the monorepo and a generated project name things
   differently: an alias that runs the app's own `-main`, or an nREPL, where
   `(go)` is the documented way to start the system. A ClojureScript nREPL is
   excluded — piggieback drives a browser REPL, not the Integrant system."
  [main-ns [_ alias-map]]
  (let [main (str/join " " (:main-opts alias-map))]
    (and (or (and main-ns (re-find (re-pattern (str "-m\\s+"
                                                    (java.util.regex.Pattern/quote main-ns)
                                                    "(\\s|$)"))
                                   main))
             (str/includes? main "nrepl.cmdline"))
         (not (str/includes? main "piggieback"))
         (not (str/includes? main "cljs")))))

(defn drivers-on-base-classpath?
  "Whether the root `:deps` already provides a JDBC driver.

   Generated projects declare theirs there, so every alias inherits one and
   asking each alias separately is the wrong question. The monorepo declares
   them per alias."
  [deps]
  (boolean (some jdbc-drivers (keys (:deps deps)))))

(defn aliases-missing-drivers
  "System-booting aliases in `deps` that declare no JDBC driver.

   Returns nil when the question does not apply because the base classpath
   already carries one."
  [deps main-ns]
  (when-not (drivers-on-base-classpath? deps)
    (->> (:aliases deps)
         (filter (partial boots-the-system? main-ns))
         (remove (fn [[_ m]] (some jdbc-drivers (keys (:extra-deps m)))))
         (mapv first))))

(defn- check-system-aliases-have-drivers
  "Every alias that can start the system must reach a JDBC driver.

   `:repl-clj` could not. The documented REPL workflow — `clojure -M:repl-clj`
   then `(go)` — died with `ClassNotFoundException: org.h2.Driver` for every
   profile, and nothing noticed, because the alias existed and that was all
   anything checked."
  []
  (println "[smoke] Verifying system-booting aliases carry a JDBC driver")
  (let [deps    (edn/read-string (slurp (io/file root-dir "deps.edn")))
        main-ns (app-main-ns root-dir)]
    (if (drivers-on-base-classpath? deps)
      (println "[smoke] OK drivers are on the base classpath — every alias inherits one")
      (let [booting (filter (partial boots-the-system? main-ns) (:aliases deps))
            broken  (aliases-missing-drivers deps main-ns)]
        ;; No matches means the derivation stopped recognising this project's
        ;; layout, which is silence rather than a pass.
        (when (empty? booting)
          (binding [*out* *err*]
            (println "[smoke] Found no system-booting aliases and no base driver — the check is not looking at anything"))
          (System/exit 1))
        (if (seq broken)
          (do (binding [*out* *err*]
                (doseq [a broken]
                  (println (str "[smoke] Alias " a " can start the system but reaches no JDBC driver"))))
              (System/exit 1))
          (println (str "[smoke] OK " (count booting) " system-booting alias(es) carry a driver")))))))

(defn- check-aliases []
  (println "[smoke] Verifying required aliases exist in deps.edn")
  (let [known (load-deps-aliases)]
    (doseq [a required-aliases]
      (if (contains? known a)
        (println (str "[smoke] OK alias " a))
        (do
          (binding [*out* *err*]
            (println (str "[smoke] Missing required alias in deps.edn: " a)))
          (System/exit 1))))
    (if-let [found (first (filter known repl-aliases))]
      (println (str "[smoke] OK alias " found))
      (do
        (binding [*out* *err*]
          (println "[smoke] Missing required alias in deps.edn: :repl or :repl-clj"))
        (System/exit 1)))))

(defn- run-check [label & cmd]
  (println (str "[smoke] " label))
  (apply shell {:out :string} cmd))

(defn smoke-check
  "Verify deps.edn aliases and key tool entrypoints. Exits non-zero on failure."
  []
  (check-aliases)
  (check-system-aliases-have-drivers)
  (run-check "Checking migrate CLI entrypoint" "clojure" "-M:migrate" "--help")
  (run-check "Checking test runner entrypoint" "clojure" "-M:test" "--help")
  (run-check "Running AGENTS link check" "bb" "check-links")
  (println "[smoke] Command smoke checks passed"))

;; =============================================================================
;; migrate — bb passthrough to clojure -M:migrate
;; =============================================================================

(defn migrate
  "Run the standard Wagoe migrate CLI via bb for a shorter DX path.

   Examples:
     bb migrate up
     bb migrate status
     bb migrate create add-tenant-memberships"
  [& args]
  (if (or (empty? args)
          (#{"--help" "-h" "help"} (first args)))
    (do
      (println "Wagoe migration CLI")
      (println)
      (println "Usage:")
      (println "  bb migrate [command] [options]")
      (println)
      (println "Commands:")
      (println "  up                 Run all pending migrations")
      (println "  rollback           Roll back the last migration")
      (println "  status             Show current migration status")
      (println "  create <name>      Create a new migration file")
      (println "  init               Initialize migration tracking")
      (println "  reset              Roll back all migrations and re-apply them")
      (println)
      (println "Examples:")
      (println "  bb migrate up")
      (println "  bb migrate status")
      (println "  bb migrate create add-tenant-memberships")
      (System/exit 0))
    (let [result (apply shell {:out :inherit
                               :err :inherit
                               :continue true}
                        "clojure" "-M:migrate" args)]
      (System/exit (:exit result)))))

;; =============================================================================
;; install-hooks — configure git hooks path to .githooks
;; =============================================================================

(defn install-hooks
  "Configure git hooks path to .githooks."
  []
  (try
    (shell "git" "config" "core.hooksPath" ".githooks")
    (println "Configured git hooks path: .githooks")
    (catch Exception e
      (let [err (str (get (ex-data e) :err "") " " (.getMessage e))]
        (if (or (str/includes? err "not in a git directory")
                (str/includes? err "not a git repository"))
          (do
            (println "Warning: could not configure git hooks — not in a git repository.")
            (println "  Run 'git init' first, then run 'bb install-hooks' again."))
          (throw e))))))

;; =============================================================================
;; Entry point (for direct invocation)
;; =============================================================================

(defn -main [& args]
  (let [[cmd] args]
    (case cmd
      "migrate"       (apply migrate (rest args))
      "check-links"   (check-links)
      "smoke-check"   (smoke-check)
      "install-hooks" (install-hooks)
      (do (println "Usage: bb dev <migrate|check-links|smoke-check|install-hooks>")
          (System/exit 1)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
