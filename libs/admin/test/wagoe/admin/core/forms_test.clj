(ns wagoe.admin.core.forms-test
  (:require [wagoe.admin.core.forms :as forms]
            [clojure.test :refer [deftest is testing]]))

^{:kaocha.testable/meta {:unit true :admin true}}

(def ^:private invoice
  {:editable-fields [:number :token]
   :fields          {:number {:widget :text-input} :token {:widget :hidden}}
   :has-many        [{:entity :lines :foreign-key :invoice_id :label "Lines" :editable true :min 1}
                     {:entity :notes :foreign-key :invoice-id :label "Notes" :editable true}
                     {:entity :tags :foreign-key :invoice-id :label "Tags" :min 1}]})

(def ^:private configs
  {:lines {:label "Lines" :editable-fields [:invoice-id :description :billable]
           :fields {:description {:type :string} :billable {:type :boolean}}}
   :notes {:editable-fields [:invoice-id :body]}
   :tags  {:editable-fields [:invoice-id :name]}})

(deftest ^:unit off-form-errors-test
  (is (= {:status ["Field is required"] :token ["x"]}
         (forms/off-form-errors invoice {:number ["x"] :status ["Field is required"] :token ["x"]}))
      "a hidden input is not shown either"))

(deftest ^:unit nested-relationships-test
  (let [[lines :as rels] (forms/nested-relationships invoice configs)]
    (testing "only an editable has-many with a :min is created with its parent"
      (is (= [:lines] (map :entity rels))))
    (testing "its row is the child's form without the foreign key"
      (is (= [:description :billable] (:fields lines)))
      (is (= :invoice-id (:fk lines))))
    (testing "not when the child has its own create flow, or none"
      (is (empty? (forms/nested-relationships invoice (assoc-in configs [:lines :create-redirect-url] "/x"))))
      (is (empty? (forms/nested-relationships invoice (assoc-in configs [:lines :permissions :create] false))))
      (is (empty? (forms/nested-relationships invoice (dissoc configs :lines)))))))

(deftest ^:unit split-child-params-test
  (let [[parent rows] (forms/split-child-params
                       {"number"                        "INV-1"
                        (forms/child-param :lines 10 :description) "second"
                        (forms/child-param :lines 2 :description)  "first"
                        (forms/child-param :lines 2 :billable)     "true"
                        :__child.lines.3.description    ""})]
    (is (= {"number" "INV-1"} parent) "the parent keeps only its own fields")
    (is (= [["2" {"description" "first" "billable" "true"}]
            ["3" {"description" ""}]
            ["10" {"description" "second"}]]
           (get rows :lines))
        "rows in index order, keyword keys too")))

(deftest ^:unit too-few-test
  (let [rels (forms/nested-relationships invoice configs)
        row  (fn [d] {"description" d "billable" "false"})]
    (testing "a blank row does not count, whatever its checkbox says"
      (is (= {:lines {:min 1 :count 0 :label "Lines"}}
             (forms/too-few rels {:lines [["0" (row "")]]}))))
    (is (= {:lines {:min 1 :count 0 :label "Lines"}} (forms/too-few rels {})))
    (is (= {} (forms/too-few rels {:lines [["0" (row "Hours")]]})))))

(deftest ^:unit a-row-index-that-is-not-a-small-number-is-dropped
  ;; parse-long answered nil for it, and padding the rows threw: a 500.
  (let [[parent rows] (forms/split-child-params
                       {"number"                                          "INV-1"
                        "__child.lines.99999999999999999999.description" "x"
                        "__child.lines.-1.description"                    "x"
                        "__child.lines.1234567890.description"            "x"
                        (forms/child-param :lines 999999999 :description) "kept"})]
    (is (= {"number" "INV-1"} parent) "not a parent field either")
    (is (= [["999999999" {"description" "kept"}]] (:lines rows)))))

(deftest ^:unit duplicate-row-test
  (let [[rel] (forms/nested-relationships invoice configs)]
    (testing "one row"
      (is (not (forms/duplicate-row? rel {"description" "a" "billable" "false"})))
      (is (not (forms/duplicate-row? rel {"description" "a" "billable" ["false" "true"]}))
          "a checked checkbox sends its hidden field too"))
    (testing "two rows under one index send a field twice"
      (is (forms/duplicate-row? rel {"description" ["a" "b"]}))
      (is (forms/duplicate-row? rel {"billable" ["false" "false"]}))
      (is (forms/duplicate-row? rel {"billable" ["false" "true" "false"]})))))

(deftest ^:unit too-many-test
  (let [rels (forms/nested-relationships invoice configs)
        rows (fn [n] {:lines (vec (for [i (range n)] [(str i) {"description" "x"}]))})]
    (is (= {} (forms/too-many rels (rows forms/max-child-rows))))
    (is (= {:lines {:max forms/max-child-rows :count (inc forms/max-child-rows) :label "Lines"}}
           (forms/too-many rels (rows (inc forms/max-child-rows)))))))
