(ns wagoe.devtools.shell.dashboard.middleware-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [wagoe.devtools.shell.dashboard.middleware :as middleware]))

(use-fixtures :each
  (fn [f]
    (middleware/clear-request-log!)
    (f)))

(deftest ^:unit wrap-request-capture-test
  (testing "captures request/response pair"
    (let [handler (middleware/wrap-request-capture
                   (fn [_req] {:status 200 :body "ok"}))
          _       (handler {:request-method :get :uri "/api/users"})]
      (is (= 1 (count (middleware/request-log))))
      (let [entry (first (middleware/request-log))]
        (is (= :get (:method entry)))
        (is (= "/api/users" (:path entry)))
        (is (= 200 (:status entry)))
        (is (uuid? (:id entry)))
        (is (inst? (:timestamp entry)))
        (is (number? (:duration-ms entry))))))

  (testing "respects buffer limit of 200"
    (let [handler (middleware/wrap-request-capture
                   (fn [_req] {:status 200 :body "ok"}))]
      (dotimes [i 210]
        (handler {:request-method :get :uri (str "/req/" i)}))
      (is (= 200 (count (middleware/request-log))))
      ;; newest first, oldest dropped — last entry is /req/10 (entries 210..11, index 0..199)
      (is (= "/req/10" (:path (nth (middleware/request-log) 199))))))

  (testing "sanitizes sensitive headers"
    (let [handler (middleware/wrap-request-capture
                   (fn [_req] {:status 200 :body "ok"}))
          _       (handler {:request-method :get
                            :uri "/api/me"
                            :headers {"authorization" "Bearer secret123"
                                      "content-type" "application/json"}})
          entry   (first (middleware/request-log))]
      (is (= "[REDACTED]" (get-in entry [:request :headers "authorization"])))
      (is (= "application/json" (get-in entry [:request :headers "content-type"]))))))
