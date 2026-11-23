(ns wagoe.email.shell.module-wiring-test
  "The :wagoe/email + :wagoe/email-queue Integrant keys build a sender and
   a queue from config."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test]
            [wagoe.email.shell.module-wiring]
            [wagoe.email.shell.adapters.logging :as logging-sender]
            [wagoe.email.ports :as ports]
            [integrant.core :as ig]))

(deftest ^:unit email-key-selects-provider
  (testing ":logging (default) builds an EmailSenderProtocol sender"
    (is (satisfies? ports/EmailSenderProtocol
                    (ig/init-key :wagoe/email {:provider :logging}))))
  (testing "an unknown provider falls back to the logging sender in dev"
    (is (satisfies? ports/EmailSenderProtocol
                    (ig/init-key :wagoe/email {:provider :carrier-pigeon :wagoe/profile :dev}))))
  (testing ":smtp builds an SMTP sender from host/port"
    (is (satisfies? ports/EmailSenderProtocol
                    (ig/init-key :wagoe/email {:provider :smtp :host "localhost" :port 1025})))))

(deftest ^:unit email-queue-key-builds-queue-over-sender
  (let [sender (ig/init-key :wagoe/email {:provider :logging})
        queue  (ig/init-key :wagoe/email-queue {:sender sender :max-retries 1})]
    (is (satisfies? ports/EmailQueueProtocol queue))
    (is (= 0 (ports/queue-size queue)))
    (ports/queue-email! queue {:to ["x@y.z"] :from "a@b.c" :subject "s" :body "b"})
    (is (= 1 (ports/queue-size queue)))))

(deftest ^:unit ig-config-ships-the-queue-the-docs-promise
  ;; BOU-346: the queued mode was documented, its adapter shipped, and nothing
  ;; ever wired it.
  (let [{:keys [components]} (wagoe.email.shell.module-wiring/ig-config nil {})]
    (is (contains? components :wagoe/email-queue))
    (is (some? (:sender (:wagoe/email-queue components)))
        "the queue wraps whatever sender is configured")))

(deftest ^:unit ^:security outside-dev-the-logging-sender-logs-no-body
  ;; BOU-1092: on wagoe-chat prod a whole invite, link included, went to the logs.
  (let [email {:id "e1" :to ["bob@example.com"] :from "a@b.c" :subject "Your invite"
               :body "Accept: https://app/invites/SECRET-TOKEN"}]
    (doseq [profile [:prod :acc nil]]
      (logging-sender/clear-sent-emails!)
      (log-test/with-log
        (let [err (with-out-str
                    (binding [*err* *out*]
                      (ports/send-email! (ig/init-key :wagoe/email {:provider :logging :wagoe/profile profile}) email)))]
          (is (not (re-find #"SECRET-TOKEN|Your invite" err)) (str profile " printed it"))
          (is (not-any? #(re-find #"SECRET-TOKEN|Your invite" (str (:message %))) (log-test/the-log))
              (str profile " logged the mail"))
          (is (log-test/logged? 'wagoe.email.shell.adapters.logging :warn #"not delivered.*bob@example\.com")
              "only who it was for")
          (is (empty? (logging-sender/list-sent-emails)) (str profile " kept it")))))
    (testing "in dev it shows the whole mail, as before"
      (logging-sender/clear-sent-emails!)
      (with-out-str (binding [*err* *out*]
                      (ports/send-email! (ig/init-key :wagoe/email {:provider :logging :wagoe/profile :dev}) email)))
      (is (= email (:email (logging-sender/latest-sent-email)))))))

(deftest ^:unit ^:security an-unknown-provider-falls-back-without-printing-mails
  ;; A typo keeps booting, as it did; outside dev it now logs only recipients (BOU-1092).
  (let [sender (ig/init-key :wagoe/email {:provider :carrier-pigeon :wagoe/profile :prod})]
    (is (satisfies? ports/EmailSenderProtocol sender))
    (is (false? (:verbose? sender)))))

(deftest ^:unit the-profile-reaches-the-sender
  (is (= :prod (get-in (wagoe.email.shell.module-wiring/ig-config {:provider :smtp} {:config {:wagoe/profile :prod}})
                       [:components :wagoe/email :wagoe/profile]))))

(deftest ^:unit the-dev-list-keeps-only-the-last-emails
  (logging-sender/clear-sent-emails!)
  (let [sender (logging-sender/create-logging-sender)]
    (with-out-str (binding [*err* *out*]
                    (dotimes [i 150] (ports/send-email! sender {:id (str i) :to ["x@y.z"] :subject "s"}))))
    (is (= 100 (count (logging-sender/list-sent-emails))))
    (is (= "149" (get-in (logging-sender/latest-sent-email) [:email :id])))))
