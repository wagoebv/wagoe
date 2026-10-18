(ns wagoe.tools.text-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.tools.text :as text])
  (:import (java.util Locale)))

(deftest ^:unit ignores-a-turkish-default-locale-test
  (let [default (Locale/getDefault)]
    (Locale/setDefault (Locale/forLanguageTag "tr-TR"))
    (try
      (is (= "invoice-id" (text/lower-case "INVOICE-ID")))
      (is (= "ID" (text/upper-case "id")))
      (is (= "Item" (text/capitalize "ITEM")))
      (finally (Locale/setDefault default)))))
