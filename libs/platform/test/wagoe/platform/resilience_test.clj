(ns wagoe.platform.resilience-test
  (:require [wagoe.cache.shell.adapters.in-memory :as cache-mem]
            [wagoe.platform.resilience :as resilience]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit with-retry-test
  (testing "retries a retryable result until attempts run out"
    (let [calls    (atom [])
          gave-up  (atom nil)
          result   (resilience/with-retry {:max-attempts 3 :delay-ms 1} :fail
                     (fn [n] (swap! calls conj n) {:fail true :n n})
                     {:on-give-up (fn [_ n] (reset! gave-up n))})]
      (is (= [1 2 3] @calls))
      (is (= {:fail true :n 3} result))
      (is (= 3 @gave-up))))

  (testing "stops at the first result it may not retry"
    (let [calls (atom 0)]
      (is (= {:n 2} (resilience/with-retry {:max-attempts 5 :delay-ms 1} :fail
                      (fn [n] (swap! calls inc) (if (= n 1) {:fail true} {:n n})))))
      (is (= 2 @calls)))))

(deftest ^:unit with-breaker-test
  (let [cache  (cache-mem/create-in-memory-cache {})
        config {:failure-threshold 2 :open-ms 60000}
        calls  (atom 0)
        call   #(resilience/with-breaker cache config "test:svc" :fail
                  (fn [retry-after-ms] {:open retry-after-ms})
                  (fn [] (swap! calls inc) {:fail true}))]
    (call)
    (call)
    (testing "an open breaker does not call f"
      (let [r (call)]
        (is (contains? r :open))
        (is (pos? (:open r)))
        (is (= 2 @calls)))))

  (testing "with no cache there is no breaker"
    (let [calls (atom 0)]
      (dotimes [_ 5]
        (resilience/with-breaker nil {:failure-threshold 1} "test:none" :fail
          (fn [_] :open)
          (fn [] (swap! calls inc) {:fail true})))
      (is (= 5 @calls)))))
