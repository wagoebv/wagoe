(ns wagoe.realtime.core.bus-test
  {:kaocha.testable/meta {:unit true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.core.bus :as bus]))

(def msg {:type :notification :payload {:x 1}})

(deftest ^:unit envelope-constructors-test
  (testing "user envelope"
    (is (= {:route :user :target #uuid "00000000-0000-0000-0000-000000000001" :message msg}
           (bus/user-envelope #uuid "00000000-0000-0000-0000-000000000001" msg))))
  (testing "role envelope"
    (is (= {:route :role :target :admin :message msg}
           (bus/role-envelope :admin msg))))
  (testing "broadcast envelope has nil target"
    (is (= {:route :broadcast :target nil :message msg}
           (bus/broadcast-envelope msg))))
  (testing "connection envelope"
    (is (= {:route :connection :target #uuid "00000000-0000-0000-0000-000000000002" :message msg}
           (bus/connection-envelope #uuid "00000000-0000-0000-0000-000000000002" msg))))
  (testing "topic envelope"
    (is (= {:route :topic :target "order:123" :message msg}
           (bus/topic-envelope "order:123" msg)))))
