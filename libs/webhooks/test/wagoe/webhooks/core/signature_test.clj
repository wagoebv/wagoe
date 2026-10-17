(ns wagoe.webhooks.core.signature-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.webhooks :as webhooks]
            [wagoe.webhooks.core.signature :as sig]))

(def secret "whsec_test")
(def body "{\"id\":\"e1\"}")

(deftest ^:unit header-shape
  (let [h (sig/signature-header secret 1700000000 body)]
    (is (re-matches #"t=1700000000,v1=[0-9a-f]{64}" h))
    (is (= {:t 1700000000 :v1 [(subs h 16)]} (sig/parse-header h)))))

(deftest ^:unit receiver-verifies-what-the-sender-signed
  (let [h (webhooks/sign secret body 1700000000)]
    (is (webhooks/verify-signature secret body h {:now 1700000010}))
    (testing "and refuses"
      (is (= :mismatch (webhooks/signature-problem secret (str body " ") h {:now 1700000010}))
          "a changed body")
      (is (= :mismatch (webhooks/signature-problem "other" body h {:now 1700000010}))
          "another secret")
      (is (= :stale (webhooks/signature-problem secret body h {:now 1700000301}))
          "a replay after the tolerance")
      (is (= :malformed (webhooks/signature-problem secret body "v1=abc")) "no timestamp")
      (is (= :malformed (webhooks/signature-problem secret body nil)) "no header")
      (is (= :stale (webhooks/signature-problem secret body "t=-9223372036854775808,v1=00"))
          "a timestamp whose distance from now overflows a long"))
    (testing "a raw body given as bytes"
      (is (webhooks/verify-signature secret (.getBytes ^String body "UTF-8") h {:now 1700000010})))
    (testing "a re-signed timestamp with the old signature"
      (let [forged (str "t=1700000300," (subs h (inc (.indexOf ^String h ","))))]
        (is (= :mismatch (webhooks/signature-problem secret body forged {:now 1700000300})))))))

(deftest ^:unit any-v1-may-match-during-rotation
  (let [good (sig/signature-header secret 100 body)
        h    (str "t=100,v1=" (apply str (repeat 64 "0")) "," (subs good 6))]
    (is (nil? (sig/signature-problem secret body h 100 {})))))

(deftest ^:unit sign-defaults-to-now
  (is (webhooks/verify-signature secret body (webhooks/sign secret body))))
