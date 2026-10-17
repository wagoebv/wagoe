(ns wagoe.webhooks.core.delivery-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [wagoe.webhooks.core.delivery :as d]))

(deftest ^:unit topic-globs
  (testing "matches"
    (is (d/pattern-matches? "*" :orders/placed))
    (is (d/pattern-matches? "orders/*" :orders/placed))
    (is (d/pattern-matches? "orders/placed" "orders/placed"))
    (is (d/pattern-matches? "*.created" :invoice.created))
    (is (d/pattern-matches? "orders" :orders)))
  (testing "does not match"
    (is (not (d/pattern-matches? "orders/*" :users/created)))
    (is (not (d/pattern-matches? "orders/placed" :orders/placed-twice)))
    (is (not (d/pattern-matches? "orders.*" :ordersXplaced)) "a dot is literal")
    (is (not (d/pattern-matches? nil :orders)))))

(def subs*
  [{:id "global" :tenant-id nil :event-pattern "*" :active? true}
   {:id "t1" :tenant-id "t1" :event-pattern "orders/*" :active? true}
   {:id "t2" :tenant-id "t2" :event-pattern "orders/*" :active? true}
   {:id "off" :tenant-id "t1" :event-pattern "*" :active? false}
   {:id "users" :tenant-id "t1" :event-pattern "users/*" :active? true}])

(defn- ids [topic event] (set (map :id (d/matching-subscriptions subs* topic event))))

(deftest ^:unit per-tenant-filtering
  (is (= #{"global" "t1"} (ids :orders/placed {:tenant-id "t1"})))
  (is (= #{"global" "t2"} (ids :orders/placed {:tenant-id "t2"})))
  (is (= #{"global"} (ids :orders/placed {})) "an event without a tenant reaches no tenant")
  (is (= #{"global" "users"} (ids :users/created {:tenant-id "t1"}))))

(deftest ^:unit payload-is-json-of-the-envelope
  (let [body (d/payload-body :orders/placed {:id "e1" :type :order/placed :source :orders
                                             :published-at (java.time.Instant/parse "2026-01-01T00:00:00Z")
                                             :tenant-id "t1" :payload {:n 1}})]
    (is (= {"id" "e1" "topic" "orders/placed" "type" "order/placed" "source" "orders"
            "published-at" "2026-01-01T00:00:00Z" "tenant-id" "t1" "payload" {"n" 1}}
           (json/parse-string body)))))

(deftest ^:unit url-shape
  (is (nil? (d/url-problem "https://example.com/hook" {:require-https? true})))
  (is (nil? (d/url-problem "http://example.com/hook" {:require-https? false})))
  (is (= "URL must use https" (d/url-problem "http://example.com/hook" {:require-https? true})))
  (is (= "URL must use http or https" (d/url-problem "ftp://example.com" {})))
  (is (= "URL must not carry credentials" (d/url-problem "https://u:p@example.com" {})))
  (is (= "URL is required" (d/url-problem "" {})))
  (is (= "::1" (d/url-host "http://[::1]:8080/x"))))

(deftest ^:unit attempt-outcomes
  (is (= :delivered (d/attempt-status {:status 204} 1 3)))
  (is (= :failed (d/attempt-status {:status 500} 1 3)))
  (is (= :failed (d/attempt-status {:status 302} 1 3)) "a redirect is not followed")
  (is (= :dead (d/attempt-status {:error {:message "x"}} 3 3)))
  (is (= "abc" (d/truncate "abcdef" 3))))
