(ns wagoe.admin-tenants-config-test
  "The shipped admin config for tenants, against the table the tenant module
   creates (BOU-534). Tenants are created through the tenant service, which
   derives schema_name; the admin must not offer a create it cannot complete."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reitit.ring :as ring]
            [wagoe.admin.shell.http :as admin-http]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as service]
            [wagoe.config :as config]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.shared.ui.core.components :as ui-components]
            [wagoe.tenant.shell.persistence :as tenant-persistence]))

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001" :email "admin@example.com"
   :name "Admin" :role :admin :active true})

(defn- request!
  "Call `app`; a thrown ex-info comes back as `{:thrown <ex-data :type>}`, the
   platform's error mapping being outside this test."
  [app method uri & [form]]
  (try
    (let [resp (app {:request-method method :uri uri :user admin-user
                     :headers {} :query-params {} :form-params (or form {})})]
      (cond-> resp
        (vector? (:body resp)) (update :body ui-components/render-html)))
    (catch clojure.lang.ExceptionInfo e
      {:thrown (:type (ex-data e))})))

(defn- with-admin [profile f]
  (let [ctx (db-factory/db-context {:adapter       :h2
                                    :database-path (str "mem:admin_tenants_" (name profile) ";DB_CLOSE_DELAY=-1")})]
    (try
      (tenant-persistence/initialize-tenant-schema! ctx)
      (let [entities (-> (config/load-config {:profile profile})
                         (get-in [:active :wagoe/admin :entities])
                         (select-keys [:tenants]))
            cfg      {:base-path        "/web/admin"
                      :entity-discovery {:mode :allowlist :allowlist #{:tenants}}
                      :entities         entities}
            provider (schema-repo/create-schema-repository ctx cfg)
            svc      (service/create-admin-service ctx provider nil nil cfg)
            app      (ring/ring-handler
                      (ring/router [["/web/admin" (admin-http/web-routes svc provider cfg nil)]]
                                   {:conflicts nil}))]
        (is (seq entities) "the profile ships a tenants entity")
        (f ctx app))
      (finally
        (db/execute-update! ctx {:raw "DROP ALL OBJECTS"})
        (db-factory/close-db-context! ctx)))))

(deftest ^:integration the-admin-does-not-offer-a-tenant-create-it-cannot-complete
  (doseq [profile [:dev :test]]
    (testing (str profile)
      (with-admin profile
        (fn [ctx app]
          (testing "the create form is refused, not shown as a config error"
            (let [resp (request! app :get "/web/admin/tenants/new")]
              (is (= :forbidden (:thrown resp)))
              (is (not= 500 (:status resp)))))

          (testing "a posted create writes nothing"
            (is (= :forbidden (:thrown (request! app :post "/web/admin/tenants"
                                                 {"slug" "acme" "name" "Acme" "status" "active"}))))
            (is (zero? (:n (db/execute-one! ctx {:select [[:%count.* :n]] :from [:tenants]})))))

          (testing "the list still works, without a New link"
            (db/execute-update! ctx {:insert-into :tenants
                                     :values [{:id (str (random-uuid)) :slug "acme" :name "Acme"
                                               :schema-name "tenant_acme" :status "active"
                                               :created-at (str (java.time.Instant/now))}]})
            (let [{:keys [status body]} (request! app :get "/web/admin/tenants")]
              (is (= 200 status))
              (is (str/includes? body "acme"))
              (is (not (str/includes? body "/web/admin/tenants/new"))))))))))
