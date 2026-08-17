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
  (:require [wagoe.config :as config]
            [wagoe.ai.core.context :as ctx]
            [wagoe.ai.core.parsing :as parsing]
            [wagoe.ai.shell.module-wiring]
            [wagoe.ai.shell.providers.anthropic :as anthropic]
            [wagoe.ai.shell.providers.ollama :as ollama]
            [wagoe.ai.shell.providers.openai :as openai]
            [wagoe.ai.shell.providers.replicate :as replicate-provider]
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
(def ^:private scaffolder-version "1.0.0-beta-5")

;; =============================================================================
;; Service bootstrap
;; =============================================================================

(defn parse-or-exit!
  "Parse `args` against `opts`, or print the errors and exit 1.

   Every subcommand destructured `parse-opts` as `{:keys [options arguments]}`
   and never read `:errors`, so tools.cli collected unknown options and invalid
   values and they were discarded. A typo one keystroke from a real flag —
   `--fil` for `--file` — was dropped, its value became a positional argument
   nothing reads, and the command failed complaining about missing input
   (BOU-279). The message pointed away from the mistake, which was on the same
   line.

   Public so a test can drive it. Returns the parsed map; `-main` exits."
  [args opts usage]
  (let [{:keys [errors] :as parsed} (cli/parse-opts args opts)]
    (when (seq errors)
      (doseq [e errors] (println (red e)))
      (println)
      (println usage)
      (System/exit 1))
    parsed))

(defn explain-provider-error
  "Turn a provider's error result into something actionable.

   Takes the service alongside the result. `:configured?` is set where the
   service is built — the only place that knows whether the user chose a
   provider — and every other basis for that judgement has been wrong:

   - environment variables alone misreported a configured OPENAI_BASE_URL
     pointing at a local endpoint that was down
   - the `:provider` keyword misreported Ollama configured in
     resources/conf/<env>/config.edn, which is a supported path

   Only the env fallback with no variable set is unconfigured. Anything else —
   an env var, or `:wagoe/ai-service` in config — is a deliberate choice, and
   telling that user to configure a provider sends them to the wrong fix.

   `env` is a parameter so the arms are testable. Reading `System/getenv` inside
   made the assertions depend on whether the developer happened to have
   OLLAMA_URL exported — the ambient-environment fault this function explains.

   Returns the original message when it has nothing better to say: replacing an
   unrecognised one with a friendlier guess would hide it."
  ([result service]
   (explain-provider-error result service
                           {"OLLAMA_URL"      (System/getenv "OLLAMA_URL")
                            "OPENAI_BASE_URL" (System/getenv "OPENAI_BASE_URL")}))
  ([result service env]
   (let [msg      (str (:error result))
         provider (:provider result)
         status   (:status result)
         body     (str (:body result))
         ;; The endpoint that actually failed, reported by the provider that
         ;; ran. `(:provider service)` is the *primary*, and a configured
         ;; :fallback means the result may describe a different provider
         ;; entirely — service.clj retries on the fallback and returns its
         ;; result — so reading the URL from the service would name the wrong
         ;; service and send the user to debug it.
         ;;
         ;; The env vars remain as a last resort: the anthropic provider has no
         ;; base-url to report, its endpoint being fixed.
         url      (or (:base-url result)
                      (:base-url (:provider service))
                      (get env "OLLAMA_URL")
                      (get env "OPENAI_BASE_URL"))
         refused? (str/includes? msg "Connection refused")]
     (cond
       (and refused? (not (:configured? service)))
       (str "No AI provider is configured, and the default (Ollama on "
            "localhost:11434) is not running.\n"
            "  Set one of:\n"
            "    ANTHROPIC_API_KEY   Anthropic (Claude)\n"
            "    OPENAI_API_KEY      OpenAI\n"
            "    OPENAI_BASE_URL     an OpenAI-compatible endpoint\n"
            "    REPLICATE_API_TOKEN Replicate-hosted models\n"
            "    OLLAMA_URL          a running Ollama, if it is not on localhost\n"
            "  or :wagoe/ai-service in resources/conf/<env>/config.edn")

       (and refused? (= :ollama provider))
       (str "Cannot reach Ollama" (when url (str " at " url)) ". Is it running?")

       (and refused? (= :openai provider) url)
       (str "Cannot reach the OpenAI-compatible endpoint at " url
            ". Is it running?")

       refused?
       (str "Cannot reach the configured AI provider"
            (when provider (str " (" (name provider) ")")) ".")

       (or (= 401 status) (str/includes? msg "status 401"))
       (str "The AI provider"
            (when provider (str " (" (name provider) ")"))
            " rejected the API key.")

       ;; Both arrive as 429, and the advice is opposite. Quota exhaustion is
       ;; permanent until you add credits — telling someone to wait and retry
       ;; sends them to do nothing, indefinitely. The provider's body text is
       ;; carried in the message, so the two are distinguishable.
       ;; Both arrive as 429 and need opposite advice. Quota exhaustion lasts
       ;; until you add credit, so "wait and retry" sends the user to do nothing
       ;; indefinitely. The two are only distinguishable from the response body,
       ;; which the message does not carry.
       (and (= 429 status)
            (or (str/includes? body "insufficient_quota")
                (str/includes? body "credit_balance_exhausted")
                (str/includes? body "billing")))
       (str "The AI provider"
            (when provider (str " (" (name provider) ")"))
            " accepted the key, but the account has no credit left.")

       (or (= 429 status) (str/includes? msg "status 429"))
       "The AI provider is rate-limiting. Wait and retry."

       :else msg))))

(defn- make-service-from-env
  "Fall-back when no :wagoe/ai-service is present in active config.
   Checks ANTHROPIC_API_KEY, OPENAI_BASE_URL, OPENAI_API_KEY, OLLAMA_URL in that order.
   OPENAI_BASE_URL covers OpenAI-compatible endpoints (oMLX, LM Studio, etc.) that may
   not require a real API key."
  []
  (cond
    (System/getenv "ANTHROPIC_API_KEY")
    {:provider    (anthropic/create-anthropic-provider
                   {:api-key (System/getenv "ANTHROPIC_API_KEY")
                    :model   (or (System/getenv "AI_MODEL") "claude-haiku-4-5-20251001")})
     :configured? true}

    (System/getenv "OPENAI_BASE_URL")
    {:provider    (openai/create-openai-provider
                   {:base-url (System/getenv "OPENAI_BASE_URL")
                    :api-key  (or (System/getenv "OPENAI_API_KEY") "no-key")
                    :model    (or (System/getenv "AI_MODEL") "gpt-4o-mini")})
     :configured? true}

    (System/getenv "OPENAI_API_KEY")
    {:provider    (openai/create-openai-provider
                   {:api-key (System/getenv "OPENAI_API_KEY")
                    :model   (or (System/getenv "AI_MODEL") "gpt-4o-mini")})
     :configured? true}

    ;; Hosted models without a local GPU or an OpenAI account. REPLICATE_API_TOKEN
    ;; is the name Replicate's own tooling uses, so it is likely already set.
    (System/getenv "REPLICATE_API_TOKEN")
    {:provider    (replicate-provider/create-replicate-provider
                   {:api-key (System/getenv "REPLICATE_API_TOKEN")
                    :model   (or (System/getenv "AI_MODEL")
                                 replicate-provider/default-model)})
     :configured? true}

    :else
    ;; `:configured?` records whether the user chose anything, at the only point
    ;; that knows. Re-deriving it downstream got this wrong twice: first from
    ;; env vars, which misreported a configured OPENAI_BASE_URL, then from the
    ;; provider keyword, which misreported Ollama configured in config.edn
    ;; (BOU-280). OLLAMA_URL alone means deliberate; no variable at all means
    ;; this is the fallback nobody asked for.
    {:provider    (ollama/create-ollama-provider
                   {:base-url (or (System/getenv "OLLAMA_URL") "http://localhost:11434")
                    :model    (or (System/getenv "AI_MODEL") "qwen2.5-coder:7b")})
     :configured? (boolean (System/getenv "OLLAMA_URL"))}))

(defn- provider-env-vars-set?
  "Returns true when the developer has explicitly configured a provider via
   environment variables, indicating their intent to use a specific backend."
  []
  (or (System/getenv "ANTHROPIC_API_KEY")
      (System/getenv "OPENAI_BASE_URL")
      (System/getenv "OPENAI_API_KEY")
      (System/getenv "REPLICATE_API_TOKEN")))

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
    (let [config (try
                   (config/load-config)
                   (catch Exception e
                     ;; Config resources absent (external consumer without
                     ;; resources/conf/<env>/config.edn) — fall back to env vars.
                     ;; Pinned to the exact message so Aero env-var resolution
                     ;; errors ("Environment variable not found: X") are NOT
                     ;; swallowed — those mean a broken config, which should
                     ;; surface immediately.
                     (if (= (str (.getMessage e)) "Configuration file not found")
                       nil
                       (throw e))))
          ai-cfg (when config (get-in config [:active :wagoe/ai-service]))]
      (if (and ai-cfg (not= (:provider ai-cfg) :no-op))
        ;; Chosen in resources/conf/<env>/config.edn — a supported path, and as
        ;; deliberate as an environment variable.
        (assoc (ig/init-key :wagoe/ai-service ai-cfg) :configured? true)
        (make-service-from-env)))))

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
  (let [{:keys [options arguments]} (parse-or-exit! args scaffold-ai-opts "Usage: bb scaffold ai <description> [--yes] [--dry-run]")
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
        (do (println (red (explain-provider-error result service))) (System/exit 1))
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
  (let [{:keys [options]} (parse-or-exit! args explain-opts "Usage: bb ai explain [--file <path>]")
        stacktrace (if (:file options)
                     (slurp (:file options))
                     (slurp *in*))]
    (when (str/blank? stacktrace)
      (println (red "No stack trace provided. Pipe via stdin or use --file."))
      (System/exit 1))
    (let [service (make-service-from-config)
          result  (svc/explain-error service stacktrace (:root options))]
      (if (:error result)
        (do (println (red (explain-provider-error result service))) (System/exit 1))
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
  [["-o" "--output FILE" "Write to this file instead of stdout"]
   ["-w" "--write" "Write to the conventional test path for the source file"]
   ["-f" "--force" "With --write, overwrite an existing test file"]
   ["-h" "--help"]])

(defn cmd-gen-tests [args]
  (let [{:keys [options arguments]} (parse-or-exit! args gen-tests-opts "Usage: bb ai gen-tests <source-file> [-o <output> | --write]")
        source-path (first arguments)]
    (when (or (:help options) (nil? source-path))
      (println "Usage: bb ai gen-tests <source-file> [-o <output> | --write]")
      (System/exit 0))
    (println (bold "\u2746 Wagoe AI Test Generator"))
    (println (dim (str "Source: " source-path)))
    (println)
    ;; Both refusals are settled before the provider call: generation costs a
    ;; request and several seconds, and paying for it only to decline to write
    ;; the answer wastes both.
    ;;
    ;; --write derives the destination; -o names it outright, and an explicit
    ;; path wins.
    (let [dest (or (:output options)
                   (when (:write options) (ctx/derive-test-path source-path)))]
      (when (and (:write options) (not (:output options)) (nil? dest))
        (println (red (str "Cannot derive a test path for " source-path
                           " \u2014 it has no src/ path segment.")))
        (println (dim "Use -o <file> to name the destination."))
        (System/exit 1))
      ;; Overwriting a hand-written test namespace with generated output is not
      ;; recoverable from the CLI, so it takes an explicit --force whichever way
      ;; the destination was chosen.
      (when (and dest (.exists (io/file dest)) (not (:force options)))
        (println (red (str "Test file already exists: " dest)))
        (println (dim "Re-run with --force to overwrite, or choose another path with -o."))
        (System/exit 1))

      (let [service (make-service-from-config)
            result  (svc/generate-tests service source-path)]
        (if (:error result)
          (do (println (red (explain-provider-error result service))) (System/exit 1))
          (if dest
            (do (io/make-parents dest)
                (spit dest (:text result))
                (println (green (str "\u2713 Tests written to " dest)))
                (println (dim (str "Metadata: ^:" (name (:test-type result))
                                   " \u2014 run with: clojure -M:test --focus-meta :"
                                   (name (:test-type result))))))
            (println (:text result))))))))

;; =============================================================================
;; Subcommand: sql
;; =============================================================================

(def sql-opts
  [["-r" "--root ROOT" "Project root" :default "."]
   ["-h" "--help"]])

(defn cmd-sql [args]
  (let [{:keys [options arguments]} (parse-or-exit! args sql-opts "Usage: bb ai sql <description>")
        description (str/join " " arguments)]
    (when (or (:help options) (str/blank? description))
      (println "Usage: bb ai sql <description>")
      (System/exit 0))
    (let [service (make-service-from-config)
          result  (svc/sql-from-description service description (:root options))]
      (if (:error result)
        (do (println (red (explain-provider-error result service))) (System/exit 1))
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
  (let [{:keys [options]} (parse-or-exit! args docs-opts "Usage: bb ai docs --module <path> [--type agents|openapi|readme]")]
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
            (println (red (explain-provider-error result service)))
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
  (let [{:keys [options arguments]} (parse-or-exit! args admin-entity-opts "Usage: bb ai admin-entity <description>")
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
        (do (println (red (explain-provider-error result service)))
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
  (let [{:keys [options arguments]} (parse-or-exit! args setup-parse-opts "Usage: bb ai setup-parse <description>")
        description (str/join " " arguments)]
    (when (or (:help options) (str/blank? description))
      (println "Usage: bb ai setup-parse <description>")
      (System/exit 0))
    (let [service (make-service-from-config)
          result  (svc/parse-setup-description service description)]
      (if (:error result)
        (do (println (red (explain-provider-error result service))) (System/exit 1))
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
       "  bb ai gen-tests <file> [--write]             Test generator\n"
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
