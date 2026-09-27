(ns wagoe.scaffolder.field-keeps-tests-green-test
  "`bb scaffold field --required` adds a NOT NULL column. The repository and
   workflow tests generated before it insert rows without it, so they failed on
   the first run after the field (BOU-581). `field` now gives them a value."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.scaffolder.cli :as cli]
            [wagoe.scaffolder.multi-entity-test :as multi]
            [wagoe.scaffolder.shell.service :as service]
            [wagoe.workflow.shell.module-wiring]))

(def ^:private svc (service/create-scaffolder-service))

(deftest ^:integration the-generated-tests-pass-after-a-required-field
  (let [base "bou581f"
        dir  (#'multi/temp-dir)
        run  (fn [& args]
               (with-out-str (cli/run-cli! svc (into (vec args) ["--base-ns" base "--output-dir" (.getPath dir)]))))]
    (run "generate" "--module-name" "billing" "--entity" "Invoice" "--field" "number:string:required"
         "--workflow" "status:draft>sent")
    (run "entity" "--module-name" "billing" "--entity" "InvoiceLineItem" "--belongs-to" "invoice"
         "--field" "description:string:required")
    (doseq [entity ["Invoice" "InvoiceLineItem"]
            [field type & more] [["unit-price" "int"]
                                 ["amount" "decimal"]
                                 ["paid" "boolean"]
                                 ["due-on" "date"]
                                 ["sent-at" "inst"]
                                 ["extra" "json"]
                                 ["kind" "enum" "--enum-values" "normal,credit"]
                                 ["customer" "relation" "--references" "invoice"]]]
      (apply run "field" "--module-name" "billing" "--entity" entity "--name" field "--type" type "--required" more))
    (testing "the tests `field` left behind name every new field"
      (let [repo (slurp (io/file dir "test" base "billing/shell/invoice_line_item_repository_test.clj"))]
        (is (str/includes? repo ":unit-price 1") repo)
        (is (str/includes? repo ":sent-at (Instant/now)") repo)))
    (let [{:keys [fail error]} (#'multi/load-and-test! dir)]
      (is (= 0 fail))
      (is (= 0 error)))))
