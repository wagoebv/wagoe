(ns wagoe.tenant.shell.tenant-iteration-test
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.tenant.shell.tenant-iteration :as sut]
            [wagoe.platform.database :as db]
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

(defn- tenant-service
  "Lists `n` active tenants a page at a time, recording each request."
  [n asked]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ports/ITenantService
    (list-tenants [_ {:keys [status limit offset] :as opts}]
      (swap! asked conj opts)
      (when (= :active status)
        (->> (range n)
             (map (fn [i] {:id (str "t" i) :schema-name (str "tenant_" i)}))
             (drop offset)
             (take limit))))))

(deftest ^:unit for-each-tenant-runs-f-in-every-active-tenant
  (let [asked  (atom [])
        seen   (atom [])
        result (sut/for-each-tenant
                (tenant-service 101 asked) (fake-provider []) {:datasource :ds}
                (fn [tenant]
                  (swap! seen conj [(:id tenant) db/*tenant-id*])
                  (when (= "t3" (:id tenant)) (throw (ex-info "boom" {})))
                  :ok))]
    (testing "a page at a time, every tenant, with its id bound"
      (is (= [0 100] (map :offset @asked)))
      (is (= 101 (count @seen)))
      (is (every? (fn [[id bound]] (= id bound)) @seen)))
    (testing "one tenant's failure is counted, and the rest run"
      (is (= 100 (:processed result)))
      (is (= 1 (:failed result)))
      (is (= 100 (count (:results result)))))))
