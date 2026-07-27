(ns wagoe.email.shell.module-wiring-test
  "The :wagoe/email + :wagoe/email-queue Integrant keys build a sender and
   a queue from config."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.email.shell.module-wiring]
            [wagoe.email.ports :as ports]
            [integrant.core :as ig]))

(deftest ^:unit email-key-selects-provider
  (testing ":logging (default) builds an EmailSenderProtocol sender"
    (is (satisfies? ports/EmailSenderProtocol
                    (ig/init-key :wagoe/email {:provider :logging}))))
  (testing "an unknown provider falls back to the logging sender"
    (is (satisfies? ports/EmailSenderProtocol
                    (ig/init-key :wagoe/email {:provider :carrier-pigeon}))))
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
