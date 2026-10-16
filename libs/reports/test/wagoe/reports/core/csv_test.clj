(ns wagoe.reports.core.csv-test
  (:require [wagoe.reports.core.csv :as sut]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time ZoneId]))

(def ^:private ctx {:zone-id (ZoneId/of "UTC")})

(deftest ^:unit quote-field-follows-rfc-4180
  (is (= "plain" (sut/quote-field "plain")))
  (is (= "\"a,b\"" (sut/quote-field "a,b")))
  (is (= "\"say \"\"hi\"\"\"" (sut/quote-field "say \"hi\"")))
  (is (= "\"two\r\nlines\"" (sut/quote-field "two\r\nlines")))
  (is (= "\"lf\nonly\"" (sut/quote-field "lf\nonly"))))

(deftest ^:unit cells-a-spreadsheet-would-evaluate-are-neutralised
  (doseq [s ["=1+1" "+1" "-1" "@SUM(A1)" "\tx" "\rx"]]
    (is (= (str "'" s) (sut/neutralise-formula s)) (pr-str s)))
  (is (= "safe" (sut/neutralise-formula "safe")))
  (is (= "" (sut/neutralise-formula ""))))

(deftest ^:unit csv-from-the-first-table-section
  (let [sections [{:type :header :content "ignored"}
                  {:type    :table
                   :columns [{:key :name :label "Name"}
                             {:key :qty :label "Qty" :format :number}
                             {:key :delta :label "Delta"}]}
                  {:type :table :columns [{:key :other :label "Other"}]}]
        data     [{:name "Widget, large" :qty 2 :delta -5}
                  {:name "=HYPERLINK(\"x\")" :qty nil :delta 3}]]
    (is (= (str "Name,Qty,Delta\r\n"
                "\"Widget, large\",2.0,-5\r\n"
                "\"'=HYPERLINK(\"\"x\"\")\",0.0,3\r\n")
           (sut/->csv sections data ctx)))
    (testing "a negative number stays a number"
      (is (= "-5" (get-in (sut/csv-rows (:columns (second sections)) data ctx) [1 2]))))))

(deftest ^:unit no-data-still-writes-the-header
  (is (= "A\r\n" (sut/->csv [{:type :table :columns [{:key :a :label "A"}]}] nil ctx))))
