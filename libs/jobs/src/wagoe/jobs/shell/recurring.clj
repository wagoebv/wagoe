(ns wagoe.jobs.shell.recurring
  "Recurring jobs on cron schedules, and enqueue-once by idempotency key.

   Every replica may run a ticker; the one holding the lease in
   `IRecurringState` enqueues. Each tick is claimed in the idempotency store
   under (job type, scheduled instant) before it is enqueued, so two tickers
   that overlap after a lease handover still enqueue it once.

   Ticks missed while no ticker ran collapse into one: after downtime a
   schedule fires its latest missed tick once and the earlier ones never. A
   schedule's first tick is the first after it was registered."
  (:require [clojure.tools.logging :as log]
            [wagoe.jobs.core.cron :as cron]
            [wagoe.jobs.core.job :as job]
            [wagoe.jobs.ports :as ports])
  (:import [java.time DateTimeException Instant ZoneId]
           [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(def defaults
  {:tick-ms  5000
   :lease-ms 30000
   :zone     "UTC"})

(def default-key-ttl-ms
  "How long a claimed idempotency key holds by default: a week."
  (* 7 24 60 60 1000))

(defn- type-name [job-type]
  (if (keyword? job-type) (subs (str job-type) 1) (str job-type)))

(defn enqueue-once!
  "Enqueue `job` on `queue-name` unless (its job type, `key`) was claimed in
   `idempotency` within `:ttl-ms`. Returns the job id, or nil for a duplicate.
   A failed enqueue releases the claim, so a retry can succeed."
  [queue idempotency queue-name job key & [{:keys [ttl-ms] :or {ttl-ms default-key-ttl-ms}}]]
  (let [scope (type-name (:job-type job))
        key   (str key)]
    (when (ports/claim-key! idempotency scope key ttl-ms)
      (try
        (ports/enqueue-job! queue queue-name job)
        (catch Throwable t
          (ports/release-key! idempotency scope key)
          (throw t))))))

(defn- zone-of ^ZoneId [zone]
  (if (instance? ZoneId zone)
    zone
    (try (ZoneId/of (str zone))
         (catch DateTimeException e
           (throw (ex-info (str "Unknown time zone " (pr-str zone))
                           {:type :validation-error :zone zone} e))))))

(defn create-scheduler
  "A scheduler enqueueing on `queue`, coordinating through `state`
   (`IRecurringState`) and `idempotency` (`IIdempotencyStore`). `opts` as
   `defaults`; `:zone` is the default for schedules that name none."
  [{:keys [queue state idempotency]} & [opts]]
  (let [opts (merge defaults opts)]
    {:queue       queue
     :state       state
     :idempotency idempotency
     :opts        (assoc opts :zone (zone-of (:zone opts)))
     :owner       (str (random-uuid))
     :schedules   (atom {})
     ;; Only the lease holder writes ticks, so while it holds the lease it can
     ;; skip a schedule until its next run instead of reading it every pass.
     :held?       (atom false)
     :next-due    (atom {})
     :purged-at   (atom 0)}))

(defn schedule-recurring!
  "Enqueue a `job-type` job on every tick of cron expression `cron-expr`.
   `opts`: `:args`, `:queue`, `:priority`, `:max-retries`, and `:zone`, a zone
   id like \"Europe/Amsterdam\". Calling it again for a job type replaces its
   schedule. Throws `:validation-error` for an expression that is malformed or
   never fires."
  [{:keys [schedules next-due opts]} job-type cron-expr & [job-opts]]
  (let [schedule (cron/parse cron-expr)
        zone     (zone-of (:zone job-opts (:zone opts)))
        now      (Instant/now)]
    (when-let [error (:error schedule)]
      (throw (ex-info (:message error) (assoc error :job-type job-type))))
    (when-not (cron/next-run schedule zone now)
      (throw (ex-info (str "Cron expression never fires: " cron-expr)
                      {:type :validation-error :job-type job-type :expression cron-expr})))
    (swap! next-due dissoc job-type)
    (swap! schedules assoc job-type
           {:name          (type-name job-type)
            :schedule      schedule
            :zone          zone
            :job           (select-keys job-opts [:args :queue :priority :max-retries])
            :registered-at now})
    job-type))

(defn- fire! [{:keys [queue idempotency]} job-type {:keys [job]} ^Instant tick now]
  (let [j (job/create-job (assoc job :job-type job-type :metadata {:recurring-tick tick})
                          (random-uuid) now)]
    (enqueue-once! queue idempotency (:queue j) j (str "tick/" tick))))

(defn- tick-schedule!
  "Enqueue `job-type`'s due tick, if any; true when it enqueued."
  [{:keys [state next-due] :as scheduler} job-type {:keys [schedule zone registered-at] schedule-name :name :as entry}
   ^Instant now]
  (when-not (some-> ^Instant (get @next-due job-type) (.isAfter now))
    (let [since (or (ports/last-tick state schedule-name) registered-at)
          {:keys [tick skipped?]} (cron/due-tick schedule zone since now)
          fired? (when tick
                   (when skipped?
                     (log/warn "Recurring job missed ticks; running the latest once"
                               {:job-type job-type :since since :tick tick}))
                   (let [fired? (some? (fire! scheduler job-type entry tick now))]
                     (ports/record-tick! state schedule-name tick)
                     fired?))]
      (swap! next-due assoc job-type (cron/next-run schedule zone (or tick since)))
      fired?)))

(defn- purge-hourly! [{:keys [idempotency purged-at]}]
  (let [now (System/currentTimeMillis)]
    (when (<= (* 60 60 1000) (- now @purged-at))
      (reset! purged-at now)
      (ports/purge-expired-keys! idempotency))))

(defn tick!
  "One ticker pass at `now`. Does nothing unless this scheduler holds the
   lease. Returns the job types it enqueued."
  [{:keys [state schedules owner opts held? next-due] :as scheduler} ^Instant now]
  (let [entries @schedules]
    (if-not (and (seq entries) (ports/acquire-lease! state owner (:lease-ms opts)))
      (do (reset! held? false) [])
      (let [_     (when-not @held?
                    ;; Newly the holder: another may have fired ticks meanwhile.
                    (reset! next-due {})
                    (reset! held? true))
            fired (into []
                        (keep (fn [[job-type entry]]
                                (try
                                  (when (tick-schedule! scheduler job-type entry now) job-type)
                                  (catch Exception e
                                    (log/error e "Recurring job tick failed" {:job-type job-type})
                                    nil))))
                        entries)]
        (purge-hourly! scheduler)
        fired))))

(defn start!
  "Run `tick!` every `:tick-ms`. Returns the scheduler with its `:executor`."
  [scheduler]
  (let [ex (Executors/newSingleThreadScheduledExecutor)
        ms (get-in scheduler [:opts :tick-ms])]
    (.scheduleWithFixedDelay ex
                             ^Runnable (fn []
                                         (try (tick! scheduler (Instant/now))
                                              ;; Caught, or no tick would follow.
                                              (catch Throwable t
                                                (log/error t "Recurring job ticker pass failed"))))
                             (long ms) (long ms) TimeUnit/MILLISECONDS)
    (assoc scheduler :executor ex)))

(defn stop!
  "Stop ticking and hand the lease to the next ticker."
  [{:keys [state owner] :as scheduler}]
  (when-let [^ScheduledExecutorService ex (:executor scheduler)]
    (.shutdown ex)
    ;; Released only once no pass is running, or two would overlap.
    (when (or (.awaitTermination ex 5 TimeUnit/SECONDS)
              (do (.shutdownNow ex)
                  (.awaitTermination ex 1 TimeUnit/SECONDS)))
      (ports/release-lease! state owner))))
