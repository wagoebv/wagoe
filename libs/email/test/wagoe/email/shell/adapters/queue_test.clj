(ns wagoe.email.shell.adapters.queue-test
  "In-memory email queue: enqueue / peek / size, drain on process, and bounded
   retry on send failure."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.email.shell.adapters.queue :as sut]
            [wagoe.email.ports :as ports]))

(defn- sender
  "Stub sender whose success is controlled by `outcome-fn` (email -> boolean)."
  [outcome-fn calls]
  (reify ports/EmailSenderProtocol
    (send-email! [_ email]
      (swap! calls conj email)
      {:success? (boolean (outcome-fn email)) :message-id "m"})
    (send-email-async! [this email] (future (ports/send-email! this email)))))

(defn- email [to] {:to [to] :from "a@b.c" :subject "s" :body "b"})

(deftest ^:unit queue-enqueue-peek-size
  (let [q (sut/create-in-memory-queue {:sender (sender (constantly true) (atom []))})]
    (is (= 0 (ports/queue-size q)))
    (let [ack (ports/queue-email! q (email "x@y.z"))]
      (is (:queued? ack))
      (is (some? (:queue-id ack)))
      (is (= 1 (ports/queue-size q))))
    (is (= ["x@y.z"] (:to (ports/peek-queue q))) "peek does not remove")
    (is (= 1 (ports/queue-size q)))))

(deftest ^:unit queue-process-drains-and-sends
  (let [calls (atom [])
        q     (sut/create-in-memory-queue {:sender (sender (constantly true) calls)})]
    (ports/queue-email! q (email "a@a.a"))
    (let [r (ports/process-queue! q)]
      (is (:processed? r))
      (is (:success? (:send-result r)))
      (is (= 0 (ports/queue-size q)) "sent email left the queue")
      (is (= 1 (count @calls))))
    (testing "empty queue reports nothing processed"
      (is (= {:processed? false} (ports/process-queue! q))))))

(deftest ^:unit queue-retries-then-exhausts
  (let [calls (atom [])
        q     (sut/create-in-memory-queue {:sender (sender (constantly false) calls)
                                           :max-retries 2})]
    (ports/queue-email! q (email "f@f.f"))
    (testing "1st failure re-queues"
      (let [r (ports/process-queue! q)]
        (is (:retrying? r))
        (is (= 1 (ports/queue-size q)))))
    (testing "2nd failure re-queues (retry 2 of 2)"
      (let [r (ports/process-queue! q)]
        (is (:retrying? r))
        (is (= 1 (ports/queue-size q)))))
    (testing "3rd failure exhausts retries and drops"
      (let [r (ports/process-queue! q)]
        (is (= :max-retries-exhausted (get-in r [:error :type])))
        (is (= 0 (ports/queue-size q)))))
    (is (= 3 (count @calls)) "attempted 1 + 2 retries = 3 sends")))

(deftest ^:unit queue-requires-a-sender
  (is (thrown? clojure.lang.ExceptionInfo (sut/create-in-memory-queue {}))))
