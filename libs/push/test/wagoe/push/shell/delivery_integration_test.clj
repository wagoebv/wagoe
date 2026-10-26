(ns wagoe.push.shell.delivery-integration-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest use-fixtures is]]
            [clojure.tools.logging.test :as log-test]
            [wagoe.push.shell.service :as service]
            [wagoe.push.shell.adapters.mock :as mock]
            [wagoe.push.shell.persistence :as p]
            [wagoe.push.shell.persistence-test :as pt]
            [wagoe.push.shell.handlers :as handlers]
            [wagoe.push.shell.jobs :as jobs]
            [wagoe.push.shell.registry :as registry]
            [wagoe.push.ports :as ports]))

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (pt/with-test-db f)))

;; --- deliver-to-platform! ---

(deftest ^:integration deliver-to-platform-fcm-test
  (let [mock-fcm (mock/->MockFCMProvider)
        deps     {:fcm-provider mock-fcm :apns-provider (mock/->MockAPNsProvider)}
        notif    {:title "Test" :body "Body" :priority :normal :ttl 3600
                  :silent? false :data {:k "v"}}
        devices  [{:token "fcm-1" :platform :fcm} {:token "fcm-2" :platform :fcm}]
        results  (service/deliver-to-platform! deps :fcm notif devices)]
    (is (= 2 (count results)))
    (is (every? :success? results))
    (is (every? #(= :fcm (:platform %)) results))))

(deftest ^:integration deliver-to-platform-apns-test
  (let [mock-apns (mock/->MockAPNsProvider)
        deps      {:fcm-provider (mock/->MockFCMProvider) :apns-provider mock-apns}
        notif     {:title "Test" :body "Body" :priority :normal :ttl 3600
                   :silent? false :data {:k "v"}}
        devices   [{:token "apns-1" :platform :apns}]
        results   (service/deliver-to-platform! deps :apns notif devices)]
    (is (= 1 (count results)))
    (is (true? (:success? (first results))))
    (is (= :apns (:platform (first results))))))

(deftest ^:integration deliver-to-platform-empty-devices-test
  (let [deps    {:fcm-provider (mock/->MockFCMProvider) :apns-provider (mock/->MockAPNsProvider)}
        notif   {:title "Test" :body "Body" :priority :normal :ttl 3600
                 :silent? false :data {:k "v"}}
        results (service/deliver-to-platform! deps :fcm notif [])]
    (is (= [] results))))

;; --- handle-send-push job handler ---

(deftest ^:integration handle-send-push-delivers-to-devices
  (registry/register-push!
   {:id :job-test :title "Hello" :body "World" :channels #{:fcm :apns}})

  (let [device-store    (p/->DeviceTokenStore pt/*db*)
        analytics-store (p/->PushAnalyticsStore pt/*db*)
        user-id         (random-uuid)
        _               (ports/register-device! device-store user-id
                                                {:token "fcm-job-token" :platform :fcm :app-id "com.test"})
        _               (ports/register-device! device-store user-id
                                                {:token (apply str (repeat 64 "a")) :platform :apns :app-id "com.test2"})
        deps            {:device-store    device-store
                         :analytics-store analytics-store
                         :fcm-provider    (mock/->MockFCMProvider)
                         :apns-provider   (mock/->MockAPNsProvider)
                         :callback-secret "test-secret"}]
    (jobs/handle-send-push deps
                           {:notification-id :job-test :data {:k "v"} :user-id user-id :locale :en})
    ;; Should have analytics events for both devices
    (let [stats (ports/get-push-stats analytics-store :job-test {})]
      (is (= 2 (:sent stats))))))

(deftest ^:integration handle-send-push-unknown-notification-throws
  (let [deps {:device-store    (p/->DeviceTokenStore pt/*db*)
              :analytics-store (p/->PushAnalyticsStore pt/*db*)
              :fcm-provider    (mock/->MockFCMProvider)
              :apns-provider   (mock/->MockAPNsProvider)
              :callback-secret "secret"}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (jobs/handle-send-push deps
                                        {:notification-id :nonexistent :data {} :user-id (random-uuid)})))))

;; --- handle-broadcast job handler ---

(deftest ^:integration handle-broadcast-delivers-paginated
  (registry/register-push!
   {:id :broadcast-test :title "Alert" :body "Msg" :channels #{:fcm}})

  (let [device-store    (p/->DeviceTokenStore pt/*db*)
        analytics-store (p/->PushAnalyticsStore pt/*db*)
        ;; Register 3 devices
        _  (doseq [i (range 3)]
             (ports/register-device! device-store (random-uuid)
                                     {:token (str "bcast-" i) :platform :fcm :app-id (str "app-" i)}))
        deps {:device-store    device-store
              :analytics-store analytics-store
              :fcm-provider    (mock/->MockFCMProvider)
              :apns-provider   (mock/->MockAPNsProvider)
              :callback-secret "secret"}]
    (jobs/handle-broadcast deps
                           {:notification-id :broadcast-test :data {} :platform :fcm :locale :en})
    (let [stats (ports/get-push-stats analytics-store :broadcast-test {})]
      (is (= 3 (:sent stats))))))

(deftest ^:integration handle-broadcast-reaches-every-device-when-tokens-turn-out-invalid
  ;; Offset paging over a table the broadcast deactivates skipped devices (BOU-683).
  (registry/register-push! {:id :bcast-invalid :title "Alert" :body "Msg" :channels #{:fcm}})
  (let [device-store    (p/->DeviceTokenStore pt/*db*)
        analytics-store (p/->PushAnalyticsStore pt/*db*)
        rejecting-fcm   (reify ports/IFCMProvider
                          (fcm-send! [_ _])
                          (fcm-validate-token [_ _])
                          (fcm-send-multicast! [_ _ tokens]
                            (mapv (fn [t] {:success? false :token-invalid? true
                                           :device-token t :platform :fcm})
                                  tokens)))]
    (doseq [i (range 5)]
      (ports/register-device! device-store (random-uuid)
                              {:token (str "bcast-" i) :platform :fcm :app-id "com.test"}))
    (with-redefs [jobs/broadcast-page-size 2]
      (jobs/handle-broadcast {:device-store    device-store
                              :analytics-store analytics-store
                              :fcm-provider    rejecting-fcm}
                             {:notification-id :bcast-invalid :data {} :platform :fcm :locale :en}))
    (let [stats (ports/get-push-stats analytics-store :bcast-invalid {})]
      (is (= 5 (:failed stats))))))

(deftest ^:integration ^:security handle-send-push-does-not-log-device-tokens
  ;; BOU-556: a device token plus the provider credentials is enough to push to it.
  (registry/register-push! {:id :token-log-test :title "Hi" :body "There" :channels #{:fcm}})
  (let [device-store (p/->DeviceTokenStore pt/*db*)
        user-id      (random-uuid)
        token        "fcm-secret:TOKENBODY-1234567890"
        _            (ports/register-device! device-store user-id
                                             {:token token :platform :fcm :app-id "com.test"})
        invalid      (reify ports/IFCMProvider
                       (fcm-send! [_ _] {:success? false :device-token token :platform :fcm
                                         :token-invalid? true})
                       (fcm-send-multicast! [_ _ tokens]
                         (mapv (fn [t] {:success? false :device-token t :platform :fcm
                                        :token-invalid? true}) tokens))
                       (fcm-validate-token [_ t] {:valid? false :token t}))]
    (log-test/with-log
      (jobs/handle-send-push {:device-store    device-store
                              :analytics-store (p/->PushAnalyticsStore pt/*db*)
                              :fcm-provider    invalid
                              :apns-provider   (mock/->MockAPNsProvider)
                              :callback-secret "s"}
                             {:notification-id :token-log-test :data {} :user-id user-id :locale :en})
      (is (log-test/logged? 'wagoe.push.shell.jobs :info #"marking invalid token fcm-se…"))
      (is (not-any? #(str/includes? (str (:message %)) "TOKENBODY") (log-test/the-log))))))

;; --- callback round trip ---

(deftest ^:integration a-sent-push-carries-the-token-its-callback-needs
  ;; Nothing called generate-callback-token, so every callback was refused (BOU-844).
  (registry/register-push! {:id :orders/shipped :title "Hi" :body "There" :channels #{:fcm :apns}})
  (let [device-store    (p/->DeviceTokenStore pt/*db*)
        analytics-store (p/->PushAnalyticsStore pt/*db*)
        user-id         (random-uuid)
        apns-token      (apply str (repeat 64 "b"))
        sent            (atom {})
        fcm             (reify ports/IFCMProvider
                          (fcm-send! [_ _])
                          (fcm-validate-token [_ _])
                          (fcm-send-multicast! [_ payload tokens]
                            (swap! sent assoc :fcm (get-in payload [:message :data]))
                            (ports/fcm-send-multicast! (mock/->MockFCMProvider) payload tokens)))
        apns            (reify ports/IAPNsProvider
                          (apns-send! [_ _ _])
                          (apns-send-batch! [_ payload tokens]
                            (swap! sent assoc :apns (:data payload))
                            (ports/apns-send-batch! (mock/->MockAPNsProvider) payload tokens)))
        secret          "test-callback-secret-32chars!!!"]
    (ports/register-device! device-store user-id {:token "fcm-cb" :platform :fcm :app-id "com.test"})
    (ports/register-device! device-store user-id {:token apns-token :platform :apns :app-id "com.test"})
    (jobs/handle-send-push {:device-store    device-store
                            :analytics-store analytics-store
                            :fcm-provider    fcm
                            :apns-provider   apns
                            :callback-secret secret}
                           {:notification-id :orders/shipped :data {:k "v"} :user-id user-id :locale :en})
    ;; FCM data travels as strings, APNs data as JSON: both arrive as strings.
    (doseq [[platform device-token data] [[:fcm "fcm-cb" (update-keys (:fcm @sent) keyword)]
                                          [:apns apns-token (:apns @sent)]]]
      (is (= "v" (:k data)))
      (is (= 204 (:status (handlers/analytics-callback-handler
                           {:analytics-store analytics-store :callback-secret secret}
                           {:body-params {:device-token        device-token
                                          :provider-message-id (:provider-message-id data)
                                          :callback-token      (:callback-token data)
                                          :notification-id     (:notification-id data)
                                          :event-type          "delivered"
                                          :platform            (name platform)}})))
          (name platform)))
    (is (= {:sent 2 :delivered 2}
           (select-keys (ports/get-push-stats analytics-store :orders/shipped {}) [:sent :delivered])))))
