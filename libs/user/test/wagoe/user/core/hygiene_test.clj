(ns wagoe.user.core.hygiene-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.group :as group]
            [wagoe.user.core.hygiene :as sut]))

(deftest ^:unit groups-are-signalled-by-their-members
  (is (= #{:empty} (sut/group-signals {:id (random-uuid) :member-count 0 :role-count 1})))
  (is (= #{:single-member} (sut/group-signals {:id (random-uuid) :member-count 1})))
  (is (= #{} (sut/group-signals {:id (random-uuid) :member-count 2})))
  (testing "Everyone lists no members: it is signalled when it gives roles"
    (is (= #{:everyone} (sut/group-signals {:id group/everyone-id :member-count 0 :role-count 1})))
    (is (= #{} (sut/group-signals {:id group/everyone-id :member-count 0 :role-count 0})))))

(def ^:private roles
  [{:name :orders/viewer :permissions #{:orders/read} :source :code}
   {:name :orders/editor :permissions #{:orders/read :orders/write} :source :code}
   {:name :orders/reader :permissions #{:orders/read} :source :managed}
   {:name :orders/clerk :permissions #{:orders/read :orders/write :orders/ship} :source :managed}
   {:name :orders/shipper :permissions #{:orders/ship :orders/pack} :source :managed}])

(deftest ^:unit a-managed-role-near-a-code-role-is-signalled
  (let [by-name (into {} (map (juxt :name identity)) (sut/role-signals roles []))]
    (testing "the same permissions, or one more or fewer"
      (is (= [:orders/viewer :orders/editor] (:near-duplicates (by-name :orders/reader))))
      (is (= [:orders/editor] (:near-duplicates (by-name :orders/clerk)))))
    (testing "two apart is not near"
      (is (empty? (:near-duplicates (by-name :orders/shipper)))))
    (testing "an empty managed role is near nothing"
      (is (nil? (sut/near-duplicates {:name :orders/draft :permissions #{} :source :managed} roles))))
    (testing "a code role is never a near duplicate"
      (is (nil? (:near-duplicates (by-name :orders/viewer)))))))

(deftest ^:unit unused-roles-and-everyone-are-signalled
  (let [assignments [{:role :orders/reader :group-id (random-uuid)}
                     {:role :orders/viewer :group-id group/everyone-id :everyone? true}]
        signals     (into {} (map (juxt :name :signals)) (sut/role-signals roles assignments))]
    (is (= #{:near-duplicate} (signals :orders/reader)))
    (is (= #{:unused} (signals :orders/shipper)))
    (is (= #{:everyone} (signals :orders/viewer)))
    (is (= #{} (signals :orders/editor)) "an unassigned code role comes with its module: not signalled")))

(deftest ^:unit permissions-nobody-holds-are-signalled
  (let [assignments [{:role :orders/viewer :everyone? true} {:role :orders/clerk}]]
    (is (= #{:unheld} (sut/permission-signals [:orders/shipper] assignments)))
    (is (= #{:unheld} (sut/permission-signals [] assignments)))
    (is (= #{} (sut/permission-signals [:orders/clerk] assignments)))
    (is (= #{:everyone} (sut/permission-signals [:orders/viewer :orders/clerk] assignments)))))
