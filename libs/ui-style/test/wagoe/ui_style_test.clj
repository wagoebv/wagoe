(ns wagoe.ui-style-test
  (:require [wagoe.ui-style :as sut]
            [clojure.java.io :as io]
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

(deftest ^:unit every-bundled-asset-ships
  (doseq [path (distinct (concat (mapcat val sut/css-bundles) (mapcat val sut/js-bundles)))]
    (is (some? (io/resource (str "public" path))) path)))

(deftest ^:unit the-sse-extension-loads-after-htmx
  (doseq [[k js] sut/js-bundles]
    (is (= 1 (- (.indexOf ^java.util.List js "/js/htmx-ext-sse.min.js")
                (.indexOf ^java.util.List js "/js/htmx.min.js")))
        (str k))))
