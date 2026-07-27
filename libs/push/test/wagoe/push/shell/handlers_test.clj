(ns wagoe.push.shell.handlers-test
  (:require [clojure.test :refer [deftest use-fixtures is]]
            [wagoe.push.shell.handlers :as handlers]
            [wagoe.push.shell.persistence :as p]
            [wagoe.push.shell.persistence-test :as pt]
            [wagoe.push.shell.service :as service]
            [wagoe.push.ports :as ports]))

(def ^:private test-secret "test-callback-secret-32chars!!!")

(use-fixtures :each
  (fn [f]
    (binding [pt/*db* (pt/create-test-db)]
      (f))))

;; --- Device Handlers ---

(deftest ^:integration register-device-handler-test
  (let [deps    {:device-store (p/->DeviceTokenStore pt/*db*)}
        user-id (random-uuid)
        request {:identity    {:user-id user-id}
                 :body-params {:token "fcm-token-1" :platform :fcm :app-id "com.test"}}
        response (handlers/register-device-handler deps request)]
    (is (= 201 (:status response)))
    (is (string? (get-in response [:headers "Location"])))))

(deftest ^:integration register-device-invalid-body-test
  (let [deps    {:device-store (p/->DeviceTokenStore pt/*db*)}
        request {:identity    {:user-id (random-uuid)}
                 :body-params {:platform :fcm}}  ;; missing :token and :app-id
        response (handlers/register-device-handler deps request)]
    (is (= 400 (:status response)))))

(deftest ^:integration list-devices-handler-test
  (let [store   (p/->DeviceTokenStore pt/*db*)
        deps    {:device-store store}
        user-id (random-uuid)]
    (ports/register-device! store user-id {:token "t1" :platform :fcm :app-id "com.test"})
    (let [response (handlers/list-devices-handler deps {:identity {:user-id user-id}})]
      (is (= 200 (:status response)))
      (is (= 1 (count (get-in response [:body :devices])))))))

(deftest ^:integration unregister-device-handler-test
  (let [store   (p/->DeviceTokenStore pt/*db*)
        deps    {:device-store store}
        user-id (random-uuid)]
    (ports/register-device! store user-id {:token "to-delete" :platform :fcm :app-id "com.test"})
    (let [response (handlers/unregister-device-handler deps
                                                       {:identity    {:user-id user-id}
                                                        :path-params {:token "to-delete"}})]
      (is (= 204 (:status response)))
      (is (empty? (ports/get-user-devices store user-id))))))

;; --- Callback Handler ---

(deftest ^:integration callback-handler-valid-test
  (let [analytics (p/->PushAnalyticsStore pt/*db*)
        deps      {:analytics-store analytics :callback-secret test-secret}
        msg-id    "provider-msg-123"
        token     (service/generate-callback-token test-secret msg-id)
        request   {:body-params {:device-token        "device-abc"
                                 :provider-message-id msg-id
                                 :event-type          :delivered
                                 :callback-token      token
                                 :notification-id     :order-shipped
                                 :platform            :fcm}}
        response  (handlers/analytics-callback-handler deps request)]
    (is (= 204 (:status response)))
    (let [stats (ports/get-push-stats analytics :order-shipped {})]
      (is (= 1 (:delivered stats))))))

(deftest ^:integration callback-handler-invalid-hmac-test
  (let [deps     {:analytics-store (p/->PushAnalyticsStore pt/*db*)
                  :callback-secret test-secret}
        request  {:body-params {:device-token        "device-abc"
                                :provider-message-id "msg-1"
                                :event-type          :delivered
                                :callback-token      "bad-hmac"
                                :notification-id     :test
                                :platform            :fcm}}
        response (handlers/analytics-callback-handler deps request)]
    (is (= 403 (:status response)))))

(deftest ^:integration callback-handler-invalid-payload-test
  (let [deps     {:analytics-store (p/->PushAnalyticsStore pt/*db*)
                  :callback-secret test-secret}
        request  {:body-params {:bad "data"}}
        response (handlers/analytics-callback-handler deps request)]
    (is (= 400 (:status response)))))

;; --- Stats Handler ---

(deftest ^:integration push-stats-handler-test
  (let [analytics (p/->PushAnalyticsStore pt/*db*)
        deps      {:analytics-store analytics}]
    (ports/record-send! analytics
                        {:id (random-uuid) :notification-id :test-stats :device-token "t1"
                         :platform :fcm :event-type :sent :timestamp (java.util.Date.)})
    (ports/record-send! analytics
                        {:id (random-uuid) :notification-id :test-stats :device-token "t1"
                         :platform :fcm :event-type :sent :timestamp (java.util.Date.)})
    (let [response (handlers/push-stats-handler deps
                                                {:path-params {:notification-id "test-stats"}})]
      (is (= 200 (:status response)))
      (is (= 2 (get-in response [:body :sent]))))))
