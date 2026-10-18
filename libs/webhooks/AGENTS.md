# wagoe-webhooks — Outbound Webhooks

Bus events delivered to subscriber URLs: signed, retried through the jobs
queue, filtered per tenant, and logged per attempt.

## Layout

| Namespace | Role |
|---|---|
| `wagoe.webhooks` | Receiver helpers: `sign`, `verify-signature`, `signature-problem` |
| `wagoe.webhooks.ports` | `ISubscriptionStore`, `IDeliveryLog`, `IWebhookTransport`, `IWebhooks` |
| `wagoe.webhooks.core.signature` | The `t=…,v1=…` header, pure |
| `wagoe.webhooks.core.delivery` | Topic globs, tenant filter, payload, URL shape, attempt outcome |
| `wagoe.webhooks.shell.service` | `IWebhooks`: create subscriptions, dispatch events to jobs, deliver |
| `wagoe.webhooks.shell.ssrf` | Refuses private addresses, honours `:allowed-hosts` |
| `wagoe.webhooks.shell.http-transport` | Apache HttpClient, timeouts, no redirects, connects to the checked addresses |
| `wagoe.webhooks.shell.crypto` | `enc:v1:` AES-GCM for secrets at rest |
| `wagoe.webhooks.shell.persistence` | Both tables on the application database |
| `wagoe.webhooks.shell.module-wiring` | Integrant keys; `ig-config` |

## Flow

1. `:wagoe.webhooks/subscriber` subscribes to each bus topic in `:topics`.
2. `dispatch!` picks the active subscriptions for the event's tenant whose
   `:event-pattern` matches the topic, and enqueues one `:webhooks/deliver` job
   each, with the JSON body built once. The idempotency store keeps a
   redelivered event from enqueueing twice.
3. The job checks the URL again, signs `<t>.<body>`, POSTs, and records the
   attempt. A non-2xx fails the job, so jobs retries it; after `:max-attempts`
   it is dead-lettered and the last log row says `dead`. A refused target is
   logged `refused` and not retried.

Events published through the events outbox reach the bus after commit, so
nothing is sent for a rolled-back transaction.

## Rules

- A subscription with `:tenant-id` gets only that tenant's events; one without
  gets all of them.
- Secrets are encrypted with `:encryption-key`, falling back to `JWT_SECRET`.
  Changing the key makes existing secrets unreadable.
- `ig-config` refuses to build without `:wagoe/jobs`, and with `:topics` but
  no `:wagoe/events`.
- Each attempt resolves the host once: the SSRF check judges the answer and the
  request connects to those addresses, keeping the URL's host for the Host
  header, TLS SNI and the certificate check. A rebinding DNS answer gets no
  second lookup.

## Tests

```bash
clojure -M:test :webhooks
WAGOE_TEST_DB=pg clojure -M:test:test/pg:test/pg-mac :webhooks   # mysql: bb test:services up
```
