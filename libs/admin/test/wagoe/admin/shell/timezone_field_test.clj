(ns wagoe.admin.shell.timezone-field-test
  "A field with `:options-source :timezones` is a combobox over the IANA zones."
  (:require [wagoe.admin.ports :as ports]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.http.support :as support]
            [wagoe.admin.shell.http.handlers.crud :as crud]
            [wagoe.admin.shell.http.handlers.detail :as detail]
            [wagoe.i18n.shell.catalogue :as catalogue]
            [wagoe.shared.ui.core.combobox :as combobox]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private admin-config
  {:entity-discovery {:mode :allowlist :allowlist #{:people}}
   :entities         {:people {:fields {:timezone {:options-source :timezones}}}}
   :pagination       {:default-page-size 20 :max-page-size 200}})

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "admin@example.com" :name "Admin" :role :admin :active true})

(def ^:private i18n-catalogue
  (catalogue/create-map-catalogue (catalogue/load-catalogue "wagoe/i18n/translations")))

(def ^:dynamic *sys* nil)

(defn- with-system [f]
  (let [ctx (db-factory/db-context {:adapter :h2 :database-path (str "mem:tzfield" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})]
    (try
      (db/execute-update! ctx {:raw "CREATE TABLE people (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL, timezone VARCHAR(64))"})
      (let [schema (schema-repo/create-schema-repository ctx admin-config {})
            svc    (service/create-admin-service ctx schema
                                                 (logging-no-op/create-logging-component {})
                                                 (error-reporting-no-op/create-error-reporting-component {})
                                                 admin-config)]
        (binding [*sys* {:schema schema :svc svc}]
          (f)))
      (finally (db-factory/close-db-context! ctx)))))

(use-fixtures :each with-system)

(defn- request [method uri path-params & [form query]]
  {:request-method method
   :uri            uri
   :user           admin-user
   :path-params    path-params
   :form-params    (or form {})
   :query-params   (or query {})
   :i18n/catalogue i18n-catalogue
   :i18n/default-locale :en})

(defn- body [response] (str (:body response)))

(deftest ^:unit zones-are-searched-as-typed
  (is (some #{["America/New_York" "America/New_York"]} (support/search-timezones "new york")))
  (is (= [["Europe/Amsterdam" "Europe/Amsterdam"]] (support/search-timezones "AMSTERDAM")))
  (is (= combobox/limit (count (support/search-timezones nil))) "no text: the first page of zones"))

(deftest ^:integration a-timezone-is-picked-from-the-zones
  (let [{:keys [schema svc]} *sys*
        id   (str (:id (ports/create-entity svc :people {:name "Ann" :timezone "Asia/Tokyo"})))
        edit #(body ((detail/entity-detail-handler svc schema admin-config)
                     (request :get (str "/web/admin/people/" id) {:entity "people" :id id})))]
    (testing "the edit page shows a combobox reading the zones, the current one filled in"
      (let [page (edit)]
        (is (re-find #"<input[^>]*hx-get=\"/web/admin/people/options/timezone\"[^>]*role=\"combobox\"" page))
        (is (re-find #"<input[^>]*name=\"timezone\"[^>]*value=\"Asia/Tokyo\"" page))))

    (testing "the options are the zones matching what was typed"
      (let [page (body ((crud/field-options-handler svc schema admin-config)
                        (request :get "/web/admin/people/options/timezone" {:entity "people" :field "timezone"}
                                 nil {"q" "amster"})))]
        (is (str/includes? page "data-value=\"Europe/Amsterdam\""))
        (is (not (str/includes? page "Asia/Tokyo")))))

    (testing "the zone picked is saved"
      (let [response ((crud/update-entity-handler svc schema admin-config)
                      (request :put (str "/web/admin/people/" id) {:entity "people" :id id}
                               {"name" "Ann" "timezone" "Europe/Amsterdam"}))]
        (is (= 200 (:status response)))
        (is (= "Europe/Amsterdam" (:timezone (ports/get-entity svc :people id))))))))
