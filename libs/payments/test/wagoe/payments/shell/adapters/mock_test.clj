(ns wagoe.payments.shell.adapters.mock-test
  "Integration tests for the Mock payment provider adapter."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [wagoe.payments.ports :as ports]
            [wagoe.payments.shell.adapters.mock :as mock]))

(def ^:private provider (mock/make-mock-provider))

(def ^:private base-request
  {:amount-cents 4900
   :currency     "EUR"
   :description  "Test product"
   :redirect-url "https://example.com/done"})

;; =============================================================================
;; create-checkout-session
;; =============================================================================

(deftest ^:integration create-checkout-session-test
  (testing "returns checkout-url and provider-checkout-id"
    (let [result (ports/create-checkout-session provider base-request)]
      (is (string? (:checkout-url result)))
      (is (string? (:provider-checkout-id result)))))

  (testing "checkout-url is a local mock path"
    (let [{:keys [checkout-url]} (ports/create-checkout-session provider base-request)]
      (is (str/starts-with? checkout-url "/web/payment/mock-return?checkout-id="))))

  (testing "provider-checkout-id is a UUID string"
    (let [{:keys [provider-checkout-id]} (ports/create-checkout-session provider base-request)]
      (is (re-matches #"[0-9a-f-]{36}" provider-checkout-id))))

  (testing "each call generates a unique checkout-id"
    (let [id1 (:provider-checkout-id (ports/create-checkout-session provider base-request))
          id2 (:provider-checkout-id (ports/create-checkout-session provider base-request))]
      (is (not= id1 id2))))

  (testing "includes payment-id in URL when present in metadata"
    (let [result (ports/create-checkout-session provider
                                                (assoc base-request :metadata {:payment-id "order-42"}))]
      (is (str/includes? (:checkout-url result) "&payment-id=order-42"))))

  (testing "omits payment-id from URL when metadata is absent"
    (let [result (ports/create-checkout-session provider base-request)]
      (is (not (str/includes? (:checkout-url result) "payment-id")))))

  (testing "omits payment-id from URL when metadata has no :payment-id key"
    (let [result (ports/create-checkout-session provider
                                                (assoc base-request :metadata {:order-ref "xyz"}))]
      (is (not (str/includes? (:checkout-url result) "payment-id")))))

  (testing "returns a provider-payment-id derived from the checkout id"
    (let [{:keys [provider-checkout-id provider-payment-id]}
          (ports/create-checkout-session provider base-request)]
      (is (= (str "mock-payment-" provider-checkout-id) provider-payment-id))))

  (testing "returns a correlation-id equal to the checkout id"
    (let [{:keys [provider-checkout-id correlation-id]}
          (ports/create-checkout-session provider base-request)]
      (is (= provider-checkout-id correlation-id))))

  (testing "accepts mandate options (setup-future-usage) without breaking"
    (let [result (ports/create-checkout-session
                  provider
                  (assoc base-request
                         :setup-future-usage :off-session
                         :customer-email     "jane@example.com"))]
      (is (string? (:checkout-url result)))
      (is (string? (:provider-checkout-id result))))))

;; =============================================================================
;; get-payment-status
;; =============================================================================

(deftest ^:integration get-payment-status-test
  (testing "always returns :paid status"
    (let [result (ports/get-payment-status provider "mock-checkout-123")]
      (is (= :paid (:status result)))))

  (testing "provider-payment-id is derived from checkout id"
    (let [result (ports/get-payment-status provider "mock-checkout-123")]
      (is (= "mock-payment-mock-checkout-123" (:provider-payment-id result)))))

  (testing "exposes mock mandate details for off-session follow-up"
    (let [result (ports/get-payment-status provider "mock-checkout-123")]
      (is (= "mock-customer-mock-checkout-123" (:provider-customer-id result)))
      (is (= "mock-pm-mock-checkout-123" (:provider-payment-method-id result))))))

;; =============================================================================
;; create-off-session-payment
;; =============================================================================

(deftest ^:integration create-off-session-payment-test
  (testing "auto-approves and returns a provider-payment-id"
    (let [result (ports/create-off-session-payment
                  provider
                  {:amount-cents         4900
                   :currency             "EUR"
                   :description          "Monthly subscription"
                   :provider-customer-id "mock-customer-abc"})]
      (is (= :paid (:status result)))
      (is (string? (:provider-payment-id result)))
      (is (str/starts-with? (:provider-payment-id result) "mock-payment-"))))

  (testing "each call generates a unique provider-payment-id"
    (let [request {:amount-cents         100
                   :currency             "EUR"
                   :description          "Recurring"
                   :provider-customer-id "mock-customer-abc"}
          id1 (:provider-payment-id (ports/create-off-session-payment provider request))
          id2 (:provider-payment-id (ports/create-off-session-payment provider request))]
      (is (not= id1 id2))))

  (testing "metadata :mock-status overrides the simulated outcome"
    (let [result (ports/create-off-session-payment
                  provider
                  {:amount-cents         4900
                   :currency             "EUR"
                   :description          "Monthly subscription"
                   :provider-customer-id "mock-customer-abc"
                   :metadata             {:mock-status :failed}})]
      (is (= :failed (:status result)))
      (is (string? (:provider-payment-id result)))))

  (testing "invalid :mock-status falls back to :paid"
    (let [result (ports/create-off-session-payment
                  provider
                  {:amount-cents         4900
                   :currency             "EUR"
                   :description          "Monthly subscription"
                   :provider-customer-id "mock-customer-abc"
                   :metadata             {:mock-status :chargeback}})]
      (is (= :paid (:status result))))))

(deftest ^:integration create-off-session-payment-idempotency-test
  (testing "a repeated :idempotency-key returns the identical result"
    (let [prov    (mock/make-mock-provider)
          request {:amount-cents         4900
                   :currency             "EUR"
                   :description          "Monthly subscription"
                   :provider-customer-id "mock-customer-abc"
                   :idempotency-key      "incasso-sub-42-2026-06"}
          first   (ports/create-off-session-payment prov request)
          again   (ports/create-off-session-payment prov request)]
      (is (= first again))))

  (testing "different idempotency-keys yield different payment ids"
    (let [prov (mock/make-mock-provider)
          base {:amount-cents         4900
                :currency             "EUR"
                :description          "Monthly subscription"
                :provider-customer-id "mock-customer-abc"}
          a    (ports/create-off-session-payment prov (assoc base :idempotency-key "key-a"))
          b    (ports/create-off-session-payment prov (assoc base :idempotency-key "key-b"))]
      (is (not= (:provider-payment-id a) (:provider-payment-id b)))))

  (testing "without an idempotency-key each call is still unique"
    (let [prov (mock/make-mock-provider)
          base {:amount-cents         4900
                :currency             "EUR"
                :description          "Monthly subscription"
                :provider-customer-id "mock-customer-abc"}
          a    (ports/create-off-session-payment prov base)
          b    (ports/create-off-session-payment prov base)]
      (is (not= (:provider-payment-id a) (:provider-payment-id b))))))

;; =============================================================================
;; expire-checkout-session
;; =============================================================================

(deftest ^:integration expire-checkout-session-test
  (testing "returns the checkout id with :expired status"
    (let [result (ports/expire-checkout-session provider "mock-checkout-123")]
      (is (= {:provider-checkout-id "mock-checkout-123"
              :status               :expired}
             result)))))

;; =============================================================================
;; verify-webhook-signature
;; =============================================================================

(deftest ^:integration verify-webhook-signature-test
  (testing "always returns true regardless of body or headers"
    (is (true? (ports/verify-webhook-signature provider "" {})))
    (is (true? (ports/verify-webhook-signature provider "anything" {"Stripe-Signature" "wrong"})))
    (is (true? (ports/verify-webhook-signature provider nil nil)))))

;; =============================================================================
;; process-webhook
;; =============================================================================

(deftest ^:integration process-webhook-test
  (testing "returns :payment.paid event type"
    (let [result (ports/process-webhook provider "{}" {})]
      (is (= :payment.paid (:event-type result)))))

  (testing "extracts correlation-id from JSON string body"
    (let [result (ports/process-webhook provider
                                        "{\"checkout-id\": \"abc-123\"}" {})]
      (is (= "abc-123" (:correlation-id result)))
      (is (str/starts-with? (:provider-payment-id result) "mock-payment-abc-123"))))

  (testing "processes a map body directly"
    (let [result (ports/process-webhook provider {:checkout-id "map-456"} {})]
      (is (= "map-456" (:correlation-id result)))))

  (testing "generates a random correlation-id when checkout-id is absent"
    (let [result (ports/process-webhook provider "{}" {})]
      (is (string? (:correlation-id result)))
      (is (string? (:provider-payment-id result)))))

  (testing "does not throw on invalid JSON"
    (is (map? (ports/process-webhook provider "not-json{{{" {}))))

  (testing "payload contains the parsed body"
    (let [result (ports/process-webhook provider "{\"foo\": \"bar\"}" {})]
      (is (= {:foo "bar"} (:payload result))))))

;; =============================================================================
;; correlation round-trip — create → webhook (BOU-78 acceptance)
;; =============================================================================

(deftest ^:contract create-to-webhook-correlation-round-trip-test
  (testing "the :correlation-id from CheckoutResult is recoverable from the webhook"
    (let [{:keys [correlation-id]} (ports/create-checkout-session provider base-request)
          ;; mock webhook echoes the checkout id back under :checkout-id
          processed (ports/process-webhook provider {:checkout-id correlation-id} {})]
      (is (string? correlation-id))
      (is (= correlation-id (:correlation-id processed))))))
