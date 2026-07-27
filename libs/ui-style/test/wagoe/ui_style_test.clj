(ns wagoe.ui-style-test
  (:require [wagoe.ui-style :as sut]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit css-bundle-selection-test
  (testing "known bundle keys return configured stacks"
    (is (= sut/base-css (sut/bundle :base)))
    (is (= sut/pilot-css (sut/bundle :pilot)))
    (is (= sut/admin-pilot-css (sut/bundle :admin-pilot))))
  (testing "unknown bundle key falls back to base"
    (is (= sut/base-css (sut/bundle :unknown)))))

(deftest ^:unit js-bundle-selection-test
  (testing "known bundle keys return configured stacks"
    (is (= sut/base-js (sut/js-bundle :base)))
    (is (= sut/pilot-js (sut/js-bundle :pilot)))
    (is (= sut/admin-pilot-js (sut/js-bundle :admin-pilot))))
  (testing "unknown bundle key falls back to base"
    (is (= sut/base-js (sut/js-bundle :unknown)))))
