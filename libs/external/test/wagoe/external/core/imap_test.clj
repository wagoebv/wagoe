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

(deftest ^:unit message-summary-test
  (testing "returns expected summary shape"
    (let [now  (java.util.Date.)
          msg  {:uid 5 :from "a@b.com" :subject "Hi" :received-at now :seen? false}
          sum  (imap/message-summary msg)]
      (is (= 5 (:uid sum)))
      (is (= "a@b.com" (:from sum)))
      (is (= "Hi" (:subject sum)))
      (is (false? (:seen? sum))))))

(deftest ^:unit attachment-part-test
  (testing "inline text/plain and text/html are body"
    (is (false? (imap/attachment-part? {:content-type "text/plain; charset=UTF-8"})))
    (is (false? (imap/attachment-part? {:content-type "TEXT/HTML" :disposition "inline"}))))

  (testing "a disposition, a filename or another type makes an attachment"
    (is (true? (imap/attachment-part? {:content-type "text/plain" :disposition "ATTACHMENT"})))
    (is (true? (imap/attachment-part? {:content-type "text/plain" :filename "notes.txt"})))
    (is (true? (imap/attachment-part? {:content-type "text/calendar; method=REQUEST"})))
    (is (true? (imap/attachment-part? {:content-type "application/pdf; name=a.pdf"})))))

(deftest ^:unit base-content-type-test
  (is (= "text/plain" (imap/base-content-type " Text/Plain ; charset=UTF-8")))
  (is (nil? (imap/base-content-type ";"))))

(deftest ^:unit build-attachment-test
  (is (= {:filename "a.pdf" :content-type "application/pdf" :size 3}
         (dissoc (imap/build-attachment {:content-type "application/pdf; name=a.pdf" :filename "a.pdf"}
                                        (byte-array 3))
                 :content)))
  (is (= "application/octet-stream"
         (:content-type (imap/build-attachment {:content-type nil} (byte-array 0)))))

  (testing "disposition is lower-cased and the content-id loses its angle brackets"
    (is (= {:disposition "inline" :content-id "logo@example.com"}
           (select-keys (imap/build-attachment {:content-type "image/png" :disposition "INLINE"
                                                :content-id " <logo@example.com> "}
                                               (byte-array 1))
                        [:disposition :content-id]))))

  (testing "no header, no key"
    (is (= #{:content-type :size :content}
           (set (keys (imap/build-attachment {:content-type "image/png" :disposition nil :content-id ""}
                                             (byte-array 1))))))))

(deftest ^:unit embedded-in-html-test
  (let [html "<p>Hi</p><img src=\"CID:logo@example.com\"><img src='cid:chart2@x'>"]
    (is (true? (imap/embedded-in-html? html {:content-id "logo@example.com"})))
    (is (true? (imap/embedded-in-html? html {:content-id "chart2@x"})))
    (testing "a prefix of a referenced id is not referenced"
      (is (false? (imap/embedded-in-html? html {:content-id "logo@example"}))))
    (is (false? (imap/embedded-in-html? html {:content-id "invoice@x"})))
    (is (false? (imap/embedded-in-html? html {})))
    (is (false? (imap/embedded-in-html? nil {:content-id "logo@example.com"})))))

(deftest ^:unit extract-body-text-first-wins-test
  (is (= {:text "first" :html "<p>first</p>"}
         (imap/extract-body-text [{:content-type "text/plain" :content "first"}
                                  {:content-type "text/html" :content "<p>first</p>"}
                                  {:content-type "text/plain" :content "second"}
                                  {:content-type "text/html" :content "<p>second</p>"}]))))
