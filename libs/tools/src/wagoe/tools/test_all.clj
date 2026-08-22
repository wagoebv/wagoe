#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/test_all.clj
;;
;; `bb test:all` — run EVERY test surface and fail if any of them fails.
;;
;; Why this exists: `clojure -M:test` is not the whole suite. It runs the
;; kaocha suites declared in tests.edn, which covers libs/<lib> that sit on the
;; app classpath — but wagoe-cli, wagoe-mcp and libs/tools are standalone
;; (their own deps.edn + :test alias) and are invisible to it. During the Wagoe
;; rename that hid real failures three times: 8 in wagoe-mcp, 1 in wagoe-cli,
;; and 5 in tools, including a silently-broken FC/IS gate. Each was found only
;; by remembering to run an extra command.
;;
;; A green `clojure -M:test` therefore does NOT mean the codebase is
;; green. `bb test:all` is the command that does mean that.
;;
;; Usage:
;;   bb test:all              # every surface
;;   bb test:all --list       # show the surfaces without running them

(ns wagoe.tools.test-all
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [wagoe.tools.ansi :refer [bold green red yellow dim]]))

;; =============================================================================
;; Surfaces
;; =============================================================================
;;
;; Every surface shells out, so adding one is a data change rather than new
;; control flow. `:dir` is relative to the repo root.
;;
;; NOTE none of these enumerate namespaces. The standalone libs run kaocha,
;; which discovers test/ itself; tools and agents discover from disk. That is
;; deliberate — `bb test:wagoe-cli` used to hardcode a namespace list and had
;; already drifted, silently skipping wagoe.cli.agents-update-test. A runner
;; whose coverage depends on someone remembering to extend a list is the exact
;; failure this task exists to remove (BOU-250).

(def main-suite-aliases
  "Aliases the root suite needs, as one `-M:...` argument.

   `:test/all` composes the heavy dependencies that moved out of `:test` so the
   27 per-library CI jobs stop resolving ~106 MB they never use (BOU-260). It is
   the same alias AGENTS.md documents for a full local run — one definition
   rather than a list assembled here that could drift from it.

   It includes the macOS PostgreSQL binary, which is right for a local run and
   wrong for CI. CI never uses this: its jobs request `:test/pg`, `:test/otel`
   or `:test/http` individually."
  "-M:test:test/all")

(def surfaces
  [{:id    :main
    :label "main suite (tests.edn — all app-classpath libs)"
    :dir   "."
    :cmd   ["clojure" main-suite-aliases]}
   {:id    :tools
    :label "wagoe-tools unit tests"
    :dir   "."
    :cmd   ["bb" "test:tools"]}
   {:id    :agents
    :label "AGENTS.md generator tests"
    :dir   "."
    :cmd   ["bb" "test:agents"]}
   {:id    :config
    :label "config (standalone lib)"
    :dir   "libs/config"
    :cmd   ["clojure" "-M:test"]}
   {:id    :wagoe-cli
    :label "wagoe-cli (standalone lib)"
    :dir   "libs/wagoe-cli"
    :cmd   ["clojure" "-M:test"]}
   {:id    :wagoe-mcp
    :label "wagoe-mcp (standalone lib)"
    :dir   "libs/wagoe-mcp"
    :cmd   ["clojure" "-M:test"]}])

;; Surfaces deliberately NOT run here, recorded so their absence is a decision
;; rather than an oversight — silence about what is skipped is how a runner
;; comes to look more complete than it is.
(def excluded
  [{:id     :e2e
    :label  "e2e (Playwright)"
    :reason (str "needs Chromium installed — `clojure -M:e2e -e \"(.exit "
                 "(com.microsoft.playwright.CLI/main (into-array String "
                 "[\\\"install\\\" \\\"chromium\\\"])))\"` fetches ~90MB. "
                 "Run it with `bb e2e`, which starts a server on :3100 and tears "
                 "it down. CI runs it on every pull request and a red test blocks "
                 "the merge (BOU-297).")}])

;; =============================================================================
;; Environment
;; =============================================================================

;; The suite refuses to boot :wagoe/auth-service with a short secret, and the
;; value AGENTS.md used to document was 27 characters — so the documented
;; command aborted before running a single test. Supply a valid default rather
;; than making every caller remember.
(def ^:private dev-jwt-secret "dev-secret-at-least-32-characters-long")

(defn- test-env []
  (cond-> {"WAG_ENV" "test"}
    (str/blank? (System/getenv "JWT_SECRET"))
    (assoc "JWT_SECRET" dev-jwt-secret)))

;; =============================================================================
;; Running
;; =============================================================================

(defn- run-surface
  "Run one surface, streaming its output. Returns the surface with :exit and
   :duration-ms added."
  [{:keys [label dir cmd] :as surface}]
  (println)
  (println (bold (str "── " label)))
  (println (dim (str "   " (str/join " " cmd) (when-not (= "." dir) (str "   (in " dir ")")))))
  (let [start (System/currentTimeMillis)
        {:keys [exit]} (apply process/shell
                              {:dir dir :extra-env (test-env) :continue true}
                              cmd)]
    (assoc surface :exit exit :duration-ms (- (System/currentTimeMillis) start))))

(defn- report [results]
  (println)
  (println (bold "Test surfaces"))
  (println)
  (doseq [{:keys [label exit duration-ms]} results]
    (println (format "  %s %-52s %5.1fs"
                     (if (zero? exit) (green "pass") (red "FAIL"))
                     label
                     (/ duration-ms 1000.0))))
  (doseq [{:keys [label reason]} excluded]
    (println (format "  %s %-52s %s" (yellow "skip") label (dim reason))))
  (println)
  (let [failed (remove (comp zero? :exit) results)]
    (if (seq failed)
      (println (red (format "%d of %d surface(s) failed: %s"
                            (count failed) (count results)
                            (str/join ", " (map (comp name :id) failed)))))
      (println (green (format "All %d surfaces passed." (count results)))))
    (count failed)))

(defn- print-list []
  (println (bold "Surfaces `bb test:all` runs:"))
  (doseq [{:keys [label dir cmd]} surfaces]
    (println (format "  %-52s %s%s" label (str/join " " cmd)
                     (if (= "." dir) "" (str "   (in " dir ")")))))
  (println)
  (println (bold "Deliberately excluded:"))
  (doseq [{:keys [label reason]} excluded]
    (println (format "  %-52s %s" label reason))))

(defn -main [& args]
  (if (some #{"--list"} args)
    (print-list)
    (do
      (println (bold "Running every test surface"))
      (println (dim "A green main suite alone does not cover wagoe-cli, wagoe-mcp or tools."))
      ;; Fail fast on a missing directory rather than reporting a confusing
      ;; non-zero exit from the shell.
      (doseq [{:keys [dir label]} surfaces]
        (when-not (fs/directory? dir)
          (println (red (str "surface directory missing for " label ": " dir)))
          (System/exit 2)))
      (let [results (mapv run-surface surfaces)
            failed  (report results)]
        (System/exit (if (pos? failed) 1 0))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
