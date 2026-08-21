(ns wagoe.email.shell.adapters.smtp
  "SMTP email adapter — delegates raw transport to libs/external SmtpProviderAdapter.

   This adapter sits at the boundary between the email library's domain model
   (Email with :id, :created-at, :attachments, :metadata) and the external
   library's transport layer (OutboundEmail). It translates between the two and
   delegates all javax.mail work to wagoe.external.shell.adapters.smtp.

   Responsibility split:
     libs/email  — Email domain model, validation, preparation, job queuing
     libs/external — Raw SMTP transport (javax.mail, TLS/SSL, HTML multipart)

   Usage:
     (def sender (create-smtp-sender
                   {:host \"smtp.gmail.com\"
                    :port 587
                    :username \"user@gmail.com\"
                    :password \"app-password\"
                    :tls? true}))
     (send-email! sender prepared-email)"
  (:require [wagoe.email.ports :as ports]
            [wagoe.external.ports :as external-ports]
            [wagoe.external.shell.adapters.smtp :as smtp-provider]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Domain Translation
;; =============================================================================

(defn- split-comma-addresses
  "Split a comma-separated address string into a trimmed vector."
  [s]
  (when s
    (mapv str/trim (str/split (str s) #",\s*"))))

(defn- email->outbound
  "Translate a libs/email Email map to a libs/external OutboundEmail map.

  The Email domain model carries extra fields (:id, :created-at, :metadata) that
  are not part of the transport layer. :attachments ARE carried through — the
  external SMTP MIME builder honours them (BOU-150). Headers (reply-to, cc, bcc)
  are stored in Email's :headers sub-map and translated to top-level keys on
  OutboundEmail."
  [email]
  (let [headers (:headers email)]
    (cond-> {:to      (:to email)
             :from    (:from email)
             :subject (:subject email)
             :body    (:body email)}
      (:reply-to headers)    (assoc :reply-to (:reply-to headers))
      (:cc headers)          (assoc :cc (split-comma-addresses (:cc headers)))
      (:bcc headers)         (assoc :bcc (split-comma-addresses (:bcc headers)))
      (:attachments email)   (assoc :attachments (:attachments email)))))

;; =============================================================================
;; Adapter Record
;; =============================================================================

(defrecord SmtpEmailSender [host port username password tls? ssl?])

(defn- ->provider
  "Create a SmtpProviderAdapter from an SmtpEmailSender.
   The :from field on the provider is left blank — the actual sender address
   is always present in the OutboundEmail map produced by email->outbound."
  [this]
  (smtp-provider/create-smtp-provider
   {:host     (:host this)
    :port     (:port this)
    :username (:username this)
    :password (:password this)
    :tls?     (boolean (:tls? this))
    :ssl?     (boolean (:ssl? this))
    :from     ""}))

(extend-protocol ports/EmailSenderProtocol
  SmtpEmailSender

  (send-email! [this email]
    (log/info "Sending email via SMTP"
              {:email-id (:id email)
               :to       (:to email)
               :subject  (:subject email)
               :host     (:host this)
               :port     (:port this)})
    (let [result (external-ports/send-email! (->provider this) (email->outbound email))]
      (if (:success? result)
        {:success? true :message-id (:message-id result)}
        {:success? false
         :error    {:message        (get-in result [:error :message])
                    :type           (get-in result [:error :type] :smtp-error)
                    :provider-error {}}})))

  (send-email-async! [this email]
    (future (ports/send-email! this email))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-smtp-sender
  "Create an SMTP email sender.

  Delegates raw SMTP transport to wagoe.external.shell.adapters.smtp.

  Config keys:
    :host     - SMTP server hostname (required)
    :port     - SMTP server port (required)
    :username - SMTP auth username (optional)
    :password - SMTP auth password (optional)
    :tls?     - Enable STARTTLS (default: true)
    :ssl?     - Enable SSL (default: false)

  Returns:
    SmtpEmailSender implementing EmailSenderProtocol"
  [{:keys [host port username password tls? ssl?]
    :or   {tls? true ssl? false}}]
  {:pre [(string? host) (some? port)]}
  (log/info "Creating SMTP email sender"
            {:host  host
             :port  port
             :tls?  tls?
             :ssl?  ssl?
             :auth? (boolean (and username password))})
  (->SmtpEmailSender host port username password tls? ssl?))
