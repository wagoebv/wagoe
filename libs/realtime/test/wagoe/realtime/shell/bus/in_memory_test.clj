(ns wagoe.realtime.shell.bus.in-memory-test
  {:kaocha.testable/meta {:unit true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.ports :as ports]
            [wagoe.realtime.shell.bus.in-memory :as bus]))

(deftest ^:unit publish-sums-delivery-fn-counts-test
  (testing "publish invokes every registered delivery-fn and sums counts"
    (let [b (bus/create-in-memory-bus)
          seen (atom [])]
      (ports/start-subscriber! b (fn [env] (swap! seen conj [:a env]) 2))
      (ports/start-subscriber! b (fn [env] (swap! seen conj [:b env]) 3))
      (is (= 5 (ports/publish b {:route :broadcast :target nil :message {:type :x}})))
      (is (= 2 (count @seen)))))
  (testing "no subscribers → 0"
    (is (= 0 (ports/publish (bus/create-in-memory-bus) {:route :broadcast :message {}}))))
  (testing "stop-subscriber! clears delivery"
    (let [b (bus/create-in-memory-bus)]
      (ports/start-subscriber! b (constantly 1))
      (ports/stop-subscriber! b)
      (is (= 0 (ports/publish b {:route :broadcast :message {}}))))))
