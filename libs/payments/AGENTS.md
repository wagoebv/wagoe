# Payments Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

PSP (Payment Service Provider) abstraction for the Boundary Framework.
Provides a single `IPaymentProvider` protocol that decouples application code
from specific payment processors. Swap between Mollie, Stripe, and Mock without
changing any business logic.

**Scope:** checkout-session flow — create → redirect → webhook → status.
This is the only payment library in the framework; `libs/external` handles
communication channels (SMTP, IMAP, Twilio) but not payments.

---

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.payments.ports` | `IPaymentProvider` protocol definition |
| `boundary.payments.schema` | Malli schemas: `CheckoutRequest`, `CheckoutResult`, `OffSessionPaymentRequest`, `OffSessionPaymentResult`, `PaymentStatusResult`, `ExpireCheckoutResult`, `WebhookResult` |
| `boundary.payments.core.provider` | Pure helpers: `cents->euro`, `normalize-event-type`, status mappers |
| `boundary.payments.shell.adapters.mock` | Mock adapter — auto-approves, no network calls |
| `boundary.payments.shell.adapters.mollie` | Mollie PSP adapter (hato HTTP client) |
| `boundary.payments.shell.adapters.stripe` | Stripe PSP adapter (hato + HMAC webhook verification) |
| `boundary.payments.shell.module-wiring` | Integrant `init-key` / `halt-key!` |

---

## Protocol — `IPaymentProvider`

```clojure
(create-checkout-session [this opts])
;; opts: {:amount-cents pos-int
;;        :currency     "EUR"        ; ISO 4217
;;        :description  string
;;        :redirect-url string       ; where PSP sends the user after payment
;;        :success-url  string       ; optional; Stripe; defaults to :redirect-url
;;        :cancel-url   string       ; optional; Stripe; defaults to :redirect-url
;;        :webhook-url  string       ; optional; Mollie uses this
;;        :metadata     map          ; optional; passed through to PSP
;;        :setup-future-usage :off-session|:on-session ; optional; store mandate
;;                                   ; Mock/Stripe only; Mollie throws {:type :not-implemented}
;;        :customer-email       string  ; optional
;;        :provider-customer-id string} ; optional; reuse existing PSP customer
;; Returns: {:checkout-url string :provider-checkout-id string
;;           :correlation-id string       ; internal id echoed by the webhook
;;           :provider-payment-id string} ; optional, when known at creation

(create-off-session-payment [this opts])
;; opts: {:amount-cents pos-int
;;        :currency     "EUR"
;;        :description  string
;;        :provider-customer-id       string  ; required
;;        :provider-payment-method-id string  ; optional; default mandate otherwise
;;        :metadata     map                   ; optional
;;        :idempotency-key string}            ; optional; retry-safe charge identity
;; Returns: {:provider-payment-id string :status :pending|:paid|:failed}
;; Implemented by Mock and Stripe; Mollie throws {:type :not-implemented}
;;
;; Idempotency (consumer pattern): pass :idempotency-key = the *stable business
;; identity* of the charge — the same logical charge must always produce the same
;; key (e.g. "incasso-<subscription>-<period>"), never a random UUID. A retry
;; after a crash/lost response then returns the original charge instead of
;; double-charging. Stripe sends it as the Idempotency-Key header; the Mock
;; remembers keys in-memory and replays the first result; Mollie ignores it.

(get-payment-status [this provider-checkout-id])
;; Stripe accepts both cs_... (Checkout Session) and pi_... (PaymentIntent)
;; ids, dispatched by prefix; Mollie takes a payment id.
;; Returns: {:status :pending|:paid|:failed|:cancelled|:expired|:chargeback
;;           :provider-payment-id string
;;           :provider-customer-id       string   ; optional, mandate follow-up
;;           :provider-payment-method-id string}  ; optional, mandate follow-up

(expire-checkout-session [this provider-checkout-id])
;; Returns: {:provider-checkout-id string :status :expired}
;; Implemented by Mock and Stripe; Mollie throws {:type :not-implemented}

(verify-webhook-signature [this raw-body headers])
;; Returns: boolean

(process-webhook [this raw-body headers])
;; Returns: {:event-type           keyword
;;           :provider-payment-id  string
;;           :correlation-id       string  ; matches CheckoutResult/:correlation-id
;;           :provider-checkout-id string  ; optional; only when the payload
;;                                         ; carries a genuine session id
;;           :payload              map}
```

### Correlating a webhook to its checkout (BOU-78)

**Store `:correlation-id` from the checkout result and match webhooks on it.**
It is the one field guaranteed to round-trip across every provider. Do **not**
correlate on `:provider-checkout-id` — a Stripe `payment_intent.*` webhook never
carries the `cs_…` session id, so it is absent from the webhook result.

Which id appears where:

| Field | create-checkout-session | get-payment-status | process-webhook |
|-------|-------------------------|--------------------|-----------------|
| `:correlation-id` (internal UUID) | ✅ always | — | ✅ always (from metadata) |
| `:provider-checkout-id` | ✅ `cs_…` / `tr_…` | — (the input arg) | ⛔ Stripe PI events: absent; Mollie: absent |
| `:provider-payment-id` | optional (`pi_…` if known) | ✅ `pi_…` / `tr_…` | ✅ `pi_…` / `tr_…` |

- **Mock**: `:correlation-id` == `:provider-checkout-id` (both the generated UUID).
- **Stripe**: `:correlation-id` is the UUID written to `payment_intent_data[metadata][checkout_id]` at creation and read back from PI metadata in the webhook.
- **Mollie**: `:correlation-id` is the UUID written to `metadata.checkout-id`; the webhook's `:provider-payment-id` (`tr_…`) equals the creation `:provider-checkout-id`.

### Normalized event types

| Keyword | Meaning |
|---------|---------|
| `:payment.paid` | Payment successfully completed |
| `:payment.authorized` | Authorized, not yet captured |
| `:payment.failed` | Payment attempt failed |
| `:payment.cancelled` | Cancelled by user or PSP |
| `:payment.expired` | Checkout/payment expired before completion |

---

## Adapters

### Mock (`:mock`)
- Auto-approves every payment — no network calls
- `create-checkout-session` → `{:checkout-url "/web/payment/mock-return?checkout-id=<uuid>"}`
- `create-off-session-payment` → `{:status :paid}`; override the simulated
  outcome with `:metadata {:mock-status :failed}` (`:pending`, `:paid` or
  `:failed` — anything else falls back to `:paid`). A repeated
  `:idempotency-key` replays the first result (per-instance atom cache) — build
  the provider with `mock/make-mock-provider`, not the bare
  `->MockPaymentProvider` ctor, to get the cache
- `verify-webhook-signature` → always `true`
- `get-payment-status` → always `{:status :paid}` plus mock
  `provider-customer-id`/`provider-payment-method-id` mandate fields
- `expire-checkout-session` → `{:status :expired}`
- Use in development and all automated tests

### Mollie (`:mollie`)
- REST API: `https://api.mollie.com/v2/payments`
- Webhook: form-POST with `id=<payment-id>` (no HMAC signing)
- `verify-webhook-signature` → always `true`; real verification happens via `get-payment-status`
- Status mapping: `"paid"` → `:payment.paid`, `"failed"` → `:payment.failed`, `"canceled"/"cancelled"` → `:payment.cancelled`, `"expired"` → `:payment.expired`
- Requires `:api-key` and `:webhook-base-url`
- No mandate support yet: `create-checkout-session` with `:setup-future-usage` throws `{:type :not-implemented}` (sequenceType=first pending), as do `create-off-session-payment` and `expire-checkout-session`

### Stripe (`:stripe`)
- REST API: `https://api.stripe.com/v1` (Checkout Sessions + PaymentIntents)
- Webhook: HMAC-SHA256 over `timestamp.raw-body`, verified with `Stripe-Signature` header
- Event mapping: `"payment_intent.succeeded"` → `:payment.paid`, etc.
- Requires `:api-key` and `:webhook-secret`
- **Checkout**: mode `payment`; `:setup-future-usage :off-session` sends
  `payment_intent_data[setup_future_usage]=off_session` and (without an
  existing `:provider-customer-id`) `customer_creation=always` so the saved
  payment method has a Customer to attach to. Stripe rejects
  `customer` + `customer_email` together, so `:customer-email` is only sent
  when no `:provider-customer-id` is given.
- **Off-session**: confirmed PaymentIntent (`off_session=true`, `confirm=true`)
  against a stored customer (+ optional payment method, default otherwise).
  Card errors from confirm (`card_declined`, `authentication_required`, ...)
  return `{:status :failed}` with the failed PaymentIntent id — they do not
  throw. Auth/config errors throw `{:type :internal-error}`. An
  `:idempotency-key` is sent as the `Idempotency-Key` HTTP header on
  `POST /v1/payment_intents`, so a retry returns the original PaymentIntent.
- **Status poll** (`get-payment-status`): accepts `cs_...` and `pi_...` ids
  (prefix dispatch). Session polls expand the PaymentIntent so a completed
  checkout exposes `provider-customer-id`/`provider-payment-method-id` for
  mandate storage.
- **Status mapping** — PaymentIntent: `succeeded` → `:paid`,
  `requires_payment_method`/`requires_confirmation`/`requires_capture`/
  `processing` → `:pending`; `canceled` and `requires_action` → `:cancelled` /
  `:pending` when polling, both `:failed` for an off-session charge (SCA
  cannot complete unattended; port contract is `:pending|:paid|:failed`).
  Checkout Session: `expired` → `:expired`, `payment_status`
  `paid`/`no_payment_required` → `:paid`, else `:pending`.
- Metadata keys/values are truncated to Stripe's limits (40/500 chars); the
  50-keys-per-object limit is not enforced.
- 401/403 on status poll throws; other poll failures (404, 5xx) degrade to
  `{:status :pending}` without a `:provider-payment-id` key, so merging
  callers cannot clobber a stored id (Mollie symmetry).

---

## Integrant

```clojure
;; config.edn
:wagoe/payment-provider
{:provider        :mock        ; :mock | :mollie | :stripe
 :api-key         #env PSP_API_KEY
 :webhook-secret  #env PSP_WEBHOOK_SECRET   ; Stripe only
 :webhook-base-url #env APP_BASE_URL}       ; Mollie only
```

Only one key, one provider at a time. Switch providers by changing `:provider` — no code changes.

### Boot-time credential validation (BOU-77)

`init-key` **fails the boot** when a configured provider is missing a required
credential — typically a forgotten env var, where Aero `#env` resolves to nil.
Without this guard the system boots fine and only fails at runtime (Stripe 401
`Bearer null` on the first charge, HMAC verification against a nil secret on the
first webhook), shipping a payment system that silently takes no money.

| Provider | Required (fails boot if nil/blank) | Env var |
|----------|-----------------------------------|---------|
| `:stripe` | `:api-key`, `:webhook-secret` | `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET` |
| `:mollie` | `:api-key` | `MOLLIE_API_KEY` |
| `:mock` | none | — |

On a missing/blank credential it throws `ex-info` with
`{:type :config-error :provider … :missing-keys [...] :env-vars [...]}` and a
message naming each missing key + env var, e.g.:

```
Stripe payment provider configured but :api-key, :webhook-secret nil/blank —
set STRIPE_API_KEY, STRIPE_WEBHOOK_SECRET (see resources/conf/<env>/config.edn)
```

`:provider` itself unknown still throws `{:type :internal-error}` (unchanged).

---

## Pure Helpers (`boundary.payments.core.provider`)

```clojure
(provider/cents->euro 11900)           ;=> "119.00"
(provider/mollie-status->event-type "paid")             ;=> :payment.paid
(provider/stripe-event->event-type "payment_intent.succeeded") ;=> :payment.paid
(provider/normalize-event-type "paid" :mollie)          ;=> :payment.paid
```

---

## Webhook handling pattern

Always verify the signature before processing:

```clojure
(defn handle-payment-webhook [request payment-provider]
  (let [raw-body (slurp (:body request))
        headers  (:headers request)]
    (if (ports/verify-webhook-signature payment-provider raw-body headers)
      ;; Correlate on :correlation-id — the field that round-trips across all
      ;; providers. (:provider-checkout-id is absent for Stripe PI webhooks.)
      (let [{:keys [event-type correlation-id]}
            (ports/process-webhook payment-provider raw-body headers)]
        (case event-type
          :payment.paid      (activate-order! correlation-id)
          :payment.failed    (mark-order-failed! correlation-id)
          :payment.cancelled (cancel-order! correlation-id)
          nil)) ; ignore unknown event types
      {:status 400 :body "Invalid signature"})))
```

**Important:** the webhook route must receive the **raw body string** before any body-parsing middleware. Parsing the body first breaks HMAC verification for Stripe.

---

## Gotchas

1. **Raw body for webhooks** — Stripe HMAC is computed over the raw string. Never pass a parsed map to `verify-webhook-signature` or `process-webhook`.
2. **Mollie has no HMAC** — `verify-webhook-signature` always returns `true` for Mollie. Verify by calling `get-payment-status` with the payment ID from the webhook body.
3. **Mollie `"canceled"` vs `"cancelled"`** — Mollie uses the American spelling. Both are mapped to `:payment.cancelled`.
4. **Amounts in cents** — always use integer cents (11900 = €119.00). Never pass floats or euro strings.
5. **Currency as ISO 4217 string** — `"EUR"`, `"USD"`. Stripe lowercases it automatically; Mollie requires uppercase.
6. **Mock checkout URL** — `/web/payment/mock-return?checkout-id=<uuid>` is a local redirect; your app must have a handler for it in development.
7. **Provider is stateless** — records hold only config; safe to share across threads.

---

## Testing

```bash
clojure -M:test:db/h2 :payments        # All payments tests
clojure -M:test:db/h2 --focus-meta :unit  # Pure core functions only
```

Use the `:mock` provider in all tests — no PSP credentials required.

```clojure
;; In test setup
(def test-provider (mock/make-mock-provider))
```

---

## Links

- [Payments How-to Guide](../../docs/modules/guides/pages/payments.adoc)
- [Root AGENTS Guide](../../AGENTS.md)
