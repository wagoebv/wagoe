(ns wagoe.email.shell.adapters.smtp-test
  "Integration tests for SMTP adapter."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.email.shell.adapters.smtp :as smtp]
            [wagoe.email.core.email :as email]
            [wagoe.email.ports :as ports]))

;; =============================================================================
;; SMTP Sender Creation Tests
;; =============================================================================

(deftest ^:integration create-smtp-sender-test
  (testing "Create SMTP sender with valid config"
    (let [config {:host "smtp.example.com"
                  :port 587
                  :username "user@example.com"
                  :password "password"
                  :tls? true}
          sender (smtp/create-smtp-sender config)]
      (is (= "smtp.example.com" (:host sender)))
      (is (= 587 (:port sender)))
      (is (= "user@example.com" (:username sender)))
      (is (= "password" (:password sender)))
      (is (:tls? sender))
      (is (not (:ssl? sender)))))

  (testing "Create SMTP sender with SSL"
    (let [config {:host "smtp.gmail.com"
                  :port 465
                  :ssl? true
                  :tls? false}  ; Explicitly disable TLS for SSL
          sender (smtp/create-smtp-sender config)]
      (is (= "smtp.gmail.com" (:host sender)))
      (is (= 465 (:port sender)))
      (is (:ssl? sender))
      (is (not (:tls? sender)))))

  (testing "Create SMTP sender with defaults"
    (let [config {:host "localhost"
                  :port 1025}
          sender (smtp/create-smtp-sender config)]
      (is (= "localhost" (:host sender)))
      (is (= 1025 (:port sender)))
      (is (:tls? sender))  ; Default is true
      (is (not (:ssl? sender)))))

  (testing "Create SMTP sender without authentication"
    (let [config {:host "localhost"
                  :port 1025
                  :tls? false}
          sender (smtp/create-smtp-sender config)]
      (is (= "localhost" (:host sender)))
      (is (nil? (:username sender)))
      (is (nil? (:password sender)))
      (is (not (:tls? sender))))))

;; =============================================================================
;; Domain Translation — attachment pass-through (BOU-150)
;; =============================================================================

(deftest ^:unit email->outbound-preserves-attachments-test
  (testing "attachments survive translation to the OutboundEmail transport map"
    (let [pdf-bytes   (.getBytes "%PDF-1.4 fake" "UTF-8")
          attachments [{:filename "invoice.pdf" :content-type "application/pdf" :content pdf-bytes}]
          email       {:to ["user@example.com"] :from "sender@example.com"
                       :subject "Invoice" :body "attached"
                       :attachments attachments}
          outbound    (#'smtp/email->outbound email)]
      (is (= attachments (:attachments outbound))
          "email->outbound must not drop :attachments"))))

(deftest ^:unit email->outbound-omits-absent-attachments-test
  (testing "no :attachments key when the email carries none"
    (let [email    {:to ["user@example.com"] :from "sender@example.com"
                    :subject "Plain" :body "hi"}
          outbound (#'smtp/email->outbound email)]
      (is (not (contains? outbound :attachments))))))

;; =============================================================================
;; SMTP Sender Error Handling Tests
;; =============================================================================

(deftest ^:integration send-email-with-invalid-host-test
  (testing "Send email with invalid SMTP host returns error"
    (let [sender (smtp/create-smtp-sender {:host "invalid-smtp-host-that-does-not-exist.example.com"
                                           :port 9999
                                           :tls? false})
          email-input {:to "user@example.com"
                       :from "sender@example.com"
                       :subject "Test"
                       :body "Hello"}
          email (email/prepare-email email-input (java.util.UUID/randomUUID) (java.time.Instant/now))
          result (ports/send-email! sender email)]
      (is (false? (:success? result)))
      (is (some? (:error result)))
      (is (= "SmtpError" (get-in result [:error :type])))
      (is (string? (get-in result [:error :message]))))))

(deftest ^:integration send-email-with-connection-refused-test
  (testing "Send email with connection refused returns error"
    (let [sender (smtp/create-smtp-sender {:host "localhost"
                                           :port 9999  ; Port that's not listening
                                           :tls? false})
          email-input {:to "user@example.com"
                       :from "sender@example.com"
                       :subject "Test"
                       :body "Hello"}
          email (email/prepare-email email-input (java.util.UUID/randomUUID) (java.time.Instant/now))
          result (ports/send-email! sender email)]
      (is (false? (:success? result)))
      (is (some? (:error result)))
      (is (or (= "SmtpError" (get-in result [:error :type]))
              (= "UnexpectedError" (get-in result [:error :type])))))))

;; =============================================================================
;; Email Structure Tests
;; =============================================================================

(deftest ^:integration send-email-with-multiple-recipients-test
  (testing "Send email with multiple recipients (invalid host, test structure only)"
    (let [sender (smtp/create-smtp-sender {:host "invalid-host.example.com"
                                           :port 9999
                                           :tls? false})
          email-input {:to ["user1@example.com" "user2@example.com" "user3@example.com"]
                       :from "sender@example.com"
                       :subject "Test Multiple Recipients"
                       :body "Hello everyone"}
          email (email/prepare-email email-input (java.util.UUID/randomUUID) (java.time.Instant/now))
          result (ports/send-email! sender email)]
      ;; Should fail to connect, but validates structure is correct
      (is (false? (:success? result)))
      (is (some? (:error result)))
      ;; Verify email structure was prepared correctly
      (is (= 3 (count (:to email))))
      (is (vector? (:to email))))))

(deftest ^:integration send-email-with-headers-test
  (testing "Send email with custom headers (invalid host, test structure only)"
    (let [sender (smtp/create-smtp-sender {:host "invalid-host.example.com"
                                           :port 9999
                                           :tls? false})
          email-input {:to "user@example.com"
                       :from "sender@example.com"
                       :subject "Test Headers"
                       :body "Hello"
                       :headers {:reply-to "support@example.com"
                                 :cc "admin@example.com"
                                 :bcc "bcc@example.com"}}
          email (email/prepare-email email-input (java.util.UUID/randomUUID) (java.time.Instant/now))
          result (ports/send-email! sender email)]
      ;; Should fail to connect, but validates structure is correct
      (is (false? (:success? result)))
      ;; Verify headers were prepared correctly
      (is (= "support@example.com" (get-in email [:headers :reply-to])))
      (is (= "admin@example.com" (get-in email [:headers :cc])))
      (is (= "bcc@example.com" (get-in email [:headers :bcc]))))))

;; =============================================================================
;; Async Email Sending Tests
;; =============================================================================

(deftest ^:integration send-email-async-test
  (testing "Send email asynchronously returns future"
    (let [sender (smtp/create-smtp-sender {:host "invalid-host.example.com"
                                           :port 9999
                                           :tls? false})
          email-input {:to "user@example.com"
                       :from "sender@example.com"
                       :subject "Async Test"
                       :body "Hello async"}
          email (email/prepare-email email-input (java.util.UUID/randomUUID) (java.time.Instant/now))
          result-future (ports/send-email-async! sender email)]

      ;; Should return a future immediately
      (is (future? result-future))

      ;; Wait for result (should fail with invalid host)
      (let [result @result-future]
        (is (false? (:success? result)))
        (is (some? (:error result)))))))

;; =============================================================================
;; Email Validation Integration Tests
;; =============================================================================

(deftest ^:integration send-email-validates-before-sending-test
  (testing "Prepare and validate email before sending"
    (let [sender (smtp/create-smtp-sender {:host "smtp.example.com"
                                           :port 587
                                           :tls? true})
          email-input {:to "user@example.com"
                       :from "sender@example.com"
                       :subject "Test"
                       :body "Hello"}
          email (email/prepare-email email-input (java.util.UUID/randomUUID) (java.time.Instant/now))
          validation (email/validate-email email)]

      ;; Email should be valid
      (is (:valid? validation))
      (is (empty? (:errors validation)))

      ;; Now try to send (will fail with invalid host)
      (let [result (ports/send-email! sender email)]
        (is (false? (:success? result))))))

  (testing "Invalid email structure should be caught by validation"
    (let [email-input {:to "invalid-email"  ; Invalid format
                       :from "sender@example.com"
                       :subject "Test"
                       :body "Hello"}
          email (email/prepare-email email-input (java.util.UUID/randomUUID) (java.time.Instant/now))
          validation (email/validate-email email)]

      ;; Email should be invalid
      (is (not (:valid? validation)))
      (is (seq (:errors validation)))
      ;; Should not attempt to send invalid email
      (is (some #(= "Invalid recipient email addresses" %) (:errors validation))))))

;; =============================================================================
;; Complete Email Workflow Tests
;; =============================================================================

(deftest ^:integration complete-email-workflow-test
  (testing "Complete workflow: prepare, validate, and attempt send"
    (let [sender (smtp/create-smtp-sender {:host "invalid-host.example.com"
                                           :port 9999
                                           :tls? false})
          ;; Step 1: Prepare email
          email-input {:to "user@example.com"
                       :from "sender@example.com"
                       :subject "Complete Workflow Test"
                       :body "Testing complete workflow"}
          prepared-email (email/prepare-email email-input (java.util.UUID/randomUUID) (java.time.Instant/now))

          ;; Step 2: Add custom headers
          email-with-headers (-> prepared-email
                                 (email/add-reply-to "support@example.com")
                                 (email/add-cc "admin@example.com"))

          ;; Step 3: Validate
          validation (email/validate-email email-with-headers)]

      ;; Validation should pass
      (is (:valid? validation))
      (is (empty? (:errors validation)))

      ;; Step 4: Attempt to send (will fail with invalid host)
      (let [result (ports/send-email! sender email-with-headers)]
        (is (false? (:success? result)))
        (is (some? (:error result)))

        ;; Create summary for logging
        (let [summary (email/email-summary email-with-headers)]
          (is (uuid? (:id summary)))
          (is (= 1 (:to summary)))
          (is (= "sender@example.com" (:from summary)))
          (is (= "Complete Workflow Test" (:subject summary)))
          (is (false? (:has-attachments? summary))))))))

;; =============================================================================
;; Protocol Implementation Tests
;; =============================================================================

(deftest ^:integration smtp-sender-implements-protocol-test
  (testing "SmtpEmailSender implements EmailSenderProtocol"
    (let [sender (smtp/create-smtp-sender {:host "localhost"
                                           :port 1025
                                           :tls? false})]
      (is (satisfies? ports/EmailSenderProtocol sender))
      ;; Can call protocol methods
      (is (fn? ports/send-email!))
      (is (fn? ports/send-email-async!)))))

;; =============================================================================
;; NOTE: Real SMTP Server Tests
;; =============================================================================

;; The following tests are COMMENTED OUT because they require a real SMTP server.
;; To run these tests:
;; 1. Start a local SMTP test server (e.g., MailHog, MailCatcher, or smtp4dev)
;; 2. Update the config below with your server details
;; 3. Uncomment the tests

;; (deftest ^:integration ^:requires-smtp send-email-success-test
;;   (testing "Send email successfully to real SMTP server"
;;     (let [sender (smtp/create-smtp-sender {:host "localhost"
;;                                             :port 1025
;;                                             :tls? false})
;;           email-input {:to "test@example.com"
;;                        :from "sender@example.com"
;;                        :subject "Test Email"
;;                        :body "This is a test email."}
;;           email (email/prepare-email email-input (java.util.UUID/randomUUID) (java.time.Instant/now))
;;           result (ports/send-email! sender email)]
;;       (is (:success? result))
;;       (is (some? (:message-id result)))
;;       (is (string? (:message-id result))))))

;; (deftest ^:integration ^:requires-smtp send-email-with-all-features-test
;;   (testing "Send email with all features to real SMTP server"
;;     (let [sender (smtp/create-smtp-sender {:host "localhost"
;;                                             :port 1025
;;                                             :tls? false})
;;           email-input {:to ["user1@example.com" "user2@example.com"]
;;                        :from "sender@example.com"
;;                        :subject "Feature Test"
;;                        :body "Testing all email features."}
;;           email (-> (email/prepare-email email-input (java.util.UUID/randomUUID) (java.time.Instant/now))
;;                     (email/add-reply-to "support@example.com")
;;                     (email/add-cc "admin@example.com")
;;                     (email/add-bcc "archive@example.com"))
;;           result (ports/send-email! sender email)]
;;       (is (:success? result))
;;       (is (some? (:message-id result))))))
