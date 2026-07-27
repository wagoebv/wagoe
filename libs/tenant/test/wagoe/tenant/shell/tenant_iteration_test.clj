(ns wagoe.tenant.shell.tenant-iteration-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tenant.shell.tenant-iteration :as sut]
            [wagoe.tenant.ports :as ports]))

(defn- fake-provider [schemas]
  (reify ports/ITenantSchemaProvider
    (with-tenant-schema [_ _db-ctx schema f] (f schema))
    (tenant-provisioned? [_ _db-ctx _tenant] true)
    (list-tenant-schemas [_ _db-ctx] schemas)))

(deftest ^:unit for-each-tenant-schema-runs-f-per-schema
  (testing "f runs once per provisioned schema, inside that schema"
    (let [seen     (atom [])
          provider (fake-provider ["tenant_a" "tenant_b"])
          result   (sut/for-each-tenant-schema
                    provider {:datasource :ds}
                    (fn [schema] (swap! seen conj schema) :ok))]
      (is (= ["tenant_a" "tenant_b"] @seen))
      (is (= 2 (:processed result)))
      (is (= 0 (:failed result))))))

(deftest ^:unit for-each-tenant-schema-isolates-failures
  (testing "one tenant's failure does not abort the others"
    (let [seen     (atom [])
          provider (fake-provider ["tenant_a" "tenant_b"])
          result   (sut/for-each-tenant-schema
                    provider {:datasource :ds}
                    (fn [schema]
                      (swap! seen conj schema)
                      (when (= "tenant_a" schema) (throw (ex-info "boom" {})))
                      :ok))]
      (is (= ["tenant_a" "tenant_b"] @seen))
      (is (= 1 (:processed result)))
      (is (= 1 (:failed result))))))
