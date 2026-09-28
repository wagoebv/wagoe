(ns wagoe.ai.shell.cli-entry
  "Clojure CLI entrypoint for AI features.

   Called by the Babashka scripts/ai.clj script:
     clojure -M -m wagoe.ai.shell.cli-entry <subcommand> [args]

   Subcommands:
     scaffold-parse <description>              -- NL module spec, as JSON
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
            [wagoe.ai.shell.test-check :as test-check]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
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

(def provider-env
  "The variables `make-service-from-env` tries, in order. The help and the
   no-provider message list these, and `bb ai`'s help is pinned to them by a
   test in wagoe-tools. AI_MODEL overrides the model of each."
  [{:var "ANTHROPIC_API_KEY"   :provider :anthropic :label "Anthropic (Claude)"}
   {:var "OPENAI_BASE_URL"     :provider :openai    :label "an OpenAI-compatible endpoint (oMLX, LM Studio)"}
   {:var "OPENAI_API_KEY"      :provider :openai    :label "OpenAI (GPT)"}
   {:var "REPLICATE_API_TOKEN" :provider :replicate :label "Replicate-hosted models"}
   {:var "OLLAMA_URL"          :provider :ollama    :label "Ollama (default http://localhost:11434)"}])

(defn- env-lines
  "A line per variable in `rows`: indent, name, `sep`, label."
  [rows indent sep]
  (apply str (for [{:keys [var label]} rows]
               (str indent (format "%-20s" var) sep label "\n"))))

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
            (env-lines provider-env "    " "")
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

(defn describe-failure
  "`explain-provider-error`, then which provider failed, with what HTTP status,
   and in the provider's own words. The advice alone left a 429 unreadable, and
   an empty message printed nothing at all (BOU-567)."
  [result service]
  (let [advice (str/trim (str (explain-provider-error result service)))
        raw    (str/trim (str (:error result)))
        body   (str/trim (str (:body result)))
        where  (str/join ", " (remove str/blank? [(some-> (:provider result) name)
                                                  (:model result)
                                                  (some->> (:status result) (str "HTTP "))]))
        words  (remove str/blank? [(when (not= raw advice) raw)
                                   ;; clj-http's own message carries no detail.
                                   (when (or (str/blank? raw) (re-matches #"clj-http: status \d+" raw))
                                     body)])]
    (str (if (str/blank? advice) "The AI request failed with no message." advice)
         (when (seq where) (str "\n  [" where "]"))
         (apply str (map #(str "\n  " %) words)))))

(defn- make-service-from-env
  "Fall-back when no :wagoe/ai-service is present in active config: the first
   variable of `provider-env` that `env` sets picks the provider.
   OPENAI_BASE_URL covers OpenAI-compatible endpoints (oMLX, LM Studio, etc.)
   that may not require a real API key."
  ([] (make-service-from-env (System/getenv)))
  ([env]
   (let [model  #(or (get env "AI_MODEL") %)
         chosen (some #(when (get env (:var %)) (:var %)) provider-env)]
     (case chosen
       "ANTHROPIC_API_KEY"
       {:provider    (anthropic/create-anthropic-provider
                      {:api-key (get env "ANTHROPIC_API_KEY")
                       :model   (model "claude-haiku-4-5-20251001")})
        :configured? true}

       "OPENAI_BASE_URL"
       {:provider    (openai/create-openai-provider
                      {:base-url (get env "OPENAI_BASE_URL")
                       :api-key  (or (get env "OPENAI_API_KEY") "no-key")
                       :model    (model "gpt-4o-mini")})
        :configured? true}

       "OPENAI_API_KEY"
       {:provider    (openai/create-openai-provider
                      {:api-key (get env "OPENAI_API_KEY")
                       :model   (model "gpt-4o-mini")})
        :configured? true}

       ;; Hosted models without a local GPU or an OpenAI account. REPLICATE_API_TOKEN
       ;; is the name Replicate's own tooling uses, so it is likely already set.
       "REPLICATE_API_TOKEN"
       {:provider    (replicate-provider/create-replicate-provider
                      {:api-key (get env "REPLICATE_API_TOKEN")
                       :model   (model replicate-provider/default-model)})
        :configured? true}

       ;; OLLAMA_URL, or nothing. `:configured?` records whether the user chose
       ;; anything, at the only point that knows. Re-deriving it downstream got
       ;; this wrong twice: first from env vars, which misreported a configured
       ;; OPENAI_BASE_URL, then from the provider keyword, which misreported
       ;; Ollama configured in config.edn (BOU-280). OLLAMA_URL alone means
       ;; deliberate; no variable at all means this is the fallback nobody
       ;; asked for.
       {:provider    (ollama/create-ollama-provider
                      {:base-url (or (get env "OLLAMA_URL") "http://localhost:11434")
                       :model    (model "qwen2.5-coder:7b")})
        :configured? (boolean chosen)}))))

(defn- config-provider
  "The :wagoe/ai-service entry from resources/conf/{env}/config.edn, when it
   names a provider to use. nil when there is no config, no such key, or the
   key selects :no-op — each of which means the env chain decides."
  []
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
    (when (and ai-cfg (not= (:provider ai-cfg) :no-op))
      ai-cfg)))

(defn- make-service-from-config
  "Build an AI service from the Aero config file (resources/conf/{env}/config.edn).

   Priority:
     1. :wagoe/ai-service from config, when it names a provider other than :no-op.
     2. Provider env vars (ANTHROPIC_API_KEY, OPENAI_BASE_URL, OPENAI_API_KEY,
        REPLICATE_API_TOKEN), in that order.
     3. The bare Ollama fallback.

   The env chain used to come first, on the reasoning that an exported key is
   developer intent. It outranked a provider written into config.edn by name,
   which is at least as deliberate and much harder to lose track of: a stale
   ANTHROPIC_API_KEY in a shell profile silently beat a config entry someone had
   just edited, and nothing said the config had been ignored. Naming a provider
   in config.edn now decides.

   Generated projects ship no :wagoe/ai-service key, so exporting a key is still
   all they need — that path is unchanged.

   Errors from a present but broken config still surface immediately."
  []
  (if-let [ai-cfg (config-provider)]
    ;; Chosen in resources/conf/<env>/config.edn — a supported path, and as
    ;; deliberate as an environment variable.
    (assoc (ig/init-key :wagoe/ai-service ai-cfg) :configured? true)
    (make-service-from-env)))

;; =============================================================================
;; Subcommand: scaffold-parse
;; =============================================================================

(def scaffold-parse-opts
  [["-r" "--root ROOT" "Project root" :default "."]
   ["-h" "--help"]])

(def ^:dynamic *exit!*
  "How a refusal ends the process; a test binds it to record the code."
  (fn [code] (System/exit code)))

(defn tty?
  "Whether a person can answer a prompt. A console is not enough: newer JDKs
   return one with stdin redirected. isTerminal is asked reflectively because
   the baseline JDK lacks it."
  []
  (if-let [c (System/console)]
    (try (boolean (clojure.lang.Reflector/invokeInstanceMethod c "isTerminal" (object-array 0)))
         (catch Exception _ true))
    false))

(defn- confirm?
  "Prompt for yes/no confirmation. Enter defaults to yes; a closed stdin is
   a no."
  [label]
  (print (str label " [Y/n]: "))
  (flush)
  (when-let [line (read-line)]
    (let [input (-> line str/trim str/lower-case)]
      (or (empty? input) (= input "y") (= input "yes")))))

(defn cmd-scaffold-parse
  "Turn a natural-language description into a module spec on stdout, as JSON.

   Parsing only: the preview, the confirmation and the scaffolder run belong to
   `bb scaffold ai`, which already injects wagoe-scaffolder, its rewrite-clj
   dependency, `--base-ns` and the WAGOE_SCAFFOLDER_ROOT override. Generating
   from here reproduced none of that, so the module either failed to build or
   landed under `wagoe.*` (BOU-401). Same split as `setup-parse`.

   Stdout carries the JSON and nothing else \u2014 diagnostics go to stderr."
  [args]
  (let [{:keys [options arguments]} (parse-or-exit! args scaffold-parse-opts "Usage: bb scaffold ai <description>")
        description (str/join " " arguments)]
    (when (or (:help options) (str/blank? description))
      (println "Usage: bb scaffold ai <description>")
      (println "  Example: bb scaffold ai \"product module with name, price, stock\"")
      (System/exit 0))
    (let [service (make-service-from-config)
          result  (svc/scaffold-from-description service description (:root options))]
      (if (:error result)
        (do (binding [*out* *err*]
              (println (red (describe-failure result service))))
            (System/exit 1))
        (println (json/generate-string
                  (select-keys result [:module-name :entity :fields :entities :http :web :public-api])))))))

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
        (do (println (red (describe-failure result service))) (System/exit 1))
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
   ["-f" "--force" "Overwrite an existing test file; write one that fails its checks"]
   ["-h" "--help"]])

(defn gen-tests-outcome
  "What to do with a generated test namespace: :refuse one that failed its
   checks unless `force?`, else :write it to `dest` or :print it."
  [{:keys [check-errors]} dest force?]
  (cond
    (and (seq check-errors) (not force?)) :refuse
    dest                                  :write
    :else                                 :print))

(defn- print-check-errors [errors]
  (doseq [e errors]
    (println (str "  " e))))

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

      ;; Linted as the file it will be, so kondo's config and namespace
      ;; checks apply as they will in `bb check`.
      (let [lint-as (or dest (ctx/derive-test-path source-path) "test/generated_test.clj")
            check   (fn [text]
                      (test-check/check-errors
                       text {:root          "."
                             :filename      lint-as
                             :context-files (keep identity
                                                  (cons source-path
                                                        (map test-check/source-file
                                                             (test-check/required-namespaces text))))}))
            service (make-service-from-config)
            result  (svc/generate-tests service source-path {:check check})]
        (when (:error result)
          (println (red (describe-failure result service)))
          (System/exit 1))
        (case (gen-tests-outcome result dest (:force options))
          :refuse
          (do (println (red "The generated namespace fails its checks, after one retry:"))
              (print-check-errors (:check-errors result))
              (println (dim "Nothing written. Re-run with --force to write it anyway."))
              (System/exit 1))

          :write
          (do (io/make-parents dest)
              (spit dest (:text result))
              (when (seq (:check-errors result))
                (println (yellow "Written with --force, although it fails these checks:"))
                (print-check-errors (:check-errors result)))
              (println (green (str "\u2713 Tests written to " dest)))
              (println (dim (str "Tags: " (str/join " " (map #(str "^" %) (sort (:test-tags result))))))))

          :print
          (println (:text result)))))))

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
        (do (println (red (describe-failure result service))) (System/exit 1))
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
            (println (red (describe-failure result service)))
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
   ["-f" "--force" "Overwrite admin files that already exist, without confirmation"]
   ["-h" "--help"]])

(defn profiles
  "The profiles under resources/conf with a config.edn, or dev and test when
   there are none yet. Hardcoding dev and test left prod without the file."
  [root]
  (->> (.listFiles (io/file root "resources" "conf"))
       (filter #(.isFile (io/file % "config.edn")))
       (map #(.getName %))
       sort
       seq
       (#(vec (or % ["dev" "test"])))))

(defn admin-entity-targets
  "One {:path :text :exists?} per profile and entity."
  [root entities]
  (vec (for [profile (profiles root)
             {:keys [entity-name text]} entities
             :let [path (str root "/resources/conf/" profile "/admin/" entity-name ".edn")]]
         {:path path :text text :exists? (.exists (io/file path))})))

(defn- admin-config
  "The :wagoe/admin map of `profile`'s config, Aero tags kept as tagged
   literals, or nil when it has none or does not read."
  [root profile]
  (try
    (get-in (edn/read-string {:default tagged-literal}
                             (slurp (io/file root "resources" "conf" profile "config.edn")))
            [:active :wagoe/admin])
    (catch Exception _ nil)))

(defn- includes? [entities file]
  (some #(and (instance? clojure.lang.TaggedLiteral %)
              (= 'include (:tag %))
              (= file (:form %)))
        (tree-seq #(or (coll? %) (instance? clojure.lang.TaggedLiteral %))
                  #(if (coll? %) (seq %) [(:form %)])
                  entities)))

(defn admin-entity-next-steps
  "What each profile's config still needs for `entity-names` to show in the
   admin, as lines. A profile that cannot be read gets both steps."
  [root entity-names]
  (vec (for [profile (profiles root)
             :let    [admin (admin-config root profile)
                      allowlist (set (get-in admin [:entity-discovery :allowlist]))]
             entity  entity-names
             :let    [file (str "admin/" entity ".edn")]
             line    [(when-not (contains? allowlist (keyword entity))
                        (str profile ": add :" entity " to :entity-discovery :allowlist"))
                      (when-not (includes? (:entities admin) file)
                        (str profile ": add #include \"" file "\" to :entities"))]
             :when   line]
         line)))

(defn write-admin-entities!
  "Write `targets`, skipping a file that exists unless `force?`: it may be a
   hand-edited prod config. Returns the targets with :written?."
  [targets force?]
  (mapv (fn [{:keys [path text exists?] :as t}]
          (if (and exists? (not force?))
            (assoc t :written? false)
            (do (io/make-parents path)
                (spit path text)
                (assoc t :written? true))))
        targets))

(defn- type-source-line [{:keys [entity-name type-source corrections]}]
  (str "Types for " entity-name ": "
       (if (= :migrations (:source type-source))
         (str "from the " (:table type-source) " table in the migrations")
         "no table in the migrations, so inferred from field names")
       (when (seq corrections)
         (str " (corrected " (str/join ", " (map (fn [{:keys [field from to]}]
                                                   (str field " " from "→" to))
                                                 corrections))
              ")"))))

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
        (do (println (red (describe-failure result service)))
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
          (doseq [e (:entities result)]
            (println (dim (type-source-line e))))
          (println)
          (let [entities (:entities result)
                force?   (:force options)
                targets  (admin-entity-targets (:root options) entities)
                writes?  (some #(or force? (not (:exists? %))) targets)]
            (println "Files:")
            (doseq [{:keys [path exists?]} targets]
              (println (str "  " path
                            (cond (not exists?) ""
                                  force?        (yellow "  (exists, will be overwritten: --force)")
                                  :else         (dim "  (exists, kept; --force overwrites)")))))
            (println)
            (cond
              (not writes?)
              (println (yellow "Nothing written: every file already exists. Re-run with --force to overwrite."))

              ;; Asking with nobody to answer took the default (BOU-585).
              (not (or (:yes options) force? (tty?)))
              (do (println (red "No terminal to confirm on. Nothing written; pass --yes to write."))
                  (*exit!* 1))

              (or (:yes options) force? (confirm? "Write these files?"))
              (do
                (println)
                (doseq [{:keys [path written?]} (write-admin-entities! targets force?)
                        :when written?]
                  (println (green (str "\u2713 Written to " path))))
                (println)
                (println (dim "Next steps, in each profile's config.edn:"))
                (doseq [line (admin-entity-next-steps (:root options) (map :entity-name entities))]
                  (println (dim (str "  - " line))))
                (println (dim "  - Review and customize the generated config")))

              :else
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
        (do (binding [*out* *err*]
              (println (red (describe-failure result service))))
            (System/exit 1))
        ;; Output the JSON data to stdout for the Babashka setup wizard to consume
        (println (json/generate-string (parsing/normalise-setup-spec (:data result))))))))

;; =============================================================================
;; Main
;; =============================================================================

(def help-text
  (str (bold "Wagoe AI \u2014 Framework-aware AI tooling") "\n"
       "\n"
       "Usage:\n"
       "  bb ai explain [--file path]                  Error explainer, also stdin (experimental)\n"
       "  bb ai gen-tests <file> [--write]             Test generator (experimental)\n"
       "  bb ai sql <description>                      SQL copilot, HoneySQL (experimental)\n"
       "  bb ai docs --module <path> [--type t]        Documentation wizard\n"
       "  bb ai admin-entity <description>             Admin entity EDN generator\n"
       "  bb ai setup-parse <description>              Parse NL setup description\n"
       "  bb ai scaffold-parse <description>           Parse NL module description\n"
       "\n"
       "Experimental: the answer can be confidently wrong. Review it before you use it.\n"
       "\n"
       "Provider, when config.edn names none (the first variable set wins):\n"
       (env-lines (conj provider-env {:var "AI_MODEL" :label "Override the default model"})
                  "  " "\u2192 ")
       "\n"
       "For NL scaffolding:\n"
       "  bb scaffold ai <description> [--yes]"))

(defn -main [& raw-args]
  (let [[sub & rest-args] (vec raw-args)]
    (cond
      (or (nil? sub) (contains? #{"-h" "--help" "help"} sub))
      (println help-text)

      (= sub "scaffold-parse")
      (cmd-scaffold-parse rest-args)

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
