(ns wagoe.push.core.device-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.push.core.device :as device]))

(deftest ^:unit detect-platform-test
  (testing "FCM tokens are long alphanumeric with colons"
    (is (= :fcm (device/detect-platform "dGVzdA:APA91bHnK..."))))
  (testing "APNs tokens are 64-char hex"
    (is (= :apns (device/detect-platform (apply str (repeat 64 "a"))))))
  (testing "unknown defaults to nil"
    (is (nil? (device/detect-platform "short")))))

(deftest ^:unit stale-token?-test
  (let [now (java.time.Instant/now)
        old (java.time.Instant/parse "2025-01-01T00:00:00Z")]
    (testing "token used recently is not stale"
      (is (not (device/stale-token? {:last-used-at now} 30 now))))
    (testing "token unused for > max-age is stale"
      (is (device/stale-token? {:last-used-at old} 30 now)))))

(deftest ^:unit prepare-device-record-test
  (let [id     (random-uuid)
        now    (java.util.Date.)
        record (device/prepare-device-record
                (random-uuid)
                {:token "abc" :platform :fcm :app-id "com.example"
                 :device-name "Pixel 8" :os-version "Android 15"}
                id now)]
    (is (= id (:id record)))
    (is (= "abc" (:token record)))
    (is (= :fcm (:platform record)))
    (is (true? (:active? record)))
    (is (= now (:created-at record)))))
