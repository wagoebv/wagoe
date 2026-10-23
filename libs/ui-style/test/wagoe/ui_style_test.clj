(ns wagoe.ui-style-test
  (:require [wagoe.ui-style :as sut]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(deftest ^:unit data-table-column-widths-follow-the-column-not-its-position
  ;; Sized by :first-child/:last-child, every table without a checkbox or
  ;; actions column squeezed its first and last columns (the workflow list).
  (let [css (slurp (io/resource "public/css/app.css"))]
    (is (not (re-find #"\.data-table t[hd]:(first|last)-child" css)))
    (doseq [sel ["th.checkbox-header" "td.checkbox-cell" "th.actions-header" "td.actions-cell"]]
      (is (str/includes? css (str "table.data-table " sel)) sel))))

(deftest ^:unit asset-url-names-the-content
  (testing "a shipped asset carries a digest of its bytes"
    (is (re-matches #"/js/forms\.js\?v=[0-9a-f]{32}" (sut/asset-url "/js/forms.js"))))
  (testing "the same bytes give the same URL"
    (is (= (sut/asset-url "/js/forms.js") (sut/asset-url "/js/forms.js"))))
  (testing "anything not under public/ is left alone"
    (is (= "/css/no-such.css" (sut/asset-url "/css/no-such.css")))
    (is (= "https://cdn.example/x.js" (sut/asset-url "https://cdn.example/x.js")))
    (is (nil? (sut/asset-url nil)))))
