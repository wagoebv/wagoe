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
      (is (str/includes? config ":boundary/settings"))
      (is (str/includes? config "\"my-app-dev\""))))

  (testing "includes postgresql config for dev"
    (let [config (setup/build-config minimal-spec "dev")]
      (is (str/includes? config ":boundary/postgresql"))
      (is (str/includes? config "POSTGRES_HOST"))))

  (testing "includes HTTP and router for dev"
    (let [config (setup/build-config minimal-spec "dev")]
      (is (str/includes? config ":boundary/http"))
      (is (str/includes? config ":boundary/router"))))

  (testing "excludes disabled providers"
    (let [config (setup/build-config minimal-spec "dev")]
      (is (not (str/includes? config ":boundary/ai-service")))
      (is (not (str/includes? config ":boundary/payment-provider")))
      (is (not (str/includes? config ":boundary/cache"))))))

;; =============================================================================
;; build-config — test environment
;; =============================================================================

(deftest ^:unit build-config-test-env-test
  (testing "uses H2 for test regardless of database choice"
    (let [config (setup/build-config minimal-spec "test")]
      (is (str/includes? config ":boundary/h2"))
      (is (not (str/includes? config ":boundary/postgresql")))))

  (testing "omits HTTP and router for test"
    (let [config (setup/build-config minimal-spec "test")]
      (is (not (str/includes? config ":boundary/http")))
      (is (not (str/includes? config ":boundary/router"))))))

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
      (is (str/includes? config ":boundary/ai-service"))
      (is (str/includes? config ":provider :ollama"))
      (is (str/includes? config ":boundary/payment-provider"))
      (is (str/includes? config ":provider :stripe"))
      (is (str/includes? config ":boundary/cache"))
      (is (str/includes? config ":provider    :redis"))
      (is (str/includes? config ":wagoe.external/smtp"))
      (is (str/includes? config ":boundary/admin"))))

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
