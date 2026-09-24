(ns wagoe.devtools.shell.dashboard.pages.schemas-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.devtools.shell.dashboard.pages.schemas :as schemas]))

(deftest ^:unit schema-namespace?-test
  ;; The scan used to match `wagoe.<module>.schema` exactly, so the browser
  ;; could never show the schemas of the application being built (BOU-509).
  (testing "an application's own schema namespace counts"
    (is (schemas/schema-namespace? 'invoicing.invoice.schema))
    (is (schemas/schema-namespace? 'acme.billing.schema)))

  (testing "the framework's own still count, nested ones included"
    (is (schemas/schema-namespace? 'wagoe.user.schema))
    (is (schemas/schema-namespace? 'wagoe.admin.core.schema)))

  (testing "namespaces that merely mention schema do not"
    (is (not (schemas/schema-namespace? 'wagoe.admin.core.schema-introspection)))
    (is (not (schemas/schema-namespace? 'invoicing.invoice.schemas)))
    (is (not (schemas/schema-namespace? 'wagoe.user.core.user)))))
