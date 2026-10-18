(ns wagoe.platform.core.retry-test
  (:require [wagoe.platform.core.retry :as retry]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit delay-ms-test
  (testing "linear, the RPC client's backoff"
    (is (= [100 200 300] (map #(retry/delay-ms {:delay-ms 100} %) [1 2 3]))))
  (testing "exponential and capped, the FTPS source's"
    (let [policy {:delay-ms 100 :backoff :exponential :max-delay-ms 30000}]
      (is (= [100 200 400] (map #(retry/delay-ms policy %) [1 2 3])))
      (is (= 30000 (retry/delay-ms (assoc policy :delay-ms 10000) 5))))))

(deftest ^:unit attempts-left?-test
  (is (retry/attempts-left? {:max-attempts 3} 2))
  (is (not (retry/attempts-left? {:max-attempts 3} 3)))
  (is (not (retry/attempts-left? nil 1)) "one attempt by default"))

(deftest ^:unit budget-ms-test
  (testing "every timeout plus every delay slept between attempts"
    (is (= (+ (* 3 300) 500 1000) (retry/budget-ms {:max-attempts 3 :delay-ms 500} 300))))
  (is (= 300 (retry/budget-ms {:max-attempts 1 :delay-ms 500} 300)))
  (is (= 0 (retry/budget-ms nil nil))))
