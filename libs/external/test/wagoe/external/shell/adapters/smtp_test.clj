(ns wagoe.external.shell.adapters.smtp-test
  "Integration tests for the SMTP provider adapter.
   Tests verify record creation, protocol satisfaction, and graceful error handling
   against an unreachable host — no real SMTP server required."
  (:require [wagoe.external.ports :as ports]
            [wagoe.external.shell.adapters.smtp :as smtp]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util Properties]
           [javax.mail Session]
           [javax.mail.internet MimeMultipart]))

(def ^:private test-config
  {:host "localhost-nonexistent.invalid"
   :port 1025
   :tls? false
   :ssl? false
   :from "no-reply@example.com"})

(deftest ^:integration create-smtp-provider-test
  (testing "returns a record satisfying ISmtpProvider"
    (let [provider (smtp/create-smtp-provider test-config)]
      (is (satisfies? ports/ISmtpProvider provider))
      (is (= "localhost-nonexistent.invalid" (:host provider)))
      (is (= 1025 (:port provider)))
      (is (= "no-reply@example.com" (:from provider))))))

(deftest ^:integration test-connection-unreachable-test
  (testing "test-connection! on unreachable host returns {:success? false}"
    (let [provider (smtp/create-smtp-provider test-config)
          result   (ports/test-connection! provider)]
      (is (false? (:success? result)))
      (is (some? (:error result)))
      (is (string? (get-in result [:error :message]))))))

(deftest ^:integration send-email-unreachable-test
  (testing "send-email! on unreachable host returns {:success? false}"
    (let [provider (smtp/create-smtp-provider test-config)
          email    {:to      "dest@example.com"
                    :subject "Test"
                    :body    "Hello"}
          result   (ports/send-email! provider email)]
      (is (false? (:success? result)))
      (is (some? (:error result))))))

;; =============================================================================
;; MIME Builder — attachment support (BOU-150)
;; =============================================================================

(defn- test-session []
  (Session/getInstance (Properties.)))

(defn- multipart-parts [^MimeMultipart mp]
  (mapv #(.getBodyPart mp %) (range (.getCount mp))))

(deftest ^:unit build-mime-message-plain-body-test
  (testing "no attachments, no html — msg content is a plain String (unchanged)"
    (let [email {:to "dest@example.com" :subject "Plain" :body "Hello"}
          msg   (#'smtp/build-mime-message (test-session) email "from@example.com")]
      (is (string? (.getContent msg)))
      (is (= "Hello" (.getContent msg))))))

(deftest ^:unit build-mime-message-html-body-test
  (testing "html-body, no attachments — multipart/alternative (unchanged)"
    (let [email {:to "dest@example.com" :subject "HTML" :body "text" :html-body "<p>hi</p>"}
          msg   (#'smtp/build-mime-message (test-session) email "from@example.com")
          content (.getContent msg)]
      (is (instance? MimeMultipart content))
      (is (str/includes? (.getContentType content) "multipart/alternative")))))

(deftest ^:unit build-mime-message-attachment-test
  (testing "attachments become an application/pdf part inside a mixed multipart"
    (let [pdf-bytes (.getBytes "%PDF-1.4 fake invoice" "UTF-8")
          email     {:to      "dest@example.com"
                     :subject "Invoice INV-1"
                     :body    "Please find your invoice attached."
                     :attachments [{:filename     "invoice.pdf"
                                    :content-type "application/pdf"
                                    :content      pdf-bytes}]}
          ;; saveChanges finalizes part Content-Type headers (what Transport/send does).
          msg       (doto (#'smtp/build-mime-message (test-session) email "from@example.com")
                      (.saveChanges))
          content   (.getContent msg)]
      (is (instance? MimeMultipart content))
      (is (str/includes? (.getContentType content) "multipart/mixed"))
      (is (= 2 (.getCount content)) "one body part + one attachment part")
      (let [parts    (multipart-parts content)
            pdf-part (first (filter #(= "invoice.pdf" (.getFileName %)) parts))]
        (is (some? pdf-part) "a part named invoice.pdf is present")
        (is (str/starts-with? (.getContentType pdf-part) "application/pdf"))
        (is (= (seq pdf-bytes)
               (seq (.readAllBytes (.getInputStream pdf-part))))
            "attachment bytes round-trip intact")))))

(deftest ^:unit build-mime-message-html-and-attachment-test
  (testing "html-body + attachment — mixed multipart with a nested alternative body part"
    (let [pdf-bytes (.getBytes "%PDF-1.4" "UTF-8")
          email     {:to      "dest@example.com"
                     :subject "Invoice"
                     :body    "text"
                     :html-body "<p>invoice</p>"
                     :attachments [{:filename     "invoice.pdf"
                                    :content-type "application/pdf"
                                    :content      pdf-bytes}]}
          msg       (doto (#'smtp/build-mime-message (test-session) email "from@example.com")
                      (.saveChanges))
          content   (.getContent msg)]
      (is (instance? MimeMultipart content))
      (is (str/includes? (.getContentType content) "multipart/mixed"))
      (is (= 2 (.getCount content)))
      (let [parts     (multipart-parts content)
            body-part (first (remove #(.getFileName %) parts))
            pdf-part  (first (filter #(= "invoice.pdf" (.getFileName %)) parts))]
        (is (some? pdf-part))
        (is (str/starts-with? (.getContentType body-part) "multipart/alternative")
            "body part carries the text/html alternative")))))

(deftest ^:unit build-mime-message-base64-attachment-test
  (testing "attachment :content as a base64 string is decoded to the original bytes"
    (let [pdf-bytes (.getBytes "%PDF-1.4 base64" "UTF-8")
          b64       (.encodeToString (java.util.Base64/getEncoder) pdf-bytes)
          email     {:to      "dest@example.com"
                     :subject "Invoice"
                     :body    "body"
                     :attachments [{:filename     "invoice.pdf"
                                    :content-type "application/pdf"
                                    :content      b64}]}
          msg       (#'smtp/build-mime-message (test-session) email "from@example.com")
          pdf-part  (->> (multipart-parts (.getContent msg))
                         (filter #(= "invoice.pdf" (.getFileName %)))
                         first)]
      (is (= (seq pdf-bytes)
             (seq (.readAllBytes (.getInputStream pdf-part))))))))

(deftest ^:unit build-mime-message-message-id-test
  (testing ":message-id sets a Message-ID header that survives saveChanges/send"
    (let [email {:to "dest@example.com" :subject "Invoice" :body "B" :message-id "inv-123"}
          ;; saveChanges is what Transport/send calls; it regenerates Message-ID
          ;; unless updateMessageID is overridden — assert our id survives it.
          msg   (doto (#'smtp/build-mime-message (test-session) email "from@example.com")
                  (.saveChanges))]
      (is (= "<inv-123>" (.getMessageID msg))))))

(deftest ^:unit build-mime-message-message-id-already-bracketed-test
  (testing "an already-bracketed :message-id is used verbatim"
    (let [email {:to "dest@example.com" :subject "Invoice" :body "B"
                 :message-id "<abc@wagoe>"}
          msg   (doto (#'smtp/build-mime-message (test-session) email "from@example.com")
                  (.saveChanges))]
      (is (= "<abc@wagoe>" (.getMessageID msg))))))

(deftest ^:unit build-mime-message-default-message-id-test
  (testing "no :message-id -> javax.mail generates one (unchanged default)"
    (let [email {:to "dest@example.com" :subject "S" :body "B"}
          msg   (doto (#'smtp/build-mime-message (test-session) email "from@example.com")
                  (.saveChanges))]
      (is (some? (.getMessageID msg))))))

(deftest ^:integration send-email-async-test
  (testing "send-email-async! returns a future whose value reports the failure"
    (let [provider (smtp/create-smtp-provider test-config)
          email    {:to "dest@example.com" :subject "Async" :body "Hi"}
          fut      (ports/send-email-async! provider email)]
      (is (future? fut))
      ;; The deadline was 5 seconds, and this test failed intermittently in
      ;; full-suite runs while passing alone (BOU-355). What it is about is
      ;; that the future eventually reports failure, not how quickly — and the
      ;; five seconds were being spent on neither the adapter nor the network:
      ;; the send against an unresolvable host returns in 1–31 ms measured, and
      ;; `future` runs on the agent send-off pool, which a full suite saturates.
      ;; A future that has not been *scheduled* yet fails a deadline that has
      ;; nothing to do with what is under test.
      ;;
      ;; Bounded rather than a bare deref so a genuine hang still fails the
      ;; suite instead of stopping it, and generous enough that it can only be
      ;; reached by something actually wrong.
      (let [result (deref fut 60000 :timeout)]
        (is (not= :timeout result)
            "the send never completed — adapter hang, not scheduling latency")
        (is (false? (:success? result)))))))
