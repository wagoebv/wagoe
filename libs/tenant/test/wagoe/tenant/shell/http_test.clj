(ns wagoe.tenant.shell.http-test
  "Contract tests for tenant HTTP endpoints.

   Tests cover:
   - Tenant list endpoint (GET /api/v1/tenants)
   - Tenant create endpoint (POST /api/v1/tenants)
   - Tenant detail endpoint (GET /api/v1/tenants/:id)
   - Tenant update endpoint (PUT /api/v1/tenants/:id)
   - Tenant delete endpoint (DELETE /api/v1/tenants/:id)
   - Tenant suspend endpoint (POST /api/v1/tenants/:id/suspend)
   - Tenant activate endpoint (POST /api/v1/tenants/:id/activate)
   - Tenant provision endpoint (POST /api/v1/tenants/:id/provision)
   - JSON request/response handling
   - Validation errors
   - Error responses (400, 404, 500, 501)"
  (:require [wagoe.tenant.shell.http :as tenant-http]
            [wagoe.tenant.ports :as tenant-ports]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.util UUID]))

^{:kaocha.testable/meta {:contract true :tenant true}}

;; =============================================================================
;; Test Configuration and Mocks
;; =============================================================================

(def ^:dynamic *mock-tenant-service* nil)
(def ^:dynamic *test-tenants* nil)

(def sample-tenant-1
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :name "ACME Corporation"
   :slug "acme-corp"
   :schema-name "tenant_acme_corp"
   :status :active
   :created-at #inst "2024-01-01T00:00:00Z"
   :updated-at #inst "2024-01-01T00:00:00Z"})

(def sample-tenant-2
  {:id #uuid "00000000-0000-0000-0000-000000000002"
   :name "Beta Inc"
   :slug "beta-inc"
   :schema-name "tenant_beta_inc"
   :status :suspended
   :created-at #inst "2024-01-02T00:00:00Z"
   :updated-at #inst "2024-01-02T00:00:00Z"})

(defrecord MockTenantService [tenants]
  tenant-ports/ITenantService

  (list-tenants [_ options]
    (let [{:keys [limit offset status search]} options
          filtered (cond->> @tenants
                     status (filter #(= status (:status %)))
                     search (filter #(or (str/includes? (str/lower-case (:name %))
                                                        (str/lower-case search))
                                         (str/includes? (str/lower-case (:slug %))
                                                        (str/lower-case search)))))
          offset (or offset 0)
          limit (or limit 20)
          paginated (take limit (drop offset filtered))]
      {:tenants (vec paginated)
       :total (count filtered)
       :limit limit
       :offset offset}))

  (get-tenant [_ tenant-id]
    (->> @tenants
         (filter #(= tenant-id (:id %)))
         first))

  (get-tenant-by-slug [_ slug]
    (->> @tenants
         (filter #(= slug (:slug %)))
         first))

  (create-new-tenant [_ tenant-input]
    (let [schema-name (str/replace (:slug tenant-input) "-" "_")
          new-tenant (assoc tenant-input
                            :id (UUID/randomUUID)
                            :schema-name (str "tenant_" schema-name)
                            :created-at (java.time.Instant/now)
                            :updated-at (java.time.Instant/now))]
      (swap! tenants conj new-tenant)
      {:success? true :tenant new-tenant}))

  (update-existing-tenant [_ tenant-id update-data]
    (if-let [tenant (->> @tenants
                         (filter #(= tenant-id (:id %)))
                         first)]
      (let [updated-tenant (merge tenant
                                  update-data
                                  {:updated-at (java.time.Instant/now)})]
        (swap! tenants (fn [ts]
                         (mapv #(if (= (:id %) tenant-id)
                                  updated-tenant
                                  %)
                               ts)))
        {:success? true :tenant updated-tenant})
      {:success? false :error "Tenant not found"}))

  (delete-existing-tenant [_ tenant-id]
    (if (->> @tenants
             (some #(= tenant-id (:id %))))
      (do
        (swap! tenants (fn [ts]
                         (filterv #(not= (:id %) tenant-id) ts)))
        {:success? true})
      {:success? false :error "Tenant not found"}))

  (suspend-tenant [_ tenant-id]
    (if-let [tenant (->> @tenants
                         (filter #(= tenant-id (:id %)))
                         first)]
      (let [updated-tenant (assoc tenant :status :suspended
                                  :updated-at (java.time.Instant/now))]
        (swap! tenants (fn [ts]
                         (mapv #(if (= (:id %) tenant-id)
                                  updated-tenant
                                  %)
                               ts)))
        {:success? true :tenant updated-tenant})
      {:success? false :error "Tenant not found"}))

  (activate-tenant [_ tenant-id]
    (if-let [tenant (->> @tenants
                         (filter #(= tenant-id (:id %)))
                         first)]
      (let [updated-tenant (assoc tenant :status :active
                                  :updated-at (java.time.Instant/now))]
        (swap! tenants (fn [ts]
                         (mapv #(if (= (:id %) tenant-id)
                                  updated-tenant
                                  %)
                               ts)))
        {:success? true :tenant updated-tenant})
      {:success? false :error "Tenant not found"})))

(defn setup-mock-service! []
  (let [tenants (atom [sample-tenant-1 sample-tenant-2])]
    (alter-var-root #'*mock-tenant-service*
                    (constantly (->MockTenantService tenants)))
    (alter-var-root #'*test-tenants* (constantly tenants))))

(defn teardown-mock-service! []
  (alter-var-root #'*mock-tenant-service* (constantly nil))
  (alter-var-root #'*test-tenants* (constantly nil)))

(use-fixtures :each
  (fn [f]
    (setup-mock-service!)
    (f)
    (teardown-mock-service!)))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn parse-json-body
  "Parse JSON response body."
  [response]
  (when-let [body (:body response)]
    (json/parse-string body true)))

(defn make-request
  "Create a test HTTP request."
  ([method uri]
   (make-request method uri nil nil))
  ([method uri params]
   (make-request method uri params nil))
  ([method uri params body]
   {:request-method method
    :uri uri
    :params (or params {})
    :body-params body
    :path-params {}}))

;; =============================================================================
;; List Tenants Tests
;; =============================================================================

(deftest ^:contract list-tenants-handler-test
  (testing "lists all tenants with default pagination"
    (let [handler (tenant-http/list-tenants-handler *mock-tenant-service*)
          request (make-request :get "/api/v1/tenants")
          response (handler request)
          body (parse-json-body response)]
      (is (= 200 (:status response)))
      (is (= 2 (count (:tenants body))))
      (is (= 2 (:total body)))
      (is (= 20 (:limit body)))
      (is (= 0 (:offset body)))))

  (testing "respects limit parameter"
    (let [handler (tenant-http/list-tenants-handler *mock-tenant-service*)
          request (make-request :get "/api/v1/tenants" {:limit "1"})
          response (handler request)
          body (parse-json-body response)]
      (is (= 200 (:status response)))
      (is (= 1 (count (:tenants body))))
      (is (= 2 (:total body)))
      (is (= 1 (:limit body)))))

  (testing "respects offset parameter"
    (let [handler (tenant-http/list-tenants-handler *mock-tenant-service*)
          request (make-request :get "/api/v1/tenants" {:offset "1"})
          response (handler request)
          body (parse-json-body response)]
      (is (= 200 (:status response)))
      (is (= 1 (count (:tenants body))))
      (is (= 1 (:offset body)))))

  (testing "filters by status"
    (let [handler (tenant-http/list-tenants-handler *mock-tenant-service*)
          request (make-request :get "/api/v1/tenants" {:status "active"})
          response (handler request)
          body (parse-json-body response)]
      (is (= 200 (:status response)))
      (is (= 1 (count (:tenants body))))
      (is (= :active (-> body :tenants first :status keyword)))))

  (testing "searches by name"
    (let [handler (tenant-http/list-tenants-handler *mock-tenant-service*)
          request (make-request :get "/api/v1/tenants" {:search "ACME"})
          response (handler request)
          body (parse-json-body response)]
      (is (= 200 (:status response)))
      (is (= 1 (count (:tenants body))))
      (is (= "ACME Corporation" (-> body :tenants first :name))))))

;; =============================================================================
;; Get Tenant Tests
;; =============================================================================

(deftest ^:contract get-tenant-handler-test
  (testing "retrieves existing tenant by ID"
    (let [handler (tenant-http/get-tenant-handler *mock-tenant-service*)
          request (-> (make-request :get "/api/v1/tenants/00000000-0000-0000-0000-000000000001")
                      (assoc :path-params {:id "00000000-0000-0000-0000-000000000001"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 200 (:status response)))
      (is (= "ACME Corporation" (:name body)))
      (is (= "acme-corp" (:slug body)))))

  (testing "returns 404 for non-existent tenant"
    (let [handler (tenant-http/get-tenant-handler *mock-tenant-service*)
          request (-> (make-request :get "/api/v1/tenants/99999999-9999-9999-9999-999999999999")
                      (assoc :path-params {:id "99999999-9999-9999-9999-999999999999"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 404 (:status response)))
      (is (= "Tenant not found" (:error body)))))

  (testing "returns 400 for invalid UUID format"
    (let [handler (tenant-http/get-tenant-handler *mock-tenant-service*)
          request (-> (make-request :get "/api/v1/tenants/invalid-uuid")
                      (assoc :path-params {:id "invalid-uuid"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 400 (:status response)))
      (is (= "Invalid tenant ID format" (:error body))))))

;; =============================================================================
;; Create Tenant Tests
;; =============================================================================

(deftest ^:contract create-tenant-handler-test
  (testing "creates new tenant successfully"
    (let [handler (tenant-http/create-tenant-handler *mock-tenant-service*)
          request (make-request :post "/api/v1/tenants" {}
                                {:name "New Tenant"
                                 :slug "new-tenant"
                                 :status "active"})
          response (handler request)
          body (parse-json-body response)]
      (is (= 201 (:status response)))
      (is (= "New Tenant" (:name body)))
      (is (= "new-tenant" (:slug body)))
      (is (= "tenant_new_tenant" (:schema-name body)))))

  (testing "defaults status to active"
    (let [handler (tenant-http/create-tenant-handler *mock-tenant-service*)
          request (make-request :post "/api/v1/tenants" {}
                                {:name "Default Status"
                                 :slug "default-status"})
          response (handler request)
          body (parse-json-body response)]
      (is (= 201 (:status response)))
      (is (= :active (keyword (:status body))))))

  (testing "returns validation error for missing name"
    (let [handler (tenant-http/create-tenant-handler *mock-tenant-service*)
          request (make-request :post "/api/v1/tenants" {}
                                {:slug "no-name"})
          response (handler request)
          body (parse-json-body response)]
      (is (= 400 (:status response)))
      (is (= "Validation failed" (:error body)))
      (is (contains? (:details body) :validation-errors))))

  (testing "returns validation error for missing slug"
    (let [handler (tenant-http/create-tenant-handler *mock-tenant-service*)
          request (make-request :post "/api/v1/tenants" {}
                                {:name "No Slug"})
          response (handler request)
          body (parse-json-body response)]
      (is (= 400 (:status response)))
      (is (= "Validation failed" (:error body))))))

;; =============================================================================
;; Update Tenant Tests
;; =============================================================================

(deftest ^:contract update-tenant-handler-test
  (testing "updates tenant successfully"
    (let [handler (tenant-http/update-tenant-handler *mock-tenant-service*)
          request (-> (make-request :put "/api/v1/tenants/00000000-0000-0000-0000-000000000001" {}
                                    {:name "Updated ACME"})
                      (assoc :path-params {:id "00000000-0000-0000-0000-000000000001"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 200 (:status response)))
      (is (= "Updated ACME" (:name body)))
      (is (= "acme-corp" (:slug body)))))

  (testing "updates multiple fields"
    (let [handler (tenant-http/update-tenant-handler *mock-tenant-service*)
          request (-> (make-request :put "/api/v1/tenants/00000000-0000-0000-0000-000000000001" {}
                                    {:name "New Name"
                                     :slug "new-slug"
                                     :status "suspended"})
                      (assoc :path-params {:id "00000000-0000-0000-0000-000000000001"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 200 (:status response)))
      (is (= "New Name" (:name body)))
      (is (= "new-slug" (:slug body)))
      (is (= :suspended (keyword (:status body))))))

  (testing "returns 400 for invalid UUID format"
    (let [handler (tenant-http/update-tenant-handler *mock-tenant-service*)
          request (-> (make-request :put "/api/v1/tenants/invalid-uuid" {}
                                    {:name "Updated"})
                      (assoc :path-params {:id "invalid-uuid"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 400 (:status response)))
      (is (= "Invalid tenant ID format" (:error body))))))

;; =============================================================================
;; Delete Tenant Tests
;; =============================================================================

(deftest ^:contract delete-tenant-handler-test
  (testing "deletes tenant successfully"
    (let [handler (tenant-http/delete-tenant-handler *mock-tenant-service*)
          request (-> (make-request :delete "/api/v1/tenants/00000000-0000-0000-0000-000000000001")
                      (assoc :path-params {:id "00000000-0000-0000-0000-000000000001"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 200 (:status response)))
      (is (= "Tenant deleted successfully" (:message body)))))

  (testing "returns 400 for non-existent tenant"
    (let [handler (tenant-http/delete-tenant-handler *mock-tenant-service*)
          request (-> (make-request :delete "/api/v1/tenants/99999999-9999-9999-9999-999999999999")
                      (assoc :path-params {:id "99999999-9999-9999-9999-999999999999"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 400 (:status response)))
      (is (= "Tenant not found" (:error body)))))

  (testing "returns 400 for invalid UUID format"
    (let [handler (tenant-http/delete-tenant-handler *mock-tenant-service*)
          request (-> (make-request :delete "/api/v1/tenants/invalid-uuid")
                      (assoc :path-params {:id "invalid-uuid"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 400 (:status response)))
      (is (= "Invalid tenant ID format" (:error body))))))

;; =============================================================================
;; Suspend Tenant Tests
;; =============================================================================

(deftest ^:contract suspend-tenant-handler-test
  (testing "suspends active tenant successfully"
    (let [handler (tenant-http/suspend-tenant-handler *mock-tenant-service*)
          request (-> (make-request :post "/api/v1/tenants/00000000-0000-0000-0000-000000000001/suspend")
                      (assoc :path-params {:id "00000000-0000-0000-0000-000000000001"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 200 (:status response)))
      (is (= "Tenant suspended successfully" (:message body)))))

  (testing "returns 400 for non-existent tenant"
    (let [handler (tenant-http/suspend-tenant-handler *mock-tenant-service*)
          request (-> (make-request :post "/api/v1/tenants/99999999-9999-9999-9999-999999999999/suspend")
                      (assoc :path-params {:id "99999999-9999-9999-9999-999999999999"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 400 (:status response)))
      (is (= "Tenant not found" (:error body)))))

  (testing "returns 400 for invalid UUID format"
    (let [handler (tenant-http/suspend-tenant-handler *mock-tenant-service*)
          request (-> (make-request :post "/api/v1/tenants/invalid-uuid/suspend")
                      (assoc :path-params {:id "invalid-uuid"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 400 (:status response)))
      (is (= "Invalid tenant ID format" (:error body))))))

;; =============================================================================
;; Activate Tenant Tests
;; =============================================================================

(deftest ^:contract activate-tenant-handler-test
  (testing "activates suspended tenant successfully"
    (let [handler (tenant-http/activate-tenant-handler *mock-tenant-service*)
          request (-> (make-request :post "/api/v1/tenants/00000000-0000-0000-0000-000000000002/activate")
                      (assoc :path-params {:id "00000000-0000-0000-0000-000000000002"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 200 (:status response)))
      (is (= "Tenant activated successfully" (:message body)))))

  (testing "returns 400 for non-existent tenant"
    (let [handler (tenant-http/activate-tenant-handler *mock-tenant-service*)
          request (-> (make-request :post "/api/v1/tenants/99999999-9999-9999-9999-999999999999/activate")
                      (assoc :path-params {:id "99999999-9999-9999-9999-999999999999"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 400 (:status response)))
      (is (= "Tenant not found" (:error body)))))

  (testing "returns 400 for invalid UUID format"
    (let [handler (tenant-http/activate-tenant-handler *mock-tenant-service*)
          request (-> (make-request :post "/api/v1/tenants/invalid-uuid/activate")
                      (assoc :path-params {:id "invalid-uuid"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 400 (:status response)))
      (is (= "Invalid tenant ID format" (:error body))))))

;; =============================================================================
;; Provision Tenant Tests
;; =============================================================================

(deftest ^:contract provision-tenant-handler-test
  (testing "returns 500 when database context not available"
    (let [handler (tenant-http/provision-tenant-handler *mock-tenant-service* nil)
          request (-> (make-request :post "/api/v1/tenants/00000000-0000-0000-0000-000000000001/provision")
                      (assoc :path-params {:id "00000000-0000-0000-0000-000000000001"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 500 (:status response)))
      (is (= "Database context not available" (:error body)))))

  (testing "returns 400 for invalid UUID format"
    (let [handler (tenant-http/provision-tenant-handler *mock-tenant-service* nil)
          request (-> (make-request :post "/api/v1/tenants/invalid-uuid/provision")
                      (assoc :path-params {:id "invalid-uuid"}))
          response (handler request)
          body (parse-json-body response)]
      (is (= 400 (:status response)))
      (is (= "Invalid tenant ID format" (:error body))))))

;; =============================================================================
;; Routes Structure Tests
;; =============================================================================

(deftest ^:contract tenant-routes-normalized-test
  (testing "returns normalized route structure"
    (let [routes (tenant-http/tenant-routes-normalized *mock-tenant-service* nil {})]
      (is (map? routes))
      (is (contains? routes :api))
      (is (vector? (:api routes)))
      (is (= 5 (count (:api routes))))))

  (testing "every route is Reitit data with real handlers"
    ;; [path {method {:handler f}}] — ADR-037.
    (let [routes (tenant-http/tenant-routes-normalized *mock-tenant-service* nil {})]
      (doseq [route (:api routes)]
        (is (vector? route))
        (is (string? (first route)))
        (is (map? (second route)))
        (doseq [[_method config] (second route)]
          (is (contains? config :handler))
          (is (fn? (:handler config))))))))
