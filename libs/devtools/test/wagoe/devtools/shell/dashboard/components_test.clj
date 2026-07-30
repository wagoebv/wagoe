(ns wagoe.devtools.shell.dashboard.components-test
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.devtools.shell.dashboard.components :as c]))

(deftest ^:unit stat-card-test
  (testing "renders stat card with label and value"
    (let [hiccup (c/stat-card {:label "Components" :value 12})]
      (is (vector? hiccup))
      (is (some #(= 12 %) (flatten hiccup)))
      (is (some #(= "Components" %) (flatten hiccup)))))

  (testing "renders with optional sub text"
    (let [hiccup (c/stat-card {:label "Errors" :value 0 :sub "last error: 2h ago"})]
      (is (some #(= "last error: 2h ago" %) (flatten hiccup))))))

(deftest ^:unit method-badge-test
  (testing "renders color-coded method badges"
    (let [get-badge (c/method-badge :get)
          post-badge (c/method-badge :post)]
      (is (some #(= "GET" %) (flatten get-badge)))
      (is (some #(= "POST" %) (flatten post-badge))))))

(deftest ^:unit data-table-test
  (testing "renders table with header and rows"
    (let [hiccup (c/data-table
                  {:columns ["Name" "Value"]
                   :col-template "1fr 1fr"
                   :rows [{:cells ["foo" "bar"]}
                          {:cells ["baz" "qux"]}]})]
      (is (vector? hiccup))
      (is (some #(= "foo" %) (flatten hiccup)))
      (is (some #(= "qux" %) (flatten hiccup))))))
