(ns wagoe.realtime.core.sse-test
  {:kaocha.testable/meta {:unit true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.realtime.core.sse :as sse]))

(deftest ^:unit format-event-splits-lines
  (is (= "event: row\ndata: <tr>\ndata: </tr>\n\n" (sse/format-event "row" "<tr>\n</tr>")))
  (is (= "event: message\ndata: x\n\n" (sse/format-event "message" "x")))
  (is (= "event: row\ndata: a\ndata: event: b\n\n" (sse/format-event "row" "a\revent: b"))
      "a lone CR cannot start a field of its own"))

(deftest ^:unit user-identity
  (let [id (random-uuid)]
    (is (= {:user-id id :roles #{:admin} :email "a@b.c"}
           (sse/user-identity {:id id :role "admin" :email "a@b.c"})))
    (is (= #{:a :b} (:roles (sse/user-identity {:id (str id) :roles [:a "b"]}))))
    (is (= #{} (:roles (sse/user-identity {:id id}))))
    (is (nil? (sse/user-identity nil)))
    (is (nil? (sse/user-identity {:id "nope"})))))

(deftest ^:unit subscription-decision
  (let [decide #(sse/subscription-decision % (fn [t] (not= "secret" t)))]
    (is (= {:ok ["a" "b"]} (decide ["a" "b" "a"])))
    (testing "errors"
      (is (= :validation-error (get-in (decide []) [:error :type])))
      (is (= :validation-error (get-in (decide ["a" ""]) [:error :type])))
      (is (= :forbidden (get-in (decide ["a" "secret"]) [:error :type]))))))

(deftest ^:unit request-topics
  (is (= ["a"] (sse/request-topics {"topic" "a"})))
  (is (= ["a" "b"] (sse/request-topics {"topic" ["a" "b"]})))
  (is (= [] (sse/request-topics {}))))
