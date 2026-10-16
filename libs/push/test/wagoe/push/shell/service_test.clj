(ns wagoe.push.shell.service-test
  (:require [clojure.test :refer [deftest use-fixtures is]]
            [wagoe.push.shell.service :as service]
            [wagoe.push.shell.adapters.mock :as mock]
            [wagoe.push.shell.persistence :as p]
            [wagoe.push.shell.persistence-test :as pt]
            [wagoe.push.shell.registry :as registry]
            [wagoe.push.ports :as ports]
            [wagoe.jobs.ports]))

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (pt/with-test-db f)))

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
                      :default
                      "test-callback-secret")]
    (ports/send-push! svc :test-push {:order-id "1"} {:user-id (random-uuid) :locale :en})
    (is (= 1 (count @jobs-atom)))
    (is (= :push/send (:job-type (:job (first @jobs-atom)))))
    ;; The queue a worker actually polls, and a job the adapters can serialise:
    ;; push enqueued on :push, which no pool read, and hand-built job maps had
    ;; no :created-at or retry budget (BOU-418 review).
    (is (= :default (:queue (first @jobs-atom))))
    (let [job (:job (first @jobs-atom))]
      (is (some? (:created-at job)))
      (is (= 3 (:max-retries job)))
      (is (= 0 (:retry-count job))))))
