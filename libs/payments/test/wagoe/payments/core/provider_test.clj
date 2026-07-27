(ns wagoe.payments.core.provider-test
  "Unit tests for pure payment provider helper functions."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.payments.core.provider :as provider]))

;; =============================================================================
;; cents->euro
;; =============================================================================

(deftest ^:unit cents->euro-test
  (testing "converts whole euro amounts"
    (is (= "1.00"   (provider/cents->euro 100)))
    (is (= "10.00"  (provider/cents->euro 1000)))
    (is (= "119.00" (provider/cents->euro 11900))))

  (testing "converts fractional euro amounts"
    (is (= "0.01"  (provider/cents->euro 1)))
    (is (= "0.99"  (provider/cents->euro 99)))
    (is (= "19.99" (provider/cents->euro 1999))))

  (testing "handles zero"
    (is (= "0.00" (provider/cents->euro 0))))

  (testing "returns a string"
    (is (string? (provider/cents->euro 4900)))))

;; =============================================================================
;; normalize-event-type — Mollie
;; =============================================================================

(deftest ^:unit normalize-event-type-mollie-test
  (testing "maps Mollie paid status"
    (is (= :payment.paid (provider/normalize-event-type "paid" :mollie))))

  (testing "maps Mollie authorized status"
    (is (= :payment.authorized (provider/normalize-event-type "authorized" :mollie))))

  (testing "maps Mollie failed status"
    (is (= :payment.failed (provider/normalize-event-type "failed" :mollie))))

  (testing "maps Mollie canceled (American spelling)"
    (is (= :payment.cancelled (provider/normalize-event-type "canceled" :mollie))))

  (testing "maps Mollie cancelled (British spelling)"
    (is (= :payment.cancelled (provider/normalize-event-type "cancelled" :mollie))))

  (testing "maps Mollie expired status"
    (is (= :payment.expired (provider/normalize-event-type "expired" :mollie))))

  (testing "returns nil for unknown Mollie status"
    (is (nil? (provider/normalize-event-type "open" :mollie)))
    (is (nil? (provider/normalize-event-type "pending" :mollie)))
    (is (nil? (provider/normalize-event-type "" :mollie)))))

;; =============================================================================
;; normalize-event-type — Stripe
;; =============================================================================

(deftest ^:unit normalize-event-type-stripe-test
  (testing "maps payment_intent.succeeded"
    (is (= :payment.paid (provider/normalize-event-type "payment_intent.succeeded" :stripe))))

  (testing "maps payment_intent.payment_failed"
    (is (= :payment.failed (provider/normalize-event-type "payment_intent.payment_failed" :stripe))))

  (testing "maps payment_intent.canceled"
    (is (= :payment.cancelled (provider/normalize-event-type "payment_intent.canceled" :stripe))))

  (testing "maps payment_intent.amount_capturable_updated"
    (is (= :payment.authorized (provider/normalize-event-type "payment_intent.amount_capturable_updated" :stripe))))

  (testing "returns nil for unknown Stripe event"
    (is (nil? (provider/normalize-event-type "charge.succeeded" :stripe)))
    (is (nil? (provider/normalize-event-type "customer.created" :stripe)))
    (is (nil? (provider/normalize-event-type "" :stripe)))))

;; =============================================================================
;; normalize-event-type — unknown provider
;; =============================================================================

(deftest ^:unit normalize-event-type-unknown-provider-test
  (testing "returns nil for unknown provider"
    (is (nil? (provider/normalize-event-type "paid" :unknown-psp)))
    (is (nil? (provider/normalize-event-type "paid" nil)))))

;; =============================================================================
;; mollie-status->event-type (convenience wrapper)
;; =============================================================================

(deftest ^:unit mollie-status->event-type-test
  (testing "delegates to normalize-event-type :mollie"
    (is (= :payment.paid      (provider/mollie-status->event-type "paid")))
    (is (= :payment.failed    (provider/mollie-status->event-type "failed")))
    (is (= :payment.cancelled (provider/mollie-status->event-type "canceled")))
    (is (= :payment.cancelled (provider/mollie-status->event-type "cancelled")))
    (is (= :payment.expired   (provider/mollie-status->event-type "expired")))
    (is (nil?                  (provider/mollie-status->event-type "open")))))

;; =============================================================================
;; mollie-status->payment-status (status keyword mapping)
;; =============================================================================

(deftest ^:unit mollie-status->payment-status-test
  (testing "maps paid to :paid (not :payment.paid)"
    (is (= :paid (provider/mollie-status->payment-status "paid"))))

  (testing "maps failed to :failed"
    (is (= :failed (provider/mollie-status->payment-status "failed"))))

  (testing "maps canceled to :cancelled"
    (is (= :cancelled (provider/mollie-status->payment-status "canceled"))))

  (testing "maps cancelled to :cancelled"
    (is (= :cancelled (provider/mollie-status->payment-status "cancelled"))))

  (testing "maps expired to :expired (aligned with boundary-license PaymentStatus)"
    (is (= :expired (provider/mollie-status->payment-status "expired"))))

  (testing "returns :pending for unrecognised statuses"
    (is (= :pending (provider/mollie-status->payment-status "open")))
    (is (= :pending (provider/mollie-status->payment-status "pending")))
    (is (= :pending (provider/mollie-status->payment-status "created")))))

;; =============================================================================
;; stripe-event->event-type (convenience wrapper)
;; =============================================================================

(deftest ^:unit stripe-event->event-type-test
  (testing "delegates to normalize-event-type :stripe"
    (is (= :payment.paid       (provider/stripe-event->event-type "payment_intent.succeeded")))
    (is (= :payment.failed     (provider/stripe-event->event-type "payment_intent.payment_failed")))
    (is (= :payment.cancelled  (provider/stripe-event->event-type "payment_intent.canceled")))
    (is (= :payment.authorized (provider/stripe-event->event-type "payment_intent.amount_capturable_updated")))
    (is (nil?                   (provider/stripe-event->event-type "checkout.session.completed")))))

;; =============================================================================
;; stripe-payment-intent-id? (get-payment-status id dispatch)
;; =============================================================================

(deftest ^:unit stripe-payment-intent-id?-test
  (testing "pi_ ids are PaymentIntent ids"
    (is (true? (provider/stripe-payment-intent-id? "pi_3abc123"))))

  (testing "cs_ ids are not PaymentIntent ids"
    (is (false? (provider/stripe-payment-intent-id? "cs_test_abc"))))

  (testing "nil and unknown prefixes are not PaymentIntent ids"
    (is (false? (provider/stripe-payment-intent-id? nil)))
    (is (false? (provider/stripe-payment-intent-id? "ch_123")))
    (is (false? (provider/stripe-payment-intent-id? "")))))

;; =============================================================================
;; stripe-object-id (expandable field handling)
;; =============================================================================

(deftest ^:unit stripe-object-id-test
  (testing "returns string ids unchanged"
    (is (= "pi_1" (provider/stripe-object-id "pi_1"))))

  (testing "extracts :id from an expanded object map"
    (is (= "pm_9" (provider/stripe-object-id {:id "pm_9" :object "payment_method"}))))

  (testing "returns nil for nil or unexpected values"
    (is (nil? (provider/stripe-object-id nil)))
    (is (nil? (provider/stripe-object-id 42)))))

;; =============================================================================
;; stripe-intent-status->payment-status
;; =============================================================================

(deftest ^:unit stripe-intent-status->payment-status-test
  (testing "succeeded → :paid"
    (is (= :paid (provider/stripe-intent-status->payment-status "succeeded"))))

  (testing "canceled → :cancelled"
    (is (= :cancelled (provider/stripe-intent-status->payment-status "canceled"))))

  (testing "in-flight statuses → :pending"
    (is (= :pending (provider/stripe-intent-status->payment-status "requires_payment_method")))
    (is (= :pending (provider/stripe-intent-status->payment-status "requires_confirmation")))
    (is (= :pending (provider/stripe-intent-status->payment-status "requires_capture")))
    (is (= :pending (provider/stripe-intent-status->payment-status "processing"))))

  (testing "requires_action → :pending in the default (poll) context"
    (is (= :pending (provider/stripe-intent-status->payment-status "requires_action"))))

  (testing "requires_action → :failed in off-session context (SCA cannot complete)"
    (is (= :failed (provider/stripe-intent-status->payment-status
                    "requires_action" {:off-session? true}))))

  (testing "succeeded unaffected by off-session context"
    (is (= :paid (provider/stripe-intent-status->payment-status "succeeded" {:off-session? true}))))

  (testing "canceled → :failed in off-session context (port contract is :pending|:paid|:failed)"
    (is (= :failed (provider/stripe-intent-status->payment-status "canceled" {:off-session? true}))))

  (testing "unknown statuses → :pending"
    (is (= :pending (provider/stripe-intent-status->payment-status "something_new")))))

;; =============================================================================
;; stripe-session-status->payment-status
;; =============================================================================

(deftest ^:unit stripe-session-status->payment-status-test
  (testing "expired session → :expired regardless of payment_status"
    (is (= :expired (provider/stripe-session-status->payment-status "expired" "unpaid")))
    (is (= :expired (provider/stripe-session-status->payment-status "expired" nil))))

  (testing "paid / no_payment_required → :paid"
    (is (= :paid (provider/stripe-session-status->payment-status "complete" "paid")))
    (is (= :paid (provider/stripe-session-status->payment-status "complete" "no_payment_required"))))

  (testing "open or complete-but-unpaid → :pending"
    (is (= :pending (provider/stripe-session-status->payment-status "open" "unpaid")))
    (is (= :pending (provider/stripe-session-status->payment-status "complete" "unpaid")))
    (is (= :pending (provider/stripe-session-status->payment-status nil nil)))))

;; =============================================================================
;; stripe-checkout-params (request shaping)
;; =============================================================================

(deftest ^:unit stripe-checkout-params-test
  (let [base-opts {:amount-cents 11900
                   :currency     "EUR"
                   :description  "Premium plan"
                   :redirect-url "https://app.example.com/return"
                   :checkout-id  "internal-uuid"}]

    (testing "base params: payment mode, line item, urls default to redirect-url"
      (let [params (provider/stripe-checkout-params base-opts)]
        (is (= "payment" (get params "mode")))
        (is (= "eur"     (get params "line_items[0][price_data][currency]")))
        (is (= "11900"   (get params "line_items[0][price_data][unit_amount]")))
        (is (= "Premium plan" (get params "line_items[0][price_data][product_data][name]")))
        (is (= "https://app.example.com/return" (get params "success_url")))
        (is (= "https://app.example.com/return" (get params "cancel_url")))
        (is (= "internal-uuid" (get params "payment_intent_data[metadata][checkout_id]")))))

    (testing "explicit success/cancel URLs override redirect-url"
      (let [params (provider/stripe-checkout-params
                    (assoc base-opts
                           :success-url "https://app.example.com/ok"
                           :cancel-url  "https://app.example.com/nope"))]
        (is (= "https://app.example.com/ok"   (get params "success_url")))
        (is (= "https://app.example.com/nope" (get params "cancel_url")))))

    (testing "blank success/cancel URLs fall back to redirect-url (empty string must not win)"
      ;; An empty PUBLIC_BASE_URL once produced an empty success_url that Stripe
      ;; rejected with a 400 (BOU-127). `or` treats "" as truthy, so guard with
      ;; blank-awareness: a blank override must defer to redirect-url.
      (let [params (provider/stripe-checkout-params
                    (assoc base-opts :success-url "" :cancel-url "   "))]
        (is (= "https://app.example.com/return" (get params "success_url")))
        (is (= "https://app.example.com/return" (get params "cancel_url")))))

    (testing "setup-future-usage :off-session adds payment_intent_data[setup_future_usage]"
      (let [params (provider/stripe-checkout-params
                    (assoc base-opts :setup-future-usage :off-session))]
        (is (= "off_session" (get params "payment_intent_data[setup_future_usage]")))))

    (testing "setup-future-usage :on-session maps to on_session"
      (let [params (provider/stripe-checkout-params
                    (assoc base-opts :setup-future-usage :on-session))]
        (is (= "on_session" (get params "payment_intent_data[setup_future_usage]")))))

    (testing "no setup-future-usage → no setup_future_usage / customer_creation params"
      (let [params (provider/stripe-checkout-params base-opts)]
        (is (not (contains? params "payment_intent_data[setup_future_usage]")))
        (is (not (contains? params "customer_creation")))))

    (testing "mandate without existing customer forces customer_creation=always"
      (let [params (provider/stripe-checkout-params
                    (assoc base-opts :setup-future-usage :off-session))]
        (is (= "always" (get params "customer_creation")))))

    (testing "existing provider-customer-id is reused; no customer_creation, no customer_email"
      (let [params (provider/stripe-checkout-params
                    (assoc base-opts
                           :setup-future-usage   :off-session
                           :provider-customer-id "cus_abc"
                           :customer-email       "jane@example.com"))]
        (is (= "cus_abc" (get params "customer")))
        (is (not (contains? params "customer_creation")))
        ;; Stripe rejects customer + customer_email on the same session
        (is (not (contains? params "customer_email")))))

    (testing "customer-email is sent when there is no existing customer"
      (let [params (provider/stripe-checkout-params
                    (assoc base-opts :customer-email "jane@example.com"))]
        (is (= "jane@example.com" (get params "customer_email")))))

    (testing "metadata propagates to session and payment_intent_data metadata"
      (let [params (provider/stripe-checkout-params
                    (assoc base-opts :metadata {:order-id "ord-1" :plan "premium"}))]
        (is (= "ord-1"   (get params "metadata[order-id]")))
        (is (= "premium" (get params "metadata[plan]")))
        (is (= "ord-1"   (get params "payment_intent_data[metadata][order-id]")))
        ;; internal checkout_id correlation key must survive a metadata merge
        (is (= "internal-uuid" (get params "payment_intent_data[metadata][checkout_id]")))))

    (testing "unknown setup-future-usage is ignored — no throw, no params"
      (let [params (provider/stripe-checkout-params
                    (assoc base-opts :setup-future-usage :weekly))]
        (is (not (contains? params "payment_intent_data[setup_future_usage]")))
        (is (not (contains? params "customer_creation")))))

    (testing "metadata keys/values are truncated to Stripe limits (40/500 chars)"
      (let [long-key (keyword (apply str (repeat 60 "k")))
            long-val (apply str (repeat 600 "v"))
            params   (provider/stripe-checkout-params
                      (assoc base-opts :metadata {long-key long-val}))
            trunc-key (str "metadata[" (apply str (repeat 40 "k")) "]")]
        (is (contains? params trunc-key))
        (is (= 500 (count (get params trunc-key))))))))

;; =============================================================================
;; stripe-off-session-params (request shaping)
;; =============================================================================

(deftest ^:unit stripe-off-session-params-test
  (let [base-opts {:amount-cents         4900
                   :currency             "EUR"
                   :description          "Monthly subscription"
                   :provider-customer-id "cus_abc"}]

    (testing "base params: amount, currency, customer, off_session + confirm"
      (let [params (provider/stripe-off-session-params base-opts)]
        (is (= "4900"    (get params "amount")))
        (is (= "eur"     (get params "currency")))
        (is (= "cus_abc" (get params "customer")))
        (is (= "true"    (get params "off_session")))
        (is (= "true"    (get params "confirm")))
        (is (= "Monthly subscription" (get params "description")))))

    (testing "payment method is included when given, omitted otherwise"
      (is (= "pm_9" (get (provider/stripe-off-session-params
                          (assoc base-opts :provider-payment-method-id "pm_9"))
                         "payment_method")))
      (is (not (contains? (provider/stripe-off-session-params base-opts)
                          "payment_method"))))

    (testing "metadata is flattened to metadata[k] params"
      (let [params (provider/stripe-off-session-params
                    (assoc base-opts :metadata {:subscription-id "sub-42"}))]
        (is (= "sub-42" (get params "metadata[subscription-id]")))))))
