(ns wagoe.events.core.outbox-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.events.core.outbox :as policy]))

(def ^:private opts {:max-attempts 3 :dead-letter-after-ms 1000})

(deftest ^:unit failure-outcome-needs-both-thresholds
  (let [error {:type :events/unavailable}]
    (testing "the first failure starts the clock"
      (is (= {:attempts 1 :first-failed-ms 50 :last-error ":events/unavailable" :dead? false}
             (policy/failure-outcome {:attempts 0} error 50 opts))))
    (is (false? (:dead? (policy/failure-outcome {:attempts 9 :first-failed-ms 0} error 999 opts)))
        "attempts enough, window not")
    (is (false? (:dead? (policy/failure-outcome {:attempts 1 :first-failed-ms 0} error 5000 opts)))
        "window passed, attempts not")
    (is (true? (:dead? (policy/failure-outcome {:attempts 2 :first-failed-ms 0} error 1000 opts))))
    (is (true? (:dead? (policy/failure-outcome {:attempts 0} {:fatal? true} 0 opts)))
        "fatal dead-letters at once")))

(deftest ^:unit next-delay-doubles-to-the-cap-and-resets
  (let [o {:poll-ms 100 :max-backoff-ms 300}]
    (is (= 200 (policy/next-delay o 100 true)))
    (is (= 300 (policy/next-delay o 200 true)))
    (is (= 100 (policy/next-delay o 300 false)))))

(deftest ^:unit opts-problem-wants-positive-integers
  (is (nil? (policy/opts-problem opts)))
  (is (some? (policy/opts-problem (assoc opts :max-attempts 0))))
  (is (some? (policy/opts-problem (assoc opts :dead-letter-after-ms "1")))))
