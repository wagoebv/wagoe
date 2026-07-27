(ns wagoe.email.shell.adapters.logging
  (:require [wagoe.email.core.email :as email-core]
            [wagoe.email.ports :as ports]
            [clojure.tools.logging :as log]))

(defonce sent-emails
  (atom []))

(defrecord LoggingEmailSender [logger-name])

(defn- summarize
  [email]
  (merge
   (email-core/email-summary email)
   {:to-addresses (:to email)
    :headers (:headers email)
    :metadata (:metadata email)}))

(extend-protocol ports/EmailSenderProtocol
  LoggingEmailSender

  (send-email! [this email]
    (let [payload {:event :dev-email-send
                   :logger-name (:logger-name this)
                   :email email
                   :summary (summarize email)}]
      (swap! sent-emails conj payload)
      (binding [*out* *err*]
        (prn payload)
        (flush))
      (log/info "Dev email send intercepted" payload))
    {:success? true
     :message-id (str "log-" (:id email))})

  (send-email-async! [this email]
    (future (ports/send-email! this email))))

(defn create-logging-sender
  ([] (create-logging-sender {}))
  ([{:keys [logger-name]
     :or {logger-name "wagoe.email.dev-logger"}}]
   (->LoggingEmailSender logger-name)))

(defn list-sent-emails
  []
  @sent-emails)

(defn latest-sent-email
  []
  (peek @sent-emails))

(defn clear-sent-emails!
  []
  (reset! sent-emails []))
