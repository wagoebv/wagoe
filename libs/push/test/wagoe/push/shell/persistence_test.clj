(ns wagoe.push.shell.persistence-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [wagoe.push.shell.persistence :as p]
            [wagoe.push.ports :as ports]
            [next.jdbc :as jdbc]))

(def ^:dynamic *db* nil)

(defn create-test-db []
  (let [ds (jdbc/get-datasource {:dbtype "h2:mem" :dbname (str "push-test-" (random-uuid))})]
    (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS push_device_tokens (
                         id UUID PRIMARY KEY, user_id UUID NOT NULL, tenant_id UUID,
                         token VARCHAR(512) NOT NULL, platform VARCHAR(10) NOT NULL,
                         app_id VARCHAR(255) NOT NULL, device_name VARCHAR(255),
                         os_version VARCHAR(50), active BOOLEAN NOT NULL DEFAULT TRUE,
                         created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         last_used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         CONSTRAINT uq_push_device_token UNIQUE (token, app_id))"])
    (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS push_analytics_events (
                         id UUID PRIMARY KEY, notification_id VARCHAR(255) NOT NULL,
                         device_token VARCHAR(512) NOT NULL, platform VARCHAR(10) NOT NULL,
                         event_type VARCHAR(20) NOT NULL, user_id UUID,
                         provider_message_id VARCHAR(255), error_message TEXT,
                         timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         tenant_id UUID)"])
    ds))

(use-fixtures :each
  (fn [f]
    (binding [*db* (create-test-db)]
      (f))))

(deftest ^:contract device-token-register-and-retrieve
  (let [store   (p/->DeviceTokenStore *db*)
        user-id (random-uuid)]
    (ports/register-device! store user-id
                            {:token "fcm-token-123" :platform :fcm :app-id "com.example"})
    (let [devices (ports/get-user-devices store user-id)]
      (is (= 1 (count devices)))
      (is (= "fcm-token-123" (:token (first devices))))
      (is (= :fcm (:platform (first devices)))))))

(deftest ^:contract device-token-unregister
  (let [store   (p/->DeviceTokenStore *db*)
        user-id (random-uuid)]
    (ports/register-device! store user-id
                            {:token "token-to-remove" :platform :fcm :app-id "com.example"})
    (ports/unregister-device! store user-id "token-to-remove")
    (is (empty? (ports/get-user-devices store user-id)))))

(deftest ^:contract mark-token-invalid-filters-from-active
  (let [store   (p/->DeviceTokenStore *db*)
        user-id (random-uuid)]
    (ports/register-device! store user-id
                            {:token "valid-token" :platform :apns :app-id "com.example"})
    (ports/register-device! store user-id
                            {:token "bad-token" :platform :apns :app-id "com.example2"})
    (ports/mark-token-invalid! store "bad-token")
    (let [devices (ports/get-user-devices store user-id)]
      (is (= 1 (count devices)))
      (is (= "valid-token" (:token (first devices)))))))

(deftest ^:contract duplicate-token-upserts
  (let [store   (p/->DeviceTokenStore *db*)
        user-id (random-uuid)]
    (ports/register-device! store user-id
                            {:token "dup-token" :platform :fcm :app-id "com.example"})
    (ports/register-device! store user-id
                            {:token "dup-token" :platform :fcm :app-id "com.example"})
    (is (= 1 (count (ports/get-user-devices store user-id))))))

(deftest ^:contract get-devices-by-platform-with-pagination
  (let [store   (p/->DeviceTokenStore *db*)
        user-id (random-uuid)]
    (doseq [i (range 5)]
      (ports/register-device! store user-id
                              {:token (str "fcm-" i) :platform :fcm :app-id (str "app-" i)}))
    (let [page1 (ports/get-devices-by-platform store :fcm {:limit 2 :offset 0})
          page2 (ports/get-devices-by-platform store :fcm {:limit 2 :offset 2})]
      (is (= 2 (count page1)))
      (is (= 2 (count page2))))))

(deftest ^:contract analytics-record-and-stats
  (let [store (p/->PushAnalyticsStore *db*)]
    (ports/record-send! store
                        {:id (random-uuid) :notification-id :test-notif :device-token "t1"
                         :platform :fcm :event-type :sent :timestamp (java.util.Date.)})
    (ports/record-send! store
                        {:id (random-uuid) :notification-id :test-notif :device-token "t2"
                         :platform :fcm :event-type :sent :timestamp (java.util.Date.)})
    (ports/record-delivery! store
                            {:id (random-uuid) :notification-id :test-notif :device-token "t1"
                             :platform :fcm :event-type :delivered :timestamp (java.util.Date.)})
    (let [stats (ports/get-push-stats store :test-notif {})]
      (is (= 2 (:sent stats)))
      (is (= 1 (:delivered stats))))))
