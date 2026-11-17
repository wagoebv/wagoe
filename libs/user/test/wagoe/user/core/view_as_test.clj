(ns wagoe.user.core.view-as-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.view-as :as sut])
  (:import [java.time Instant]))

(deftest ^:unit a-view-is-active-until-its-time
  (let [now (Instant/now)]
    (is (sut/active? {:view-as-user-id 1 :view-as-until (.plusSeconds now 60)} now))
    (is (not (sut/active? {:view-as-user-id 1 :view-as-until (.minusSeconds now 1)} now)))
    (is (not (sut/active? {:view-as-until (.plusSeconds now 60)} now)))
    (is (not (sut/active? {} now)))))

(deftest ^:unit only-reads-pass-while-viewing
  (is (every? (complement sut/writes?) [:get :head :options]))
  (is (every? sut/writes? [:post :put :patch :delete])))

(deftest ^:unit who-may-be-viewed-as
  (let [now   (Instant/now)
        admin {:id 1 :role :admin}
        ada   {:id 2 :role :user :active true}
        why   #(some-> (sut/refusal %1 %2 %3 now) :error ((juxt :type :reason)))]
    (is (nil? (why admin ada {})))
    (is (= [:forbidden :nested] (why admin ada {:view-as-user-id 3 :view-as-until (.plusSeconds now 60)})))
    (is (= [:forbidden :not-admin] (why {:id 1 :role :user} ada {})))
    (is (= [:not-found nil] (why admin nil {})))
    (is (= [:forbidden :self] (why admin admin {})))
    (is (= [:forbidden :admin] (why admin (assoc ada :role :admin) {})))
    (is (= [:forbidden :inactive] (why admin (assoc ada :active false) {})))
    (testing "a tenant's administrator views only their tenant's users"
      (is (= [:not-found nil] (why (assoc admin :tenant-id 7) (assoc ada :tenant-id 8) {})))
      (is (nil? (why (assoc admin :tenant-id 7) (assoc ada :tenant-id 7) {})))
      (is (nil? (why admin (assoc ada :tenant-id 8) {})) "a platform admin any"))))
