(ns wagoe.email.schema-test
  "Schema validation tests for the email module.

   Regression: Attachment previously used `:bytes` (not a Malli schema),
   making every validator that touches it throw on first call."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.email.schema :as schema]))

(def ^:private valid-email
  {:id (java.util.UUID/randomUUID)
   :to ["jane@example.com"]
   :from "noreply@example.com"
   :subject "Hello"
   :body "Body"
   :created-at (java.util.Date.)})

(deftest ^:unit valid-email?-test
  (testing "valid email passes"
    (is (schema/valid-email? valid-email)))
  (testing "missing subject fails"
    (is (not (schema/valid-email? (dissoc valid-email :subject)))))
  (testing "attachment content accepts bytes and string"
    (let [with-attachment (fn [content]
                            (assoc valid-email
                                   :attachments [{:filename "a.pdf"
                                                  :content-type "application/pdf"
                                                  :content content}]))]
      (is (schema/valid-email? (with-attachment (byte-array [1 2 3]))))
      (is (schema/valid-email? (with-attachment "YmFzZTY0")))
      (is (not (schema/valid-email? (with-attachment 42)))))))

(deftest ^:unit valid-email-input?-test
  (testing "string recipient allowed on input schema"
    (is (schema/valid-email-input? {:to "jane@example.com"
                                    :from "noreply@example.com"
                                    :subject "Hello"
                                    :body "Body"}))))

(deftest ^:unit explain-email-errors-test
  (testing "invalid email produces explanation"
    (is (some? (schema/explain-email-errors {})))
    (is (nil? (schema/explain-email-errors valid-email)))))
