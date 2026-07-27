(ns wagoe.external.core.twilio-test
  (:require [wagoe.external.core.twilio :as twilio]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit build-sms-params-test
  (testing "builds basic SMS params"
    (let [params (twilio/build-sms-params
                  {:to "+31612345678" :body "Hello!"}
                  "+15005550006")]
      (is (= "+31612345678" (get params "To")))
      (is (= "+15005550006" (get params "From")))
      (is (= "Hello!"       (get params "Body")))))

  (testing "input :from overrides default from-number"
    (let [params (twilio/build-sms-params
                  {:to "+31612345678" :body "Hi" :from "+31600000001"}
                  "+15005550006")]
      (is (= "+31600000001" (get params "From")))))

  (testing "includes MediaUrl when provided"
    (let [params (twilio/build-sms-params
                  {:to "+1234" :body "pic" :media-url "https://example.com/img.jpg"}
                  "+15005550006")]
      (is (= "https://example.com/img.jpg" (get params "MediaUrl")))))

  (testing "no MediaUrl key when not provided"
    (let [params (twilio/build-sms-params {:to "+1234" :body "text"} "+15005550006")]
      (is (nil? (get params "MediaUrl"))))))

(deftest ^:unit build-whatsapp-params-test
  (testing "adds whatsapp: prefix to To and From"
    (let [params (twilio/build-whatsapp-params
                  {:to "+31612345678" :body "Hello via WhatsApp"}
                  "+14155238886")]
      (is (= "whatsapp:+31612345678" (get params "To")))
      (is (= "whatsapp:+14155238886" (get params "From")))
      (is (= "Hello via WhatsApp"    (get params "Body")))))

  (testing "does not double-prefix if whatsapp: already present"
    (let [params (twilio/build-whatsapp-params
                  {:to "whatsapp:+31612345678" :body "Hi"}
                  "whatsapp:+14155238886")]
      (is (= "whatsapp:+31612345678" (get params "To")))
      (is (= "whatsapp:+14155238886" (get params "From"))))))

(deftest ^:unit parse-message-response-test
  (testing "parses Twilio message response"
    (let [body   {"sid"    "SMxxx123"
                  "status" "queued"
                  "to"     "+31612345678"
                  "from"   "+15005550006"}
          result (twilio/parse-message-response body)]
      (is (= "SMxxx123"       (:message-sid result)))
      (is (= "queued"         (:status result)))
      (is (= "+31612345678"   (:to result)))
      (is (= "+15005550006"   (:from result))))))

(deftest ^:unit parse-twilio-error-test
  (testing "parses Twilio error body"
    (let [body   {"message"   "The From phone number is not valid."
                  "code"      21211
                  "more_info" "https://www.twilio.com/docs/errors/21211"}
          result (twilio/parse-twilio-error body 400)]
      (is (= "The From phone number is not valid." (:message result)))
      (is (= 21211 (:code result)))
      (is (= 400   (:status-code result)))))

  (testing "defaults when body empty"
    (let [result (twilio/parse-twilio-error {} 500)]
      (is (= "Unknown Twilio error" (:message result))))))
