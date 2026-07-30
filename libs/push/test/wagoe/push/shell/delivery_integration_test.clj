(ns wagoe.push.shell.delivery-integration-test
  (:require [clojure.test :refer [deftest use-fixtures is]]
            [wagoe.push.shell.service :as service]
            [wagoe.push.shell.adapters.mock :as mock]
            [wagoe.push.shell.persistence :as p]
            [wagoe.push.shell.persistence-test :as pt]
            [wagoe.push.shell.jobs :as jobs]
            [wagoe.push.shell.registry :as registry]
            [wagoe.push.ports :as ports]))

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (binding [pt/*db* (pt/create-test-db)]
      (f))))

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
