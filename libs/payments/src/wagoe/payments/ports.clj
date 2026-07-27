(ns wagoe.payments.ports
  "Payment provider abstraction — enables swappable PSP adapters (Mock, Mollie, Stripe).")

(defprotocol IPaymentProvider
  (provider-name [this])
  ;; Returns a keyword identifying the provider: :mock, :mollie, :stripe

  (create-checkout-session [this {:keys [amount-cents currency description
                                         redirect-url success-url cancel-url
                                         webhook-url metadata
                                         setup-future-usage customer-email
                                         provider-customer-id]}])
  ;; Hosted checkout for the first payment. Pass :setup-future-usage :off-session
  ;; to store a mandate/payment-method for later off-session charges
  ;; (Stripe: setup_future_usage=off_session; Mollie throws {:type :not-implemented}
  ;; until sequenceType=first support lands).
  ;; :success-url/:cancel-url override :redirect-url when given (Stripe).
  ;; Returns {:checkout-url "..." :provider-checkout-id "..."
  ;;          :correlation-id "..."         ; internal id echoed by the webhook —
  ;;                                         ; store this to correlate (see process-webhook)
  ;;          :provider-payment-id "..."}   ; optional, when known at creation

  (create-off-session-payment [this {:keys [amount-cents currency description
                                            provider-customer-id
                                            provider-payment-method-id
                                            metadata idempotency-key]}])
  ;; Charge a stored customer + payment method without user interaction
  ;; (recurring billing). :provider-payment-method-id is optional — providers
  ;; may fall back to the customer's stored default mandate.
  ;; :idempotency-key is optional — the stable business identity of the charge
  ;; (e.g. "incasso-<subscription>-<period>"). A retry with the same key returns
  ;; the original charge instead of double-charging (Stripe: Idempotency-Key
  ;; header; Mock: in-memory cache; Mollie: n/a, throws :not-implemented).
  ;; Returns {:provider-payment-id "..." :status :pending|:paid|:failed}

  (get-payment-status [this provider-checkout-id])
  ;; Stripe accepts both Checkout Session ids (cs_...) and PaymentIntent ids
  ;; (pi_...), dispatched by prefix; Mollie takes a payment id.
  ;; Returns {:status :pending|:paid|:failed|:cancelled|:expired|:chargeback
  ;;          :provider-payment-id "..."
  ;;          :provider-customer-id "..."          ; optional, mandate follow-up
  ;;          :provider-payment-method-id "..."}   ; optional, mandate follow-up

  (expire-checkout-session [this provider-checkout-id])
  ;; Expire an abandoned checkout session so it can no longer be completed.
  ;; Returns {:provider-checkout-id "..." :status :expired}

  (process-webhook [this raw-body headers])
  ;; Returns {:event-type :payment.paid|:payment.failed|:payment.cancelled
  ;;                       |:payment.expired|:payment.authorized|nil
  ;;                       ; nil for a signature-verified event with no generic
  ;;                       ; payment.* outcome (checkout.session.*, disputes, …);
  ;;                       ; the consumer routes those by :payload type or
  ;;                       ; acknowledges-and-ignores. Never throws on an
  ;;                       ; unmapped-but-parseable event (BOU-147).
  ;;          :provider-payment-id "..."
  ;;          :correlation-id "..."          ; matches create-checkout-session's
  ;;                                         ; :correlation-id — correlate on THIS
  ;;          :provider-checkout-id "..."    ; optional: genuine session id only
  ;;                                         ; when the payload carries one
  ;;          :payload {...}}

  (verify-webhook-signature [this raw-body headers]))
  ;; Provider-agnostic signature check over the RAW body string + headers
  ;; (Stripe: HMAC via Stripe-Signature header; Mollie: no HMAC, always true).
  ;; Returns boolean
