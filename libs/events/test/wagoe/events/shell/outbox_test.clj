(ns wagoe.events.shell.outbox-test
  "BOU-448: an event commits with the business change or not at all. Runs on
   the engine WAGOE_TEST_DB names (h2 by default; pg, mysql)."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [support.test-db :as test-db]
            [wagoe.events.ports :as ports]
            [wagoe.events.shell.module-wiring :as wiring]
            [wagoe.events.shell.outbox :as outbox]
            [wagoe.events.shell.publisher :as publisher]
            [wagoe.platform.database :as db]))

(defn- recording-bus
  "A bus that records what it is handed. `fail?` makes publish! return an error."
  [fail?]
  (let [seen (atom [])]
    {:seen seen
     :bus  (reify ports/IEventPublisher
             (publish! [_ topic event]
               (if @fail?
                 {:error {:type :events/unavailable}}
                 (do (swap! seen conj [topic event]) (:id event)))))}))

(defn- with-outbox [prefix f]
  (test-db/with-db prefix
    (fn [ctx]
      (let [ds   (:datasource ctx)
            fail (atom false)
            {:keys [seen bus]} (recording-bus fail)]
        (outbox/create-outbox-table! ds)
        (f {:ctx ctx :ds ds :seen seen :fail fail :bus bus
            :outbox (outbox/create-outbox ds bus {:retain-ms 60000})})))))

(defn- event [n] (publisher/build :order/placed :orders {:n n} {:tenant-id "t1"}))

(defn- pending-count [ds]
  (:n (jdbc/execute-one! ds ["SELECT COUNT(*) AS n FROM event_outbox WHERE delivered_ms IS NULL AND dead_ms IS NULL"]
                         {:builder-fn rs/as-unqualified-lower-maps})))

(deftest ^:integration committed-event-is-delivered-once
  (with-outbox "outbox_commit"
    (fn [{:keys [ds seen outbox]}]
      (let [e (event 1)]
        (is (= (:id e) (jdbc/with-transaction [tx ds]
                         (ports/publish-in-tx! outbox tx :orders/placed e))))
        (is (= 1 (outbox/relay-once! outbox)))
        (is (= 0 (outbox/relay-once! outbox)) "a delivered row is not relayed again")
        (is (= [[:orders/placed e]] @seen) "topic and envelope survive the table")
        (is (zero? (pending-count ds)))))))

(deftest ^:integration rolled-back-event-is-never-delivered
  (with-outbox "outbox_rollback"
    (fn [{:keys [ds seen outbox]}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (jdbc/with-transaction [tx ds]
                     (ports/publish-in-tx! outbox tx :orders (event 1))
                     (throw (ex-info "business change failed" {:type :conflict})))))
      (is (= 0 (outbox/relay-once! outbox)))
      (is (empty? @seen)))))

(deftest ^:integration relay-crash-before-mark-redelivers
  (with-outbox "outbox_crash"
    (fn [{:keys [ds seen outbox]}]
      (jdbc/with-transaction [tx ds]
        (ports/publish-in-tx! outbox tx :orders (event 1)))
      (with-redefs-fn {#'outbox/mark-delivered! (fn [& _] (throw (ex-info "relay died" {:type :internal-error})))}
        #(is (thrown? clojure.lang.ExceptionInfo (outbox/relay-once! outbox))))
      (is (= 1 (count @seen)) "published before the crash")
      (is (= 1 (outbox/relay-once! outbox)) "still pending, so published again")
      (is (= 2 (count @seen)))
      (is (apply = (map (comp :id second) @seen)) "same :id, so a consumer can dedupe")
      (is (= 0 (outbox/relay-once! outbox))))))

(deftest ^:integration failed-publish-keeps-order
  (with-outbox "outbox_order"
    (fn [{:keys [ds seen fail outbox]}]
      (jdbc/with-transaction [tx ds]
        (doseq [n (range 5)]
          (ports/publish-in-tx! outbox tx :orders (event n))))
      (reset! fail true)
      (is (= 0 (outbox/relay-once! outbox)))
      (is (= 5 (pending-count ds)))
      (reset! fail false)
      (is (= 5 (outbox/relay-once! outbox)))
      (is (= (range 5) (map (comp :n :payload second) @seen))))))

(deftest ^:integration platform-transaction-context-is-accepted
  (with-outbox "outbox_platform_tx"
    (fn [{:keys [ctx seen outbox]}]
      (db/with-transaction [tx ctx]
        (publisher/emit-in-tx! outbox tx :orders :order/placed :orders {:n 1}))
      (is (= 1 (outbox/relay-once! outbox)))
      (is (= {:n 1} (:payload (second (first @seen))))))))

(deftest ^:integration options-wrapped-transaction-is-accepted
  (with-outbox "outbox_options_tx"
    (fn [{:keys [ds seen outbox]}]
      ;; What jdbc/with-transaction+options binds: the tx wrapped in options.
      (jdbc/with-transaction [tx ds]
        (ports/publish-in-tx! outbox (jdbc/with-options tx {}) :orders (event 1)))
      (is (= 1 (outbox/relay-once! outbox)))
      (is (= 1 (count @seen))))))

(deftest ^:integration refuses-what-would-lose-the-event
  (with-outbox "outbox_refuse"
    (fn [{:keys [ds outbox]}]
      (testing "a datasource would autocommit the row outside the business change"
        (is (= :validation-error
               (try (ports/publish-in-tx! outbox ds :orders (event 1))
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
      (testing "a malformed event throws, rolling the transaction back"
        (is (thrown? clojure.lang.ExceptionInfo
                     (jdbc/with-transaction [tx ds]
                       (ports/publish-in-tx! outbox tx :orders {:no "envelope"})))))
      (is (zero? (pending-count ds))))))

(deftest ^:integration delivered-rows-are-purged-after-retention
  (with-outbox "outbox_purge"
    (fn [{:keys [ds outbox]}]
      (let [outbox (assoc-in outbox [:opts :retain-ms] -1000)]
        (jdbc/with-transaction [tx ds]
          (ports/publish-in-tx! outbox tx :orders (event 1)))
        (is (= 1 (outbox/relay-once! outbox)))
        (is (= 0 (:n (jdbc/execute-one! ds ["SELECT COUNT(*) AS n FROM event_outbox"]
                                        {:builder-fn rs/as-unqualified-lower-maps}))))))))

(deftest ^:integration one-relay-at-a-time
  (with-outbox "outbox_lease"
    (fn [{:keys [ds bus seen outbox]}]
      (let [other (outbox/create-outbox ds bus)]
        (is (= 0 (outbox/relay-once! outbox)) "takes the lease")
        (jdbc/with-transaction [tx ds]
          (ports/publish-in-tx! outbox tx :orders (event 1)))
        (is (= 0 (outbox/relay-once! other)) "lease held elsewhere")
        (is (= 1 (outbox/relay-once! outbox)))
        (outbox/stop-relay! (outbox/start-relay! outbox))
        (jdbc/with-transaction [tx ds]
          (ports/publish-in-tx! outbox tx :orders (event 2)))
        (is (= 1 (outbox/relay-once! other)) "released on stop")
        (is (= 2 (count @seen)))))))

(deftest ^:integration the-relay-delivers-on-its-own
  (with-outbox "outbox_relay"
    (fn [{:keys [ds seen outbox]}]
      (let [o (outbox/start-relay! (assoc-in outbox [:opts :poll-ms] 20))]
        (try
          (jdbc/with-transaction [tx ds]
            (ports/publish-in-tx! o tx :orders (event 1)))
          (loop [n 0]
            (when (and (empty? @seen) (< n 100))
              (Thread/sleep 20)
              (recur (inc n))))
          (is (= 1 (count @seen)))
          (finally (outbox/stop-relay! o)))))))

(deftest ^:unit outbox-component-only-when-configured
  (is (= #{:wagoe/events}
         (set (keys (:components (wiring/ig-config {:provider :memory} {}))))))
  (let [c (:components (wiring/ig-config {:provider :memory :outbox {:poll-ms 500}} {}))]
    (is (= 500 (get-in c [:wagoe/events-outbox :poll-ms])))
    (is (= (ig/ref :wagoe/events) (get-in c [:wagoe/events-outbox :bus])))
    (is (= (ig/ref :wagoe/db-context) (get-in c [:wagoe/events-outbox :db-ctx])))
    (is (= (ig/ref :wagoe/events-outbox) (get-in c [:wagoe/events-relay :outbox]))))
  (testing ":relay? false writes to the outbox without relaying"
    (is (= #{:wagoe/events :wagoe/events-outbox}
           (set (keys (:components (wiring/ig-config {:provider :memory :outbox {:relay? false}} {}))))))))

(deftest ^:integration purge-runs-at-most-once-per-interval
  (with-outbox "outbox_purge_interval"
    (fn [{:keys [ds]}]
      (let [purge! #'outbox/purge-delivered!
            at     (atom 0)]
        (is (some? (purge! ds at 1000)) "first pass purges")
        (is (nil? (purge! ds at 1000)) "the next within the interval does not")
        (reset! at (- (System/currentTimeMillis) 2000))
        (is (some? (purge! ds at 1000)))))))

(defn- publish-all! [ds outbox ns]
  (jdbc/with-transaction [tx ds]
    (doseq [n ns]
      (ports/publish-in-tx! outbox tx :orders (event n)))))

(defn- relayed-ns [seen] (map (comp :n :payload second) @seen))

(deftest ^:integration poison-row-is-dead-lettered-after-max-attempts
  (with-outbox "outbox_poison"
    (fn [{:keys [ds seen bus outbox]}]
      (let [picky  (reify ports/IEventPublisher
                     (publish! [_ topic e]
                       (if (zero? (:n (:payload e)))
                         {:error {:type :events/rejected}}
                         (ports/publish! bus topic e))))
            outbox (-> outbox (assoc :bus picky) (assoc-in [:opts :max-attempts] 3))]
        (publish-all! ds outbox (range 3))
        (is (= 0 (outbox/relay-once! outbox)) "attempt 1 stops the pass")
        (is (= 0 (outbox/relay-once! outbox)) "attempt 2 stops the pass")
        (is (= 2 (outbox/relay-once! outbox)) "attempt 3 dead-letters it; the rest flow")
        (is (= [1 2] (relayed-ns seen)))
        (is (zero? (pending-count ds)))
        (let [[dead :as all] (outbox/dead-letters outbox)]
          (is (= 1 (count all)))
          (is (= 3 (:attempts dead)))
          (is (re-find #"rejected" (:last-error dead)))
          (is (some? (:dead-ms dead))))))))

(deftest ^:integration undecodable-row-is-dead-lettered-at-once
  (with-outbox "outbox_undecodable"
    (fn [{:keys [ds seen outbox]}]
      (publish-all! ds outbox (range 2))
      (let [first-id (:id (jdbc/execute-one! ds ["SELECT id FROM event_outbox ORDER BY seq LIMIT 1"]
                                             {:builder-fn rs/as-unqualified-lower-maps}))]
        (jdbc/execute-one! ds ["UPDATE event_outbox SET envelope = 'not transit' WHERE id = ?" first-id]))
      (is (= 1 (outbox/relay-once! outbox)))
      (is (= [1] (relayed-ns seen)))
      (is (= [1] (map :attempts (outbox/dead-letters outbox)))))))

(deftest ^:integration requeued-dead-letter-is-delivered
  (with-outbox "outbox_requeue"
    (fn [{:keys [ds seen fail outbox]}]
      (let [outbox (assoc-in outbox [:opts :max-attempts] 1)]
        (publish-all! ds outbox [0])
        (reset! fail true)
        (is (= 0 (outbox/relay-once! outbox)))
        (let [ids (map :id (outbox/dead-letters outbox))]
          (is (= 1 (count ids)))
          (reset! fail false)
          (is (= 0 (outbox/relay-once! outbox)) "dead letters are not relayed")
          (is (= 1 (outbox/requeue! outbox ids)))
          (is (= 0 (outbox/requeue! outbox ids)) "only dead rows are requeued"))
        (is (empty? (outbox/dead-letters outbox)))
        (is (= 1 (outbox/relay-once! outbox)))
        (is (= [0] (relayed-ns seen)))))))

(deftest ^:integration lease-is-timed-by-the-database-clock
  (with-outbox "outbox_lease_clock"
    (fn [{:keys [ds bus outbox]}]
      (let [other (outbox/create-outbox ds bus)
            until #(:until_ms (jdbc/execute-one! ds ["SELECT until_ms FROM event_outbox_relay"]
                                                 {:builder-fn rs/as-unqualified-lower-maps}))]
        (is (= 0 (outbox/relay-once! outbox)) "takes the lease")
        (is (< (abs (- (until) (+ (System/currentTimeMillis) 30000))) 5000)
            "expiry is database now plus :lease-ms, in epoch millis")
        (publish-all! ds outbox [1])
        (testing "a replica whose clock runs an hour ahead cannot take a live lease"
          (with-redefs-fn {#'outbox/now-ms #(+ (System/currentTimeMillis) (* 60 60 1000))}
            #(is (= 0 (outbox/relay-once! other)))))
        (is (= 1 (outbox/relay-once! outbox)) "still held by its owner")))))
