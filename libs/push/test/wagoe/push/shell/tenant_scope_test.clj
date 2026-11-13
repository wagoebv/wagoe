(ns wagoe.push.shell.tenant-scope-test
  "A tenant's send reaches only devices registered in that tenant (BOU-946)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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

(defn- register!
  "Register `token` for `user-id` through the HTTP handler, as a request in `tenant` makes it."
  [store user-id token tenant]
  (handlers/register-device-handler
   {:device-store store}
   {:user {:id user-id :tenant-id tenant} :tenant {:id tenant}
    :tenant-membership {:tenant-id tenant :role :member}
    ;; A tenant the body names is ignored
    :body-params {:token token :platform "fcm" :app-id "com.test" :tenant-id (str tenant-b)}}))

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
