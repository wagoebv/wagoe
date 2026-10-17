(ns wagoe.observability.metrics.ports-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [wagoe.observability.metrics.ports :as ports]
   [wagoe.observability.metrics.shell.adapters.no-op :as no-op]
   [wagoe.observability.metrics.shell.adapters.prometheus :as prom]))

(defn- series [metrics metric-name]
  (:series (ports/get-metric-values metrics metric-name)))

(deftest ^:unit increment-registers-on-first-use
  (let [m (prom/create-metrics-component {})]
    (ports/increment m "orders.placed" {:channel "web"})
    (ports/increment m "orders.placed" {:channel "web"})
    (ports/increment m "orders.placed" nil)
    (is (= :counter (:type (ports/get-metric m "orders.placed"))))
    (is (= {{:channel "web"} 2, {} 1} (series m "orders.placed")))))

(deftest ^:unit observe-registers-a-histogram-on-first-use
  (let [m (prom/create-metrics-component {})]
    (ports/observe m "checkout.ms" 12.5 {})
    (ports/observe m "checkout.ms" 7.5 {})
    (is (= :histogram (:type (ports/get-metric m "checkout.ms"))))
    (is (= {:sum 20.0 :count 2}
           (select-keys (get (series m "checkout.ms") {}) [:sum :count])))))

(deftest ^:unit a-disabled-metric-stays-disabled
  (testing "first-use registration does not re-enable a metric someone turned off"
    (let [m (prom/create-metrics-component {})]
      (ports/increment m "jobs.run" {})
      (ports/disable-metric! m "jobs.run")
      (ports/increment m "jobs.run" {})
      (is (= {{} 1} (series m "jobs.run"))))))

(deftest ^:unit no-op-accepts-the-calls
  (let [m (no-op/create-metrics-component {})]
    (is (nil? (ports/increment m "x" {})))
    (is (nil? (ports/observe m "y" 1.0 {})))))
