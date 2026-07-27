#!/usr/bin/env bb
;; libs/tools/src/wagoe/tools/setup.clj
;;
;; Config Setup Wizard — interactive config generation for Boundary projects.
;;
;; Usage (via bb.edn task):
;;   bb setup                                              # Interactive wizard
;;   bb setup ai "PostgreSQL with Stripe payments"         # NL mode
;;   bb setup --database postgresql --payment stripe       # Non-interactive flags

(ns wagoe.tools.setup
  (:require [wagoe.tools.ansi :refer [bold green red cyan yellow dim]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :refer [shell]]))

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

(defn- settings-template [project-name env]
  (str "  :wagoe/settings\n"
       "  {:name              \"" project-name "-" env "\"\n"
       "   :version           \"0.1.0\"\n"
       "   :date-format       \"yyyy-MM-dd\"\n"
       "   :date-time-format  \"yyyy-MM-dd HH:mm:ss\"\n"
       "   :currency/iso-code \"EUR\"\n"
       "   :features          {:user-web-ui {:enabled? true}}}\n"))

(defn- postgresql-template [env]
  (if (= env "test")
    ""  ; test uses H2
    (str "  :wagoe/postgresql\n"
         "  {:host        #or [#env POSTGRES_HOST \"localhost\"]\n"
         "   :port        #or [#long #or [#env POSTGRES_PORT 5432] 5432]\n"
         "   :dbname      #or [#env POSTGRES_DB \"" "boundary_" env "\"]\n"
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
         "  {:db   \"" env "-database.db\"\n"
         "   :pool {:minimum-idle          1\n"
         "          :maximum-pool-size     3\n"
         "          :connection-timeout-ms 10000}}\n")))

(defn- mysql-template [env]
  (if (= env "test")
    ""
    (str "  :wagoe/mysql\n"
         "  {:host     #or [#env MYSQL_HOST \"localhost\"]\n"
         "   :port     #or [#long #or [#env MYSQL_PORT 3306] 3306]\n"
         "   :dbname   #or [#env MYSQL_DB \"boundary_" env "\"]\n"
         "   :user     #or [#env MYSQL_USER \"root\"]\n"
         "   :password #or [#env MYSQL_PASSWORD \"\"]\n"
         "   :pool     {:minimum-idle          2\n"
         "              :maximum-pool-size     10\n"
         "              :connection-timeout-ms 30000}}\n")))

(defn- h2-template [env]
  (if (= env "test")
    (str "  :wagoe/h2\n"
         "  {:memory true\n"
         "   :pool   {:minimum-idle 1\n"
         "            :maximum-pool-size 5\n"
         "            :connection-timeout-ms 5000}}\n")
    (str "  :wagoe/h2\n"
         "  {:memory true\n"
         "   :pool   {:minimum-idle      1\n"
         "            :maximum-pool-size 10}}\n")))

(defn- http-template [_env]
  (str "  :wagoe/http\n"
       "  {:port       #or [#env HTTP_PORT 3000]\n"
       "   :host       #or [#env HTTP_HOST \"0.0.0.0\"]\n"
       "   :join?      false\n"
       "   :port-range {:start 3000 :end 3099}}\n"))

(defn- router-template [_env]
  (str "  :wagoe/router\n"
       "  {:adapter    :reitit\n"
       "   :coercion   :malli\n"
       "   :middleware []}\n"))

(defn- logging-template [env]
  (if (= env "test")
    (str "  :wagoe/logging\n"
         "  {:level     :debug\n"
         "   :console   true\n"
         "   :appenders [{:appender       :rolling-file\n"
         "                :file           \"logs/boundary-test.log\"\n"
         "                :rolling-policy {:type :time-based :max-history 3}}]}\n")
    (str "  :wagoe/logging\n"
         "  {:provider     :slf4j\n"
         "   :level        :debug\n"
         "   :logger-name  \"boundary\"\n"
         "   :default-tags {:service     \"boundary-" env "\"\n"
         "                  :environment \"" (if (= env "prod") "production" "development") "\"}}\n")))

(defn- observability-template [_env]
  (str "  :wagoe/metrics\n"
       "  {:provider :no-op}\n"
       "\n"
       "  :wagoe/error-reporting\n"
       "  {:provider :no-op}\n"))

(defn- ai-template [provider env]
  (case provider
    :none ""
    :ollama
    (if (= env "test")
      (str "  :wagoe/ai-service\n"
           "  {:provider :no-op}\n")
      (str "  :wagoe/ai-service\n"
           "  {:provider :ollama\n"
           "   :model    #or [#env AI_MODEL \"qwen2.5-coder:7b\"]\n"
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
           "   :api-key  #env OPENAI_API_KEY}\n"))))

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

(defn- cache-template [provider env]
  (case provider
    :none ""
    :in-memory
    (str "  :wagoe/cache\n"
         "  {:provider    :in-memory\n"
         "   :default-ttl 300}\n")
    :redis
    (if (= env "test")
      (str "  :wagoe/cache\n"
           "  {:provider    :in-memory\n"
           "   :default-ttl 300}\n")
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
    (if (= env "test")
      ""
      (str "  :wagoe.external/smtp\n"
           "  {:host #or [#env SMTP_HOST \"localhost\"]\n"
           "   :port #or [#long #or [#env SMTP_PORT 1025] 1025]\n"
           "   :tls? false\n"
           "   :from #or [#env SMTP_FROM \"no-reply@localhost\"]}\n"))))

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
   :redis      ["REDIS_HOST" "REDIS_PORT" "REDIS_PASSWORD"]
   :smtp       ["SMTP_HOST" "SMTP_PORT" "SMTP_FROM"]})

(defn build-env-example
  "Generate .env.example content from a setup spec."
  [spec]
  (let [sections
        [["# Boundary Environment Configuration" ""]
         ["# HTTP Server" "HTTP_PORT=3000" "HTTP_HOST=0.0.0.0" ""]
         (when (= (:database spec) :postgresql)
           (into ["# PostgreSQL Database"]
                 (concat (map #(str % "=") (get component-env-vars :postgresql)) [""])))
         (when (= (:database spec) :mysql)
           (into ["# MySQL Database"]
                 (concat (map #(str % "=") (get component-env-vars :mysql)) [""])))
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
  (println (bold "✦ Boundary Config Setup Wizard"))
  (println (dim "Generate config.edn, test config, and .env.example for your project."))
  (println)

  (let [project-name (loop []
                       (let [s (prompt "Project name (kebab-case)" "my-app")]
                         (if (re-matches #"[a-z][a-z0-9-]*" s)
                           s
                           (do (println (red "  Must be kebab-case")) (recur)))))

        database (select-option "Database"
                                [[:postgresql "Production-ready relational database"]
                                 [:sqlite     "Lightweight file-based database"]
                                 [:h2         "In-memory database (testing/prototyping)"]
                                 [:mysql      "MySQL/MariaDB"]])

        ai-provider (select-option "AI provider"
                                   [[:ollama    "Local AI via Ollama (no API key needed)"]
                                    [:anthropic "Anthropic Claude (requires ANTHROPIC_API_KEY)"]
                                    [:openai    "OpenAI GPT (requires OPENAI_API_KEY)"]
                                    [:none      "Disable AI tooling"]])

        payment (select-option "Payment provider"
                               [[:none   "No payments"]
                                [:mock   "Mock adapter (development/testing)"]
                                [:stripe "Stripe (requires STRIPE_SECRET_KEY)"]
                                [:mollie "Mollie (requires MOLLIE_API_KEY)"]])

        cache (select-option "Cache"
                             [[:none      "No caching"]
                              [:redis     "Redis (requires running Redis instance)"]
                              [:in-memory "In-memory cache (no external deps)"]])

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

(defn- write-config-files!
  "Write generated config files to disk."
  [spec]
  (let [dev-config  (build-config spec "dev")
        test-config (build-config spec "test")
        env-example (build-env-example spec)]

    ;; Ensure directories exist
    (io/make-parents (io/file (root-dir) "resources" "conf" "dev" "config.edn"))
    (io/make-parents (io/file (root-dir) "resources" "conf" "test" "config.edn"))

    (spit (io/file (root-dir) "resources" "conf" "dev" "config.edn") dev-config)
    (spit (io/file (root-dir) "resources" "conf" "test" "config.edn") test-config)
    (spit (io/file (root-dir) ".env.example") env-example)

    (println)
    (println (green "✓") " Generated " (cyan "resources/conf/dev/config.edn"))
    (println (green "✓") " Generated " (cyan "resources/conf/test/config.edn"))
    (println (green "✓") " Generated " (cyan ".env.example"))
    (println)
    (println (dim "Next steps:"))
    (println (dim "  1. Copy .env.example to .env and fill in your values"))
    (println (dim "  2. Run: bb migrate up"))
    (println (dim "  3. Run: bb doctor  (to verify your config)"))))

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
    (let [clean (-> json-str str/trim
                    (str/replace #"^```json\s*" "")
                    (str/replace #"\s*```$" ""))
          data  (json/parse-string clean)]
      {:project-name (or (get data "project-name") "my-app")
       :database     (keyword (or (get data "database") "postgresql"))
       :ai-provider  (keyword (or (get data "ai-provider") "none"))
       :payment      (keyword (or (get data "payment") "none"))
       :cache        (keyword (or (get data "cache") "none"))
       :email        (keyword (or (get data "email") "none"))
       :admin-ui     (if (some? (get data "admin-ui"))
                       (boolean (get data "admin-ui"))
                       true)})
    (catch Exception e
      (println (red (str "Failed to parse AI response: " (.getMessage e))))
      nil)))

(defn wizard-ai [description]
  (println)
  (println (bold "✦ Boundary AI Config Setup"))
  (println (dim (str "Parsing: " description)))
  (println)
  (try
    (let [result (shell {:out :string :err :string}
                        "clojure" "-M" "-m" "wagoe.ai.shell.cli-entry"
                        "setup-parse" description)
          spec   (parse-ai-result (:out result))]
      (if spec
        (do
          (display-summary spec)
          (println)
          (if (confirm "Generate these config files?" true)
            (write-config-files! spec)
            (println (yellow "Cancelled."))))
        (do
          (println (red "Could not parse AI response. Falling back to interactive mode."))
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
              :database     (keyword (or (:database opts) "postgresql"))
              :ai-provider  (keyword (or (:ai-provider opts) "none"))
              :payment      (keyword (or (:payment opts) "none"))
              :cache        (keyword (or (:cache opts) "none"))
              :email        (keyword (or (:email opts) "none"))
              :admin-ui     (not= "false" (or (:admin-ui opts) "true"))}]
    (display-summary spec)
    (println)
    (write-config-files! spec)))

;; =============================================================================
;; Help
;; =============================================================================

(defn- print-help []
  (println (bold "bb setup") " — Interactive config setup wizard for Boundary projects")
  (println)
  (println "Usage:")
  (println "  bb setup                                              Interactive wizard")
  (println "  bb setup ai \"PostgreSQL with Stripe payments\"         NL description mode")
  (println "  bb setup --database postgresql --payment stripe       Non-interactive flags")
  (println)
  (println "Options (non-interactive mode):")
  (println "  --project-name NAME    Project name (default: my-app)")
  (println "  --database DB          postgresql, sqlite, h2, mysql")
  (println "  --ai-provider PROV     ollama, anthropic, openai, none")
  (println "  --payment PAY          none, mock, stripe, mollie")
  (println "  --cache CACHE          none, redis, in-memory")
  (println "  --email EMAIL          none, smtp")
  (println "  --admin-ui BOOL        true, false")
  (println)
  (println "Generated files:")
  (println "  resources/conf/dev/config.edn")
  (println "  resources/conf/test/config.edn")
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
