(ns wagoe.external.core.smtp-test
  (:require [wagoe.external.core.smtp :as smtp]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util Properties]))

(deftest ^:unit normalize-recipients-test
  (testing "string to single-element vector"
    (is (= ["user@example.com"] (smtp/normalize-recipients "user@example.com"))))

  (testing "vector passthrough"
    (is (= ["a@b.com" "c@d.com"] (smtp/normalize-recipients ["a@b.com" "c@d.com"]))))

  (testing "nil returns empty vector"
    (is (= [] (smtp/normalize-recipients nil))))

  (testing "empty string returns empty vector"
    (is (= [] (smtp/normalize-recipients ""))))

  (testing "filters blank strings from vector"
    (is (= ["a@b.com"] (smtp/normalize-recipients ["a@b.com" "" nil])))))

(deftest ^:unit build-mime-properties-test
  (testing "returns Properties instance"
    (let [props (smtp/build-mime-properties {:host "smtp.example.com" :port 587})]
      (is (instance? Properties props))))

  (testing "sets host and port"
    (let [props (smtp/build-mime-properties {:host "smtp.example.com" :port 587})]
      (is (= "smtp.example.com" (.getProperty props "mail.smtp.host")))
      (is (= "587" (.getProperty props "mail.smtp.port")))))

  (testing "sets TLS properties when tls? true"
    (let [props (smtp/build-mime-properties {:host "h" :port 587 :tls? true})]
      (is (= "true" (.getProperty props "mail.smtp.starttls.enable")))
      (is (= "true" (.getProperty props "mail.smtp.starttls.required")))))

  (testing "no TLS properties when tls? false"
    (let [props (smtp/build-mime-properties {:host "h" :port 25 :tls? false})]
      (is (nil? (.getProperty props "mail.smtp.starttls.enable")))))

  (testing "sets SSL properties when ssl? true"
    (let [props (smtp/build-mime-properties {:host "h" :port 465 :ssl? true})]
      (is (= "true" (.getProperty props "mail.smtp.ssl.enable")))
      (is (= "465" (.getProperty props "mail.smtp.socketFactory.port")))))

  (testing "sets auth when username and password provided"
    (let [props (smtp/build-mime-properties {:host "h" :port 587
                                             :username "u" :password "p"})]
      (is (= "true" (.getProperty props "mail.smtp.auth"))))))

(deftest ^:unit validate-config-test
  (testing "valid config returns {:valid? true}"
    (let [result (smtp/validate-config {:host "smtp.example.com"
                                        :port 587
                                        :from "no-reply@example.com"})]
      (is (true? (:valid? result)))))

  (testing "missing host returns {:valid? false}"
    (let [result (smtp/validate-config {:port 587 :from "a@b.com"})]
      (is (false? (:valid? result)))
      (is (some? (:errors result))))))

(deftest ^:unit prepare-outbound-email-test
  (testing "normalises :to to vector"
    (let [now    (java.util.Date.)
          result (smtp/prepare-outbound-email
                  {:to "user@example.com" :subject "Hi" :body "Hello"}
                  now)]
      (is (= ["user@example.com"] (:to result)))
      (is (= now (:prepared-at result)))))

  (testing "preserves vector :to"
    (let [now    (java.util.Date.)
          result (smtp/prepare-outbound-email
                  {:to ["a@b.com" "c@d.com"] :subject "Hi"}
                  now)]
      (is (= ["a@b.com" "c@d.com"] (:to result))))))

(deftest ^:unit outbound-email-summary-test
  (testing "returns expected summary keys"
    (let [email  {:to      ["a@b.com" "c@d.com"]
                  :from    "no-reply@example.com"
                  :subject "Test"
                  :body    "plain text"
                  :html-body "<p>html</p>"}
          summary (smtp/outbound-email-summary email)]
      (is (= 2 (:to-count summary)))
      (is (= "no-reply@example.com" (:from summary)))
      (is (= "Test" (:subject summary)))
      (is (true? (:has-html? summary)))
      (is (false? (:has-reply-to? summary))))))
