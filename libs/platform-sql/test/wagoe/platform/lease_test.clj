(ns wagoe.platform.lease-test
  "Runs on the engine WAGOE_TEST_DB names (h2 by default; pg, mysql)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [support.test-db :as test-db]
            [wagoe.platform.lease :as lease]))

(defn- with-lease [f]
  (test-db/with-db "lease"
    (fn [{:keys [datasource]}]
      (lease/create-table! datasource :test_lease "a")
      ;; Twice: a second replica booting finds the table and the row.
      (lease/create-table! datasource :test_lease "a")
      (f datasource (lease/create-lease datasource :test_lease "a")))))

(deftest ^:integration one-holder-at-a-time
  (with-lease
    (fn [_ l]
      (is (true? (lease/claim! l "x" 60000)))
      (is (true? (lease/claim! l "x" 60000)) "its holder renews it")
      (is (false? (lease/claim! l "y" 60000)))
      (testing "released only by its holder"
        (lease/release! l "y")
        (is (false? (lease/claim! l "y" 60000)))
        (lease/release! l "x")
        (is (true? (lease/claim! l "y" 1000)))))))

(deftest ^:integration an-expired-lease-is-taken-over
  (with-lease
    (fn [_ l]
      (is (true? (lease/claim! l "x" 50)))
      (Thread/sleep 200)
      (is (true? (lease/claim! l "y" 60000)))
      (is (false? (lease/claim! l "x" 60000))))))

(deftest ^:integration leases-in-one-table-are-independent
  (with-lease
    (fn [ds a]
      (lease/create-table! ds :test_lease "b")
      (let [b (lease/create-lease ds :test_lease "b")]
        (is (true? (lease/claim! a "x" 60000)))
        (is (true? (lease/claim! b "y" 60000)))
        (is (= 2 (count (jdbc/execute! ds ["SELECT name FROM test_lease"]))))))))

(deftest ^:unit a-table-name-is-an-identifier
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plain identifier"
                        (lease/create-lease nil "lease; DROP TABLE users" "a"))))
