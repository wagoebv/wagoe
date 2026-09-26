(ns wagoe.admin.shell.readonly-not-null-test
  "BOU-494: a :readonly-fields entry on a NOT NULL column without a default.

   The admin insert omits every read-only field, so such a column makes the
   entity impossible to create. Swept over H2, SQLite and PostgreSQL, because
   the nullability, the default and the constraint error each come from the
   driver."
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.http.handlers.crud :as crud]
            [wagoe.admin.shell.http.handlers.detail :as detail]
            [wagoe.admin.test.embedded-pg :as epg]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.test.logging :refer [with-silent-logging]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]))

^{:kaocha.testable/meta {:integration true :admin true}}

(def ^:private readonly #{:id :status :created-at :updated-at})

(def ^:private admin-config
  {:enabled? true
   :base-path "/web/admin"
   :require-role :admin
   :entity-discovery {:mode :allowlist
                      :allowlist #{:invoices :drafts :open-invoices}}
   :entities {;; The ticket's repro: status is read-only, NOT NULL, no default.
              :invoices      {:readonly-fields readonly}
              ;; The same, but the column has a default, so it can be created.
              :drafts        {:readonly-fields readonly}
              ;; status is editable but optional to the admin; the database
              ;; still refuses a row without it.
              :open-invoices {:table-name :invoices
                              :readonly-fields #{:id :created-at :updated-at}
                              :fields {:status {:required false}}}}})

(def ^:private malli-schemas
  ;; As a scaffolded module registers it: status is an enum, so a select.
  (let [schema [:map [:status [:enum :draft :sent]]]]
    {:invoices schema :drafts schema :open-invoices schema}))

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "admin@example.com" :name "Admin" :role :admin :active true})

(defn- create-tables! [db-ctx]
  (doseq [[table status-default] [["invoices" ""] ["drafts" " DEFAULT 'draft'"]]]
    (db/execute-update! db-ctx {:raw (str "DROP TABLE IF EXISTS " table)})
    (db/execute-update!
     db-ctx
     {:raw (str "CREATE TABLE " table " ("
                "id VARCHAR(36) PRIMARY KEY, "
                "number VARCHAR(50) NOT NULL, "
                "status VARCHAR(50)" status-default " NOT NULL, "
                "created_at VARCHAR(40) NOT NULL, "
                "updated_at VARCHAR(40) NOT NULL)")})))

(defonce ^:private backends (atom {}))

(defn- with-backends
  [f]
  (let [sqlite-file (File/createTempFile "bou494" ".db")
        pg          (epg/start!)
        ctxs        {:h2         (db-factory/db-context {:adapter :h2 :database-path "mem:bou494;DB_CLOSE_DELAY=-1"})
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
  (let [schema (schema-repo/create-schema-repository db-ctx admin-config malli-schemas)
        svc    (service/create-admin-service db-ctx schema
                                             (logging-no-op/create-logging-component {})
                                             (error-reporting-no-op/create-error-reporting-component {})
                                             admin-config)]
    {:schema schema :svc svc}))

(defn- request [method entity form]
  {:request-method method
   :uri (str "/web/admin/" (name entity))
   :user admin-user
   :path-params {:entity (name entity)}
   :form-params form})

(deftest ^:integration readonly-not-null-column-is-a-config-error-test
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)]]
    (testing (str backend)
      (testing "introspection reports the read-only NOT NULL column"
        (is (= [:status] (mapv :field (:create-config-errors (ports/get-entity-config schema :invoices))))))

      (testing "a column default makes it creatable"
        (is (empty? (:create-config-errors (ports/get-entity-config schema :drafts)))))

      (testing "the create form refuses with a config error naming the entity, the column and the fix"
        (let [ex (try ((detail/new-entity-handler svc schema admin-config) (request :get :invoices nil))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :invalid-config (:type (ex-data ex))))
          (is (str/includes? (str (ex-message ex)) "invoices"))
          (is (str/includes? (str (ex-message ex)) "'status'"))
          (is (str/includes? (str (ex-message ex)) "default"))
          (is (str/includes? (str (ex-message ex)) ":readonly-fields"))))

      (testing "so does a create submitted anyway"
        (let [ex (try ((crud/create-entity-handler svc schema admin-config)
                       (request :post :invoices {"number" "INV-1"}))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :invalid-config (:type (ex-data ex)))))))))

(deftest ^:integration not-null-violation-is-a-field-error-test
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)]]
    (testing (str backend)
      (testing "the service reports the column the database refused"
        (let [ex (with-silent-logging
                   (try (ports/create-entity svc :open-invoices {:number "INV-2"})
                        nil
                        (catch clojure.lang.ExceptionInfo e e)))]
          (is (= :validation-error (:type (ex-data ex))))
          (is (= :status (:field (ex-data ex))))
          (is (contains? (:errors (ex-data ex)) :status))))

      (testing "the form shows it on the field, not as the generic banner"
        (let [response (with-silent-logging
                         ((crud/create-entity-handler svc schema admin-config)
                          (request :post :open-invoices {"number" "INV-3"})))
              body     (str (:body response))]
          (is (= 422 (:status response)))
          (is (re-find #"has-errors\"><label for=\"status\"" body)
              "the status field carries the error")
          (is (not (str/includes? body "flash-create-failed"))))))))

(deftest ^:integration create-form-honours-readonly-fields-test
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)]]
    (testing (str backend)
      (let [body (str (:body ((detail/new-entity-handler svc schema admin-config)
                              (request :get :drafts nil))))]
        (is (str/includes? body "name=\"number\""))
        (is (not (str/includes? body "name=\"status\""))
            "a read-only field is not an input on the create form"))

      (testing "and the create stores the column default"
        (let [created (ports/create-entity svc :drafts {:number "D-1" :status "sent"})]
          (is (= "draft" (:status created))))))))
