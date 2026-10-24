(ns wagoe.jobs.shell.outcomes
  "Where the worker reports how each run ended (BOU-832): the store's record,
   metrics, the error tracker and the application's own hook. Each is
   optional, and none can fail a job.

   Failure and staleness reports share one throttle per job type, a claim in
   the idempotency store, so nodes sharing that store report once between
   them (BOU-834)."
  (:require [clojure.tools.logging :as log]
            [wagoe.jobs.core.outcome :as outcome]
            [wagoe.jobs.ports :as ports]
            [wagoe.jobs.shell.adapters.in-memory :as mem]
            [wagoe.observability.errors.ports :as errors]
            [wagoe.observability.metrics.ports :as metrics])
  (:import [java.time Instant]))

(def default-report-failures-every-ms
  "While a job type keeps failing, the error tracker hears of it this often."
  (* 60 60 1000))

(def ^:private report-scope "wagoe.jobs/report")

(defn- tag [job-type] (str (symbol job-type)))

(defn- report-due?
  "Claim `job-type`'s report for `every-ms`. True when no report for it went
   out within that time on any node sharing `idempotency`. A store that cannot
   answer lets the report through: better twice than not at all."
  [idempotency job-type every-ms]
  (try
    (ports/claim-key! idempotency report-scope (tag job-type) every-ms)
    (catch Exception e
      (log/warn e "Could not claim a job report, reporting anyway" {:job-type job-type})
      true)))

(defn- report-throttled!
  "Call `report!` when `job-type`'s report is due. A report that throws is
   logged and gives its claim back, so the next one tries again instead of
   waiting out the interval. True when it went out."
  [idempotency job-type every-ms report!]
  (when (report-due? idempotency job-type every-ms)
    (try (report!)
         true
         (catch Exception e
           (log/error e "Could not report a job" {:job-type job-type})
           (try (ports/release-key! idempotency report-scope (tag job-type))
                (catch Exception e
                  (log/warn e "Could not release a job report's claim" {:job-type job-type})))
           false))))

(defn- emit-metrics!
  "A run counter by outcome, a duration histogram in seconds, and the epoch
   second of the last success, each tagged with the job type."
  [m {:keys [job-type outcome duration-ms ^Instant finished-at]}]
  (let [tags {:job-type (tag job-type)}]
    (metrics/increment m :jobs.runs (assoc tags :outcome (name outcome)))
    (metrics/observe m :jobs.run.duration (/ duration-ms 1000.0) tags)
    (when (= :success outcome)
      (metrics/gauge m :jobs.last-success (.getEpochSecond finished-at) tags))))

(defn- report-failure! [reporter {:keys [job-type job-id attempt retrying? error]}]
  ;; The message names only the job type, so the tracker groups by it.
  (errors/capture-message reporter (str "Job " (tag job-type) " failed") :error
                          {:extra {:job-id          (str job-id)
                                   :attempt         attempt
                                   :retrying?       retrying?
                                   :error           (:message error)
                                   :error-type      (some-> (:type error) str)
                                   :exception-class (:exception-class error)
                                   :stacktrace      (:stacktrace error)}}
                          {:job-type (tag job-type)}))

(defmacro ^:private safely [what job-type & body]
  `(try ~@body
        (catch Exception e#
          (log/error e# (str "Could not " ~what " a job's outcome") {:job-type ~job-type}))))

(defn reporter
  "A fn of a run's outcome that records it on `store` when the store
   implements `IJobOutcomes`, emits it to `metrics`, sends a failure to
   `error-reporter` (the first after a success, then one per
   `report-failures-every-ms`), and calls `on-outcome`. Every key is optional;
   the fn never throws.

   `idempotency` (`IIdempotencyStore`) holds the throttle; give every node the
   same one and a failing type is reported once between them. Without it the
   throttle is this reporter's own."
  [{:keys [store metrics error-reporter on-outcome report-failures-every-ms idempotency]}]
  (let [record?     (satisfies? ports/IJobOutcomes store)
        every-ms    (or report-failures-every-ms default-report-failures-every-ms)
        idempotency (or idempotency (mem/create-in-memory-idempotency-store))
        ;; Types this reporter saw fail since it saw them succeed. A success
        ;; frees the claim only for these, sparing a write per successful run,
        ;; so a success seen only on another node does not cut the interval short.
        failing     (atom #{})]
    (fn [{:keys [job-type] :as o}]
      (when record?
        (safely "record" job-type (ports/record-outcome! store o)))
      (when metrics
        (safely "emit metrics for" job-type (emit-metrics! metrics o)))
      (when error-reporter
        (safely "report" job-type
                (if (= :failure (:outcome o))
                  (do (swap! failing conj job-type)
                      (report-throttled! idempotency job-type every-ms
                                         #(report-failure! error-reporter o)))
                  (when (contains? @failing job-type)
                    (swap! failing disj job-type)
                    (ports/release-key! idempotency report-scope (tag job-type))))))
      (when on-outcome
        (safely "hand on" job-type (on-outcome o)))
      nil)))

(defn- report-stale! [reporter {:keys [job-type expected-within-hours] :as record}]
  (errors/capture-message reporter
                          (str "Job " (tag job-type) " has not succeeded within " expected-within-hours "h")
                          :error
                          {:extra {:last-succeeded-at      (str (:last-succeeded-at record))
                                   :last-outcome           (some-> (:last-outcome record) name)
                                   :failures-since-success (:failures-since-success record)
                                   :last-error             (:last-error record)}}
                          {:job-type (tag job-type)}))

(defn registered-outcomes
  "`store`'s `job-outcomes` for the job types `registry` has a handler for, so
   a type that was renamed or removed drops out."
  [store registry]
  (select-keys (ports/job-outcomes store) (ports/list-handlers registry)))

(defn check-stale!
  "Report each job type in `expect-success`, {job-type hours}, whose last
   success on `store` is older than its hours at `now`, through the same
   throttle as failures. A type that has no record or never succeeded is not
   stale: its failures are reported already. Given `registry`, only types it
   has a handler for are checked, so a type switched off here is not reported
   every day. A report that fails does not stop the others. Returns the types
   reported."
  [{:keys [store registry idempotency error-reporter expect-success report-failures-every-ms]} ^Instant now]
  (let [every-ms (or report-failures-every-ms default-report-failures-every-ms)
        records  (if registry (registered-outcomes store registry) (ports/job-outcomes store))]
    (into []
          (keep (fn [{:keys [job-type] :as record}]
                  (when (report-throttled! idempotency job-type every-ms
                                           #(report-stale! error-reporter record))
                    job-type)))
          (outcome/stale records expect-success now))))
