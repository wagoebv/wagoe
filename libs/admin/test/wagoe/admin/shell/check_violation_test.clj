(ns wagoe.admin.shell.check-violation-test
  "BOU-734: a value a CHECK constraint refuses answered 500 and logged an
   ERROR. It is a 422 now, on the field when the constraint shows which
   (PostgreSQL's name, SQLite's expression), else above the form (H2's and
   MySQL's generated names). Swept over H2, SQLite and PostgreSQL, and MySQL
   with WAGOE_TEST_DB=mysql."
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
            [support.mysql :as mysql]
            [support.test-db :as test-db]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.test.logging :refer [logged-errors]])
  (:import [java.io File]))

(def ^:private admin-config
  {:enabled? true
   :base-path "/web/admin"
   :require-role :admin
   :entity-discovery {:mode :allowlist :allowlist #{:swatches}}
   :entities {:swatches {:readonly-fields #{:id :created-at :updated-at}}}})

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "admin@example.com" :name "Admin" :role :admin :active true})

(defn- create-table! [db-ctx]
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS swatches"})
  (db/execute-update! db-ctx {:raw (str "CREATE TABLE swatches (id VARCHAR(36) PRIMARY KEY, "
                                        "name VARCHAR(50) NOT NULL, "
                                        "color VARCHAR(20) NOT NULL CHECK (color IN ('red','green')), "
                                        "lo INT, hi INT, "
                                        "created_at VARCHAR(40) NOT NULL, "
                                        "updated_at VARCHAR(40) NOT NULL, "
                                        "CHECK (lo < hi))")}))

(defonce ^:private backends (atom {}))

(defn- with-backends [f]
  (let [sqlite-file (File/createTempFile "bou734" ".db")
        pg          (epg/start!)
        ctxs        {:h2         (db-factory/db-context {:adapter :h2 :database-path "mem:bou734admin;DB_CLOSE_DELAY=-1"})
                     :sqlite     (db-factory/db-context {:adapter :sqlite :database-path (.getPath sqlite-file)})
                     :postgresql (epg/db-context pg)}
        run         (fn [ctxs]
                      (doseq [ctx (vals ctxs)] (create-table! ctx))
                      (reset! backends ctxs)
                      (f))]
    (try
      (if (= :mysql test-db/engine)
        (mysql/with-database "bou734" #(run (assoc ctxs :mysql %)))
        (run ctxs))
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

(defn- request [method uri params form]
  {:request-method method
   :uri uri
   :user admin-user
   :path-params params
   :form-params form
   :i18n/catalogue i18n-catalogue
   :i18n/default-locale :en})

(defn- marked? [body field]
  (boolean (re-find (re-pattern (str "has-errors\"><label for=\"" field "\"")) body)))

(def ^:private names-the-column?
  "Whether the engine's report on the inline CHECK shows its column."
  #{:postgresql :sqlite})

(deftest ^:integration a-refused-color-is-a-422
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)
                on-field? (contains? names-the-column? backend)
                existing  (ports/create-entity svc :swatches {:name "ok" :color "red"})]]
    (testing (str backend)
      (testing "the service: a validation error, on :color where the engine shows it"
        (let [ex (try (ports/create-entity svc :swatches {:name "x" :color "blue"})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :validation-error (:type (ex-data ex))) (pr-str (ex-data ex)))
          (is (= (when on-field? :color) (:field (ex-data ex))))))

      (testing "create: 422, nothing at ERROR"
        (let [[response errors] (logged-errors #((crud/create-entity-handler svc schema admin-config)
                                                 (request :post "/web/admin/swatches" {:entity "swatches"}
                                                          {"name" "x" "color" "blue"})))
              body (str (:body response))]
          (is (= 422 (:status response)))
          (is (= [] errors))
          (is (= on-field? (marked? body "color")))
          (when-not on-field?
            (is (str/includes? body "A value is not allowed")))))

      (testing "edit: 422, nothing at ERROR"
        (let [id (str (:id existing))
              [response errors] (logged-errors #((crud/update-entity-handler svc schema admin-config)
                                                 (request :put (str "/web/admin/swatches/" id)
                                                          {:entity "swatches" :id id}
                                                          {"color" "blue"})))
              body (str (:body response))]
          (is (= 422 (:status response)))
          (is (= [] errors))
          (is (= on-field? (marked? body "color")))
          (when-not on-field?
            (is (str/includes? body "A value is not allowed"))))))))

(deftest ^:integration a-check-over-two-columns-is-a-422-above-the-form
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)
                existing (ports/create-entity svc :swatches {:name "ok" :color "green" :lo 1 :hi 2})]]
    (testing (str backend)
      (testing "create"
        (let [[response errors] (logged-errors #((crud/create-entity-handler svc schema admin-config)
                                                 (request :post "/web/admin/swatches" {:entity "swatches"}
                                                          {"name" "x" "color" "red" "lo" "3" "hi" "2"})))
              body (str (:body response))]
          (is (= 422 (:status response)))
          (is (= [] errors))
          (is (str/includes? body "A value is not allowed"))
          (is (not (marked? body "lo")))
          (is (not (marked? body "hi")))))

      (testing "edit"
        (let [id (str (:id existing))
              [response errors] (logged-errors #((crud/update-entity-handler svc schema admin-config)
                                                 (request :put (str "/web/admin/swatches/" id)
                                                          {:entity "swatches" :id id}
                                                          {"lo" "5"})))
              body (str (:body response))]
          (is (= 422 (:status response)))
          (is (= [] errors))
          (is (str/includes? body "A value is not allowed")))))))
