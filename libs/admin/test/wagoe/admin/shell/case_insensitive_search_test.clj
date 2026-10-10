(ns wagoe.admin.shell.case-insensitive-search-test
  "Admin search and the contains/starts-with/ends-with filters ignore case on
   every database. They emitted ILIKE, which SQLite and MySQL do not have
   (ADR-039)."
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.test.embedded-pg :as epg]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]))

(def ^:private admin-config
  {:enabled? true
   :base-path "/web/admin"
   :require-role :admin
   :entity-discovery {:mode :allowlist :allowlist #{:invoices}}
   :entities {:invoices {:search-fields [:number]}}})

(defn- create-table! [db-ctx]
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS invoices"})
  (db/execute-update! db-ctx {:raw "CREATE TABLE invoices (id VARCHAR(36) PRIMARY KEY, number VARCHAR(50) NOT NULL)"})
  (db/execute-update! db-ctx {:insert-into :invoices
                              :values [{:id "1" :number "INV-ABC"}
                                       {:id "2" :number "inv-xyz"}
                                       {:id "3" :number "Élodie-7"}]}))

(defonce ^:private backends (atom {}))

(defn- with-backends [f]
  (let [sqlite-file (File/createTempFile "admin-search" ".db")
        pg          (epg/start!)
        ctxs        {:h2         (db-factory/db-context {:adapter :h2 :database-path "mem:adminsearch;DB_CLOSE_DELAY=-1"})
                     :sqlite     (db-factory/db-context {:adapter :sqlite :database-path (.getPath sqlite-file)})
                     :postgresql (epg/db-context pg)}]
    (try
      (doseq [ctx (vals ctxs)] (create-table! ctx))
      (reset! backends ctxs)
      (f)
      (finally
        (doseq [ctx (vals ctxs)] (db-factory/close-db-context! ctx))
        (epg/stop! pg)
        (.delete sqlite-file)
        (reset! backends {})))))

(use-fixtures :once with-backends)

(defn- admin [db-ctx]
  (service/create-admin-service db-ctx (schema-repo/create-schema-repository db-ctx admin-config {})
                                (logging-no-op/create-logging-component {})
                                (error-reporting-no-op/create-error-reporting-component {})
                                admin-config))

(defn- ids [svc options]
  (set (map (comp str :id) (:records (ports/list-entities svc :invoices options)))))

(deftest ^:integration search-and-text-filters-ignore-case-everywhere
  (doseq [[engine ctx] @backends]
    (testing (name engine)
      (let [svc (admin ctx)]
        (is (= #{"1" "2"} (ids svc {:search "INV"})) "search")
        (is (= #{"3"} (ids svc {:search "élodie"})) "search, accented")
        (is (= #{"1" "2"} (ids svc {:filters {:number {:op :contains :value "Inv"}}})) "contains")
        (is (= #{"1" "2"} (ids svc {:filters {:number {:op :starts-with :value "INV"}}})) "starts-with")
        (is (= #{"1"} (ids svc {:filters {:number {:op :ends-with :value "abc"}}})) "ends-with")
        (is (= 2 (ports/count-entities svc :invoices {:number {:op :contains :value "inv"}})) "count")))))
