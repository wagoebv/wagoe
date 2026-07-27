#!/usr/bin/env bb
;; scripts/ai.clj
;;
;; Babashka frontend for Boundary AI tooling.
;;
;; Usage (via bb.edn task):
;;   bb ai                           -- show help
;;   bb ai explain                   -- error explainer (reads from stdin)
;;   bb ai explain --file path       -- error explainer from file
;;   bb ai gen-tests <file>          -- generate test namespace
;;   bb ai sql <description>         -- SQL copilot (HoneySQL)
;;   bb ai docs --module <path> [--type agents|openapi|readme]
;;
;; Usage (direct):
;;   bb scripts/ai.clj explain --file stacktrace.txt

(ns ai
  (:require [clojure.string :as str]
            [babashka.process :refer [shell]]))

;; =============================================================================
;; ANSI helpers
;; =============================================================================

(defn bold   [s] (str "\033[1m"  s "\033[0m"))
(defn green  [s] (str "\033[32m" s "\033[0m"))
(defn cyan   [s] (str "\033[36m" s "\033[0m"))
(defn red    [s] (str "\033[31m" s "\033[0m"))
(defn yellow [s] (str "\033[33m" s "\033[0m"))
(defn dim    [s] (str "\033[2m"  s "\033[0m"))

;; =============================================================================
;; Run Clojure AI CLI
;; =============================================================================

(defn run-clojure!
  "Shell out to the Clojure AI CLI with given args. Streams output to terminal."
  [args]
  (try
    (apply shell "clojure" "-M" "-m" "wagoe.ai.shell.cli-entry" args)
    (catch Exception e
      (println (red (str "AI CLI exited with error: " (.getMessage e))))
      (System/exit 1))))

;; =============================================================================
;; Help text
;; =============================================================================

(def help-text
  (str (bold "Boundary AI \u2014 Framework-aware AI Tooling") "\n"
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
  (let [args      (vec raw-args)
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

      :else
      (do
        (println (red (str "Unknown subcommand: " sub)))
        (println)
        (println help-text)
        (System/exit 1)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
