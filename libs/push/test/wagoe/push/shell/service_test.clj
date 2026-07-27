(ns wagoe.push.shell.service-test
  (:require [clojure.test :refer [deftest use-fixtures is]]
            [wagoe.push.shell.service :as service]
            [wagoe.push.shell.adapters.mock :as mock]
            [wagoe.push.shell.persistence :as p]
            [wagoe.push.shell.persistence-test :as pt]
            [wagoe.push.core.notification :as notif]
            [wagoe.push.shell.registry :as registry]
            [wagoe.push.ports :as ports]
            [wagoe.jobs.ports]))

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (binding [pt/*db* (pt/create-test-db)]
      (f))))

(deftest ^:integration send-push-enqueues-job
  (registry/register-push!
   {:id :test-push :title "Hello" :body "World" :channels #{:fcm}})

  (let [jobs-atom    (atom [])
        ;; Partial mock — send-push! only enqueues, so the other IJobQueue
        ;; methods are intentionally unimplemented.
        mock-queue   #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify wagoe.jobs.ports/IJobQueue
          (enqueue-job! [_ queue-name job]
            (swap! jobs-atom conj {:queue queue-name :job job})
            (:id job)))
        device-store (p/->DeviceTokenStore pt/*db*)
        analytics    (p/->PushAnalyticsStore pt/*db*)
        svc          (service/->PushService
                      device-store analytics
                      (mock/->MockFCMProvider)
                      (mock/->MockAPNsProvider)
                      mock-queue
                      "test-callback-secret")]
    (ports/send-push! svc :test-push {:order-id "1"} {:user-id (random-uuid) :locale :en})
    (is (= 1 (count @jobs-atom)))
    (is (= :push/send (:job-type (:job (first @jobs-atom)))))))
