(ns wagoe.user.core.admin-guard-test
  "BOU-665: no admin removes their own rights, and the last admin keeps theirs."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.user.core.user :as sut]))

(def ^:private admin {:id 1 :role :admin :active true :deleted-at nil})

(deftest ^:unit removes-admin
  (testing "a demotion, a deactivation and a delete take admin rights"
    (is (sut/removes-admin? admin {:role :user}))
    (is (sut/removes-admin? admin {:active false}))
    (is (sut/removes-admin? admin nil)))
  (testing "other edits do not"
    (is (not (sut/removes-admin? admin {:name "New"})))
    (is (not (sut/removes-admin? admin {:role "admin"})))
    (is (not (sut/removes-admin? {:id 2 :role :user :active true} {:role :viewer})))
    (is (not (sut/removes-admin? (assoc admin :active false) {:role :user})))))

(deftest ^:unit admin-removal-refusal
  (is (= :own-admin-rights (get-in (sut/admin-removal-refusal 1 admin 3) [:error :reason])))
  (is (= :last-admin (get-in (sut/admin-removal-refusal 2 admin 0) [:error :reason])))
  (is (= :conflict (get-in (sut/admin-removal-refusal nil admin 0) [:error :type])))
  (is (nil? (sut/admin-removal-refusal 2 admin 1))))
