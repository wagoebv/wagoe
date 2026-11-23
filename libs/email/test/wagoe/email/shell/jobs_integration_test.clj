(ns wagoe.email.shell.jobs-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [wagoe.email.ports :as ports]
            [wagoe.email.shell.jobs-integration :as sut]
            [wagoe.jobs.ports :as jobs]))

(defn- recording-sender [sent]
  (reify ports/EmailSenderProtocol
    (send-email! [_ email] (swap! sent conj email) {:success? true :message-id "m1"})
    (send-email-async! [this email] (future (ports/send-email! this email)))))

(deftest ^:unit ^:security a-queued-email-job-carries-no-credentials
  ;; BOU-1100: the stored job held the SMTP username and password.
  (let [queued (atom nil)
        queue  #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify jobs/IJobQueue
          (enqueue-job! [_ _queue-name job] (reset! queued job) :job-1))
        smtp   {:host "smtp.example.com" :port 587 :username "mailer" :password "s3cret"}
        email  {:id "e1" :to ["bob@example.com"] :from "a@b.c" :subject "Hi" :body "Body"}]
    (sut/queue-email-job! queue smtp email)
    (is (= {:email email} (:args @queued)))
    (is (not-any? #{"s3cret" "mailer"} (tree-seq coll? seq (walk/stringify-keys @queued))))))

(deftest ^:unit the-worker-sends-with-its-own-sender
  (let [sent  (atom [])
        email {:id "e1" :to ["bob@example.com"] :subject "Hi" :body "Body"}]
    (is (:success? (sut/process-email-job (recording-sender sent) {:email email})))
    (is (= [email] @sent)))
  (testing "registering without a sender is refused, not a handler that cannot send"
    (is (= :configuration-error
           (try (sut/register-email-job-handler! ::registry) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
    (is (= :configuration-error
           (try (sut/register-email-job-handler! ::registry nil) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))
