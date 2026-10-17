# wagoe-webhooks

[![Status](https://img.shields.io/badge/status-incubating-yellow)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-webhooks.svg)](https://clojars.org/com.wagoe/wagoe-webhooks)

> Outbound webhooks for Wagoe: bus events delivered as signed HTTP requests,
> one retried job per delivery, per-tenant subscriptions, and a delivery log in
> the admin.

```clojure
;; deps.edn
{:deps {com.wagoe/wagoe-webhooks {:mvn/version "1.0.1"}}}
```

```clojure
;; config.edn, under :active — needs :wagoe/jobs, and :wagoe/events for :topics
:wagoe/webhooks {:topics [:orders/placed]}
```

```clojure
(require '[wagoe.webhooks.ports :as webhooks])

(webhooks/create-subscription! (:wagoe/webhooks system)
                               {:url "https://example.com/hooks" :event-pattern "orders/*"})
;; => {:subscription {...} :secret "whsec_..."}   the secret is shown once
```

A receiver checks the `Wagoe-Signature` header against the raw body, and
drops repeats by `Wagoe-Event-Id`:

```clojure
(require '[wagoe.webhooks :as webhooks])

(webhooks/verify-signature secret raw-body (get-in request [:headers "wagoe-signature"]))
```

Private, loopback and link-local targets are refused unless listed in
`:allowed-hosts`; HTTPS is required outside dev and test; redirects are not
followed.

Documentation: [docs/modules/libraries/pages/webhooks.adoc](../../docs/modules/libraries/pages/webhooks.adoc).
