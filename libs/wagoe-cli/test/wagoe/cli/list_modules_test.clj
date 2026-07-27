(ns wagoe.cli.list-modules-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [wagoe.cli.list-modules :as lm]))

(deftest ^:unit human-table-test
  (testing "table output contains module names"
    (let [out (with-out-str (lm/print-table))]
      (is (str/includes? out "payments"))
      (is (str/includes? out "storage"))
      (is (str/includes? out "wagoe add payments"))))

  (testing "table output contains header"
    (let [out (with-out-str (lm/print-table))]
      (is (str/includes? out "Module"))
      (is (str/includes? out "Description")))))

(deftest ^:unit json-output-test
  (testing "JSON output is valid JSON"
    (let [out (with-out-str (lm/print-json))
          parsed (json/parse-string out true)]
      (is (map? parsed))
      (is (contains? parsed :modules))
      (is (contains? parsed :cli-version))
      (is (contains? parsed :catalogue-version))))

  (testing "JSON modules include required fields"
    (let [out (with-out-str (lm/print-json))
          parsed (json/parse-string out true)
          payments (first (filter #(= "payments" (:name %)) (:modules parsed)))]
      (is payments)
      (is (= "optional" (:category payments)))
      (is (string? (:description payments)))
      (is (string? (:add-command payments)))
      (is (string? (:docs-url payments)))))

  (testing "JSON includes core modules"
    (let [out (with-out-str (lm/print-json))
          parsed (json/parse-string out true)
          names (set (map :name (:modules parsed)))]
      (is (contains? names "core"))
      (is (contains? names "platform")))))
