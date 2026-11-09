(ns wagoe.admin.shell.tenant-scope-test
  "An entity mapping `:tenant-id` under `:scope` shows a tenant's admin only
   their tenant's records, whatever the authorization mode (BOU-954)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [reitit.ring :as ring]
            [wagoe.admin.shell.http :as admin-http]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.admin.shell.service :as service]
            [wagoe.i18n.shell.catalogue :as i18n-catalogue]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.platform.database :as db]
            [support.test-db :as test-db]))

(def ^:private tenant-a #uuid "a0000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b #uuid "b0000000-0000-0000-0000-00000000000b")
(def ^:private m-a "00000000-0000-0000-0000-0000000000a1")
(def ^:private m-b "00000000-0000-0000-0000-0000000000b1")

(def ^:private settings
  {:enabled?           true
   :base-path          "/web/admin"
   :authorization-mode :off
   :entity-discovery   {:mode :allowlist :allowlist #{:members}}
   :entities           {:members {:label "Members" :list-fields [:name] :title-field :name
                                  :scope {:tenant-id :tenant-id}}}
   :pagination         {:default-page-size 50 :max-page-size 200}})

(def ^:private catalogue
  (i18n-catalogue/create-map-catalogue (i18n-catalogue/load-catalogue "wagoe/i18n/translations")))

(def ^:dynamic ^:private *app* nil)
(def ^:dynamic ^:private *db* nil)

(use-fixtures :each
  (fn [f]
    (test-db/with-db "admin_tenant_scope"
      (fn [db-ctx]
        (db/execute-update! db-ctx {:raw "CREATE TABLE members (id VARCHAR(36) PRIMARY KEY, name VARCHAR(50) NOT NULL, tenant_id VARCHAR(36))"})
        (db/execute-update! db-ctx {:insert-into :members
                                    :values [{:id m-a :name "MEMBER-A" :tenant_id (str tenant-a)}
                                             {:id m-b :name "MEMBER-B" :tenant_id (str tenant-b)}]})
        (let [provider (schema-repo/create-schema-repository db-ctx settings {})
              svc      (service/create-admin-service db-ctx provider
                                                     (logging-no-op/create-logging-component {})
                                                     (error-reporting-no-op/create-error-reporting-component {})
                                                     settings)
              app      (ring/ring-handler (ring/router (admin-http/web-routes svc provider settings nil) {:conflicts nil}))]
          (binding [*db* db-ctx
                    *app* (fn [request]
                            (try (app request)
                                 (catch clojure.lang.ExceptionInfo e
                                   {:status (case (:type (ex-data e)) :not-found 404 :forbidden 403 500)})))]
            (f)))))))

(defn- call [user method uri & {:keys [tenant form]}]
  (*app* (cond-> {:request-method method :uri uri :headers {} :query-params {} :user user
                  :i18n/catalogue catalogue :i18n/default-locale :en}
           tenant (assoc :tenant {:id tenant})
           form   (assoc :form-params form))))

(defn- names [response]
  (set (re-seq #"MEMBER-[AB]" (str (:body response)))))

(defn- name-of [id]
  (:name (db/execute-one! *db* {:select [:name] :from [:members] :where [:= :id id]})))

(def ^:private admin-of-a {:id (random-uuid) :role :admin :tenant-id tenant-a})
(def ^:private platform-admin {:id (random-uuid) :role :admin})

(deftest ^:integration ^:security a-tenants-admin-reaches-only-their-tenants-records
  (testing "the list"
    (is (= #{"MEMBER-A"} (names (call admin-of-a :get "/members")))))
  (testing "another tenant's record is not found, to read, change or delete"
    (is (= 200 (:status (call admin-of-a :get (str "/members/" m-a)))))
    (is (= 404 (:status (call admin-of-a :get (str "/members/" m-b)))))
    (is (= 404 (:status (call admin-of-a :put (str "/members/" m-b) :form {"name" "TAKEN"}))))
    (is (= 404 (:status (call admin-of-a :delete (str "/members/" m-b)))))
    (is (= "MEMBER-B" (name-of m-b))))
  (testing "on another tenant's host, nothing"
    (is (= 403 (:status (call admin-of-a :get "/members" :tenant tenant-b))))))

(deftest ^:integration ^:security a-platform-admin-reaches-every-tenant-or-the-resolved-one
  (is (= #{"MEMBER-A" "MEMBER-B"} (names (call platform-admin :get "/members"))))
  (is (= #{"MEMBER-B"} (names (call platform-admin :get "/members" :tenant tenant-b)))))

(deftest ^:integration ^:security a-member-who-administers-another-tenant-is-no-admin-here
  ;; In-tenant-scope as B's member, admin only by the role A gave them
  (is (= 403 (:status (*app* {:request-method :get :uri "/members" :headers {} :query-params {}
                              :user admin-of-a :tenant {:id tenant-b}
                              :tenant-membership {:tenant-id tenant-b :role :member}
                              :i18n/catalogue catalogue :i18n/default-locale :en}))))
  (testing "an admin by membership of B is"
    (is (= #{"MEMBER-B"} (names (*app* {:request-method :get :uri "/members" :headers {} :query-params {}
                                        :user admin-of-a :tenant {:id tenant-b}
                                        :tenant-membership {:tenant-id tenant-b :role :admin}
                                        :i18n/catalogue catalogue :i18n/default-locale :en}))))))
