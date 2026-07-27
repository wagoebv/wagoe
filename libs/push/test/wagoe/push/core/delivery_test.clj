(ns wagoe.push.core.delivery-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.push.core.delivery :as delivery]))

(def sample-notification
  {:title "Order Shipped"
   :body "Your order ORD-42 is on its way!"
   :deep-link "/orders/ORD-42"
   :priority :high
   :ttl 3600
   :silent? false
   :collapse-key :order-status
   :data {:order-id "ORD-42"}})

(deftest ^:unit build-fcm-payload-test
  (let [payload (delivery/build-fcm-payload sample-notification "fcm-token-abc")]
    (is (= "fcm-token-abc" (get-in payload [:message :token])))
    (is (= "Order Shipped" (get-in payload [:message :notification :title])))
    (is (= "Your order ORD-42 is on its way!" (get-in payload [:message :notification :body])))
    (is (= "high" (get-in payload [:message :android :priority])))
    (is (= "3600s" (get-in payload [:message :android :ttl])))
    (is (= "order-status" (get-in payload [:message :android :collapse_key])))))

(deftest ^:unit build-apns-payload-test
  (let [payload (delivery/build-apns-payload sample-notification)]
    (is (= "Order Shipped" (get-in payload [:aps :alert :title])))
    (is (= "Your order ORD-42 is on its way!" (get-in payload [:aps :alert :body])))
    (is (= "default" (get-in payload [:aps :sound])))
    (is (= 0 (get-in payload [:aps :content-available])))
    (is (= "/orders/ORD-42" (:deep-link payload)))))

(deftest ^:unit build-apns-silent-payload-test
  (let [silent (assoc sample-notification :silent? true)
        payload (delivery/build-apns-payload silent)]
    (is (nil? (get-in payload [:aps :sound])))
    (is (= 1 (get-in payload [:aps :content-available])))))

(deftest ^:unit classify-error-test
  (testing "FCM errors"
    (is (= :retryable (delivery/classify-error :fcm "UNAVAILABLE")))
    (is (= :retryable (delivery/classify-error :fcm "INTERNAL")))
    (is (= :token-invalid (delivery/classify-error :fcm "UNREGISTERED")))
    (is (= :token-invalid (delivery/classify-error :fcm "INVALID_ARGUMENT")))
    (is (= :rate-limited (delivery/classify-error :fcm "QUOTA_EXCEEDED")))
    (is (= :permanent (delivery/classify-error :fcm "PERMISSION_DENIED")))
    (is (= :permanent (delivery/classify-error :fcm "SENDER_ID_MISMATCH"))))
  (testing "APNs errors"
    (is (= :retryable (delivery/classify-error :apns "ServiceUnavailable")))
    (is (= :token-invalid (delivery/classify-error :apns "BadDeviceToken")))
    (is (= :token-invalid (delivery/classify-error :apns "Unregistered")))
    (is (= :rate-limited (delivery/classify-error :apns "TooManyRequests")))
    (is (= :permanent (delivery/classify-error :apns "BadCertificate")))
    (is (= :permanent (delivery/classify-error :apns "Forbidden"))))
  (testing "unknown error defaults to :retryable"
    (is (= :retryable (delivery/classify-error :fcm "SOMETHING_NEW")))))

(deftest ^:unit retry-delay-ms-test
  (testing "exponential backoff"
    (is (= 1000 (delivery/retry-delay-ms {:backoff :exponential} 0)))
    (is (= 2000 (delivery/retry-delay-ms {:backoff :exponential} 1)))
    (is (= 4000 (delivery/retry-delay-ms {:backoff :exponential} 2))))
  (testing "linear backoff"
    (is (= 0 (delivery/retry-delay-ms {:backoff :linear} 0)))
    (is (= 1000 (delivery/retry-delay-ms {:backoff :linear} 1))))
  (testing "fixed backoff"
    (is (= 2000 (delivery/retry-delay-ms {:backoff :fixed} 0)))
    (is (= 2000 (delivery/retry-delay-ms {:backoff :fixed} 3)))))

(deftest ^:unit group-devices-by-platform-test
  (let [devices [{:token "a" :platform :fcm}
                 {:token "b" :platform :apns}
                 {:token "c" :platform :fcm}]]
    (is (= {:fcm [{:token "a" :platform :fcm} {:token "c" :platform :fcm}]
            :apns [{:token "b" :platform :apns}]}
           (delivery/group-devices-by-platform devices)))))

(deftest ^:unit stringify-values-test
  (is (= {"order-id" "ORD-42" "count" "3"}
         (delivery/stringify-values {:order-id "ORD-42" :count 3}))))
