(ns wagoe.email.shell.module-wiring
  "Integrant lifecycle for the email module.

   Config keys:

   :wagoe/email
     {:provider :smtp :host \"...\" :port 587 :username \"...\" :password \"...\"}
     {:provider :logging}                          ; dev — logs instead of sending

     Returns an EmailSenderProtocol implementation.

   :wagoe/email-queue
     {:sender (ig/ref :wagoe/email) :max-retries 3}

     Returns an EmailQueueProtocol implementation (in-memory, single-process)."
  (:require [integrant.core :as ig]
            [wagoe.email.shell.adapters.smtp :as smtp]
            [wagoe.email.shell.adapters.logging :as logging-adapter]
            [wagoe.email.shell.adapters.queue :as queue]
            [clojure.tools.logging :as log]))

(defmethod ig/init-key :wagoe/email
  [_ {:keys [provider] :or {provider :logging} :as config}]
  (log/info "Initializing email sender" {:provider provider})
  (case provider
    :smtp    (smtp/create-smtp-sender config)
    :logging (logging-adapter/create-logging-sender config)
    (do
      (log/warn "Unknown email provider, falling back to the logging sender"
                {:provider provider})
      (logging-adapter/create-logging-sender config))))

(defmethod ig/halt-key! :wagoe/email
  [_ _sender]
  (log/info "Email sender halted"))

(defmethod ig/init-key :wagoe/email-queue
  [_ config]
  (log/info "Initializing in-memory email queue")
  (queue/create-in-memory-queue config))

(defmethod ig/halt-key! :wagoe/email-queue
  [_ _queue]
  (log/info "Email queue halted"))

;; =============================================================================
;; Module graph
;; =============================================================================

(defn ig-config
  "This module's Integrant entries, for `wagoe.platform.shell.system.config`.

   Assembled in every application whether or not its config names
   `:wagoe/email`: the user module's routes send mail, and the default sender
   logs rather than delivers, so a project with no SMTP settings still boots and
   still shows you what it would have sent."
  [settings _ctx]
  {:components {:wagoe/email (or settings {:provider :logging})}})
