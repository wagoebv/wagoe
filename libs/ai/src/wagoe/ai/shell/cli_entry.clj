(ns wagoe.ai.shell.cli-entry
  "Clojure CLI entrypoint for AI features.

   Called by the Babashka scripts/ai.clj script:
     clojure -M -m wagoe.ai.shell.cli-entry <subcommand> [args]

   Subcommands:
     scaffold-ai <description>                 -- NL module scaffolding
     explain [--file path] [--stdin]           -- error explainer
     gen-tests <source-file>                   -- test generator
     sql <description>                         -- SQL copilot
     docs --module <path> --type <type>        -- documentation wizard"
  (:require [wagoe.ai.core.parsing :as parsing]
            [wagoe.ai.shell.module-wiring]
            [wagoe.ai.shell.providers.anthropic :as anthropic]
            [wagoe.ai.shell.providers.ollama :as ollama]
            [wagoe.ai.shell.providers.openai :as openai]
            [wagoe.ai.shell.service :as svc]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [integrant.core :as ig])
  (:gen-class))

;; =============================================================================
;; ANSI helpers
;; =============================================================================

(defn- bold  [s] (str "\033[1m"  s "\033[0m"))
(defn- green [s] (str "\033[32m" s "\033[0m"))
(defn- red   [s] (str "\033[31m" s "\033[0m"))
(defn- cyan  [s] (str "\033[36m" s "\033[0m"))
(defn- yellow [s] (str "\033[33m" s "\033[0m"))
(defn- dim   [s] (str "\033[2m"  s "\033[0m"))

;; Must match libs/tools/src/wagoe/tools/scaffold.clj scaffolder-version.
;; Update both together on each release.
(def ^:private scaffolder-version "1.0.0-beta-4")

;; =============================================================================
;; Service bootstrap
;; =============================================================================

(defn- make-service-from-env
  "Fall-back when no :wagoe/ai-service is present in active config.
   Checks ANTHROPIC_API_KEY, OPENAI_BASE_URL, OPENAI_API_KEY, OLLAMA_URL in that order.
   OPENAI_BASE_URL covers OpenAI-compatible endpoints (oMLX, LM Studio, etc.) that may
   not require a real API key."
  []
  (cond
    (System/getenv "ANTHROPIC_API_KEY")
    {:provider (anthropic/create-anthropic-provider
                {:api-key (System/getenv "ANTHROPIC_API_KEY")
                 :model   (or (System/getenv "AI_MODEL") "claude-haiku-4-5-20251001")})}

    (System/getenv "OPENAI_BASE_URL")
    {:provider (openai/create-openai-provider
                {:base-url (System/getenv "OPENAI_BASE_URL")
                 :api-key  (or (System/getenv "OPENAI_API_KEY") "no-key")
                 :model    (or (System/getenv "AI_MODEL") "gpt-4o-mini")})}

    (System/getenv "OPENAI_API_KEY")
    {:provider (openai/create-openai-provider
                {:api-key (System/getenv "OPENAI_API_KEY")
                 :model   (or (System/getenv "AI_MODEL") "gpt-4o-mini")})}

    :else
    {:provider (ollama/create-ollama-provider
                {:base-url (or (System/getenv "OLLAMA_URL") "http://localhost:11434")
                 :model    (or (System/getenv "AI_MODEL") "qwen2.5-coder:7b")})}))

(defn- provider-env-vars-set?
  "Returns true when the developer has explicitly configured a provider via
   environment variables, indicating their intent to use a specific backend."
  []
  (or (System/getenv "ANTHROPIC_API_KEY")
      (System/getenv "OPENAI_BASE_URL")
      (System/getenv "OPENAI_API_KEY")))

(defn- make-service-from-config
  "Build an AI service from the Aero config file (resources/conf/{env}/config.edn).

   Priority:
     1. Explicit provider env vars (ANTHROPIC_API_KEY, OPENAI_BASE_URL, OPENAI_API_KEY)
        — developer intent always wins over project config.
     2. :wagoe/ai-service from config, when present and not :no-op.
     3. make-service-from-env fallback (config absent, resources missing, or :no-op).

   Errors from a present but broken config still surface immediately."
  []
  (if (provider-env-vars-set?)
    (make-service-from-env)
    (let [config-available? (try (require 'wagoe.config) true
                                 (catch Exception _ false))]
      (if-not config-available?
        (make-service-from-env)
        (let [load-config (resolve 'wagoe.config/load-config)
              config      (try (load-config)
                               (catch Exception e
                                 ;; Config resources absent (external consumer without
                                 ;; resources/conf/<env>/config.edn) — fall back to env vars.
                                 ;; Pin to the exact message so Aero env-var resolution
                                 ;; errors ("Environment variable not found: X") are NOT
                                 ;; swallowed — those indicate a broken config that should
                                 ;; surface immediately.
                                 (if (= (str (.getMessage e)) "Configuration file not found")
                                   nil
                                   (throw e))))
              ai-cfg      (when config (get-in config [:active :wagoe/ai-service]))]
          (if (and ai-cfg (not= (:provider ai-cfg) :no-op))
            (ig/init-key :wagoe/ai-service ai-cfg)
            (make-service-from-env)))))))

;; =============================================================================
;; Subcommand: scaffold-ai
;; =============================================================================

(def scaffold-ai-opts
  [["-r" "--root ROOT" "Project root" :default "."]
   ["-y" "--yes" "Skip confirmation and generate immediately"]
   ["-h" "--help"]])

(defn- confirm?
  "Prompt for yes/no confirmation. Enter defaults to yes."
  [label]
  (print (str label " [Y/n]: "))
  (flush)
  (let [input (-> (or (read-line) "") str/trim str/lower-case)]
    (or (empty? input) (= input "y") (= input "yes"))))

(defn cmd-scaffold-ai [args]
  (let [{:keys [options arguments]} (cli/parse-opts args scaffold-ai-opts)
        description (str/join " " arguments)]
    (when (or (:help options) (str/blank? description))
      (println "Usage: bb scaffold ai <description>")
      (println "  Example: bb scaffold ai \"product module with name, price, stock\"")
      (System/exit 0))
    (println (bold "\u2746 Wagoe AI Scaffolder"))
    (println)
    (println (dim (str "Parsing: " description)))
    (println)
    (let [service (make-service-from-config)
          result  (svc/scaffold-from-description service description (:root options))]
      (if (:error result)
        (do (println (red (str "Error: " (:error result)))) (System/exit 1))
        (do
          (println (cyan "\u250c\u2500 Preview \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510"))
          (println (str (cyan "\u2502") " Module:  " (bold (:module-name result))))
          (println (str (cyan "\u2502") " Entity:  " (bold (:entity result))))
          (println (str (cyan "\u2502") " Fields:"))
          (doseq [{:keys [name type required unique]} (:fields result)]
            (let [mods (str/join ", " (filter some? [(when required "required") (when unique "unique")]))]
              (println (str (cyan "\u2502") "   " (format "%-14s" name) (format "%-10s" type)
                            (when (seq mods) (str " (" mods ")"))))))
          (println (str (cyan "\u2502") " HTTP: " (if (:http result) (green "\u2713") (red "\u2717"))
                        "  Web: " (if (:web result) (green "\u2713") (red "\u2717"))))
          (println (cyan "\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518"))
          (println)
          (if (or (:yes options) (confirm? "Generate this module?"))
            (let [cli-args     (parsing/module-spec->cli-args result)
                  in-monorepo? (.exists (io/file "libs/scaffolder"))
                  base-cmd     (if in-monorepo?
                                 ["clojure" "-M" "-m" "wagoe.scaffolder.shell.cli-entry"]
                                 ["clojure"
                                  "-Sdeps"
                                  (str "{:deps {com.wagoe/wagoe-scaffolder "
                                       "{:mvn/version \"" scaffolder-version "\"}}}")
                                  "-M" "-m" "wagoe.scaffolder.shell.cli-entry"])
                  {:keys [exit out err]} (apply sh/sh (concat base-cmd cli-args))]
              (when (seq out) (print out))
              (when (seq err) (binding [*out* *err*] (print err)))
              (System/exit exit))
            (println (yellow "Cancelled. No files were generated."))))))))

;; =============================================================================
;; Subcommand: explain
;; =============================================================================

(def explain-opts
  [["-f" "--file FILE" "Read stack trace from file"]
   ["-r" "--root ROOT" "Project root" :default "."]
   ["-h" "--help"]])

(defn cmd-explain [args]
  (let [{:keys [options]} (cli/parse-opts args explain-opts)
        stacktrace (if (:file options)
                     (slurp (:file options))
                     (slurp *in*))]
    (when (str/blank? stacktrace)
      (println (red "No stack trace provided. Pipe via stdin or use --file."))
      (System/exit 1))
    (let [service (make-service-from-config)
          result  (svc/explain-error service stacktrace (:root options))]
      (if (:error result)
        (do (println (red (str "Error: " (:error result)))) (System/exit 1))
        (do
          (println)
          (println (bold "=== AI Error Explanation ==="))
          (println)
          (println (:text result))
          (println)
          (println (dim (str "[" (:provider result) "/" (:model result)
                             " \u2014 " (:tokens result) " tokens]"))))))))

;; =============================================================================
;; Subcommand: gen-tests
;; =============================================================================

(def gen-tests-opts
  [["-o" "--output FILE" "Write to file instead of stdout"]
   ["-h" "--help"]])

(defn cmd-gen-tests [args]
  (let [{:keys [options arguments]} (cli/parse-opts args gen-tests-opts)
        source-path (first arguments)]
    (when (or (:help options) (nil? source-path))
      (println "Usage: bb ai gen-tests <source-file>")
      (System/exit 0))
    (println (bold "\u2746 Wagoe AI Test Generator"))
    (println (dim (str "Source: " source-path)))
    (println)
    (let [service (make-service-from-config)
          result  (svc/generate-tests service source-path)]
      (if (:error result)
        (do (println (red (str "Error: " (:error result)))) (System/exit 1))
        (let [test-src (:text result)]
          (if (:output options)
            (do (spit (:output options) test-src)
                (println (green (str "\u2713 Tests written to " (:output options)))))
            (println test-src)))))))

;; =============================================================================
;; Subcommand: sql
;; =============================================================================

(def sql-opts
  [["-r" "--root ROOT" "Project root" :default "."]
   ["-h" "--help"]])

(defn cmd-sql [args]
  (let [{:keys [options arguments]} (cli/parse-opts args sql-opts)
        description (str/join " " arguments)]
    (when (or (:help options) (str/blank? description))
      (println "Usage: bb ai sql <description>")
      (System/exit 0))
    (let [service (make-service-from-config)
          result  (svc/sql-from-description service description (:root options))]
      (if (:error result)
        (do (println (red (str "Error: " (:error result)))) (System/exit 1))
        (do
          (println)
          (println (bold "=== HoneySQL ==="))
          (println (:honeysql result))
          (println)
          (println (bold "=== Explanation ==="))
          (println (:explanation result))
          (println)
          (println (bold "=== Raw SQL ==="))
          (println (:raw-sql result)))))))

;; =============================================================================
;; Subcommand: docs
;; =============================================================================

(def docs-opts
  [["-m" "--module MODULE" "Module path (e.g. libs/user)"]
   ["-t" "--type TYPE"    "Doc type: agents, openapi, readme" :default "agents"]
   ["-o" "--output FILE"  "Write to file instead of stdout"]
   ["-h" "--help"]])

(defn cmd-docs [args]
  (let [{:keys [options]} (cli/parse-opts args docs-opts)]
    (when (or (:help options) (nil? (:module options)))
      (println "Usage: bb ai docs --module <path> [--type agents|openapi|readme]")
      (System/exit 0))
    (let [module-path (:module options)
          doc-types   (if (= (:type options) "all")
                        [:agents :openapi :readme]
                        [(keyword (:type options))])
          service     (make-service-from-config)]
      (doseq [doc-type doc-types]
        (println (bold (str "\u2746 Generating " (name doc-type) " for " module-path)))
        (println)
        (let [result (svc/generate-docs service module-path doc-type)]
          (if (:error result)
            (println (red (str "Error: " (:error result))))
            (if (:output options)
              (let [fname (str (:output options)
                               (when (> (count doc-types) 1)
                                 (str "-" (name doc-type))))]
                (spit fname (:text result))
                (println (green (str "\u2713 Written to " fname))))
              (println (:text result)))))))))

;; =============================================================================
;; Subcommand: admin-entity
;; =============================================================================

(def admin-entity-opts
  [["-r" "--root ROOT" "Project root" :default "."]
   ["-y" "--yes" "Skip confirmation and write immediately"]
   ["-h" "--help"]])

(defn cmd-admin-entity [args]
  (let [{:keys [options arguments]} (cli/parse-opts args admin-entity-opts)
        description (str/join " " arguments)]
    (when (or (:help options) (str/blank? description))
      (println "Usage: bb ai admin-entity <description>")
      (println "  Example: bb ai admin-entity \"products with name, price, status\"")
      (System/exit 0))
    (println (bold "\u2746 Wagoe AI Admin Entity Generator"))
    (println)
    (println (dim (str "Parsing: " description)))
    (println)
    (let [service (make-service-from-config)
          result  (svc/generate-admin-entity service description (:root options))]
      (if (:error result)
        (do (println (red (str "Error: " (:error result))))
            (when (:raw-text result)
              (println)
              (println (dim "Raw AI output:"))
              (println (:raw-text result)))
            (System/exit 1))
        (do
          (println (cyan "\u250c\u2500 Preview \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510"))
          (doseq [line (str/split-lines (:text result))]
            (println (str (cyan "\u2502") " " line)))
          (println (cyan "\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518"))
          (println)
          (let [entity-name (:entity-name result)]
            (if (or (:yes options) (confirm? "Write this entity config?"))
              (let [dev-path  (str (:root options) "/resources/conf/dev/admin/" entity-name ".edn")
                    test-path (str (:root options) "/resources/conf/test/admin/" entity-name ".edn")]
                (io/make-parents dev-path)
                (io/make-parents test-path)
                (spit dev-path (:text result))
                (spit test-path (:text result))
                (println)
                (println (green (str "\u2713 Written to " dev-path)))
                (println (green (str "\u2713 Written to " test-path)))
                (println)
                (println (dim "Next steps:"))
                (println (dim (str "  1. Add :" entity-name " to :entity-discovery :allowlist in config.edn")))
                (println (dim (str "  2. Add #include \"admin/" entity-name ".edn\" to :entities in config.edn")))
                (println (dim "  3. Review and customize the generated config")))
              (println (yellow "Cancelled. No files were written.")))))))))

;; =============================================================================
;; Subcommand: setup-parse
;; =============================================================================

(def setup-parse-opts
  [["-h" "--help"]])

(defn cmd-setup-parse [args]
  (let [{:keys [options arguments]} (cli/parse-opts args setup-parse-opts)
        description (str/join " " arguments)]
    (when (or (:help options) (str/blank? description))
      (println "Usage: bb ai setup-parse <description>")
      (System/exit 0))
    (let [service (make-service-from-config)
          result  (svc/parse-setup-description service description)]
      (if (:error result)
        (do (println (red (str "Error: " (:error result)))) (System/exit 1))
        ;; Output the JSON data to stdout for the Babashka setup wizard to consume
        (let [data   (:data result)
              output {"project-name" (get data "project-name" "my-app")
                      "database"     (get data "database" "postgresql")
                      "ai-provider"  (get data "ai-provider" "none")
                      "payment"      (get data "payment" "none")
                      "cache"        (get data "cache" "none")
                      "email"        (get data "email" "none")
                      "admin-ui"     (get data "admin-ui" true)}]
          (println (json/generate-string output)))))))

;; =============================================================================
;; Main
;; =============================================================================

(def help-text
  (str (bold "Wagoe AI \u2014 Framework-aware AI tooling") "\n"
       "\n"
       "Usage:\n"
       "  bb ai explain [--file path]                  Error explainer (also: stdin)\n"
       "  bb ai gen-tests <file>                       Test generator\n"
       "  bb ai sql <description>                      SQL copilot (HoneySQL)\n"
       "  bb ai docs --module <path> [--type t]        Documentation wizard\n"
       "  bb ai admin-entity <description>             Admin entity EDN generator\n"
       "  bb ai setup-parse <description>              Parse NL setup description\n"
       "\n"
       "Provider selection (via environment variables):\n"
       "  ANTHROPIC_API_KEY   \u2192 Anthropic (Claude)\n"
       "  OPENAI_API_KEY      \u2192 OpenAI (GPT)\n"
       "  OLLAMA_URL          \u2192 Ollama (local, default http://localhost:11434)\n"
       "  AI_MODEL            \u2192 Override default model\n"
       "\n"
       "For NL scaffolding:\n"
       "  bb scaffold ai <description> [--yes]"))

(defn -main [& raw-args]
  (let [[sub & rest-args] (vec raw-args)]
    (cond
      (or (nil? sub) (contains? #{"-h" "--help" "help"} sub))
      (println help-text)

      (= sub "scaffold-ai")
      (cmd-scaffold-ai rest-args)

      (= sub "explain")
      (cmd-explain rest-args)

      (= sub "gen-tests")
      (cmd-gen-tests rest-args)

      (= sub "sql")
      (cmd-sql rest-args)

      (= sub "docs")
      (cmd-docs rest-args)

      (= sub "admin-entity")
      (cmd-admin-entity rest-args)

      (= sub "setup-parse")
      (cmd-setup-parse rest-args)

      :else
      (do
        (println (red (str "Unknown subcommand: " sub)))
        (println)
        (println help-text)
        (System/exit 1)))))
