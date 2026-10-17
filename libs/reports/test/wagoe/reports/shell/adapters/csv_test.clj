(ns wagoe.reports.shell.adapters.csv-test
  (:require [wagoe.reports.shell.adapters.csv :as sut]
            [wagoe.reports.ports :as ports]
            [wagoe.reports.schema :as schema]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.charset StandardCharsets]))

(def ^:private report
  {:id       :csv-test
   :type     :csv
   :sections [{:type :table :columns [{:key :city :label "Stad"}]}]})

(defn- text [^bytes b] (String. b StandardCharsets/UTF_8))

(deftest ^:unit generates-utf-8-csv
  (let [out (ports/generate! (sut/create-csv-generator) report [{:city "Zoë"}] {})]
    (is (schema/valid-report-output? out))
    (is (= :csv (:type out)))
    (is (= "csv-test.csv" (:filename out)))
    (is (= "Stad\r\nZoë\r\n" (text (:bytes out))))
    (testing "no byte order mark unless asked"
      (is (= 0x53 (aget ^bytes (:bytes out) 0))))))

(deftest ^:unit bom-on-request
  (let [^bytes b (:bytes (ports/generate! (sut/create-csv-generator) report [] {:bom? true}))]
    (is (= [0xEF 0xBB 0xBF] (map #(bit-and % 0xFF) (take 3 b))))))

(deftest ^:unit filename-from-the-definition
  (is (= "x.csv" (:filename (ports/generate! (sut/create-csv-generator)
                                             (assoc report :filename "x.csv") [] {})))))

(deftest ^:unit supports-only-csv
  (is (ports/supported-type? (sut/create-csv-generator) :csv))
  (is (not (ports/supported-type? (sut/create-csv-generator) :pdf))))

(deftest ^:unit no-table-is-a-validation-error
  (is (= :validation-error
         (try (ports/generate! (sut/create-csv-generator) (dissoc report :sections) [{:city "x"}] {})
              nil
              (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
