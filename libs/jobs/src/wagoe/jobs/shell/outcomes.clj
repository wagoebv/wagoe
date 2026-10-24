(ns wagoe.jobs.shell.outcomes
  "Where the worker reports how each run ended (BOU-832): the store's record,
   metrics, the error tracker and the application's own hook. Each is
   optional, and none can fail a job."
  (:require [clojure.tools.logging :as log]
            [wagoe.jobs.core.outcome :as outcome]
            [wagoe.jobs.ports :as ports]
            [wagoe.observability.errors.ports :as errors]
            [wagoe.observability.metrics.ports :as metrics])
  (:import [java.time Instant]))

(def default-report-failures-every-ms
  "While a job type keeps failing, the error tracker hears of it this often."
  (* 60 60 1000))

(defn- tag [job-type] (str (symbol job-type)))

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
   the fn never throws."
  [{:keys [store metrics error-reporter on-outcome report-failures-every-ms]}]
  (let [record?  (satisfies? ports/IJobOutcomes store)
        every-ms (or report-failures-every-ms default-report-failures-every-ms)
        failing  (atom {})]
    (fn [{:keys [job-type] :as o}]
      (when record?
        (safely "record" job-type (ports/record-outcome! store o)))
      (when metrics
        (safely "emit metrics for" job-type (emit-metrics! metrics o)))
      (when error-reporter
        (safely "report" job-type
                ;; One decision across the pool's workers.
                (when (:report? (get (swap! failing update job-type outcome/throttle o every-ms) job-type))
                  (report-failure! error-reporter o))))
      (when on-outcome
        (safely "hand on" job-type (on-outcome o)))
      nil)))
