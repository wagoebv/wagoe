(ns wagoe.admin.shell.digit-columns-test
  "A column with a digit segment (`x2y_ref_id`) read back empty: next.jdbc's
   kebab builder keyed it `:x-2y-ref-id`, the admin `:x2y-ref-id`. And a
   foreign key whose parent is named by a has-many, not by the key, showed
   no label. Swept over H2, SQLite and PostgreSQL, and MySQL with
   WAGOE_TEST_DB=mysql."
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.http.handlers.detail :as detail]
            [wagoe.admin.shell.http.handlers.list :as list]
            [wagoe.admin.test.embedded-pg :as epg]
            [wagoe.i18n.shell.catalogue :as catalogue]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [support.mysql :as mysql]
            [support.test-db :as test-db]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]))

(def ^:private admin-config
  {:enabled?         true
   :base-path        "/web/admin"
   :require-role     :admin
   :entity-discovery {:mode :allowlist :allowlist #{:dg-owners :dg-items}}
   ;; `x2y-ref-id` names no entity: only the has-many says it is an owner
   :entities         {:dg-owners {:title-field :name
                                  :has-many    [{:entity      :dg-items
                                                 :table       :dg_items
                                                 :foreign-key :x2y-ref-id
                                                 :label       "Items"}]}
                      :dg-items  {:list-fields [:label :x2y-ref-id]}}
   :pagination       {:default-page-size 20 :max-page-size 200}})

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "admin@example.com" :name "Admin" :role :admin :active true})

(defn- create-tables! [db-ctx]
  (doseq [sql ["DROP TABLE IF EXISTS dg_items"
               "DROP TABLE IF EXISTS dg_owners"
               "CREATE TABLE dg_owners (id VARCHAR(36) PRIMARY KEY, name VARCHAR(50) NOT NULL)"
               "CREATE TABLE dg_items (id VARCHAR(36) PRIMARY KEY, label VARCHAR(50) NOT NULL,
                  x2y_ref_id VARCHAR(36) REFERENCES dg_owners(id))"]]
    (db/execute-update! db-ctx {:raw sql})))

(def ^:private backends (atom {}))

(defn- with-backends [f]
  (let [sqlite-file (File/createTempFile "digit-columns" ".db")
        pg          (epg/start!)
        ctxs        {:h2         (db-factory/db-context {:adapter :h2 :database-path "mem:digitcols;DB_CLOSE_DELAY=-1"})
                     :sqlite     (db-factory/db-context {:adapter :sqlite :database-path (.getPath sqlite-file)})
                     :postgresql (epg/db-context pg)}
        run         (fn [ctxs]
                      (doseq [ctx (vals ctxs)] (create-tables! ctx))
                      (reset! backends ctxs)
                      (f))]
    (try
      (if (= :mysql test-db/engine)
        (mysql/with-database "digitcols" #(run (assoc ctxs :mysql %)))
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

(defn- request [uri params]
  {:request-method :get
   :uri            uri
   :headers        {}
   :user           admin-user
   :session        {:user admin-user}
   :path-params    params
   :query-params   {}
   :i18n/catalogue i18n-catalogue
   :i18n/default-locale :en})

(deftest ^:integration a-digit-column-reads-back-and-its-parent-shows-by-name
  (doseq [[backend db-ctx] @backends
          :let [{:keys [schema svc]} (system db-ctx)
                owner (str (:id (ports/create-entity svc :dg-owners {:name "Veldhuis"})))
                item  (str (:id (ports/create-entity svc :dg-items {:label "Bolt" :x2y-ref-id owner})))]]
    (testing (str backend)
      (testing "the service reads the column under the key it writes it with"
        (is (= owner (str (:x2y-ref-id (ports/get-entity svc :dg-items item)))))
        (is (= [owner] (map (comp str :x2y-ref-id)
                            (:records (ports/list-entities svc :dg-items {:limit 10}))))))

      (testing "the list cell shows the parent's name, linked"
        (let [body (str (:body ((list/entity-list-handler svc schema admin-config)
                                (request "/web/admin/dg-items" {:entity "dg-items"}))))]
          (is (str/includes? body (str "href=\"/web/admin/dg-owners/" owner "\"")))
          (is (str/includes? body ">Veldhuis</a>"))))

      (testing "the edit form selects the current parent"
        (let [body (str (:body ((detail/entity-detail-handler svc schema admin-config)
                                (request (str "/web/admin/dg-items/" item)
                                         {:entity "dg-items" :id item}))))]
          (is (str/includes? body (str "<option selected=\"selected\" value=\"" owner "\">Veldhuis</option>"))))))))
