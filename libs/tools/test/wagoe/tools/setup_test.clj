(ns wagoe.tools.setup-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tools.setup :as setup]
            [clojure.string :as str]))

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
;; Settings template env parameter
;; =============================================================================

(deftest ^:unit settings-template-uses-env-test
  (testing "project name includes env suffix"
    (let [dev-config  (setup/build-config minimal-spec "dev")
          test-config (setup/build-config minimal-spec "test")]
      (is (str/includes? dev-config "\"my-app-dev\""))
      (is (str/includes? test-config "\"my-app-test\"")))))
