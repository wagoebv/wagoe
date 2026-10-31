# wagoe/external

[![Status](https://img.shields.io/badge/status-incubating-yellow)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-external.svg)](https://clojars.org/com.wagoe/wagoe-external)

**Status:** Active (not production-ready)  
**Version:** 1.1.1

Adapters for external services: Twilio SMS/WhatsApp, SMTP transport, IMAP mailbox, FTPS file source, and SOAP 1.1 client.

## Installation

```clojure
{:deps {com.wagoe/wagoe-external {:mvn/version "1.1.1"}}}
```

## Features

- **SMTP**: Email transport adapter for sending via SMTP
- **IMAP**: Mailbox adapter for reading emails
- **Twilio**: SMS and WhatsApp messaging adapter
- **FTPS**: List and download files from an FTP-over-TLS server
- **SOAP**: Call SOAP 1.1 operations on a WCF service

## Quick Start

```clojure
(ns myapp.notifications
  (:require [wagoe.external.shell.adapters.smtp :as smtp]))

;; Create an SMTP provider via Integrant config (add to :active):
;; {:wagoe.external/smtp {:host "smtp.example.com"
;;                        :port 587
;;                        :username "user"
;;                        :password "pass"
;;                        :tls? true
;;                        :from "noreply@example.com"}}

;; Then send through the wagoe.external.ports/ISmtpProvider protocol (send-email!)
```

## License

See root [CONTRIBUTING](../../CONTRIBUTING.md)
