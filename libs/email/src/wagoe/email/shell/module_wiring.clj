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

(def ^:private dev-profiles
  "Profiles where the :logging sender prints whole emails. Elsewhere a body's
   invite and reset links reached the logs, so it logs only the recipients
   (BOU-1092). No profile counts as not dev, unlike wagoe.config/dev-like?: quiet
   is the safe side."
  #{:dev :test :local})

(defmethod ig/init-key :wagoe/email
  [_ {:keys [provider] :wagoe/keys [profile] :or {provider :logging} :as config}]
  (log/info "Initializing email sender" {:provider provider})
  (let [dev?    (contains? dev-profiles profile)
        logging #(do (when-not dev?
                       (log/warn "Email is logged, not delivered: :wagoe/email has :provider :logging"
                                 {:profile profile}))
                     (logging-adapter/create-logging-sender (assoc config :verbose? dev?)))]
    (case provider
      :smtp    (smtp/create-smtp-sender config)
      :logging (logging)
      (do (log/warn "Unknown email provider, falling back to the logging sender" {:provider provider})
          (logging)))))

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
  [settings ctx]
  {:components {:wagoe/email       (assoc (or settings {:provider :logging})
                                          ;; Decides what the :logging sender prints (BOU-1092).
                                          :wagoe/profile (get-in ctx [:config :wagoe/profile]))
                ;; The queued mode the docs promise (BOU-346): an in-memory
                ;; outbox over whatever sender is configured. Per-process and
                ;; lost on restart, as its adapter says on the tin.
                :wagoe/email-queue {:sender (ig/ref :wagoe/email)}}})
