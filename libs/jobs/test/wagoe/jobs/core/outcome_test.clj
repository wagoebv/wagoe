(ns wagoe.jobs.core.outcome-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.jobs.core.outcome :as outcome])
  (:import [java.time Instant]))

(def ^:private t0 (Instant/parse "2026-10-24T10:00:00Z"))

(defn- at [s] (.plusSeconds t0 s))

(defn- run [result & {:keys [retry-count max-retries finished] :or {retry-count 0 max-retries 3 finished 1}}]
  (outcome/run-outcome {:id (random-uuid) :job-type :reports/generate :queue :default
                        :retry-count retry-count :max-retries max-retries}
                       result t0 (at finished) 1000))

(deftest ^:unit a-run-says-how-it-ended
  (let [ok (run {:success? true})]
    (is (= :success (:outcome ok)))
    (is (= 1 (:attempt ok)))
    (is (not (contains? ok :error))))
  (testing "a failure says whether the worker runs it again"
    (is (:retrying? (run {:success? false :error {:message "boom"}})))
    (is (false? (:retrying? (run {:success? false :error {:message "boom"}} :retry-count 2))))))

(deftest ^:unit a-record-counts-failures-since-the-last-success
  (let [fail   (run {:success? false :error {:message "boom"}} :finished 5)
        record (-> nil
                   (outcome/after-outcome (run {:success? true}))
                   (outcome/after-outcome fail)
                   (outcome/after-outcome fail))]
    (is (= :reports/generate (:job-type record)))
    (is (= :failure (:last-outcome record)))
    (is (= (at 1) (:last-succeeded-at record)))
    (is (= (at 5) (:last-failed-at record)))
    (is (= "boom" (:last-error record)))
    (is (= 2 (:failures-since-success record)))
    (is (zero? (:failures-since-success (outcome/after-outcome record (run {:success? true})))))))

(deftest ^:unit a-long-error-is-cut
  (is (= outcome/max-error-length
         (count (:last-error (outcome/after-outcome nil (run {:success? false
                                                              :error {:message (apply str (repeat 3000 "x"))}})))))))

(deftest ^:unit a-type-is-stale-once-its-last-success-is-older-than-its-hours
  (let [now     (at (* 10 60 60))
        record  (fn [job-type hours-ago]
                  {:job-type          job-type
                   :last-succeeded-at (when hours-ago (.minusSeconds now (* hours-ago 60 60)))})
        records {:ok    (record :ok 2)
                 :late  (record :late 5)
                 :never (record :never nil)
                 :half  (record :half 1)}
        stale   (outcome/stale records {:ok 3 :late 3 :never 3 :unknown 3 :half 0.5} now)]
    (is (= {:late 3 :half 0.5} (into {} (map (juxt :job-type :expected-within-hours)) stale)))
    (testing "a type with no record, or that never succeeded, is not stale"
      (is (not-any? #{:never :unknown} (map :job-type stale))))))
