(ns wagoe.platform.shell.adapters.database.config-factory-test
  (:require [wagoe.platform.shell.adapters.database.config-factory :as sut]
            [wagoe.platform.shell.adapters.database.config :as db-config]
            [wagoe.platform.shell.adapters.database.common.core :as db-core]
            [wagoe.platform.ports.database :as protocols]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]))

(defn- adapter-stub
  [dialect]
  (reify protocols/DBAdapter
    (dialect [_] dialect)
    (jdbc-driver [_] "driver")
    (jdbc-url [_ _] nil)
    (pool-defaults [_] {})
    (init-connection! [_ _ _] nil)
    (build-where [_ _] nil)
    (boolean->db [_ value] value)
    (db->boolean [_ value] value)
    (table-exists? [_ _ _] false)
    (get-table-info [_ _ _] [])))

(deftest ^:unit create-config-context-selects-requested-config
  (let [adapter (adapter-stub :sqlite)]
      (with-redefs [db-config/get-active-db-configs
                    (fn [env]
                      (is (= "test" env))
                      {:wagoe/sqlite {:adapter :sqlite :database-path "dev.db"}
                       :wagoe/h2 {:adapter :h2 :database-path "mem:test"}})
                    wagoe.platform.shell.adapters.database.config-factory/create-config-adapter
                    (fn [adapter-type env]
                      (is (= :sqlite adapter-type))
                      (is (= "test" env))
                      adapter)
                    db-core/create-connection-pool
                    (fn [arg cfg]
                      (is (= adapter arg))
                      (is (= {:adapter :sqlite
                              :database-path "dev.db"}
                             cfg))
                      ::datasource)]
      (is (= {:adapter adapter
              :datasource ::datasource
              :config-key :wagoe/sqlite
              :environment "test"}
             (sut/create-config-context "test" :wagoe/sqlite))))))

(deftest ^:unit get-default-context-prefers-sqlite-when-available
  (let [sqlite-ctx {:adapter (adapter-stub :sqlite)
                    :datasource ::sqlite-ds}
        h2-ctx {:adapter (adapter-stub :h2)
                :datasource ::h2-ds}]
    (with-redefs [wagoe.platform.shell.adapters.database.config-factory/create-active-contexts
                  (fn [_]
                    {:wagoe/h2 h2-ctx
                     :wagoe/sqlite sqlite-ctx})]
      (is (= {:adapter (:adapter sqlite-ctx)
              :datasource ::sqlite-ds}
             (sut/get-default-context "test"))))))

(deftest ^:unit create-active-contexts-and-health-check-cover-success-and-failure
  (testing "active contexts are created for every active config"
    (with-redefs [wagoe.platform.shell.adapters.database.config/get-active-db-configs
                  (fn [_]
                    {:wagoe/sqlite {:adapter :sqlite}
                     :wagoe/h2 {:adapter :h2}})
                  wagoe.platform.shell.adapters.database.config-factory/create-config-context
                  (fn [env config-key]
                    {:environment env
                     :config-key config-key
                     :adapter (adapter-stub (case config-key
                                              :wagoe/sqlite :sqlite
                                              :h2))
                     :datasource config-key})]
      (is (= #{:wagoe/sqlite :wagoe/h2}
             (set (keys (sut/create-active-contexts "test")))))))

  (testing "health-check records healthy and unhealthy adapters and closes pools"
    (let [closed (atom [])]
      (with-redefs [wagoe.platform.shell.adapters.database.config-factory/create-active-contexts
                    (fn [_]
                      {:wagoe/sqlite {:adapter (adapter-stub :sqlite)
                                         :datasource ::sqlite-ds}
                       :wagoe/postgresql {:adapter (adapter-stub :postgresql)
                                             :datasource ::pg-ds}})
                    wagoe.platform.shell.adapters.database.common.core/execute-query!
                    (fn [ctx _]
                      (if (= ::pg-ds (:datasource ctx))
                        (throw (ex-info "query boom" {}))
                        [{:one 1}]))
                    wagoe.platform.shell.adapters.database.common.core/close-connection-pool!
                    (fn [datasource]
                      (swap! closed conj datasource))]
        (let [result (sut/health-check "test")]
          (is (= :healthy (get-in result [:wagoe/sqlite :status])))
          (is (= :unhealthy (get-in result [:wagoe/postgresql :status])))
          (is (= "query boom" (get-in result [:wagoe/postgresql :error])))
          (is (= [::sqlite-ds ::pg-ds] @closed))))))

  (testing "config validation delegates directly to db-config"
    (with-redefs [wagoe.platform.shell.adapters.database.config/validate-database-configs
                  (fn [env]
                    (is (= "test" env))
                    {:valid? true :errors []})]
      (is (= {:valid? true :errors []}
             (sut/validate-environment-config "test"))))))

(deftest ^:unit adapter-availability-and-context-lifecycle-helpers
  (with-redefs [wagoe.platform.shell.adapters.database.config/get-active-adapters
                (fn [_] [:sqlite :postgresql])
                wagoe.platform.shell.adapters.database.config/adapter-active?
                (fn [adapter-type _] (= adapter-type :sqlite))]
    (is (= [:sqlite :postgresql] (sut/get-active-adapter-types "test")))
    (is (true? (sut/adapter-available? :sqlite "test")))
    (is (thrown-with-msg? IllegalStateException
                          #"Available adapters: \[:sqlite :postgresql\]"
                          (sut/ensure-adapter-available! :mysql "test"))))

  (testing "with-config-context and with-default-context always close datasources"
    (let [closed (atom [])]
      (with-redefs [wagoe.platform.shell.adapters.database.config-factory/create-config-context
                    (fn [_ _] {:datasource ::config-ds})
                    wagoe.platform.shell.adapters.database.config-factory/get-default-context
                    (fn [_] {:datasource ::default-ds})
                    wagoe.platform.shell.adapters.database.common.core/close-connection-pool!
                    (fn [datasource]
                      (swap! closed conj datasource))]
        (is (= :config-ok
               (sut/with-config-context "test" :wagoe/sqlite (fn [_] :config-ok))))
        (is (= :default-ok
               (sut/with-default-context "test" (fn [_] :default-ok))))
        (is (= [::config-ds ::default-ds] @closed)))))

  (testing "default context errors when no adapters are active"
    (with-redefs [wagoe.platform.shell.adapters.database.config-factory/create-active-contexts (fn [_] {})]
      (is (thrown-with-msg? IllegalStateException
                            #"No active database adapters found"
                            (sut/get-default-context "test"))))))

(deftest ^:unit adapter-loading-and-default-environment-helpers
  (testing "inactive adapters are rejected with a helpful error"
    (with-redefs [wagoe.platform.shell.adapters.database.config/adapter-active? (constantly false)]
      (is (thrown-with-msg? IllegalStateException
                            #"Database adapter :mysql is not active in environment test"
                            (sut/create-config-adapter :mysql "test")))))

  (testing "active adapters return their constructor when the namespace loads"
    (with-redefs [wagoe.platform.shell.adapters.database.config/adapter-active? (constantly true)
                  require (fn [ns-sym]
                            (is (= 'wagoe.platform.shell.adapters.database.sqlite.core ns-sym))
                            true)
                  find-ns (fn [ns-sym]
                            (when (= 'wagoe.platform.shell.adapters.database.sqlite.core ns-sym)
                              'fake-ns))
                  ns-resolve (fn [ns-obj fn-sym]
                               (is (= 'fake-ns ns-obj))
                               (is (= 'new-adapter fn-sym))
                               (fn [] ::sqlite-adapter))]
      (is (= ::sqlite-adapter
             ((#'wagoe.platform.shell.adapters.database.config-factory/load-adapter-constructor-if-active
               :sqlite
               "test"))))))

  (testing "active adapters surface namespace load failures clearly"
    (with-redefs [wagoe.platform.shell.adapters.database.config/adapter-active? (constantly true)
                  require (fn [_] (throw (RuntimeException. "boom")))]
      (is (thrown-with-msg? RuntimeException
                            #"Failed to load active database adapter: :sqlite for environment: test"
                            (#'wagoe.platform.shell.adapters.database.config-factory/load-adapter-constructor-if-active
                             :sqlite
                             "test")))))

  (testing "default helpers delegate through detected environment"
    (with-redefs [wagoe.platform.shell.adapters.database.config/detect-environment (constantly "detected")
                  wagoe.platform.shell.adapters.database.config-factory/create-active-contexts
                  (fn
                    ([] {:wagoe/sqlite {:datasource ::detected-ds}})
                    ([env]
                     (is (= "detected" env))
                     {:wagoe/sqlite {:datasource ::detected-ds}}))
                  wagoe.platform.shell.adapters.database.common.core/close-connection-pool!
                  (fn [_] nil)]
      (is (= {:datasource ::detected-ds}
             (sut/get-default-context)))
      (is (= {:wagoe/sqlite {:datasource ::detected-ds}}
             (sut/create-active-contexts)))
      (is (= :ok
             (sut/with-default-context (fn [_] :ok)))))))

(deftest ^:unit config-context-and-health-check-failure-paths
  (testing "missing config keys produce a useful error message"
    (with-redefs [wagoe.platform.shell.adapters.database.config/get-active-db-configs
                  (fn [_]
                    {:wagoe/sqlite {:adapter :sqlite}
                     :wagoe/h2 {:adapter :h2}})]
      (is (thrown-with-msg? IllegalArgumentException
                            #"Configuration key :wagoe/postgresql not found in active configs"
                            (sut/create-config-context "test" :wagoe/postgresql)))))

  (testing "create-active-contexts rethrows context creation failures"
    (with-redefs [wagoe.platform.shell.adapters.database.config/get-active-db-configs
                  (fn [_]
                    {:wagoe/sqlite {:adapter :sqlite}})
                  wagoe.platform.shell.adapters.database.config-factory/create-config-context
                  (fn [_ _]
                    (throw (ex-info "context boom" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"context boom"
                            (sut/create-active-contexts "test")))))

  (testing "health-check closes datasources even when query execution throws"
    (let [closed (atom [])]
      (with-redefs [wagoe.platform.shell.adapters.database.config-factory/create-active-contexts
                    (fn [_]
                      {:wagoe/sqlite {:adapter (adapter-stub :sqlite)
                                         :datasource ::sqlite-ds}})
                    wagoe.platform.shell.adapters.database.common.core/execute-query!
                    (fn [_ _]
                      (throw (ex-info "unhealthy" {})))
                    wagoe.platform.shell.adapters.database.common.core/close-connection-pool!
                    (fn [datasource]
                      (swap! closed conj datasource))]
        (let [result (sut/health-check "test")]
          (is (= :unhealthy (get-in result [:wagoe/sqlite :status])))
          (is (= [::sqlite-ds] @closed))))))

  (testing "with-context helpers close datasources on exceptions"
    (let [closed (atom [])]
      (with-redefs [wagoe.platform.shell.adapters.database.config-factory/create-config-context
                    (fn [_ _] {:datasource ::config-ds})
                    wagoe.platform.shell.adapters.database.config-factory/get-default-context
                    (fn [_] {:datasource ::default-ds})
                    wagoe.platform.shell.adapters.database.common.core/close-connection-pool!
                    (fn [datasource]
                      (swap! closed conj datasource))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"config path failure"
                              (sut/with-config-context "test" :wagoe/sqlite
                                (fn [_] (throw (ex-info "config path failure" {}))))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"default path failure"
                              (sut/with-default-context "test"
                                (fn [_] (throw (ex-info "default path failure" {}))))))
        (is (= [::config-ds ::default-ds] @closed))))))

(deftest ^:unit compatibility-helper-functions
  (testing "supported adapter helpers expose the expected compatibility surface"
    (is (true? (sut/adapter-supported? :wagoe/sqlite)))
    (is (false? (sut/adapter-supported? :wagoe/unknown)))
    (is (= [:wagoe/sqlite :wagoe/h2 :wagoe/postgresql :wagoe/mysql :wagoe/settings :wagoe/logging]
           (sut/list-available-adapters))))

  (testing "adapter config validation handles positive and negative cases"
    (is (true? (sut/valid-adapter-config? :wagoe/sqlite {:db "dev.db"})))
    (is (true? (sut/valid-adapter-config? :wagoe/h2 {:memory true})))
    (is (true? (sut/valid-adapter-config? :wagoe/postgresql
                                          {:host "localhost" :port 5432 :dbname "app" :user "u" :password "p"})))
    (is (true? (sut/valid-adapter-config? :wagoe/settings {:anything true})))
    (is (false? (sut/valid-adapter-config? :wagoe/sqlite {:db 42})))
    (is (false? (sut/valid-adapter-config? :wagoe/postgresql {:host "localhost"})))
    (is (false? (sut/valid-adapter-config? :wagoe/unknown {:db "x"}))))

  (testing "create-adapter and create-active-adapters validate inputs and produce compatible adapters"
    (is (thrown-with-msg? IllegalArgumentException
                          #"Configuration cannot be null"
                          (sut/create-adapter :wagoe/sqlite nil)))
    (is (thrown-with-msg? IllegalArgumentException
                          #"Unsupported adapter type"
                          (sut/create-adapter :wagoe/unknown {})))
    (is (thrown-with-msg? IllegalArgumentException
                          #"Invalid configuration for adapter type: :wagoe/sqlite"
                          (sut/create-adapter :wagoe/sqlite {:db 42})))
    (let [sqlite-adapter (sut/create-adapter :wagoe/sqlite {:db "dev.db"})
          active (sut/create-active-adapters {:active {:wagoe/sqlite {:db "dev.db"}}
                                              :wagoe/unknown {:db "ignored"}})]
      (is (= :sqlite (protocols/dialect sqlite-adapter)))
      (is (= "jdbc:sqlite:dev.db" (protocols/jdbc-url sqlite-adapter {})))
      (is (= #{:wagoe/sqlite} (set (keys active))))
      (is (thrown-with-msg? IllegalArgumentException
                            #"Configuration must contain an :active section"
                            (sut/create-active-adapters {})))
      (is (thrown-with-msg? IllegalArgumentException
                            #"The :active section must be a map"
                            (sut/create-active-adapters {:active []}))))))

(deftest ^:unit print-environment-summary-renders-validation-and-adapter-information
  (let [printed (atom [])]
    (with-redefs [wagoe.platform.shell.adapters.database.config/print-config-summary
                  (fn [env]
                    (swap! printed conj (str "summary:" env)))
                  wagoe.platform.shell.adapters.database.config-factory/validate-environment-config
                  (fn [_]
                    {:valid? false
                     :errors [{:adapter :sqlite :error "missing db"}]})
                  wagoe.platform.shell.adapters.database.config-factory/get-active-adapter-types
                  (fn [_]
                    [:sqlite :h2])
                  println
                  (fn [& args]
                    (swap! printed conj (str/join " " args)))]
      (sut/print-environment-summary "test")
      (is (some #(str/includes? % "Database Environment Summary: test") @printed))
      (is (some #(str/includes? % "Configuration Validation: FAILED") @printed))
      (is (some #(str/includes? % "summary:test") @printed))
      (is (some #(str/includes? % "✅ :sqlite") @printed))
      (is (some #(str/includes? % "✅ :h2") @printed)))))
