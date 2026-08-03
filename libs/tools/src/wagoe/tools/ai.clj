#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/ai.clj
;;
;; Framework-aware AI tooling for the Wagoe framework.
;;
;; Usage (via bb.edn task):
;;   bb ai                           -- show help
;;   bb ai explain                   -- error explainer (reads from stdin)
;;   bb ai explain --file path       -- error explainer from file
;;   bb ai gen-tests <file>          -- generate test namespace
;;   bb ai sql <description>         -- SQL copilot (HoneySQL)
;;   bb ai docs --module <path> [--type agents|openapi|readme]

(ns wagoe.tools.ai
  (:require [wagoe.tools.ansi :refer [bold red]]
            [clojure.java.io :as io]
            [babashka.process :refer [shell]]))

;; =============================================================================
;; Run Clojure AI CLI
;; =============================================================================

;; Must match libs/tools/build.clj and libs/ai/build.clj. Update with the other
;; release pins — scaffolder-version in scaffold.clj, and the two in
;; libs/wagoe-cli/src/wagoe/cli/new.clj.
(def ^:private ai-version "1.0.0-beta-4")

;; Match libs/ai/deps.edn and the monorepo's own pin.
(def ^:private tools-cli-version "1.4.256")

(defn- ai-deps
  "The -Sdeps argument that makes wagoe.ai.shell.cli-entry resolvable.

   Generated projects carry com.wagoe/wagoe-ai only in their :mcp alias, never
   in :deps, so a plain `clojure -M` could not find it and every `bb ai`
   subcommand died with a FileNotFoundException (BOU-272). Injecting the
   dependency here rather than expecting it in deps.edn matches what
   wagoe.tools.scaffold already does.

   WAGOE_AI_ROOT overrides the pin with a local checkout — the only way to
   exercise unreleased AI code from a generated project, since this dependency
   is injected here and a :local/root rewrite of deps.edn has no effect on it.

   The root is a parameter as well as an env lookup so a test can reach both
   branches; reading the environment inline would leave the override arm
   testable only by whatever the test runner happened to have set."
  ([] (ai-deps (System/getenv "WAGOE_AI_ROOT")))
  ([root]
   ;; tools.cli is named explicitly as well as being declared by libs/ai. The
   ;; library declaration is the real fix, but it only reaches users on the next
   ;; release — the already-published version this pins has a POM without it, so
   ;; injecting wagoe-ai alone still failed with
   ;;   Could not locate clojure/tools/cli
   ;; Naming it here makes `bb ai` work against the currently published
   ;; artifact, and is harmless once the POM carries it.
   (let [ai-coord (if root
                    (str "{:local/root \"" root "\"}")
                    (str "{:mvn/version \"" ai-version "\"}"))]
     (str "{:deps {com.wagoe/wagoe-ai " ai-coord " "
          "org.clojure/tools.cli {:mvn/version \"" tools-cli-version "\"}}}"))))

(defn- run-clojure!
  "Shell out to the Clojure AI CLI with given args. Streams output to terminal.

   In generated projects (no libs/ai directory) the dependency is injected via
   -Sdeps. In the monorepo libs/ai/src is already on the classpath, so -Sdeps is
   skipped to avoid forcing Maven resolution of an artifact that may not yet be
   published."
  [args]
  (try
    (let [in-monorepo? (.exists (io/file "libs/ai"))
          base-cmd     (if in-monorepo?
                         ["clojure" "-M" "-m" "wagoe.ai.shell.cli-entry"]
                         ["clojure" "-Sdeps" (ai-deps)
                          "-M" "-m" "wagoe.ai.shell.cli-entry"])]
      (apply shell (concat base-cmd args)))
    (catch Exception e
      (println (red (str "AI CLI exited with error: " (.getMessage e))))
      (System/exit 1))))

;; =============================================================================
;; Help text
;; =============================================================================

(def ^:private help-text
  (str (bold "Wagoe AI \u2014 Framework-aware AI Tooling") "\n"
       "\n"
       "Usage:\n"
       "  bb ai                               Show this help\n"
       "  bb ai explain                       Explain error from stdin\n"
       "  bb ai explain --file <path>         Explain error from file\n"
       "  bb ai gen-tests <file>              Generate test namespace\n"
       "  bb ai gen-tests <file> -o <output>  Write tests to file\n"
       "  bb ai sql <description>             Generate HoneySQL from description\n"
       "  bb ai docs --module <path>          Generate all docs (agents, openapi, readme)\n"
       "  bb ai docs --module <path> --type agents|openapi|readme\n"
       "  bb ai admin-entity <description>    Generate admin entity EDN config\n"
       "\n"
       "Provider selection (environment variables):\n"
       "  ANTHROPIC_API_KEY   \u2192 Anthropic (Claude)\n"
       "  OPENAI_API_KEY      \u2192 OpenAI (GPT)\n"
       "  OLLAMA_URL          \u2192 Ollama (local, default http://localhost:11434)\n"
       "  AI_MODEL            \u2192 Override default model\n"
       "\n"
       "Examples:\n"
       "  cat stacktrace.txt | bb ai explain\n"
       "  bb ai explain --file errors.txt\n"
       "  bb ai gen-tests libs/user/src/wagoe/user/core/validation.clj\n"
       "  bb ai sql \"find active users with orders in the last 7 days\"\n"
       "  bb ai docs --module libs/user --type agents\n"
       "  bb ai admin-entity \"products with name, price, status\"\n"
       "\n"
       "For NL module scaffolding:\n"
       "  bb scaffold ai \"product module with name, price, stock\"\n"
       "\n"
       "The tool delegates to:\n"
       "  clojure -M -m wagoe.ai.shell.cli-entry <subcommand> [opts]"))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& raw-args]
  (let [args (vec raw-args)
        [sub & rest-args] args]
    (cond
      (or (nil? sub)
          (contains? #{"-h" "--help" "help"} sub))
      (println help-text)

      (= sub "explain")
      (run-clojure! (into ["explain"] rest-args))

      (= sub "gen-tests")
      (run-clojure! (into ["gen-tests"] rest-args))

      (= sub "sql")
      (run-clojure! (into ["sql"] rest-args))

      (= sub "docs")
      (run-clojure! (into ["docs"] rest-args))

      (= sub "admin-entity")
      (run-clojure! (into ["admin-entity"] rest-args))

      (= sub "setup-parse")
      (run-clojure! (into ["setup-parse"] rest-args))

      :else
      (do
        (println (red (str "Unknown subcommand: " sub)))
        (println)
        (println help-text)
        (System/exit 1)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
