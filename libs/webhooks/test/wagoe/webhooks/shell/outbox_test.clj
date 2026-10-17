(ns wagoe.webhooks.shell.outbox-test
  "An event published inside a transaction is delivered only once the
   transaction commits: the subscriber listens on the bus, which the outbox
   relay reaches after commit."
  (:require [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [support.test-db :as test-db]
            [wagoe.events.ports :as events]
            [wagoe.events.shell.adapters.in-memory :as bus]
            [wagoe.events.shell.outbox :as outbox]
            [wagoe.events.shell.publisher :as publisher]
            [wagoe.webhooks.shell.module-wiring]
            [wagoe.webhooks.shell.persistence :as persistence]
            [wagoe.webhooks.support :as support]
            [wagoe.webhooks.ports :as ports]))

(deftest ^:integration delivery-waits-for-the-outbox-commit
  (support/with-receiver (constantly {:status 200})
    (fn [{:keys [url seen]}]
      (test-db/with-db "webhooks_outbox"
        (fn [ctx]
          (let [ds  (:datasource ctx)
                b   (bus/create-in-memory-bus {})
                js  (support/job-system)
                svc (support/service (doto ctx persistence/create-tables!) js {})
                sub (ig/init-key :wagoe.webhooks/subscriber {:service svc :bus b :topics [:orders/placed]})
                o   (do (outbox/create-outbox-table! ds)
                        (outbox/create-outbox ds b {}))
                emit-in-tx! (fn [tx] (publisher/emit-in-tx! o tx :orders/placed :order/placed :orders {:n 1}))]
            (try
              (ports/create-subscription! svc {:url url :event-pattern "orders/*"})
              (try (jdbc/with-transaction [tx ds]
                     (emit-in-tx! tx)
                     (throw (ex-info "rolled back" {:type :conflict})))
                   (catch clojure.lang.ExceptionInfo _))
              (outbox/relay-once! o)
              (bus/drain! b :orders/placed)
              (support/run-jobs! svc js support/no-backoff)
              (is (empty? @seen) "a rolled-back event sends nothing")

              (let [id (jdbc/with-transaction [tx ds] (emit-in-tx! tx))]
                (support/run-jobs! svc js support/no-backoff)
                (is (empty? @seen) "nothing before the relay has published it")
                (outbox/relay-once! o)
                (bus/drain! b :orders/placed)
                (support/run-jobs! svc js support/no-backoff)
                (is (= [id] (mapv #(get-in % [:headers "wagoe-event-id"]) @seen))))
              (finally
                (ig/halt-key! :wagoe.webhooks/subscriber sub)
                (bus/stop! b)))))))))

(deftest ^:unit the-subscriber-unsubscribes-on-halt
  (let [b   (bus/create-in-memory-bus {})
        hit (atom 0)
        svc (reify ports/IWebhooks
              (create-subscription! [_ _] nil)
              (change-subscription! [_ _ _] nil)
              (remove-subscription! [_ _] nil)
              (subscriptions [_ _] nil)
              (dispatch! [_ _ _] (swap! hit inc))
              (deliver! [_ _] nil))
        sub (ig/init-key :wagoe.webhooks/subscriber {:service svc :bus b :topics [:a]})]
    (events/publish! b :a (publisher/build :x :y nil))
    (bus/drain! b :a)
    (ig/halt-key! :wagoe.webhooks/subscriber sub)
    (events/publish! b :a (publisher/build :x :y nil))
    (bus/drain! b :a)
    (bus/stop! b)
    (is (= 1 @hit))))
