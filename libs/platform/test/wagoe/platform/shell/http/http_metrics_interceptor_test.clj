(ns wagoe.platform.shell.http.http-metrics-interceptor-test
  "The http-request-metrics interceptor must emit through a real IMetricsEmitter
   (regression for BOU-208: it previously called the no-op `increment` stub, so
   nothing reached any provider). Verified against the Prometheus adapter."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.http.interceptors :as interceptors]
            [wagoe.observability.metrics.ports :as mp]
            [wagoe.observability.metrics.shell.adapters.prometheus :as prom]))

(defn- setup []
  (let [metrics (prom/create-metrics-component {:provider :prometheus})
        handles (interceptors/register-http-metrics! metrics)]
    [metrics handles {:metrics-emitter metrics :metrics-handles handles}]))

(def ^:private m interceptors/http-request-metrics)

(deftest ^:unit register-http-metrics-returns-handles
  (let [[_ handles _] (setup)]
    (is (= #{:requests :errors :duration} (set (keys handles))))
    (testing "no metrics component -> nil handles"
      (is (nil? (interceptors/register-http-metrics! nil)))
      (is (nil? (interceptors/register-http-metrics! ::not-a-component))))))

(deftest ^:unit http-request-metrics-emitted-through-real-emitter
  (let [[metrics _ system] (setup)
        req    {:request-method :get :uri "/widgets"}
        enter  ((:enter m) {:request req :system system})
        _      ((:leave m) (assoc enter :response {:status 200}))
        out    (mp/export-metrics metrics :prometheus)]
    (testing "the request counter incremented with method+status labels"
      (is (re-find #"http_requests\{[^}]*status=\"200\"[^}]*\} 1" out)))
    (testing "the latency histogram recorded one observation (seconds buckets)"
      (is (re-find #"http_request_duration_bucket\{[^}]*le=\"0.005\"[^}]*\} 1" out))
      (is (re-find #"http_request_duration_count\{[^}]*\} 1" out)))))

(deftest ^:unit http-request-metrics-error-path-counts-request-and-error
  (let [[metrics _ system] (setup)
        enter ((:enter m) {:request {:request-method :post :uri "/orders"} :system system})]
    ;; error handler set a 500 response before this interceptor's :error runs
    ((:error m) (assoc enter
                       :request {:request-method :post :uri "/orders"}
                       :response {:status 500}))
    (let [out (mp/export-metrics metrics :prometheus)]
      (testing "the error counter increments (method + status labels)"
        (is (re-find #"http_requests_errors\{[^}]*method=\"post\"[^}]*\} 1" out))
        (is (re-find #"http_requests_errors\{[^}]*status=\"500\"[^}]*\} 1" out)))
      (testing "the total request counter also counts the errored request"
        (is (re-find #"http_requests\{[^}]*status=\"500\"[^}]*\} 1" out)))
      (testing "latency is recorded for errored requests too"
        (is (re-find #"http_request_duration_count\{[^}]*status=\"500\"[^}]*\} 1" out))))))

(deftest ^:unit http-request-metrics-error-without-response-defaults-to-500
  (let [[metrics _ system] (setup)
        enter ((:enter m) {:request {:request-method :get :uri "/x"} :system system})]
    ((:error m) (assoc enter :request {:request-method :get :uri "/x"}))
    (is (re-find #"http_requests_errors\{[^}]*status=\"500\"[^}]*\} 1"
                 (mp/export-metrics metrics :prometheus)))))

(deftest ^:unit http-request-metrics-no-op-without-metrics-component
  (let [system {:metrics-emitter nil :metrics-handles nil}
        enter  ((:enter m) {:request {:request-method :get :uri "/x"} :system system})]
    (testing "leave and error do not throw when no metrics component is wired"
      (is (map? ((:leave m) (assoc enter :response {:status 200}))))
      (is (map? ((:error m) (assoc enter :request {:request-method :get :uri "/x"})))))))
