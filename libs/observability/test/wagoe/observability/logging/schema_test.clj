(ns wagoe.observability.logging.schema-test
  (:require [clojure.test :refer [deftest is]]
            [wagoe.observability.logging.schema :as sut]))

(deftest ^:unit the-providers-logging-boots-validate
  ;; BOU-1098: the schema knew neither :slf4j nor :no-op, the two configs use.
  (is (sut/validate-logging-config {:provider :slf4j :level :info}))
  (is (sut/validate-logging-config {:provider :no-op}))
  (is (sut/validate-logging-config {:provider :stdout :level :debug}))
  (is (not (sut/validate-logging-config {:provider :wire}))))
