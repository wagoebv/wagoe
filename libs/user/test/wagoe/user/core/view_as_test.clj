(ns wagoe.user.core.view-as-test
  (:require [clojure.test :refer [deftest is]]
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
