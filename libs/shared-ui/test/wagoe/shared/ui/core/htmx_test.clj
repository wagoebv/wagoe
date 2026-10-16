(ns wagoe.shared.ui.core.htmx-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.shared.ui.core.htmx :as htmx]))

(deftest ^:unit oob-marks-an-element
  (is (= [:span#badge {:hx-swap-oob "true"} "3"] (htmx/oob [:span#badge "3"])))
  (is (= [:div {:hx-swap-oob "true" :id "t"} [:p "x"]] (htmx/oob [:div {:id "t"} [:p "x"]])))
  (is (= [:div {:hx-swap-oob "afterbegin:#toasts"}] (htmx/oob [:div {:hx-swap-oob "afterbegin:#toasts"}])))
  (is (= "<p>raw</p>" (htmx/oob "<p>raw</p>"))))
