(ns wagoe.platform.shell.adapters.database.factory-test
  (:require [wagoe.platform.shell.adapters.database.factory :as sut]
            [wagoe.platform.shell.adapters.database.common.core :as db-core]
            [wagoe.platform.ports.database :as protocols]
            [clojure.test :refer [deftest is testing]]))

(defn- adapter-stub
  [dialect jdbc-driver]
  (reify protocols/DBAdapter
    (dialect [_] dialect)
    (jdbc-driver [_] jdbc-driver)
    (jdbc-url [_ _] nil)
    (pool-defaults [_] {})
    (init-connection! [_ _ _] nil)
    (build-where [_ _] nil)
    (boolean->db [_ value] value)
    (db->boolean [_ value] value)
    (table-exists? [_ _ _] false)
    (get-table-info [_ _ _] [])))

(deftest ^:unit create-adapter-and-context-delegate-to-core
  (let [adapter (adapter-stub :sqlite "org.sqlite.JDBC")
        datasource ::datasource]
      (with-redefs [wagoe.platform.ports.database/validate-db-config (fn [cfg]
                                                                                           (is (= :sqlite (:adapter cfg))))
                    wagoe.platform.shell.adapters.database.factory/load-adapter-constructor (fn [_] (fn [] adapter))
                    db-core/create-connection-pool (fn [arg cfg]
                                                     (is (= adapter arg))
                                                     (is (= {:adapter :sqlite
                                                             :database-path "app.db"}
                                                            cfg))
                                                     datasource)]
      (is (= adapter (sut/create-adapter {:adapter :sqlite :database-path "app.db"})))
      (is (= datasource (sut/create-datasource {:adapter :sqlite :database-path "app.db"})))
      (is (= {:adapter adapter
              :datasource datasource}
             (sut/db-context {:adapter :sqlite :database-path "app.db"}))))))

(deftest ^:unit close-and-with-db-manage-lifecycle
  (testing "close-db-context! only closes valid contexts"
    (let [closed (atom [])]
      (with-redefs [db-core/db-context? (fn [ctx] (= ::valid ctx))
                    db-core/close-connection-pool! (fn [datasource]
                                                      (swap! closed conj datasource))]
        (sut/close-db-context! ::invalid)
        (sut/close-db-context! ::valid)
        (is (= [nil] @closed)))))

  (testing "with-db always closes the created context"
    (let [closed (atom [])
          ctx {:adapter (adapter-stub :h2 "org.h2.Driver")
               :datasource ::temp-ds}]
      (with-redefs [wagoe.platform.shell.adapters.database.factory/db-context (fn [_] ctx)
                    wagoe.platform.shell.adapters.database.factory/close-db-context! (fn [arg]
                                                                                          (swap! closed conj arg))]
        (is (= :ok
               (sut/with-db {:adapter :h2 :database-path "mem:test"}
                 (fn [arg]
                   (is (= ctx arg))
                   :ok))))
        (is (= [ctx] @closed))))))

(deftest ^:unit validation-and-managed-context-helpers-cover-common-branches
  (testing "validate-connection reports success and failure"
      (with-redefs [wagoe.platform.shell.adapters.database.factory/with-db
                    (fn [_ f]
                      (f {:adapter ::ctx :datasource ::ds}))
                    db-core/database-info
                    (fn [_]
                      {:adapter :sqlite
                       :pool-info {:active 1}})]
      (is (= {:status :success
              :adapter :sqlite
              :connection-pool {:active 1}
              :message "Database connection successful"}
             (sut/validate-connection {:adapter :sqlite :database-path "app.db"}))))
    (with-redefs [wagoe.platform.shell.adapters.database.factory/with-db
                  (fn [_ _]
                    (throw (ex-info "connect boom" {})))]
      (is (= {:status :error
              :error "connect boom"
              :message "Database connection failed"}
             (sut/validate-connection {:adapter :sqlite :database-path "app.db"})))))

  (testing "managed contexts expose a close-all function"
    (let [created (atom [])
          closed (atom [])]
      (with-redefs [wagoe.platform.shell.adapters.database.factory/db-context (fn [cfg]
                                                                                   (swap! created conj cfg)
                                                                                   {:adapter (:adapter cfg)
                                                                                    :datasource (:adapter cfg)})
                    wagoe.platform.shell.adapters.database.factory/close-db-context! (fn [ctx]
                                                                                          (swap! closed conj (:datasource ctx)))]
        (let [contexts (sut/create-managed-contexts {:main {:adapter :sqlite}
                                                     :cache {:adapter :h2}})]
          (is (= [{:adapter :sqlite} {:adapter :h2}] @created))
          ((:close-all! contexts))
          (is (= [:sqlite :h2] @closed))))))

  (testing "supported adapter helper functions remain stable"
    (is (= [:sqlite :postgresql :mysql :h2] (sut/list-supported-adapters)))
    (is (= {:adapter :sqlite :database-path "app.db"}
           (sut/sqlite-config "app.db")))
    (is (= {:adapter :h2 :database-path "mem:testdb"}
           (sut/h2-config :memory)))))
