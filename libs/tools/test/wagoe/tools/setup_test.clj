(ns wagoe.tools.setup-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.config-edn :as config-edn]
            [wagoe.tools.integrate :as integrate]
            [wagoe.tools.setup :as setup]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(defn- lib-source
  "Source of `path` under libs/, from the repo root or from libs/tools."
  [path]
  (or (some #(when (.exists (io/file %)) (slurp %))
            [(str "libs/" path) (str "../" path)])
      (throw (ex-info (str path " not found — cannot compare") {}))))

;; =============================================================================
;; build-config — dev environment
;; =============================================================================

(def minimal-spec
  {:project-name "my-app"
   :database     :postgresql
   :ai-provider  :none
   :payment      :none
   :cache        :none
   :email        :none
   :admin-ui     false})

(deftest ^:unit build-config-dev-test
  (testing "generates valid dev config structure"
    (let [config (setup/build-config minimal-spec "dev")]
      (is (str/includes? config ":active"))
      (is (str/includes? config ":inactive"))
      (is (str/includes? config ":wagoe/settings"))
      (is (str/includes? config "\"my-app-dev\""))))

  (testing "includes postgresql config for dev"
    (let [config (setup/build-config minimal-spec "dev")]
      (is (str/includes? config ":wagoe/postgresql"))
      (is (str/includes? config "POSTGRES_HOST"))))

  (testing "includes HTTP and router for dev"
    (let [config (setup/build-config minimal-spec "dev")]
      (is (str/includes? config ":wagoe/http"))
      (is (str/includes? config ":wagoe/router"))))

  (testing "excludes disabled providers"
    (let [config (setup/build-config minimal-spec "dev")]
      (is (not (str/includes? config ":wagoe/ai-service")))
      (is (not (str/includes? config ":wagoe/payment-provider")))
      (is (not (str/includes? config ":wagoe/cache"))))))

;; =============================================================================
;; build-config — test environment
;; =============================================================================

(deftest ^:unit build-config-test-env-test
  (testing "uses H2 for test regardless of database choice"
    (let [config (setup/build-config minimal-spec "test")]
      (is (str/includes? config ":wagoe/h2"))
      (is (not (str/includes? config ":wagoe/postgresql")))))

  (testing "omits HTTP and router for test"
    (let [config (setup/build-config minimal-spec "test")]
      (is (not (str/includes? config ":wagoe/http")))
      (is (not (str/includes? config ":wagoe/router"))))))

;; =============================================================================
;; H2 — in-memory for test, file-backed for dev (BOU-265)
;; =============================================================================

;; An in-memory H2 database is private to the JVM that opened it. The first-run
;; funnel spans three: `bb migrate up`, `bb create-admin`, and the app. With
;; :memory true in dev, each got its own empty database while every step exited
;; 0 — migrations applied nowhere, the admin user was written nowhere, and the
;; app booted unmigrated. Verified against H2 2.4.240 directly:
;;
;;   process-1 wrote users, rowcount: 1
;;   process-2 CANNOT see it: Table "users" not found (this database is empty)
;;
;; The test profile is a single JVM, so in-memory is correct there.

(def ^:private h2-spec (assoc minimal-spec :database :h2))

(deftest ^:unit h2-dev-config-is-file-backed
  (testing "dev H2 is a file, so separate processes share one database"
    (let [config (setup/build-config h2-spec "dev")]
      (is (str/includes? config ":wagoe/h2"))
      (is (not (str/includes? config ":memory true"))
          "in-memory H2 in dev is invisible to bb migrate / bb create-admin")
      (is (str/includes? config ":db")
          "dev H2 must name a database file")))

  (testing "the path is explicitly relative"
    ;; H2 2.x rejects a bare relative path outright:
    ;;   \"A file path that is implicitly relative to the current working
    ;;    directory is not allowed in the database URL ... Use an absolute
    ;;    path, ~/name, ./name, or the baseDir setting instead. [90011-240]\"
    ;; The adapter builds the URL as (str \"jdbc:h2:\" database-path ...), so a
    ;; bare name would fail at connection time rather than at config time.
    (let [config (setup/build-config h2-spec "dev")]
      (is (re-find #":db\s+\"\./" config)
          "H2 file path must start with ./ or the JDBC URL is rejected")))

  (testing "test env keeps in-memory H2 — one JVM, and it should stay fast"
    (let [config (setup/build-config h2-spec "test")]
      (is (str/includes? config ":wagoe/h2"))
      (is (str/includes? config ":memory true"))))

  (testing "a non-H2 choice still gets in-memory H2 for the test profile"
    ;; build-config maps every database to h2-template for "test"
    (let [config (setup/build-config minimal-spec "test")]
      (is (str/includes? config ":memory true")))))

;; =============================================================================
;; build-config — with all providers enabled
;; =============================================================================

(def full-spec
  {:project-name "shop"
   :database     :postgresql
   :ai-provider  :ollama
   :payment      :stripe
   :cache        :redis
   :email        :smtp
   :admin-ui     true})

(deftest ^:unit build-config-full-spec-test
  (testing "includes all enabled providers for dev"
    (let [config (setup/build-config full-spec "dev")]
      (is (str/includes? config ":wagoe/ai-service"))
      (is (str/includes? config ":provider :ollama"))
      (is (str/includes? config ":wagoe/payment-provider"))
      (is (str/includes? config ":provider :stripe"))
      (is (str/includes? config ":wagoe/cache"))
      (is (str/includes? config ":provider    :redis"))
      (is (str/includes? config ":wagoe.external/smtp"))
      (is (str/includes? config ":wagoe/admin"))))

  (testing "uses mocks and no-ops for test"
    (let [config (setup/build-config full-spec "test")]
      (is (str/includes? config ":provider :no-op"))    ;; AI
      (is (str/includes? config ":provider :mock"))      ;; Payment
      ;; :memory, not :in-memory — the generator writes the canonical spelling,
      ;; so a new project does not start out warning about its own config.
      (is (str/includes? config ":provider    :memory"))))) ;; Cache

;; =============================================================================
;; build-env-example
;; =============================================================================

(deftest ^:unit build-env-example-test
  (testing "always includes HTTP and JWT vars"
    (let [env (setup/build-env-example minimal-spec)]
      (is (str/includes? env "HTTP_PORT=3000"))
      (is (str/includes? env "JWT_SECRET="))))

  (testing "includes PostgreSQL vars for postgresql database"
    (let [env (setup/build-env-example minimal-spec)]
      (is (str/includes? env "POSTGRES_HOST="))
      (is (str/includes? env "POSTGRES_PASSWORD="))))

  (testing "excludes database vars for H2"
    (let [env (setup/build-env-example (assoc minimal-spec :database :h2))]
      (is (not (str/includes? env "POSTGRES_HOST")))))

  (testing "includes AI vars when AI provider set"
    (let [env (setup/build-env-example (assoc minimal-spec :ai-provider :anthropic))]
      (is (str/includes? env "ANTHROPIC_API_KEY="))
      (is (str/includes? env "AI_MODEL="))))

  (testing "includes Stripe vars when Stripe payment set"
    (let [env (setup/build-env-example (assoc minimal-spec :payment :stripe))]
      (is (str/includes? env "STRIPE_SECRET_KEY="))
      (is (str/includes? env "STRIPE_WEBHOOK_SECRET="))))

  (testing "includes Redis vars when redis cache set"
    (let [env (setup/build-env-example (assoc minimal-spec :cache :redis))]
      (is (str/includes? env "REDIS_HOST="))
      (is (str/includes? env "REDIS_PORT="))))

  (testing "excludes provider vars when provider is :none"
    (let [env (setup/build-env-example minimal-spec)]
      (is (not (str/includes? env "ANTHROPIC_API_KEY")))
      (is (not (str/includes? env "STRIPE_SECRET_KEY")))
      (is (not (str/includes? env "REDIS_HOST"))))))

;; =============================================================================
;; Replicate — a provider the rest of the stack already supports (BOU-411)
;; =============================================================================

(deftest ^:unit replicate-is-configurable-test
  (testing "dev config names the provider and its token"
    (let [config (setup/build-config (assoc minimal-spec :ai-provider :replicate) "dev")]
      (is (str/includes? config ":wagoe/ai-service"))
      (is (str/includes? config ":provider :replicate"))
      (is (str/includes? config "REPLICATE_API_TOKEN"))))

  (testing "the token has no default, like the other hosted providers"
    ;; `#or [#env X \"\"]` would satisfy doctor with an empty key and fail at the
    ;; provider instead. A bare #env makes `doctor --ci` say which variable to
    ;; export, which is the contract :anthropic and :openai already have.
    (let [config (setup/build-config (assoc minimal-spec :ai-provider :replicate) "dev")]
      (is (str/includes? config ":api-key  #env REPLICATE_API_TOKEN"))
      (is (not (str/includes? config "#or [#env REPLICATE_API_TOKEN")))))

  (testing "test profile stays on the no-op provider"
    (let [config (setup/build-config (assoc minimal-spec :ai-provider :replicate) "test")]
      (is (str/includes? config ":provider :no-op"))
      (is (not (str/includes? config ":provider :replicate")))))

  (testing ".env.example names the token"
    (let [env (setup/build-env-example (assoc minimal-spec :ai-provider :replicate))]
      (is (str/includes? env "REPLICATE_API_TOKEN="))
      (is (str/includes? env "AI_MODEL=")))))

;; =============================================================================
;; Unknown enum values are refused, not thrown at (BOU-411)
;; =============================================================================

(deftest ^:unit spec-errors-test
  (testing "a valid spec has no errors"
    (is (empty? (setup/spec-errors full-spec)))
    (is (empty? (setup/spec-errors (assoc minimal-spec :ai-provider :replicate)))))

  (testing "an unknown value names the flag, the value and the valid set"
    (let [[msg :as errors] (setup/spec-errors (assoc minimal-spec :ai-provider :bogus))]
      (is (= 1 (count errors)))
      (is (str/includes? msg "--ai-provider"))
      (is (str/includes? msg "bogus"))
      (is (str/includes? msg "replicate"))))

  (testing "every enum flag is covered — each template is a case with no default"
    ;; Before BOU-411 any of these reached `case` and died on "No matching
    ;; clause", a Clojure internal error naming neither the flag nor the choices.
    (doseq [k [:database :ai-provider :payment :cache :email]]
      (is (seq (setup/spec-errors (assoc minimal-spec k :bogus)))
          (str "unknown " k " must be reported")))))

;; =============================================================================
;; The four provider lists agree (BOU-411, same hazard as BOU-281)
;; =============================================================================

(deftest ^:unit setup-offers-every-provider-the-code-can-build
  ;; `build-provider` is the registry. doctor mirrors it (pinned by
  ;; doctor-knows-every-ai-provider-the-code-dispatches-on), and these two
  ;; mirror it again: the wizard's choices and the AI prompt's enum.
  (let [src        (lib-source "ai/src/wagoe/ai/shell/module_wiring.clj")
        dispatched (set (map (comp keyword second)
                             (re-seq #"(?m)^\s+:([a-z-]+)\s+\([a-z-]+/create-" src)))
        ;; :no-op is the registry's way to disable AI; :none is the wizard's,
        ;; and it writes no :wagoe/ai-service block at all. Neither is a
        ;; provider a user picks by name.
        buildable  (disj dispatched :no-op)
        offered    (set (remove #{:none} (get setup/valid-choices :ai-provider)))]

    (testing "the source parsed — otherwise this passes vacuously"
      (is (<= 4 (count dispatched))
          (str "only found " (pr-str dispatched) " in build-provider")))

    (testing "the wizard offers every provider the code can build"
      (is (empty? (set/difference buildable offered))
          (str "build-provider handles " (pr-str (set/difference buildable offered))
               " but bb setup cannot write them")))

    (testing "and offers none the code cannot build"
      (is (empty? (set/difference offered buildable))
          (str "bb setup offers " (pr-str (set/difference offered buildable))
               " but build-provider would throw")))))

(deftest ^:unit setup-prompt-offers-every-provider-test
  ;; The AI path's enum. Omitting a provider here is worse than rejecting it:
  ;; `none` is a valid answer, so a description asking for Replicate came back
  ;; as "AI disabled" and no validation could fire (BOU-411).
  (let [src      (lib-source "ai/src/wagoe/ai/core/prompts.clj")
        enum     (second (re-find #"\\\"ai-provider\\\": \\\"([a-z|-]+)\\\"" src))
        in-prompt (set (map keyword (str/split (or enum "") #"\|")))
        offered   (set (get setup/valid-choices :ai-provider))]

    (testing "the prompt parsed — otherwise this passes vacuously"
      (is (<= 4 (count in-prompt))
          (str "found " (pr-str in-prompt) " in the setup-parse prompt")))

    (testing "the prompt names exactly the choices bb setup accepts"
      (is (= offered in-prompt)
          (str "prompt-only: " (pr-str (set/difference in-prompt offered))
               ", setup-only: " (pr-str (set/difference offered in-prompt)))))

    (testing "and its database default is one that boots unaided (BOU-228)"
      (is (str/includes? src "database defaults to \\\"sqlite\\\"")
          "the AI path must not default to a database needing a server"))))

(deftest ^:unit ai-path-accepts-replicate-test
  ;; The NL path end to end from the JSON a provider returns: the parse must
  ;; keep `replicate` and the config must then render.
  (let [parse #'setup/parse-ai-result
        spec  (parse "{\"project-name\":\"shop\",\"database\":\"sqlite\",\"ai-provider\":\"replicate\"}")]
    (is (= :replicate (:ai-provider spec)))
    (is (str/includes? (setup/build-config spec "dev") ":provider :replicate"))
    (is (str/includes? (setup/build-config spec "dev") "REPLICATE_API_TOKEN")))

  (testing "a provider that answers with something unbuildable is refused, not written"
    (let [parse #'setup/parse-ai-result]
      (is (nil? (parse "{\"ai-provider\":\"gemini\"}"))))))

(deftest ^:unit from-flags-refuses-unknown-value-test
  (testing "exits non-zero and writes nothing"
    (let [exits (atom [])]
      (binding [setup/*exit!* (fn [code] (swap! exits conj code))]
        (let [out (with-out-str (setup/from-flags {:ai-provider "bogus"}))]
          (is (= [1] @exits))
          (is (str/includes? out "bogus"))
          ;; The summary belongs to a run that is going to write files.
          (is (not (str/includes? out "Generated"))))))))

;; =============================================================================
;; Settings template env parameter
;; =============================================================================

(deftest ^:unit settings-template-uses-env-test
  (testing "project name includes env suffix"
    (let [dev-config  (setup/build-config minimal-spec "dev")
          test-config (setup/build-config minimal-spec "test")]
      (is (str/includes? dev-config "\"my-app-dev\""))
      (is (str/includes? test-config "\"my-app-test\"")))))

;; =============================================================================
;; What bb setup enables must be on the classpath the app boots on
;; =============================================================================

(def ^:private generated-deps-path
  "wagoe-cli/resources/wagoe/cli/templates/deps.edn.tmpl")

(defn- top-level-deps
  "The dependency lines of the generated deps.edn `:deps` map.

   Aliases are excluded deliberately: `-M:run` and `(go)` see only `:deps`, and
   a library parked in an alias is exactly how BOU-414 shipped. Comments are
   stripped, because the prose here names the very coordinates this asserts —
   matching them would let a comment stand in for the dependency and the gate
   would pass while the project could not boot."
  []
  (let [src (lib-source generated-deps-path)]
    (->> (str/split-lines (subs src 0 (str/index-of src ":aliases")))
         (remove #(str/starts-with? (str/triml %) ";;"))
         (str/join "\n"))))

(defn- declares?
  "Whether `deps` declares `coordinate` as a dependency, not merely mentions it."
  [deps coordinate]
  (boolean (re-find (re-pattern (str (java.util.regex.Pattern/quote coordinate)
                                     #"\s+\{:mvn/version"))
                    deps)))

(def ^:private choice->coordinate
  "The dependency each `bb setup` value needs once it writes its config key.

   Keyed by the value the user passes, so a new provider added to
   `valid-choices` without a dependency shows up here as a gap."
  {[:database :postgresql] "org.postgresql/postgresql"
   [:database :mysql]      "com.mysql/mysql-connector-j"
   [:database :sqlite]     "org.xerial/sqlite-jdbc"
   [:database :h2]         "com.h2database/h2"
   [:ai-provider :ollama]    "com.wagoe/wagoe-ai"
   [:ai-provider :anthropic] "com.wagoe/wagoe-ai"
   [:ai-provider :openai]    "com.wagoe/wagoe-ai"
   [:ai-provider :replicate] "com.wagoe/wagoe-ai"
   [:payment :mock]        "com.wagoe/wagoe-payments"
   [:payment :stripe]      "com.wagoe/wagoe-payments"
   [:payment :mollie]      "com.wagoe/wagoe-payments"
   [:cache :redis]         "com.wagoe/wagoe-cache"
   [:cache :memory]        "com.wagoe/wagoe-cache"
   ;; still accepted, so a script that passes the old spelling keeps working
   [:cache :in-memory]     "com.wagoe/wagoe-cache"
   [:email :smtp]          "com.wagoe/wagoe-external"})

(deftest ^:unit setup-choices-resolve-in-a-generated-project-test
  (testing "every value bb setup accepts has its dependency in top-level :deps"
    (let [deps (top-level-deps)]
      (doseq [[[flag value] coordinate] choice->coordinate]
        (is (declares? deps coordinate)
            (str "bb setup --" (name flag) " " (name value)
                 " writes a config key, but " coordinate
                 " is not in the generated project's top-level :deps."
                 " The project will not boot (BOU-414)")))))

  ;; Both loops below are only as good as what they iterate. An empty table or
  ;; an empty valid-choices would make every assertion above vacuous and the
  ;; gate would report success having checked nothing.
  (testing "there is something to check"
    (is (seq (top-level-deps)))
    (is (<= 14 (count choice->coordinate)))
    (is (<= 5 (count setup/valid-choices))))

  (testing "the table covers every choice, so a new provider cannot slip through"
    (doseq [[flag values] setup/valid-choices
            value         values
            ;; :none is the only value that writes no config key.
            :when (not= :none value)]
      (is (contains? choice->coordinate [flag value])
          (str "bb setup --" (name flag) " " (name value)
               " is accepted but this test names no dependency for it —"
               " add one, or confirm it needs none")))))

;; =============================================================================
;; A chosen AI provider tells you what it needs (BOU-415)
;; =============================================================================

(deftest ^:unit ai-provider-prerequisites-cover-every-provider-test
  (testing "every provider bb setup accepts has closing steps; :none has none"
    (is (= (disj (set (:ai-provider setup/valid-choices)) :none)
           (set (keys setup/ai-provider-prerequisites))))
    (is (nil? (setup/ai-provider-prerequisites :none))))

  (testing "hosted providers name the exact variable their config reads"
    (doseq [[provider env-var] {:anthropic "ANTHROPIC_API_KEY"
                                :openai    "OPENAI_API_KEY"
                                :replicate "REPLICATE_API_TOKEN"}]
      (is (some #(str/includes? % env-var)
                (setup/ai-provider-prerequisites provider))
          (str (name provider) " steps must name " env-var
               " — the variable doctor will ask for"))))

  (testing "the ollama pull command fetches the model the config names"
    (let [config (setup/build-config (assoc minimal-spec :ai-provider :ollama) "dev")
          pull   (some #(when (str/includes? % "ollama pull") %)
                       (setup/ai-provider-prerequisites :ollama))]
      (is (some? pull))
      (is (str/includes? config (str/replace pull #"^.*ollama pull " ""))
          "telling the user to pull a model the config does not use helps nobody"))))

;; =============================================================================
;; setup regenerates what wagoe new wrote — it must not write less (BOU-416)
;; =============================================================================

(deftest ^:unit setup-dev-config-covers-wagoe-new-template-test
  (testing "every :active key the wagoe new dev template writes, setup writes too"
    ;; Only the :active section counts. Both generators also have :inactive,
    ;; and a key demoted there is the regression this test exists to catch —
    ;; scanning the whole string would call that parity.
    (let [active-of #(let [start (str/index-of % ":active")
                           end   (str/index-of % ":inactive")]
                       (is (and start end (< start end))
                           "config must have :active before :inactive — cannot slice")
                       (subs % start end))
          strip     #(str/replace % #"(?m)^\s*;;.*$" "")
          keys-of   #(set (re-seq #":wagoe[./][a-z-]+\b" (strip (active-of %))))
          template  (keys-of (lib-source "wagoe-cli/resources/wagoe/cli/templates/dev-config.edn.tmpl"))
          setup*    (keys-of (setup/build-config (assoc full-spec :database :sqlite) "dev"))]
      (is (<= 5 (count template))
          "template keys parsed — an empty set would make the parity below vacuous")
      (is (empty? (set/difference template setup*))
          (str "bb setup regenerates the whole config, so a key it lacks is "
               "un-shipped for anyone who runs it. Missing: "
               (set/difference template setup*))))))

(deftest ^:unit the-generator-never-writes-a-deprecated-provider
  ;; The generator is the canonical way to write a config, so anything it emits
  ;; must be what the runtime wants today — not a spelling that boots with a
  ;; deprecation warning (BOU-436 review).
  (doseq [cache [:memory :in-memory :redis]
          env   ["dev" "test"]]
    (let [config (setup/build-config (assoc full-spec :cache cache) env)]
      (is (not (str/includes? config ":provider    :in-memory"))
          (str "--cache " cache " (" env ") wrote the deprecated spelling"))
      (is (not (str/includes? config ":redis-streams"))
          (str "--cache " cache " (" env ") wrote the deprecated events spelling")))))

;; =============================================================================
;; BOU-447: the generated config has to work on a local HTTP dev box
;; =============================================================================

(deftest ^:unit generated-settings-turn-off-secure-cookies
  ;; :secure-cookies? defaults to true (HTTPS-only), and both configs setup
  ;; writes are served over plain HTTP — so omitting the key sent the session
  ;; cookie with Secure and nobody could stay logged in. `wagoe new` writes it
  ;; for the same reason.
  (doseq [env ["dev" "test"]]
    (let [config (setup/build-config minimal-spec env)]
      (is (str/includes? config ":secure-cookies?")
          (str env " config must decide this rather than inherit the HTTPS default"))
      (is (re-find #":secure-cookies\?\s+false" config)
          (str env " config is served over HTTP")))))

(deftest ^:unit every-include-the-config-names-is-written
  ;; `#include "admin/users.edn"` was emitted without the file, and Aero throws
  ;; on a missing include — so an admin-enabled project could not read its own
  ;; config.
  (doseq [env ["dev" "test" "prod"]]
    (let [config   (setup/build-config (assoc minimal-spec :admin-ui true) env)
          includes (map second (re-seq #"#include\s+\"([^\"]+)\"" config))]
      (is (seq includes) "admin config should reference the entity file")
      (doseq [inc includes]
        (is (some? (io/resource (str "wagoe/tools/" inc)))
            (str inc " is included by the " env " config but wagoe-tools ships no such resource")))))

  (testing "and the resource is readable EDN naming the entity"
    (let [entity (read-string (slurp (io/resource "wagoe/tools/admin/users.edn")))]
      (is (contains? entity :users)))))

;; =============================================================================
;; BOU-499: a prod profile that is production-shaped
;; =============================================================================

(defn- read-config
  "Parse generated config text. Tags are kept as `(tag value)`, so `#env X`
   reads as `(env X)` and a test can tell a variable from a literal."
  [text]
  (edn/read-string {:default (fn [tag value] (list tag value))} text))

(defn- env-ref? [v]
  (and (seq? v) (= 'env (first v))))

(deftest ^:unit every-env-logs-through-slf4j
  ;; Without :provider, :wagoe/logging is the no-op adapter and :level governs
  ;; nothing (BOU-528).
  (doseq [env ["dev" "test" "prod"]]
    (is (= :slf4j (get-in (read-config (setup/build-config full-spec env))
                          [:active :wagoe/logging :provider]))
        env)))

(deftest ^:unit prod-config-is-production-shaped
  (doseq [db (:database setup/valid-choices)]
    (let [active (:active (read-config (setup/build-config (assoc full-spec :database db) "prod")))
          db-key (keyword "wagoe" (name db))
          db-cfg (get active db-key)]
      (testing (str "--database " (name db))
        (is (true? (get-in active [:wagoe/settings :secure-cookies?]))
            "prod is served behind TLS; auth cookies must carry Secure")
        (is (= :info (get-in active [:wagoe/logging :level])))
        (is (not (contains? active :wagoe/dashboard))
            "the platform refuses dev-only modules outside :dev")
        (is (not (contains? active :wagoe/dev-error-enricher)))
        (is (not (contains? active :wagoe/ai-service))
            "the AI service is a build-time tool")
        (is (map? db-cfg) (str db-key " must be the active database"))
        (is (not (contains? db-cfg :migrate-on-start?))
            "prod migrations are a reviewed deploy step, and replicas race (BOU-485)")
        (is (not (:memory db-cfg)))
        (doseq [k [:host :dbname :user :password :db]
                :when (contains? db-cfg k)]
          (is (env-ref? (get db-cfg k))
              (str db-key " " k " must come from the environment, not a literal")))))))

(deftest ^:unit prod-config-takes-secrets-from-the-environment
  (let [active (:active (read-config (setup/build-config full-spec "prod")))]
    (is (env-ref? (get-in active [:wagoe/payment-provider :api-key])))
    (is (env-ref? (get-in active [:wagoe/cache :password])))
    (is (env-ref? (get-in active [:wagoe.external/smtp :password])))
    (is (true? (get-in active [:wagoe.external/smtp :tls?])))))

(deftest ^:unit setup-writes-a-prod-config
  (let [dir (fs/create-temp-dir)]
    (try
      (with-redefs [setup/root-dir (constantly (str dir))]
        (with-out-str (setup/from-flags {:database "postgresql" :admin-ui "true"})))
      (let [f (fs/file dir "resources" "conf" "prod" "config.edn")]
        (is (fs/exists? f) "bb setup must write resources/conf/prod/config.edn")
        (when (fs/exists? f)
          (is (true? (get-in (read-config (slurp f)) [:active :wagoe/settings :secure-cookies?])))))
      (is (fs/exists? (fs/file dir "resources" "conf" "prod" "admin" "users.edn"))
          "the prod config's #include must resolve")
      (finally (fs/delete-tree dir)))))

(deftest ^:unit setup-keeps-an-existing-prod-config
  (let [dir   (fs/create-temp-dir)
        conf  (fs/file dir "resources" "conf" "prod" "config.edn")
        users (fs/file dir "resources" "conf" "prod" "admin" "users.edn")]
    (try
      (fs/create-dirs (fs/parent users))
      (spit conf "{:active {:my/service {}}}")
      (spit users "{:users {:label \"Mine\"}}")
      (with-redefs [setup/root-dir (constantly (str dir))]
        (with-out-str (setup/from-flags {:database "postgresql" :admin-ui "true"})))
      (is (= "{:active {:my/service {}}}" (slurp conf))
          "a hand-maintained prod config must survive bb setup")
      (is (= "{:users {:label \"Mine\"}}" (slurp users)))
      (is (fs/exists? (fs/file dir "resources" "conf" "dev" "config.edn"))
          "dev is still generated")
      (finally (fs/delete-tree dir)))))

;; =============================================================================
;; Existing config is merged into, never clobbered (BOU-404, BOU-532)
;; =============================================================================

(defn- wagoe-new-project!
  "A dir holding what `wagoe new` writes, plus a module `bb scaffold integrate`
   added. Returns the dir."
  []
  (let [dir    (fs/create-temp-dir)
        render #(str/replace (lib-source (str "wagoe-cli/resources/wagoe/cli/templates/" %))
                             "{{project-name}}" "shop")]
    (doseq [env ["dev" "test"]]
      (fs/create-dirs (fs/file dir "resources" "conf" env))
      (spit (fs/file dir "resources" "conf" env "config.edn")
            (render (str env "-config.edn.tmpl"))))
    (spit (fs/file dir ".env.example") (render "env.example.tmpl"))
    (integrate/write-config! (str dir) ":wagoe/product"
                             (integrate/generate-config-snippet "product") {})
    dir))

(defn- snapshot [dir]
  (into {} (for [f (fs/glob dir "**" {:hidden true}) :when (fs/regular-file? f)]
             [(str (fs/relativize dir f)) (slurp (fs/file f))])))

(defn- tree
  "Every file (with its text) and every directory under `dir`."
  [dir]
  (into {} (for [f (fs/glob dir "**" {:hidden true})]
             [(str (fs/relativize dir f)) (if (fs/directory? f) :dir (slurp (fs/file f)))])))

(def ^:private enter-through
  "Enter for every question the wizard can ask."
  (apply str (repeat 12 "\n")))

(defn- conf [dir env]
  (read-config (slurp (fs/file dir "resources" "conf" env "config.edn"))))

(defn- run-setup
  "Run `bb setup` with `args` and `stdin` in `dir`. Returns [exit-code output]."
  [dir stdin & args]
  (let [exit (atom nil)
        out  (with-redefs [setup/root-dir (constantly (str dir))]
               (binding [setup/*exit!* #(reset! exit %)]
                 (with-out-str (with-in-str stdin (apply setup/-main args)))))]
    [@exit out]))

(deftest ^:unit wizard-on-closed-stdin-writes-nothing
  (let [dir (wagoe-new-project!)]
    (try
      (let [before     (snapshot dir)
            [exit out] (run-setup dir "")]
        (is (= 1 exit) "EOF is not consent")
        (is (str/includes? out "stdin closed"))
        (is (= before (snapshot dir))))
      (finally (fs/delete-tree dir)))))

(deftest ^:unit wizard-names-the-files-it-would-change-before-asking
  (let [dir (wagoe-new-project!)]
    (try
      (let [before     (snapshot dir)
            ;; Enter through every question, then decline.
            [exit out] (run-setup dir "\n\n\n\n\n\n\n\nn\n")
            summary    (-> out (str/split #"Config Summary") second
                           (str/split #"Generate these") first)]
        (is (nil? exit))
        (is (str/includes? summary "resources/conf/dev/config.edn"))
        (is (str/includes? summary "resources/conf/test/config.edn"))
        (is (str/includes? summary ".env.example"))
        (is (= before (snapshot dir)) "declined, so nothing written"))
      (finally (fs/delete-tree dir)))))

(deftest ^:unit flag-mode-merges-into-a-wagoe-new-project
  (let [dir (wagoe-new-project!)]
    (try
      (let [[exit _] (run-setup dir "" "--database" "sqlite" "--ai-provider" "ollama")
            dev      (:active (conf dir "dev"))
            test     (:active (conf dir "test"))
            env-ex   (slurp (fs/file dir ".env.example"))]
        (is (nil? exit))
        (is (true? (get-in dev [:wagoe/sqlite :migrate-on-start?])))
        (is (= "shop-dev.db" (get-in dev [:wagoe/sqlite :db])))
        (is (true? (get-in test [:wagoe/h2 :migrate-on-start?])))
        (is (contains? dev :wagoe/product) "integrate-written key survives")
        (is (contains? test :wagoe/product))
        (is (= :ollama (get-in dev [:wagoe/ai-service :provider])) "what was asked is written")
        (is (str/includes? env-ex "JWT_SECRET=change-me-to-a-32-char-minimum-secret"))
        (is (str/includes? env-ex "OLLAMA_URL=")))
      (finally (fs/delete-tree dir)))))

(deftest ^:unit flag-mode-switching-database-keeps-the-old-one-inactive
  (let [dir (wagoe-new-project!)]
    (try
      (run-setup dir "" "--database" "postgresql")
      (let [{:keys [active inactive]} (conf dir "dev")]
        (is (contains? active :wagoe/postgresql))
        (is (not (contains? active :wagoe/sqlite)) "two active databases do not boot")
        (is (true? (get-in inactive [:wagoe/sqlite :migrate-on-start?])))
        (is (contains? active :wagoe/product)))
      (finally (fs/delete-tree dir)))))

(deftest ^:unit setup-twice-changes-nothing-the-second-time
  (let [dir (wagoe-new-project!)]
    (try
      (run-setup dir "" "--database" "sqlite" "--cache" "redis")
      (let [before (snapshot dir)]
        (run-setup dir "" "--database" "sqlite" "--cache" "redis")
        (is (= before (snapshot dir))))
      (finally (fs/delete-tree dir)))))

(deftest ^:unit keys-setup-and-integrate-add-line-up-with-the-rest
  ;; They sat at column 2 in a `wagoe new` config whose entries are at column
  ;; 3, with :active's closing brace alone on a line (BOU-580).
  (let [dir (wagoe-new-project!)]
    (try
      (run-setup dir "" "--database" "sqlite" "--ai-provider" "ollama" "--cache" "redis")
      (doseq [env ["dev" "test"]]
        (let [text (slurp (fs/file dir "resources" "conf" env "config.edn"))
              cols (->> (config-edn/entries text ":active")
                        (map #(- (:start %) 1 (or (str/last-index-of text "\n" (:start %)) -1))))]
          (is (contains? (set (map :key (config-edn/entries text ":active"))) ":wagoe/product") env)
          (is (= #{3} (set cols)) text)
          (is (not (re-find #"(?m)^\s*\}\s*$" text)) text)))
      (finally (fs/delete-tree dir)))))

(deftest ^:unit unmergeable-config-refuses-and-writes-nothing
  (let [dir (fs/create-temp-dir)]
    (try
      (fs/create-dirs (fs/file dir "resources" "conf" "dev"))
      (spit (fs/file dir "resources" "conf" "dev" "config.edn") "{:my/key 1}")
      (let [[exit out] (run-setup dir "" "--database" "sqlite")]
        (is (= 1 exit))
        (is (str/includes? out "resources/conf/dev/config.edn"))
        (is (= {"resources/conf/dev/config.edn" "{:my/key 1}"} (snapshot dir))))
      (finally (fs/delete-tree dir)))))

(deftest ^:unit fresh-dir-gets-every-file
  (let [dir (fs/create-temp-dir)]
    (try
      (let [[exit _] (run-setup dir "" "--database" "sqlite")]
        (is (nil? exit))
        (is (= #{"resources/conf/dev/config.edn" "resources/conf/test/config.edn"
                 "resources/conf/prod/config.edn" ".env.example"
                 "resources/conf/dev/admin/users.edn" "resources/conf/test/admin/users.edn"
                 "resources/conf/prod/admin/users.edn"}
               (set (keys (snapshot dir)))))
        (is (= (setup/build-config (setup/with-defaults (setup/from-flags-spec {:database "sqlite"})) "dev")
               (slurp (fs/file dir "resources" "conf" "dev" "config.edn")))))
      (finally (fs/delete-tree dir)))))

;; -----------------------------------------------------------------------------
;; Only what was asked changes (review of #592)
;; -----------------------------------------------------------------------------

(defn- conf-file [dir env] (fs/file dir "resources" "conf" env "config.edn"))

(defn- with-project
  "Run `f` on a wagoe-new project dir, deleting it afterwards."
  [f]
  (let [dir (wagoe-new-project!)]
    (try (f dir) (finally (fs/delete-tree dir)))))

(deftest ^:unit an-unasked-database-is-left-alone
  (with-project
    (fn [dir]
      (run-setup dir "" "--database" "postgresql")
      (let [[exit _] (run-setup dir "" "--payment" "mock")
            {:keys [active]} (conf dir "dev")]
        (is (nil? exit))
        (is (contains? active :wagoe/postgresql) "--payment is not --database sqlite")
        (is (not (contains? active :wagoe/sqlite)))
        (is (= :mock (get-in active [:wagoe/payment-provider :provider])))))))

(deftest ^:unit a-test-profile-on-postgresql-survives
  (with-project
    (fn [dir]
      (spit (conf-file dir "test") "{:active {:wagoe/postgresql {:host \"db\"}\n          :wagoe/product {:enabled? true}}}\n")
      (run-setup dir "" "--database" "sqlite")
      (let [{:keys [active]} (conf dir "test")]
        (is (= {:host "db"} (:wagoe/postgresql active)))
        (is (not (contains? active :wagoe/h2)))))))

(deftest ^:unit enter-through-the-wizard-changes-nothing
  (with-project
    (fn [dir]
      (spit (conf-file dir "dev")
            (config-edn/insert-into (slurp (conf-file dir "dev")) ":active"
                                    "\n   :wagoe/ai-service {:provider :anthropic}"))
      (let [before     (tree dir)
            [exit out] (run-setup dir enter-through)]
        (is (nil? exit) out)
        (is (= before (tree dir)) "a menu default is not an answer")))))

(deftest ^:unit enter-through-the-wizard-changes-nothing-without-dev-or-prod
  (with-project
    (fn [dir]
      (fs/delete (conf-file dir "dev"))
      (let [before     (tree dir)
            [exit out] (run-setup dir enter-through)]
        (is (nil? exit) out)
        (is (= before (tree dir)) "no dev config is not a licence to pick ollama or create prod")))))

(deftest ^:unit a-switched-database-migrates-on-start-outside-prod
  (with-project
    (fn [dir]
      (run-setup dir "" "--database" "postgresql")
      (run-setup dir "" "--database" "postgresql" "--prod" "true")
      (is (true? (get-in (conf dir "dev") [:active :wagoe/postgresql :migrate-on-start?])))
      (is (nil? (get-in (conf dir "prod") [:active :wagoe/postgresql :migrate-on-start?]))))))

(deftest ^:unit prod-answers-go-to-prod-only
  (with-project
    (fn [dir]
      (let [[exit out] (run-setup dir "" "--prod" "true")]
        (is (nil? exit) out))
      (spit (conf-file dir "prod")
            (config-edn/insert-into (slurp (conf-file dir "prod")) ":active"
                                    "\n  :my/hand-edit {:kept? true}"))
      (let [dev-before  (slurp (conf-file dir "dev"))
            test-before (slurp (conf-file dir "test"))
            [exit out]  (run-setup dir "" "--database" "postgresql" "--prod" "true")
            prod        (conf dir "prod")]
        (is (nil? exit) out)
        (is (= dev-before (slurp (conf-file dir "dev"))) "dev is not the profile asked for")
        (is (= test-before (slurp (conf-file dir "test"))))
        (is (contains? (:active prod) :wagoe/postgresql) out)
        (is (env-ref? (get-in prod [:active :wagoe/postgresql :host])))
        (is (contains? (:inactive prod) :wagoe/sqlite) "two active databases do not boot")
        (is (= {:kept? true} (get-in prod [:active :my/hand-edit])) "merged, not regenerated")
        (is (= {:enabled? true} (get-in prod [:active :wagoe/product])))
        (is (not (str/includes? out "Kept existing")) out)))))

(deftest ^:unit a-moved-database-lands-in-inactive-not-a-trailing-comment
  (with-project
    (fn [dir]
      (spit (conf-file dir "dev") "{:active {:wagoe/sqlite {:db \"x.db\"}}}\n;; end }\n")
      (let [[exit out] (run-setup dir "" "--database" "postgresql")
            text (slurp (conf-file dir "dev"))]
        (is (nil? exit) out)
        (is (= {:db "x.db"} (get-in (read-config text) [:inactive :wagoe/sqlite])))
        (is (str/ends-with? text ";; end }\n"))))))

(deftest ^:unit crlf-stays-crlf
  (with-project
    (fn [dir]
      (spit (conf-file dir "dev") (str/replace (slurp (conf-file dir "dev")) "\n" "\r\n"))
      (run-setup dir "" "--cache" "redis")
      (let [text (slurp (conf-file dir "dev"))]
        (is (str/includes? text ":wagoe/cache"))
        (is (not (re-find #"[^\r]\n" text)))))))

(def ^:private refused {:exit 1 :names-dev? true :unchanged? true})

(defn- refusal
  "How setup with `args` ended in `dir`, in the shape of `refused`."
  [dir args]
  (let [before     (snapshot dir)
        [exit out] (apply run-setup dir "" args)]
    {:exit       exit
     :names-dev? (str/includes? out "resources/conf/dev/config.edn")
     :unchanged? (= before (snapshot dir))}))

(deftest ^:unit the-safety-net-refuses-a-lossy-or-unreadable-merge
  (testing "a merge that drops a key"
    (with-project
      (fn [dir]
        (with-redefs [setup/merge-config (fn [old _ _] {:text (str/replace old ":wagoe/product" ":wagoe/prodUCT")
                                                        :changes [] :moved #{}})]
          (is (= refused (refusal dir ["--cache" "redis"])))))))
  (testing "a merge that does not read"
    (with-project
      (fn [dir]
        (with-redefs [setup/merge-config (fn [old _ _] {:text (str old "}") :changes [] :moved #{}})]
          (is (= refused (refusal dir ["--cache" "redis"])))))))
  (testing "a config that does not read as EDN"
    (with-project
      (fn [dir]
        (spit (conf-file dir "dev") "{:active {:a/k #\"re\"}}")
        (is (= refused (refusal dir ["--cache" "redis"]))))))
  (testing "an :active that is not a literal map"
    (doseq [v ["#include \"active.edn\"" "#profile {:dev {}}" "#merge [{} {}]"]]
      (with-project
        (fn [dir]
          (spit (conf-file dir "dev") (str "{:active " v "}"))
          (is (= refused (refusal dir ["--cache" "redis"]))))))))

(deftest ^:unit a-read-only-target-writes-nothing
  (testing "read-only file"
    (with-project
      (fn [dir]
        (.setWritable (conf-file dir "dev") false)
        (is (= refused (refusal dir ["--cache" "redis"]))))))
  (testing "read-only directory: the temp files go first, so nothing is renamed"
    (with-project
      (fn [dir]
        ;; The root is read-only, so .env.example fails after prod's
        ;; directory was already created for its temp file.
        (let [root   (fs/file dir)
              before (tree dir)]
          (.setWritable root false)
          (try
            (let [[exit out] (run-setup dir "" "--cache" "redis" "--prod" "true")
                  report     (second (str/split out #"Nothing was written" 2))]
              (is (= 1 exit))
              (is (some? report) out)
              ;; No admin users.edn: prod takes dev's modules, and dev has no admin.
              (doseq [p ["resources/conf/prod/config.edn" ".env.example"]]
                (is (str/includes? (str report) p) (str p " is named as not written")))
              (is (= before (tree dir)) "and no directory is left behind"))
            (finally (.setWritable root true))))))))

;; -----------------------------------------------------------------------------
;; Second adversarial review of #592
;; -----------------------------------------------------------------------------

(deftest ^:unit an-answer-that-matches-keeps-the-existing-map
  (with-project
    (fn [dir]
      (let [tst "{:active\n {:wagoe/h2 {:memory true}\n  :wagoe/cache {:provider :redis :host \"localhost\" :port 6379}\n  :wagoe/payment-provider {:provider :stripe :api-key \"sk_test_x\"}\n  :wagoe/ai-service {:provider :anthropic :api-key #env K}}\n :inactive {}}\n"
            dev (config-edn/insert-into (slurp (conf-file dir "dev")) ":active"
                                        "\n  :wagoe/cache {:provider :redis :host \"cache.internal\"}")]
        (spit (conf-file dir "test") tst)
        (spit (conf-file dir "dev") dev)
        (run-setup dir "" "--cache" "redis" "--payment" "stripe" "--ai-provider" "anthropic")
        (is (= tst (slurp (conf-file dir "test"))) "test is never downgraded to memory, mock or no-op")
        (is (= "cache.internal" (get-in (conf dir "dev") [:active :wagoe/cache :host]))
            "same provider, so the customised map stays")))))

(deftest ^:unit a-file-changed-after-planning-is-not-overwritten
  (with-project
    (fn [dir]
      (let [summary @#'setup/display-summary
            racer   "{:active {:wagoe/sqlite {:db \"theirs\"}}}\n"]
        (with-redefs [setup/display-summary (fn [& args]
                                              (apply summary args)
                                              (spit (conf-file dir "dev") racer))]
          (let [test-before (slurp (conf-file dir "test"))
                [exit out]  (run-setup dir "" "--cache" "redis")]
            (is (= 1 exit))
            (is (str/includes? out "changed on disk since planning"))
            (is (= racer (slurp (conf-file dir "dev"))))
            (is (= test-before (slurp (conf-file dir "test"))) "nothing written")))))))

(deftest ^:unit a-string-that-looks-like-a-comment-is-not-moved
  (with-project
    (fn [dir]
      (let [dev "{:active\n {:wagoe/settings {:note \"x\n; y\"}\n  :wagoe/postgresql {:host \"db\"}\n  ;; \"} ;\n  :wagoe/http {:port 3000}}\n :inactive {}}\n"]
        (spit (conf-file dir "dev") dev)
        (let [[exit out] (run-setup dir "" "--database" "sqlite")
              {:keys [active inactive]} (conf dir "dev")]
          (is (nil? exit) out)
          (is (= {:note "x\n; y"} (:wagoe/settings active)))
          (is (= {:port 3000} (:wagoe/http active)))
          (is (= {:host "db"} (:wagoe/postgresql inactive))))))))

(deftest ^:unit the-safety-net-compares-values
  (with-project
    (fn [dir]
      (with-redefs [setup/merge-config (fn [old _ _] {:text (str/replace old "shop-dev.db" "other.db")
                                                      :changes [] :moved #{}})]
        (is (= refused (refusal dir ["--cache" "redis"])))))))

(deftest ^:unit mixed-line-endings-do-not-drift
  (with-project
    (fn [dir]
      (let [mixed "{:active\r\n {:wagoe/sqlite {:db \"x\"}\n  :wagoe/settings {:banner \"a\nb\"}}\r\n :inactive {}}\r\n"]
        (spit (conf-file dir "dev") mixed)
        (run-setup dir "" "--database" "sqlite")
        (is (= mixed (slurp (conf-file dir "dev"))) "a no-op run writes nothing")
        (run-setup dir "" "--cache" "memory")
        (is (= {:banner "a\nb"} (get-in (conf dir "dev") [:active :wagoe/settings]))
            "a newline inside a string stays a bare newline")))))

(deftest ^:unit a-rewrite-keeps-mode-and-symlink
  (with-project
    (fn [dir]
      (let [path  (.toPath (conf-file dir "dev"))
            perms (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------")]
        (java.nio.file.Files/setPosixFilePermissions path perms)
        (run-setup dir "" "--cache" "memory")
        (is (= perms (java.nio.file.Files/getPosixFilePermissions
                      path (make-array java.nio.file.LinkOption 0)))))))
  (with-project
    (fn [dir]
      (let [shared (fs/file dir "shared-dev.edn")
            link   (.toPath (conf-file dir "dev"))]
        (fs/move (conf-file dir "dev") shared)
        (java.nio.file.Files/createSymbolicLink link (.toPath shared)
                                                (make-array java.nio.file.attribute.FileAttribute 0))
        (run-setup dir "" "--cache" "memory")
        (is (java.nio.file.Files/isSymbolicLink link))
        (is (str/includes? (slurp shared) ":wagoe/cache") "the link's target is what changes")))))

;; -----------------------------------------------------------------------------
;; A prod profile made later is the project's, not a new one's (BOU-564)
;; -----------------------------------------------------------------------------

(deftest ^:unit the-wizard-offers-the-projects-own-name
  (with-project
    (fn [dir]
      (let [[_ out] (run-setup dir enter-through)]
        (is (re-find #"Project name \(kebab-case\)\S*\s+\[shop\]" out) out)))))

(deftest ^:unit prod-carries-over-what-dev-has
  (with-project
    (fn [dir]
      (spit (conf-file dir "dev")
            (config-edn/insert-into
             (slurp (conf-file dir "dev")) ":active"
             (str "\n   :wagoe/ai-service {:provider :replicate :api-key #env REPLICATE_API_TOKEN}"
                  "\n   :wagoe/events\n   {:provider :memory}"
                  "\n   :wagoe/payment-provider {:provider :mock}"
                  "\n   :wagoe/cache {:provider :memory :default-ttl 300}"
                  "\n   :wagoe/jobs {:provider :memory :workers {:count 1}}"
                  "\n   :wagoe/realtime {:provider :memory}"
                  "\n   :my/gateway {:provider :mock}"
                  "\n   :wagoe/dashboard {:port 9999}")))
      (let [dev-before  (slurp (conf-file dir "dev"))
            test-before (slurp (conf-file dir "test"))
            env-before  (slurp (fs/file dir ".env.example"))
            [exit out]  (run-setup dir "" "--prod" "true")
            prod        (:active (conf dir "prod"))]
        (is (nil? exit) out)
        (is (= "shop-prod" (get-in prod [:wagoe/settings :name])) "the project's name, not my-app")
        (is (contains? prod :wagoe/sqlite) "dev's database")
        (is (env-ref? (get-in prod [:wagoe/sqlite :db])))
        (is (= {:enabled? true} (:wagoe/product prod))
            "a module integrated before prod existed")
        (is (not (contains? prod :wagoe/ai-service)) "no AI in prod, as ai-template has it")
        (testing "no stand-in reaches prod (BOU-564 review)"
          (let [text (slurp (conf-file dir "prod"))]
            (doseq [bad [":mock" ":memory" ":in-memory"]]
              (is (not (str/includes? text bad)) bad)))
          (is (not (contains? prod :wagoe/payment-provider)) "the mock accepts any webhook as paid")
          (is (= :redis (get-in prod [:wagoe/cache :provider])))
          (is (= :db (get-in prod [:wagoe/jobs :provider])))
          (is (= :redis (get-in prod [:wagoe/realtime :provider])))
          (is (not (contains? prod :my/gateway)))
          (is (str/includes? out ":my/gateway") "and says what it left out"))
        (is (= :redis (get-in prod [:wagoe/events :provider])) "one process in dev, several in prod")
        (is (env-ref? (get-in prod [:wagoe/events :host])))
        (is (str/includes? (slurp (conf-file dir "prod")) "\n  :wagoe/events\n  {:provider :redis")
            "at the column of the keys around it")
        (is (not (contains? prod :wagoe/dashboard)) "the platform refuses it outside :dev")
        (is (not (contains? prod :wagoe/dev-error-enricher)))
        (is (= dev-before (slurp (conf-file dir "dev"))))
        (is (= test-before (slurp (conf-file dir "test"))))
        (testing ".env.example names the Redis variables prod's event bus reads"
          (let [env-ex (slurp (fs/file dir ".env.example"))]
            (is (str/starts-with? env-ex env-before) "only added to")
            (doseq [v ["REDIS_HOST" "REDIS_PORT" "REDIS_PASSWORD"]]
              (is (= 1 (count (re-seq (re-pattern (str "(?m)^" v "=")) env-ex))) v))))))))

(deftest ^:unit an-explicit-answer-still-keeps-the-mock-out-of-prod
  (with-project
    (fn [dir]
      (spit (conf-file dir "dev")
            (config-edn/insert-into (slurp (conf-file dir "dev")) ":active"
                                    "\n   :wagoe/events {:provider :memory}"))
      (let [[exit out] (run-setup dir "" "--prod" "true" "--cache" "redis")
            text       (slurp (conf-file dir "prod"))
            env-ex     (slurp (fs/file dir ".env.example"))]
        (is (nil? exit) out)
        (is (not (str/includes? text ":mock")))
        (is (= 1 (count (re-seq #"(?m)^REDIS_HOST=" env-ex)))
            "cache and event bus share the Redis variables")))))

(deftest ^:unit prod-refuses-what-prod-never-gets
  ;; Setup said "Payments: mock", exited 0 and wrote it nowhere (BOU-577).
  (doseq [args [["--payment" "mock"] ["--ai-provider" "anthropic"]]]
    (testing (str/join " " args)
      (with-project
        (fn [dir]
          (let [before     (tree dir)
                [exit out] (apply run-setup dir "" "--prod" "true" args)]
            (is (= 1 exit) out)
            (is (str/includes? out (str "--" (subs (first args) 2) " " (second args))) out)
            (is (str/includes? out "never written to prod") out)
            (is (str/includes? out "without --prod") out)
            (is (= before (tree dir)))))))))

(deftest ^:unit the-wizard-asks-about-prod-first
  (with-project
    (fn [dir]
      (let [dev-before (slurp (conf-file dir "dev"))
            ;; prod? yes, then Enter through the rest, then write.
            [exit out] (run-setup dir (str "y\n" (apply str (repeat 12 "\n"))))
            prod-q     (str/index-of out "resources/conf/prod/config.edn")
            db-q       (str/index-of out "Database")]
        (is (nil? exit) out)
        (is (and prod-q db-q (< prod-q db-q)) "asked before the questions it decides")
        (is (str/includes? out "for prod") "and the questions say whose they are")
        (is (not (str/includes? out "AI provider")) "prod never gets AI, so it is not asked")
        (is (not (str/includes? out "Mock adapter")) "nor the mock payment provider")
        (is (fs/exists? (conf-file dir "prod")) out)
        (is (= dev-before (slurp (conf-file dir "dev"))))))))

(deftest ^:unit a-fresh-prod-has-no-mock-payments
  (is (not (str/includes? (setup/build-config (assoc full-spec :payment :mock) "prod") ":mock"))))

(deftest ^:unit prod-gets-devs-admin-and-its-entity-files
  (with-project
    (fn [dir]
      (let [entity "{:invoices {:label \"Invoices\"}}\n"]
        (fs/create-dirs (fs/file dir "resources" "conf" "dev" "admin"))
        (spit (fs/file dir "resources" "conf" "dev" "admin" "invoices.edn") entity)
        (spit (conf-file dir "dev")
              (config-edn/insert-into
               (slurp (conf-file dir "dev")) ":active"
               "\n   :wagoe/admin {:enabled? true :entities #merge [#include \"admin/invoices.edn\"]}"))
        (let [[exit out] (run-setup dir "" "--prod" "true")]
          (is (nil? exit) out)
          (is (str/includes? (slurp (conf-file dir "prod")) "admin/invoices.edn"))
          (is (= entity (slurp (fs/file dir "resources" "conf" "prod" "admin" "invoices.edn")))))))))

;; -----------------------------------------------------------------------------
;; Next steps never tell an existing .env to be overwritten (BOU-573)
;; -----------------------------------------------------------------------------

(defn- next-steps [out]
  (second (str/split out #"Next steps:")))

(deftest ^:unit an-existing-env-is-told-only-what-it-lacks
  (with-project
    (fn [dir]
      (spit (fs/file dir ".env") "HTTP_PORT=3000\nexport JWT_SECRET=my-real-secret-of-32-characters!!\n")
      (let [[exit out] (run-setup dir "" "--ai-provider" "ollama")
            steps      (next-steps out)]
        (is (nil? exit) out)
        (is (not (str/includes? steps "Copy .env.example")) "copying would overwrite JWT_SECRET")
        (is (str/includes? steps "OLLAMA_URL"))
        (is (str/includes? steps "HTTP_HOST"))
        (is (not (str/includes? steps "JWT_SECRET")) "present, so not named")
        (is (= "HTTP_PORT=3000\nexport JWT_SECRET=my-real-secret-of-32-characters!!\n"
               (slurp (fs/file dir ".env"))))))))

(deftest ^:unit a-complete-env-is-told-nothing-to-add
  (with-project
    (fn [dir]
      (spit (fs/file dir ".env") (str (slurp (fs/file dir ".env.example")) "SQLITE_PATH=my.db\n"))
      (let [[exit out] (run-setup dir "" "--database" "sqlite")
            steps      (next-steps out)]
        (is (nil? exit) out)
        (is (not (str/includes? steps "Copy .env.example")))
        (is (str/includes? steps ".env has every variable in .env.example"))))))

(deftest ^:unit a-missing-env-is-told-to-copy-the-example
  (with-project
    (fn [dir]
      (let [[exit out] (run-setup dir "" "--database" "sqlite")]
        (is (nil? exit) out)
        (is (str/includes? (next-steps out) "Copy .env.example to .env"))))))

;; -----------------------------------------------------------------------------
;; AGENTS.md follows what setup switched on (BOU-573)
;; -----------------------------------------------------------------------------

(defn- cli-command
  "The wagoe CLI from this checkout, run the way bbin runs it."
  []
  (let [root (some #(when (fs/exists? (fs/file % "libs/wagoe-cli/src")) (fs/canonicalize %))
                   ["." ".."])]
    ["bb" "-cp" (str (fs/file root "libs/wagoe-cli/src") ":" (fs/file root "libs/wagoe-cli/resources"))
     "-m" "wagoe.cli.main"]))

(defn- installed-block [dir]
  (second (re-find #"(?s)<!-- wagoe:installed-modules -->(.*?)<!-- /wagoe:installed-modules -->"
                   (slurp (fs/file dir "AGENTS.md")))))

(defn- with-new-project
  "Run `f` on a project `wagoe new` made, deleting it afterwards."
  [f]
  (let [parent (fs/create-temp-dir)]
    (try
      (apply process/shell {:dir (str parent) :out :string :err :string}
             (concat (cli-command) ["new" "shop" "--skip-git"]))
      (f (fs/file parent "shop"))
      (finally (fs/delete-tree parent)))))

(deftest ^:integration setup-keeps-agents-md-modules-in-line
  (with-new-project
    (fn [dir]
      (is (not (str/includes? (installed-block dir) "- payments (")))
      (let [[exit out] (run-setup dir "" "--payment" "mock")]
        (is (nil? exit) out)
        (is (str/includes? (installed-block dir) "- payments (") out)
        (is (str/includes? (slurp (fs/file dir "AGENTS.md")) "<!-- gen:pitfalls -->")
            "the rest of AGENTS.md is left as it was")))))

(def ^:private module-block-re
  #"(?s)(<!-- (wagoe:(?:available|installed)-modules) -->).*?(<!-- /\2 -->)")

(defn- outside-module-blocks [text]
  (str/replace text module-block-re "$1$3"))

(defn- module-blocks [text]
  (mapv first (re-seq module-block-re text)))

(defn- cli-rendered-blocks
  "The module blocks the CLI renders for `dir` as it is now."
  [dir]
  (require 'wagoe.cli.add)
  (let [render (resolve 'wagoe.cli.add/render-module-blocks)
        states (resolve 'wagoe.cli.add/module-states)]
    (module-blocks (render (slurp (fs/file dir "AGENTS.md")) (states (str dir))))))

(deftest ^:integration setup-and-add-agree-on-agents-md
  ;; Setup ran whichever `wagoe` was on PATH. An older one ignored --modules and
  ;; re-rendered AGENTS.md from its own template: camelCase API naming, no
  ;; :public pitfall, a module table add then flipped back (BOU-577).
  (with-new-project
    (fn [dir]
      (let [agents   #(slurp (fs/file dir "AGENTS.md"))
            outside  (outside-module-blocks (agents))
            step     (fn [label]
                       (testing label
                         (is (= outside (outside-module-blocks (agents)))
                             "nothing outside the module blocks changes")
                         (is (= (cli-rendered-blocks dir) (module-blocks (agents)))
                             "the blocks are what the CLI renders")))]
        (let [[exit out] (run-setup dir "" "--payment" "mock")]
          (is (nil? exit) out)
          (is (str/includes? (installed-block dir) "- payments (") out))
        (step "setup")
        (apply process/shell {:dir (str dir) :out :string :err :string}
               (concat (cli-command) ["add" "jobs"]))
        (step "add")
        (let [[exit out] (run-setup dir "" "--cache" "memory")]
          (is (nil? exit) out)
          (step "setup again"))))))
