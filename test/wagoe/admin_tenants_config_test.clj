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
            [wagoe.i18n.shell.catalogue :as catalogue]
            [wagoe.platform.database :as db]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.shared.ui.core.components :as ui-components]
            [wagoe.tenant.shell.persistence :as tenant-persistence]
            [wagoe.user.shell.persistence :as user-persistence]))

(def ^:private admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001" :email "admin@example.com"
   :name "Admin" :role :admin :active true})

(def ^:private i18n-catalogue
  (catalogue/create-map-catalogue (catalogue/load-catalogue "wagoe/i18n/translations")))

(defn- request-as!
  "Call `app` as `user`; a thrown ex-info comes back as `{:thrown <ex-data :type>}`,
   the platform's error mapping being outside this test."
  [user app method uri & [form]]
  (try
    (let [resp (app {:request-method method :uri uri :user user
                     :headers {} :query-params {} :form-params (or form {})
                     :i18n/catalogue i18n-catalogue :i18n/default-locale :en})]
      (cond-> resp
        (vector? (:body resp)) (update :body ui-components/render-html)))
    (catch clojure.lang.ExceptionInfo e
      {:thrown (:type (ex-data e))})))

(defn- request! [app method uri & [form]]
  (request-as! admin-user app method uri form))

(defn- with-admin [profile f & [allowlist]]
  (let [allowlist (or allowlist #{:tenants})
        ctx (db-factory/db-context {:adapter       :h2
                                    :database-path (str "mem:admin_tenants_" (name profile) ";DB_CLOSE_DELAY=-1")})]
    (try
      (tenant-persistence/initialize-tenant-schema! ctx)
      (when (contains? allowlist :users)
        (user-persistence/initialize-user-schema! ctx))
      (let [entities (-> (config/load-config {:profile profile})
                         (get-in [:active :wagoe/admin :entities])
                         (select-keys allowlist))
            cfg      {:base-path        "/web/admin"
                      :entity-discovery {:mode :allowlist :allowlist allowlist}
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

(defn- html? [resp]
  (some-> (get-in resp [:headers "Content-Type"]) (str/starts-with? "text/html")))

(deftest ^:integration the-admin-does-not-offer-a-tenant-create-it-cannot-complete
  (doseq [profile [:dev :test]]
    (testing (str profile)
      (with-admin profile
        (fn [ctx app]
          (testing "the create form is an admin 403 page that points at the API"
            (let [{:keys [status body] :as resp} (request! app :get "/web/admin/tenants/new")]
              (is (= 403 status))
              (is (html? resp))
              (is (str/includes? (str body) "admin-forbidden"))
              (is (str/includes? (str body) "POST /api/v1/tenants"))))

          (testing "a posted create is refused the same way and writes nothing"
            (let [resp (request! app :post "/web/admin/tenants"
                                 {"slug" "acme" "name" "Acme" "status" "active"})]
              (is (= 403 (:status resp)))
              (is (str/includes? (str (:body resp)) "POST /api/v1/tenants")))
            (is (zero? (:n (db/execute-one! ctx {:select [[:%count.* :n]] :from [:tenants]})))))

          (testing "the list and the detail page still work, without a New link"
            (let [id (str (random-uuid))]
              (db/execute-update! ctx {:insert-into :tenants
                                       :values [{:id id :slug "acme" :name "Acme"
                                                 :schema-name "tenant_acme" :status "active"
                                                 :created-at (str (java.time.Instant/now))}]})
              (doseq [uri ["/web/admin/tenants" (str "/web/admin/tenants/" id)]]
                (testing uri
                  (let [{:keys [status body]} (request! app :get uri)]
                    (is (= 200 status))
                    (is (str/includes? (str body) "acme"))
                    (is (not (str/includes? (str body) "/web/admin/tenants/new"))))))
              (testing "an edit leaves the slug alone: it names the schema"
                (request! app :put (str "/web/admin/tenants/" id)
                          {"slug" "acme-renamed" "name" "Acme 2" "status" "active"})
                (is (= {:slug "acme" :name "Acme 2"}
                       (db/execute-one! ctx {:select [:slug :name] :from [:tenants]
                                             :where [:= :id id]})))))))))))

(deftest ^:integration deleting-a-tenant-its-users-reference-is-refused
  ;; Users are a detected child of tenants, and a user can belong to more than
  ;; one: a delete must not take them with it. Detected children restrict
  ;; (BOU-563).
  (doseq [profile [:dev :test]]
    (testing (str profile)
      (with-admin profile
        (fn [ctx app]
          (let [tenant (str (random-uuid))
                user   (str (random-uuid))
                now    (str (java.time.Instant/now))]
            (db/execute-update! ctx {:insert-into :tenants
                                     :values [{:id tenant :slug "acme" :name "Acme"
                                               :schema-name "tenant_acme" :status "active" :created-at now}]})
            (db/execute-update! ctx {:insert-into :auth_users
                                     :values [{:id user :email "a@acme.test" :password-hash "x"
                                               :active true :created-at now}]})
            (db/execute-update! ctx {:insert-into :users
                                     :values [{:id user :tenant-id tenant :name "Ada" :role "user"
                                               :created-at now}]})
            (let [resp (request! app :delete (str "/web/admin/tenants/" tenant))]
              (is (= 409 (:status resp)))
              (is (str/includes? (str (get-in resp [:headers "HX-Trigger"])) "1 Users")))
            (is (nil? (:deleted-at (db/execute-one! ctx {:select [:deleted-at] :from [:tenants] :where [:= :id tenant]}))))
            (is (nil? (:deleted-at (db/execute-one! ctx {:select [:deleted-at] :from [:auth_users] :where [:= :id user]}))))
            (is (= tenant (str (:tenant-id (db/execute-one! ctx {:select [:tenant-id] :from [:users] :where [:= :id user]})))))))
        #{:tenants :users}))))

(deftest ^:integration ^:security a-tenants-admin-cannot-change-its-status-limits-or-existence
  ;; Only a platform admin, as the tenant API has it (BOU-1011)
  (doseq [profile [:dev :test]]
    (testing (str profile)
      (with-admin profile
        (fn [ctx app]
          (let [id     (str (random-uuid))
                theirs (assoc admin-user :id (random-uuid) :tenant-id (parse-uuid id))
                row    #(db/execute-one! ctx {:select [:name :status :settings :deleted-at] :from [:tenants]
                                              :where [:= :id id]})]
            (db/execute-update! ctx {:insert-into :tenants
                                     :values [{:id id :slug "acme" :name "Acme" :schema-name "tenant_acme"
                                               :status "active" :created-at (str (java.time.Instant/now))}]})
            (testing "an edit keeps the status and limits, and the name changes"
              (request-as! theirs app :put (str "/web/admin/tenants/" id)
                           {"name" "Acme 2" "status" "suspended" "settings" "{\"max-users\": 9999}"})
              (is (= "Acme 2" (:name (row))))
              (is (= "active" (:status (row))))
              (is (not (str/includes? (str (:settings (row))) "9999"))))
            (testing "a delete is refused"
              (is (#{403 :forbidden} (let [r (request-as! theirs app :delete (str "/web/admin/tenants/" id))]
                                       (or (:thrown r) (:status r)))))
              (is (nil? (:deleted-at (row)))))
            (testing "nor through an inline edit, nor a bulk delete"
              (let [r (request-as! theirs app :patch (str "/web/admin/tenants/" id "/status") {"status" "suspended"})]
                (is (#{403 :forbidden} (or (:thrown r) (:status r)))))
              (let [r (request-as! theirs app :post "/web/admin/tenants/bulk-delete" {"ids[]" [id]})]
                (is (#{403 :forbidden} (or (:thrown r) (:status r)))))
              (is (= "active" (:status (row))))
              (is (nil? (:deleted-at (row)))))
            (testing "the edit form shows them read-only, and offers no delete"
              (let [body (str (:body (request-as! theirs app :get (str "/web/admin/tenants/" id))))]
                (is (not (re-find #"<(input|select|textarea)[^>]*name=\"status\"(?![^>]*(disabled|readonly))" body)))
                (is (not (str/includes? body "hx-delete"))))
              (is (re-find #"name=\"status\"" (str (:body (request! app :get (str "/web/admin/tenants/" id)))))
                  "a platform admin's form has the field, so the check above means something"))
            (testing "a platform admin still sets the status"
              (request! app :put (str "/web/admin/tenants/" id) {"name" "Acme 2" "status" "suspended"})
              (is (= "suspended" (:status (row)))))))))))

