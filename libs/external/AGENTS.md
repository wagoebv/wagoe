# External Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

External third-party communication adapters following the FC/IS pattern.
Covers three integration channels: outbound email (SMTP), inbound email (IMAP), and SMS/WhatsApp (Twilio).

For payment processing (Mollie, Stripe), see [`libs/payments/AGENTS.md`](../payments/AGENTS.md).

## Key Namespaces

```
wagoe.external.schema           — Malli schemas for all three adapters
wagoe.external.ports            — Protocol definitions (ISmtpProvider, IImapMailbox, ITwilioMessaging)
wagoe.external.core.smtp        — Pure SMTP helpers (normalize-recipients, build-mime-properties)
wagoe.external.core.imap        — Pure IMAP helpers (parse-message-headers, extract-body-text,
                                     build-inbound-message, filter-by-date, filter-unread)
wagoe.external.core.twilio      — Pure Twilio helpers (build-sms-params, build-whatsapp-params,
                                     parse-message-response, parse-twilio-error)
wagoe.external.shell.adapters.smtp    — SmtpProviderAdapter (javax.mail)
wagoe.external.shell.adapters.imap    — ImapMailboxAdapter (javax.mail, UIDFolder)
wagoe.external.shell.adapters.twilio  — TwilioAdapter (clj-http, Basic auth)
wagoe.external.shell.module-wiring    — Integrant init/halt for :wagoe.external/* keys
```

## FC/IS Layout

```
libs/external/src/wagoe/external/
├── schema.clj          Malli schemas
├── ports.clj           Protocol definitions
├── core/
│   ├── smtp.clj        Pure SMTP helpers (no javax.mail I/O)
│   ├── imap.clj        Pure IMAP message parsing / filtering
│   └── twilio.clj      Pure Twilio param building + response parsing
└── shell/
    ├── adapters/
    │   ├── smtp.clj    SmtpProviderAdapter — javax.mail Transport
    │   ├── imap.clj    ImapMailboxAdapter — javax.mail Store/UIDFolder
    │   └── twilio.clj  TwilioAdapter — clj-http REST
    └── module_wiring.clj  Integrant multimethods
```

## Integrant Keys

All three keys are opt-in — add to `:active` in `config.edn` to enable:

```clojure
:wagoe.external/smtp
{:host     #env SMTP_HOST
 :port     587
 :username #env SMTP_USERNAME
 :password #env SMTP_PASSWORD
 :tls?     true
 :from     "noreply@example.com"}

:wagoe.external/imap
{:host     #env IMAP_HOST
 :port     993
 :username #env IMAP_USERNAME
 :password #env IMAP_PASSWORD
 :ssl?     true}

:wagoe.external/twilio
{:account-sid  #env TWILIO_ACCOUNT_SID
 :auth-token   #env TWILIO_AUTH_TOKEN
 :from-number  #env TWILIO_FROM_NUMBER}
```

## Dependencies

- `clj-http/clj-http 3.13.1` — Twilio REST calls
- `cheshire/cheshire 6.2.0` — JSON parsing
- `com.sun.mail/javax.mail 1.6.2` — SMTP and IMAP
- `metosin/malli 0.20.1` — schema validation

## Key Pitfalls

### IMAP UIDFolder Cast
`Message.getMessageNumber()` is NOT the IMAP UID. Always cast the folder:
```clojure
(let [uid-fld (cast com.sun.mail.imap.IMAPFolder folder)]
  (.getUID uid-fld message))
```

### IMAP Connection-Per-Call
`ImapMailboxAdapter` opens and closes a Store + Folder per operation. `close!` is a no-op (returns `true`). Use `try/finally` inside `with-imap-connection`.

### Twilio WhatsApp Sandbox
In dev, Twilio sandbox requires the sandbox number (`whatsapp:+14155238886`) as the From number. The adapter is config-agnostic — set `:from-number` accordingly.

## Local SMTP Development (Mailhog)

For dev/test, use [Mailhog](https://github.com/mailhog/MailHog) as a local SMTP server that captures all outgoing email without delivering it.

```bash
# Start Mailhog (SMTP on 1025, Web UI on 8025)
docker run -d -p 1025:1025 -p 8025:8025 mailhog/mailhog
```

**Web UI**: http://localhost:8025 — view all captured emails, search by sender/recipient/subject.

**Config** (`config.edn` dev profile):
```clojure
:wagoe.external/smtp
{:host "localhost" :port 1025 :tls? false :from "no-reply@localhost"}
```

**Key points:**
- No authentication needed — Mailhog accepts all connections
- `:tls?` must be `false` — Mailhog doesn't support TLS
- Emails are stored in memory only — restart clears all messages
- Use `docker run --name mailhog ...` to reuse the container: `docker start mailhog`

**Alternative** (no Docker): Use `LoggingEmailSender` from `libs/email` which logs emails to console and stores in an atom for REPL inspection:
```clojure
(require '[wagoe.email.shell.adapters.logging :as log-email])
(def sender (log-email/create-logging-sender))
;; After sending:
(log-email/list-sent-emails)  ;; all emails
(log-email/latest-sent-email) ;; most recent
(log-email/clear-sent-emails!) ;; reset
```

## Testing

```bash
# All external tests
clojure -M:test:db/h2 :external

# Unit tests only (pure functions, no I/O)
clojure -M:test:db/h2 --focus-meta :unit

# Integration tests (validates parsing; no real services needed)
clojure -M:test:db/h2 --focus-meta :integration
```

## Links

- [Payments AGENTS — PSP integration](../payments/AGENTS.md)
- [Root AGENTS Guide](../../AGENTS.md)
