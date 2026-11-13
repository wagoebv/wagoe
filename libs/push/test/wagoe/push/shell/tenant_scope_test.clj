(ns wagoe.push.shell.tenant-scope-test
  "A tenant's send reaches only devices registered in that tenant (BOU-946)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.core.utils.type-conversion :as tc]
            [wagoe.jobs.ports]
            [wagoe.platform.database :as db]
            [wagoe.push.core.device]
            [wagoe.push.ports :as ports]
            [wagoe.push.shell.adapters.mock :as mock]
            [wagoe.push.shell.handlers :as handlers]
            [wagoe.push.shell.jobs :as jobs]
            [wagoe.push.shell.persistence :as p]
            [wagoe.push.shell.persistence-test :as pt]
            [wagoe.push.shell.registry :as registry]
            [wagoe.push.shell.service :as service]))

(def ^:private tenant-a #uuid "a0000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b #uuid "b0000000-0000-0000-0000-00000000000b")

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (registry/register-push! {:id :tenant-test :title "Hi" :body "There" :channels #{:fcm}})
    (pt/with-test-db f)))

(defn- in-tenant
  "A request by `user-id`, a member of `tenant`, running in it."
  [user-id tenant]
  {:user {:id user-id :tenant-id tenant} :tenant {:id tenant}
   :tenant-membership {:tenant-id tenant :role :member}})

(defn- register!
  "Register `token` for `user-id` through the HTTP handler, as a request in `tenant` makes it."
  ([store user-id token tenant] (register! store user-id token tenant "com.test"))
  ([store user-id token tenant app-id]
   (handlers/register-device-handler
    {:device-store store}
    ;; A tenant the body names is ignored
    (assoc (in-tenant user-id tenant)
           :body-params {:token token :platform "fcm" :app-id app-id :tenant-id (str tenant-b)}))))

(defn- delete!
  "DELETE `token` as `user-id` in `tenant`, through a store's routes' deps."
  [store user-id token tenant]
  (:status (handlers/unregister-device-handler (handlers/with-capabilities {:device-store store})
                                               (assoc (in-tenant user-id tenant) :path-params {:token token}))))

(defn- seed!
  "A registration of `token` in `tenant`, written as data stored before BOU-994
   could hold it: one token in two tenants."
  [user-id token tenant app-id]
  (db/execute-update! pt/*db* {:insert-into :push-device-tokens
                               :values [{:id (random-uuid) :user-id user-id :token token :platform "fcm"
                                         :app-id app-id :tenant-id tenant :active true
                                         :created-at (java.time.Instant/now) :last-used-at (java.time.Instant/now)}]}))

(defn- rows
  "Every registration of `token`, active or not, by tenant."
  [token]
  (->> (db/execute-query! pt/*db* {:select [:tenant-id] :from [:push-device-tokens] :where [:= :token token]})
       (map (comp tc/string->uuid :tenant-id))
       sort))

(defn- without-removal
  "`store` without IDeviceRemoval, as a custom store written before it."
  [store]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify
    ports/IDeviceTokenStore
    (get-user-devices [_ user-id] (ports/get-user-devices store user-id))
    (unregister-device! [_ user-id token] (ports/unregister-device! store user-id token))
    ports/IDeviceLookup
    (find-user-devices [_ user-id token app-id] (ports/find-user-devices store user-id token app-id))))

(defn- job-args
  "The args `send` puts on the job, as a request bound to `tenant` enqueues it."
  [send tenant]
  (let [enqueued (atom nil)
        queue    #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify wagoe.jobs.ports/IJobQueue
          (enqueue-job! [_ _ job] (reset! enqueued job) (:id job))
          (schedule-job! [_ _ job _] (reset! enqueued job) (:id job)))
        svc      (service/map->PushService {:job-queue queue :queue-name :push})]
    (binding [db/*tenant-id* tenant] (send svc))
    (:args @enqueued)))

(deftest ^:integration ^:security a-tenants-send-reaches-only-its-devices
  (let [store     (p/->DeviceTokenStore pt/*db*)
        analytics (p/->PushAnalyticsStore pt/*db*)
        user      (random-uuid)
        _         (register! store user "token-in-a" tenant-a)
        _         (register! store user "token-in-b" tenant-b)
        deps      {:device-store store :analytics-store analytics :callback-secret "s"
                   :fcm-provider (mock/->MockFCMProvider) :apns-provider (mock/->MockAPNsProvider)}
        sent      #(:sent (ports/get-push-stats analytics :tenant-test {}))]
    (testing "registration records the request's tenant, not the body's"
      (is (= #{tenant-a tenant-b} (set (map :tenant-id (ports/get-user-devices store user))))))
    (testing "a send enqueued in tenant A carries it, and reaches A's device only"
      (let [args (job-args #(ports/send-push! % :tenant-test {} {:user-id user}) tenant-a)]
        (is (= (str tenant-a) (:tenant-id args)))
        (is (:success? (jobs/handle-send-push deps args)) "nil read as a failure, and was sent again (BOU-987)")
        (is (= 1 (sent)))))
    (testing "a broadcast in tenant B reaches B's device only"
      (jobs/handle-broadcast deps (job-args #(ports/broadcast! % :tenant-test {} {:platform :fcm}) tenant-b))
      (is (= 2 (sent))))
    (testing "a tenant's stats count its own sends"
      (is (= 1 (:sent (ports/get-push-stats analytics :tenant-test {:tenant-id tenant-a}))))
      (is (= 1 (:sent (ports/get-push-stats analytics :tenant-test {:tenant-id tenant-b})))))
    (testing "another tenant's registration of a device is not found"
      (let [in-a {:user {:id user :tenant-id tenant-a} :tenant {:id tenant-a}
                  :tenant-membership {:tenant-id tenant-a :role :member}}]
        (is (= 200 (:status (handlers/get-device-handler {:device-store store} (assoc in-a :path-params {:token "token-in-a"})))))
        (is (= 404 (:status (handlers/get-device-handler {:device-store store} (assoc in-a :path-params {:token "token-in-b"})))))))))

(deftest ^:unit ^:security a-send-of-no-tenant-reaches-no-tenants-devices
  ;; It reached every tenant's: an event handler or job that lost its tenant
  ;; pushed to all of them (BOU-985)
  (let [devices [{:token "a" :tenant-id "t1"} {:token "b" :tenant-id "t2"} {:token "c"}]]
    (is (= ["a"] (map :token (wagoe.push.core.device/of-tenant "t1" devices))))
    (is (= ["c"] (map :token (wagoe.push.core.device/of-tenant nil devices))))
    (is (= ["a" "b" "c"] (map :token (wagoe.push.core.device/of-tenant nil true devices)))
        "the operator reaches every tenant only when asked")))

(deftest ^:unit ^:security all-tenants-is-honoured-only-outside-a-tenant
  ;; A tenant's code must not reach every tenant's devices by asking (BOU-985)
  (doseq [[label call] {"send-push!"     #(ports/send-push! %1 :tenant-test {} %2)
                        "schedule-push!" #(ports/schedule-push! %1 :tenant-test {} %2 (java.time.Instant/now))
                        "broadcast!"     #(ports/broadcast! %1 :tenant-test {} %2)}]
    (testing label
      (let [send #(job-args (fn [svc] (call svc (merge {:user-id (random-uuid) :platform :fcm} %1))) %2)]
        (is (true? (:all-tenants? (send {:all-tenants? true} nil))))
        (is (false? (:all-tenants? (send {:all-tenants? true} tenant-a))) "ignored inside a tenant")
        (is (false? (:all-tenants? (send {} nil))) "never by default")))))

(deftest ^:integration ^:security a-job-of-no-tenant-reaches-only-no-tenants-devices
  ;; A handler or job that lost its tenant pushed to every tenant's devices (BOU-985)
  (let [store     (p/->DeviceTokenStore pt/*db*)
        analytics (p/->PushAnalyticsStore pt/*db*)
        user      (random-uuid)
        _         (register! store user "token-in-a" tenant-a)
        _         (register! store user "token-in-b" tenant-b)
        _         (register! store user "token-in-none" nil)
        deps      {:device-store store :analytics-store analytics :callback-secret "s"
                   :fcm-provider (mock/->MockFCMProvider) :apns-provider (mock/->MockAPNsProvider)}
        sent      #(:sent (ports/get-push-stats analytics :tenant-test {}))]
    (is (= 3 (count (ports/get-user-devices store user))))
    (testing "a send of no tenant"
      (jobs/handle-send-push deps (job-args #(ports/send-push! % :tenant-test {} {:user-id user}) nil))
      (is (= 1 (sent))))
    (testing "with :all-tenants?"
      (jobs/handle-send-push deps (job-args #(ports/send-push! % :tenant-test {} {:user-id user :all-tenants? true}) nil))
      (is (= 4 (sent))))
    (testing "a broadcast of no tenant"
      (jobs/handle-broadcast deps (job-args #(ports/broadcast! % :tenant-test {} {:platform :fcm}) nil))
      (is (= 5 (sent))))
    (testing "a broadcast with :all-tenants?"
      (jobs/handle-broadcast deps (job-args #(ports/broadcast! % :tenant-test {} {:platform :fcm :all-tenants? true}) nil))
      (is (= 8 (sent))))))

(deftest ^:integration ^:security another-tenants-device-is-not-taken-over
  ;; A registration with the token moved B's device to A (BOU-990)
  (let [store (p/->DeviceTokenStore pt/*db*)
        alice (random-uuid)
        bob   (random-uuid)
        carol (random-uuid)]
    (is (= 201 (:status (register! store bob "bobs-phone" tenant-b))))
    (testing "a registration in another tenant is refused"
      (is (= 409 (:status (register! store alice "bobs-phone" tenant-a))))
      (is (= [tenant-b] (map :tenant-id (ports/get-user-devices store bob)))))
    (testing "in the same tenant the phone moves to its new user (BOU-683)"
      (is (= 201 (:status (register! store carol "bobs-phone" tenant-b))))
      (is (empty? (ports/get-user-devices store bob)))
      (is (= [tenant-b] (map :tenant-id (ports/get-user-devices store carol)))))
    (testing "in no tenant too, and a tenant does not take a phone of no tenant"
      (is (= 201 (:status (register! store alice "no-tenants" nil))))
      (is (= 201 (:status (register! store bob "no-tenants" nil))))
      (is (= [nil] (rows "no-tenants")))
      (is (= 409 (:status (register! store carol "no-tenants" tenant-b)))))
    (testing "nor under another app-id: a token is one device (BOU-994)"
      (is (= 409 (:status (register! store alice "bobs-phone" tenant-a "anything"))))
      (is (= [tenant-b] (rows "bobs-phone"))))
    (testing "one tenant may hold a token under several app-ids"
      (is (= 201 (:status (register! store carol "bobs-phone" tenant-b "com.other")))))))

(deftest ^:integration ^:security delete-removes-only-the-requests-tenants-device
  ;; DELETE removed the user's token in every tenant (BOU-990)
  (let [store (p/->DeviceTokenStore pt/*db*)
        user  (random-uuid)]
    (register! store user "phone" tenant-a "com.a")
    (seed! user "phone" tenant-b "com.b")
    (register! store user "old" tenant-a)
    (ports/mark-token-invalid! store "old")
    (is (= 204 (delete! store user "phone" tenant-a)))
    (is (= [tenant-b] (rows "phone")))
    (testing "an invalidated registration goes too"
      (is (= 204 (delete! store user "old" tenant-a)))
      (is (empty? (rows "old"))))))

(deftest ^:integration ^:security a-store-without-removal-deletes-only-what-stays-in-reach
  ;; Its unregister-device! removes the token in every tenant (BOU-990)
  (let [store (p/->DeviceTokenStore pt/*db*)
        older (without-removal store)
        user  (random-uuid)]
    (register! store user "both" tenant-a "com.a")
    (seed! user "both" tenant-b "com.b")
    (register! store user "in-b" tenant-b)
    (register! store user "in-a" tenant-a)
    (testing "the token in another tenant too: refused, both kept"
      (is (= 409 (delete! older user "both" tenant-a)))
      (is (= (sort [tenant-a tenant-b]) (rows "both"))))
    (testing "the token in another tenant only: nothing removed"
      (is (= 204 (delete! older user "in-b" tenant-a)))
      (is (= [tenant-b] (rows "in-b"))))
    (testing "the token in this tenant only: removed"
      (is (= 204 (delete! older user "in-a" tenant-a)))
      (is (empty? (rows "in-a"))))))

(deftest ^:integration ^:security a-callback-counts-in-the-tenant-of-its-send
  ;; The token's first row named the tenant, and it may be registered in two (BOU-990)
  (let [store     (p/->DeviceTokenStore pt/*db*)
        analytics (p/->PushAnalyticsStore pt/*db*)]
    (register! store (random-uuid) "phone" tenant-a "com.a")
    (seed! (random-uuid) "phone" tenant-b "com.b")
    (ports/record-send! analytics {:id (random-uuid) :notification-id :tenant-test :device-token "phone"
                                   :platform :fcm :event-type :sent :callback-id "cb-1" :tenant-id tenant-b})
    (ports/record-delivery! analytics {:id (random-uuid) :notification-id :tenant-test :device-token "phone"
                                       :platform :fcm :callback-id "cb-1"})
    (is (= 1 (:delivered (ports/get-push-stats analytics :tenant-test {:tenant-id tenant-b}))))
    (is (= 0 (:delivered (ports/get-push-stats analytics :tenant-test {:tenant-id tenant-a}))))
    (testing "without a recorded send, the token's tenant when it has one"
      (register! store (random-uuid) "only-in-a" tenant-a)
      (ports/record-open! analytics {:id (random-uuid) :notification-id :tenant-test :device-token "only-in-a"
                                     :platform :fcm :callback-id "cb-unsent"})
      (is (= 1 (:opened (ports/get-push-stats analytics :tenant-test {:tenant-id tenant-a})))))
    (testing "and no tenant's when the token is in several"
      (ports/record-open! analytics {:id (random-uuid) :notification-id :tenant-test :device-token "phone"
                                     :platform :fcm :provider-message-id "legacy"})
      (is (= 1 (:opened (ports/get-push-stats analytics :tenant-test {:tenant-id tenant-a}))))
      (is (= 0 (:opened (ports/get-push-stats analytics :tenant-test {:tenant-id tenant-b}))))
      (is (= 1 (:opened (ports/get-push-stats analytics :tenant-test {:tenant-id nil})))))))
