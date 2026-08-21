(ns wagoe.core.utils.validation-test
  "Unit tests for wagoe.core.utils.validation namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.transform :as mt]
            [wagoe.core.utils.validation :as validation]))

(def TestSchema
  [:map
   [:name :string]
   [:age :int]
   [:active :boolean]])

(deftest ^:unit valid-uuid?-test
  (testing "valid UUID strings"
    (is (true? (validation/valid-uuid? "123e4567-e89b-12d3-a456-426614174000"))))

  (testing "invalid UUID strings"
    (is (false? (validation/valid-uuid? "not-a-uuid")))
    (is (false? (validation/valid-uuid? "")))))

(deftest ^:unit valid-output-format?-test
  (testing "valid formats"
    (is (true? (validation/valid-output-format? "table")))
    (is (true? (validation/valid-output-format? "json"))))

  (testing "invalid formats"
    (is (false? (validation/valid-output-format? "xml")))
    (is (false? (validation/valid-output-format? "csv")))))
