(ns wagoe.push.shell.handlers-test
  (:require [clojure.test :refer [deftest use-fixtures is]]
            [wagoe.platform.core.authorization :as authorization]
            [wagoe.push.shell.handlers :as handlers]
            [wagoe.push.shell.persistence :as p]
            [wagoe.push.shell.persistence-test :as pt]
            [wagoe.push.shell.service :as service]
            [wagoe.push.ports :as ports]))

(def ^:private test-secret "test-callback-secret-32chars!!!")

(use-fixtures :each pt/with-test-db)

;; --- Device Handlers ---

(deftest ^:integration register-device-handler-test
  (let [deps    {:device-store (p/->DeviceTokenStore pt/*db*)}
        user-id (random-uuid)
        request {:user        {:id user-id}
                 :body-params {:token "fcm-token-1" :platform :fcm :app-id "com.test"}}
        response (handlers/register-device-handler deps request)]
    (is (= 201 (:status response)))
    (is (string? (get-in response [:headers "Location"])))))

(deftest ^:integration register-device-invalid-body-test
  (let [deps    {:device-store (p/->DeviceTokenStore pt/*db*)}
        request {:user        {:id (random-uuid)}
                 :body-params {:platform :fcm}}  ;; missing :token and :app-id
        response (handlers/register-device-handler deps request)]
    (is (= 400 (:status response)))))

(deftest ^:integration list-devices-handler-test
  (let [store   (p/->DeviceTokenStore pt/*db*)
        deps    {:device-store store}
        user-id (random-uuid)]
    (ports/register-device! store user-id {:token "t1" :platform :fcm :app-id "com.test"})
    (let [response (handlers/list-devices-handler deps {:user {:id user-id}})]
      (is (= 200 (:status response)))
      (is (= 1 (count (get-in response [:body :devices])))))))

(deftest ^:integration unregister-device-handler-test
  (let [store   (p/->DeviceTokenStore pt/*db*)
        deps    {:device-store store}
        user-id (random-uuid)]
    (ports/register-device! store user-id {:token "to-delete" :platform :fcm :app-id "com.test"})
    (let [response (handlers/unregister-device-handler deps
                                                       {:user        {:id user-id}
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

(deftest ^:unit callback-handler-without-a-secret-refuses-test
  (let [request  {:body-params {:device-token        "device-abc"
                                :provider-message-id "msg-1"
                                :event-type          "delivered"
                                :callback-token      "any"
                                :notification-id     "test"
                                :platform            "fcm"}}
        response (handlers/analytics-callback-handler {} request)]
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

;; --- Route data ---

(deftest ^:unit only-the-callback-is-public
  ;; The platform refuses every route that is not :public true. The callback is
  ;; posted by a device, and its HMAC is the credential (BOU-568).
  (let [public (for [[path data] (handlers/push-routes {})
                     [method endpoint] data
                     :when (:public endpoint)]
                 [path method])]
    (is (= [["/push/callback" :post]] public))))

(deftest ^:unit every-route-has-an-authorization-rule
  ;; Each route says its own rule: the module tag would ask a device owner
  ;; for :push/read or :push/write, which no ordinary user holds.
  (is (= {["/push/devices" :get]                [:self []]
          ["/push/devices" :post]               [:self []]
          ["/push/devices/:token" :delete]      [:self []]
          ["/push/callback" :post]              [:public []]
          ["/push/stats/:notification-id" :get] [:permission [:push/read]]}
         (into {} (map (juxt (juxt :path :method) (juxt :rule :permissions)))
               (authorization/route-report (handlers/push-routes {}))))))
