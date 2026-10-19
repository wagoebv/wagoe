# External Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

External third-party communication adapters following the FC/IS pattern.
Covers outbound email (SMTP), inbound email (IMAP), SMS/WhatsApp (Twilio), file imports from an FTPS server, and SOAP 1.1 calls to WCF services.

For payment processing (Mollie, Stripe), see [`libs/payments/AGENTS.md`](../payments/AGENTS.md).

## Key Namespaces

```
wagoe.external.schema           — Malli schemas for all three adapters
wagoe.external.ports            — Protocol definitions (ISmtpProvider, IImapMailbox, ITwilioMessaging)
wagoe.external.core.smtp        — Pure SMTP helpers (normalize-recipients, build-mime-properties)
wagoe.external.core.imap        — Pure IMAP helpers (parse-message-headers, attachment-part?,
                                     build-attachment, mark-embedded, extract-body-text,
                                     build-inbound-message)
wagoe.external.core.twilio      — Pure Twilio helpers (build-sms-params, build-whatsapp-params,
                                     parse-message-response, parse-twilio-error)
wagoe.external.shell.adapters.smtp    — SmtpProviderAdapter (Jakarta Mail)
wagoe.external.shell.adapters.imap    — ImapMailboxAdapter (Jakarta Mail, UIDFolder)
wagoe.external.shell.adapters.twilio  — TwilioAdapter (clj-http, Basic auth)
wagoe.external.core.ftps        — Pure FTPS helpers (select-files, retryable?, ftps-error)
wagoe.external.shell.adapters.ftps    — FtpsFileSource implementing IFileSource (Commons Net)
wagoe.external.core.soap        — Pure SOAP helpers (operation-spec, envelope, read-envelope, retryable?)
wagoe.external.shell.adapters.soap    — SoapClient implementing ISoapClient (java.net.http, JDK DOM)
wagoe.external.shell.resilience      — Retry + circuit breaker for SMTP/IMAP/Twilio
                                         (wagoe.platform.resilience, shared with the RPC client)
wagoe.external.shell.module-wiring    — Integrant init/halt for :wagoe.external/* keys
```

## FC/IS Layout

```
libs/external/src/wagoe/external/
├── schema.clj          Malli schemas
├── ports.clj           Protocol definitions
├── core/
│   ├── smtp.clj        Pure SMTP helpers (no Jakarta Mail I/O)
│   ├── imap.clj        Pure IMAP message parsing
│   └── twilio.clj      Pure Twilio param building + response parsing
└── shell/
    ├── adapters/
    │   ├── smtp.clj    SmtpProviderAdapter — Jakarta Mail Transport
    │   ├── imap.clj    ImapMailboxAdapter — Jakarta Mail Store/UIDFolder
    │   └── twilio.clj  TwilioAdapter — clj-http REST
    └── module_wiring.clj  Integrant multimethods
```

## Integrant Keys

All five keys are opt-in — add to `:active` in `config.edn` to enable:

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

:wagoe.external/ftps
{:host         #env FTPS_HOST       ; the certificate must name it
 :port         21
 :username     #env FTPS_USERNAME
 :password     #env FTPS_PASSWORD
 :timeout-ms   30000                ; connect, control and data reads
 :max-attempts 3                    ; transient failures only
 :backoff-ms   1000                 ; doubled per retry
 ;; Only when the JVM's cacerts does not cover the server:
 :trust-store  "certs/ftps-trust.p12" :trust-store-password #env FTPS_TRUST_PASSWORD}

:wagoe.external/soap
{:endpoint       #env IBABS_ENDPOINT    ; e.g. https://…/api/v2/List.svc
 :contract       "IList"                ; SOAPAction = namespace + contract + "/" + operation
 :data-namespace "http://schemas.datacontract.org/2004/07/…"
 :username       #env IBABS_USERNAME    ; HTTP Basic by default
 :password       #env IBABS_PASSWORD
 :retry-faults   ["Unknown fault occured"]  ; iBABS's throttling fault
 :operations     {:GetListEntries {:timeout-ms 60000 :arrays #{:list-entries}}}}
```

## TLS trust and certificate names

SMTP and IMAP refuse a certificate the JVM does not trust or that does not name
`:host` (Angus Mail checks the name by default; javax.mail 1.6 did not).

- `:ssl-trust` — `"*"` or hosts (string, space-separated, or vector) whose
  certificate is accepted whatever it is.
- `:check-server-identity?` — `false` accepts a certificate for another name.
  Default `true`.

Both let a man in the middle read credentials and mail. Prefer the server's CA in
the JVM trust store and connecting by the certificate's name. Trusting a host
does not skip the name check; a self-signed certificate for another name needs
both. Invalid values throw `:validation-error` from the constructor; from an
environment variable, read the flag with `#boolean #env`.

## Retry and circuit breaker

The adapters use the RPC client's resilience layer (`wagoe.platform.resilience`).
Both are opt-in. SMTP, IMAP and Twilio retry only a call that never connected —
one that did may have sent the message. They set no connect timeout, so retries
are off by default:

```clojure
{:retries         2       ; extra attempts (default 0)
 :retry-delay-ms  100     ; ×1, ×2, … between attempts
 :cache           #ig/ref :wagoe/cache                    ; enables the breaker
 :circuit-breaker {:failure-threshold 5 :open-ms 30000}}
```

FTPS and SOAP keep `:max-attempts` / `:backoff-ms` and take the same `:cache` and
`:circuit-breaker`. The breaker counts calls that got no answer (FTPS: transient
failures; SOAP: also faults named in `:retry-faults`), once per call after its retries. While open, calls return
`{:success? false :error {:type :circuit-open ...}}` without connecting.

## Dependencies

- `clj-http/clj-http 3.13.1` — Twilio REST calls
- `cheshire/cheshire 6.2.0` — JSON parsing
- `jakarta.mail/jakarta.mail-api 2.1.5` + `org.eclipse.angus/angus-mail 2.0.5` (Jakarta Mail 2) — SMTP and IMAP
- `commons-net/commons-net 3.13.0` — FTPS
- `metosin/malli 0.20.1` — schema validation

## Key Pitfalls

### IMAP UIDFolder Cast
`Message.getMessageNumber()` is NOT the IMAP UID. Always cast the folder:
```clojure
(let [uid-fld (cast org.eclipse.angus.mail.imap.IMAPFolder folder)]
  (.getUID uid-fld message))
```

### IMAP Connection-Per-Call
`ImapMailboxAdapter` opens and closes a Store + Folder per operation. `close!` is a no-op (returns `true`). Use `try/finally` inside `with-imap-connection`.

### IMAP UIDs are per folder
A message carries its `:folder`. Pass the message as opts — `(ports/mark-read! mb (:uid msg) msg)` — or a
UID from another folder hits a different message. `delete-message!` expunges only that UID (UID EXPUNGE, needs
UIDPLUS); without UIDPLUS it only flags `\Deleted`, returns `:expunged? false`, and fetches skip it. A fetch without `:limit` takes
100 messages, since each is held in memory with its attachments.

### IMAP inline parts are attachments too
`:attachments` holds every part with a filename or a non-text type, so a signature logo or a `cid:` image
comes back next to the real files. Each carries `:disposition`, `:content-id` and `:embedded?`, true when the
HTML body shows it by `cid:`. Filter on `:embedded?`, not on `"inline"`, since some clients (Apple Mail) send
real attachments inline: `(remove :embedded? (:attachments msg))`.

### IMAP adapter is reflection-free, and tested on GreenMail
Jakarta Mail hands back package-private classes, and the mocked tests never caught a call that failed on a real
server. The adapter ns sets `*warn-on-reflection*` and a test fails on any warning. `imap_greenmail_test.clj`
needs the `:test/mail` alias: `clojure -M:test:test/mail :external`.

### FTPS: trust and retries
Commons Net's `FTPSClient` trusts any unexpired certificate by default. The adapter replaces that with the JVM's
cacerts (or `:trust-store`) and checks the hostname, so a self-signed server needs `:trust-store`, not a
trust-all switch. Retries cover I/O failures and 4xx replies; a 5xx (bad login, missing file) or an untrusted
certificate fails on the first attempt. Commons Net does not resume the TLS session on the data channel, so a
server that requires it (vsftpd `require_ssl_reuse=YES`) refuses every transfer.

```clojure
(ports/list-files! source "/outbox" {:match #"contacts-.*\.csv" :skip processed-names})
(ports/fetch-file! source "/outbox/contacts-1.csv") ;=> {:success? true :content <bytes> ...}
```

Remembering which files were processed is the caller's job: pass their names as `:skip`.
`ftps_server_test.clj` runs against MINA FtpServer and needs `:test/ftp`.

### SOAP: operations by hand, element order, arrays
There is no WSDL fetch: WCF's defaults (namespace `http://tempuri.org/`, request element named after the
operation, SOAPAction namespace + `:contract` + `/` + operation) cover most operations, and `:operations` sets
`:action`, `:element`, `:namespace`, `:timeout-ms`, `:idempotent?`, `:arrays` and `:retry-faults` for the rest.

- Request element names are sent as written (`:Sitename`, `:filterRequest`); the contract is case-sensitive.
  Nested members go in `:data-namespace`. A map is written ordinally by name, DataContract's default order;
  pass `[[name value] ...]` pairs where the contract declares another. A vector of scalars is a WCF array.
- Responses come back with kebab-case keys and string leaves; `xsi:nil` is nil, `KeyValueOf…` a map with string
  keys, `ArrayOf…` / Serialization-Arrays items / repeated names a vector. An array that can hold one item or
  none reads as a map or `""` unless named in `:arrays`.
- A fault is `{:type :fault :code :message :detail}`; no usable answer is `:external-error`. Faults named in
  `:retry-faults`, refused connections and 429s are retried; timeouts, I/O errors and 5xx only while the
  operation is `:idempotent?` (default true).
- Credentials go as HTTP Basic, or as leading parameters with `:credential-elements ["username" "password"]`
  (set `:basic-auth? false` if the service wants only those). Nothing logs a request body.

```clojure
(ports/call! ibabs :GetListEntries [[:Sitename "town"]
                                    [:filterRequest {:Sitename "town" :ListId id :SinceDate "2026-10-01T00:00:00"}]])
;=> {:success? true :result {:list-entries [{:entry-id "42" :values {"Titel" "Raad"}}] ...} :body {...}}
```

`soap_server_test.clj` runs against an in-process `com.sun.net.httpserver` stub; it needs no alias.

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
clojure -M:test:test/mail:test/ftp :external

# Unit tests only (pure functions, no I/O)
clojure -M:test :external --focus-meta :unit

# Integration tests (validates parsing; no real services needed)
clojure -M:test :external --focus-meta :integration
```

## Links

- [Payments AGENTS — PSP integration](../payments/AGENTS.md)
- [Root AGENTS Guide](../../AGENTS.md)
