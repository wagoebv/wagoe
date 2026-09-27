(ns wagoe.admin.shell.required-on-create-test
  "BOU-570: a hidden field, or a column with a default, is not required on
   create; every rejected form marks its field. Swept over H2, SQLite and
   PostgreSQL, because each reports a column default its own way."
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.http.handlers.crud :as crud]
            [wagoe.admin.shell.http.handlers.detail :as detail]
            [wagoe.admin.test.embedded-pg :as epg]
            [wagoe.i18n.shell.catalogue :as catalogue]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [clojure.tools.logging.test :as log-test]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]))

^{:kaocha.testable/meta {:integration true :admin true}}

(def ^:private admin-config
  {:entity-discovery {:mode :allowlist :allowlist #{:roc-invoices :roc-owned}}
   :entities {;; The rc-4 repro: the workflow owns status, so it is hidden.
              :roc-invoices {:label       "Invoices"
                             :hide-fields #{:status}
                             :fields      {:description {:widget :textarea}
                                           :kind        {:widget  :select
                                                         :options [["goods" "Goods"] ["services" "Services"]]}}}
              ;; Hidden, NOT NULL and no default: the database refuses it.
              :roc-owned    {:label       "Owned"
                             :table-name  :roc-invoices
                             :hide-fields #{:status :owner}}}})

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "admin@example.com" :name "Admin" :role :admin :active true})

(def ^:private ddl
  (str "CREATE TABLE roc_invoices ("
       "id VARCHAR(36) PRIMARY KEY, "
       "number VARCHAR(50) NOT NULL, "
       "status VARCHAR(20) DEFAULT 'entered' NOT NULL, "
       "note VARCHAR(50) DEFAULT 'none' NOT NULL, "
       "description VARCHAR(500) NOT NULL, "
       "kind VARCHAR(20) NOT NULL, "
       "owner VARCHAR(50) NOT NULL)"))

(defonce ^:private backends (atom {}))

(use-fixtures :once
  (fn [f]
    (let [sqlite-file (File/createTempFile "bou570" ".db")
          pg          (epg/start!)
          ctxs        {:h2         (db-factory/db-context {:adapter :h2 :database-path "mem:bou570;DB_CLOSE_DELAY=-1"})
                       :sqlite     (db-factory/db-context {:adapter :sqlite :database-path (.getPath sqlite-file)})
                       :postgresql (epg/db-context pg)}]
      (try
        (doseq [ctx (vals ctxs)]
          (db/execute-update! ctx {:raw "DROP TABLE IF EXISTS roc_invoices"})
          (db/execute-update! ctx {:raw ddl}))
        (reset! backends ctxs)
        (f)
        (finally
          (doseq [ctx (vals ctxs)] (db-factory/close-db-context! ctx))
          (epg/stop! pg)
          (.delete sqlite-file)
          (reset! backends {}))))))

(use-fixtures :each
  (fn [f]
    (doseq [ctx (vals @backends)]
      (db/execute-update! ctx {:raw "DELETE FROM roc_invoices"}))
    (f)))

(defn- system [db-ctx]
  (let [schema (schema-repo/create-schema-repository db-ctx admin-config)]
    {:schema schema :svc (service/create-admin-service db-ctx schema nil nil admin-config)}))

(def ^:private i18n-catalogue
  (catalogue/create-map-catalogue (catalogue/load-catalogue "wagoe/i18n/translations")))

(defn- request [method entity form & [id]]
  {:request-method method
   :uri            (str "/web/admin/" (name entity) (when id (str "/" id)))
   :user           admin-user
   :path-params    (cond-> {:entity (name entity)} id (assoc :id (str id)))
   :form-params    form
   :i18n/catalogue i18n-catalogue
   :i18n/default-locale :en})

(defn- rows [db-ctx]
  (db/execute-query! db-ctx {:select [:*] :from [:roc_invoices]}))

(def ^:private valid-form
  {"number" "INV-1" "description" "Hours" "kind" "services" "owner" "Ann"})

(deftest ^:integration a-hidden-field-with-a-default-is-not-required-on-create
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)]]
    (testing (str backend)
      (let [response ((crud/create-entity-handler svc schema admin-config)
                      (request :post :roc-invoices valid-form))]
        (is (not= 422 (:status response)) (str "status was " (:status response)))
        (is (= "entered" (:status (first (rows db-ctx)))) "the column default is stored")))))

(deftest ^:integration an-emptied-field-with-a-default-gets-the-default
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)]]
    (testing (str backend)
      (let [response ((crud/create-entity-handler svc schema admin-config)
                      (request :post :roc-invoices (assoc valid-form "note" "")))]
        (is (not= 422 (:status response)) (str "status was " (:status response)))
        (is (= "none" (:note (first (rows db-ctx)))))))))

(deftest ^:integration an-edit-still-requires-a-column-with-a-default
  (let [db-ctx (:h2 @backends)
        {:keys [schema svc]} (system db-ctx)
        created (ports/create-entity svc :roc-invoices {:number "INV-2" :description "d" :kind "goods" :owner "Ann"})
        response ((crud/update-entity-handler svc schema admin-config)
                  (request :put :roc-invoices {"note" ""} (:id created)))]
    (is (= 422 (:status response)))
    (is (re-find #"has-errors\"><label for=\"note\"" (str (:body response))) "the field carries the error")))

(deftest ^:integration the-create-form-marks-only-what-it-requires
  (let [db-ctx (:h2 @backends)
        {:keys [schema svc]} (system db-ctx)
        body (str (:body ((detail/new-entity-handler svc schema admin-config)
                          (request :get :roc-invoices nil))))]
    (testing "a required textarea and select say so"
      (is (re-find #"<textarea[^>]*name=\"description\"[^>]*required" body))
      (is (re-find #"<select[^>]*name=\"kind\"[^>]*required" body)))
    (testing "a field with a default does not"
      (is (re-find #"<input[^>]*name=\"note\"" body))
      (is (not (re-find #"<input[^>]*name=\"note\"[^>]*required" body))))))

(deftest ^:integration a-rejected-create-marks-its-field
  (let [db-ctx (:h2 @backends)
        {:keys [schema svc]} (system db-ctx)
        response ((crud/create-entity-handler svc schema admin-config)
                  (request :post :roc-invoices (dissoc valid-form "number")))
        body (str (:body response))]
    (is (= 422 (:status response)))
    (is (re-find #"has-errors\"><label for=\"number\"" body))
    (is (empty? (rows db-ctx)))))

(deftest ^:integration an-error-on-a-field-the-form-does-not-show-is-named-and-logged
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)]]
    (testing (str backend)
      (log-test/with-log
        (let [response ((crud/create-entity-handler svc schema admin-config)
                        (request :post :roc-owned (dissoc valid-form "owner")))
              body     (str (:body response))]
          (is (= 422 (:status response)))
          (is (re-find #"validation-errors[\s\S]*Owner[\s\S]*not on this form" body)
              "at the top of the form, naming the field")
          (is (log-test/logged? 'wagoe.admin.shell.http.handlers.crud :warn #"owner")
              "and in the log")
          (is (empty? (rows db-ctx))))))))
