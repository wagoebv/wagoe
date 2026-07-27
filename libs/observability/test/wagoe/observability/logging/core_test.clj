(ns wagoe.observability.logging.core-test
  "Unit tests for wagoe.observability.logging.core namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.observability.logging.core :as logging-core]))

(deftest ^:unit merge-contexts-test
  (testing "merges multiple context maps"
    (is (= {:a 1 :b 2 :c 3}
           (logging-core/merge-contexts {:a 1} {:b 2} {:c 3}))))

  (testing "later maps override earlier keys"
    (is (= {:a 2} (logging-core/merge-contexts {:a 1} {:a 2}))))

  (testing "single context passes through"
    (is (= {:a 1} (logging-core/merge-contexts {:a 1}))))

  (testing "nil contexts produce nil entries"
    (is (= {:a 1} (logging-core/merge-contexts nil {:a 1})))))

(deftest ^:unit with-correlation-id-test
  (testing "adds correlation-id to context"
    (is (= {:correlation-id "abc-123"}
           (logging-core/with-correlation-id {} "abc-123"))))

  (testing "overwrites existing correlation-id"
    (is (= {:correlation-id "new-id"}
           (logging-core/with-correlation-id {:correlation-id "old-id"} "new-id")))))

(deftest ^:unit with-tenant-id-test
  (testing "adds tenant-id to context"
    (is (= {:tenant-id "t-1"} (logging-core/with-tenant-id {} "t-1"))))

  (testing "preserves other keys"
    (is (= {:foo "bar" :tenant-id "t-2"}
           (logging-core/with-tenant-id {:foo "bar"} "t-2")))))

(deftest ^:unit with-user-id-test
  (testing "adds user-id to context"
    (is (= {:user-id "u-1"} (logging-core/with-user-id {} "u-1")))))

(deftest ^:unit with-tags-test
  (testing "adds tags to context"
    (is (= {:tags {:env "test"}}
           (logging-core/with-tags {} {:env "test"}))))

  (testing "merges with existing tags"
    (is (= {:tags {:env "test" :service "api"}}
           (logging-core/with-tags {:tags {:env "test"}} {:service "api"}))))

  (testing "later tags override"
    (is (= {:tags {:env "prod"}}
           (logging-core/with-tags {:tags {:env "test"}} {:env "prod"})))))

(deftest ^:unit with-trace-info-test
  (testing "adds trace-id and span-id"
    (is (= {:trace-id "t-1" :span-id "s-1"}
           (logging-core/with-trace-info {} "t-1" "s-1"))))

  (testing "preserves other keys"
    (is (= {:foo "bar" :trace-id "t-2" :span-id "s-2"}
           (logging-core/with-trace-info {:foo "bar"} "t-2" "s-2")))))
