(ns wagoe.payments.core.provider
  "Pure helper functions for payment provider logic — no I/O, no side effects."
  (:require [clojure.string :as str]))

(defn cents->euro
  "Convert integer cents to a euro amount string (e.g. 11900 -> \"119.00\").
   Always uses a period as decimal separator regardless of JVM locale."
  [cents]
  (String/format java.util.Locale/US "%.2f" (into-array Object [(/ (double cents) 100.0)])))

(defn normalize-event-type
  "Map provider-specific event strings to internal event-type keywords."
  [raw-type provider]
  (case provider
    :mollie
    (case raw-type
      "paid"       :payment.paid
      "authorized" :payment.authorized
      "failed"     :payment.failed
      "canceled"   :payment.cancelled
      "cancelled"  :payment.cancelled
      "expired"    :payment.expired
      nil)

    :stripe
    (case raw-type
      "payment_intent.succeeded"             :payment.paid
      "payment_intent.payment_failed"        :payment.failed
      "payment_intent.canceled"              :payment.cancelled
      "payment_intent.amount_capturable_updated" :payment.authorized
      nil)

    nil))

(defn mollie-status->event-type
  "Map a Mollie payment status string to an internal event-type keyword."
  [status]
  (normalize-event-type status :mollie))

(defn mollie-status->payment-status
  "Map a Mollie payment status string to a PaymentStatusResult :status keyword.
   Returns :pending for unrecognised statuses (e.g. \"open\")."
  [status]
  (case status
    "paid"       :paid
    "failed"     :failed
    "canceled"   :cancelled
    "cancelled"  :cancelled
    "expired"    :expired
    :pending))

(defn stripe-event->event-type
  "Map a Stripe event type string to an internal event-type keyword."
  [event-type]
  (normalize-event-type event-type :stripe))

;; =============================================================================
;; Stripe — id dispatch & expandable fields
;; =============================================================================

(defn stripe-payment-intent-id?
  "True when the id names a Stripe PaymentIntent (pi_...).
   Used by get-payment-status to dispatch: pi_ ids are polled via
   /v1/payment_intents, everything else (cs_... Checkout Session ids) via
   /v1/checkout/sessions."
  [id]
  (str/starts-with? (str id) "pi_"))

(defn stripe-object-id
  "Stripe expandable fields are either a string id or an expanded object map.
   Returns the id in both cases, nil otherwise."
  [x]
  (cond
    (map? x)    (:id x)
    (string? x) x
    :else       nil))

;; =============================================================================
;; Stripe — status mapping
;; =============================================================================

(defn stripe-intent-status->payment-status
  "Map a Stripe PaymentIntent status string to a PaymentStatus keyword.

   succeeded → :paid, canceled → :cancelled; in-flight statuses
   (requires_payment_method, requires_confirmation, requires_capture,
   processing) and unknown statuses → :pending.

   requires_action and canceled depend on context: in the default (poll)
   context requires_action maps to :pending (the customer may still complete
   authentication) and canceled to :cancelled. With {:off-session? true} both
   map to :failed — an off-session charge that needs SCA cannot be completed
   unattended (bring the customer on-session), and a canceled charge collected
   no money; the off-session port contract is :pending|:paid|:failed."
  ([status] (stripe-intent-status->payment-status status {}))
  ([status {:keys [off-session?]}]
   (case status
     "succeeded"       :paid
     "canceled"        (if off-session? :failed :cancelled)
     "requires_action" (if off-session? :failed :pending)
     :pending)))

(defn stripe-session-status->payment-status
  "Map Stripe Checkout Session `status` + `payment_status` to a PaymentStatus.
   expired session → :expired; payment_status paid/no_payment_required → :paid;
   anything else (open, or complete-but-unpaid) → :pending."
  [session-status payment-status]
  (cond
    (= "expired" session-status)                               :expired
    (contains? #{"paid" "no_payment_required"} payment-status) :paid
    :else                                                      :pending))

;; =============================================================================
;; Stripe — request shaping (snake_case form params at the HTTP boundary)
;; =============================================================================

(defn- truncate
  "Truncate string s to at most max-len characters."
  [s max-len]
  (if (> (count s) max-len) (subs s 0 max-len) s))

(defn- stripe-metadata-params
  "Flatten a metadata map into Stripe nested form params under prefix,
   e.g. {:order-id \"x\"} → {\"metadata[order-id]\" \"x\"}.
   Keys and values are truncated to Stripe's metadata limits (40 / 500 chars)
   so oversized user metadata cannot fail the whole request. Stripe's
   50-keys-per-object limit is not enforced here."
  [prefix metadata]
  (into {}
        (map (fn [[k v]]
               [(str prefix "[" (truncate (name k) 40) "]")
                (truncate (str v) 500)]))
        metadata))

(defn stripe-checkout-params
  "Build the flat form-params map for a Stripe Checkout Session (mode=payment).

   - :success-url / :cancel-url override :redirect-url when given.
   - :setup-future-usage :off-session|:on-session is sent as
     payment_intent_data[setup_future_usage] so the payment method is saved
     for later charges. In payment mode Stripe only creates a Customer when
     asked, so customer_creation=always is added when a mandate is requested
     and no :provider-customer-id is supplied.
   - :provider-customer-id reuses an existing Stripe Customer; Stripe rejects
     customer + customer_email together, so :customer-email is only sent when
     there is no existing customer.
   - :metadata is propagated to both the session and the underlying
     PaymentIntent (payment_intent_data[metadata]) so webhook payment_intent.*
     events carry it. The internal :checkout-id correlation key always wins."
  [{:keys [amount-cents currency description redirect-url success-url cancel-url
           checkout-id metadata setup-future-usage customer-email
           provider-customer-id]}]
  ;; Unknown :setup-future-usage values are ignored (schema validation guards
  ;; the boundary; a pure fn must not throw).
  (let [setup-future-usage-param ({:off-session "off_session"
                                   :on-session  "on_session"} setup-future-usage)]
    (cond-> {"line_items[0][price_data][currency]"           (str/lower-case (or currency "eur"))
             "line_items[0][price_data][unit_amount]"        (str amount-cents)
             "line_items[0][price_data][product_data][name]" description
             "line_items[0][quantity]"                       "1"
             "payment_method_types[0]"                       "card"
             "mode"                                          "payment"
             ;; Blank-safe: an empty/whitespace override (e.g. an unset
             ;; PUBLIC_BASE_URL upstream) must NOT win over redirect-url — Stripe
             ;; rejects an empty success_url with a 400 (BOU-127). `or` alone
             ;; treats "" as truthy, so defer blank overrides to redirect-url.
             "success_url"                                   (if (str/blank? success-url) redirect-url success-url)
             "cancel_url"                                    (if (str/blank? cancel-url) redirect-url cancel-url)}
      setup-future-usage-param
      (assoc "payment_intent_data[setup_future_usage]" setup-future-usage-param)

      (and setup-future-usage-param (nil? provider-customer-id))
      (assoc "customer_creation" "always")

      provider-customer-id
      (assoc "customer" provider-customer-id)

      (and customer-email (nil? provider-customer-id))
      (assoc "customer_email" customer-email)

      (seq metadata)
      (merge (stripe-metadata-params "metadata" metadata)
             (stripe-metadata-params "payment_intent_data[metadata]" metadata))

      ;; Internal correlation id — set last so user metadata cannot clobber it.
      true
      (assoc "payment_intent_data[metadata][checkout_id]" checkout-id))))

(defn stripe-off-session-params
  "Build the flat form-params map for an off-session PaymentIntent charge:
   confirm=true + off_session=true against a stored customer. When
   :provider-payment-method-id is omitted Stripe falls back to the customer's
   default payment method (it must have one, or the confirm fails)."
  [{:keys [amount-cents currency description provider-customer-id
           provider-payment-method-id metadata]}]
  (cond-> {"amount"      (str amount-cents)
           "currency"    (str/lower-case (or currency "eur"))
           "customer"    provider-customer-id
           "off_session" "true"
           "confirm"     "true"}
    description                (assoc "description" description)
    provider-payment-method-id (assoc "payment_method" provider-payment-method-id)
    (seq metadata)             (merge (stripe-metadata-params "metadata" metadata))))
