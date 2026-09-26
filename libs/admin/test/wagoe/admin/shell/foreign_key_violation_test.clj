(ns wagoe.admin.shell.foreign-key-violation-test
  "BOU-540: an admin create naming a parent row that does not exist.

   The database refuses the insert; the admin answered a generic banner. Swept
   over H2, SQLite and PostgreSQL, because the refusal's text is the driver's."
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.http.handlers.crud :as crud]
            [wagoe.admin.test.embedded-pg :as epg]
            [wagoe.i18n.shell.catalogue :as catalogue]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.test.logging :refer [with-silent-logging]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]))

^{:kaocha.testable/meta {:integration true :admin true}}

(def ^:private admin-config
  {:enabled? true
   :base-path "/web/admin"
   :require-role :admin
   :entity-discovery {:mode :allowlist :allowlist #{:customers :orders}}
   :entities {:orders {:readonly-fields #{:id :created-at :updated-at}}}})

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "admin@example.com" :name "Admin" :role :admin :active true})

(defn- create-tables! [db-ctx]
  (doseq [t ["orders" "customers"]]
    (db/execute-update! db-ctx {:raw (str "DROP TABLE IF EXISTS " t)}))
  (db/execute-update! db-ctx {:raw "CREATE TABLE customers (id VARCHAR(36) PRIMARY KEY, name VARCHAR(50))"})
  (db/execute-update! db-ctx {:raw (str "CREATE TABLE orders (id VARCHAR(36) PRIMARY KEY, "
                                        "number VARCHAR(50) NOT NULL, "
                                        "customer_id VARCHAR(36) NOT NULL REFERENCES customers(id), "
                                        "created_at VARCHAR(40) NOT NULL, "
                                        "updated_at VARCHAR(40) NOT NULL)")}))

(defonce ^:private backends (atom {}))

(defn- with-backends [f]
  (let [sqlite-file (File/createTempFile "bou540" ".db")
        pg          (epg/start!)
        ctxs        {:h2         (db-factory/db-context {:adapter :h2 :database-path "mem:bou540;DB_CLOSE_DELAY=-1"})
                     :sqlite     (db-factory/db-context {:adapter :sqlite :database-path (.getPath sqlite-file)})
                     :postgresql (epg/db-context pg)}]
    (try
      (doseq [ctx (vals ctxs)] (create-tables! ctx))
      (reset! backends ctxs)
      (f)
      (finally
        (doseq [ctx (vals ctxs)] (db-factory/close-db-context! ctx))
        (epg/stop! pg)
        (.delete sqlite-file)
        (reset! backends {})))))

(use-fixtures :once with-backends)

(defn- system [db-ctx]
  (let [schema (schema-repo/create-schema-repository db-ctx admin-config {})
        svc    (service/create-admin-service db-ctx schema
                                             (logging-no-op/create-logging-component {})
                                             (error-reporting-no-op/create-error-reporting-component {})
                                             admin-config)]
    {:schema schema :svc svc}))

(def ^:private i18n-catalogue
  (catalogue/create-map-catalogue (catalogue/load-catalogue "wagoe/i18n/translations")))

(defn- request [form]
  {:request-method :post
   :uri "/web/admin/orders"
   :user admin-user
   :path-params {:entity "orders"}
   :form-params form
   :i18n/catalogue i18n-catalogue
   :i18n/default-locale :en})

(def ^:private no-such-customer "00000000-0000-0000-0000-00000000dead")

(deftest ^:integration foreign-key-violation-is-a-validation-error-test
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)]]
    (testing (str backend)
      (testing "the service reports it as a validation error, on its field where the driver names it"
        (let [ex (with-silent-logging
                   (try (ports/create-entity svc :orders {:number "O-1" :customer-id no-such-customer})
                        nil
                        (catch clojure.lang.ExceptionInfo e e)))]
          (is (= :validation-error (:type (ex-data ex))) (pr-str (ex-data ex)))
          ;; SQLite's message names no column: "FOREIGN KEY constraint failed".
          (when-not (= :sqlite backend)
            (is (= :customer-id (:field (ex-data ex))))
            (is (contains? (:errors (ex-data ex)) :customer-id)))))

      (testing "the form answers 422, not the generic banner"
        (let [response (with-silent-logging
                         ((crud/create-entity-handler svc schema admin-config)
                          (request {"number" "O-2" "customer-id" no-such-customer})))
              body     (str (:body response))]
          (is (= 422 (:status response)))
          (is (not (str/includes? body "flash-create-failed")))
          (when-not (= :sqlite backend)
            (is (re-find #"has-errors\"><label for=\"customer-id\"" body)
                "the customer-id field carries the error")))))))
