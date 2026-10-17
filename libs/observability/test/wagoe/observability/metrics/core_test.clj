(ns wagoe.observability.metrics.core-test
  "Unit tests for wagoe.observability.metrics.core namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.observability.metrics.core :as metrics-core]
            [wagoe.observability.metrics.ports :as ports]
            [wagoe.observability.metrics.shell.adapters.prometheus :as prom]))

(deftest ^:unit normalize-metric-name-test
  (testing "lowercases and replaces invalid chars"
    (is (= "http_requests" (metrics-core/normalize-metric-name "HTTP-Requests"))))

  (testing "collapses multiple underscores"
    (is (= "foo_bar" (metrics-core/normalize-metric-name "foo__bar"))))

  (testing "strips leading/trailing underscores"
    (is (= "foo" (metrics-core/normalize-metric-name "_foo_"))))

  (testing "keeps valid chars"
    (is (= "abc_123" (metrics-core/normalize-metric-name "abc_123")))))

(deftest ^:unit build-metric-name-test
  (testing "joins namespace, subsystem, and name"
    (is (= "http_server_requests"
           (metrics-core/build-metric-name "http" "server" "requests"))))

  (testing "skips nil/empty components"
    (is (= "http_requests"
           (metrics-core/build-metric-name "http" nil "requests")))
    (is (= "http_requests"
           (metrics-core/build-metric-name "http" "" "requests"))))

  (testing "normalizes each component"
    (is (= "my_app_api_duration"
           (metrics-core/build-metric-name "My-App" "API" "Duration")))))

(deftest ^:unit sanitize-tags-test
  (testing "normalizes tag keys and stringifies values"
    (is (= {"method" "GET" "status" "200"}
           (metrics-core/sanitize-tags {:method "GET" :status 200}))))

  (testing "empty map returns empty map"
    (is (= {} (metrics-core/sanitize-tags {})))))

(deftest ^:unit merge-tags-test
  (testing "merges multiple tag maps"
    (is (= {:a 1 :b 2 :c 3}
           (metrics-core/merge-tags {:a 1} {:b 2} {:c 3}))))

  (testing "later maps override earlier keys"
    (is (= {:a 2} (metrics-core/merge-tags {:a 1} {:a 2}))))

  (testing "single map passes through"
    (is (= {:a 1} (metrics-core/merge-tags {:a 1})))))

(defn- series [m metric-name]
  (:series (ports/get-metric-values m metric-name)))

(deftest ^:unit helpers-register-on-first-use
  ;; Every adapter drops an emission to an unregistered name, so these
  ;; recorded nothing until they registered.
  (let [m (prom/create-metrics-component {})]
    (metrics-core/increment-counter m "jobs.run")
    (metrics-core/increment-counter-by m "jobs.run" 2 {})
    (metrics-core/set-gauge-value m "queue.depth" 7)
    (metrics-core/observe-histogram m "job.seconds" 0.2)
    (is (= 42 (metrics-core/time-operation m "job.timed" {} (constantly 42))))
    (is (= [3] (vals (series m "jobs.run"))))
    (is (= [7] (vals (series m "queue.depth"))))
    (is (= 1 (:count (first (vals (series m "job.seconds"))))))
    (is (= 1 (:count (first (vals (series m "job.timed"))))))))
