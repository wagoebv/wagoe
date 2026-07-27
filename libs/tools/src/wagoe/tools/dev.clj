#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/dev.clj
;;
;; Development utilities for Boundary projects:
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
      (str/starts-with? link "#")))

(defn- resolve-target [base-file link]
  (let [target (first (str/split link #"#"))]
    (if (str/starts-with? target "/")
      (io/file root-dir (subs target 1))
      (.getCanonicalFile (io/file (.getParentFile base-file) target)))))

(defn- check-file [file]
  (let [content (slurp file)
        link-pattern #"\[[^\]]+\]\(([^)]+)\)"
        local-links (->> (re-seq link-pattern content)
                         (map second)
                         (map str/trim)
                         (remove skippable?))
        broken (->> local-links
                    (map (fn [link]
                           (let [target (resolve-target file link)]
                             (when-not (.exists target)
                               {:file file :link link :target target}))))
                    (remove nil?))]
    {:checked (count local-links)
     :broken broken}))

(defn check-links
  "Validate local markdown links in AGENTS.md files (root + all libs/).
   Prints a summary and exits non-zero when broken links are found."
  []
  (let [files (vec (iter-agents-files))
        results (map check-file files)
        total-checked (reduce + (map :checked results))
        all-broken (vec (mapcat :broken results))]
    (println (str "AGENTS files checked: " (count files)))
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

;; Aliases every Boundary project must have.
(def ^:private required-aliases [:migrate :test])

;; The REPL alias name differs between the monorepo (:repl-clj) and generated
;; projects (:repl). Accept either so smoke-check works in both contexts.
(def ^:private repl-aliases #{:repl :repl-clj})

(defn- load-deps-aliases []
  (let [deps-file (io/file root-dir "deps.edn")
        content (slurp deps-file)
        deps (edn/read-string content)]
    (set (keys (:aliases deps)))))

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
  (run-check "Checking migrate CLI entrypoint" "clojure" "-M:migrate" "--help")
  (run-check "Checking test runner entrypoint" "clojure" "-M:test" "--help")
  (run-check "Running AGENTS link check" "bb" "check-links")
  (println "[smoke] Command smoke checks passed"))

;; =============================================================================
;; migrate — bb passthrough to clojure -M:migrate
;; =============================================================================

(defn migrate
  "Run the standard Boundary migrate CLI via bb for a shorter DX path.

   Examples:
     bb migrate up
     bb migrate status
     bb migrate create add-tenant-memberships"
  [& args]
  (if (or (empty? args)
          (#{"--help" "-h" "help"} (first args)))
    (do
      (println "Boundary migration CLI")
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
