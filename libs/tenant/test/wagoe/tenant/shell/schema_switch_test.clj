(ns wagoe.tenant.shell.schema-switch-test
  "Tenant work lands in its own schema or nowhere (BOU-988)."
  (:require [clojure.test :refer [deftest is testing]]
            [support.embedded-pg :as epg]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.tenant.shell.provisioning :as sut]))

(defn- first-schema [ctx]
  (:schema (db/execute-one! ctx ["SELECT (current_schemas(false))[1] AS schema"])))

(defn- error-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest ^:integration ^:security tenant-work-lands-in-its-own-schema-or-nowhere
  (let [pg  (epg/start!)
        ctx (epg/db-context pg)]
    (try
      (db/execute-ddl! ctx "CREATE SCHEMA tenant_a")
      (db/execute-ddl! ctx "CREATE SCHEMA tenant_b")
      (testing "switching schema inside an open transaction is refused"
        (is (= :configuration-error
               (error-type #(sut/call-in-schema ctx "tenant_a"
                                                (fn [_] (sut/call-in-schema ctx "tenant_b" (fn [_] nil))))))))
      (testing "the same schema joins, and stays"
        (is (= "tenant_a"
               (sut/call-in-schema ctx "tenant_a"
                                   (fn [_] (sut/call-in-schema ctx "tenant_a" (fn [_] nil))
                                     (first-schema ctx))))))
      (testing "a schema that does not exist is refused, not served from public"
        (is (= :schema-not-found
               (error-type #(sut/call-in-schema ctx "tenant_ghost" (fn [_] :ran))))))
      (finally
        (db-factory/close-db-context! ctx)
        (epg/stop! pg)))))
