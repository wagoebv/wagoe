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

   A row that fails `:max-attempts` times over `:dead-letter-after-ms`, or
   cannot be decoded, is dead-lettered and skipped so later events still flow;
   after a failed pass the relay backs off (BOU-679). `dead-letters` lists
   them and `requeue!` puts them back.

   Times are epoch-millis BIGINTs and ids text, so the same SQL runs on H2,
   PostgreSQL, MySQL and SQLite without per-engine encoding."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.tools.logging :as log]
            [wagoe.events.core.event :as event]
            [wagoe.events.core.outbox :as policy]
            [wagoe.events.ports :as ports]
            [wagoe.platform.transit :as transit]
            [wagoe.platform.database :as db]
            [wagoe.platform.lease :as lease]
            [wagoe.platform.ports.database :as db-ports])
  (:import (java.util.concurrent RejectedExecutionException ScheduledThreadPoolExecutor TimeUnit)
           (java.util.concurrent.atomic AtomicLong)))

(def defaults
  {:poll-ms              1000
   :max-backoff-ms       60000
   :batch-size           100
   :retain-ms            (* 24 60 60 1000)
   :lease-ms             30000
   :max-attempts         10
   :dead-letter-after-ms (* 60 60 1000)
   :relay?               true})

;; --- schema -----------------------------------------------------------------

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
                                   [:first_failed_ms "BIGINT NULL"]
                                   [:last_error (str (db-ports/column-type (:adapter ctx) :text) " NULL")]])
    ;; dead_ms in the index, or every poll walks past each dead letter.
    (db/create-index-if-not-exists! ctx "ix_event_outbox_live" :event_outbox
                                    [:delivered_ms :dead_ms :seq])
    (db/drop-index-if-exists! ctx "ix_event_outbox_pending" :event_outbox)
    (lease/create-table! ds :event_outbox_relay "relay")))

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

(defn- insert-row! [conn topic event]
  (jdbc/execute-one! conn ["INSERT INTO event_outbox (id, seq, topic, envelope) VALUES (?,?,?,?)"
                           (str (random-uuid)) (next-seq)
                           (event/topic-name topic)
                           (transit/encode {:topic topic :event event})]))

(defrecord EventOutbox [ds bus opts owner]
  ports/ITransactionalPublisher
  (publish-in-tx! [_ tx topic event]
    (let [conn    (db/tx-connection tx)
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
  (let [opts (merge defaults opts)]
    (when-let [problem (policy/opts-problem opts)]
      (throw (ex-info problem {:type :validation-error})))
    (let [adapter (:adapter (db/context-of ds))]
      (assoc (->EventOutbox ds bus opts (str (random-uuid)))
             :purged-at (atom 0)
             :lease (lease/create-lease ds :event_outbox_relay "relay")
             :now-sql (str "SELECT " (db-ports/now-ms-sql adapter) " AS now_ms")))))

;; --- relay ------------------------------------------------------------------

(defn- placeholders [ids] (str/join "," (repeat (count ids) "?")))

(defn- pending [ds limit]
  (jdbc/execute! ds ["SELECT id, envelope, attempts, first_failed_ms FROM event_outbox
                       WHERE delivered_ms IS NULL AND dead_ms IS NULL ORDER BY seq LIMIT ?" limit]
                 {:builder-fn db/as-kebab-maps}))

(defn- publish-row
  "The bus's answer for `row`, or an error. An undecodable envelope is
   `:fatal?`: retrying cannot fix it."
  [bus {:keys [envelope]}]
  (let [decoded (try (transit/decode envelope)
                     (catch Exception e
                       {:error {:type :events/undecodable :message (ex-message e) :fatal? true}}))]
    (if (:error decoded)
      decoded
      (try
        (ports/publish! bus (:topic decoded) (:event decoded))
        (catch Exception e
          {:error {:type :events/publish-failed :message (ex-message e)}})))))

(defn- record-failure!
  "Count a failed attempt on `row`, dead-lettering it as `policy/failure-outcome`
   decides, by the database clock. True when dead-lettered."
  [{:keys [ds opts now-sql]} row error]
  (let [now (:now_ms (jdbc/execute-one! ds [now-sql] {:builder-fn rs/as-unqualified-lower-maps}))
        {:keys [attempts first-failed-ms last-error dead?]}
        (policy/failure-outcome row error (long now) opts)]
    ;; Guarded: a relay overlapping this one after a lease pause may have
    ;; delivered the row meanwhile.
    (jdbc/execute-one! ds ["UPDATE event_outbox SET attempts = ?, first_failed_ms = ?, last_error = ?, dead_ms = ?
                             WHERE id = ? AND delivered_ms IS NULL AND dead_ms IS NULL"
                           attempts first-failed-ms last-error (when dead? now) (:id row)])
    dead?))

(defn- mark-delivered! [ds ids]
  (when (seq ids)
    (jdbc/execute-one! ds (into [(str "UPDATE event_outbox SET delivered_ms = ? WHERE id IN ("
                                      (placeholders ids) ")")
                                 (System/currentTimeMillis)]
                                ids))))

(defn- purge-delivered!
  "Delete rows delivered more than `retain-ms` ago, at most once per
   `retain-ms` or hour, whichever is shorter: not a DELETE on every idle tick."
  [ds purged-at retain-ms]
  (let [now (System/currentTimeMillis)]
    (when (>= (- now @purged-at) (min retain-ms (* 60 60 1000)))
      (reset! purged-at now)
      (jdbc/execute-one! ds ["DELETE FROM event_outbox WHERE delivered_ms < ?"
                             (- now retain-ms)]))))

(defn- relay-pass!
  "One relay pass: `{:delivered n :more? bool :failed? bool}`. `:more?` when
   the batch was full and nothing stopped it, so more may be waiting;
   `:failed?` when a publish failed and the row is to be retried."
  [{:keys [ds bus opts owner purged-at lease] :as outbox}]
  (let [{:keys [batch-size retain-ms lease-ms]} opts]
    ;; A relay paused past `lease-ms` may overlap the next owner for one
    ;; batch: duplicates, no loss.
    (if-not (lease/claim! lease owner lease-ms)
      {:delivered 0 :more? false :failed? false}
      (let [rows (pending ds batch-size)
            {:keys [ids stopped?]}
            (reduce (fn [acc row]
                      (let [{:keys [error]} (publish-row bus row)]
                        (cond
                          (not error) (update acc :ids conj (:id row))
                          (record-failure! outbox row error)
                          (do (log/error "outbox relay: event dead-lettered, skipping it"
                                         {:id (:id row) :error error})
                              acc)
                          :else
                          (do (log/warn "outbox relay: publish failed, retrying next pass"
                                        {:id (:id row) :error error})
                              (reduced (assoc acc :stopped? true))))))
                    {:ids [] :stopped? false}
                    rows)]
        (mark-delivered! ds ids)
        ;; Only once the backlog is drained.
        (when (< (count rows) batch-size)
          (purge-delivered! ds purged-at retain-ms))
        ;; `pos?` keeps a :batch-size of 0 from spinning.
        {:delivered (count ids)
         :more?     (and (pos? batch-size) (= (count rows) batch-size) (not stopped?))
         :failed?   stopped?}))))

(defn relay-once!
  "Publish up to `:batch-size` pending rows in order and mark them delivered.
   Stops at the first failed publish, so a later event never overtakes an
   earlier one; the failed row is retried next pass. A row dead-lettered
   instead (see `record-failure!`) is skipped and the pass goes on. Does
   nothing while another relay holds the lease. Returns the count delivered."
  [outbox]
  (:delivered (relay-pass! outbox)))

(defn dead-letters
  "Dead-lettered rows, oldest first: `:id`, `:topic`, `:attempts`,
   `:last-error`, `:dead-ms`."
  [{:keys [ds]}]
  (jdbc/execute! ds ["SELECT id, topic, attempts, last_error, dead_ms FROM event_outbox
                       WHERE dead_ms IS NOT NULL ORDER BY seq"]
                 {:builder-fn db/as-kebab-maps}))

(defn requeue!
  "Make dead-lettered rows `ids` pending again, failures forgotten. They
   keep their place, so the relay publishes them before anything newer still
   pending. Returns the count requeued."
  [{:keys [ds]} ids]
  (if (empty? ids)
    0
    (::jdbc/update-count
     (jdbc/execute-one! ds (into [(str "UPDATE event_outbox SET dead_ms = NULL, attempts = 0, first_failed_ms = NULL, last_error = NULL
                                         WHERE dead_ms IS NOT NULL AND id IN ("
                                       (placeholders ids) ")")]
                                 ids)))))

(defn- relay-tick!
  "Relay until drained or a pass fails; the delay before the next tick."
  [outbox delay]
  (policy/next-delay
   (:opts outbox) delay
   (try
     ;; Counted by rows read, not delivered: a dead letter in a full batch
     ;; still means more may be waiting.
     (loop []
       (let [{:keys [more? failed?]} (relay-pass! outbox)]
         (if more? (recur) failed?)))
     (catch Throwable t
       ;; Caught, or no tick would follow.
       (log/error t "outbox relay pass failed")
       true))))

(defn start-relay!
  "Poll every `:poll-ms`, backing off after a failed pass. Returns `outbox`
   with its `:executor`."
  [outbox]
  (let [ex (doto (ScheduledThreadPoolExecutor. 1)
             (.setExecuteExistingDelayedTasksAfterShutdownPolicy false))
        tick (fn tick [delay]
               (try
                 (.schedule ex ^Runnable #(tick (relay-tick! outbox delay))
                            (long delay) TimeUnit/MILLISECONDS)
                 ;; Stopped.
                 (catch RejectedExecutionException _ nil)))]
    (tick (get-in outbox [:opts :poll-ms]))
    (assoc outbox :executor ex)))

(defn stop-relay!
  "Stop polling and hand the lease to the next relay. Rows not yet relayed stay
   pending."
  [{:keys [owner lease] :as outbox}]
  (when-let [^ScheduledThreadPoolExecutor ex (:executor outbox)]
    (.shutdown ex)
    ;; Released only once no pass is running: handing over mid-batch would let
    ;; the next relay publish alongside this one. Otherwise the lease expires.
    (when (or (.awaitTermination ex 5 TimeUnit/SECONDS)
              (do (.shutdownNow ex)
                  (.awaitTermination ex 1 TimeUnit/SECONDS)))
      (lease/release! lease owner))))
