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
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [wagoe.tools.config-edn :as config-edn]
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

(defn- read-answer
  "A line from stdin. EOF throws: a closed stdin used to take every default,
   the final \"write these files?\" included (BOU-404)."
  []
  (or (read-line)
      (throw (ex-info "stdin closed" {:type :setup/stdin-closed}))))

(defn- prompt
  ([label] (prompt label nil))
  ([label default]
   (if default
     (print (str (cyan "? ") (bold label) " [" default "]: "))
     (print (str (cyan "? ") (bold label) ": ")))
   (flush)
   (let [input (str/trim (read-answer))]
     (if (and (empty? input) default)
       default
       input))))

(defn- confirm
  ([label] (confirm label true))
  ([label default-yes?]
   (let [hint (if default-yes? "Y/n" "y/N")]
     (print (str (cyan "? ") (bold label) " [" hint "]: "))
     (flush)
     (let [input (str/trim (str/lower-case (read-answer)))]
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
  (let [input  (str/trim (read-answer))
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
    "  :wagoe/logging\n  {:provider :slf4j :level :info}\n"
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
           "   :model    #or [#env AI_MODEL \"anthropic/claude-opus-4.6\"]\n"
           "   :api-key  #env REPLICATE_API_TOKEN}\n"))))

(defn- payment-template [provider env]
  (case provider
    :none ""
    ;; Never in prod, answered or not: the mock accepts any webhook as paid.
    :mock
    (if (prod? env)
      ""
      (str "  :wagoe/payment-provider\n"
           "  {:provider :mock}\n"))
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

(defn- database-template
  "nil for no answer. Dev and test migrate on boot, as `wagoe new` sets them
   (BOU-485); prod never does, a migration there is a deploy step."
  [db env]
  (when db
    (cond-> (case db
              :postgresql (if (= env "test") (h2-template env) (postgresql-template env))
              :sqlite     (if (= env "test") (h2-template env) (sqlite-template env))
              :mysql      (if (= env "test") (h2-template env) (mysql-template env))
              :h2         (h2-template env))
      (not (prod? env)) (str/replace-first "  {" "  {:migrate-on-start? true\n   "))))

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
                  (some-> (:ai-provider spec) (ai-template env))
                  (some-> (:payment spec) (payment-template env))
                  (some-> (:cache spec) (cache-template env))
                  (some-> (:email spec) (email-template env))
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

(defn- env-example-sections
  "The .env.example for `spec`, as sections of lines."
  [spec]
  (remove nil?
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
           (when-not (contains? #{nil :none} (:ai-provider spec))
             (let [provider (:ai-provider spec)]
               (into [(str "# AI Provider (" (name provider) ")")]
                     (concat (map #(str % "=") (get component-env-vars provider)) ["AI_MODEL=" ""]))))
           (when (contains? #{:stripe :mollie} (:payment spec))
             (into [(str "# Payments (" (name (:payment spec)) ")")]
                   (concat (map #(str % "=") (get component-env-vars (:payment spec))) [""])))
           (when (= (:cache spec) :redis)
             (into ["# Redis Cache"]
                   (concat (map #(str % "=") (get component-env-vars :redis)) [""])))
           ;; Set by plan when a prod it writes reads Redis.
           (when (:prod-redis? spec)
             (into ["# Redis (prod profile)"]
                   (concat (map #(str % "=") (get component-env-vars :redis)) [""])))
           (when (= (:email spec) :smtp)
             (into ["# SMTP Email"]
                   (concat (map #(str % "=") (get component-env-vars :smtp)) [""])))]))

(defn build-env-example
  "Generate .env.example content from a setup spec."
  [spec]
  (str/join "\n" (flatten (env-example-sections spec))))

(defn- root-dir [] (System/getProperty "user.dir"))

(def ^:private envs ["dev" "test" "prod"])

(def ^:private database-keys
  #{":wagoe/postgresql" ":wagoe/sqlite" ":wagoe/h2" ":wagoe/mysql"})

(def ^:private chosen-keys
  "Each key whose value answers a question, and the answer's spec key."
  {":wagoe/ai-service"       :ai-provider
   ":wagoe/payment-provider" :payment
   ":wagoe/cache"            :cache
   ":wagoe.external/smtp"    :email})

(def defaults
  "What an unanswered question means in a project setup creates. In a project
   that exists, no answer means leave it alone."
  {:project-name "my-app" :database :sqlite :ai-provider :none :payment :none
   :cache :none :email :none :admin-ui true})

(defn with-defaults [spec]
  (merge-with #(if (nil? %2) %1 %2) defaults spec))

(defn- read-edn
  "Every form in `text`, tags kept as tagged literals, or nil when it does not
   read."
  [text]
  (try
    (with-open [r (java.io.PushbackReader. (java.io.StringReader. text))]
      (let [opts {:eof ::eof :default tagged-literal}]
        (loop [out []]
          (let [form (edn/read opts r)]
            (if (= ::eof form) out (recur (conj out form)))))))
    (catch Exception _ nil)))

(defn- conf-rel [env] (str "resources/conf/" env "/config.edn"))

(defn- existing-project?
  "Whether a dev or test config exists. Setup then changes only what is
   answered, and creates no config file nobody asked for."
  []
  (some #(.exists (io/file (root-dir) (conf-rel %))) ["dev" "test"]))

(defn- current-active
  "The :active map of the existing dev config, else test's, or nil."
  []
  (some (fn [env]
          (let [f (io/file (root-dir) (conf-rel env))]
            (when (.exists f)
              (let [[m] (read-edn (slurp f))]
                (when (map? m) (:active m))))))
        ["dev" "test"]))

(defn- current-choices
  "What the existing config already answers, to label the wizard's keep."
  [active]
  (let [provider #(let [p (get-in active [% :provider])]
                    (if (or (nil? p) (= :no-op p)) :none p))]
    {:database    (some #(let [k (keyword (subs % 1))]
                           (when (contains? active k) (keyword (name k))))
                        (sort database-keys))
     :ai-provider (provider :wagoe/ai-service)
     :payment     (provider :wagoe/payment-provider)
     :cache       (provider :wagoe/cache)
     :email       (if (contains? active :wagoe.external/smtp) :smtp :none)
     :admin-ui    (if (contains? active :wagoe/admin) :yes :no)}))

(defn- project-name
  "The name the existing config was written for: :wagoe/settings :name less
   its profile suffix, or nil. Re-running setup offered my-app (BOU-564)."
  [active]
  (some-> (get-in active [:wagoe/settings :name])
          (str/replace #"-(dev|development|test)$" "")
          not-empty))

(defn- project-spec
  "What the existing config answers, as a spec, for a profile made from it."
  [active]
  (-> (current-choices active)
      (update :admin-ui #(= :yes %))
      (assoc :project-name (project-name active))))

;; =============================================================================
;; Interactive wizard
;; =============================================================================

(defn wizard-interactive []
  (println)
  (println (bold "✦ Wagoe Config Setup Wizard"))
  (println (dim "Generate config.edn, test config, and .env.example for your project."))
  (println)

  (let [existing? (existing-project?)
        current   (current-choices (current-active))
        ;; In an existing project Enter keeps what it has: a menu default is
        ;; not an answer, and must not replace a working provider (BOU-404).
        pick      (fn [label k options]
                    (let [answer (select-option
                                  label
                                  (cond->> options
                                    existing? (cons [:keep (str "Keep what the config has ("
                                                                (some-> (get current k) name) ")")])))]
                      (when-not (= :keep answer) answer)))
        project-name (loop []
                       (let [s (prompt "Project name (kebab-case)"
                                       (or (when existing? (project-name (current-active))) "my-app"))]
                         (if (re-matches #"[a-z][a-z0-9-]*" s)
                           s
                           (do (println (red "  Must be kebab-case")) (recur)))))

        ;; SQLite leads deliberately. This list is what a newcomer meets on
        ;; their first run, and whatever sits first is what Enter selects.
        ;; With postgresql first, the default path wrote a config needing a
        ;; database server that is not installed, and the run died on
        ;; ClassNotFoundException: org.postgresql.Driver (BOU-228). The
        ;; default must boot unaided.
        database (pick "Database" :database
                       [[:sqlite     "File-based, zero setup — recommended to start"]
                        [:postgresql "Production-ready relational database"]
                        [:h2         "H2 file-based (in-memory for the test profile)"]
                        [:mysql      "MySQL/MariaDB"]])

        ai-provider (pick "AI provider" :ai-provider
                          [[:ollama    "Local AI via Ollama (no API key, but needs Ollama installed + a pulled model)"]
                           [:anthropic "Anthropic Claude (requires ANTHROPIC_API_KEY)"]
                           [:openai    "OpenAI GPT (requires OPENAI_API_KEY)"]
                           [:replicate "Hosted models via Replicate (requires REPLICATE_API_TOKEN)"]
                           [:none      "Disable AI tooling"]])

        payment (pick "Payment provider" :payment
                      [[:none   "No payments"]
                       [:mock   "Mock adapter (development/testing)"]
                       [:stripe "Stripe (requires STRIPE_SECRET_KEY)"]
                       [:mollie "Mollie (requires MOLLIE_API_KEY)"]])

        cache (pick "Cache" :cache
                    [[:none      "No caching"]
                     [:redis     "Redis (requires running Redis instance)"]
                     [:memory    "In-process cache (no external deps)"]])

        email (pick "Email" :email
                    [[:none "No email"]
                     [:smtp "SMTP (requires SMTP server)"]])

        ;; No never removes an existing admin, it only does not add one.
        admin-ui (if existing?
                   (= :yes (pick "Admin UI" :admin-ui [[:yes "Add the admin UI"] [:no "No admin UI"]]))
                   (confirm "Enable admin UI?" true))

        prod? (when (and existing? (not (.exists (io/file (root-dir) (conf-rel "prod")))))
                (confirm "Create resources/conf/prod/config.edn?" false))]

    {:project-name project-name
     :database     database
     :ai-provider  ai-provider
     :payment      payment
     :cache        cache
     :email        email
     :admin-ui     (if existing? (when admin-ui true) admin-ui)
     :prod?        prod?}))

;; =============================================================================
;; Merging into an existing config (BOU-404)
;; =============================================================================
;; Regenerating the whole file dropped every key setup does not write: a module
;; `bb scaffold integrate` added, the :migrate-on-start? `wagoe new` sets
;; (BOU-532). An existing file now gets only what was answered.

(defn- line-ending
  "The file's own line ending, so inserted lines match it."
  [text]
  (let [i (str/index-of text "\n")]
    (if (and i (pos? i) (= \return (get text (dec i)))) "\r\n" "\n")))

(defn- entry [text kw k]
  (some #(when (= k (:key %)) %) (config-edn/entries text kw)))

(defn- entry-text [text e]
  (subs text (:start e) (:end e)))

(defn- column [text i]
  (- i (inc (or (str/last-index-of text "\n" (dec i)) -1))))

(defn- reindent
  "`snippet`, written at column 2, moved to column `col`, lines ending in `nl`."
  [snippet col nl]
  (let [[first-line & more] (str/split-lines snippet)
        shift #(cond
                 (> col 2) (str (apply str (repeat (- col 2) \space)) %)
                 (< col 2) (str/replace-first % (re-pattern (str "^ {0," (- 2 col) "}")) "")
                 :else     %)]
    (str/join nl (cons first-line (map shift more)))))

(defn- cut
  "`text` without entry `e`, the comments above it, and its line end."
  [text e]
  (let [rest (subs text (:end e))
        skip (cond (str/starts-with? rest "\r\n") 2 (str/starts-with? rest "\n") 1 :else 0)]
    (str (subs text 0 (:from e)) (subs rest skip))))

(defn- add-entry [text snippet nl]
  (let [last-key (some-> (config-edn/entries text ":active") last :start)
        col      (if last-key (column text last-key) 2)
        close    (second (config-edn/section text ":active"))]
    (config-edn/insert-into text ":active"
                            (str (if (= \newline (get text (dec close))) nl (str nl nl))
                                 (apply str (repeat col \space)) (reindent snippet col nl)))))

(defn- deactivate
  "`text` with entry `e` moved from :active to :inactive, comments and all. An
   :inactive entry of the same key is replaced."
  [text e nl]
  (let [piece (subs text (:from e) (:end e))
        text  (cut text e)
        text  (if-let [old (entry text ":inactive" (:key e))] (cut text old) text)]
    (if (config-edn/section text ":inactive")
      (config-edn/insert-into text ":inactive" (str nl piece))
      (let [[_ close] (config-edn/root-map text)]
        (str (subs text 0 close) nl " :inactive" nl " {" nl piece "}" (subs text close))))))

(defn- provider-of
  "The provider an existing entry configures: its :provider, or `present` for
   a key that has none (smtp)."
  [text e present]
  (let [[v] (read-edn (subs text (:value e) (:end e)))
        p   (if (map? v) (:provider v) present)]
    (get {:in-memory :memory} p p)))

(defn merge-config
  "`existing` config text with the answers in `generated`, as
   {:text :changes :moved :touched}. nil when `existing` has no literal
   :active map.

   Only answered keys are touched. A provider already configured with the
   answer keeps its map, and in the test profile any configured provider is
   kept: its template is a stand-in (memory, mock, no-op), and replacing a real
   one with it is a downgrade. A database is added when none is active; with
   `switch-db?` the one it replaces moves to :inactive, since two active
   databases do not boot. Without it (the test profile) an active database is
   left alone."
  [existing generated {:keys [switch-db? env spec nl] :or {nl "\n"}}]
  (when (config-edn/entries existing ":active")
    (reduce
     (fn [{:keys [text] :as acc} g]
       (let [k      (:key g)
             new    (entry-text generated g)
             cur    (entry text ":active" k)
             active (->> (config-edn/entries text ":active") (map :key) (filter database-keys))
             answer (some->> (chosen-keys k) (get spec))
             add    #(-> %1
                         (update :text add-entry new nl)
                         (update :changes conj (str "add " k)))]
         (cond
           (and cur (chosen-keys k))
           (if (or (= "test" env)
                   (= (get {:in-memory :memory} answer answer) (provider-of text cur answer)))
             acc
             (-> acc
                 (assoc :text (str (subs text 0 (:start cur))
                                   (reindent new (column text (:start cur)) nl)
                                   (subs text (:end cur))))
                 (update :changes conj (str "replace " k " (edits inside it are lost)"))
                 (update :touched conj k)))

           (chosen-keys k) (add acc)

           (and (= ":wagoe/admin" k) (not cur)) (add acc)

           (and (database-keys k) (not cur) (or (empty? active) switch-db?))
           (-> acc
               (assoc :text (reduce #(deactivate %1 (entry %1 ":active" %2) nl) text active))
               (update :changes into (for [o active]
                                       (str "move " o " to :inactive"
                                            (when (entry text ":inactive" o) ", replacing the one there"))))
               (update :moved into active)
               (add))

           :else acc)))
     {:text existing :changes [] :moved #{} :touched #{}}
     (config-edn/entries generated ":active"))))

;; =============================================================================
;; A prod profile made in an existing project (BOU-564)
;; =============================================================================
;; Built from the answers alone, it lacked every module added before it and the
;; AI config, and was named my-app.

(def ^:private dev-only-keys
  "Keys that stay in dev: the platform refuses the first two outside :dev, and
   the AI service is a build-time tool (see `ai-template`)."
  #{":wagoe/dashboard" ":wagoe/dev-error-enricher" ":wagoe/ai-service"})

(def ^:private stand-ins
  "Providers that only pretend. The mock payment provider accepts any webhook
   as paid; an in-memory bus, queue or cache splits across replicas."
  #{:mock :no-op :memory :in-memory})

(def ^:private redis-conn
  (str "   :host     #env REDIS_HOST\n"
       "   :port     #long #or [#env REDIS_PORT 6379]\n"
       "   :password #env REDIS_PASSWORD"))

(defn- prod-value
  "What prod gets for dev's in-memory `k`, or nil when there is no real
   provider to put in its place."
  [k spec]
  (case k
    ":wagoe/events"   (str ":wagoe/events\n  {:provider :redis\n" redis-conn "\n"
                           "   :group    \"" (:project-name spec) "\"}\n")
    ":wagoe/realtime" (str ":wagoe/realtime\n  {:provider :redis\n" redis-conn "}\n")
    ;; The database prod already has; no extra service to run.
    ":wagoe/jobs"     ":wagoe/jobs\n  {:provider :db :workers {:count 1}}\n"
    nil))

(defn carry-over
  "`prod-text` with every entry of `dev-text`'s :active it lacks, as
   {:text :left-out}. Dev-only keys and databases stay behind. A stand-in
   provider is replaced by a real one where there is one, else left out and
   named in :left-out."
  [prod-text dev-text spec]
  (reduce (fn [{:keys [text] :as acc} e]
            (let [k (:key e)]
              (cond
                (or (entry text ":active" k) (dev-only-keys k) (database-keys k))
                acc

                (stand-ins (provider-of dev-text e nil))
                (if-let [v (prod-value k spec)]
                  (assoc acc :text (add-entry text v "\n"))
                  (update acc :left-out conj k))

                :else
                (assoc acc :text
                       (add-entry text
                                  ;; reindent moves a snippet from column 2 to
                                  ;; `col`; 4 - c brings one at column c to 2.
                                  (reindent (subs dev-text (:start e) (:end e))
                                            (- 4 (column dev-text (:start e))) "\n")
                                  "\n")))))
          {:text prod-text :left-out []}
          (or (config-edn/entries dev-text ":active") [])))

(defn- merge-loss
  "Why `merged` must not replace `old`, or nil. Both must read as one EDN map,
   and every root, :active and :inactive entry of `old` must read back `=` —
   except the keys setup replaced (`touched`), which only have to be there, and
   the databases it moved (`moved`), which must be `=` in :inactive. Every
   parser mistake lands here as a refusal."
  [old merged moved touched]
  (let [[o & o-more] (read-edn old)
        [m & m-more] (read-edn merged)
        kw           #(set (map (fn [s] (keyword (subs s 1))) %))
        moved        (kw moved)
        touched      (kw touched)
        oa (:active o) ma (:active m) oi (:inactive o) mi (:inactive m)]
    (cond
      (or (not (map? o)) (seq o-more)) "it does not read as one EDN map"
      (or (not (map? m)) (seq m-more)) "the merged result would not read as EDN"
      :else
      (let [lost (concat
                  (for [[k v] (dissoc o :active :inactive) :when (not= v (get m k ::absent))] k)
                  (for [[k v] oa
                        :when (cond (touched k) (not (contains? ma k))
                                    (moved k)   (not= v (get mi k ::absent))
                                    :else       (not= v (get ma k ::absent)))]
                    k)
                  (for [[k v] oi
                        :when (if (moved k)
                                (not (contains? mi k))
                                (not= v (get mi k ::absent)))]
                    k))]
        (when (seq lost)
          (str "the merge would change or lose " (str/join ", " lost)))))))

(def ^:private baseline-env-headers
  "Sections of .env.example no question answers, so a merge never adds them."
  #{"# Wagoe Environment Configuration" "# HTTP Server" "# Security"})

(defn- merge-env-example
  "`existing` .env.example with the variables the answers in `spec` need and it
   lacks."
  [existing spec nl]
  (let [have    (set (map second (re-seq #"(?m)^\s*([A-Z][A-Z0-9_]*)=" existing)))
        missing (fn [line]
                  (let [v (second (re-matches #"([A-Z][A-Z0-9_]*)=.*" line))]
                    (and v (not (have v)))))
        ;; Two sections can name one variable (a Redis cache and a Redis
        ;; event bus), so what one adds counts as present for the next.
        added   (:out (reduce (fn [{:keys [seen] :as acc} [header & lines]]
                                (let [vs (distinct (remove #(seen (second (re-matches #"([A-Z][A-Z0-9_]*)=.*" %)))
                                                           (filter missing lines)))]
                                  (if (or (baseline-env-headers header) (empty? vs))
                                    acc
                                    {:seen (into seen (map #(second (re-matches #"([A-Z][A-Z0-9_]*)=.*" %)) vs))
                                     :out  (conj (:out acc) (concat [header] vs [""]))})))
                              {:seen #{} :out []}
                              (env-example-sections spec)))]
    (if (empty? added)
      {:text existing :changes []}
      {:text    (str existing
                     (when-not (or (str/blank? existing) (str/ends-with? existing "\n")) nl)
                     (when-not (str/blank? existing) nl)
                     (str/join nl (apply concat added)))
       :changes [(str "add " (str/join ", " (mapcat #(remove str/blank? (rest %)) added)))]})))

(defn- read-target
  "The text at `rel`, or nil when there is no file."
  [rel]
  (let [f (io/file (root-dir) rel)]
    (when (.exists f) (slurp f))))

(defn- plan-file
  "What setup would do to `rel`, with the text it was planned from as :old.

   `create` is the content for a missing file, or nil to leave it missing.
   `merge-fn` takes the existing text and its line ending and returns
   {:text :changes :moved :touched}, or nil when it cannot; without one an
   existing file is kept. `edn?` runs the loss check on the result."
  [rel create merge-fn edn?]
  (let [f   (io/file (root-dir) rel)
        old (read-target rel)]
    (cond
      (nil? old)          (if create
                            {:path rel :status :new :content create :old nil}
                            {:path rel :status :skipped})
      (nil? merge-fn)     {:path rel :status :kept}
      (not (.canWrite f)) {:path rel :status :refused :reason "it is read-only"}
      :else
      (let [res  (try (merge-fn old (line-ending old))
                      (catch Exception e {::error (str "setup could not read it (" (.getMessage e) ")")}))
            text (:text res)
            why  (cond
                   (::error res) (::error res)
                   (nil? res)    "it has no literal :active map to merge into"
                   (= text old)  nil
                   edn?          (merge-loss old text (:moved res) (:touched res)))]
        (cond
          why          {:path rel :status :refused :reason why}
          (= text old) {:path rel :status :unchanged}
          :else        {:path rel :status :changed :content text :old old
                        :changes (:changes res)})))))

(defn- plan
  "Every file setup would touch for `spec`.

   In a new project every file is created, with the defaults for what was not
   answered. In an existing one only answers are written: no missing config is
   created, except prod when asked for (`:prod?`). Prod is hand-maintained once
   it exists, and so is an admin entity file, so both are kept (BOU-499)."
  [spec]
  (let [existing? (existing-project?)
        full      (with-defaults spec)
        dev-text  (read-target (conf-rel "dev"))
        dev       (let [a (some-> dev-text read-edn first :active)] (when (map? a) a))
        ;; Unanswered means what dev has. Dev's admin is carried whole, with
        ;; the entity files it includes, rather than regenerated.
        ;; What dev runs in memory, prod runs on Redis, unless asked for.
        from-dev  (cond-> (project-spec dev)
                    (#{:memory :in-memory} (:cache (project-spec dev))) (assoc :cache :redis))
        prod-spec (cond-> (with-defaults (merge-with #(if (nil? %2) %1 %2) from-dev spec))
                    (contains? dev :wagoe/admin) (assoc :admin-ui false))
        carried   (when (and existing? (:prod? spec) dev-text)
                    (carry-over (build-config prod-spec "prod") dev-text prod-spec))
        create    (fn [env]
                    (cond
                      (not existing?) (build-config full env)
                      (and (prod? env) (:prod? spec))
                      (or (:text carried) (build-config prod-spec env))))
        configs   (for [env envs]
                    (cond-> (plan-file (conf-rel env)
                                       (create env)
                                       (when-not (prod? env)
                                         #(merge-config %1 (build-config spec env)
                                                        {:switch-db? (not= "test" env) :env env
                                                         :spec spec :nl %2}))
                                       true)
                      (and (prod? env) (seq (:left-out carried)))
                      (assoc :changes (for [k (:left-out carried)]
                                        (str "leave out " k ": dev's provider is a stand-in; configure a real one")))))
        redis?    (some (fn [{:keys [path status content]}]
                          (and (= (conf-rel "prod") path) (= :new status)
                               (str/includes? content "#env REDIS_HOST")))
                        configs)
        env-spec  (cond-> spec redis? (assoc :prod-redis? true))
        env-ex    (if (or existing? (read-target ".env.example"))
                    (let [p (plan-file ".env.example" nil #(merge-env-example %1 env-spec %2) false)]
                      (if (= :skipped (:status p))
                        ;; Missing in an existing project: only what the answers add.
                        (let [{:keys [text changes]} (merge-env-example "" env-spec "\n")]
                          (if (seq changes)
                            {:path ".env.example" :status :new :content text :old nil :changes changes}
                            p))
                        p))
                    (plan-file ".env.example" (build-env-example full) nil false))
        entity    @admin-users-entity]
    (concat
     configs
     [env-ex]
     ;; The files a config written now `#include`s: dev's copy for a prod made
     ;; from dev, else the users entity setup ships.
     (distinct
      (for [[env {:keys [content]}] (map vector envs configs)
            :when content
            inc   (distinct (map second (re-seq #"#include\s+\"([^\"]+)\"" content)))
            :let  [source (or (when (prod? env) (read-target (str "resources/conf/dev/" inc)))
                              (when (= "admin/users.edn" inc) entity))]
            :when source]
        (plan-file (str "resources/conf/" env "/" inc) source nil false))))))

(defn- stale
  "The paths of `items` whose file no longer holds the text it was planned
   from."
  [items]
  (for [{:keys [path old]} items
        :when (not= old (read-target path))]
    path))

(defn- missing-dirs
  "The directories that would have to be created for `f`, outermost first."
  [^java.io.File f]
  (reverse (take-while #(not (.exists ^java.io.File %))
                       (iterate #(.getParentFile ^java.io.File %) (.getParentFile f)))))

(defn- write-all!
  "Write `items` ({:path :content}) all or nothing, as far as the filesystem
   allows: every file goes to a temp file beside its target first, then each
   is renamed over it. A symlink's target is what gets replaced, and an
   existing file's permissions carry over. Returns nil, or a message naming
   what failed, what was written and what was not."
  [items]
  (let [target (fn [{:keys [path]}]
                 (let [p (.toPath (io/file (root-dir) path))]
                   (if (java.nio.file.Files/exists p (make-array java.nio.file.LinkOption 0))
                     (.toRealPath p (make-array java.nio.file.LinkOption 0))
                     p)))
        tmp-of (fn [it]
                 (let [t (target it)]
                   (.resolveSibling t (str "." (.getFileName t) ".setup-tmp"))))
        temps  (atom [])
        dirs   (atom [])
        paths  (map :path items)]
    (try
      (doseq [it items]
        (let [tmp (tmp-of it)
              t   (target it)]
          (swap! dirs into (missing-dirs (.toFile tmp)))
          (io/make-parents (.toFile tmp))
          (spit (.toFile tmp) (:content it))
          (swap! temps conj tmp)
          (when (java.nio.file.Files/exists t (make-array java.nio.file.LinkOption 0))
            (try
              (java.nio.file.Files/setPosixFilePermissions
               tmp (java.nio.file.Files/getPosixFilePermissions t (make-array java.nio.file.LinkOption 0)))
              (catch UnsupportedOperationException _ nil)))))
      (let [done (atom [])]
        (try
          (doseq [it items]
            (java.nio.file.Files/move (tmp-of it) (target it)
                                      (into-array java.nio.file.CopyOption
                                                  [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
            (swap! done conj (:path it)))
          nil
          (catch Exception e
            (str "Could not replace a file (" (.getMessage e) ")."
                 " Written: " (str/join ", " @done) "."
                 " Not written: " (str/join ", " (remove (set @done) paths)) "."))))
      (catch Exception e
        (str "Could not write a file (" (.getMessage e) ")."
             " Nothing was written: " (str/join ", " paths) "."))
      (finally
        (doseq [t @temps] (java.nio.file.Files/deleteIfExists t))
        (doseq [^java.io.File d (reverse (distinct @dirs))]
          (when (and (.isDirectory d) (empty? (.list d)))
            (.delete d)))))))

(defn- write-plan! [spec plan]
  (println)
  (let [items (filter (comp #{:new :changed} :status) plan)]
    (if-let [changed (seq (stale items))]
      (do (println (red (str (str/join ", " changed)
                             " changed on disk since planning, nothing written. Run setup again.")))
          (*exit!* 1))
      (if-let [err (write-all! items)]
        (do (println (red err))
            (*exit!* 1))
        (do
          (doseq [{:keys [path status]} plan]
            (case status
              :new       (println (green "✓") " Generated" (cyan path))
              :changed   (println (green "✓") " Updated" (cyan path))
              :kept      (println (yellow "!") " Kept existing" (cyan path) (dim "(delete it to regenerate)"))
              (:unchanged :skipped) nil))
          (when (and (:admin-ui spec) (nil? @admin-users-entity))
            (println (yellow "!") " Admin entity config missing from wagoe-tools;"
                     (cyan "#include \"admin/users.edn\"") "will not resolve."))
          (println)
          (println (dim "Next steps:"))
          (println (dim "  1. Copy .env.example to .env and fill in your values"))
          (println (dim "  2. Run: bb migrate up"))
          (println (dim "  3. Run: bb doctor  (to verify your config)"))
          (when-let [steps (ai-provider-prerequisites (:ai-provider spec))]
            (println)
            (println (yellow (str "Before " (name (:ai-provider spec)) " answers:")))
            (doseq [s steps]
              (println (dim (str "  - " s))))))))))

;; =============================================================================
;; Display summary
;; =============================================================================

(defn- display-summary [spec plan]
  (let [shown #(if-some [v (get spec %)] (name v) "keep")]
    (println)
    (println (cyan "┌─ Config Summary ─────────────────────────────────────┐"))
    (println (str (cyan "│") " Project:   " (bold (shown :project-name))))
    (println (str (cyan "│") " Database:  " (bold (shown :database))))
    (println (str (cyan "│") " AI:        " (bold (shown :ai-provider))))
    (println (str (cyan "│") " Payments:  " (bold (shown :payment))))
    (println (str (cyan "│") " Cache:     " (bold (shown :cache))))
    (println (str (cyan "│") " Email:     " (bold (shown :email))))
    (println (str (cyan "│") " Admin UI:  " (case (:admin-ui spec) true (green "✓") false (red "✗") "keep")))
    (println (cyan "└───────────────────────────────────────────────────────┘"))
    (doseq [{:keys [path status changes reason]} plan]
      (println (str "  " (case status
                           :new       (green "create       ")
                           :changed   (yellow "change       ")
                           :unchanged (dim "unchanged    ")
                           :skipped   (dim "not created  ")
                           :kept      (dim "keep         ")
                           :refused   (red "cannot merge "))
                    path
                    (when reason (str ": " reason))))
      (doseq [c changes]
        (println (dim (str "                 " c)))))))

(defn- run-setup!
  "Show what `spec` would change, ask first when `ask?`, then write it."
  [spec ask?]
  (let [plan    (plan spec)
        refused (filter #(= :refused (:status %)) plan)]
    (display-summary spec plan)
    (println)
    (cond
      (seq refused)
      (do (doseq [{:keys [path reason]} refused]
            (println (red (str "Cannot merge into " path ": " reason "."))))
          (println "Nothing was written. Fix the file, or move it aside to have setup regenerate it.")
          (*exit!* 1))

      (not-any? (comp #{:new :changed} :status) plan)
      (println (dim "Nothing to change."))

      (and ask? (not (confirm "Generate these config files?" true)))
      (println (yellow "Cancelled."))

      :else (write-plan! spec plan))))

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
                 ;; An absent answer stays nil: it leaves an existing config
                 ;; alone, and a new file gets `defaults` (BOU-404).
                 {:project-name (get data "project-name")
                  :database     (some-> (get data "database") keyword)
                  :ai-provider  (some-> (get data "ai-provider") keyword)
                  :payment      (some-> (get data "payment") keyword)
                  :cache        (some-> (get data "cache") keyword)
                  :email        (some-> (get data "email") keyword)
                  :admin-ui     (some-> (get data "admin-ui") boolean)})
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
  (let [result (try (apply shell {:out :string :err :string :continue true}
                           (ai/ai-command ["setup-parse" description]))
                    (catch Exception _ nil))
        spec   (when (some-> result :exit zero?) (parse-ai-result (:out result)))]
    (cond
      spec (run-setup! spec true)

      (nil? result)
      (do (println (yellow "AI parsing unavailable. Falling back to interactive mode."))
          (println)
          (run-setup! (wizard-interactive) true))

      :else
      (do (println (red "Could not parse AI response. Falling back to interactive mode."))
          ;; The CLI explains itself on stderr, which is captured here — a
          ;; rejected API key must not read as "the AI is unavailable".
          (when-not (str/blank? (str (:err result)))
            (println (dim (str/trim (str (:err result))))))
          (run-setup! (wizard-interactive) true)))))

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

(defn from-flags-spec
  "The setup spec the flags in `opts` describe. A flag not given stays nil:
   it leaves an existing config alone, and a new file gets `defaults` —
   sqlite, so every entry point agrees on a first run that boots unaided
   (BOU-228). Defaulting it here moved an active postgresql aside on
   `bb setup --payment mock` (BOU-404)."
  [opts]
  {:project-name (:project-name opts)
   :database     (some-> (:database opts) keyword)
   :ai-provider  (some-> (:ai-provider opts) keyword)
   :payment      (some-> (:payment opts) keyword)
   :cache        (some-> (:cache opts) keyword)
   :email        (some-> (:email opts) keyword)
   :admin-ui     (some-> (:admin-ui opts) (not= "false"))
   :prod?        (= "true" (:prod opts))})

(defn from-flags [opts]
  (let [spec   (from-flags-spec opts)
        errors (spec-errors spec)]
    (if (seq errors)
      ;; Before the templates, not inside them: a `case` fall-through reported
      ;; "No matching clause: :bogus" and named neither the flag nor the
      ;; choices (BOU-411).
      (do (doseq [e errors] (println (red e)))
          (*exit!* 1))
      (run-setup! spec false))))

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
  (println "  --prod true            Create resources/conf/prod/config.edn in an existing project")
  (println)
  (println "Generated files:")
  (println "  resources/conf/dev/config.edn")
  (println "  resources/conf/test/config.edn")
  (println "  resources/conf/prod/config.edn")
  (println "  .env.example"))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn- dispatch [raw-args]
  (let [args (vec raw-args)
        [sub & rest-args] args]
    (cond
      (or (nil? sub) (contains? #{"-h" "--help" "help"} sub))
      (if (nil? sub)
        ;; No args at all — run interactive wizard
        (run-setup! (wizard-interactive) true)
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

(defn -main [& raw-args]
  (try
    (dispatch raw-args)
    (catch clojure.lang.ExceptionInfo e
      (if (= :setup/stdin-closed (:type (ex-data e)))
        (do (println)
            (println (red "stdin closed before the wizard finished. Nothing was written."))
            (println "  Without a terminal, pass flags:  bb setup --database sqlite")
            (*exit!* 1))
        (throw e)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
