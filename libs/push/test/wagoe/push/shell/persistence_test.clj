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

(deftest ^:contract re-registration-moves-the-token-and-returns-the-stored-id
  ;; The conflict update kept the old user_id, and register returned a fresh id (BOU-683).
  (let [store  (p/->DeviceTokenStore *db*)
        alice  (random-uuid)
        bob    (random-uuid)
        device {:token "shared-token" :platform :fcm :app-id "com.example"}
        alices (ports/register-device! store alice device)
        bobs   (ports/register-device! store bob device)]
    (is (= (:id alices) (:id bobs)))
    (is (empty? (ports/get-user-devices store alice)))
    (is (= ["shared-token"] (map :token (ports/get-user-devices store bob))))))

(deftest ^:contract get-devices-by-platform-pages-by-id
  ;; The broadcast test deactivates every device it reads, so it passes with :after-id ignored.
  (let [store (p/->DeviceTokenStore *db*)]
    (doseq [i (range 5)]
      (ports/register-device! store (random-uuid)
                              {:token (str "fcm-" i) :platform :fcm :app-id "com.example"}))
    (let [page1 (ports/get-devices-by-platform store :fcm {:limit 2})
          page2 (ports/get-devices-by-platform store :fcm {:limit 2 :after-id (:id (peek page1))})
          page3 (ports/get-devices-by-platform store :fcm {:limit 2 :after-id (:id (peek page2))})
          ids   (map (comp str :id) (concat page1 page2 page3))]
      (is (= [2 2 1] (map count [page1 page2 page3])))
      (is (= (sort ids) ids) "ascending and without repeats across pages")
      (is (= 5 (count (set ids)))))))

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

(deftest ^:contract cleanup-deletes-only-what-is-older-than-the-cutoff
  ;; The cutoff was a java.sql.Timestamp, which SQLite compares as epoch millis
  ;; against the ISO text written there, so nothing was ever older.
  (let [store (p/->PushAnalyticsStore *db*)
        event (fn [days-ago]
                {:id (random-uuid) :notification-id :old-notif :device-token "t"
                 :platform :fcm :event-type :sent
                 :timestamp (java.util.Date/from (.minus (java.time.Instant/now)
                                                         (java.time.Duration/ofDays days-ago)))})]
    (ports/record-send! store (event 10))
    (ports/record-send! store (event 1))
    (ports/cleanup-old-events! store 5)
    (is (= 1 (:sent (ports/get-push-stats store :old-notif {}))))))
