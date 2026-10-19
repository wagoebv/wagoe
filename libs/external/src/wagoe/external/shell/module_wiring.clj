(ns wagoe.external.shell.module-wiring
  "Integrant lifecycle wiring for the external service adapters.

   Registers init-key and halt-key! multimethods for:
     :wagoe.external/smtp   — SMTP transport provider
     :wagoe.external/imap   — IMAP mailbox reader
     :wagoe.external/twilio — Twilio SMS / WhatsApp
     :wagoe.external/ftps   — FTPS file source
     :wagoe.external/soap   — SOAP 1.1 client

   All five keys are opt-in: add them to :active in config.edn to enable.
   They are shipped in :inactive by default."
  (:require [wagoe.external.ports :as ports]
            [wagoe.external.shell.adapters.smtp :as smtp-adapter]
            [wagoe.external.shell.adapters.imap :as imap-adapter]
            [wagoe.external.shell.adapters.twilio :as twilio-adapter]
            [wagoe.external.shell.adapters.ftps :as ftps-adapter]
            [wagoe.external.shell.adapters.soap :as soap-adapter]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; SMTP
;; =============================================================================

(defmethod ig/init-key :wagoe.external/smtp
  [_ config]
  (log/info "Initializing external SMTP provider" {:host (:host config)})
  (let [adapter (smtp-adapter/create-smtp-provider config)]
    (log/info "External SMTP provider initialized" {:host (:host config)})
    adapter))

(defmethod ig/halt-key! :wagoe.external/smtp
  [_ _adapter]
  (log/info "External SMTP provider halted (no cleanup required)"))

;; =============================================================================
;; IMAP
;; =============================================================================

(defmethod ig/init-key :wagoe.external/imap
  [_ config]
  (log/info "Initializing external IMAP mailbox" {:host (:host config)})
  (let [adapter (imap-adapter/create-imap-mailbox config)]
    (log/info "External IMAP mailbox initialized" {:host (:host config)})
    adapter))

(defmethod ig/halt-key! :wagoe.external/imap
  [_ adapter]
  (log/info "Halting external IMAP mailbox")
  (try
    (ports/close! adapter)
    (catch Exception e
      (log/warn e "Error while closing IMAP mailbox"))))

;; =============================================================================
;; Twilio
;; =============================================================================

(defmethod ig/init-key :wagoe.external/twilio
  [_ config]
  (log/info "Initializing Twilio adapter" {:account-sid (:account-sid config)})
  (let [adapter (twilio-adapter/create-twilio-adapter config)]
    (log/info "Twilio adapter initialized")
    adapter))

(defmethod ig/halt-key! :wagoe.external/twilio
  [_ _adapter]
  (log/info "Twilio adapter halted (no cleanup required)"))

;; =============================================================================
;; FTPS
;; =============================================================================

(defmethod ig/init-key :wagoe.external/ftps
  [_ config]
  (log/info "Initializing FTPS file source" {:host (:host config)})
  (ftps-adapter/create-ftps-source config))

(defmethod ig/halt-key! :wagoe.external/ftps
  [_ _adapter]
  (log/info "FTPS file source halted (no cleanup required)"))

;; =============================================================================
;; SOAP
;; =============================================================================

(defmethod ig/init-key :wagoe.external/soap
  [_ config]
  (soap-adapter/create-soap-client config))

(defmethod ig/halt-key! :wagoe.external/soap
  [_ _adapter]
  (log/info "SOAP client halted (no cleanup required)"))
