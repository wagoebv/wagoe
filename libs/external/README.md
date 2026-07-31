# wagoe/external

[![Status](https://img.shields.io/badge/status-in%20development-yellow)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-external.svg)](https://clojars.org/com.wagoe/wagoe-external)

**Status:** Active (not production-ready)  
**Version:** 1.0.0-beta-1

Adapters for external services: Twilio SMS/WhatsApp, SMTP transport, and IMAP mailbox.

## Installation

```clojure
{:deps {com.wagoe/wagoe-external {:mvn/version "1.0.0-beta-1"}}}
```

## Features

- **SMTP**: Email transport adapter for sending via SMTP
- **IMAP**: Mailbox adapter for reading emails
- **Twilio**: SMS and WhatsApp messaging adapter

## Quick Start

```clojure
(ns myapp.notifications
  (:require [wagoe.external.shell.adapters.smtp :as smtp]))

;; Create an SMTP provider via Integrant config:
;; {:wagoe/smtp-provider {:host "smtp.example.com"
;;                           :port 587
;;                           :username "user"
;;                           :password "pass"}}

;; Then use the IExternalEmailProvider protocol to send emails
```

## License

See root [CONTRIBUTING](../../CONTRIBUTING.md)
