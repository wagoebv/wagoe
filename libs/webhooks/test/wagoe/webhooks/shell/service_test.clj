(ns wagoe.webhooks.shell.service-test
  (:require [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.events.shell.publisher :as publisher]
            [wagoe.jobs.ports :as jobs]
            [wagoe.webhooks :as webhooks]
            [wagoe.webhooks.ports :as ports]
            [wagoe.webhooks.shell.persistence :as persistence]
            [wagoe.webhooks.support :as support]))

(defn- with-service
  "Call `f` with a service, its job system and its store."
  ([f] (with-service {} f))
  ([settings f]
   (test-db/with-db "webhooks_svc"
     (fn [ctx]
       (persistence/create-tables! ctx)
       (let [js (support/job-system)]
         (f {:svc   (support/service ctx js settings)
             :js    js
             :store (persistence/create-store ctx)}))))))

(defn- event [tenant] (publisher/build :order/placed :orders {:n 1} {:tenant-id tenant}))

(defn- subscribe! [svc url & [extra]]
  (ports/create-subscription! svc (merge {:url url :event-pattern "orders/*"} extra)))

(deftest ^:integration a-delivery-is-signed-for-the-receiver
  (support/with-receiver (constantly {:status 200 :body "ok"})
    (fn [{:keys [url seen]}]
      (with-service
        (fn [{:keys [svc js store]}]
          (let [{:keys [secret subscription]} (subscribe! svc url)
                e (event nil)]
            (is (re-find #"^whsec_" secret))
            (is (not (contains? subscription :secret)) "the secret is returned once, beside the subscription")
            (is (re-find #"^enc:v1:" (:secret (ports/find-subscription store (:id subscription))))
                "and stored encrypted")
            (is (= 1 (ports/dispatch! svc :orders/placed e)))
            (support/run-jobs! svc js support/no-backoff)
            (let [[req & more] @seen]
              (is (empty? more))
              (is (= "POST" (:method req)))
              (is (= (:id e) (get-in req [:headers "wagoe-event-id"])))
              (is (webhooks/verify-signature secret (:body req) (get-in req [:headers "wagoe-signature"]))))
            (testing "the attempt is in the log"
              (let [[a] (ports/list-attempts store {:subscription-id (:id subscription)})]
                (is (= {:attempt 1 :status :delivered :status-code 200 :response "ok" :event-id (:id e)}
                       (select-keys a [:attempt :status :status-code :response :event-id])))
                (is (nat-int? (:latency-ms a)))))))))))

(deftest ^:integration failures-retry-then-dead-letter
  (support/with-receiver (constantly {:status 500 :body "down"})
    (fn [{:keys [url seen]}]
      (with-service {:max-attempts 3}
        (fn [{:keys [svc js store]}]
          (let [{:keys [subscription]} (subscribe! svc url)]
            (ports/dispatch! svc :orders/placed (event nil))
            (support/run-jobs! svc js support/no-backoff)
            (is (= 3 (count @seen)) "every attempt the queue allows")
            (is (= [:dead :failed :failed]
                   (mapv :status (ports/list-attempts store {:subscription-id (:id subscription)}))))
            (is (= 1 (count (jobs/failed-jobs (:store js) 10))) "and the job is dead-lettered")))))))

(deftest ^:integration a-retry-waits-for-its-backoff
  (support/with-receiver (constantly {:status 503})
    (fn [{:keys [url seen]}]
      (with-service
        (fn [{:keys [svc js]}]
          (subscribe! svc url)
          (ports/dispatch! svc :orders/placed (event nil))
          (support/run-jobs! svc js {:initial-delay-ms 60000 :jitter false})
          (is (= 1 (count @seen)) "the retry is scheduled a minute out, not run now")
          (is (empty? (jobs/failed-jobs (:store js) 10))))))))

(deftest ^:integration redirects-are-not-followed
  (support/with-receiver (fn [{:keys [path]}]
                           (if (= "/hook" path)
                             {:status 302 :headers {"Location" "/elsewhere"}}
                             {:status 200}))
    (fn [{:keys [url seen]}]
      (with-service {:max-attempts 1}
        (fn [{:keys [svc js store]}]
          (subscribe! svc url)
          (ports/dispatch! svc :orders/placed (event nil))
          (support/run-jobs! svc js support/no-backoff)
          (is (= ["/hook"] (mapv :path @seen)))
          (is (= [302] (mapv :status-code (ports/list-attempts store {})))))))))

(deftest ^:integration private-targets-are-refused
  (support/with-receiver (constantly {:status 200})
    (fn [{:keys [url seen]}]
      (with-service
        (fn [{:keys [svc js store]}]
          (testing "at create time"
            (with-service {:allowed-hosts #{}}
              (fn [{:keys [svc]}]
                (is (= :validation-error (get-in (subscribe! svc url) [:error :type]))))))
          (testing "and at delivery, when the allowlist no longer covers it"
            (let [{:keys [subscription]} (subscribe! svc url)
                  strict (support/service (:ctx store) js {:allowed-hosts #{}})]
              (ports/dispatch! strict :orders/placed (event nil))
              (support/run-jobs! strict js support/no-backoff)
              (is (empty? @seen) "no request is made")
              (is (= [:refused] (mapv :status (ports/list-attempts store {:subscription-id (:id subscription)}))))
              (is (empty? (jobs/failed-jobs (:store js) 10)) "and it is not retried"))))))))

(deftest ^:integration https-is-required-when-configured
  (with-service {:require-https? true}
    (fn [{:keys [svc]}]
      (is (= "URL must use https"
             (get-in (subscribe! svc "http://127.0.0.1/hook") [:error :message]))))))

(deftest ^:integration only-the-events-tenant-is-delivered
  (support/with-receiver (constantly {:status 200})
    (fn [{:keys [url seen]}]
      (with-service
        (fn [{:keys [svc js]}]
          (subscribe! svc (str url "?t1") {:tenant-id "t1"})
          (subscribe! svc (str url "?t2") {:tenant-id "t2"})
          (subscribe! svc (str url "?all"))
          (subscribe! svc (str url "?users") {:event-pattern "users/*"})
          (is (= 2 (ports/dispatch! svc :orders/placed (event "t1"))))
          (support/run-jobs! svc js support/no-backoff)
          (is (= 2 (count @seen))))))))

(deftest ^:integration a-redelivered-event-is-dispatched-once
  (support/with-receiver (constantly {:status 200})
    (fn [{:keys [url]}]
      (with-service
        (fn [{:keys [svc]}]
          (subscribe! svc url)
          (let [e (event nil)]
            (is (= 1 (ports/dispatch! svc :orders/placed e)))
            (is (= 0 (ports/dispatch! svc :orders/placed e)))))))))

(deftest ^:integration an-inactive-subscription-gets-nothing
  (support/with-receiver (constantly {:status 200})
    (fn [{:keys [url seen]}]
      (with-service
        (fn [{:keys [svc js]}]
          (let [{:keys [subscription]} (subscribe! svc url)]
            (ports/dispatch! svc :orders/placed (event nil))
            (is (false? (:active? (ports/change-subscription! svc (:id subscription) {:active? false}))))
            (support/run-jobs! svc js support/no-backoff)
            (is (empty? @seen) "a job enqueued before it was switched off is skipped")
            (is (= 0 (ports/dispatch! svc :orders/placed (event nil))))))))))
