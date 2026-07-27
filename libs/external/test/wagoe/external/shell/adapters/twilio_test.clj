(ns wagoe.external.shell.adapters.twilio-test
  "Integration tests for the Twilio messaging adapter.
   Tests verify protocol satisfaction and graceful error handling against
   an invalid base-url — no real Twilio credentials required."
  (:require [wagoe.external.ports :as ports]
            [wagoe.external.shell.adapters.twilio :as twilio]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]))

(def ^:private test-config
  {:account-sid  "ACtest0000000000000000000000000000"
   :auth-token   "test_auth_token"
   :from-number  "+15005550006"
   :base-url     "http://localhost-nonexistent.invalid:1"})

(defmacro with-silent-logging [& body]
  `(with-redefs [log/info (fn [& args#]
                            nil)
                 log/error (fn [& args#]
                             nil)]
     ~@body))

(deftest ^:integration create-twilio-adapter-test
  (with-silent-logging
    (testing "returns a record satisfying ITwilioMessaging"
      (let [adapter (twilio/create-twilio-adapter test-config)]
        (is (satisfies? ports/ITwilioMessaging adapter))
        (is (= "ACtest0000000000000000000000000000" (:account-sid adapter)))
        (is (= "+15005550006" (:from-number adapter)))))))

(deftest ^:integration send-sms-unreachable-test
  (with-silent-logging
    (testing "send-sms! on invalid base-url returns error map"
      (let [adapter (twilio/create-twilio-adapter test-config)
            result  (ports/send-sms! adapter {:to "+31612345678" :body "Hello!"})]
        (is (false? (:success? result)))
        (is (some? (:error result)))
        (is (string? (get-in result [:error :message])))))))

(deftest ^:integration send-whatsapp-unreachable-test
  (with-silent-logging
    (testing "send-whatsapp! on invalid base-url returns error map"
      (let [adapter (twilio/create-twilio-adapter test-config)
            result  (ports/send-whatsapp! adapter {:to "+31612345678" :body "Hi via WA!"})]
        (is (false? (:success? result)))
        (is (some? (:error result)))))))

(deftest ^:integration get-message-status-unreachable-test
  (with-silent-logging
    (testing "get-message-status! on invalid base-url returns error map"
      (let [adapter (twilio/create-twilio-adapter test-config)
            result  (ports/get-message-status! adapter "SMxxx123")]
        (is (false? (:success? result)))
        (is (some? (:error result)))))))
