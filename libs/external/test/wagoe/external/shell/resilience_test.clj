(ns wagoe.external.shell.resilience-test
  (:require [wagoe.cache.shell.adapters.in-memory :as cache-mem]
            [wagoe.external.ports :as ports]
            [wagoe.external.shell.adapters.twilio :as twilio]
            [wagoe.external.shell.resilience :as resilience]
            [clojure.test :refer [deftest is testing]])
  (:import (java.net ConnectException ServerSocket SocketTimeoutException)
           (javax.mail MessagingException)))

(defn- closed-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- counting-call [settings ex]
  (let [calls (atom 0)]
    [(try (resilience/call! settings #(do (swap! calls inc) (throw ex)))
          (catch Exception e e))
     @calls]))

(deftest ^:unit only-a-call-that-never-connected-is-retried
  (let [settings (resilience/settings {:retries 2 :retry-delay-ms 1} "test:svc" nil)]
    (testing "never connected: retried, then rethrown"
      (let [ex             (ConnectException. "Connection refused")
            [thrown calls] (counting-call settings ex)]
        (is (= 3 calls))
        (is (identical? ex thrown))))

    (testing "javax.mail wraps the socket exception; the cause still counts"
      (is (= 3 (second (counting-call settings (MessagingException. "connect failed"
                                                                    (ConnectException.)))))))

    (testing "a timeout may have sent the message, so it is not retried"
      (is (= 1 (second (counting-call settings (SocketTimeoutException. "Read timed out"))))))

    (testing "no retries by default"
      (is (= 1 (second (counting-call (resilience/settings {} "test:svc" nil)
                                      (ConnectException.))))))))

(deftest ^:unit an-invalid-breaker-config-is-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failure-threshold"
                        (resilience/settings {:circuit-breaker {:failure-threshold 0}} "test:svc" nil)))
  (testing "an #env string is refused at construction, not at the first retry"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":retries"
                          (resilience/settings {:retries "2"} "test:svc" nil)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":retry-delay-ms"
                          (resilience/settings {:retry-delay-ms "100"} "test:svc" nil)))))

(deftest ^:integration an-unreachable-twilio-trips-the-breaker
  (let [adapter (twilio/create-twilio-adapter
                 {:account-sid "ACtest" :auth-token "token" :from-number "+15005550006"
                  :base-url        (str "http://127.0.0.1:" (closed-port))
                  :retries         0
                  :cache           (cache-mem/create-in-memory-cache {})
                  :circuit-breaker {:failure-threshold 1 :open-ms 60000}})
        send!   #(ports/send-sms! adapter {:to "+31612345678" :body "hi"})]
    (is (= :network-error (get-in (send!) [:error :type])))
    (testing "the next call is declined without being made"
      (let [r (send!)]
        (is (false? (:success? r)))
        (is (= :circuit-open (get-in r [:error :type])))))))
