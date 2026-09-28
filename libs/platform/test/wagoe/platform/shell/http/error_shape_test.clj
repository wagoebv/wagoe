(ns wagoe.platform.shell.http.error-shape-test
  "The refusals the route sweep in `wagoe.error-shape-test` cannot reach,
   because the test profile leaves them off: CSRF and the rate limiter. Both
   answer in the one error shape, on the wire (BOU-586)."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.platform.core.http.errors :as errors]
            [wagoe.platform.shell.http.interceptors :as itc]
            [wagoe.platform.shell.http.reitit-router :as router]))

(use-fixtures :each
  (fn [t]
    (reset! @#'itc/rate-limit-state {})
    (t)))

(defn- handler [system]
  (router/compile-routes
   [["/api/thing" {:post {:handler (fn [_] {:status 200 :body {:ok true}}) :public true}}]
    ["/web/form" {:post {:handler (fn [_] {:status 200 :body "ok"}) :public true}}]]
   {:swagger-enabled false :system system}))

(defn- post [h uri ip]
  (h {:request-method :post :uri uri :remote-addr ip :scheme :http
      :server-name "localhost" :server-port 80
      :headers {"accept" "application/json" "content-type" "application/json"}
      :body (java.io.ByteArrayInputStream. (.getBytes "{}"))}))

(defn- wire-body [{:keys [body]}]
  (json/parse-string (if (string? body) body (slurp body)) true))

(deftest ^:unit ^:security a-csrf-refusal-is-in-the-one-shape
  (let [h (handler {:csrf {:enabled? true :secret "0123456789abcdef0123456789abcdef"}})
        r (post h "/web/form" "10.1.0.1")
        b (wire-body r)]
    (is (= 403 (:status r)))
    (is (= "csrf-validation-failed" (get-in b [:error :type])))
    (is (errors/error-body? b)))
  (testing "the Ring-middleware form"
    (let [h (itc/wrap-csrf (fn [_] {:status 200}) {:enabled? true :secret "0123456789abcdef0123456789abcdef"})
          r (h {:request-method :post :uri "/web/form" :headers {}})]
      (is (= 403 (:status r)))
      (is (errors/error-body? (wire-body r))))))

(deftest ^:unit ^:security a-rate-limit-refusal-is-in-the-one-shape
  (let [h (handler {:rate-limit {:enabled? true :limit 1 :window-ms 60000}})
        _ (post h "/api/thing" "10.1.0.2")
        r (post h "/api/thing" "10.1.0.2")
        b (wire-body r)]
    (is (= 429 (:status r)))
    (is (errors/error-body? b))
    (is (= "rate-limit-exceeded" (get-in b [:error :type])))
    (is (= 60 (get-in b [:error :details :retry-after-seconds])))))
