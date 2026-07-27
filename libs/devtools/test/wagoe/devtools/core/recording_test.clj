(ns wagoe.devtools.core.recording-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [wagoe.devtools.core.recording :as recording]))

(deftest ^:unit create-session-test
  (testing "creates empty session with timestamp"
    (let [session (recording/create-session)]
      (is (vector? (:entries session)))
      (is (empty? (:entries session)))
      (is (inst? (:started-at session)))
      (is (nil? (:stopped-at session))))))

(deftest ^:unit add-entry-test
  (testing "appends entry with auto-incrementing index"
    (let [session (-> (recording/create-session)
                      (recording/add-entry
                       {:method :get :uri "/api/users" :headers {}}
                       {:status 200 :body {:users []} :headers {}}
                       42)
                      (recording/add-entry
                       {:method :post :uri "/api/users" :body {:name "Test"} :headers {}}
                       {:status 201 :body {:id 1} :headers {}}
                       15))]
      (is (= 2 (count (:entries session))))
      (is (= 0 (:idx (first (:entries session)))))
      (is (= 1 (:idx (second (:entries session))))))))

(deftest ^:unit get-entry-test
  (testing "retrieves entry by index"
    (let [session (-> (recording/create-session)
                      (recording/add-entry
                       {:method :get :uri "/api/users" :headers {}}
                       {:status 200 :body {} :headers {}}
                       10))]
      (is (= :get (get-in (recording/get-entry session 0) [:request :method])))
      (is (nil? (recording/get-entry session 5))))))

(deftest ^:unit stop-session-test
  (testing "freezes session with stopped-at timestamp"
    (let [session (-> (recording/create-session)
                      (recording/stop-session))]
      (is (inst? (:stopped-at session))))))

(deftest ^:unit merge-request-modifications-test
  (testing "deep-merges overrides into captured request body"
    (let [request {:method :post :uri "/api/users"
                   :body {:name "Test" :email "old@test.com"}
                   :headers {"content-type" "application/json"}}
          modified (recording/merge-request-modifications
                    request {:email "new@test.com" :role :admin})]
      (is (= "new@test.com" (get-in modified [:body :email])))
      (is (= "Test" (get-in modified [:body :name])))
      (is (= :admin (get-in modified [:body :role]))))))

(deftest ^:unit diff-entries-test
  (testing "produces structured diff between two entries"
    (let [session (-> (recording/create-session)
                      (recording/add-entry
                       {:method :get :uri "/api/users" :headers {}}
                       {:status 200 :body {:count 5} :headers {}}
                       42)
                      (recording/add-entry
                       {:method :get :uri "/api/users" :headers {}}
                       {:status 200 :body {:count 10} :headers {}}
                       38))
          diff (recording/diff-entries session 0 1)]
      (is (map? diff))
      (is (contains? diff :request-diff))
      (is (contains? diff :response-diff)))))

(deftest ^:unit format-entry-table-test
  (testing "formats entries as a printable table string"
    (let [session (-> (recording/create-session)
                      (recording/add-entry
                       {:method :get :uri "/api/users" :headers {}}
                       {:status 200 :body {} :headers {}}
                       42))
          table (recording/format-entry-table session)]
      (is (string? table))
      (is (str/includes? table "GET"))
      (is (str/includes? table "/api/users"))
      (is (str/includes? table "200")))))

(deftest ^:unit serialization-round-trip-test
  (testing "session survives EDN serialization"
    (let [session (-> (recording/create-session)
                      (recording/add-entry
                       {:method :post :uri "/api/users"
                        :body {:name "Test"} :headers {}}
                       {:status 201 :body {:id 1} :headers {}}
                       15)
                      (recording/stop-session))
          serialized (recording/serialize-session session)
          deserialized (recording/deserialize-session serialized)]
      (is (string? serialized))
      (is (= 1 (count (:entries deserialized))))
      (is (= :post (get-in deserialized [:entries 0 :request :method]))))))
