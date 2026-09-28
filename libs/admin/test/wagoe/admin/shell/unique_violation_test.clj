(ns wagoe.admin.shell.unique-violation-test
  "BOU-590: an admin create or update with a value a unique key already holds.
   The form said only \"Failed to create Invoice\"; it marks the field now.
   Swept over H2, SQLite and PostgreSQL, because the refusal's text is the driver's."
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

(def ^:private admin-config
  {:enabled? true
   :base-path "/web/admin"
   :require-role :admin
   :entity-discovery {:mode :allowlist :allowlist #{:invoices}}
   :entities {:invoices {:readonly-fields #{:id :created-at :updated-at}}}})

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "admin@example.com" :name "Admin" :role :admin :active true})

(defn- create-table! [db-ctx]
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS invoices"})
  (db/execute-update! db-ctx {:raw (str "CREATE TABLE invoices (id VARCHAR(36) PRIMARY KEY, "
                                        "number VARCHAR(50) NOT NULL UNIQUE, "
                                        "created_at VARCHAR(40) NOT NULL, "
                                        "updated_at VARCHAR(40) NOT NULL)")}))

(defonce ^:private backends (atom {}))

(defn- with-backends [f]
  (let [sqlite-file (File/createTempFile "bou590" ".db")
        pg          (epg/start!)
        ctxs        {:h2         (db-factory/db-context {:adapter :h2 :database-path "mem:bou590admin;DB_CLOSE_DELAY=-1"})
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
   :uri "/web/admin/invoices"
   :user admin-user
   :path-params {:entity "invoices"}
   :form-params form
   :i18n/catalogue i18n-catalogue
   :i18n/default-locale :en})

(defn- thrown [f]
  (with-silent-logging
    (try (f) nil (catch clojure.lang.ExceptionInfo e e))))

(deftest ^:integration a-duplicate-is-an-error-on-its-field
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)
                number (str "I-" (name backend))
                taken  (ports/create-entity svc :invoices {:number number})]]
    (testing (str backend)
      (testing "the service reports it on the field"
        (let [ex (thrown #(ports/create-entity svc :invoices {:number number}))]
          (is (= :validation-error (:type (ex-data ex))) (pr-str (ex-data ex)))
          (is (= :number (:field (ex-data ex))))
          (is (contains? (:errors (ex-data ex)) :number))))

      (testing "and on update and the inline edit"
        (let [other (ports/create-entity svc :invoices {:number (str number "-2")})
              id    (parse-uuid (str (:id other)))]
          (doseq [[label f] [["update-entity" #(ports/update-entity svc :invoices id {:number number})]
                             ["update-entity-field" #(ports/update-entity-field svc :invoices id :number number)]]]
            (testing label
              (is (= :number (:field (ex-data (thrown f)))))))))

      (testing "the form answers 422 with the field marked, not the generic banner"
        (let [response (with-silent-logging
                         ((crud/create-entity-handler svc schema admin-config)
                          (request {"number" number})))
              body     (str (:body response))]
          (is (= 422 (:status response)))
          (is (not (str/includes? body "Failed to create")) "the generic banner")
          (is (re-find #"has-errors\"><label for=\"number\"" body)
              "the number field carries the error")))
      (is (some? taken)))))
