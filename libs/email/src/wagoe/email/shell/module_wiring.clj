(ns wagoe.email.shell.module-wiring
  "Integrant lifecycle for the email module.

   Config keys:

   :boundary/email
     {:provider :smtp :host \"...\" :port 587 :username \"...\" :password \"...\"}
     {:provider :logging}                          ; dev — logs instead of sending

     Returns an EmailSenderProtocol implementation.

   :boundary/email-queue
     {:sender (ig/ref :boundary/email) :max-retries 3}

     Returns an EmailQueueProtocol implementation (in-memory, single-process)."
  (:require [integrant.core :as ig]
            [wagoe.email.shell.adapters.smtp :as smtp]
            [wagoe.email.shell.adapters.logging :as logging-adapter]
            [wagoe.email.shell.adapters.queue :as queue]
            [clojure.tools.logging :as log]))

(defmethod ig/init-key :boundary/email
  [_ {:keys [provider] :or {provider :logging} :as config}]
  (log/info "Initializing email sender" {:provider provider})
  (case provider
    :smtp    (smtp/create-smtp-sender config)
    :logging (logging-adapter/create-logging-sender config)
    (do
      (log/warn "Unknown email provider, falling back to the logging sender"
                {:provider provider})
      (logging-adapter/create-logging-sender config))))

(defmethod ig/halt-key! :boundary/email
  [_ _sender]
  (log/info "Email sender halted"))

(defmethod ig/init-key :boundary/email-queue
  [_ config]
  (log/info "Initializing in-memory email queue")
  (queue/create-in-memory-queue config))

(defmethod ig/halt-key! :boundary/email-queue
  [_ _queue]
  (log/info "Email queue halted"))
