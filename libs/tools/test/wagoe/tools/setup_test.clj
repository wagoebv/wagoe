(ns wagoe.tools.setup-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.setup :as setup]
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
      (is (str/includes? config ":provider    :in-memory"))))) ;; Cache

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
