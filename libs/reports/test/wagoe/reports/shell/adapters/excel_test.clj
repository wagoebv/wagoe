(ns wagoe.reports.shell.adapters.excel-test
  "Integration tests for the docjure Excel adapter.
   Generates real XLSX bytes — verifies ZIP magic bytes (XLSX = ZIP container)."
  (:require [wagoe.reports.shell.adapters.excel :as sut]
            [wagoe.reports.ports :as ports]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Helpers
;; =============================================================================

(def ^:private sample-sections-report
  {:id       :excel-test-sections
   :type     :excel
   :filename "test-output.xlsx"
   :sections [{:type    :table
               :columns [{:key :name    :label "Product"}
                         {:key :qty     :label "Qty"     :format :number}
                         {:key :price   :label "Price"   :format :currency}
                         {:key :date    :label "Date"    :format :date}]}]})

(def ^:private sample-data
  [{:name "Alpha"   :qty 10 :price 19.99  :date nil}
   {:name "Beta"    :qty 5  :price 99.00  :date nil}
   {:name "Gamma"   :qty 3  :price 4.49   :date nil}])

(def ^:private multi-sheet-report
  {:id     :excel-test-multi
   :type   :excel
   :sheets [{:name    "Products"
             :columns [{:key :name  :label "Name"}
                       {:key :price :label "Price" :format :currency}]
             :data    [{:name "X" :price 1.0}
                       {:name "Y" :price 2.0}]}
            {:name    "Summary"
             :columns [{:key :total :label "Total" :format :number}]
             :data    [{:total 3}]}]})

;; =============================================================================
;; ZIP magic bytes helper
;; =============================================================================

(defn- xlsx-magic? [^bytes bytes]
  ;; XLSX is a ZIP file. ZIP magic: 50 4B 03 04
  (and (>= (count bytes) 4)
       (= (aget bytes 0) (byte 0x50))  ; P
       (= (aget bytes 1) (byte 0x4B))  ; K
       (= (aget bytes 2) (byte 0x03))
       (= (aget bytes 3) (byte 0x04))))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest ^:integration excel-magic-bytes-test
  (testing "sections-based report produces valid XLSX bytes"
    (let [gen    (sut/create-excel-generator)
          result (ports/generate! gen sample-sections-report sample-data {})]
      (is (some? (:bytes result)))
      (is (pos? (count (:bytes result))))
      (is (xlsx-magic? (:bytes result))))))

(deftest ^:integration excel-filename-default-test
  (testing "filename defaults to <id>.xlsx when not specified"
    (let [gen  (sut/create-excel-generator)
          defn {:id       :my-excel
                :type     :excel
                :sections [{:type    :table
                            :columns [{:key :x :label "X"}]}]}
          result (ports/generate! gen defn [{:x 1}] {})]
      (is (= "my-excel.xlsx" (:filename result)))))
  (testing "explicit filename is used"
    (let [gen    (sut/create-excel-generator)
          result (ports/generate! gen sample-sections-report sample-data {})]
      (is (= "test-output.xlsx" (:filename result))))))

(deftest ^:integration excel-multi-sheet-test
  (testing "multi-sheet report produces valid XLSX bytes"
    (let [gen    (sut/create-excel-generator)
          result (ports/generate! gen multi-sheet-report nil {})]
      (is (xlsx-magic? (:bytes result))))))

(deftest ^:unit excel-supported-type-test
  (testing "supported-type? returns true for :excel"
    (is (ports/supported-type? (sut/create-excel-generator) :excel)))
  (testing "supported-type? returns false for :pdf"
    (is (not (ports/supported-type? (sut/create-excel-generator) :pdf)))))

(deftest ^:integration excel-type-in-output-test
  (testing ":type is :excel in output"
    (let [gen    (sut/create-excel-generator)
          result (ports/generate! gen sample-sections-report sample-data {})]
      (is (= :excel (:type result))))))
