(ns wagoe.external.shell.adapters.mail-tls-greenmail-test
  "SMTP and IMAP over TLS to a server whose certificate names another host.
   GreenMail's bundled certificate is self-signed for \"GreenMail selfsigned
   Test Certificate\", so it is neither trusted by the JVM nor issued for
   localhost."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.external.ports :as ports]
            [wagoe.external.shell.adapters.imap :as imap]
            [wagoe.external.shell.adapters.smtp :as smtp])
  (:import [com.icegreen.greenmail.util GreenMail ServerSetup ServerSetupTest]))

(def ^:private user "inbox@example.com")
(def ^:private password "secret")

(def ^:private ^:dynamic ^GreenMail *gm* nil)

(defn- with-greenmail [f]
  (let [gm (GreenMail. ^"[Lcom.icegreen.greenmail.util.ServerSetup;"
            (into-array ServerSetup [(.dynamicPort ServerSetupTest/SMTPS)
                                     (.dynamicPort ServerSetupTest/IMAPS)]))]
    (.start gm)
    (try
      (.setUser gm user user password)
      (binding [*gm* gm] (f))
      (finally (.stop gm)))))

(use-fixtures :once with-greenmail)

(defn- smtp-connects? [tls-config]
  (-> (smtp/create-smtp-provider (merge {:host "localhost" :port (.getPort (.getSmtps *gm*))
                                         :ssl? true :from "app@example.com" :retries 0}
                                        tls-config))
      ports/test-connection!
      :success?))

(defn- imap-connects? [tls-config]
  (-> (imap/create-imap-mailbox (merge {:host "localhost" :port (.getPort (.getImaps *gm*))
                                        :username user :password password :ssl? true :retries 0}
                                       tls-config))
      ports/fetch-messages!
      :success?))

(deftest ^:integration certificate-for-another-name-test
  (doseq [[proto connects?] [["smtp" smtp-connects?] ["imap" imap-connects?]]]
    (testing proto
      (testing "is refused by default"
        (is (not (connects? {}))))
      (testing "trusting the host still checks the name"
        (is (not (connects? {:ssl-trust "localhost"}))))
      (testing "connects once the name check is off for a trusted host"
        (is (connects? {:ssl-trust "localhost" :check-server-identity? false}))
        (is (connects? {:ssl-trust ["mail.example.com" "localhost"] :check-server-identity? false}))
        (is (connects? {:ssl-trust "*" :check-server-identity? false})))
      (testing "a host not in :ssl-trust is still refused"
        (is (not (connects? {:ssl-trust "mail.example.com" :check-server-identity? false})))))))

(defn- error-type [create config]
  (try (create config) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest ^:unit invalid-tls-config-test
  (doseq [bad                [{:ssl-trust true} {:ssl-trust ""} {:ssl-trust []} {:check-server-identity? "no"}]
          [create base]      [[smtp/create-smtp-provider {:host "h" :port 25 :from "a@b.c"}]
                              [imap/create-imap-mailbox {:host "h" :port 993 :username "u" :password "p"}]]]
    (is (= :validation-error (error-type create (merge base bad))) (pr-str bad))))
