(ns wagoe.devtools.shell.fcis-checker-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.shell.fcis-checker :as fcis]))

(deftest ^:unit core-ns-and-shell-ns-test
  (testing "is-core-ns? identifies core namespaces"
    (is (true? (fcis/core-ns? "wagoe.product.core.validation")))
    (is (true? (fcis/core-ns? "wagoe.user.core.service")))
    (is (false? (fcis/core-ns? "wagoe.product.shell.persistence")))
    (is (false? (fcis/core-ns? "wagoe.platform.core.http"))))

  (testing "is-shell-ns? identifies shell namespaces"
    (is (true? (fcis/shell-ns? "wagoe.product.shell.persistence")))
    (is (false? (fcis/shell-ns? "wagoe.product.core.validation")))
    (is (false? (fcis/shell-ns? "wagoe.platform.shell.interceptors")))))
