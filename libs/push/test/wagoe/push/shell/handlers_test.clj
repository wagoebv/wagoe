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
                 :uri         "/api/v1/push/devices"
                 :body-params {:token "fcm:token-1" :platform :fcm :app-id "com.test"}}
        response (handlers/register-device-handler deps request)]
    (is (= 201 (:status response)))
    ;; The path GET and DELETE answer on, not the id nothing serves (BOU-845).
    (is (= "/api/v1/push/devices/fcm%3Atoken-1?app-id=com.test" (get-in response [:headers "Location"])))))

(deftest ^:integration a-token-with-a-slash-gets-no-location
  ;; Its %2F is refused by Jetty, so the Location would name nothing.
  (let [response (handlers/register-device-handler
                  {:device-store (p/->DeviceTokenStore pt/*db*)}
                  {:user        {:id (random-uuid)}
                   :uri         "/api/v1/push/devices"
                   :body-params {:token "a/b" :platform :fcm :app-id "com.test"}})]
    (is (= 201 (:status response)))
    (is (not (contains? (:headers response) "Location")))))

(deftest ^:integration register-device-reads-the-coerced-body
  (let [store   (p/->DeviceTokenStore pt/*db*)
        user-id (random-uuid)
        request {:user       {:id user-id}
                 :uri        "/api/v1/push/devices"
                 :parameters {:body {:token "coerced" :platform :fcm :app-id "com.test"}}}]
    (is (= 201 (:status (handlers/register-device-handler {:device-store store} request))))
    (is (= ["coerced"] (map :token (ports/get-user-devices store user-id))))))

(defn- scanning
  "`store` without IDeviceLookup, as a store written before it would be."
  [store]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ports/IDeviceTokenStore
    (get-user-devices [_ user-id] (ports/get-user-devices store user-id))))

(deftest ^:integration get-device-handler-test
  (let [store   (p/->DeviceTokenStore pt/*db*)
        user-id (random-uuid)]
    (ports/register-device! store user-id {:token "mine" :platform :fcm :app-id "com.test"})
    (ports/register-device! store user-id {:token "both" :platform :fcm :app-id "com.a"})
    (ports/register-device! store user-id {:token "both" :platform :fcm :app-id "com.b"})
    (doseq [[label s] [["lookup" store] ["scan" (scanning store)]]]
      (let [fetch (fn [user token & [app-id]]
                    (handlers/get-device-handler {:device-store s}
                                                 {:user         {:id user}
                                                  :path-params  {:token token}
                                                  :query-params (if app-id {"app-id" app-id} {})}))]
        (is (= "mine" (get-in (fetch user-id "mine") [:body :token])) label)
        (is (= 404 (:status (fetch user-id "missing"))) label)
        (is (= 404 (:status (fetch (random-uuid) "mine"))) (str label ": another user's device"))
        (is (= 404 (:status (fetch user-id "mine" "com.other"))) (str label ": another app-id"))
        (is (= 409 (:status (fetch user-id "both"))) (str label ": one token, two app-ids"))
        (is (= "com.b" (get-in (fetch user-id "both" "com.b") [:body :app-id])) label)))))

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
        token     #_{:clj-kondo/ignore [:deprecated-var]}
        (service/generate-callback-token test-secret msg-id)
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

(deftest ^:unit ^:security a-legacy-token-is-refused-when-configured
  (let [request {:body-params {:device-token        "device-abc"
                               :provider-message-id "msg-1"
                               :event-type          :delivered
                               :callback-token      #_{:clj-kondo/ignore [:deprecated-var]}
                               (service/generate-callback-token test-secret "msg-1")
                               :notification-id     :order-shipped
                               :platform            :fcm}}]
    (is (= 403 (:status (handlers/analytics-callback-handler
                         {:callback-secret test-secret :refuse-legacy-callback-tokens? true}
                         request))))))

(defn- bound-callback
  "A callback body for a bound token over `fields`, with `changes` applied."
  [fields changes]
  {:body-params (merge {:provider-message-id (:callback-id fields)
                        :notification-id     (:notification-id fields)
                        :device-token        (:device-token fields)
                        :platform            (:platform fields)
                        :event-type          :delivered
                        :callback-token      (service/callback-token test-secret fields)}
                       changes)})

(deftest ^:integration ^:security a-bound-token-counts-for-its-own-push-once
  ;; One token used to count for any notification and device, as often as
  ;; posted (BOU-849).
  (let [analytics (p/->PushAnalyticsStore pt/*db*)
        deps      {:analytics-store analytics :callback-secret test-secret}
        fields    {:callback-id "cb-1" :notification-id :order-shipped :device-token "device-a"
                   :platform :fcm :expires-at (+ (quot (System/currentTimeMillis) 1000) 600)}
        post      #(:status (handlers/analytics-callback-handler deps (bound-callback fields %)))]
    (is (= 403 (post {:notification-id :other-push})) "another notification")
    (is (= 403 (post {:device-token "device-b"})) "another device")
    (is (= 0 (:delivered (ports/get-push-stats analytics :other-push {}))))
    (is (= [204 204] [(post {}) (post {})]) "a replay is answered, not counted")
    (is (= 204 (post {:event-type :opened})))
    (is (= {:delivered 1 :opened 1}
           (select-keys (ports/get-push-stats analytics :order-shipped {}) [:delivered :opened])))))

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
  ;; A blank secret too: an empty HMAC key throws, which was a 500.
  (let [request {:body-params {:device-token        "device-abc"
                               :provider-message-id "msg-1"
                               :event-type          "delivered"
                               :callback-token      "any"
                               :notification-id     "test"
                               :platform            "fcm"}}]
    (doseq [deps [{} {:callback-secret ""}]]
      (is (= 403 (:status (handlers/analytics-callback-handler deps request))) (pr-str deps)))))

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
  (let [public (for [[path data] (handlers/api-routes {})
                     [method endpoint] data
                     :when (:public endpoint)]
                 [path method])]
    (is (= [["/push/callback" :post]] public))))

(deftest ^:unit every-route-has-an-authorization-rule
  ;; Each route says its own rule: the module tag would ask a device owner
  ;; for :push/read or :push/write, which no ordinary user holds.
  (is (= {["/push/devices" :get]                [:self []]
          ["/push/devices" :post]               [:self []]
          ["/push/devices/:token" :get]         [:self []]
          ["/push/devices/:token" :delete]      [:self []]
          ["/push/callback" :post]              [:public []]
          ["/push/stats/:notification-id" :get] [:permission [:push/read]]}
         (into {} (map (juxt (juxt :path :method) (juxt :rule :permissions)))
               (authorization/route-report (handlers/api-routes {}))))))

(deftest ^:unit the-deprecated-vector-keeps-its-paths
  (let [[root & children] (handlers/push-routes {})]
    (is (= "/api/push" root))
    (is (= ["/devices" "/devices/:token" "/callback" "/stats/:notification-id"]
           (map first children)))
    (is (not-any? :parameters (mapcat (comp (juxt :get :post :delete) second) children))
        "an app's own router may not coerce with malli")))
