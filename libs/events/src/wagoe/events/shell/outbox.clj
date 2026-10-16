(ns wagoe.events.shell.outbox
  "Transactional outbox: publish an event as part of a database transaction.

   `publish-in-tx!` writes the event to `event_outbox` on the caller's open
   transaction, so it commits or rolls back with the business change. A relay
   polls the table, publishes each pending row to the bus in order, and marks
   it delivered. A relay that dies between publish and mark publishes the row
   again: delivery is at-least-once, and consumers dedupe on `:id` (BOU-448).

   Every relay running against one database publishes every pending row, so
   two relays mean duplicates. Run it on one node and set `:relay? false` on
   the rest; idempotent consumers make duplicates harmless, not free.

   Times are epoch-millis BIGINTs and ids text, so the same SQL runs on H2,
   PostgreSQL, MySQL and SQLite without per-engine encoding."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.tools.logging :as log]
            [wagoe.events.core.event :as event]
            [wagoe.events.ports :as ports]
            [wagoe.events.shell.codec :as codec]
            [wagoe.platform.database :as db]
            [wagoe.platform.ports.database :as db-ports])
  (:import (java.util.concurrent Executors ScheduledExecutorService TimeUnit)
           (java.util.concurrent.atomic AtomicLong)))

(def defaults
  {:poll-ms    1000
   :batch-size 100
   :retain-ms  (* 24 60 60 1000)
   :relay?     true})

;; --- schema -----------------------------------------------------------------

(defn create-outbox-table!
  "Create `event_outbox` and its index if absent. Called at boot by the
   `:wagoe/events-outbox` component."
  [ds]
  (let [ctx (db/context-of ds)]
    (jdbc/execute! ds [(format "CREATE TABLE IF NOT EXISTS event_outbox (
                                  id           VARCHAR(36) PRIMARY KEY,
                                  seq          BIGINT NOT NULL,
                                  topic        VARCHAR(255) NOT NULL,
                                  envelope     %s NOT NULL,
                                  delivered_ms BIGINT NULL)"
                               (db-ports/column-type (:adapter ctx) :text))])
    (db/create-index-if-not-exists! ctx "ix_event_outbox_pending" :event_outbox
                                    [:delivered_ms :seq])))

;; --- write side -------------------------------------------------------------

(defonce ^:private last-seq (AtomicLong. 0))

(defn- next-seq
  "Microseconds, strictly increasing in this process, so two events written in
   one millisecond keep their order."
  []
  (let [now (* 1000 (System/currentTimeMillis))]
    (loop []
      (let [prev (.get ^AtomicLong last-seq)
            n    (max now (inc prev))]
        (if (.compareAndSet ^AtomicLong last-seq prev n) n (recur))))))

(defn- open-connection
  "The connection behind `tx` when it is an open transaction: a next.jdbc tx,
   or the platform's tx context. A datasource is refused because it would
   succeed — autocommitting the row and reopening the dual-write window."
  [tx]
  (let [conn (if (map? tx) (:datasource tx) tx)]
    (when (and (instance? java.sql.Connection conn)
               (not (.getAutoCommit ^java.sql.Connection conn)))
      conn)))

(defn- insert-row! [conn topic event]
  (jdbc/execute-one! conn ["INSERT INTO event_outbox (id, seq, topic, envelope) VALUES (?,?,?,?)"
                           (str (random-uuid)) (next-seq)
                           (if (keyword? topic) (subs (str topic) 1) (str topic))
                           (codec/encode {:topic topic :event event})]))

(defrecord EventOutbox [ds bus opts state]
  ports/ITransactionalPublisher
  (publish-in-tx! [_ tx topic event]
    (let [conn    (open-connection tx)
          problem (or (when-not conn "publish-in-tx! needs an open transaction, not a datasource")
                      (event/topic-problem topic)
                      (event/event-problem event))]
      (when problem
        (throw (ex-info problem {:type :validation-error :topic topic})))
      (insert-row! conn topic event)
      (:id event))))

(defn create-outbox
  "An outbox over datasource `ds` that relays to `bus`. `opts` as `defaults`."
  [ds bus & [opts]]
  (->EventOutbox ds bus (merge defaults opts) (atom {})))

;; --- relay ------------------------------------------------------------------

(defn- pending [ds limit]
  (jdbc/execute! ds ["SELECT id, envelope FROM event_outbox
                       WHERE delivered_ms IS NULL ORDER BY seq LIMIT ?" limit]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn- publish-row [bus {:keys [envelope]}]
  (try
    (let [{:keys [topic event]} (codec/decode envelope)]
      (ports/publish! bus topic event))
    (catch Exception e
      {:error {:type :events/publish-failed :message (ex-message e)}})))

(defn- mark-delivered! [ds id]
  (jdbc/execute-one! ds ["UPDATE event_outbox SET delivered_ms = ? WHERE id = ?"
                         (System/currentTimeMillis) id]))

(defn- purge-delivered! [ds retain-ms]
  (jdbc/execute-one! ds ["DELETE FROM event_outbox WHERE delivered_ms < ?"
                         (- (System/currentTimeMillis) retain-ms)]))

(defn relay-once!
  "Publish up to `:batch-size` pending rows in order and mark each delivered.
   Stops at the first failed publish, so a later event never overtakes an
   earlier one; the failed row is retried next pass. Returns the count
   delivered."
  [{:keys [ds bus opts]}]
  (let [{:keys [batch-size retain-ms]} opts
        delivered (reduce (fn [n row]
                            (let [result (publish-row bus row)]
                              (if (:error result)
                                (do (log/warn "outbox relay: publish failed, retrying next pass"
                                              {:id (:id row) :error (:error result)})
                                    (reduced n))
                                (do (mark-delivered! ds (:id row))
                                    (inc n)))))
                          0
                          (pending ds batch-size))]
    (purge-delivered! ds retain-ms)
    delivered))

(defn- relay-tick! [outbox]
  (try
    ;; A full batch means more may be waiting.
    (while (= (get-in outbox [:opts :batch-size]) (relay-once! outbox)))
    (catch Throwable t
      ;; Caught, or the scheduler cancels every later run.
      (log/error t "outbox relay pass failed"))))

(defn start-relay!
  "Poll every `:poll-ms`. Returns `outbox` with its executor."
  [outbox]
  (let [ex (Executors/newSingleThreadScheduledExecutor)
        ms (long (get-in outbox [:opts :poll-ms]))]
    (.scheduleWithFixedDelay ex ^Runnable #(relay-tick! outbox) ms ms TimeUnit/MILLISECONDS)
    (swap! (:state outbox) assoc :executor ex)
    outbox))

(defn stop-relay!
  "Stop polling. Rows not yet relayed stay pending for the next start."
  [outbox]
  (when-let [^ScheduledExecutorService ex (:executor @(:state outbox))]
    (.shutdown ex)
    (when-not (.awaitTermination ex 5 TimeUnit/SECONDS)
      (.shutdownNow ex))
    (swap! (:state outbox) dissoc :executor)))
