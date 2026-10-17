(ns wagoe.jobs.core.cron-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.jobs.core.cron :as cron])
  (:import [java.time Instant ZoneId]))

(def ^:private utc (ZoneId/of "UTC"))
(def ^:private amsterdam (ZoneId/of "Europe/Amsterdam"))

(defn- at [s] (Instant/parse s))

(defn- next-run [expr zone after]
  (some-> (cron/next-run (cron/parse expr) zone (at after)) str))

(defn- runs
  "The first `n` runs of `expr` after `after`, as strings."
  [expr zone after n]
  (let [schedule (cron/parse expr)]
    (->> (iterate #(cron/next-run schedule zone %) (at after))
         rest
         (take n)
         (mapv str))))

(deftest ^:unit parse-reads-each-field-form
  (let [s (cron/parse "*/15 9-17 1,15 JAN-MAR mon-fri")]
    (is (= #{0 15 30 45} (:minutes s)))
    (is (= (set (range 9 18)) (:hours s)))
    (is (= #{1 15} (:days s)))
    (is (= #{1 2 3} (:months s)))
    (is (= #{1 2 3 4 5} (:weekdays s))))
  (testing "a/n steps from a to the field's end"
    (is (= #{5 25 45} (:minutes (cron/parse "5/20 * * * *")))))
  (testing "7 is Sunday, like 0"
    (is (= #{0 5 6} (:weekdays (cron/parse "0 0 * * 5-7")))))
  (testing "shorthands"
    (is (= (dissoc (cron/parse "0 0 * * *") :expression)
           (dissoc (cron/parse "@daily") :expression)))))

(deftest ^:unit parse-returns-an-error-for-bad-expressions
  (doseq [expr ["" "* * * *" "* * * * * *" "60 * * * *" "* 24 * * *" "* * 0 * *"
                "* * * 13 *" "* * * * 8" "*/0 * * * *" "5-1 * * * *" "a * * * *"
                "1,,2 * * * *" "1-2-3 * * * *" "1/2/3 * * * *"]]
    (is (= :validation-error (get-in (cron/parse expr) [:error :type])) (pr-str expr))))

(deftest ^:unit next-run-is-strictly-after
  (is (= "2026-01-01T02:00:00Z" (next-run "0 2 * * *" utc "2026-01-01T01:59:59Z")))
  (is (= "2026-01-02T02:00:00Z" (next-run "0 2 * * *" utc "2026-01-01T02:00:00Z")))
  (is (= ["2026-01-01T00:15:00Z" "2026-01-01T00:30:00Z" "2026-01-01T00:45:00Z"]
         (runs "*/15 * * * *" utc "2026-01-01T00:00:00Z" 3))))

(deftest ^:unit next-run-crosses-months-and-years
  (is (= "2026-02-01T00:00:00Z" (next-run "@monthly" utc "2026-01-15T10:00:00Z")))
  (is (= "2027-01-01T00:00:00Z" (next-run "@yearly" utc "2026-01-01T00:00:00Z")))
  (testing "31st skips the short months"
    (is (= ["2026-01-31T00:00:00Z" "2026-03-31T00:00:00Z" "2026-05-31T00:00:00Z"]
           (runs "0 0 31 * *" utc "2026-01-01T00:00:00Z" 3))))
  (testing "29 February waits for a leap year"
    (is (= "2028-02-29T00:00:00Z" (next-run "0 0 29 2 *" utc "2026-01-01T00:00:00Z"))))
  (testing "30 February never comes"
    (is (nil? (next-run "0 0 30 2 *" utc "2026-01-01T00:00:00Z")))))

(deftest ^:unit restricted-day-fields-match-either
  ;; 2026-01-01 is a Thursday. The 13th, or any Friday.
  (is (= ["2026-01-02T00:00:00Z" "2026-01-09T00:00:00Z" "2026-01-13T00:00:00Z"]
         (runs "0 0 13 * FRI" utc "2026-01-01T00:00:00Z" 3)))
  (testing "a * day field defers to the other"
    (is (= ["2026-01-02T00:00:00Z" "2026-01-09T00:00:00Z"]
           (runs "0 0 * * 5" utc "2026-01-01T00:00:00Z" 2)))))

(deftest ^:unit next-run-is-in-the-zone
  ;; 02:00 in Amsterdam is 01:00Z in winter, 00:00Z in summer.
  (is (= "2026-01-10T01:00:00Z" (next-run "0 2 * * *" amsterdam "2026-01-09T12:00:00Z")))
  (is (= "2026-07-10T00:00:00Z" (next-run "0 2 * * *" amsterdam "2026-07-09T12:00:00Z"))))

(deftest ^:unit dst-spring-forward-runs-a-skipped-time-once
  ;; 2026-03-29: Amsterdam jumps 02:00 CET -> 03:00 CEST, so 02:30 never happens.
  (testing "a daily job at a skipped time runs at the shifted instant"
    (is (= ["2026-03-28T01:30:00Z" "2026-03-29T01:30:00Z" "2026-03-30T00:30:00Z"]
           (runs "30 2 * * *" amsterdam "2026-03-27T12:00:00Z" 3))))
  (testing "a job every 15 minutes neither stalls nor repeats"
    (is (= ["2026-03-29T00:45:00Z" "2026-03-29T01:00:00Z" "2026-03-29T01:15:00Z"]
           (runs "*/15 * * * *" amsterdam "2026-03-29T00:30:00Z" 3)))))

(deftest ^:unit dst-fall-back-runs-a-repeated-time-once
  ;; 2026-10-25: Amsterdam goes 03:00 CEST -> 02:00 CET, so 02:30 happens twice.
  (is (= ["2026-10-24T00:30:00Z" "2026-10-25T00:30:00Z" "2026-10-26T01:30:00Z"]
         (runs "30 2 * * *" amsterdam "2026-10-23T12:00:00Z" 3)))
  (testing "inside the repeated hour, a run continues on the side it started"
    (is (= "2026-10-25T01:45:00Z" (next-run "*/15 * * * *" amsterdam "2026-10-25T01:30:00Z")))))

(deftest ^:unit latest-run-finds-the-last-tick-in-a-window
  (let [daily (cron/parse "0 2 * * *")]
    (is (= (at "2026-01-05T02:00:00Z")
           (cron/latest-run daily utc (at "2026-01-01T00:00:00Z") (at "2026-01-05T12:00:00Z"))))
    (is (= (at "2026-01-05T02:00:00Z")
           (cron/latest-run daily utc (at "2026-01-01T00:00:00Z") (at "2026-01-05T02:00:00Z")))
        "the window's end is inclusive")
    (is (nil? (cron/latest-run daily utc (at "2026-01-05T02:00:00Z") (at "2026-01-05T12:00:00Z")))
        "its start is not"))
  (testing "a long gap on a busy schedule"
    (is (= (at "2026-06-01T11:59:00Z")
           (cron/latest-run (cron/parse "* * * * *") utc
                            (at "2025-06-01T00:00:00Z") (at "2026-06-01T11:59:30Z"))))))

(deftest ^:unit due-tick-collapses-missed-ticks
  (let [daily (cron/parse "0 2 * * *")]
    (testing "nothing due before the next tick"
      (is (nil? (cron/due-tick daily utc (at "2026-01-01T02:00:00Z") (at "2026-01-02T01:59:59Z")))))
    (testing "one tick due"
      (is (= {:tick (at "2026-01-02T02:00:00Z") :skipped? false}
             (cron/due-tick daily utc (at "2026-01-01T02:00:00Z") (at "2026-01-02T02:00:04Z")))))
    (testing "three days down: the latest tick, once"
      (is (= {:tick (at "2026-01-04T02:00:00Z") :skipped? true}
             (cron/due-tick daily utc (at "2026-01-01T02:00:00Z") (at "2026-01-04T09:00:00Z")))))))
