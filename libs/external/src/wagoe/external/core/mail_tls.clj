(ns wagoe.external.core.mail-tls
  "TLS trust settings shared by the SMTP and IMAP adapters, as Angus Mail
   session properties.")

(defn tls-properties
  "Session properties for :ssl-trust and :check-server-identity? under the
   protocol prefix (\"mail.smtp\", \"mail.imaps\" …). The identity check is
   written out even when on, so the default does not hang on Angus's own."
  [prefix {:keys [ssl-trust check-server-identity?]}]
  (cond-> {(str prefix ".ssl.checkserveridentity") (str (not (false? check-server-identity?)))}
    ssl-trust (assoc (str prefix ".ssl.trust")
                     (if (string? ssl-trust) ssl-trust (apply str (interpose " " ssl-trust))))))
