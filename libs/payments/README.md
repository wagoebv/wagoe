# wagoe/payments

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-payments.svg)](https://clojars.org/com.wagoe/wagoe-payments)

PSP (Payment Service Provider) abstraction — a single `IPaymentProvider` protocol that decouples application code from Mollie, Stripe, and Mock across the checkout-session flow: create → redirect → webhook → status.

## Installation

**deps.edn** (recommended):
```clojure
{:deps {com.wagoe/wagoe-payments {:mvn/version "1.0.0-beta-1"}}}
```

**Leiningen**:
```clojure
[com.wagoe/wagoe-payments "1.0.0-beta-1"]
```

## Features

| Feature | Description |
|---------|-------------|
| **PSP abstraction** | One `IPaymentProvider` protocol; swap providers with zero business-logic changes |
| **Adapters** | Mollie, Stripe, and Mock — selected by config |
| **Checkout-session flow** | Hosted checkout, status polling, and abandoned-session expiry |
| **Off-session charges** | Idempotency-key-safe recurring billing against a stored mandate (Stripe, Mock) |
| **Webhook verification** | Provider-agnostic signature check over the raw body + normalized event types |
| **Boot-time validation** | `init-key` fails the boot when a provider is missing a required credential |

## Quick Start

### Configuration (Integrant)

```clojure
;; config.edn — one provider at a time; switch by changing :provider
:wagoe/payment-provider
{:provider         :mock       ; :mock | :mollie | :stripe
 :api-key          #env PSP_API_KEY
 :webhook-secret   #env PSP_WEBHOOK_SECRET   ; Stripe only
 :webhook-base-url #env APP_BASE_URL}        ; Mollie only
```

### Create a checkout session

```clojure
(ns myapp.checkout
  (:require [wagoe.payments.ports :as ports]))

(ports/create-checkout-session payment-provider
  {:amount-cents 11900          ; integer cents — 11900 = €119.00
   :currency     "EUR"          ; ISO 4217, uppercase
   :description  "Order #1234"
   :redirect-url "https://app.example.com/return"
   :webhook-url  "https://app.example.com/web/payment/webhook"})
;; => {:checkout-url "..." :provider-checkout-id "..." :correlation-id "..."}
```

Store the returned `:correlation-id` — it is the one field that round-trips
across every provider and is how you match an incoming webhook to its checkout.

### Verify and process a webhook

```clojure
(defn handle-payment-webhook [request payment-provider]
  (let [raw-body (slurp (:body request))   ; RAW string — before body parsing
        headers  (:headers request)]
    (if (ports/verify-webhook-signature payment-provider raw-body headers)
      (let [{:keys [event-type correlation-id]}
            (ports/process-webhook payment-provider raw-body headers)]
        (case event-type
          :payment.paid      (activate-order! correlation-id)
          :payment.failed    (mark-order-failed! correlation-id)
          :payment.cancelled (cancel-order! correlation-id)
          nil))                              ; ignore unmapped events
      {:status 400 :body "Invalid signature"})))
```

The webhook route must receive the **raw body string** before any body-parsing
middleware — parsing it first breaks Stripe HMAC verification.

Other protocol methods: `create-off-session-payment`, `get-payment-status`,
`expire-checkout-session`, `provider-name`.

## Providers

| Provider | Key | Required credentials | Env vars | Notes |
|----------|-----|----------------------|----------|-------|
| **Mock** | `:mock` | none | — | Auto-approves; no network calls. Use in dev and all tests |
| **Mollie** | `:mollie` | `:api-key`, `:webhook-base-url` | `MOLLIE_API_KEY`, `APP_BASE_URL` | Form-POST webhook, no HMAC; off-session/expiry throw `:not-implemented` |
| **Stripe** | `:stripe` | `:api-key`, `:webhook-secret` | `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET` | HMAC-SHA256 webhooks; full off-session + expiry support |

A configured provider missing a required credential (e.g. a forgotten env var
resolving to nil) **fails the boot** with a `{:type :config-error}` error naming
each missing key. In tests, build the Mock directly:

```clojure
(require '[wagoe.payments.shell.adapters.mock :as mock])
(def test-provider (mock/make-mock-provider))
```

## Documentation

- [Development guide](AGENTS.md) — protocol reference, adapter behaviour, gotchas
- [Payments library docs](../../docs/modules/libraries/pages/payments.adoc)

## License

Copyright © 2024-2026 Thijs Creemers

Distributed under the Eclipse Public License version 2.0.
