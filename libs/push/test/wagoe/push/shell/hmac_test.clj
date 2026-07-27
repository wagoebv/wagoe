(ns wagoe.push.shell.hmac-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.push.shell.service :as service]))

(deftest ^:unit generate-callback-token-test
  (testing "generates consistent HMAC for same input"
    (let [secret "test-secret-32chars-minimum!!"
          msg-id "msg-123"]
      (is (= (service/generate-callback-token secret msg-id)
             (service/generate-callback-token secret msg-id)))))

  (testing "different message-ids produce different tokens"
    (let [secret "test-secret"]
      (is (not= (service/generate-callback-token secret "msg-1")
                (service/generate-callback-token secret "msg-2")))))

  (testing "different secrets produce different tokens"
    (is (not= (service/generate-callback-token "secret-a" "msg-1")
              (service/generate-callback-token "secret-b" "msg-1")))))

(deftest ^:unit verify-callback-token-test
  (let [secret "test-secret-key"
        msg-id "provider-msg-abc"]
    (testing "valid token verifies"
      (let [token (service/generate-callback-token secret msg-id)]
        (is (true? (service/verify-callback-token secret msg-id token)))))

    (testing "wrong token rejected"
      (is (false? (service/verify-callback-token secret msg-id "bad-token"))))

    (testing "wrong secret rejected"
      (let [token (service/generate-callback-token secret msg-id)]
        (is (false? (service/verify-callback-token "wrong-secret" msg-id token)))))

    (testing "wrong message-id rejected"
      (let [token (service/generate-callback-token secret msg-id)]
        (is (false? (service/verify-callback-token secret "wrong-msg" token)))))))
