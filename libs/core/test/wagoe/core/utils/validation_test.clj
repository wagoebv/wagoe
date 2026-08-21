(ns wagoe.core.utils.validation-test
  "The value predicates. The schema-validation tests that used to live here
   covered a second implementation of wagoe.core.validation's API, deleted in
   BOU-323 — the Malli transformer and the schema they needed went with them."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.core.utils.validation :as validation]))

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
