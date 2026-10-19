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

(defn- recording-emitter
  "An IMetricsEmitter that keeps [kind name value tags]. Not a registry, so
   the port helpers emit without registering first."
  [calls]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ports/IMetricsEmitter
    (inc-counter! [_ h value tags] (swap! calls conj [:counter h value tags]))
    (set-gauge! [_ h value tags] (swap! calls conj [:gauge h value tags]))
    (observe-histogram! [_ h value tags] (swap! calls conj [:histogram h value tags]))))

(deftest ^:unit time-with-histogram-test
  (let [calls (atom [])]
    (is (= :done (metrics-core/time-with-histogram (recording-emitter calls) "op" {:k "v"}
                                                   (fn [] (Thread/sleep 5) :done))))
    (let [[[kind metric-name ms tags]] @calls]
      (is (= [:histogram "op" {:k "v"}] [kind metric-name tags]))
      (is (>= ms 5.0)))))

(deftest ^:unit named-helpers-build-names-and-tags-test
  (let [calls (atom [])
        e     (recording-emitter calls)]
    (metrics-core/count-events e :signup {:plan "free"})
    (metrics-core/track-resource-usage e :heap 512)
    (metrics-core/track-business-kpi e :mrr 1000.0 {:currency "EUR"})
    (metrics-core/track-request-duration e :get "/orders" 200 12)
    (metrics-core/track-active-count e "sessions" 3)
    (metrics-core/record-timer e "render" 4)
    (is (= [[:counter "app_events_signup" 1 {:event-type "signup" :plan "free"}]
            [:gauge "system_resources_heap" 512 {:resource "heap"}]
            [:gauge "business_kpi_mrr" 1000.0 {:kpi "mrr" :currency "EUR"}]
            [:histogram "http_requests_duration" 12 {:method "GET" :path "/orders" :status "200"}]
            [:gauge "sessions" 3 {}]
            [:histogram "render" 4 {}]]
           @calls))))

(deftest ^:unit track-user-action-test
  (testing "a success also counts as a successful action"
    (let [calls (atom [])]
      (metrics-core/track-user-action (recording-emitter calls) :checkout "u1" true)
      (is (= [["app_events_user_action" {:event-type "user-action" :action "checkout" :success "true"}]
              ["app_events_successful_action" {:event-type "successful-action" :action "checkout"}]]
             (mapv (fn [[_ n _ t]] [n t]) @calls)))))

  (testing "a failure counts once"
    (let [calls (atom [])]
      (metrics-core/track-user-action (recording-emitter calls) :checkout "u1" false)
      (is (= ["app_events_user_action"] (mapv second @calls))))))

(deftest ^:unit track-api-usage-test
  (testing "a 2xx counts as success and records the duration"
    (let [calls (atom [])]
      (metrics-core/track-api-usage (recording-emitter calls) "/orders" :post 201 30)
      (is (= ["app_events_api_request" "app_events_api_success" "http_requests_duration"]
             (mapv second @calls)))
      (is (= 30 (nth (last @calls) 2)))))

  (testing "a 4xx counts as an error"
    (let [calls (atom [])]
      (metrics-core/track-api-usage (recording-emitter calls) "/orders" :post 404 3)
      (is (= "app_events_api_error" (second (second @calls)))))))

(deftest ^:unit track-dependency-calls-test
  (testing "a failed database operation counts an error without the success tag"
    (let [calls (atom [])]
      (metrics-core/track-database-operations (recording-emitter calls) :select "users" 8 false)
      (is (= [[:counter "app_events_db_operation"]
              [:histogram "db_operations_duration"]
              [:counter "app_events_db_error"]]
             (mapv #(subvec % 0 2) @calls)))
      (is (not (contains? (peek (last @calls)) :success)))))

  (testing "a successful external call records no error"
    (let [calls (atom [])]
      (metrics-core/track-external-service-calls (recording-emitter calls) "stripe" "charge" 120 true)
      (is (= ["app_events_external_service_call" "external_services_duration"]
             (mapv second @calls)))
      (is (= {:service "stripe" :operation "charge" :success "true"} (peek (second @calls)))))))

(deftest ^:unit track-health-check-test
  (letfn [(status-gauge [status]
            (let [calls (atom [])]
              (metrics-core/track-health-check (recording-emitter calls) "db" status 5)
              (let [[kind metric-name value] (last @calls)]
                (is (= [:gauge "health_status_db"] [kind metric-name]))
                value)))]
    (is (= 1 (status-gauge :healthy)))
    (is (= 0.5 (status-gauge :degraded)))
    (is (= 0 (status-gauge :unhealthy)))
    (testing "a status outside the three reads as unhealthy instead of throwing"
      (is (= 0 (status-gauge :unknown))))
    (testing "a string status is read like its keyword"
      (is (= 1 (status-gauge "healthy"))))))

(deftest ^:unit conditional-tracking-test
  (is (= :ran (metrics-core/track-when true (constantly :ran))))
  (is (nil? (metrics-core/track-when false (constantly :ran))))
  (is (= :ran (metrics-core/track-sampling 1.0 (constantly :ran))))
  (is (nil? (metrics-core/track-sampling 0.0 (constantly :ran)))))
