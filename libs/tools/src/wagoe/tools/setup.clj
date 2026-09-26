#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/setup.clj
;;
;; Config Setup Wizard — interactive config generation for Wagoe projects.
;;
;; Usage (via bb.edn task):
;;   bb setup                                              # Interactive wizard
;;   bb setup ai "PostgreSQL with Stripe payments"         # NL mode
;;   bb setup --database postgresql --payment stripe       # Non-interactive flags

(ns wagoe.tools.setup
  (:require [wagoe.tools.ai :as ai]
            [wagoe.tools.ansi :refer [bold green red cyan yellow dim]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :refer [shell]]))

;; =============================================================================
;; Process exit
;; =============================================================================

(def ^:dynamic *exit!*
  "Terminates the process with `code`. Indirection so tests can observe the exit
   code of a command instead of killing the test JVM."
  (fn [code] (System/exit code)))

;; =============================================================================
;; Choices
;; =============================================================================

(def valid-choices
  "The values each enum flag accepts, and the order the help text lists them in.

   Every template below is a `case` with no default, so a value that is not
   validated here dies on `No matching clause` without naming the flag (BOU-411)."
  {:database    [:postgresql :sqlite :h2 :mysql]
   :ai-provider [:none :ollama :anthropic :openai :replicate]
   :payment     [:none :mock :stripe :mollie]
   ;; :in-memory is the pre-1.0 spelling of :memory, still accepted here so a
   ;; script that passes it keeps working; the template writes :memory either
   ;; way (BOU-436).
   :cache       [:none :redis :memory :in-memory]
   :email       [:none :smtp]})

(defn spec-errors
  "Messages for every enum value in `spec` that no template can render.

   Empty when the spec is renderable. Keyed by the flag the user typed, not by
   the internal key, so the message is actionable from the command line."
  [spec]
  (for [[k allowed] valid-choices
        :let  [v (get spec k)]
        :when (and (some? v) (not (some #{v} allowed)))]
    (str "Unknown value for --" (name k) ": " (name v)
         "\n  Valid: " (str/join ", " (map name allowed)))))

;; =============================================================================
;; Input helpers (follows scaffold.clj pattern)
;; =============================================================================

(defn- prompt
  ([label] (prompt label nil))
  ([label default]
   (if default
     (print (str (cyan "? ") (bold label) " [" default "]: "))
     (print (str (cyan "? ") (bold label) ": ")))
   (flush)
   (let [input (str/trim (or (read-line) ""))]
     (if (and (empty? input) default)
       default
       input))))

(defn- confirm
  ([label] (confirm label true))
  ([label default-yes?]
   (let [hint (if default-yes? "Y/n" "y/N")]
     (print (str (cyan "? ") (bold label) " [" hint "]: "))
     (flush)
     (let [input (str/trim (str/lower-case (or (read-line) "")))]
       (if (empty? input)
         default-yes?
         (= input "y"))))))

(defn- select-option
  "Display numbered options and return the chosen keyword."
  [label options]
  (println (str (cyan "? ") (bold label)))
  (doseq [[i [k description]] (map-indexed vector options)]
    (println (str "    " (inc i) ") " (name k) "  " (dim description))))
  (print "  Choice [1]: ")
  (flush)
  (let [input  (str/trim (or (read-line) ""))
        choice (when (seq input) (try (Integer/parseInt input) (catch Exception _ nil)))]
    (cond
      (empty? input)                                     (first (first options))
      (and choice (>= choice 1) (<= choice (count options))) (first (nth options (dec choice)))
      :else (do (println (red (str "  Invalid choice, enter 1–" (count options))))
                (select-option label options)))))

;; =============================================================================
;; Template fragments — each returns an EDN string for the given env
;; =============================================================================

(defn- prod? [env] (= env "prod"))

(defn- settings-template [project-name env]
  (str "  :wagoe/settings\n"
       "  {:name              \"" project-name "-" env "\"\n"
       "   :version           \"0.1.0\"\n"
       "   :date-format       \"yyyy-MM-dd\"\n"
       "   :date-time-format  \"yyyy-MM-dd HH:mm:ss\"\n"
       "   :time-zone         \"Europe/Amsterdam\"\n"
       "   :currency/iso-code \"EUR\"\n"
       ;; Defaults to true when unset, and both configs this writes are served
       ;; over plain HTTP — so omitting it sent the session cookie with Secure
       ;; and nobody could stay logged in locally (BOU-447). `wagoe new` writes
       ;; it for the same reason (dev-config.edn.tmpl).
       (if (prod? env)
         (str "   ;; HTTPS-only auth cookies. Prod is served behind TLS.\n"
              "   :secure-cookies?   true\n")
         (str "   ;; Auth cookies omit Secure for local HTTP; set true behind TLS.\n"
              "   :secure-cookies?   false\n"))
       "   :features          {:user-web-ui {:enabled? true}}}\n"))

(defn- postgresql-template [env]
  (cond
    (= env "test") ""  ; test uses H2
    ;; No defaults: a prod database named by a fallback is one nobody chose,
    ;; and a bare #env makes `bb doctor --ci` name what is unset.
    (prod? env)
    (str "  :wagoe/postgresql\n"
         "  {:host        #env POSTGRES_HOST\n"
         "   :port        #or [#long #or [#env POSTGRES_PORT 5432] 5432]\n"
         "   :dbname      #env POSTGRES_DB\n"
         "   :user        #env POSTGRES_USER\n"
         "   :password    #env POSTGRES_PASSWORD\n"
         "   :auto-commit true\n"
         "   :pool        {:minimum-idle          2\n"
         "                 :maximum-pool-size     10\n"
         "                 :connection-timeout-ms 30000}}\n")
    :else
    (str "  :wagoe/postgresql\n"
         "  {:host        #or [#env POSTGRES_HOST \"localhost\"]\n"
         "   :port        #or [#long #or [#env POSTGRES_PORT 5432] 5432]\n"
         "   :dbname      #or [#env POSTGRES_DB \"" "wagoe_" env "\"]\n"
         "   :user        #or [#env POSTGRES_USER \"postgres\"]\n"
         "   :password    #or [#env POSTGRES_PASSWORD \"postgres\"]\n"
         "   :auto-commit true\n"
         "   :pool        {:minimum-idle          2\n"
         "                 :maximum-pool-size     10\n"
         "                 :connection-timeout-ms 30000}}\n")))

(defn- sqlite-template [env]
  (if (= env "test")
    ""
    (str "  :wagoe/sqlite\n"
         ;; In prod the file must sit on a volume the operator picks, not in
         ;; the working directory of an image.
         (if (prod? env)
           "  {:db   #env SQLITE_PATH\n"
           (str "  {:db   \"" env "-database.db\"\n"))
         "   :pool {:minimum-idle          1\n"
         "          :maximum-pool-size     3\n"
         "          :connection-timeout-ms 10000}}\n")))

(defn- mysql-template [env]
  (cond
    (= env "test") ""
    (prod? env)
    (str "  :wagoe/mysql\n"
         "  {:host     #env MYSQL_HOST\n"
         "   :port     #or [#long #or [#env MYSQL_PORT 3306] 3306]\n"
         "   :dbname   #env MYSQL_DB\n"
         "   :user     #env MYSQL_USER\n"
         "   :password #env MYSQL_PASSWORD\n"
         "   :pool     {:minimum-idle          2\n"
         "              :maximum-pool-size     10\n"
         "              :connection-timeout-ms 30000}}\n")
    :else
    (str "  :wagoe/mysql\n"
         "  {:host     #or [#env MYSQL_HOST \"localhost\"]\n"
         "   :port     #or [#long #or [#env MYSQL_PORT 3306] 3306]\n"
         "   :dbname   #or [#env MYSQL_DB \"wagoe_" env "\"]\n"
         "   :user     #or [#env MYSQL_USER \"root\"]\n"
         "   :password #or [#env MYSQL_PASSWORD \"\"]\n"
         "   :pool     {:minimum-idle          2\n"
         "              :maximum-pool-size     10\n"
         "              :connection-timeout-ms 30000}}\n")))

(defn- h2-template
  "H2 config. In-memory for test, file-backed everywhere else.

   An in-memory H2 database is private to the JVM that opened it, and the
   first-run funnel spans three of them: `bb migrate up`, `bb create-admin` and
   the app each shell out separately. With :memory true in dev, migrations
   applied to a database that died with the migrate process, the admin user was
   written to another, and the app booted on a third — empty and unmigrated,
   with every step exiting 0 (BOU-265).

   The test profile runs in a single JVM, so in-memory is both correct and
   faster there.

   The path must start with ./ — H2 2.x rejects an implicitly-relative path
   (\"[90011-240]\"), and the adapter builds the URL as (str \"jdbc:h2:\" path)
   so a bare name would fail at connection time, not at config time."
  [env]
  (if (= env "test")
    (str "  :wagoe/h2\n"
         "  {:memory true\n"
         "   :pool   {:minimum-idle 1\n"
         "            :maximum-pool-size 5\n"
         "            :connection-timeout-ms 5000}}\n")
    (str "  :wagoe/h2\n"
         (if (prod? env)
           "  {:db   #env H2_PATH\n"
           (str "  {:db   \"./" env "-h2-database\"\n"))
         "   :pool {:minimum-idle      1\n"
         "          :maximum-pool-size 10}}\n")))

(defn- http-template [_env]
  (str "  :wagoe/http\n"
       "  {:port       #or [#env HTTP_PORT 3000]\n"
       "   :host       #or [#env HTTP_HOST \"0.0.0.0\"]\n"
       "   :join?      false\n"
       "   :port-range {:start 3000 :end 3099}}\n"))

(defn- router-template [_env]
  (str "  :wagoe/router\n"
       "  {:coercion   :malli\n"
       "   :middleware []}\n"))

(defn- logging-template [env]
  (if (= env "test")
    (str "  :wagoe/logging\n"
         "  {:level     :debug\n"
         "   :console   true\n"
         "   :appenders [{:appender       :rolling-file\n"
         "                :file           \"logs/wagoe-test.log\"\n"
         "                :rolling-policy {:type :time-based :max-history 3}}]}\n")
    (str "  :wagoe/logging\n"
         "  {:provider     :slf4j\n"
         "   :level        " (if (prod? env) ":info" ":debug") "\n"
         "   :logger-name  \"wagoe\"\n"
         "   :default-tags {:service     \"wagoe-" env "\"\n"
         "                  :environment \"" (if (= env "prod") "production" "development") "\"}}\n")))

(defn- observability-template [_env]
  (str "  :wagoe/metrics\n"
       "  {:provider :no-op}\n"
       "\n"
       "  :wagoe/error-reporting\n"
       "  {:provider :no-op}\n"))

(def ^:private default-ollama-model
  "Written into the config and named in the wizard's closing steps, so the
   `ollama pull` we tell the user to run fetches the model we configured."
  "qwen2.5-coder:7b")

(def ai-provider-prerequisites
  "What each provider needs before its first call answers, shown after setup
   writes the config. The config is valid without any of this — BOU-414 made
   the project boot — so this is the only place the user hears about it."
  {:ollama    ["Install Ollama: https://ollama.com/download"
               (str "Pull the configured model:  ollama pull " default-ollama-model)
               "Keep Ollama running — the service calls localhost:11434"]
   :anthropic ["Create an API key: https://console.anthropic.com/settings/keys"
               "Export it:  export ANTHROPIC_API_KEY=<key>   (or add it to .env, then: set -a; source .env; set +a)"]
   :openai    ["Create an API key: https://platform.openai.com/api-keys"
               "Export it:  export OPENAI_API_KEY=<key>   (or add it to .env, then: set -a; source .env; set +a)"]
   :replicate ["Create an API token: https://replicate.com/account/api-tokens"
               "Export it:  export REPLICATE_API_TOKEN=<token>   (or add it to .env, then: set -a; source .env; set +a)"]})

(defn- ai-template [provider env]
  ;; The AI service backs scaffolding and the error explainer — build-time
  ;; tools, not something a production app calls.
  (case (if (prod? env) :none provider)
    :none ""
    :ollama
    (if (= env "test")
      (str "  :wagoe/ai-service\n"
           "  {:provider :no-op}\n")
      (str "  :wagoe/ai-service\n"
           "  {:provider :ollama\n"
           "   :model    #or [#env AI_MODEL \"" default-ollama-model "\"]\n"
           "   :base-url #or [#env OLLAMA_URL \"http://localhost:11434\"]}\n"))
    :anthropic
    (if (= env "test")
      (str "  :wagoe/ai-service\n"
           "  {:provider :no-op}\n")
      (str "  :wagoe/ai-service\n"
           "  {:provider :anthropic\n"
           "   :model    #or [#env AI_MODEL \"claude-haiku-4-5-20251001\"]\n"
           "   :api-key  #env ANTHROPIC_API_KEY}\n"))
    :openai
    (if (= env "test")
      (str "  :wagoe/ai-service\n"
           "  {:provider :no-op}\n")
      (str "  :wagoe/ai-service\n"
           "  {:provider :openai\n"
           "   :model    #or [#env AI_MODEL \"gpt-4o-mini\"]\n"
           "   :api-key  #env OPENAI_API_KEY}\n"))
    ;; Bare #env, as above: `doctor --ci` then names the variable to export
    ;; rather than passing with an empty key that fails at the provider.
    :replicate
    (if (= env "test")
      (str "  :wagoe/ai-service\n"
           "  {:provider :no-op}\n")
      (str "  :wagoe/ai-service\n"
           "  {:provider :replicate\n"
           "   :model    #or [#env AI_MODEL \"anthropic/claude-4.5-haiku\"]\n"
           "   :api-key  #env REPLICATE_API_TOKEN}\n"))))

(defn- payment-template [provider env]
  (case provider
    :none ""
    :mock
    (str "  :wagoe/payment-provider\n"
         "  {:provider :mock}\n")
    :stripe
    (if (= env "test")
      (str "  :wagoe/payment-provider\n"
           "  {:provider :mock}\n")
      (str "  :wagoe/payment-provider\n"
           "  {:provider :stripe\n"
           "   :api-key  #env STRIPE_SECRET_KEY\n"
           "   :webhook-secret #env STRIPE_WEBHOOK_SECRET}\n"))
    :mollie
    (if (= env "test")
      (str "  :wagoe/payment-provider\n"
           "  {:provider :mock}\n")
      (str "  :wagoe/payment-provider\n"
           "  {:provider :mollie\n"
           "   :api-key  #env MOLLIE_API_KEY}\n"))))

(def ^:private in-process-cache
  ;; :memory, not :in-memory: the generator is the canonical way to write a
  ;; config, so emitting the deprecated spelling would greet a new project with
  ;; its own deprecation warning (BOU-436).
  (str "  :wagoe/cache\n"
       "  {:provider    :memory\n"
       "   :default-ttl 300}\n"))

(defn- cache-template [provider env]
  (case provider
    :none ""
    (:memory :in-memory) in-process-cache
    :redis
    (if (= env "test")
      in-process-cache
      (str "  :wagoe/cache\n"
           "  {:provider    :redis\n"
           "   :host        #or [#env REDIS_HOST \"localhost\"]\n"
           "   :port        #or [#long #or [#env REDIS_PORT 6379] 6379]\n"
           "   :password    #env REDIS_PASSWORD\n"
           "   :database    0\n"
           "   :timeout     2000\n"
           "   :default-ttl 300\n"
           "   :max-total   10\n"
           "   :max-idle    5\n"
           "   :min-idle    1}\n"))))

(defn- email-template [provider env]
  (case provider
    :none ""
    :smtp
    (cond
      (= env "test") ""
      (prod? env)
      (str "  :wagoe.external/smtp\n"
           "  {:host     #env SMTP_HOST\n"
           "   :port     #or [#long #or [#env SMTP_PORT 587] 587]\n"
           "   :username #env SMTP_USERNAME\n"
           "   :password #env SMTP_PASSWORD\n"
           "   :tls?     true\n"
           "   :from     #env SMTP_FROM}\n")
      :else
      (str "  :wagoe.external/smtp\n"
           "  {:host #or [#env SMTP_HOST \"localhost\"]\n"
           "   :port #or [#long #or [#env SMTP_PORT 1025] 1025]\n"
           "   :tls? false\n"
           "   :from #or [#env SMTP_FROM \"no-reply@localhost\"]}\n"))))

(def ^:private admin-users-entity
  "The `:users` entity config the admin `#include` points at.

   Shipped as a resource rather than composed here: it describes the
   framework's own auth_users/users split, which a project does not choose.
   Writing the `#include` without the file it names left every generated
   config unreadable — Aero threw on the missing resource at boot (BOU-447)."
  (delay (some-> (io/resource "wagoe/tools/admin/users.edn") slurp)))

(defn- admin-template [enabled? _env]
  (if-not enabled?
    ""
    (str "  :wagoe/admin\n"
         "  {:enabled?         true\n"
         "   :base-path        \"/web/admin\"\n"
         "   :require-role     :admin\n"
         "   :entity-discovery {:mode      :allowlist\n"
         "                      :allowlist #{:users}}\n"
         "   :entities         #merge [#include \"admin/users.edn\"]\n"
         "   :pagination       {:default-page-size 20\n"
         "                      :max-page-size     200}}\n")))

;; =============================================================================
;; Config assembly
;; =============================================================================

(defn- database-template [db env]
  (case db
    :postgresql (if (= env "test") (h2-template env) (postgresql-template env))
    :sqlite     (if (= env "test") (h2-template env) (sqlite-template env))
    :mysql      (if (= env "test") (h2-template env) (mysql-template env))
    :h2         (h2-template env)))

(defn build-config
  "Assemble a full config.edn from a setup spec and environment name."
  [spec env]
  (let [sections [(str "{;; =============================================================================\n"
                       " ;; " (str/capitalize env) " Environment Configuration\n"
                       " ;; =============================================================================\n"
                       "\n"
                       " :active\n"
                       " {")
                  (settings-template (:project-name spec) env)
                  (database-template (:database spec) env)
                  (when-not (= env "test") (http-template env))
                  (when-not (= env "test") (router-template env))
                  ;; wagoe new writes this key too (dev-config.edn.tmpl) — the
                  ;; BND-code enrichment of BOU-321. Setup regenerates the whole
                  ;; config, so leaving it out here silently un-ships the
                  ;; feature on any project that runs setup (BOU-416).
                  (when-not (#{"test" "prod"} env)
                    "  :wagoe/dev-error-enricher {}\n")
                  ;; Also written by wagoe new. An expired session is hidden
                  ;; from every read but its row stays, so without this key
                  ;; user_sessions grows with every login (BOU-429).
                  "  :wagoe/session-pruner\n  {:enable-pruning true :retention-days 30 :interval-hours 6}\n"
                  (logging-template env)
                  (observability-template env)
                  (admin-template (:admin-ui spec) env)
                  (ai-template (:ai-provider spec) env)
                  (payment-template (:payment spec) env)
                  (cache-template (:cache spec) env)
                  (email-template (:email spec) env)
                  "}\n\n :inactive\n {}\n}\n"]]
    (->> sections
         (remove nil?)
         (remove empty?)
         (str/join "\n"))))

;; =============================================================================
;; Env vars collection
;; =============================================================================

(def ^:private component-env-vars
  {:postgresql ["POSTGRES_HOST" "POSTGRES_PORT" "POSTGRES_DB"
                "POSTGRES_USER" "POSTGRES_PASSWORD"]
   :mysql      ["MYSQL_HOST" "MYSQL_PORT" "MYSQL_DB"
                "MYSQL_USER" "MYSQL_PASSWORD"]
   :stripe     ["STRIPE_SECRET_KEY" "STRIPE_WEBHOOK_SECRET"]
   :mollie     ["MOLLIE_API_KEY"]
   :anthropic  ["ANTHROPIC_API_KEY"]
   :openai     ["OPENAI_API_KEY"]
   :ollama     ["OLLAMA_URL"]
   :replicate  ["REPLICATE_API_TOKEN"]
   :redis      ["REDIS_HOST" "REDIS_PORT" "REDIS_PASSWORD"]
   :sqlite     ["SQLITE_PATH"]
   :h2         ["H2_PATH"]
   :smtp       ["SMTP_HOST" "SMTP_PORT" "SMTP_FROM" "SMTP_USERNAME" "SMTP_PASSWORD"]})

(defn build-env-example
  "Generate .env.example content from a setup spec."
  [spec]
  (let [sections
        [["# Wagoe Environment Configuration" ""]
         ["# HTTP Server" "HTTP_PORT=3000" "HTTP_HOST=0.0.0.0" ""]
         (when (= (:database spec) :postgresql)
           (into ["# PostgreSQL Database"]
                 (concat (map #(str % "=") (get component-env-vars :postgresql)) [""])))
         (when (= (:database spec) :mysql)
           (into ["# MySQL Database"]
                 (concat (map #(str % "=") (get component-env-vars :mysql)) [""])))
         (when (#{:sqlite :h2} (:database spec))
           (into ["# Database file (prod profile only)"]
                 (concat (map #(str % "=") (get component-env-vars (:database spec))) [""])))
         ["# Security" "JWT_SECRET=change-me-to-a-32-char-secret" ""]
         (when (not= (:ai-provider spec) :none)
           (let [provider (:ai-provider spec)]
             (into [(str "# AI Provider (" (name provider) ")")]
                   (concat (map #(str % "=") (get component-env-vars provider)) ["AI_MODEL=" ""]))))
         (when (contains? #{:stripe :mollie} (:payment spec))
           (into [(str "# Payments (" (name (:payment spec)) ")")]
                 (concat (map #(str % "=") (get component-env-vars (:payment spec))) [""])))
         (when (= (:cache spec) :redis)
           (into ["# Redis Cache"]
                 (concat (map #(str % "=") (get component-env-vars :redis)) [""])))
         (when (= (:email spec) :smtp)
           (into ["# SMTP Email"]
                 (concat (map #(str % "=") (get component-env-vars :smtp)) [""])))]]
    (->> sections
         (remove nil?)
         flatten
         (str/join "\n"))))

;; =============================================================================
;; Interactive wizard
;; =============================================================================

(defn wizard-interactive []
  (println)
  (println (bold "✦ Wagoe Config Setup Wizard"))
  (println (dim "Generate config.edn, test config, and .env.example for your project."))
  (println)

  (let [project-name (loop []
                       (let [s (prompt "Project name (kebab-case)" "my-app")]
                         (if (re-matches #"[a-z][a-z0-9-]*" s)
                           s
                           (do (println (red "  Must be kebab-case")) (recur)))))

        ;; SQLite leads deliberately. This list is what a newcomer meets on
        ;; their first run, and whatever sits first is what Enter (and a
        ;; non-interactive/EOF stdin) selects. With postgresql first, the
        ;; default path wrote a config needing a database server that is not
        ;; installed, and the run died on ClassNotFoundException:
        ;; org.postgresql.Driver (BOU-228). The default must boot unaided.
        database (select-option "Database"
                                [[:sqlite     "File-based, zero setup — recommended to start"]
                                 [:postgresql "Production-ready relational database"]
                                 [:h2         "H2 file-based (in-memory for the test profile)"]
                                 [:mysql      "MySQL/MariaDB"]])

        ai-provider (select-option "AI provider"
                                   [[:ollama    "Local AI via Ollama (no API key, but needs Ollama installed + a pulled model)"]
                                    [:anthropic "Anthropic Claude (requires ANTHROPIC_API_KEY)"]
                                    [:openai    "OpenAI GPT (requires OPENAI_API_KEY)"]
                                    [:replicate "Hosted models via Replicate (requires REPLICATE_API_TOKEN)"]
                                    [:none      "Disable AI tooling"]])

        payment (select-option "Payment provider"
                               [[:none   "No payments"]
                                [:mock   "Mock adapter (development/testing)"]
                                [:stripe "Stripe (requires STRIPE_SECRET_KEY)"]
                                [:mollie "Mollie (requires MOLLIE_API_KEY)"]])

        cache (select-option "Cache"
                             [[:none      "No caching"]
                              [:redis     "Redis (requires running Redis instance)"]
                              [:memory    "In-process cache (no external deps)"]])

        email (select-option "Email"
                             [[:none "No email"]
                              [:smtp "SMTP (requires SMTP server)"]])

        admin-ui (confirm "Enable admin UI?" true)]

    {:project-name project-name
     :database     database
     :ai-provider  ai-provider
     :payment      payment
     :cache        cache
     :email        email
     :admin-ui     admin-ui}))

;; =============================================================================
;; File writing
;; =============================================================================

(defn- root-dir [] (System/getProperty "user.dir"))

(defn- write-profile-file!
  "Prod is hand-maintained once it exists — module wiring, security policy —
  so an existing prod file is kept rather than regenerated."
  [env rel content]
  (let [f    (io/file (root-dir) "resources" "conf" env rel)
        path (str "resources/conf/" env "/" rel)]
    (if (and (= env "prod") (.exists f))
      (println (yellow "!") " Kept existing " (cyan path) (dim " (delete it to regenerate)"))
      (do (io/make-parents f)
          (spit f content)
          (println (green "✓") " Generated " (cyan path))))))

(defn- write-config-files!
  "Write generated config files to disk."
  [spec]
  (let [envs ["dev" "test" "prod"]]
    (println)
    (doseq [env envs]
      (write-profile-file! env "config.edn" (build-config spec env)))
    (spit (io/file (root-dir) ".env.example") (build-env-example spec))
    (println (green "✓") " Generated " (cyan ".env.example"))

    ;; The file the admin key's `#include` names. Written for every env, and
    ;; only when the config references it.
    (when (:admin-ui spec)
      (if-let [entity @admin-users-entity]
        (doseq [env envs]
          (write-profile-file! env "admin/users.edn" entity))
        (println (yellow "!") " Admin entity config missing from wagoe-tools;"
                 (cyan "#include \"admin/users.edn\"") "will not resolve.")))
    (println)
    (println (dim "Next steps:"))
    (println (dim "  1. Copy .env.example to .env and fill in your values"))
    (println (dim "  2. Run: bb migrate up"))
    (println (dim "  3. Run: bb doctor  (to verify your config)"))
    (when-let [steps (ai-provider-prerequisites (:ai-provider spec))]
      (println)
      (println (yellow (str "Before " (name (:ai-provider spec)) " answers:")))
      (doseq [s steps]
        (println (dim (str "  - " s)))))))

;; =============================================================================
;; Display summary
;; =============================================================================

(defn- display-summary [spec]
  (println)
  (println (cyan "┌─ Config Summary ─────────────────────────────────────┐"))
  (println (str (cyan "│") " Project:   " (bold (:project-name spec))))
  (println (str (cyan "│") " Database:  " (bold (name (:database spec)))))
  (println (str (cyan "│") " AI:        " (bold (name (:ai-provider spec)))))
  (println (str (cyan "│") " Payments:  " (bold (name (:payment spec)))))
  (println (str (cyan "│") " Cache:     " (bold (name (:cache spec)))))
  (println (str (cyan "│") " Email:     " (bold (name (:email spec)))))
  (println (str (cyan "│") " Admin UI:  " (if (:admin-ui spec) (green "✓") (red "✗"))))
  (println (cyan "└───────────────────────────────────────────────────────┘")))

;; =============================================================================
;; AI mode
;; =============================================================================

(defn- parse-ai-result
  "Parse AI setup-parse JSON result into a setup spec."
  [json-str]
  (try
    ;; Not the whole capture: the CLI is a JVM logging to the console, and in a
    ;; generated project — which ships no logback config — its own log lines sit
    ;; on stdout above the JSON (BOU-401).
    (let [data (some-> (ai/json-line json-str) json/parse-string)
          spec (when (map? data)
                 ;; Fall back to sqlite, not postgresql: an unparsed/absent choice must
                 ;; still yield a project that boots without a database server (BOU-228).
                 {:project-name (or (get data "project-name") "my-app")
                  :database     (keyword (or (get data "database") "sqlite"))
                  :ai-provider  (keyword (or (get data "ai-provider") "none"))
                  :payment      (keyword (or (get data "payment") "none"))
                  :cache        (keyword (or (get data "cache") "none"))
                  :email        (keyword (or (get data "email") "none"))
                  :admin-ui     (if (some? (get data "admin-ui"))
                                  (boolean (get data "admin-ui"))
                                  true)})
          errors (when spec (spec-errors spec))]
      ;; A provider is free to answer "gemini", or "claude" where the choice is
      ;; named "anthropic". Rejecting it here hands the caller its existing
      ;; fallback to the interactive wizard, instead of carrying an unrenderable
      ;; value into a `case` (BOU-411).
      (if (seq errors)
        (do (doseq [e errors] (println (red e)))
            nil)
        spec))
    (catch Exception e
      (println (red (str "Failed to parse AI response: " (.getMessage e))))
      nil)))

(defn wizard-ai [description]
  (println)
  (println (bold "✦ Wagoe AI Config Setup"))
  (println (dim (str "Parsing: " description)))
  (println)
  (try
    (let [result (apply shell {:out :string :err :string :continue true}
                        (ai/ai-command ["setup-parse" description]))
          spec   (when (zero? (:exit result)) (parse-ai-result (:out result)))]
      (if spec
        (do
          (display-summary spec)
          (println)
          (if (confirm "Generate these config files?" true)
            (write-config-files! spec)
            (println (yellow "Cancelled."))))
        (do
          (println (red "Could not parse AI response. Falling back to interactive mode."))
          ;; The CLI explains itself on stderr, which is captured here — a
          ;; rejected API key must not read as "the AI is unavailable".
          (when-not (str/blank? (str (:err result)))
            (println (dim (str/trim (str (:err result))))))
          (let [spec (wizard-interactive)]
            (display-summary spec)
            (println)
            (if (confirm "Generate these config files?" true)
              (write-config-files! spec)
              (println (yellow "Cancelled.")))))))
    (catch Exception _
      (println (yellow "AI parsing unavailable. Falling back to interactive mode."))
      (println)
      (let [spec (wizard-interactive)]
        (display-summary spec)
        (println)
        (if (confirm "Generate these config files?" true)
          (write-config-files! spec)
          (println (yellow "Cancelled.")))))))

;; =============================================================================
;; Non-interactive flag mode
;; =============================================================================

(defn- parse-flag-args [args]
  (loop [[flag & more :as remaining] args
         opts {}]
    (cond
      (empty? remaining) opts
      (or (= flag "--help") (= flag "-h")) (assoc opts :help true)
      (and (str/starts-with? flag "--") (seq more))
      (recur (rest more)
             (assoc opts (keyword (subs flag 2)) (first more)))
      :else (recur more opts))))

(defn from-flags [opts]
  (let [spec {:project-name (or (:project-name opts) "my-app")
              ;; sqlite, matching the interactive menu and the AI path — all
              ;; three entry points must agree. Any `bb setup --<flag>` lands
              ;; here, so `bb setup --payment mock` (no --database) took this
              ;; default; with postgresql it wrote a config whose driver is not
              ;; even on a generated project's classpath, which now ships only
              ;; sqlite and h2. Same clean-start failure as BOU-228.
              :database     (keyword (or (:database opts) "sqlite"))
              :ai-provider  (keyword (or (:ai-provider opts) "none"))
              :payment      (keyword (or (:payment opts) "none"))
              :cache        (keyword (or (:cache opts) "none"))
              :email        (keyword (or (:email opts) "none"))
              :admin-ui     (not= "false" (or (:admin-ui opts) "true"))}
        errors (spec-errors spec)]
    (if (seq errors)
      ;; Before the templates, not inside them: a `case` fall-through reported
      ;; "No matching clause: :bogus" and named neither the flag nor the
      ;; choices (BOU-411).
      (do (doseq [e errors] (println (red e)))
          (*exit!* 1))
      (do (display-summary spec)
          (println)
          (write-config-files! spec)))))

;; =============================================================================
;; Help
;; =============================================================================

(defn- print-help []
  (println (bold "bb setup") " — Interactive config setup wizard for Wagoe projects")
  (println)
  (println "Usage:")
  (println "  bb setup                                              Interactive wizard")
  (println "  bb setup ai \"PostgreSQL with Stripe payments\"         NL description mode")
  (println "  bb setup --database postgresql --payment stripe       Non-interactive flags")
  (println)
  (println "Options (non-interactive mode):")
  (println "  --project-name NAME    Project name (default: my-app)")
  (println "  --database DB          postgresql, sqlite, h2, mysql")
  (println "  --ai-provider PROV     ollama, anthropic, openai, replicate, none")
  (println "  --payment PAY          none, mock, stripe, mollie")
  (println "  --cache CACHE          none, redis, memory")
  (println "  --email EMAIL          none, smtp")
  (println "  --admin-ui BOOL        true, false")
  (println)
  (println "Generated files:")
  (println "  resources/conf/dev/config.edn")
  (println "  resources/conf/test/config.edn")
  (println "  resources/conf/prod/config.edn")
  (println "  .env.example"))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& raw-args]
  (let [args (vec raw-args)
        [sub & rest-args] args]
    (cond
      (or (nil? sub) (contains? #{"-h" "--help" "help"} sub))
      (if (nil? sub)
        ;; No args at all — run interactive wizard
        (let [spec (wizard-interactive)]
          (display-summary spec)
          (println)
          (if (confirm "Generate these config files?" true)
            (write-config-files! spec)
            (println (yellow "Cancelled."))))
        (print-help))

      (= sub "ai")
      (let [description (str/join " " rest-args)]
        (if (seq description)
          (wizard-ai description)
          (do (println (red "Please provide a description."))
              (println "  Example: bb setup ai \"PostgreSQL with Stripe payments\""))))

      ;; Has flags like --database
      (str/starts-with? sub "--")
      (let [opts (parse-flag-args args)]
        (if (:help opts)
          (print-help)
          (from-flags opts)))

      :else
      (do (println (red (str "Unknown subcommand: " sub)))
          (println)
          (print-help)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
