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

(deftest ^:unit schema-key-test
  ;; The key used to be :<segment before .schema>/<Var>, so acme.user.schema/User
  ;; and wagoe.user.schema/User were both :user/User. The scan now includes
  ;; every loaded *.schema namespace (BOU-509), which made that collision
  ;; reachable, and discover-all-schemas merges — so one of the two silently
  ;; disappeared from the browser. Keys carry the whole source namespace.
  (testing "same module segment and var name, different namespaces, distinct keys"
    (is (not= (schemas/schema-key 'acme.user.schema 'User)
              (schemas/schema-key 'wagoe.user.schema 'User))))

  (testing "nested framework namespaces do not collapse onto their last segment"
    (is (not= (schemas/schema-key 'wagoe.admin.core.schema 'Entity)
              (schemas/schema-key 'wagoe.other.core.schema 'Entity))))

  (testing "the key round-trips through the ?schema=<ns>/<name> query parameter"
    (let [k (schemas/schema-key 'invoicing.invoice.schema 'Invoice)]
      (is (= k (keyword (str (namespace k) "/" (name k))))))))
