(ns wagoe.payments.schema
  "Malli schemas for the payment provider abstraction layer.")

(def PaymentStatus
  "Normalized payment status across providers. Aligned with the consumer-side
   PaymentStatus enum in boundary-license (:pending :paid :failed :expired
   :chargeback) plus :cancelled for user/PSP-cancelled payments."
  [:enum :pending :paid :failed :cancelled :expired :chargeback])

(def OffSessionPaymentStatus
  "Statuses an off-session charge can report at creation time. Subset of
   PaymentStatus — a just-created charge cannot be cancelled, expired or
   charged back."
  [:enum :pending :paid :failed])

(def CheckoutRequest
  [:map
   [:amount-cents  pos-int?]
   [:currency      [:string {:min 3 :max 3}]]
   [:description   :string]
   [:redirect-url  :string]
   ;; Optional explicit success/cancel URLs (Stripe Checkout). Both fall back
   ;; to :redirect-url when omitted.
   [:success-url   {:optional true} [:maybe :string]]
   [:cancel-url    {:optional true} [:maybe :string]]
   [:webhook-url   {:optional true} [:maybe :string]]
   [:metadata      {:optional true} [:maybe :map]]
   ;; Mandate options — request storage of the payment method for later
   ;; off-session charges (Stripe: setup_future_usage; Mollie: sequenceType).
   [:setup-future-usage   {:optional true} [:maybe [:enum :off-session :on-session]]]
   [:customer-email       {:optional true} [:maybe [:re #".+@.+"]]]
   [:provider-customer-id {:optional true} [:maybe :string]]])

(def CheckoutResult
  [:map
   [:checkout-url        :string]
   [:provider-checkout-id :string]
   ;; Adapter-internal correlation id embedded in the PSP's payment metadata at
   ;; session creation. The webhook surfaces the SAME value as :correlation-id,
   ;; so a consumer can match every webhook to its stored checkout using only
   ;; fields available here — unlike :provider-checkout-id, which a webhook
   ;; payload may not carry (Stripe payment_intent.* events have no session id).
   [:correlation-id      :string]
   ;; Some providers expose the underlying payment id at session creation.
   [:provider-payment-id {:optional true} [:maybe :string]]])

(def OffSessionPaymentRequest
  [:map
   [:amount-cents          pos-int?]
   [:currency              [:string {:min 3 :max 3}]]
   [:description           :string]
   [:provider-customer-id  :string]
   ;; Optional — providers may use the customer's stored default mandate.
   [:provider-payment-method-id {:optional true} [:maybe :string]]
   [:metadata              {:optional true} [:maybe :map]]
   ;; Optional provider-side idempotency key — the stable business identity of
   ;; the charge (e.g. "incasso-<subscription>-<period>"). A retry with the same
   ;; key returns the original charge instead of creating a duplicate (Stripe:
   ;; Idempotency-Key header; Mock: in-memory cache; Mollie: n/a).
   [:idempotency-key       {:optional true} [:maybe :string]]])

(def OffSessionPaymentResult
  [:map
   [:provider-payment-id :string]
   [:status              OffSessionPaymentStatus]])

(def PaymentStatusResult
  [:map
   [:status              PaymentStatus]
   [:provider-payment-id {:optional true} [:maybe :string]]
   ;; Mandate details, when the provider exposes them — lets consumers store
   ;; the customer + payment method after the first checkout completes.
   [:provider-customer-id       {:optional true} [:maybe :string]]
   [:provider-payment-method-id {:optional true} [:maybe :string]]])

(def ExpireCheckoutResult
  [:map
   [:provider-checkout-id :string]
   [:status               [:enum :expired]]])

(def WebhookResult
  [:map
   ;; nil for a signature-verified event whose provider type maps to no generic
   ;; payment.* outcome (e.g. Stripe checkout.session.* / charge.dispute.* and any
   ;; other type a connected endpoint receives). The adapter surfaces these as
   ;; :event-type nil + the full :payload rather than throwing (BOU-147), so the
   ;; consumer can route them by payload type or acknowledge-and-ignore. A
   ;; non-nil value is always one of the generic payment.* keywords.
   [:event-type           [:maybe [:enum :payment.paid :payment.failed :payment.cancelled
                                   :payment.expired :payment.authorized]]]
   [:provider-payment-id  {:optional true} [:maybe :string]]
   ;; Adapter-internal correlation id recovered from the PSP payment metadata —
   ;; matches CheckoutResult/:correlation-id. This is the field consumers should
   ;; correlate on; it is provider-agnostic and always present when the checkout
   ;; was created through this adapter.
   [:correlation-id       {:optional true} [:maybe :string]]
   ;; The genuine provider session id (cs_…/tr_…), only when the webhook payload
   ;; actually carries one. Absent for Stripe payment_intent.* events.
   [:provider-checkout-id {:optional true} [:maybe :string]]
   [:payload              :map]])
