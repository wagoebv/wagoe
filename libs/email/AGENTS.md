# Email Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Application-layer email sending: an `Email` domain model, pure preparation and
validation, and pluggable sender adapters. Raw SMTP transport is delegated to
`libs/external`. Sending comes in several flavours — synchronous, async
(`future`-based), in-process queued (`EmailQueueProtocol`), and distributed
queued (via the optional `libs/jobs` module). A `:wagoe/email` Integrant key
builds the sender from config.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `wagoe.email.core.email` | Pure functions: validate addresses/recipients, normalize + prepare emails, format headers, add reply-to/cc/bcc, summarize |
| `wagoe.email.ports` | Protocols: `EmailSenderProtocol`, `EmailQueueProtocol` |
| `wagoe.email.schema` | Malli schemas: `EmailAddress`, `Attachment`, `Email`, `SendEmailInput`, `EmailValidationResult`, `RecipientValidationResult`, `EmailSummary` + validators |
| `wagoe.email.shell.adapters.smtp` | Production SMTP sender — thin wrapper over `libs/external`'s `SmtpProviderAdapter` |
| `wagoe.email.shell.adapters.logging` | Dev/test sender — records + prints emails instead of delivering them |
| `wagoe.email.shell.adapters.queue` | `InMemoryEmailQueue` — in-process `EmailQueueProtocol` impl, bounded retry |
| `wagoe.email.shell.jobs-integration` | Optional distributed queued sending via `libs/jobs` (loaded through `requiring-resolve`) |
| `wagoe.email.shell.module-wiring` | `:wagoe/email` + `:wagoe/email-queue` Integrant keys |

## Relationship to `libs/external`

`libs/email` is the **application/domain** layer; `libs/external` is the raw
**transport** layer. `email` depends on `external` (see `deps.edn`); there is no
direct `javax.mail` dependency here — it arrives transitively.

```
libs/email (domain)                         libs/external (transport)
  Email {id, created-at, metadata, ...}  →   OutboundEmail {to, from, subject, ...}
  EmailSenderProtocol                    →   ISmtpProvider  (javax.mail, TLS/SSL, HTML, MIME)
  shell/adapters/smtp SmtpEmailSender    →   shell/adapters/smtp SmtpProviderAdapter
```

`shell/adapters/smtp.clj`'s private `email->outbound` translates the domain
`Email` to an `OutboundEmail`, mapping the `:headers` sub-map (`:reply-to`,
`:cc`, `:bcc`) to top-level transport keys and passing `:attachments` through
(BOU-150). It then delegates to `external-ports/send-email!`.

> `libs/user`'s web handlers send welcome emails through this library's
> `EmailSenderProtocol` (the `:email-sender` injected into `:wagoe/user-routes`
> is the `:wagoe/email` component), not against `libs/external` directly.

## Ports

### `EmailSenderProtocol`
| Method | Signature | Returns |
|--------|-----------|---------|
| `send-email!` | `(send-email! this email)` | `{:success? bool :message-id "..."}` or `{:success? false :error {:message :type :provider-error}}` |
| `send-email-async!` | `(send-email-async! this email)` | a `future` wrapping the `send-email!` result map |

Implemented by `SmtpEmailSender` (real SMTP) and `LoggingEmailSender` (dev sink).

### `EmailQueueProtocol`
Defines `queue-email!`, `process-queue!`, `queue-size`, `peek-queue`. Implemented
by `shell.adapters.queue/InMemoryEmailQueue` — a single-process queue with
bounded retry (`:max-retries`), built via `:wagoe/email-queue`. For
**distributed** queuing across replicas use `shell.jobs-integration` (below); the
in-memory queue is per-process and lost on restart.

## Core functions (pure — `wagoe.email.core.email`)

- `(valid-email-address? s)` → boolean (basic RFC 5322 regex)
- `(validate-recipients recipients)` → `{:valid? :valid-emails :invalid-emails}`
- `(normalize-recipients recipients)` → always a vector
- `(format-headers headers)` → keywordized keys, stringified values
- `(prepare-email email-input email-id now)` → normalized `Email` map. **Takes
  three args:** the shell supplies `email-id` (a UUID) and `now` (a timestamp).
- `(validate-email email)` → `{:valid? :errors}`
- `(email-summary email)` → compact map for logging (recipient count, not list)
- `(add-reply-to email addr)` / `(add-cc email recips)` / `(add-bcc email recips)`
  → return a new `Email` with the header set under `:headers`

## Sending modes

| Mode | How to trigger | Backing |
|------|----------------|---------|
| **Sync** | `(ports/send-email! sender email)` | Blocks until the SMTP round-trip completes; immediate result. Use for critical mail (password reset, MFA). |
| **Async** | `(ports/send-email-async! sender email)` → deref the `future` | Plain Clojure `future` on the agent thread pool. Non-blocking, in-process (not durable across restarts). |
| **In-process queued** | `(ports/queue-email! q email)` + `(ports/process-queue! q)` | `InMemoryEmailQueue` — enqueue + drain with bounded retry. Single-process, lost on restart. |
| **Distributed queued** | `(jobs-integration/queue-email-job! job-queue sender email)` | Enqueues a `:send-email` job onto the `:emails` queue in `libs/jobs` (Redis/DB-backed, retryable). Requires the jobs module. |

## Jobs integration (`shell.jobs-integration`)

`libs/jobs` is an **optional** dependency, resolved at call time via
`requiring-resolve`. If it is absent, these throw `ex-info` with
`:type :missing-dependency`.

- `(queue-email-job! job-queue email-sender email)` — extracts SMTP config from
  `email-sender`, builds a `:send-email` job, enqueues it on `:emails`. Returns
  the job id.
- `(register-email-job-handler! job-registry)` — registers `process-email-job`
  for the `:send-email` job type. Call once at startup.
- `(process-email-job job-args)` — the handler: rebuilds an `SmtpEmailSender`
  from `:sender-config`, sends, returns `{:success? ... :result/:error}`.

## Wiring & configuration

`wagoe.email.shell.module-wiring` ships `:wagoe/email` (builds a sender —
`:provider :smtp` / `:logging`) and `:wagoe/email-queue` (in-memory queue over
a sender). The app refs `:wagoe/email` and threads it into
`:wagoe/user-routes` as `:email-sender`:

```clojure
:wagoe/email       {:provider :smtp :host "smtp.example.com" :port 587
                       :username "..." :password "..."}
;; or {:provider :logging} in dev
:wagoe/email-queue {:sender (ig/ref :wagoe/email) :max-retries 3}
```

You can also construct a sender directly (no Integrant) and pass it where needed:

```clojure
(require '[wagoe.email.shell.adapters.smtp :as smtp])

;; create-smtp-sender config keys:
;;   :host     (required)  SMTP hostname
;;   :port     (required)  SMTP port
;;   :username (optional)  auth user
;;   :password (optional)  auth password
;;   :tls?     (default true)   STARTTLS
;;   :ssl?     (default false)  implicit SSL
(def sender
  (smtp/create-smtp-sender
    {:host "smtp.gmail.com" :port 587
     :username "user@gmail.com" :password "app-password"
     :tls? true}))
```

Pull host/port/credentials from Aero config + env vars (`resources/conf/*`) at
the app layer; never hard-code them in a module.

## Usage example

```clojure
(require '[wagoe.email.core.email :as email]
         '[wagoe.email.ports :as ports]
         '[wagoe.email.shell.adapters.smtp :as smtp])

(def sender (smtp/create-smtp-sender {:host "localhost" :port 1025 :tls? false}))

;; prepare-email is pure — the SHELL supplies the id + timestamp:
(def prepared
  (email/prepare-email
    {:to "user@example.com" :from "no-reply@myapp.com"
     :subject "Welcome!" :body "Thanks for signing up"}
    (java.util.UUID/randomUUID)
    (java.time.Instant/now)))

;; :to is normalized to a vector; add optional headers (pure):
(def ready (-> prepared
               (email/add-reply-to "support@myapp.com")
               (email/add-cc "admin@myapp.com")))

(email/validate-email ready)      ;; => {:valid? true :errors []}
(ports/send-email! sender ready)  ;; => {:success? true :message-id "..."}

@(ports/send-email-async! sender ready)  ;; async: deref the future
```

### Dev sender (no real delivery)

`(log-sender/create-logging-sender)` returns a `LoggingEmailSender` that prints
to `*err*` and records emails in-memory instead of delivering them. Inspect with
`list-sent-emails` / `latest-sent-email`; reset with `clear-sent-emails!`.

## Common pitfalls

1. **`prepare-email` is 3-arity.** `(prepare-email input id now)` — the shell
   generates the UUID and timestamp so the core stays pure. Calling it with one
   arg is a bug.
2. **Recipients become vectors.** After `prepare-email`, `:to` is always a
   vector even when you passed a single string. `add-cc`/`add-bcc` join lists
   into a comma string inside `:headers`; the SMTP adapter re-splits them.
3. **`EmailQueueProtocol` has an in-process impl** (`InMemoryEmailQueue`) — good
   for single-node queued sending. For **durable, cross-replica** queuing use
   `jobs-integration/queue-email-job!` instead (the in-memory queue is lost on
   restart).
4. **Jobs module is optional.** `queue-email-job!` / `register-email-job-handler!`
   throw `{:type :missing-dependency}` when `libs/jobs` is not on the classpath.
5. **Gmail** requires a 16-char App Password, not the account password.
6. **Port ↔ transport security:** 587 → `:tls? true`; 465 → `:ssl? true`
   (`:tls? false`); 25 → both false. `create-smtp-sender` defaults `:tls? true`.
7. **HTML body:** the domain `Email` carries a plain-text `:body` only, and
   `email->outbound` does not set `:html-body`. `libs/external`'s adapter *can*
   render HTML multipart when `:html-body` is present — reach for the external
   transport directly (or extend `email->outbound`) if you need HTML.
8. **Attachments are supported** (BOU-150): put an `:attachments` vector on the
   `Email`; `email->outbound` passes it through to the external MIME builder.
   (Earlier docs claiming "no attachment support" are stale.)
9. **`LoggingEmailSender` holds a `defonce` atom** — a shell-layer dev sink,
   intentionally outside FC/IS core-purity rules.

## Local development (MailHog)

```bash
docker run -d -p 1025:1025 -p 8025:8025 mailhog/mailhog
# Sender config: {:host "localhost" :port 1025 :tls? false}
# Inbox UI:      http://localhost:8025
```

## Testing

```bash
clojure -M:test:db/h2 :email
```

Tests: `core/email_test.clj` (pure functions), `schema_test.clj` (Malli), and
`shell/adapters/smtp_test.clj` (adapter + `email->outbound` translation,
including attachment pass-through; tagged `^:integration`).

## Links

- [Library README](README.md)
- [Root AGENTS Guide](../../AGENTS.md)
- [external library](../external/AGENTS.md) — SMTP transport
- [jobs library](../jobs/AGENTS.md) — background/queued processing
