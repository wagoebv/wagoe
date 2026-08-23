(ns wagoe.tenant.shell.tenant-middleware-test
  "Tests for multi-tenant HTTP middleware."
  (:require [clojure.test :refer [deftest testing is]]
            [wagoe.platform.database :as db]
            [wagoe.tenant.shell.tenant-middleware :as tenant-mw]
            [wagoe.platform.ports.database]
            [wagoe.tenant.ports :as tenant-ports]
            [next.jdbc])
  (:import (java.util UUID)))

;; =============================================================================
;; Test Data
;; =============================================================================

(def test-tenant-1
  {:id (UUID/fromString "11111111-1111-1111-1111-111111111111")
   :slug "acme-corp"
   :name "Acme Corporation"
   :schema-name "tenant_acme_corp"
   :status :active
   :settings {}
   :created-at (java.time.Instant/parse "2024-01-01T00:00:00Z")})

(def test-tenant-2
  {:id (UUID/fromString "22222222-2222-2222-2222-222222222222")
   :slug "widgets-inc"
   :name "Widgets Inc"
   :schema-name "tenant_widgets_inc"
   :status :active
   :settings {}
   :created-at (java.time.Instant/parse "2024-01-02T00:00:00Z")})

;; =============================================================================
;; Mock Tenant Service
;; =============================================================================

(defrecord MockTenantService [tenants-atom]
  tenant-ports/ITenantService

  (get-tenant [_ tenant-id]
    (first (filter #(= (:id %) tenant-id) @tenants-atom)))

  (get-tenant-by-slug [_ slug]
    (first (filter #(= (:slug %) slug) @tenants-atom)))

  (list-tenants [_ _options]
    @tenants-atom)

  (create-new-tenant [_ tenant-input]
    (let [new-tenant (assoc tenant-input :id (UUID/randomUUID))]
      (swap! tenants-atom conj new-tenant)
      new-tenant))

  (update-existing-tenant [_ _tenant-id _update-data]
    (throw (UnsupportedOperationException. "Not implemented in mock")))

  (delete-existing-tenant [_ _tenant-id]
    (throw (UnsupportedOperationException. "Not implemented in mock")))

  (suspend-tenant [_ _tenant-id]
    (throw (UnsupportedOperationException. "Not implemented in mock")))

  (activate-tenant [_ _tenant-id]
    (throw (UnsupportedOperationException. "Not implemented in mock"))))

(defn create-mock-tenant-service
  ([]
   (create-mock-tenant-service [test-tenant-1 test-tenant-2]))
  ([tenants]
   (->MockTenantService (atom tenants))))

;; =============================================================================
;; Mock Database Context
;; =============================================================================

(defn create-mock-db-context
  "Create mock database context that tracks schema switches."
  []
  (let [schema-calls (atom [])
        mock-datasource (proxy [Object clojure.lang.ILookup] []
                          (valAt
                            ([_k] nil)
                            ([_k _default] nil)))
        mock-adapter (reify
                       wagoe.platform.ports.database/DBAdapter
                       (dialect [_] :postgresql)
                       (jdbc-driver [_] "org.postgresql.Driver")
                       (jdbc-url [_ _db-config] "jdbc:postgresql://localhost:5432/test")
                       (pool-defaults [_] {:minimum-idle 1 :maximum-pool-size 5})
                       (init-connection! [_ _datasource _db-config] nil)
                       (build-where [_ _filters] nil)
                       (boolean->db [_ bool-val] bool-val)
                       (db->boolean [_ db-val] db-val)
                       (table-exists? [_ _datasource _table-name] false)
                       (get-table-info [_ _datasource _table-name] []))]
    {:datasource mock-datasource
     :adapter mock-adapter
     :schema-calls-atom schema-calls  ; Store atom in context for test access
     :get-schema-calls (fn [] @schema-calls)}))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn simple-handler
  "Simple handler that returns tenant info."
  [request]
  {:status 200
   :body {:tenant (:tenant request)
          :uri (:uri request)}})

(defn schema-tracking-handler
  "Handler that returns current schema (for testing schema switching)."
  [request]
  {:status 200
   :body {:tenant (:tenant request)
          :uri (:uri request)}})

;; =============================================================================
;; Tenant Resolution Tests
;; =============================================================================

(deftest ^:unit wrap-tenant-resolution-subdomain-test
  (testing "resolves tenant from subdomain"
    (let [tenant-service (create-mock-tenant-service)
          handler (tenant-mw/wrap-tenant-resolution simple-handler tenant-service)
          request {:server-name "acme-corp.myapp.com"
                   :uri "/api/users"
                   :request-method :get}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "acme-corp" (get-in response [:body :tenant :slug])))
      (is (= "tenant_acme_corp" (get-in response [:body :tenant :schema-name])))))

  (testing "handles subdomain with multiple dots"
    (let [tenant-service (create-mock-tenant-service)
          handler (tenant-mw/wrap-tenant-resolution simple-handler tenant-service)
          request {:server-name "widgets-inc.myapp.example.com"
                   :uri "/api/users"
                   :request-method :get}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "widgets-inc" (get-in response [:body :tenant :slug])))))

  (testing "ignores localhost"
    (let [tenant-service (create-mock-tenant-service)
          handler (tenant-mw/wrap-tenant-resolution simple-handler tenant-service)
          request {:server-name "localhost:3000"
                   :uri "/api/users"
                   :request-method :get}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (nil? (get-in response [:body :tenant]))))) ; No tenant = continues without tenant

  (testing "ignores single-domain names"
    (let [tenant-service (create-mock-tenant-service)
          handler (tenant-mw/wrap-tenant-resolution simple-handler tenant-service)
          request {:server-name "myapp.com"
                   :uri "/api/users"
                   :request-method :get}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (nil? (get-in response [:body :tenant]))))))

(deftest ^:unit wrap-tenant-resolution-jwt-test
  (testing "resolves tenant from JWT slug claim"
    (let [tenant-service (create-mock-tenant-service)
          handler (tenant-mw/wrap-tenant-resolution simple-handler tenant-service)
          request {:server-name "myapp.com"
                   :identity {:user-id (UUID/randomUUID)
                              :tenant-slug "acme-corp"}
                   :uri "/api/users"
                   :request-method :get}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "acme-corp" (get-in response [:body :tenant :slug])))))

  (testing "resolves tenant from JWT ID claim"
    (let [tenant-service (create-mock-tenant-service)
          tenant-id (:id test-tenant-1)
          handler (tenant-mw/wrap-tenant-resolution simple-handler tenant-service)
          request {:server-name "myapp.com"
                   :identity {:user-id (UUID/randomUUID)
                              :tenant-id tenant-id}
                   :uri "/api/users"
                   :request-method :get}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= tenant-id (get-in response [:body :tenant :id]))))))

(deftest ^:unit wrap-tenant-resolution-header-test
  (testing "resolves tenant from X-Tenant-Slug header"
    (let [tenant-service (create-mock-tenant-service)
          handler (tenant-mw/wrap-tenant-resolution simple-handler tenant-service)
          request {:server-name "myapp.com"
                   :headers {"x-tenant-slug" "widgets-inc"}
                   :uri "/api/users"
                   :request-method :get}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "widgets-inc" (get-in response [:body :tenant :slug])))))

  (testing "resolves tenant from X-Tenant-Id header"
    (let [tenant-service (create-mock-tenant-service)
          tenant-id (:id test-tenant-2)
          handler (tenant-mw/wrap-tenant-resolution simple-handler tenant-service)
          request {:server-name "myapp.com"
                   :headers {"x-tenant-id" (str tenant-id)}
                   :uri "/api/users"
                   :request-method :get}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= tenant-id (get-in response [:body :tenant :id]))))))

(deftest ^:unit wrap-tenant-resolution-priority-test
  (testing "subdomain takes priority over JWT"
    (let [tenant-service (create-mock-tenant-service)
          handler (tenant-mw/wrap-tenant-resolution simple-handler tenant-service)
          request {:server-name "acme-corp.myapp.com"
                   :identity {:tenant-slug "widgets-inc"} ; Different tenant in JWT
                   :uri "/api/users"
                   :request-method :get}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "acme-corp" (get-in response [:body :tenant :slug])))))

  (testing "JWT takes priority over header"
    (let [tenant-service (create-mock-tenant-service)
          handler (tenant-mw/wrap-tenant-resolution simple-handler tenant-service)
          request {:server-name "myapp.com"
                   :identity {:tenant-slug "acme-corp"}
                   :headers {"x-tenant-slug" "widgets-inc"} ; Different tenant in header
                   :uri "/api/users"
                   :request-method :get}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "acme-corp" (get-in response [:body :tenant :slug]))))))

(deftest ^:unit wrap-tenant-resolution-not-found-test
  (testing "returns 404 when tenant not found and required"
    (let [tenant-service (create-mock-tenant-service)
          handler (tenant-mw/wrap-tenant-resolution
                   simple-handler
                   tenant-service
                   {:require-tenant? true})
          request {:server-name "nonexistent.myapp.com"
                   :uri "/api/users"
                   :request-method :get}
          response (handler request)]

      (is (= 404 (:status response)))
      (is (= "Tenant not found" (get-in response [:body :error])))))

  (testing "continues without tenant when not required"
    (let [tenant-service (create-mock-tenant-service)
          handler (tenant-mw/wrap-tenant-resolution
                   simple-handler
                   tenant-service
                   {:require-tenant? false})
          request {:server-name "nonexistent.myapp.com"
                   :uri "/api/users"
                   :request-method :get}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (nil? (get-in response [:body :tenant])))))

  (testing "returns 404 when no tenant identifier and required"
    (let [tenant-service (create-mock-tenant-service)
          handler (tenant-mw/wrap-tenant-resolution
                   simple-handler
                   tenant-service
                   {:require-tenant? true})
          request {:server-name "myapp.com" ; No subdomain
                   :uri "/api/users"
                   :request-method :get}
          response (handler request)]

      (is (= 404 (:status response)))
      (is (= "No tenant identifier in request" (get-in response [:body :message]))))))

(deftest ^:unit wrap-tenant-resolution-caching-test
  (testing "caches tenant lookup"
    (let [lookup-count (atom 0)
          tenants-atom (atom [test-tenant-1])
          tenant-service (reify tenant-ports/ITenantService
                           (get-tenant [_ _] nil)
                           (get-tenant-by-slug [_ slug]
                             (swap! lookup-count inc)
                             (first (filter #(= (:slug %) slug) @tenants-atom)))
                           (list-tenants [_ _] @tenants-atom)
                           (create-new-tenant [_ _] nil)
                           (update-existing-tenant [_ _ _] nil)
                           (delete-existing-tenant [_ _] nil)
                           (suspend-tenant [_ _] nil)
                           (activate-tenant [_ _] nil))
          cache (tenant-mw/wrap-tenant-resolution simple-handler tenant-service)
          handler cache
          request {:server-name "acme-corp.myapp.com"
                   :uri "/api/users"
                   :request-method :get}]

      ;; First request - should hit database
      (handler request)
      (is (= 1 @lookup-count))

      ;; Second request - should use cache
      (handler request)
      (is (= 1 @lookup-count)) ; Still 1 - cached!

      ;; Third request - should still use cache
      (handler request)
      (is (= 1 @lookup-count))))) ; Still 1 - cached!

;; =============================================================================
;; Schema Switching Tests
;; =============================================================================

(deftest ^:unit wrap-tenant-schema-test
  (testing "switches schema when tenant present"
    (let [db-ctx (create-mock-db-context)
          handler (tenant-mw/wrap-tenant-schema schema-tracking-handler db-ctx)]
      (with-redefs [db/with-transaction* (fn [ctx f] (f ctx))
                    next.jdbc/execute! (fn [_ds [sql]]
                                         (swap! (:schema-calls-atom db-ctx) conj sql)
                                         nil)]
        (let [request {:tenant test-tenant-1
                       :uri "/api/users"
                       :request-method :get}
              response (handler request)]

          (is (= 200 (:status response)))
          (is (= ["SET search_path TO tenant_acme_corp, public"]
                 ((:get-schema-calls db-ctx))))))))

  (testing "does not switch schema when no tenant"
    (let [db-ctx (create-mock-db-context)
          handler (tenant-mw/wrap-tenant-schema schema-tracking-handler db-ctx)]
      (with-redefs [next.jdbc/execute! (fn [_ds [sql]]
                                         (swap! (:schema-calls-atom db-ctx) conj sql)
                                         nil)]
        (let [request {:uri "/api/users"
                       :request-method :get}
              response (handler request)]

          (is (= 200 (:status response)))
          (is (empty? ((:get-schema-calls db-ctx))))))))  ; No schema switch

  (testing "handles schema switch failure gracefully"
    (let [db-ctx (create-mock-db-context)
          handler (tenant-mw/wrap-tenant-schema schema-tracking-handler db-ctx)]
      (with-redefs [db/with-transaction* (fn [ctx f] (f ctx))
                    next.jdbc/execute! (fn [_ _] (throw (Exception. "Schema not found")))]
        (let [request {:tenant test-tenant-1
                       :uri "/api/users"
                       :request-method :get}
              response (handler request)]

          (is (= 500 (:status response)))  ; Should return 500 on error
          (is (= "Internal server error" (get-in response [:body :error]))))))))

;; =============================================================================
;; Combined Middleware Tests
;; =============================================================================

(deftest ^:unit wrap-multi-tenant-test
  (testing "combined middleware resolves tenant and switches schema"
    (let [tenant-service (create-mock-tenant-service)
          db-ctx (create-mock-db-context)
          handler (tenant-mw/wrap-multi-tenant
                   schema-tracking-handler
                   tenant-service
                   db-ctx)]
      (with-redefs [db/with-transaction* (fn [ctx f] (f ctx))
                    next.jdbc/execute! (fn [_ds [sql]]
                                         (swap! (:schema-calls-atom db-ctx) conj sql)
                                         nil)]
        (let [request {:server-name "acme-corp.myapp.com"
                       :uri "/api/users"
                       :request-method :get}
              response (handler request)]

          (is (= 200 (:status response)))
          (is (= "acme-corp" (get-in response [:body :tenant :slug])))
          (is (= ["SET search_path TO tenant_acme_corp, public"]
                 ((:get-schema-calls db-ctx))))))))

  (testing "combined middleware with require-tenant option"
    (let [tenant-service (create-mock-tenant-service)
          db-ctx (create-mock-db-context)
          handler (tenant-mw/wrap-multi-tenant
                   schema-tracking-handler
                   tenant-service
                   db-ctx
                   {:require-tenant? true})]
      (with-redefs [db/with-transaction* (fn [ctx f] (f ctx))
                    next.jdbc/execute! (fn [_ds [sql]]
                                         (swap! (:schema-calls-atom db-ctx) conj sql)
                                         nil)]
        (let [request {:server-name "nonexistent.myapp.com"
                       :uri "/api/users"
                       :request-method :get}
              response (handler request)]

          (is (= 404 (:status response)))
          (is (= "Tenant not found" (get-in response [:body :error])))))))

  (testing "combined middleware without tenant (optional)"
    (let [tenant-service (create-mock-tenant-service)
          db-ctx (create-mock-db-context)
          handler (tenant-mw/wrap-multi-tenant
                   schema-tracking-handler
                   tenant-service
                   db-ctx
                   {:require-tenant? false})]
      (with-redefs [db/with-transaction* (fn [ctx f] (f ctx))
                    next.jdbc/execute! (fn [_ds [sql]]
                                         (swap! (:schema-calls-atom db-ctx) conj sql)
                                         nil)]
        (let [request {:server-name "localhost"
                       :uri "/health"
                       :request-method :get}
              response (handler request)]

          (is (= 200 (:status response)))
          (is (nil? (get-in response [:body :tenant])))
          (is (empty? ((:get-schema-calls db-ctx)))))))))  ; No schema switch for localhost) ; No schema switch
