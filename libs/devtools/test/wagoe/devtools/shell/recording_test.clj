(ns wagoe.devtools.shell.recording-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [wagoe.devtools.shell.recording :as recording]))

(def ^:private test-dir ".boundary/recordings-test")

(use-fixtures :each
  (fn [f]
    (recording/reset-session!)
    (f)
    (recording/reset-session!)
    (let [dir (io/file test-dir)]
      (when (.exists dir)
        (doseq [f (.listFiles dir)] (.delete f))
        (.delete dir)))))

(deftest ^:unit start-stop-session-test
  (testing "start creates a session, stop freezes it"
    (recording/start-recording!)
    (is (some? (recording/active-session)))
    (is (nil? (:stopped-at (recording/active-session))))
    (recording/stop-recording!)
    (is (inst? (:stopped-at (recording/active-session))))))

(deftest ^:unit capture-middleware-test
  (testing "middleware captures request/response pairs"
    (recording/start-recording!)
    (let [handler (fn [_req] {:status 200 :body {:ok true}})
          wrapped ((recording/capture-middleware) handler)
          response (wrapped {:request-method :get :uri "/api/test" :headers {}})]
      (is (= 200 (:status response)))
      (is (= 1 (count (:entries (recording/active-session))))))))

(deftest ^:integration save-load-session-test
  (testing "saves and loads session from file"
    (recording/start-recording!)
    (let [handler (fn [_req] {:status 200 :body {:ok true}})
          wrapped ((recording/capture-middleware) handler)]
      (wrapped {:request-method :get :uri "/api/test" :headers {}})
      (recording/stop-recording!)
      (recording/save-session! "test-flow" test-dir)
      (recording/reset-session!)
      (is (nil? (recording/active-session)))
      (recording/load-session! "test-flow" test-dir)
      (is (= 1 (count (:entries (recording/active-session))))))))

(deftest ^:integration load-missing-session-test
  (testing "loading a non-existent session returns error info"
    (let [result (recording/load-session! "nonexistent" test-dir)]
      (is (= :not-found (:error result))))))
