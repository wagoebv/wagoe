(ns wagoe.push.core.analytics-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.push.core.analytics :as analytics]))

(deftest ^:unit calculate-rates-test
  (testing "normal counts"
    (let [result (analytics/calculate-rates
                  {:notification-id :test :sent 100 :delivered 80 :opened 20 :failed 5})]
      (is (= 0.8 (:delivery-rate result)))
      (is (= 0.25 (:open-rate result)))))
  (testing "zero sent — no rates added"
    (let [result (analytics/calculate-rates
                  {:notification-id :test :sent 0 :delivered 0 :opened 0 :failed 0})]
      (is (nil? (:delivery-rate result)))
      (is (nil? (:open-rate result)))))
  (testing "sent but zero delivered — delivery-rate present, no open-rate"
    (let [result (analytics/calculate-rates
                  {:notification-id :test :sent 10 :delivered 0 :opened 0 :failed 10})]
      (is (= 0.0 (:delivery-rate result)))
      (is (nil? (:open-rate result))))))
