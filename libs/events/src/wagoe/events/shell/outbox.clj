(ns wagoe.events.shell.outbox
  "Transactional outbox: publish an event as part of a database transaction.

   `publish-in-tx!` writes the event to `event_outbox` on the caller's open
   transaction, so it commits or rolls back with the business change. A relay
   polls the table, publishes each pending row to the bus in order, and marks
   it delivered. A relay that dies between publish and mark publishes the row
   again: delivery is at-least-once, and consumers dedupe on `:id` (BOU-448).

   Relays on several replicas take turns through a lease row in
   `event_outbox_relay`, so one publishes at a time and order holds. The lease
   is timed by the database clock, so replica clock skew cannot split it.

   A row that fails `:max-attempts` times, or cannot be decoded, is
   dead-lettered and skipped so later events still flow (BOU-679).
   `dead-letters` lists them and `requeue!` puts them back.

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
  {:poll-ms      1000
   :batch-size   100
   :retain-ms    (* 24 60 60 1000)
   :lease-ms     30000
   :max-attempts 10
   :relay?       true})

;; --- schema -----------------------------------------------------------------

(defn- seed-relay-lease! [ds]
  (let [seeded? #(jdbc/execute-one! ds ["SELECT name FROM event_outbox_relay WHERE name = 'relay'"])]
    (when-not (seeded?)
      (try
        (jdbc/execute-one! ds ["INSERT INTO event_outbox_relay (name, owner, until_ms) VALUES ('relay', NULL, 0)"])
        ;; Another replica seeded it first. Anything else would leave no row,
        ;; and a relay that can never claim the lease delivers nothing, silently.
        (catch java.sql.SQLException e
          (when-not (seeded?) (throw e)))))))

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
    (db/add-columns-if-not-exist! ctx :event_outbox
                                  [[:attempts "INT DEFAULT 0 NOT NULL"]
                                   [:dead_ms "BIGINT NULL"]
                                   [:last_error (str (db-ports/column-type (:adapter ctx) :text) " NULL")]])
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
   succeed — autocommitting the row and reopening the dual-write window.
   `:connectable` unwraps `jdbc/with-transaction+options`."
  [tx]
  (let [conn (if (map? tx) (or (:datasource tx) (:connectable tx)) tx)]
    (when (and (instance? java.sql.Connection conn)
               (not (.getAutoCommit ^java.sql.Connection conn)))
      conn)))

(defn- insert-row! [conn topic event]
  (jdbc/execute-one! conn ["INSERT INTO event_outbox (id, seq, topic, envelope) VALUES (?,?,?,?)"
                           (str (random-uuid)) (next-seq)
                           (event/topic-name topic)
                           (codec/encode {:topic topic :event event})]))

(defn- db-now-ms
  "SQL for the database's clock in epoch millis, so every replica times the
   lease by one clock."
  [engine]
  (case engine
    :postgresql "CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)"
    :mysql      "CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS SIGNED)"
    :h2         "DATEDIFF('MILLISECOND', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00', CURRENT_TIMESTAMP)"
    :sqlite     "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"))

(defrecord EventOutbox [ds bus opts owner]
  ports/ITransactionalPublisher
  (publish-in-tx! [_ tx topic event]
    (let [conn    (open-connection tx)
          problem (or (when-not conn "publish-in-tx! needs an open transaction, not a datasource")
                      (event/topic-problem topic)
                      (when (< 255 (count (event/topic-name topic)))
                        "Topic must be at most 255 characters, the event_outbox.topic column")
                      (event/event-problem event))]
      (when problem
        (throw (ex-info problem {:type :validation-error :topic topic})))
      (insert-row! conn topic event)
      (:id event))))

(defn create-outbox
  "An outbox over datasource `ds` that relays to `bus`. `opts` as `defaults`."
  [ds bus & [opts]]
  (assoc (->EventOutbox ds bus (merge defaults opts) (str (random-uuid)))
         :purged-at (atom 0)
         :db-now (db-now-ms (db/engine-of ds))))

;; --- relay ------------------------------------------------------------------

(defn- now-ms [] (System/currentTimeMillis))

(defn- pending [ds limit]
  (jdbc/execute! ds ["SELECT id, envelope, attempts FROM event_outbox
                       WHERE delivered_ms IS NULL AND dead_ms IS NULL ORDER BY seq LIMIT ?" limit]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn- publish-row
  "The bus's answer for `row`, or an error. An undecodable envelope is
   `:fatal?`: retrying cannot fix it."
  [bus {:keys [envelope]}]
  (let [decoded (try (codec/decode envelope)
                     (catch Exception e
                       {:error {:type :events/undecodable :message (ex-message e) :fatal? true}}))]
    (if (:error decoded)
      decoded
      (try
        (ports/publish! bus (:topic decoded) (:event decoded))
        (catch Exception e
          {:error {:type :events/publish-failed :message (ex-message e)}})))))

(defn- record-failure!
  "Count a failed attempt on `row`; dead-letter it at `max-attempts` or when
   the error is fatal. True when dead-lettered."
  [ds {:keys [id attempts]} error max-attempts]
  (let [attempts (inc (or attempts 0))
        dead?    (or (:fatal? error) (>= attempts max-attempts))
        message  (let [m (str (:type error) (some->> (:message error) (str " ")))]
                   (subs m 0 (min 1000 (count m))))]
    (jdbc/execute-one! ds ["UPDATE event_outbox SET attempts = ?, last_error = ?, dead_ms = ? WHERE id = ?"
                           attempts message (when dead? (now-ms)) id])
    dead?))

(defn- claim-relay!
  "Take or renew the relay lease for `owner`; true when held. `db-now` is the
   database clock: a replica's own clock may be skewed. A relay paused past
   `lease-ms` may overlap the next owner for one batch: duplicates, no loss."
  [ds db-now owner lease-ms]
  (= 1 (::jdbc/update-count
        (jdbc/execute-one! ds [(str "UPDATE event_outbox_relay SET owner = ?, until_ms = " db-now " + ?
                                     WHERE name = 'relay' AND (until_ms < " db-now " OR owner = ?)")
                               owner lease-ms owner]))))

(defn- release-relay! [ds owner]
  (jdbc/execute-one! ds ["UPDATE event_outbox_relay SET until_ms = 0
                           WHERE name = 'relay' AND owner = ?" owner]))

(defn- mark-delivered! [ds ids]
  (when (seq ids)
    (jdbc/execute-one! ds (into [(str "UPDATE event_outbox SET delivered_ms = ? WHERE id IN ("
                                      (str/join "," (repeat (count ids) "?")) ")")
                                 (now-ms)]
                                ids))))

(defn- purge-delivered!
  "Delete rows delivered more than `retain-ms` ago, at most once per
   `retain-ms` or hour, whichever is shorter: not a DELETE on every idle tick."
  [ds purged-at retain-ms]
  (let [now (now-ms)]
    (when (>= (- now @purged-at) (min retain-ms (* 60 60 1000)))
      (reset! purged-at now)
      (jdbc/execute-one! ds ["DELETE FROM event_outbox WHERE delivered_ms < ?"
                             (- now retain-ms)]))))

(defn relay-once!
  "Publish up to `:batch-size` pending rows in order and mark them delivered.
   Stops at the first failed publish, so a later event never overtakes an
   earlier one; the failed row is retried next pass. A row dead-lettered
   instead (see `record-failure!`) is skipped and the pass goes on. Does
   nothing while another relay holds the lease. Returns the count delivered."
  [{:keys [ds bus opts owner purged-at db-now]}]
  (let [{:keys [batch-size retain-ms lease-ms max-attempts]} opts]
    (if-not (claim-relay! ds db-now owner lease-ms)
      0
      (let [rows (pending ds batch-size)
            ids  (reduce (fn [ids row]
                           (let [{:keys [error]} (publish-row bus row)]
                             (cond
                               (not error) (conj ids (:id row))
                               (record-failure! ds row error max-attempts)
                               (do (log/error "outbox relay: event dead-lettered, skipping it"
                                              {:id (:id row) :error error})
                                   ids)
                               :else
                               (do (log/warn "outbox relay: publish failed, retrying next pass"
                                             {:id (:id row) :error error})
                                   (reduced ids)))))
                         []
                         rows)]
        (mark-delivered! ds ids)
        ;; Only once the backlog is drained.
        (when (< (count rows) batch-size)
          (purge-delivered! ds purged-at retain-ms))
        (count ids)))))

(defn dead-letters
  "Dead-lettered rows, oldest first: `:id`, `:topic`, `:attempts`,
   `:last-error`, `:dead-ms`."
  [{:keys [ds]}]
  (jdbc/execute! ds ["SELECT id, topic, attempts, last_error, dead_ms FROM event_outbox
                       WHERE dead_ms IS NOT NULL ORDER BY seq"]
                 {:builder-fn rs/as-unqualified-kebab-maps}))

(defn requeue!
  "Make dead-lettered rows `ids` pending again with a fresh attempt count. They
   keep their place, so the relay publishes them before anything newer still
   pending. Returns the count requeued."
  [{:keys [ds]} ids]
  (if (empty? ids)
    0
    (::jdbc/update-count
     (jdbc/execute-one! ds (into [(str "UPDATE event_outbox SET dead_ms = NULL, attempts = 0, last_error = NULL
                                         WHERE dead_ms IS NOT NULL AND id IN ("
                                       (str/join "," (repeat (count ids) "?")) ")")]
                                 ids)))))

(defn- relay-tick! [outbox]
  (try
    ;; A full batch means more may be waiting. `pos?` keeps a :batch-size of 0
    ;; from spinning.
    (while (let [n (relay-once! outbox)]
             (and (pos? n) (= n (get-in outbox [:opts :batch-size])))))
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
    ;; Released only once no pass is running: handing over mid-batch would let
    ;; the next relay publish alongside this one. Otherwise the lease expires.
    (when (or (.awaitTermination ex 5 TimeUnit/SECONDS)
              (do (.shutdownNow ex)
                  (.awaitTermination ex 1 TimeUnit/SECONDS)))
      (release-relay! ds owner))))
