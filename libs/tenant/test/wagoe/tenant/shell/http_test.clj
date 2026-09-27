(ns wagoe.tenant.shell.http-test
  "Contract tests for the tenant HTTP endpoints, over the real service and
   repository on H2.

   These ran against a mock that returned {:success? true :tenant ...}, which
   the service never does: it returns the tenant or throws a typed error. Every
   handler but list and get answered 400 {\"error\":null} on success (BOU-576).
   A mock cannot drift from the service when there is none."
  (:require [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.tenant.shell.http :as tenant-http]
            [wagoe.tenant.shell.persistence :as tenant-persistence]
            [wagoe.tenant.shell.service :as tenant-service]
            [wagoe.tenant.ports :as tenant-ports]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.util UUID]))

^{:kaocha.testable/meta {:contract true :tenant true}}

(def ^:dynamic *ctx* nil)
(def ^:dynamic *service* nil)

(use-fixtures :each
  (fn [f]
    (let [ctx (factory/db-context
               (factory/h2-config (str "mem:tenant_http_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
      (try
        (tenant-persistence/initialize-tenant-schema! ctx)
        (binding [*ctx*     ctx
                  *service* (tenant-service/create-tenant-service
                             (tenant-persistence/create-tenant-repository ctx nil nil)
                             {} nil nil nil)]
          (f))
        (finally (factory/close-db-context! ctx))))))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- body [response]
  (some-> (:body response) (json/parse-string true)))

(defn- call
  "Call the handler built by `handler-fn` with a request for tenant `id`."
  ([handler-fn id] (call handler-fn id {} nil))
  ([handler-fn id params body-params]
   ((handler-fn *service*) {:path-params {:id (str id)}
                            :params      params
                            :body-params body-params})))

(defn- create! [slug & [tenant-name]]
  (tenant-ports/create-new-tenant *service* {:slug slug :name (or tenant-name slug)}))

(def ^:private unknown-id #uuid "99999999-9999-9999-9999-999999999999")

;; =============================================================================
;; Create
;; =============================================================================

(deftest ^:contract create-tenant-handler-test
  (let [post #((tenant-http/create-tenant-handler *service*) {:body-params %})]
    (testing "creates the tenant and answers with it"
      (let [response (post {:name "New Tenant" :slug "new-tenant"})]
        (is (= 201 (:status response)))
        (is (= {:name "New Tenant" :slug "new-tenant" :schema-name "tenant_new_tenant" :status "active"}
               (select-keys (body response) [:name :slug :schema-name :status])))
        (is (= (:id (body response))
               (str (:id (tenant-ports/get-tenant-by-slug *service* "new-tenant")))))))

    (testing "a taken slug is a conflict that says why"
      (let [response (post {:name "Again" :slug "new-tenant"})]
        (is (= 409 (:status response)))
        (is (= "Tenant slug already exists" (:error (body response))))))

    (testing "input the schema refuses is a 400 naming the field"
      (let [response (post {:slug "no-name"})]
        (is (= 400 (:status response)))
        (is (= "Validation failed" (:error (body response))))
        (is (contains? (get-in (body response) [:details :validation-errors]) :name))))

    (testing "nothing was written for a refusal"
      (is (= 1 (count (tenant-ports/list-tenants *service* {})))))))

;; =============================================================================
;; List
;; =============================================================================

(deftest ^:contract list-tenants-handler-test
  (create! "acme-corp" "ACME Corporation")
  (create! "beta-inc" "Beta Inc")
  (tenant-ports/suspend-tenant *service* (:id (tenant-ports/get-tenant-by-slug *service* "beta-inc")))
  (let [list-with #(let [r ((tenant-http/list-tenants-handler *service*) {:params %})]
                     [(:status r) (body r)])
        slugs     #(set (map :slug %))]
    (testing "lists every tenant"
      (let [[status b] (list-with {})]
        (is (= 200 status))
        (is (= #{"acme-corp" "beta-inc"} (slugs b)))))
    (testing "limit and offset"
      (is (= 1 (count (second (list-with {:limit "1"})))))
      (is (= 1 (count (second (list-with {:offset "1"}))))))
    (testing "filters by status"
      (is (= #{"beta-inc"} (slugs (second (list-with {:status "suspended"}))))))
    (testing "searches name and slug"
      (is (= #{"acme-corp"} (slugs (second (list-with {:search "ACME"})))))
      (is (= #{"beta-inc"} (slugs (second (list-with {:search "beta-"}))))))))

;; =============================================================================
;; Get
;; =============================================================================

(deftest ^:contract get-tenant-handler-test
  (let [tenant (create! "acme-corp" "ACME Corporation")]
    (testing "an existing tenant"
      (let [response (call tenant-http/get-tenant-handler (:id tenant))]
        (is (= 200 (:status response)))
        (is (= "ACME Corporation" (:name (body response))))))
    (testing "an unknown id is a 404"
      (let [response (call tenant-http/get-tenant-handler unknown-id)]
        (is (= 404 (:status response)))
        (is (= "Tenant not found" (:error (body response))))))
    (testing "an id that is not a UUID is a 400"
      (is (= 400 (:status (call tenant-http/get-tenant-handler "invalid-uuid")))))))

;; =============================================================================
;; Update
;; =============================================================================

(deftest ^:contract update-tenant-handler-test
  (let [tenant (create! "acme-corp" "ACME")
        put    #(call tenant-http/update-tenant-handler %1 {} %2)]
    (testing "updates and answers with the tenant"
      (let [response (put (:id tenant) {:name "Updated ACME" :status "suspended"})]
        (is (= 200 (:status response)))
        (is (= {:name "Updated ACME" :slug "acme-corp" :status "suspended"}
               (select-keys (body response) [:name :slug :status])))
        (is (= "Updated ACME" (:name (tenant-ports/get-tenant *service* (:id tenant)))))))
    (testing "the slug names the tenant's schema, so it cannot change"
      (let [response (put (:id tenant) {:slug "new-slug"})]
        (is (= 400 (:status response)))
        (is (re-find #"slug" (:error (body response)))))
      (is (= 200 (:status (put (:id tenant) {:slug "acme-corp" :name "Same slug"})))))
    (testing "an unknown id is a 404"
      (is (= 404 (:status (put unknown-id {:name "x"})))))
    (testing "input the schema refuses is a 400"
      (is (= 400 (:status (put (:id tenant) {:status "bogus"})))))
    (testing "an id that is not a UUID is a 400"
      (is (= 400 (:status (put "invalid-uuid" {:name "x"})))))))

;; =============================================================================
;; Delete, suspend, activate
;; =============================================================================

(deftest ^:contract delete-tenant-handler-test
  (let [tenant (create! "acme-corp")
        delete #(call tenant-http/delete-tenant-handler %)]
    (testing "deletes"
      (let [response (delete (:id tenant))]
        (is (= 200 (:status response)))
        (is (= "Tenant deleted successfully" (:message (body response)))))
      (is (= 404 (:status (call tenant-http/get-tenant-handler (:id tenant))))))
    (testing "a deleted or unknown tenant is a 404"
      (is (= 404 (:status (delete (:id tenant)))))
      (is (= 404 (:status (delete unknown-id)))))
    (testing "an id that is not a UUID is a 400"
      (is (= 400 (:status (delete "invalid-uuid")))))))

(deftest ^:contract ^:security a-deleted-tenants-slug-cannot-be-taken
  ;; The schema name derives from the slug and a deleted tenant's schema is not
  ;; dropped, so a new tenant with the same slug would inherit its data.
  (let [tenant   (create! "acme-corp")
        _        (tenant-ports/delete-existing-tenant *service* (:id tenant))
        response ((tenant-http/create-tenant-handler *service*)
                  {:body-params {:name "Someone else" :slug "acme-corp"}})]
    (is (= 409 (:status response)))
    (is (= "Tenant slug already exists" (:error (body response))))))

(deftest ^:contract suspend-and-activate-tenant-handler-test
  (let [tenant (create! "acme-corp")]
    (testing "suspend answers with the suspended tenant"
      (let [response (call tenant-http/suspend-tenant-handler (:id tenant))]
        (is (= 200 (:status response)))
        (is (= "suspended" (:status (body response))))))
    (testing "activate answers with the active tenant"
      (let [response (call tenant-http/activate-tenant-handler (:id tenant))]
        (is (= 200 (:status response)))
        (is (= "active" (:status (body response))))))
    (testing "an unknown id is a 404"
      (is (= 404 (:status (call tenant-http/suspend-tenant-handler unknown-id))))
      (is (= 404 (:status (call tenant-http/activate-tenant-handler unknown-id)))))
    (testing "an id that is not a UUID is a 400"
      (is (= 400 (:status (call tenant-http/suspend-tenant-handler "invalid-uuid"))))
      (is (= 400 (:status (call tenant-http/activate-tenant-handler "invalid-uuid")))))))

;; =============================================================================
;; Provision
;; =============================================================================

(deftest ^:contract provision-tenant-handler-test
  (let [tenant    (create! "acme-corp")
        provision #((tenant-http/provision-tenant-handler *service* %1)
                    {:path-params {:id (str %2)}})]
    (testing "H2 has no schemas to provision: 501, saying so"
      (let [response (provision *ctx* (:id tenant))]
        (is (= 501 (:status response)))
        (is (re-find #"PostgreSQL" (:error (body response))))))
    (testing "an unknown id is a 404"
      (is (= 404 (:status (provision *ctx* unknown-id)))))
    (testing "no database context is a 500"
      (is (= 500 (:status (provision nil (:id tenant))))))
    (testing "an id that is not a UUID is a 400"
      (is (= 400 (:status (provision *ctx* "invalid-uuid")))))))

;; =============================================================================
;; Routes
;; =============================================================================

(deftest ^:contract tenant-routes-test
  (let [routes (tenant-http/tenant-routes *service* *ctx* {})]
    (is (= 5 (count (:api routes))))
    (testing "every route is Reitit data with real handlers"
      (doseq [[path data] (:api routes)]
        (is (string? path))
        (doseq [[_method config] (dissoc data :middleware)]
          (is (fn? (:handler config))))))))

(deftest ^:contract ^:security tenant-routes-require-the-admin-role
  ;; The platform refuses anyone not signed in; a signed-in user who is not an
  ;; admin must not manage tenants either (BOU-568).
  (let [tenant (create! "acme-corp")
        routes (:api (tenant-http/tenant-routes *service* *ctx* {}))
        call   (fn [path method user]
                 (let [data    (some (fn [[p d]] (when (= p path) d)) routes)
                       handler (reduce (fn [h mw] (mw h))
                                       (get-in data [method :handler])
                                       (reverse (:middleware data)))]
                   (handler {:request-method method
                             :uri            (str "/api/v1" path)
                             :path-params    {:id (str (:id tenant))}
                             :params         {}
                             :user           user})))]
    (doseq [[path method] [["/tenants" :get] ["/tenants" :post]
                           ["/tenants/:id" :get] ["/tenants/:id" :put] ["/tenants/:id" :delete]
                           ["/tenants/:id/suspend" :post] ["/tenants/:id/activate" :post]
                           ["/tenants/:id/provision" :post]]]
      (testing (str (name method) " " path)
        (is (= 403 (:status (call path method {:id (UUID/randomUUID) :role :user}))))))
    (testing "an admin gets through"
      (is (= 200 (:status (call "/tenants" :get {:id (UUID/randomUUID) :role :admin})))))))
