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
            [wagoe.i18n.shell.catalogue :as catalogue]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.test.logging :refer [with-silent-logging]]
            [clojure.string :as str]
            [clojure.tools.logging.test :as log-test]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]))

^{:kaocha.testable/meta {:integration true :admin true}}

(def ^:private readonly #{:id :status :created-at :updated-at})

(def ^:private admin-config
  {:enabled? true
   :base-path "/web/admin"
   :require-role :admin
   :entity-discovery {:mode :allowlist
                      :allowlist #{:invoices :drafts :open-invoices :tickets}}
   :entities {;; The ticket's repro: status is read-only, NOT NULL, no default.
              :invoices      {:readonly-fields readonly}
              ;; The same, but the column has a default, so it can be created.
              :drafts        {:readonly-fields readonly}
              ;; status is editable but optional to the admin; the database
              ;; still refuses a row without it.
              :open-invoices {:table-name :invoices
                              :readonly-fields #{:id :created-at :updated-at}
                              :fields {:status {:required false}}}
              ;; Columns the database fills, kept off the form.
              :tickets       {:readonly-fields #{:id :created-at :updated-at :seq :len}}}})

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

(def ^:private tickets-ddl
  "An identity and a computed column, both NOT NULL with no column default.
   SQLite has no identity column that is not the primary key."
  {:h2         (str "CREATE TABLE tickets (id VARCHAR(36) PRIMARY KEY, title VARCHAR(50),"
                    " seq BIGINT GENERATED ALWAYS AS IDENTITY,"
                    " len INT GENERATED ALWAYS AS (CHAR_LENGTH(id)) NOT NULL)")
   :postgresql (str "CREATE TABLE tickets (id VARCHAR(36) PRIMARY KEY, title VARCHAR(50),"
                    " seq BIGINT GENERATED ALWAYS AS IDENTITY,"
                    " len INT GENERATED ALWAYS AS (length(id)) STORED NOT NULL)")
   :sqlite     (str "CREATE TABLE tickets (id VARCHAR(36) PRIMARY KEY, title VARCHAR(50),"
                    " len INT GENERATED ALWAYS AS (length(id)) STORED NOT NULL)")})

(defonce ^:private backends (atom {}))

(defn- with-backends
  [f]
  (let [sqlite-file (File/createTempFile "bou494" ".db")
        pg          (epg/start!)
        ctxs        {:h2         (db-factory/db-context {:adapter :h2 :database-path "mem:bou494;DB_CLOSE_DELAY=-1"})
                     :sqlite     (db-factory/db-context {:adapter :sqlite :database-path (.getPath sqlite-file)})
                     :postgresql (epg/db-context pg)}]
    (try
      (doseq [[backend ctx] ctxs]
        (create-tables! ctx)
        (db/execute-update! ctx {:raw "DROP TABLE IF EXISTS tickets"})
        (db/execute-update! ctx {:raw (tickets-ddl backend)}))
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

(def ^:private i18n-catalogue
  (catalogue/create-map-catalogue (catalogue/load-catalogue "wagoe/i18n/translations")))

(defn- request [method entity form]
  {:request-method method
   :uri (str "/web/admin/" (name entity))
   :user admin-user
   :path-params {:entity (name entity)}
   :form-params form
   :i18n/catalogue i18n-catalogue
   :i18n/default-locale :en})

(deftest ^:integration readonly-not-null-column-is-a-config-error-test
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)]]
    (testing (str backend)
      (testing "introspection reports the read-only NOT NULL column"
        (is (= [:status] (mapv :field (:create-config-errors (ports/get-entity-config schema :invoices))))))

      (testing "a column default makes it creatable"
        (is (empty? (:create-config-errors (ports/get-entity-config schema :drafts)))))

      ;; It was an ex-info no error mapping knew, so the admin answered with
      ;; a bare JSON 500 (PR #568 review).
      (testing "the create form is an admin page naming the entity, the column and the fix"
        (let [response ((detail/new-entity-handler svc schema admin-config) (request :get :invoices nil))
              body     (str (:body response))]
          (is (= 500 (:status response)))
          (is (str/includes? (get-in response [:headers "Content-Type"]) "text/html"))
          (is (str/includes? body "admin-shell") "inside the admin layout")
          (is (re-find #"Invoices cannot be created here: column \S*status\S* is NOT NULL" body))
          (is (str/includes? body "default"))))

      (testing "so is a create submitted anyway, and nothing is written"
        (let [response ((crud/create-entity-handler svc schema admin-config)
                        (request :post :invoices {"number" "INV-1"}))]
          (is (= 500 (:status response)))
          (is (re-find #"column \S*status\S* is NOT NULL" (str (:body response))))
          (is (zero? (:total-count (ports/list-entities svc :invoices {})))))))))

(deftest ^:integration columns-the-database-fills-are-not-config-errors-test
  ;; Identity and computed columns have no column default, so they were
  ;; reported as uncreatable (PR #568 review).
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema]} (system db-ctx)]]
    (testing (str backend)
      (is (empty? (:create-config-errors (ports/get-entity-config schema :tickets)))))))

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

(deftest ^:integration a-config-error-is-logged-once-test
  ;; Each entity's config is also computed as another entity's possible child
  ;; (BOU-481), and both computations logged (PR #568 review).
  (let [db-ctx (:h2 @backends)]
    (log-test/with-log
      (let [{:keys [schema]} (system db-ctx)]
        (doseq [e (ports/list-available-entities schema)]
          (ports/get-entity-config schema e))
        (doseq [e (ports/list-available-entities schema)]
          (ports/get-entity-config schema e)))
      (is (= 1 (count (filter #(re-find #"Entity 'invoices' cannot be created" (str (:message %)))
                              (log-test/the-log))))))))
