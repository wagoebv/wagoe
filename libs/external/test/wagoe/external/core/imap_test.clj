(ns wagoe.external.core.imap-test
  (:require [wagoe.external.core.imap :as imap]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit parse-message-headers-test
  (testing "converts string keys to kebab-case keywords"
    (let [result (imap/parse-message-headers {"Content-Type" "text/plain"
                                              "Message-ID"   "<123@example.com>"
                                              "X-Mailer"     "MyMailer"})]
      (is (= "text/plain"         (result :content-type)))
      (is (= "<123@example.com>"  (result :message-id)))
      (is (= "MyMailer"           (result :x-mailer)))))

  (testing "handles nil input"
    (is (= {} (imap/parse-message-headers nil))))

  (testing "handles empty map"
    (is (= {} (imap/parse-message-headers {})))))

(deftest ^:unit extract-body-text-test
  (testing "extracts plain text part"
    (let [{:keys [text html]}
          (imap/extract-body-text [{:content-type "text/plain; charset=UTF-8"
                                    :content      "Hello, world!"}])]
      (is (= "Hello, world!" text))
      (is (nil? html))))

  (testing "extracts HTML part"
    (let [{:keys [text html]}
          (imap/extract-body-text [{:content-type "text/html; charset=UTF-8"
                                    :content      "<p>Hello</p>"}])]
      (is (nil? text))
      (is (= "<p>Hello</p>" html))))

  (testing "extracts both text and html"
    (let [{:keys [text html]}
          (imap/extract-body-text [{:content-type "text/plain" :content "plain"}
                                   {:content-type "text/html"  :content "<b>bold</b>"}])]
      (is (= "plain" text))
      (is (= "<b>bold</b>" html))))

  (testing "returns nil for both on empty parts"
    (let [result (imap/extract-body-text [])]
      (is (nil? (:text result)))
      (is (nil? (:html result)))))

  (testing "ignores non-text parts"
    (let [{:keys [text html]}
          (imap/extract-body-text [{:content-type "application/pdf" :content "..."}])]
      (is (nil? text))
      (is (nil? html)))))

(deftest ^:unit build-inbound-message-test
  (testing "constructs InboundMessage map"
    (let [now (java.util.Date.)
          msg (imap/build-inbound-message
               42
               "sender@example.com"
               ["recipient@example.com"]
               "Test Subject"
               [{:content-type "text/plain" :content "Body text"}]
               now
               {"Content-Type" "text/plain"})]
      (is (= 42 (:uid msg)))
      (is (= "sender@example.com" (:from msg)))
      (is (= ["recipient@example.com"] (:to msg)))
      (is (= "Test Subject" (:subject msg)))
      (is (= "Body text" (:body msg)))
      (is (= now (:received-at msg)))
      (is (= :content-type (first (keys (:headers msg))))))))

(deftest ^:unit filter-by-date-test
  (let [now   (java.util.Date.)
        old   (java.util.Date. (- (.getTime now) 86400000))
        later (java.util.Date. (+ (.getTime now) 1000))
        msgs  [{:uid 1 :received-at old}
               {:uid 2 :received-at later}]]

    (testing "nil since returns all messages"
      (is (= 2 (count (imap/filter-by-date msgs nil)))))

    (testing "filters messages before threshold"
      (is (= [{:uid 2 :received-at later}]
             (imap/filter-by-date msgs now))))))

(deftest ^:unit filter-unread-test
  (let [msgs [{:uid 1 :seen? false}
              {:uid 2 :seen? true}
              {:uid 3}]]

    (testing "returns only unseen messages"
      (let [result (imap/filter-unread msgs)]
        (is (= 2 (count result)))
        (is (every? #(not (:seen? %)) result))))))

(deftest ^:unit message-summary-test
  (testing "returns expected summary shape"
    (let [now  (java.util.Date.)
          msg  {:uid 5 :from "a@b.com" :subject "Hi" :received-at now :seen? false}
          sum  (imap/message-summary msg)]
      (is (= 5 (:uid sum)))
      (is (= "a@b.com" (:from sum)))
      (is (= "Hi" (:subject sum)))
      (is (false? (:seen? sum))))))
