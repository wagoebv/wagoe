(ns wagoe.push.shell.persistence-test
  "On the engine WAGOE_TEST_DB names, with push's shipped migrations."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [support.test-db :as test-db]
            [wagoe.platform.shell.database.migrations :as migrations]
            [wagoe.push.shell.persistence :as p]
            [wagoe.push.ports :as ports]))

(def ^:dynamic *db* nil)

(defn with-test-db
  "Call `f` with `*db*` bound to a migrated db-context."
  [f]
  (test-db/with-db "push"
    (fn [ctx]
      (migratus/migrate (migrations/migratus-config (:datasource ctx) ["wagoe/push/migrations/"]))
      (binding [*db* ctx]
        (f)))))

(use-fixtures :each with-test-db)

(deftest ^:contract a-device-id-is-stored-as-its-uuid
  ;; MySQL stored a raw UUID parameter Java-serialised (BOU-657).
  (let [store  (p/->DeviceTokenStore *db*)
        record (ports/register-device! store (random-uuid)
                                       {:token "uuid-token" :platform :fcm :app-id "com.example"})]
    (is (= (str (:id record))
           (str (:id (jdbc/execute-one! (:datasource *db*)
                                        ["SELECT id FROM push_device_tokens WHERE token = 'uuid-token'"]
                                        {:builder-fn rs/as-unqualified-lower-maps})))))))

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
