(ns wagoe.devtools.shell.http-error-middleware-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.shell.http-error-middleware :as middleware]))

(deftest ^:integration wrap-dev-error-enrichment-test
  (testing "exceptions are re-thrown with :dev-info in ex-data"
    (let [handler (fn [_req] (throw (ex-info "bad input" {:wagoe/error-code "BND-201"})))
          wrapped (middleware/wrap-dev-error-enrichment handler)
          thrown-ex (try (wrapped {:uri "/api/test" :request-method :post})
                         nil
                         (catch Exception e e))]
      (is (some? thrown-ex))
      (let [dev-info (get (ex-data thrown-ex) :dev-info)]
        (is (some? dev-info) "should have :dev-info in ex-data")
        (is (= "BND-201" (:code dev-info)))
        (is (string? (:formatted dev-info))))))

  (testing "non-exception responses pass through unchanged"
    (let [handler (fn [_req] {:status 200 :body "ok"})
          wrapped (middleware/wrap-dev-error-enrichment handler)]
      (is (= 200 (:status (wrapped {:uri "/test"}))))))

  (testing "unclassified exceptions still get :dev-info"
    (let [handler (fn [_req] (throw (Exception. "mystery")))
          wrapped (middleware/wrap-dev-error-enrichment handler)
          thrown-ex (try (wrapped {:uri "/api/test"})
                         nil
                         (catch Exception e e))]
      (is (some? thrown-ex))
      (let [dev-info (get (ex-data thrown-ex) :dev-info)]
        (is (some? dev-info))
        (is (nil? (:code dev-info)))
        (is (string? (:formatted dev-info)))))))
