(ns wagoe.events.shell.outbox
  "Transactional outbox: publish an event as part of a database transaction.

   `publish-in-tx!` writes the event to `event_outbox` on the caller's open
   transaction, so it commits or rolls back with the business change. A relay
   polls the table, publishes each pending row to the bus in order, and marks
   it delivered. A relay that dies between publish and mark publishes the row
   again: delivery is at-least-once, and consumers dedupe on `:id` (BOU-448).

   Relays on several replicas take turns through a lease row in
   `event_outbox_relay`, so one publishes at a time and order holds.

   Times are epoch-millis BIGINTs and ids text, so the same SQL runs on H2,
   PostgreSQL, MySQL and SQLite without per-engine encoding."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
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
   :lease-ms   30000
   :relay?     true})

;; --- schema -----------------------------------------------------------------

(defn- seed-relay-lease! [ds]
  (when-not (jdbc/execute-one! ds ["SELECT name FROM event_outbox_relay WHERE name = 'relay'"])
    (try
      (jdbc/execute-one! ds ["INSERT INTO event_outbox_relay (name, owner, until_ms) VALUES ('relay', NULL, 0)"])
      ;; Another replica seeded it first.
      (catch java.sql.SQLException _))))

(defn create-outbox-table!
  "Create `event_outbox`, its index and the relay lease row if absent. Called
   at boot by the `:wagoe/events-outbox` component."
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
                                    [:delivered_ms :seq])
    (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS event_outbox_relay (
                          name     VARCHAR(64) PRIMARY KEY,
                          owner    VARCHAR(36) NULL,
                          until_ms BIGINT NOT NULL)"])
    (seed-relay-lease! ds)))

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
                           (event/topic-name topic)
                           (codec/encode {:topic topic :event event})]))

(defrecord EventOutbox [ds bus opts owner]
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
  (->EventOutbox ds bus (merge defaults opts) (str (random-uuid))))

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

(defn- claim-relay!
  "Take or renew the relay lease for `owner`; true when held. A relay paused
   past `lease-ms` may overlap the next owner for one batch: duplicates, no loss."
  [ds owner lease-ms]
  (let [now (System/currentTimeMillis)]
    (= 1 (::jdbc/update-count
          (jdbc/execute-one! ds ["UPDATE event_outbox_relay SET owner = ?, until_ms = ?
                                   WHERE name = 'relay' AND (until_ms < ? OR owner = ?)"
                                 owner (+ now lease-ms) now owner])))))

(defn- release-relay! [ds owner]
  (jdbc/execute-one! ds ["UPDATE event_outbox_relay SET until_ms = 0
                           WHERE name = 'relay' AND owner = ?" owner]))

(defn- mark-delivered! [ds ids]
  (when (seq ids)
    (jdbc/execute-one! ds (into [(str "UPDATE event_outbox SET delivered_ms = ? WHERE id IN ("
                                      (str/join "," (repeat (count ids) "?")) ")")
                                 (System/currentTimeMillis)]
                                ids))))

(defn- purge-delivered! [ds retain-ms]
  (jdbc/execute-one! ds ["DELETE FROM event_outbox WHERE delivered_ms < ?"
                         (- (System/currentTimeMillis) retain-ms)]))

(defn relay-once!
  "Publish up to `:batch-size` pending rows in order and mark them delivered.
   Stops at the first failed publish, so a later event never overtakes an
   earlier one; the failed row is retried next pass. Does nothing while
   another relay holds the lease. Returns the count delivered."
  [{:keys [ds bus opts owner]}]
  (let [{:keys [batch-size retain-ms lease-ms]} opts]
    (if-not (claim-relay! ds owner lease-ms)
      0
      (let [rows (pending ds batch-size)
            ids  (reduce (fn [ids row]
                           (let [result (publish-row bus row)]
                             (if (:error result)
                               (do (log/warn "outbox relay: publish failed, retrying next pass"
                                             {:id (:id row) :error (:error result)})
                                   (reduced ids))
                               (conj ids (:id row)))))
                         []
                         rows)]
        (mark-delivered! ds ids)
        ;; A short batch ends the tick's loop: purge once per tick, not per batch.
        (when (< (count rows) batch-size)
          (purge-delivered! ds retain-ms))
        (count ids)))))

(defn- relay-tick! [outbox]
  (try
    ;; A full batch means more may be waiting.
    (while (= (get-in outbox [:opts :batch-size]) (relay-once! outbox)))
    (catch Throwable t
      ;; Caught, or the scheduler cancels every later run.
      (log/error t "outbox relay pass failed"))))

(defn start-relay!
  "Poll every `:poll-ms`. Returns `outbox` with its `:executor`."
  [outbox]
  (let [ex (Executors/newSingleThreadScheduledExecutor)
        ms (long (get-in outbox [:opts :poll-ms]))]
    (.scheduleWithFixedDelay ex ^Runnable #(relay-tick! outbox) ms ms TimeUnit/MILLISECONDS)
    (assoc outbox :executor ex)))

(defn stop-relay!
  "Stop polling and hand the lease to the next relay. Rows not yet relayed stay
   pending."
  [{:keys [ds owner] :as outbox}]
  (when-let [^ScheduledExecutorService ex (:executor outbox)]
    (.shutdown ex)
    (when-not (.awaitTermination ex 5 TimeUnit/SECONDS)
      (.shutdownNow ex))
    (release-relay! ds owner)))
