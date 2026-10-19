(ns wagoe.ui-style-test
  (:require [wagoe.ui-style :as sut]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(deftest ^:unit css-bundle-selection-test
  (testing "known bundle keys return configured stacks"
    (is (= sut/pilot-css (sut/bundle :pilot)))
    (is (= sut/admin-pilot-css (sut/bundle :admin-pilot))))
  (testing "unknown bundle key falls back to pilot, the default"
    (is (= sut/pilot-css (sut/bundle :unknown))))
  (testing "the removed :base bundle serves pilot"
    (is (= sut/pilot-css (sut/bundle :base)))))

(deftest ^:unit unknown-key-warns-once-test
  (let [k     (keyword (str "typo-" (random-uuid)))
        warn! #(let [w (java.io.StringWriter.)]
                 (binding [*err* w] (sut/bundle k) (sut/js-bundle k))
                 (str w))]
    (is (re-find #"unknown bundle" (warn!)))
    (is (= "" (warn!)))))

(deftest ^:unit js-bundle-selection-test
  (testing "known bundle keys return configured stacks"
    (is (= sut/pilot-js (sut/js-bundle :pilot)))
    (is (= sut/admin-pilot-js (sut/js-bundle :admin-pilot))))
  (testing "unknown bundle key falls back to pilot, the default"
    (is (= sut/pilot-js (sut/js-bundle :unknown)))))

(deftest ^:unit every-bundled-asset-ships
  (doseq [path (distinct (concat (mapcat val sut/css-bundles) (mapcat val sut/js-bundles)))]
    (is (some? (io/resource (str "public" path))) path)))

(deftest ^:unit the-sse-extension-loads-after-htmx
  (doseq [[k js] sut/js-bundles]
    (is (= 1 (- (.indexOf ^java.util.List js "/js/htmx-ext-sse.min.js")
                (.indexOf ^java.util.List js "/js/htmx.min.js")))
        (str k))))
