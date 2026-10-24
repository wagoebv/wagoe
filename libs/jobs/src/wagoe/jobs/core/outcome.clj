(ns wagoe.jobs.core.outcome
  "How a job run ended, each job type's record of its runs (BOU-832), and
   which job types are overdue a success (BOU-834)."
  (:require [wagoe.jobs.core.job :as job])
  (:import [java.time Instant]))

(def max-error-length
  "A record keeps this much of the last error's message."
  2000)

(defn run-outcome
  "What one run of `job` ended in. `result` is the handler's, `started-at` and
   `finished-at` Instants. A failure carries `:retrying?`: whether the worker
   runs it again."
  [job result ^Instant started-at ^Instant finished-at duration-ms]
  (let [success? (boolean (:success? result))]
    (cond-> {:job-id      (:id job)
             :job-type    (:job-type job)
             :queue       (:queue job)
             :attempt     (inc (or (:retry-count job) 0))
             :outcome     (if success? :success :failure)
             :started-at  started-at
             :finished-at finished-at
             :duration-ms duration-ms}
      (not success?)
      (assoc :error     (:error result)
             :retrying? (= :retrying (:status (job/fail-job job (:error result) finished-at)))))))

(defn error-message
  "The part of `outcome`'s error a record keeps."
  [outcome]
  (when-let [error (:error outcome)]
    (let [s (str (or (:message error) error))]
      (subs s 0 (min max-error-length (count s))))))

(defn after-outcome
  "`record`, a job type's record (nil before its first run), after `outcome`."
  [record {:keys [job-type outcome started-at finished-at duration-ms] :as o}]
  (let [record (merge {:job-type job-type :failures-since-success 0
                       :last-succeeded-at nil :last-failed-at nil :last-error nil}
                      record
                      {:last-outcome     outcome
                       :last-started-at  started-at
                       :last-duration-ms duration-ms})]
    (if (= :success outcome)
      (assoc record :last-succeeded-at finished-at :failures-since-success 0)
      (-> record
          (assoc :last-failed-at finished-at :last-error (error-message o))
          (update :failures-since-success inc)))))

(defn stale
  "The records among `records` (job type -> record) that `expect-success`
   ({job-type hours}) says should have succeeded since `now` minus their
   hours, each with `:expected-within-hours`. A type without a record or
   without a success yet is not stale: when it first ran is not kept."
  [records expect-success ^Instant now]
  (for [[job-type hours] expect-success
        :let [record (get records job-type)
              ^Instant last (:last-succeeded-at record)]
        :when (and last (.isBefore last (.minusMillis now (long (* hours 60 60 1000)))))]
    (assoc record :expected-within-hours hours)))
