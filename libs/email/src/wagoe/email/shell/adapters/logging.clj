(ns wagoe.email.shell.adapters.logging
  (:require [wagoe.email.core.email :as email-core]
            [wagoe.email.ports :as ports]
            [clojure.tools.logging :as log]))

(defonce sent-emails
  (atom []))

(def ^:private kept
  "How many sent emails `list-sent-emails` keeps: a dev or test session reads
   the last few, and an unbounded list grew for as long as the app ran (BOU-1092)."
  100)

(defrecord LoggingEmailSender [logger-name verbose?])

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
    (if (:verbose? this)
      (let [payload {:event :dev-email-send
                     :logger-name (:logger-name this)
                     :email email
                     :summary (summarize email)}]
        (swap! sent-emails #(vec (take-last kept (conj % payload))))
        (binding [*out* *err*]
          (prn payload)
          (flush))
        (log/info "Dev email send intercepted" payload))
      ;; Outside dev the body holds invite and reset links that work as
      ;; passwords: log who a mail was for, never what it said (BOU-1092).
      (log/warn "Email not delivered: the :logging sender only logs" {:to (:to email)}))
    {:success? true
     :message-id (str "log-" (:id email))})

  (send-email-async! [this email]
    (future (ports/send-email! this email))))

(defn create-logging-sender
  "A sender that delivers nothing. `:verbose?` (default true) prints each email
   whole and keeps the last ones for `list-sent-emails`; `:wagoe/email` sets it
   only under dev, test and local, as the body carries links that sign people in."
  ([] (create-logging-sender {}))
  ([{:keys [logger-name verbose?]
     :or {logger-name "wagoe.email.dev-logger" verbose? true}}]
   (->LoggingEmailSender logger-name verbose?)))

(defn list-sent-emails
  []
  @sent-emails)

(defn latest-sent-email
  []
  (peek @sent-emails))

(defn clear-sent-emails!
  []
  (reset! sent-emails []))
