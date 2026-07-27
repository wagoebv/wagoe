(ns wagoe.payments.shell.module-wiring-test
  "Boot-time credential validation for :boundary/payment-provider (BOU-77)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [integrant.core :as ig]
            ;; loads the init-key defmethod + transitively the adapter records
            ;; referenced below via fully-qualified class names.
            [wagoe.payments.shell.module-wiring]))

(defn- init [config]
  (ig/init-key :boundary/payment-provider config))

(defn- config-error [config]
  (try (init config) nil
       (catch clojure.lang.ExceptionInfo e e)))

;; =============================================================================
;; Stripe — both api-key and webhook-secret required
;; =============================================================================

(deftest ^:unit stripe-credential-validation-test
  (testing "boots when both credentials are present"
    (let [provider (init {:provider       :stripe
                          :api-key        "sk_test_123"
                          :webhook-secret "whsec_123"})]
      (is (instance? wagoe.payments.shell.adapters.stripe.StripePaymentProvider provider))))

  (testing "missing api-key fails boot with :config-error naming the key + env var"
    (let [ex (config-error {:provider :stripe :webhook-secret "whsec_123"})]
      (is (some? ex))
      (is (= :config-error (:type (ex-data ex))))
      (is (contains? (set (:missing-keys (ex-data ex))) :api-key))
      (is (str/includes? (ex-message ex) "STRIPE_API_KEY"))))

  (testing "blank api-key is treated as missing"
    (doseq [blank ["" "   "]]
      (let [ex (config-error {:provider :stripe :api-key blank :webhook-secret "whsec_123"})]
        (is (= :config-error (:type (ex-data ex))) (str "blank=" (pr-str blank))))))

  (testing "missing webhook-secret fails boot naming STRIPE_WEBHOOK_SECRET"
    (let [ex (config-error {:provider :stripe :api-key "sk_test_123"})]
      (is (= :config-error (:type (ex-data ex))))
      (is (contains? (set (:missing-keys (ex-data ex))) :webhook-secret))
      (is (str/includes? (ex-message ex) "STRIPE_WEBHOOK_SECRET"))))

  (testing "both missing are reported together"
    (let [ex (config-error {:provider :stripe})]
      (is (= #{:api-key :webhook-secret} (set (:missing-keys (ex-data ex))))))))

;; =============================================================================
;; Mollie — only api-key required (webhook-base-url effectively required already)
;; =============================================================================

(deftest ^:unit mollie-credential-validation-test
  (testing "boots when api-key is present"
    (let [provider (init {:provider         :mollie
                          :api-key          "test_key"
                          :webhook-base-url "https://example.com"})]
      (is (instance? wagoe.payments.shell.adapters.mollie.MolliePaymentProvider provider))))

  (testing "missing api-key fails boot with :config-error naming MOLLIE_API_KEY"
    (let [ex (config-error {:provider :mollie :webhook-base-url "https://example.com"})]
      (is (some? ex))
      (is (= :config-error (:type (ex-data ex))))
      (is (contains? (set (:missing-keys (ex-data ex))) :api-key))
      (is (str/includes? (ex-message ex) "MOLLIE_API_KEY"))))

  (testing "blank api-key is treated as missing"
    (let [ex (config-error {:provider :mollie :api-key "  " :webhook-base-url "https://example.com"})]
      (is (= :config-error (:type (ex-data ex)))))))

;; =============================================================================
;; Mock — no credential requirements; happy paths unaffected
;; =============================================================================

(deftest ^:unit mock-needs-no-credentials-test
  (testing "mock boots with no credentials"
    (is (instance? wagoe.payments.shell.adapters.mock.MockPaymentProvider
                   (init {:provider :mock}))))

  (testing "defaults to mock when :provider is omitted"
    (is (instance? wagoe.payments.shell.adapters.mock.MockPaymentProvider
                   (init {})))))

;; =============================================================================
;; Unknown provider — unchanged behaviour
;; =============================================================================

(deftest ^:unit unknown-provider-test
  (testing "unknown provider still throws :internal-error (not :config-error)"
    (let [ex (config-error {:provider :paypal})]
      (is (= :internal-error (:type (ex-data ex)))))))
