(ns wagoe.email.core.email-test
  "Unit tests for email core functions."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.email.core.email :as email])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Email Address Validation Tests
;; =============================================================================

(deftest ^:unit valid-email-address?-test
  (testing "Valid email addresses"
    (is (true? (email/valid-email-address? "user@example.com")))
    (is (true? (email/valid-email-address? "test.user@example.com")))
    (is (true? (email/valid-email-address? "user+tag@example.co.uk")))
    (is (true? (email/valid-email-address? "123@test.com")))
    (is (true? (email/valid-email-address? "user_name@example.org"))))

  (testing "Invalid email addresses"
    (is (false? (email/valid-email-address? "invalid-email")))
    (is (false? (email/valid-email-address? "@example.com")))
    (is (false? (email/valid-email-address? "user@")))
    (is (false? (email/valid-email-address? "user @example.com")))
    (is (false? (email/valid-email-address? "")))
    (is (false? (email/valid-email-address? nil)))
    (is (false? (email/valid-email-address? 123)))))

(deftest ^:unit validate-recipients-test
  (testing "All valid email addresses"
    (let [result (email/validate-recipients ["user@example.com" "admin@test.org"])]
      (is (:valid? result))
      (is (= ["user@example.com" "admin@test.org"] (:valid-emails result)))
      (is (empty? (:invalid-emails result)))))

  (testing "Some invalid email addresses"
    (let [result (email/validate-recipients ["valid@example.com" "invalid-email" "another@test.com"])]
      (is (not (:valid? result)))
      (is (= ["valid@example.com" "another@test.com"] (:valid-emails result)))
      (is (= ["invalid-email"] (:invalid-emails result)))))

  (testing "Single email as string"
    (let [result (email/validate-recipients "user@example.com")]
      (is (:valid? result))
      (is (= ["user@example.com"] (:valid-emails result)))
      (is (empty? (:invalid-emails result)))))

  (testing "Single invalid email as string"
    (let [result (email/validate-recipients "invalid")]
      (is (not (:valid? result)))
      (is (empty? (:valid-emails result)))
      (is (= ["invalid"] (:invalid-emails result)))))

  (testing "Empty recipient list"
    (let [result (email/validate-recipients [])]
      (is (:valid? result))
      (is (empty? (:valid-emails result)))
      (is (empty? (:invalid-emails result))))))

;; =============================================================================
;; Header Formatting Tests
;; =============================================================================

(deftest ^:unit format-headers-test
  (testing "Format headers with mixed key types"
    (let [headers {"Content-Type" "text/plain"
                   :reply-to "support@example.com"
                   "X-Custom" "value"}
          result (email/format-headers headers)]
      (is (= "text/plain" (:Content-Type result)))
      (is (= "support@example.com" (:reply-to result)))
      (is (= "value" (:X-Custom result)))))

  (testing "Format headers with nil values handled"
    (let [result (email/format-headers nil)]
      (is (nil? result))))

  (testing "Format headers converts values to strings"
    (let [headers {:priority 1
                   :flag true}
          result (email/format-headers headers)]
      (is (= "1" (:priority result)))
      (is (= "true" (:flag result))))))

;; =============================================================================
;; Email Preparation Tests
;; =============================================================================

(deftest ^:unit normalize-recipients-test
  (testing "Normalize string to vector"
    (is (= ["user@example.com"] (email/normalize-recipients "user@example.com"))))

  (testing "Keep vector as vector"
    (is (= ["user1@example.com" "user2@example.com"]
           (email/normalize-recipients ["user1@example.com" "user2@example.com"])))))

(deftest ^:unit prepare-email-test
  (testing "Prepare email with required fields only"
    (let [email-input {:to "user@example.com"
                       :from "sender@example.com"
                       :subject "Test"
                       :body "Hello"}
          result (email/prepare-email email-input (UUID/randomUUID) (Instant/now))]
      (is (uuid? (:id result)))
      (is (vector? (:to result)))
      (is (= ["user@example.com"] (:to result)))
      (is (= "sender@example.com" (:from result)))
      (is (= "Test" (:subject result)))
      (is (= "Hello" (:body result)))
      (is (inst? (:created-at result)))))

  (testing "Prepare email with vector of recipients"
    (let [email-input {:to ["user1@example.com" "user2@example.com"]
                       :from "sender@example.com"
                       :subject "Test"
                       :body "Hello"}
          result (email/prepare-email email-input (UUID/randomUUID) (Instant/now))]
      (is (= ["user1@example.com" "user2@example.com"] (:to result)))))

  (testing "Prepare email with headers"
    (let [email-input {:to "user@example.com"
                       :from "sender@example.com"
                       :subject "Test"
                       :body "Hello"
                       :headers {:reply-to "support@example.com"}}
          result (email/prepare-email email-input (UUID/randomUUID) (Instant/now))]
      (is (= "support@example.com" (get-in result [:headers :reply-to])))))

  (testing "Prepare email with attachments"
    (let [attachments [{:filename "doc.pdf" :content-type "application/pdf"}]
          email-input {:to "user@example.com"
                       :from "sender@example.com"
                       :subject "Test"
                       :body "Hello"
                       :attachments attachments}
          result (email/prepare-email email-input (UUID/randomUUID) (Instant/now))]
      (is (= attachments (:attachments result)))))

  (testing "Prepare email with metadata"
    (let [metadata {:user-id "123" :campaign "welcome"}
          email-input {:to "user@example.com"
                       :from "sender@example.com"
                       :subject "Test"
                       :body "Hello"
                       :metadata metadata}
          result (email/prepare-email email-input (UUID/randomUUID) (Instant/now))]
      (is (= metadata (:metadata result))))))

;; =============================================================================
;; Email Validation Tests
;; =============================================================================

(deftest ^:unit validate-email-test
  (testing "Valid email structure"
    (let [email {:to ["user@example.com"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"}
          result (email/validate-email email)]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "Missing required field: to"
    (let [email {:from "sender@example.com"
                 :subject "Test"
                 :body "Hello"}
          result (email/validate-email email)]
      (is (not (:valid? result)))
      (is (some #(= "Missing required field: to" %) (:errors result)))))

  (testing "Missing required field: from"
    (let [email {:to ["user@example.com"]
                 :subject "Test"
                 :body "Hello"}
          result (email/validate-email email)]
      (is (not (:valid? result)))
      (is (some #(= "Missing required field: from" %) (:errors result)))))

  (testing "Missing required field: subject"
    (let [email {:to ["user@example.com"]
                 :from "sender@example.com"
                 :body "Hello"}
          result (email/validate-email email)]
      (is (not (:valid? result)))
      (is (some #(= "Missing required field: subject" %) (:errors result)))))

  (testing "Missing required field: body"
    (let [email {:to ["user@example.com"]
                 :from "sender@example.com"
                 :subject "Test"}
          result (email/validate-email email)]
      (is (not (:valid? result)))
      (is (some #(= "Missing required field: body" %) (:errors result)))))

  (testing "Invalid from email address"
    (let [email {:to ["user@example.com"]
                 :from "invalid-email"
                 :subject "Test"
                 :body "Hello"}
          result (email/validate-email email)]
      (is (not (:valid? result)))
      (is (some #(= "Invalid from email address" %) (:errors result)))))

  (testing "Invalid recipient email addresses"
    (let [email {:to ["invalid-email"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"}
          result (email/validate-email email)]
      (is (not (:valid? result)))
      (is (some #(= "Invalid recipient email addresses" %) (:errors result)))))

  (testing "Multiple validation errors"
    (let [email {:to ["invalid"]
                 :from "also-invalid"}
          result (email/validate-email email)]
      (is (not (:valid? result)))
      (is (>= (count (:errors result)) 4)))))  ; Missing subject, body, and 2 invalid emails

;; =============================================================================
;; Email Utilities Tests
;; =============================================================================

(deftest ^:unit email-summary-test
  (testing "Create email summary with minimal fields"
    (let [email-id (UUID/randomUUID)
          created-at (Instant/now)
          email {:id email-id
                 :to ["user1@example.com" "user2@example.com"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"
                 :created-at created-at}
          result (email/email-summary email)]
      (is (= email-id (:id result)))
      (is (= 2 (:to result)))
      (is (= "sender@example.com" (:from result)))
      (is (= "Test" (:subject result)))
      (is (false? (:has-attachments? result)))
      (is (= created-at (:created-at result)))))

  (testing "Create email summary with attachments"
    (let [email {:id (UUID/randomUUID)
                 :to ["user@example.com"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"
                 :attachments [{:filename "doc.pdf"}]
                 :created-at (Instant/now)}
          result (email/email-summary email)]
      (is (true? (:has-attachments? result)))))

  (testing "Create email summary with no attachments"
    (let [email {:id (UUID/randomUUID)
                 :to ["user@example.com"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"
                 :attachments []
                 :created-at (Instant/now)}
          result (email/email-summary email)]
      (is (false? (:has-attachments? result))))))

(deftest ^:unit add-reply-to-test
  (testing "Add reply-to header to email without existing headers"
    (let [email {:to ["user@example.com"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"}
          result (email/add-reply-to email "support@example.com")]
      (is (= "support@example.com" (get-in result [:headers :reply-to])))))

  (testing "Add reply-to header to email with existing headers"
    (let [email {:to ["user@example.com"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"
                 :headers {:x-custom "value"}}
          result (email/add-reply-to email "support@example.com")]
      (is (= "support@example.com" (get-in result [:headers :reply-to])))
      (is (= "value" (get-in result [:headers :x-custom])))))

  (testing "Replace existing reply-to header"
    (let [email {:to ["user@example.com"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"
                 :headers {:reply-to "old@example.com"}}
          result (email/add-reply-to email "new@example.com")]
      (is (= "new@example.com" (get-in result [:headers :reply-to]))))))

(deftest ^:unit add-cc-test
  (testing "Add CC recipients as vector"
    (let [email {:to ["user@example.com"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"}
          result (email/add-cc email ["cc1@example.com" "cc2@example.com"])]
      (is (= "cc1@example.com, cc2@example.com" (get-in result [:headers :cc])))))

  (testing "Add CC recipient as string"
    (let [email {:to ["user@example.com"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"}
          result (email/add-cc email "cc@example.com")]
      (is (= "cc@example.com" (get-in result [:headers :cc])))))

  (testing "Add CC to email with existing headers"
    (let [email {:to ["user@example.com"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"
                 :headers {:reply-to "support@example.com"}}
          result (email/add-cc email ["cc@example.com"])]
      (is (= "cc@example.com" (get-in result [:headers :cc])))
      (is (= "support@example.com" (get-in result [:headers :reply-to]))))))

(deftest ^:unit add-bcc-test
  (testing "Add BCC recipients as vector"
    (let [email {:to ["user@example.com"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"}
          result (email/add-bcc email ["bcc1@example.com" "bcc2@example.com"])]
      (is (= "bcc1@example.com, bcc2@example.com" (get-in result [:headers :bcc])))))

  (testing "Add BCC recipient as string"
    (let [email {:to ["user@example.com"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"}
          result (email/add-bcc email "bcc@example.com")]
      (is (= "bcc@example.com" (get-in result [:headers :bcc])))))

  (testing "Add BCC to email with existing headers"
    (let [email {:to ["user@example.com"]
                 :from "sender@example.com"
                 :subject "Test"
                 :body "Hello"
                 :headers {:cc "cc@example.com"}}
          result (email/add-bcc email ["bcc@example.com"])]
      (is (= "bcc@example.com" (get-in result [:headers :bcc])))
      (is (= "cc@example.com" (get-in result [:headers :cc]))))))

;; =============================================================================
;; Integration Tests (Pure Function Composition)
;; =============================================================================

(deftest ^:unit complete-email-workflow-test
  (testing "Complete email preparation and validation workflow"
    (let [email-input {:to "user@example.com"
                       :from "sender@example.com"
                       :subject "Welcome"
                       :body "Hello, welcome to our service!"}
          ;; Prepare email
          prepared (email/prepare-email email-input (UUID/randomUUID) (Instant/now))
          ;; Add reply-to
          with-reply-to (email/add-reply-to prepared "support@example.com")
          ;; Add CC
          with-cc (email/add-cc with-reply-to "admin@example.com")
          ;; Validate
          validation (email/validate-email with-cc)]

      (is (:valid? validation))
      (is (empty? (:errors validation)))
      (is (= ["user@example.com"] (:to with-cc)))
      (is (= "support@example.com" (get-in with-cc [:headers :reply-to])))
      (is (= "admin@example.com" (get-in with-cc [:headers :cc])))
      (is (uuid? (:id with-cc)))
      (is (inst? (:created-at with-cc))))))
