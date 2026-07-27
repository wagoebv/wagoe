(ns wagoe.payments.schema-test
  "Unit tests for payment Malli schemas."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [wagoe.payments.schema :as schema]))

;; =============================================================================
;; CheckoutRequest
;; =============================================================================

(deftest ^:unit checkout-request-schema-test
  (testing "accepts a valid checkout request"
    (is (m/validate schema/CheckoutRequest
                    {:amount-cents 4900
                     :currency     "EUR"
                     :description  "Test product"
                     :redirect-url "https://example.com/done"})))

  (testing "accepts optional fields"
    (is (m/validate schema/CheckoutRequest
                    {:amount-cents  11900
                     :currency      "USD"
                     :description   "Premium plan"
                     :redirect-url  "https://example.com/return"
                     :webhook-url   "https://example.com/webhook"
                     :metadata      {:order-id "abc-123"}})))

  (testing "rejects missing amount-cents"
    (is (not (m/validate schema/CheckoutRequest
                         {:currency    "EUR"
                          :description "Test"
                          :redirect-url "https://example.com"}))))

  (testing "rejects zero amount-cents (must be pos-int)"
    (is (not (m/validate schema/CheckoutRequest
                         {:amount-cents 0
                          :currency    "EUR"
                          :description "Test"
                          :redirect-url "https://example.com"}))))

  (testing "rejects non-integer amount-cents"
    (is (not (m/validate schema/CheckoutRequest
                         {:amount-cents 49.0
                          :currency    "EUR"
                          :description "Test"
                          :redirect-url "https://example.com"}))))

  (testing "rejects currency shorter than 3 chars"
    (is (not (m/validate schema/CheckoutRequest
                         {:amount-cents 100
                          :currency    "EU"
                          :description "Test"
                          :redirect-url "https://example.com"}))))

  (testing "rejects currency longer than 3 chars"
    (is (not (m/validate schema/CheckoutRequest
                         {:amount-cents 100
                          :currency    "EURO"
                          :description "Test"
                          :redirect-url "https://example.com"}))))

  (testing "accepts mandate-related optional fields for off-session reuse"
    (is (m/validate schema/CheckoutRequest
                    {:amount-cents       4900
                     :currency           "EUR"
                     :description        "First installment"
                     :redirect-url       "https://example.com/done"
                     :setup-future-usage :off-session
                     :customer-email     "jane@example.com"
                     :provider-customer-id "cus_abc123"})))

  (testing "accepts optional explicit success/cancel URLs"
    (is (m/validate schema/CheckoutRequest
                    {:amount-cents 4900
                     :currency     "EUR"
                     :description  "Test product"
                     :redirect-url "https://example.com/done"
                     :success-url  "https://example.com/success"
                     :cancel-url   "https://example.com/cancel"})))

  (testing "rejects unknown setup-future-usage value"
    (is (not (m/validate schema/CheckoutRequest
                         {:amount-cents       100
                          :currency           "EUR"
                          :description        "Test"
                          :redirect-url       "https://example.com"
                          :setup-future-usage :sometimes}))))

  (testing "rejects customer-email without an @"
    (is (not (m/validate schema/CheckoutRequest
                         {:amount-cents   100
                          :currency       "EUR"
                          :description    "Test"
                          :redirect-url   "https://example.com"
                          :customer-email "not-an-email"})))))

;; =============================================================================
;; CheckoutResult
;; =============================================================================

(deftest ^:unit checkout-result-schema-test
  (testing "accepts a valid checkout result"
    (is (m/validate schema/CheckoutResult
                    {:checkout-url         "https://psp.example.com/pay/abc"
                     :provider-checkout-id "cs_test_abc123"
                     :correlation-id       "corr-uuid-123"})))

  (testing "rejects missing checkout-url"
    (is (not (m/validate schema/CheckoutResult
                         {:provider-checkout-id "cs_test_abc123"
                          :correlation-id       "corr-uuid-123"}))))

  (testing "rejects missing provider-checkout-id"
    (is (not (m/validate schema/CheckoutResult
                         {:checkout-url   "https://psp.example.com/pay/abc"
                          :correlation-id "corr-uuid-123"}))))

  (testing "rejects missing correlation-id — consumers must always be able to correlate"
    (is (not (m/validate schema/CheckoutResult
                         {:checkout-url         "https://psp.example.com/pay/abc"
                          :provider-checkout-id "cs_test_abc123"}))))

  (testing "accepts optional provider-payment-id"
    (is (m/validate schema/CheckoutResult
                    {:checkout-url         "https://psp.example.com/pay/abc"
                     :provider-checkout-id "cs_test_abc123"
                     :correlation-id       "corr-uuid-123"
                     :provider-payment-id  "pi_abc123"}))))

;; =============================================================================
;; PaymentStatusResult
;; =============================================================================

(deftest ^:unit payment-status-result-schema-test
  (testing "accepts all valid statuses (aligned with boundary-license PaymentStatus)"
    (doseq [status [:pending :paid :failed :cancelled :expired :chargeback]]
      (is (m/validate schema/PaymentStatusResult {:status status})
          (str "should accept status " status))))

  (testing "accepts optional provider-payment-id"
    (is (m/validate schema/PaymentStatusResult
                    {:status              :paid
                     :provider-payment-id "pi_abc123"})))

  (testing "accepts optional mandate fields for off-session follow-up"
    (is (m/validate schema/PaymentStatusResult
                    {:status                     :paid
                     :provider-payment-id        "pi_abc123"
                     :provider-customer-id       "cus_abc123"
                     :provider-payment-method-id "pm_abc123"})))

  (testing "rejects unknown status"
    (is (not (m/validate schema/PaymentStatusResult {:status :unknown}))))

  (testing "rejects missing status"
    (is (not (m/validate schema/PaymentStatusResult {})))))

;; =============================================================================
;; WebhookResult
;; =============================================================================

(deftest ^:unit webhook-result-schema-test
  (testing "accepts all valid event types"
    (doseq [event-type [:payment.paid :payment.failed :payment.cancelled
                        :payment.expired :payment.authorized]]
      (is (m/validate schema/WebhookResult
                      {:event-type event-type :payload {}})
          (str "should accept event-type " event-type))))

  (testing "accepts a nil event-type (unmapped-but-acknowledged event, BOU-147)"
    ;; checkout.session.* / disputes / any type with no generic payment.* outcome
    ;; — the adapter surfaces these as :event-type nil + payload, never a throw.
    (is (m/validate schema/WebhookResult
                    {:event-type nil
                     :payload    {:type "checkout.session.expired"}})))

  (testing "accepts optional fields"
    (is (m/validate schema/WebhookResult
                    {:event-type            :payment.paid
                     :provider-payment-id   "pi_abc"
                     :correlation-id        "corr-uuid-123"
                     :provider-checkout-id  "cs_abc"
                     :payload               {:id "evt_abc"}})))

  (testing "accepts a correlation-only result (Stripe payment_intent.* — no session id)"
    (is (m/validate schema/WebhookResult
                    {:event-type      :payment.paid
                     :correlation-id  "corr-uuid-123"
                     :payload         {:id "evt_abc"}})))

  (testing "rejects unknown event-type"
    (is (not (m/validate schema/WebhookResult
                         {:event-type :payment.refunded :payload {}}))))

  (testing "rejects missing payload"
    (is (not (m/validate schema/WebhookResult {:event-type :payment.paid}))))

  (testing "rejects missing event-type"
    (is (not (m/validate schema/WebhookResult {:payload {}})))))

;; =============================================================================
;; OffSessionPaymentRequest
;; =============================================================================

(deftest ^:unit off-session-payment-request-schema-test
  (testing "accepts a valid off-session payment request"
    (is (m/validate schema/OffSessionPaymentRequest
                    {:amount-cents         4900
                     :currency             "EUR"
                     :description          "Monthly subscription"
                     :provider-customer-id "cus_abc123"})))

  (testing "accepts optional payment-method and metadata"
    (is (m/validate schema/OffSessionPaymentRequest
                    {:amount-cents                4900
                     :currency                    "EUR"
                     :description                 "Monthly subscription"
                     :provider-customer-id        "cus_abc123"
                     :provider-payment-method-id  "pm_abc123"
                     :metadata                    {:subscription-id "sub-42"}})))

  (testing "accepts optional idempotency-key"
    (is (m/validate schema/OffSessionPaymentRequest
                    {:amount-cents         4900
                     :currency             "EUR"
                     :description          "Monthly subscription"
                     :provider-customer-id "cus_abc123"
                     :idempotency-key      "incasso-sub-42-2026-06"})))

  (testing "rejects a non-string idempotency-key"
    (is (not (m/validate schema/OffSessionPaymentRequest
                         {:amount-cents         4900
                          :currency             "EUR"
                          :description          "Monthly subscription"
                          :provider-customer-id "cus_abc123"
                          :idempotency-key      42}))))

  (testing "rejects missing provider-customer-id"
    (is (not (m/validate schema/OffSessionPaymentRequest
                         {:amount-cents 4900
                          :currency     "EUR"
                          :description  "Monthly subscription"}))))

  (testing "rejects zero amount-cents"
    (is (not (m/validate schema/OffSessionPaymentRequest
                         {:amount-cents         0
                          :currency             "EUR"
                          :description          "Monthly subscription"
                          :provider-customer-id "cus_abc123"})))))

;; =============================================================================
;; OffSessionPaymentResult
;; =============================================================================

(deftest ^:unit off-session-payment-result-schema-test
  (testing "accepts a valid off-session payment result"
    (doseq [status [:pending :paid :failed]]
      (is (m/validate schema/OffSessionPaymentResult
                      {:provider-payment-id "pi_abc123"
                       :status              status})
          (str "should accept status " status))))

  (testing "rejects missing provider-payment-id"
    (is (not (m/validate schema/OffSessionPaymentResult {:status :paid}))))

  (testing "rejects unknown status"
    (is (not (m/validate schema/OffSessionPaymentResult
                         {:provider-payment-id "pi_abc123"
                          :status              :unknown}))))

  (testing "rejects PaymentStatus values outside the off-session subset"
    (doseq [status [:cancelled :expired :chargeback]]
      (is (not (m/validate schema/OffSessionPaymentResult
                           {:provider-payment-id "pi_abc123"
                            :status              status}))
          (str "should reject status " status)))))

;; =============================================================================
;; ExpireCheckoutResult
;; =============================================================================

(deftest ^:unit expire-checkout-result-schema-test
  (testing "accepts a valid expire-checkout result"
    (is (m/validate schema/ExpireCheckoutResult
                    {:provider-checkout-id "cs_test_abc123"
                     :status               :expired})))

  (testing "rejects non-expired status"
    (is (not (m/validate schema/ExpireCheckoutResult
                         {:provider-checkout-id "cs_test_abc123"
                          :status               :paid}))))

  (testing "rejects missing provider-checkout-id"
    (is (not (m/validate schema/ExpireCheckoutResult {:status :expired})))))
