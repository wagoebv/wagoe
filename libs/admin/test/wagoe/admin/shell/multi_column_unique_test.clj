(ns wagoe.admin.shell.multi-column-unique-test
  "BOU-712: a duplicate on a unique key over two columns. The create answered
   200 and logged an ERROR, the edit 409; neither marked a field. Both are a
   409 with each of the key's fields marked now. Swept over H2, SQLite and
   PostgreSQL, and MySQL with WAGOE_TEST_DB=mysql: each names the columns its
   own way."
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
            [clojure.tools.logging :as log]
            [clojure.tools.logging.test :as log-test])
  (:import [java.io File]))

(def ^:private admin-config
  {:enabled? true
   :base-path "/web/admin"
   :require-role :admin
   :entity-discovery {:mode :allowlist :allowlist #{:templates}}
   :entities {:templates {:readonly-fields #{:id :created-at :updated-at}}}})

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "admin@example.com" :name "Admin" :role :admin :active true})

(defn- create-table! [db-ctx]
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS templates"})
  (db/execute-update! db-ctx {:raw (str "CREATE TABLE templates (id VARCHAR(36) PRIMARY KEY, "
                                        "template_key VARCHAR(50) NOT NULL, "
                                        "domain_id VARCHAR(50) NOT NULL, "
                                        "slug VARCHAR(50) NOT NULL UNIQUE, "
                                        "created_at VARCHAR(40) NOT NULL, "
                                        "updated_at VARCHAR(40) NOT NULL, "
                                        "CONSTRAINT uq_templates_key_domain UNIQUE (template_key, domain_id))")}))

(defonce ^:private backends (atom {}))

(defn- with-backends [f]
  (let [sqlite-file (File/createTempFile "bou712" ".db")
        pg          (epg/start!)
        ctxs        {:h2         (db-factory/db-context {:adapter :h2 :database-path "mem:bou712admin;DB_CLOSE_DELAY=-1"})
                     :sqlite     (db-factory/db-context {:adapter :sqlite :database-path (.getPath sqlite-file)})
                     :postgresql (epg/db-context pg)}
        run         (fn [ctxs]
                      (doseq [ctx (vals ctxs)] (create-table! ctx))
                      (reset! backends ctxs)
                      (f))]
    (try
      (if (= :mysql test-db/engine)
        (mysql/with-database "bou712" #(run (assoc ctxs :mysql %)))
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

(defn- logged
  "[result errors], `errors` the messages logged at ERROR while `f` ran."
  [f]
  (let [the-log (log-test/atomic-log log-test/->LogEntry)
        res     (binding [log/*logger-factory* (log-test/logger-factory the-log (constantly true))] (f))]
    [res (into [] (comp (filter #(= :error (:level %))) (map :message)) (log-test/entries the-log))]))

(defn- marked? [body field]
  (boolean (re-find (re-pattern (str "has-errors\"><label for=\"" field "\"")) body)))

(deftest ^:integration a-duplicate-pair-is-a-409-on-both-fields
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)
                k     (str "welcome-" (name backend))
                _     (ports/create-entity svc :templates {:template-key k :domain-id "d1" :slug (str k "-1")})
                other (ports/create-entity svc :templates {:template-key k :domain-id "d2" :slug (str k "-2")})]]
    (testing (str backend)
      (testing "the platform names both columns"
        (let [ex (try (ports/create-entity svc :templates {:template-key k :domain-id "d1" :slug (str k "-3")})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= {:type :conflict :constraint :unique :fields [:template-key :domain-id]}
                 (ex-data ex)))))

      (testing "create: 409, both fields marked, nothing at ERROR"
        (let [[response errors] (logged #((crud/create-entity-handler svc schema admin-config)
                                          (request :post "/web/admin/templates" {:entity "templates"}
                                                   {"template-key" k "domain-id" "d1" "slug" (str k "-4")})))
              body (str (:body response))]
          (is (= 409 (:status response)))
          (is (= [] errors))
          (is (str/includes? body "Another record already has these values"))
          (is (str/includes? body "Taken in this combination"))
          (is (marked? body "template-key"))
          (is (marked? body "domain-id"))
          (is (not (marked? body "slug")))))

      (testing "edit: 409, both fields marked, nothing at ERROR"
        (let [id (str (:id other))
              [response errors] (logged #((crud/update-entity-handler svc schema admin-config)
                                          (request :put (str "/web/admin/templates/" id)
                                                   {:entity "templates" :id id}
                                                   {"domain-id" "d1"})))
              body (str (:body response))]
          (is (= 409 (:status response)))
          (is (= [] errors))
          (is (str/includes? body "Another record already has these values"))
          (is (marked? body "template-key"))
          (is (marked? body "domain-id")))))))

(deftest ^:integration a-duplicate-in-one-column-is-still-a-422-on-it
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)
                slug (str "only-" (name backend))
                _    (ports/create-entity svc :templates {:template-key slug :domain-id "d1" :slug slug})]]
    (testing (str backend)
      (let [[response errors] (logged #((crud/create-entity-handler svc schema admin-config)
                                        (request :post "/web/admin/templates" {:entity "templates"}
                                                 {"template-key" slug "domain-id" "d9" "slug" slug})))
            body (str (:body response))]
        (is (= 422 (:status response)))
        (is (= [] errors))
        (is (marked? body "slug"))
        (is (not (marked? body "template-key")))))))
