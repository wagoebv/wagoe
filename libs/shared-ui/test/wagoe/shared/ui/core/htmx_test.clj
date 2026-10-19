(ns wagoe.shared.ui.core.htmx-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.shared.ui.core.htmx :as htmx]))

(deftest ^:unit oob-marks-an-element
  (is (= [:span#badge {:hx-swap-oob "true"} "3"] (htmx/oob [:span#badge "3"])))
  (is (= [:div {:hx-swap-oob "true" :id "t"} [:p "x"]] (htmx/oob [:div {:id "t"} [:p "x"]])))
  (is (= [:div {:hx-swap-oob "afterbegin:#toasts"}] (htmx/oob [:div {:hx-swap-oob "afterbegin:#toasts"}])))
  (is (= [[:tr#a {:hx-swap-oob "true"}] [:tr#b {:hx-swap-oob "true"}]] (htmx/oob (list [:tr#a] [:tr#b]))))
  (is (= "<p>raw</p>" (htmx/oob "<p>raw</p>"))))

(deftest ^:unit fragment-request-test
  (let [ids ["orders"]]
    (is (htmx/fragment-request? {"hx-request" "true" "hx-target" "orders"} ids))
    (is (not (htmx/fragment-request? {"hx-target" "orders"} ids)) "a browser gets the page")
    (is (not (htmx/fragment-request? {"hx-request" "true" "hx-target" "drawer"} ids)))
    (is (not (htmx/fragment-request? {"hx-request" "true" "hx-target" "orders"
                                      "hx-history-restore-request" "true"} ids))
        "a history restore gets the page")))

(deftest ^:unit merge-attrs-test
  (is (= [:tr {:id "r" :class "x"} [:td "1"]] (htmx/merge-attrs [:tr {:id "r"} [:td "1"]] {:class "x"})))
  (is (= [:tr {:class "x"} [:td "1"]] (htmx/merge-attrs [:tr [:td "1"]] {:class "x"}))))
