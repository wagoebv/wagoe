(ns wagoe.push.shell.hmac-test
  "The deprecated legacy token is tested until it goes in 2.0."
  {:clj-kondo/config '{:linters {:deprecated-var {:level :off}}}}
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
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

(deftest ^:unit without-a-secret-a-push-carries-no-callback-token
  (let [notification {:title "Hi" :data {:k "v"}}]
    (doseq [secret [nil ""]]
      (is (= notification (service/with-callback notification :n secret)) (pr-str secret)))))

;; --- bound tokens (BOU-849) ---

(def ^:private secret "test-callback-secret-32chars!!!")

(def ^:private now 1800000000)

(def ^:private fields
  {:callback-id "cb-1" :notification-id :orders/shipped :device-token "device-a"
   :platform :fcm :expires-at (+ now 60)})

(defn- payload
  "A callback as the device posts it for `fields`, with `changes` applied."
  [changes]
  (merge {:provider-message-id (:callback-id fields)
          :notification-id     (:notification-id fields)
          :device-token        (:device-token fields)
          :platform            (:platform fields)
          :event-type          :delivered
          :callback-token      (service/callback-token secret fields)}
         changes))

(deftest ^:unit a-bound-token-verifies-for-its-own-push-only
  (is (= :bound (service/callback-verdict secret (payload {}) now)))
  (is (= :bound (service/callback-verdict secret (payload {:event-type :opened}) now))
      "one token reports delivery and the open")
  (doseq [[what changes] {"another notification" {:notification-id :orders/cancelled}
                          "another device"       {:device-token "device-b"}
                          "another platform"     {:platform :apns}
                          "another id"           {:provider-message-id "cb-2"}}]
    (is (nil? (service/callback-verdict secret (payload changes) now)) what))
  (is (nil? (service/callback-verdict "another-secret" (payload {}) now)))
  (is (nil? (service/callback-verdict nil (payload {}) now))))

(deftest ^:unit a-bound-token-expires
  (is (= :bound (service/callback-verdict secret (payload {}) (+ now 59))))
  (is (nil? (service/callback-verdict secret (payload {}) (+ now 60)))))

(deftest ^:unit a-forged-expiry-fails
  (let [token     (service/callback-token secret fields)
        postponed (str/replace token (str "v2." (+ now 60) ".") (str "v2." (+ now 99999) "."))]
    (is (nil? (service/callback-verdict secret (payload {:callback-token postponed}) (+ now 120))))))

(deftest ^:unit a-legacy-token-still-verifies-and-says-so
  (let [legacy (service/generate-callback-token secret "cb-1")]
    (is (= :legacy (service/callback-verdict secret (payload {:callback-token legacy}) now))))
  (testing "a bound token's MAC is no legacy token"
    (let [mac (peek (str/split (service/callback-token secret fields) #"\."))]
      (is (nil? (service/callback-verdict secret (payload {:callback-token mac}) now))))))

(deftest ^:unit with-callback-binds-the-token-to-the-device
  (let [n  {:title "Hi" :data {:k "v"}}
        a  (:data (service/with-callback n :orders/shipped secret {:token "device-a" :platform :fcm}))
        b  (:data (service/with-callback n :orders/shipped secret {:token "device-b" :platform :fcm}))
        at (fn [data device] (service/callback-verdict
                              secret
                              {:provider-message-id (:provider-message-id data)
                               :callback-token      (:callback-token data)
                               :notification-id     (keyword (:notification-id data))
                               :device-token        device
                               :platform            :fcm}
                              (quot (System/currentTimeMillis) 1000)))]
    (is (= "v" (:k a)))
    (is (not= (:callback-token a) (:callback-token b)) "each device its own token")
    (is (not= (:provider-message-id a) (:provider-message-id b)))
    (is (= :bound (at a "device-a")))
    (is (nil? (at a "device-b")))))
