(ns wagoe.push.core.schema-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.push.schema :as schema]
            [malli.core :as m]))

(deftest ^:unit push-definition-validation
  (testing "valid push definition accepted"
    (is (m/validate schema/PushDefinition
                    {:id :order-shipped
                     :title {:en "Shipped" :nl "Verzonden"}
                     :body {:en "Your order {{id}} shipped"}
                     :channels #{:fcm :apns}
                     :priority :high
                     :ttl 3600
                     :deep-link "/orders/{{id}}"
                     :silent? false
                     :collapse-key :order-status
                     :retry {:max-attempts 3 :backoff :exponential}})))

  (testing "plain string title accepted"
    (is (m/validate schema/PushDefinition
                    {:id :simple
                     :title "Hello"
                     :body "World"
                     :channels #{:fcm}})))

  (testing "invalid id rejected"
    (is (not (m/validate schema/PushDefinition
                         {:id "not-keyword"
                          :title "X"
                          :body "Y"
                          :channels #{:fcm}})))))

(deftest ^:unit device-info-validation
  (testing "valid device info"
    (is (m/validate schema/DeviceInfo
                    {:token "abc123" :platform :fcm :app-id "com.example"})))

  (testing "missing token rejected"
    (is (not (m/validate schema/DeviceInfo
                         {:platform :fcm :app-id "com.example"})))))

(deftest ^:unit callback-payload-validation
  (testing "valid callback"
    (is (m/validate schema/CallbackPayload
                    {:device-token "abc"
                     :provider-message-id "msg-1"
                     :event-type :delivered
                     :callback-token "hmac-sig"
                     :notification-id :order-shipped
                     :platform :fcm})))

  (testing "invalid event type rejected"
    (is (not (m/validate schema/CallbackPayload
                         {:device-token "abc"
                          :provider-message-id "msg-1"
                          :event-type :sent
                          :callback-token "x"
                          :notification-id :test
                          :platform :fcm})))))
