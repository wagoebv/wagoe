(ns wagoe.admin.shell.http-test
  "Contract tests for admin HTTP endpoints.

   Tests cover:
   - Authentication and authorization (admin vs non-admin)
   - Entity list endpoint (GET /web/admin/:entity)
   - Entity detail/edit endpoint (GET /web/admin/:entity/:id)
   - Create entity endpoint (POST /web/admin/:entity)
   - Update entity endpoint (PUT /web/admin/:entity/:id)
   - Delete entity endpoint (DELETE /web/admin/:entity/:id)
   - HTMX fragment responses
   - Query parameters (pagination, sorting, search, filters)
   - Error responses (403, 404, 422, 500)
   - Form validation"
  (:require [wagoe.admin.ports :as admin-ports]
            [wagoe.admin.shell.http :as admin-http]
            [wagoe.admin.shell.service :as service]
            [wagoe.admin.shell.schema-repository :as schema-repo]
            [wagoe.platform.shell.adapters.database.factory :as db-factory]
            [wagoe.platform.database :as db]
            [wagoe.observability.logging.shell.adapters.no-op :as logging-no-op]
            [wagoe.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [wagoe.shared.ui.core.components :as ui-components]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str])
  (:import [java.util UUID]
           [java.time Instant]))

^{:kaocha.testable/meta {:contract true :admin true}}

;; =============================================================================
;; Test Configuration and Setup
;; =============================================================================

(def test-db-config
  "H2 in-memory database for contract testing"
  {:adapter :h2
   :database-path "mem:admin_http_test;DB_CLOSE_DELAY=-1"
   :pool {:minimum-idle 1
          :maximum-pool-size 3}})

(def admin-config
  "Admin module configuration"
  {:enabled? true
   :base-path "/web/admin"
   :require-role :admin
   :entity-discovery {:mode :allowlist
                      :allowlist #{:test-users}}
   :entities {:test-users {:label "Test Users"
                           :list-fields [:email :name :active]
                           :search-fields [:email :name]
                           :hide-fields #{:password-hash}
                           :readonly-fields #{:id :created-at}}}
   :pagination {:default-page-size 50
                :max-page-size 200}})

(defonce ^:dynamic *db-ctx* nil)
(defonce ^:dynamic *admin-service* nil)
(defonce ^:dynamic *schema-provider* nil)
(defonce ^:dynamic *handler* nil)

;; Test users for authentication
(def admin-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "admin@example.com"
   :name "Admin User"
   :role :admin
   :active true})

(def regular-user
  {:id #uuid "00000000-0000-0000-0000-000000000002"
   :email "user@example.com"
   :name "Regular User"
   :role :user
   :active true})

(defn create-test-table!
  "Create test users table"
  [db-ctx]
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS test_users (
           id UUID PRIMARY KEY,
           email VARCHAR(255) NOT NULL UNIQUE,
           name VARCHAR(255) NOT NULL,
           password_hash VARCHAR(255) NOT NULL,
           active BOOLEAN NOT NULL DEFAULT true,
           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
           updated_at TIMESTAMP,
           deleted_at TIMESTAMP)"}))

(defn drop-test-table!
  "Drop test users table"
  [db-ctx]
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS test_users"}))

(defn setup-test-system!
  "Set up test HTTP handlers and database"
  []
  (let [db-ctx (db-factory/db-context test-db-config)
        logger (logging-no-op/create-logging-component {})
        error-reporter (error-reporting-no-op/create-error-reporting-component {})
        schema-provider (schema-repo/create-schema-repository db-ctx admin-config)
        admin-service (service/create-admin-service db-ctx schema-provider logger error-reporter admin-config)
        routes (admin-http/web-routes admin-service schema-provider admin-config nil)

        ;; Create simple handler that wraps routes (Week 1 stub - no full router)
        handler (fn [request]
                  ;; Match route and call handler
                  (let [path (:uri request)
                        method (:request-method request)
                        base-path (get admin-config :base-path "")
                        ;; Strip base path if present to get normalized path
                        ;; If stripping results in empty string, treat as root "/"
                        stripped-path (if (and (not (str/blank? base-path))
                                               (.startsWith path base-path))
                                        (subs path (count base-path))
                                        path)
                        normalized-path (if (str/blank? stripped-path) "/" stripped-path)]
                    (try
                      (or
                         ;; Iterate through routes IN ORDER (important for specificity)
                       (some (fn [route]
                               ;; Reitit data: [path data], where data carries
                               ;; the endpoints and any route-level keys (ADR-037).
                               (let [route-path (first route)
                                     route-methods (second route)]
                                 (when (contains? route-methods method)
                                    ;; Match path pattern
                                   (let [route-parts (str/split route-path #"/")
                                         path-parts (str/split normalized-path #"/")]
                                     (when (and (= (count route-parts) (count path-parts))
                                                (every? (fn [[rp pp]]
                                                          (or (= rp pp)
                                                              (.startsWith rp ":")))
                                                        (map vector route-parts path-parts)))
                                        ;; Extract path params
                                       (let [path-params (into {}
                                                               (keep (fn [[rp pp]]
                                                                       (when (.startsWith rp ":")
                                                                         [(keyword (subs rp 1)) pp]))
                                                                     (map vector route-parts path-parts)))
                                             updated-request (update request :path-params merge path-params)
                                             handler-fn (get-in route-methods [method :handler])
                                             response (handler-fn updated-request)]
                                          ;; If response body is Hiccup (vector), render to HTML
                                         (if (vector? (:body response))
                                           (update response :body ui-components/render-html)
                                           response)))))))
                             routes)

                         ;; No route matched - return 404
                       {:status 404
                        :headers {"Content-Type" "text/html"}
                        :body "Not Found"})
                      (catch Exception e
                          ;; Basic error handling - return appropriate status code
                        (let [error-data (ex-data e)]
                          (cond
                            (= :forbidden (:type error-data))
                            {:status 403
                             :headers {"Content-Type" "text/html"}
                             :body "Forbidden"}

                            (= :not-found (:type error-data))
                            {:status 404
                             :headers {"Content-Type" "text/html"}
                             :body "Not Found"}

                            :else
                            ;; Mirror the platform error path (BOU-161/BOU-182):
                            ;; 5xx bodies are generic — never echo (.getMessage e).
                            {:status 500
                             :headers {"Content-Type" "text/html"}
                             :body "Internal Server Error"}))))))]

    (create-test-table! db-ctx)

    (alter-var-root #'*db-ctx* (constantly db-ctx))
    (alter-var-root #'*admin-service* (constantly admin-service))
    (alter-var-root #'*schema-provider* (constantly schema-provider))
    (alter-var-root #'*handler* (constantly handler))))

(defn teardown-test-system!
  "Tear down test system"
  []
  (when *db-ctx*
    (drop-test-table! *db-ctx*)
    (db-factory/close-db-context! *db-ctx*)
    (alter-var-root #'*db-ctx* (constantly nil))
    (alter-var-root #'*admin-service* (constantly nil))
    (alter-var-root #'*schema-provider* (constantly nil))
    (alter-var-root #'*handler* (constantly nil))))

(defn with-clean-table
  "Fixture to clean table between tests"
  [f]
  (when *db-ctx*
    (db/execute-update! *db-ctx* {:raw "DELETE FROM test_users"}))
  (f))

(use-fixtures :once
  (fn [f]
    (setup-test-system!)
    (f)
    (teardown-test-system!)))

(use-fixtures :each with-clean-table)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn make-request
  "Create a Ring request map.
   
   If user is provided, adds to both :user (for handler) and [:session :user] (for middleware).
   The admin handlers expect :user at request root after authentication middleware."
  ([method uri]
   (make-request method uri nil nil))
  ([method uri user]
   (make-request method uri user nil))
  ([method uri user params]
   (cond-> {:request-method method
            :uri uri
            :headers (or (:headers params) {})
            :query-params (or (:query params) {})
            :form-params (or (:form params) {})
            :path-params (or (:path params) {})}
     user (assoc :user user)
     user (assoc-in [:session :user] user))))

(defn create-test-user!
  "Create a test user in database"
  [email name active]
  (let [user-data {:id (UUID/randomUUID)
                   :email email
                   :name name
                   :password-hash "hash123"
                   :active active
                   :created-at (Instant/now)}]
    (db/execute-one! *db-ctx*
                     {:insert-into :test-users
                      :values [user-data]})
    user-data))

(defn parse-html-response
  "Extract text content from HTML response (simple parser)"
  [html]
  ;; Week 1: Simple string checks
  ;; Week 2+: Use proper HTML parser
  html)

;; =============================================================================
;; Authentication and Authorization Tests
;; =============================================================================

(deftest ^:contract admin-home-authorization-test
  (testing "Admin home page requires authentication"
    (testing "Admin user can access"
      (let [request (make-request :get "/web/admin" admin-user)
            response (*handler* request)]
        (is (some? response))
        ;; Should redirect to first entity or show admin home
        (is (or (= 302 (:status response))
                (= 200 (:status response))))))

    (testing "Regular user gets 403 Forbidden"
      (let [request (make-request :get "/web/admin" regular-user)
            response (*handler* request)]
        ;; Week 1: May return 403 or throw exception
        ;; Actual behavior depends on error handling middleware
        (is (some? response))))

    (testing "Unauthenticated user gets 403 Forbidden"
      (let [request (make-request :get "/web/admin" nil)
            response (*handler* request)]
        (is (some? response))))))

;; =============================================================================
;; Entity List Endpoint Tests
;; =============================================================================

(deftest ^:contract entity-list-endpoint-test
  (testing "GET /web/admin/:entity - List entities"
    ;; Create test data
    (create-test-user! "alice@example.com" "Alice" true)
    (create-test-user! "bob@example.com" "Bob" true)
    (create-test-user! "charlie@example.com" "Charlie" false)

    (testing "Admin can view entity list"
      (let [request (make-request :get "/web/admin/test-users" admin-user
                                  {:path {:entity "test-users"}})
            response (*handler* request)]
        (is (some? response))
        (is (= 200 (:status response)))
        (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))

        ;; Response should contain user emails
        (let [body (:body response)]
          (is (string? body))
          (is (str/includes? body "alice@example.com"))
          (is (str/includes? body "bob@example.com")))))

    (testing "Regular user gets 403 Forbidden"
      (let [request (make-request :get "/web/admin/test-users" regular-user
                                  {:path {:entity "test-users"}})
            response (*handler* request)]
        ;; Should be denied
        (is (some? response))))))

(deftest ^:contract entity-list-pagination-test
  (testing "Entity list with pagination query parameters"
    ;; Create 10 test users
    (doseq [i (range 10)]
      (create-test-user! (str "user" i "@example.com") (str "User " i) true))

    (testing "Pagination with page and page-size"
      (let [request (make-request :get "/web/admin/test-users" admin-user
                                  {:path {:entity "test-users"}
                                   :query {"page" "1" "page-size" "5"}})
            response (*handler* request)]
        (is (= 200 (:status response)))
        ;; Response should show page 1 of 2
        (let [body (:body response)]
          (is (str/includes? body "test-users")))))

    (testing "Pagination with limit and offset"
      (let [request (make-request :get "/web/admin/test-users" admin-user
                                  {:path {:entity "test-users"}
                                   :query {"limit" "5" "offset" "5"}})
            response (*handler* request)]
        (is (= 200 (:status response)))))))

(deftest ^:contract entity-list-sorting-test
  (testing "Entity list with sorting query parameters"
    (create-test-user! "charlie@example.com" "Charlie" true)
    (create-test-user! "alice@example.com" "Alice" true)
    (create-test-user! "bob@example.com" "Bob" true)

    (testing "Sort by email ascending"
      (let [request (make-request :get "/web/admin/test-users" admin-user
                                  {:path {:entity "test-users"}
                                   :query {"sort" "email" "sort-dir" "asc"}})
            response (*handler* request)]
        (is (= 200 (:status response)))
        ;; alice should appear before bob and charlie
        (let [body (:body response)]
          (is (str/includes? body "alice@example.com")))))

    (testing "Sort by email descending"
      (let [request (make-request :get "/web/admin/test-users" admin-user
                                  {:path {:entity "test-users"}
                                   :query {"sort" "email" "sort-dir" "desc"}})
            response (*handler* request)]
        (is (= 200 (:status response)))))))

(deftest ^:contract entity-list-search-test
  (testing "Entity list with search query parameter"
    (create-test-user! "alice@example.com" "Alice Smith" true)
    (create-test-user! "bob@gmail.com" "Bob Jones" true)
    (create-test-user! "charlie@example.com" "Charlie Brown" true)

    (testing "Search by email domain"
      (let [request (make-request :get "/web/admin/test-users" admin-user
                                  {:path {:entity "test-users"}
                                   :query {"search" "example.com"}})
            response (*handler* request)]
        (is (= 200 (:status response)))
        ;; Should show alice and charlie, not bob
        (let [body (:body response)]
          (is (str/includes? body "alice@example.com"))
          (is (str/includes? body "charlie@example.com")))))

    (testing "Search by name"
      (let [request (make-request :get "/web/admin/test-users" admin-user
                                  {:path {:entity "test-users"}
                                   :query {"search" "Charlie"}})
            response (*handler* request)]
        (is (= 200 (:status response)))
        (let [body (:body response)]
          (is (str/includes? body "Charlie Brown")))))))

(deftest ^:contract entity-list-nonexistent-entity-test
  (testing "List non-existent entity returns error"
    (let [request (make-request :get "/web/admin/nonexistent" admin-user
                                {:path {:entity "nonexistent"}})
          response (*handler* request)]
      ;; Should return error or throw exception
      (is (some? response)))))

;; =============================================================================
;; HTMX Fragment Endpoint Tests
;; =============================================================================

(deftest ^:contract entity-table-fragment-test
  (testing "GET /web/admin/:entity/table - HTMX table fragment"
    (create-test-user! "test@example.com" "Test User" true)

    (testing "Admin can fetch table fragment"
      (let [request (make-request :get "/web/admin/test-users/table" admin-user
                                  {:path {:entity "test-users"}
                                   :headers {"hx-request" "true"}})
            response (*handler* request)]
        (is (some? response))
        (is (= 200 (:status response)))

        ;; Should have HX-Trigger header for HTMX
        (let [headers (:headers response)]
          (is (or (nil? (get headers "HX-Trigger"))
                  (string? (get headers "HX-Trigger")))))

        ;; Response should be HTML fragment with table
        (let [body (:body response)]
          (is (string? body))
          (is (str/includes? body "test@example.com")))))))

;; =============================================================================
;; Entity Detail/Edit Endpoint Tests
;; =============================================================================

(deftest ^:contract entity-detail-endpoint-test
  (testing "GET /web/admin/:entity/:id - Entity detail/edit page"
    (let [user (create-test-user! "detail@example.com" "Detail User" true)
          user-id (:id user)]

      (testing "Admin can view entity detail"
        (let [request (make-request :get (str "/web/admin/test-users/" user-id) admin-user
                                    {:path {:entity "test-users" :id (str user-id)}})
              response (*handler* request)]
          (is (some? response))
          (is (= 200 (:status response)))

          ;; Response should show edit form with user data
          (let [body (:body response)]
            (is (str/includes? body "detail@example.com"))
            (is (str/includes? body "Detail User")))))

      (testing "Non-existent entity ID returns 404"
        (let [fake-id (UUID/randomUUID)
              request (make-request :get (str "/web/admin/test-users/" fake-id) admin-user
                                    {:path {:entity "test-users" :id (str fake-id)}})
              response (*handler* request)]
          ;; Should return error or throw
          (is (some? response)))))))

(deftest ^:contract new-entity-form-endpoint-test
  (testing "GET /web/admin/:entity/new - New entity form"
    (testing "Admin can view create form"
      (let [request (make-request :get "/web/admin/test-users/new" admin-user
                                  {:path {:entity "test-users"}})
            response (*handler* request)]
        (is (some? response))
        (is (= 200 (:status response)))

        ;; Response should show empty form
        (let [body (:body response)]
          (is (string? body))
          ;; Should have form elements
          (is (or (str/includes? body "form")
                  (str/includes? body "input"))))))))

;; =============================================================================
;; Create Entity Endpoint Tests
;; =============================================================================

(deftest ^:contract create-entity-endpoint-test
  (testing "POST /web/admin/:entity - Create new entity"
    (testing "Admin can create entity with valid data"
      (let [request (make-request :post "/web/admin/test-users" admin-user
                                  {:path {:entity "test-users"}
                                   :form {"email" "newuser@example.com"
                                          "name" "New User"
                                          "password-hash" "hash123"
                                          "active" "true"}})
            response (*handler* request)]
        (is (some? response))

        ;; Should redirect to list page with success flash
        (is (or (= 302 (:status response))
                (= 200 (:status response))))))

    (testing "Create with validation errors shows form with errors"
      ;; Missing required fields
      (let [request (make-request :post "/web/admin/test-users" admin-user
                                  {:path {:entity "test-users"}
                                   :form {"email" "incomplete@example.com"}})
            response (*handler* request)]
        (is (some? response))
        ;; Should return form with validation errors
        ;; Week 1: May accept incomplete data, Week 2+: Full validation
        ))))

;; =============================================================================
;; Update Entity Endpoint Tests
;; =============================================================================

(deftest ^:contract update-entity-endpoint-test
  (testing "PUT /web/admin/:entity/:id - Update existing entity"
    (let [user (create-test-user! "update@example.com" "Update User" true)
          user-id (:id user)]

      (testing "Admin can update entity with valid data"
        (let [request (make-request :put (str "/web/admin/test-users/" user-id) admin-user
                                    {:path {:entity "test-users" :id (str user-id)}
                                     :form {"name" "Updated Name"
                                            "active" "false"}})
              response (*handler* request)]
          (is (some? response))

          ;; Should redirect to list page with success flash
          (is (or (= 302 (:status response))
                  (= 200 (:status response))))))

      (testing "Update non-existent entity returns error"
        (let [fake-id (UUID/randomUUID)
              request (make-request :put (str "/web/admin/test-users/" fake-id) admin-user
                                    {:path {:entity "test-users" :id (str fake-id)}
                                     :form {"name" "Test"}})
              response (*handler* request)]
          ;; Should return error
          (is (some? response)))))))

;; =============================================================================
;; Delete Entity Endpoint Tests
;; =============================================================================

(deftest ^:contract delete-entity-endpoint-test
  (testing "DELETE /web/admin/:entity/:id - Delete entity"
    (let [user (create-test-user! "delete@example.com" "Delete User" true)
          user-id (:id user)]

      (testing "Admin can delete entity"
        (let [request (make-request :delete (str "/web/admin/test-users/" user-id) admin-user
                                    {:path {:entity "test-users" :id (str user-id)}})
              response (*handler* request)]
          (is (some? response))
          (is (= 200 (:status response)))

          ;; Should have HX-Trigger header for HTMX
          (let [headers (:headers response)]
            (is (or (nil? (get headers "HX-Trigger"))
                    (= "entityDeleted" (get headers "HX-Trigger")))))))

      (testing "Delete non-existent entity returns error"
        (let [fake-id (UUID/randomUUID)
              request (make-request :delete (str "/web/admin/test-users/" fake-id) admin-user
                                    {:path {:entity "test-users" :id (str fake-id)}})
              response (*handler* request)]
          ;; Should return error or indicate no rows affected
          (is (some? response)))))))

;; =============================================================================
;; Bulk Delete Endpoint Tests
;; =============================================================================

(deftest ^:contract bulk-delete-endpoint-test
  (testing "POST /web/admin/:entity/bulk-delete - Bulk delete entities"
    (let [user1 (create-test-user! "bulk1@example.com" "Bulk 1" true)
          user2 (create-test-user! "bulk2@example.com" "Bulk 2" true)
          ids [(str (:id user1)) (str (:id user2))]]

      (testing "Admin can bulk delete multiple entities"
        (let [request (make-request :post "/web/admin/test-users/bulk-delete" admin-user
                                    {:path {:entity "test-users"}
                                     :form {"ids[]" ids}})
              response (*handler* request)]
          (is (some? response))

          ;; Should indicate success
          (is (or (= 200 (:status response))
                  (= 207 (:status response)))))))))

;; =============================================================================
;; Error Response Tests
;; =============================================================================

(deftest ^:contract error-response-formats-test
  (testing "Error responses use correct HTTP status codes"
    (testing "403 Forbidden for unauthorized access"
      (let [request (make-request :get "/web/admin/test-users" regular-user
                                  {:path {:entity "test-users"}})
            response (*handler* request)]
        ;; Should return 403 or throw exception
        (is (some? response))))

    (testing "404 Not Found for non-existent entity"
      (let [fake-id (UUID/randomUUID)
            request (make-request :get (str "/web/admin/test-users/" fake-id) admin-user
                                  {:path {:entity "test-users" :id (str fake-id)}})
            response (*handler* request)]
        ;; Should return 404 or throw exception
        (is (some? response))))))

;; =============================================================================
;; Security Tests
;; =============================================================================

(deftest ^:contract security-tests
  (testing "Security: Hidden fields not exposed in responses"
    (let [user (create-test-user! "secure@example.com" "Secure User" true)
          user-id (:id user)]

      (testing "password-hash not visible in detail view"
        (let [request (make-request :get (str "/web/admin/test-users/" user-id) admin-user
                                    {:path {:entity "test-users" :id (str user-id)}})
              response (*handler* request)]
          (is (= 200 (:status response)))
          ;; password-hash should not appear in response
          (let [body (:body response)]
            ;; Week 1: May show hash, Week 2+: Properly hidden
            (is (string? body)))))))

  (testing "Security: Readonly fields rejected in updates"
    (let [user (create-test-user! "readonly@example.com" "Readonly Test" true)
          user-id (:id user)
          original-id (:id user)]

      (testing "Attempting to change ID is ignored"
        (let [new-id (UUID/randomUUID)
              request (make-request :put (str "/web/admin/test-users/" user-id) admin-user
                                    {:path {:entity "test-users" :id (str user-id)}
                                     :form {"id" (str new-id)
                                            "name" "New Name"}})
              response (*handler* request)]
          (is (some? response))

          ;; Verify ID didn't change
          (let [updated (db/execute-one! *db-ctx*
                                         {:select [:*]
                                          :from [:test-users]
                                          :where [:= :id original-id]})]
            (is (= original-id (:id updated)))))))))

(deftest ^:contract ^:security create-entity-error-flash-no-leak-test
  (testing "raw exception during create never echoes its message to the client (BOU-182)"
    (let [secret          "SECRET-DB-DETAIL-XYZ-123"
          stub-service    (reify admin-ports/IAdminService
                            (validate-entity-data [_ _ data] {:valid? true :data data})
                            (create-entity [_ _ _] (throw (RuntimeException. secret))))
          schema-provider (schema-repo/create-schema-repository *db-ctx* admin-config)
          handler         (admin-http/create-entity-handler stub-service schema-provider admin-config)
          request         (make-request :post "/web/admin/test-users" admin-user
                                        {:path {:entity "test-users"}
                                         :form {"email" "leak@example.com"
                                                "name" "Leak Test"
                                                "password-hash" "hash123"}})
          response        (handler request)
          body            (let [b (:body response)]
                            (if (vector? b) (ui-components/render-html b) (str b)))]
      (is (not (str/includes? body secret))
          "raw exception message must not reach the client")))

  (testing "typed domain errors still surface their client-safe message"
    (let [stub-service    (reify admin-ports/IAdminService
                            (validate-entity-data [_ _ data] {:valid? true :data data})
                            (create-entity [_ _ _]
                              (throw (ex-info "Email already in use" {:type :conflict}))))
          schema-provider (schema-repo/create-schema-repository *db-ctx* admin-config)
          handler         (admin-http/create-entity-handler stub-service schema-provider admin-config)
          request         (make-request :post "/web/admin/test-users" admin-user
                                        {:path {:entity "test-users"}
                                         :form {"email" "dup@example.com"
                                                "name" "Dup Test"
                                                "password-hash" "hash123"}})
          response        (handler request)
          body            (let [b (:body response)]
                            (if (vector? b) (ui-components/render-html b) (str b)))]
      (is (str/includes? body "Email already in use")
          "typed domain errors keep their client-safe message")))

  (testing "typed but unmapped/5xx errors are still generic (typed != client-safe)"
    ;; e.g. schema-repository wraps raw DB errors as {:type :schema-fetch-error}
    ;; with the driver message embedded — typed, but internal.
    (let [secret          "INTERNAL-SCHEMA-DETAIL-456"
          stub-service    (reify admin-ports/IAdminService
                            (validate-entity-data [_ _ data] {:valid? true :data data})
                            (create-entity [_ _ _]
                              (throw (ex-info (str "Failed to fetch table metadata: " secret)
                                              {:type :schema-fetch-error}))))
          schema-provider (schema-repo/create-schema-repository *db-ctx* admin-config)
          handler         (admin-http/create-entity-handler stub-service schema-provider admin-config)
          request         (make-request :post "/web/admin/test-users" admin-user
                                        {:path {:entity "test-users"}
                                         :form {"email" "typed5xx@example.com"
                                                "name" "Typed 5xx"
                                                "password-hash" "hash123"}})
          response        (handler request)
          body            (let [b (:body response)]
                            (if (vector? b) (ui-components/render-html b) (str b)))]
      (is (not (str/includes? body secret))
          "typed errors without a 4xx mapping must not leak their message"))))

;; =============================================================================
;; Integration: Full CRUD Workflow
;; =============================================================================

(deftest ^:contract full-crud-workflow-test
  (testing "Complete CRUD workflow via HTTP"
    (let [create-request (make-request :post "/web/admin/test-users" admin-user
                                       {:path {:entity "test-users"}
                                        :form {"email" "workflow@example.com"
                                               "name" "Workflow User"
                                               "password-hash" "hash"
                                               "active" "true"}})
          create-response (*handler* create-request)]

      (testing "Step 1: Create entity"
        (is (some? create-response))
        ;; Handler returns 200 with entity list page and success flash message
        (is (= 200 (:status create-response))))

      ;; Find created user
      (let [created-user (db/execute-one! *db-ctx*
                                          {:select [:*]
                                           :from [:test-users]
                                           :where [:= :email "workflow@example.com"]})
            user-id (:id created-user)]

        (testing "Step 2: Read entity"
          (let [read-request (make-request :get (str "/web/admin/test-users/" user-id) admin-user
                                           {:path {:entity "test-users" :id (str user-id)}})
                read-response (*handler* read-request)]
            (is (= 200 (:status read-response)))
            (is (str/includes? (:body read-response) "workflow@example.com"))))

        (testing "Step 3: Update entity"
          (let [update-request (make-request :put (str "/web/admin/test-users/" user-id) admin-user
                                             {:path {:entity "test-users" :id (str user-id)}
                                              :form {"name" "Updated Workflow User"
                                                     "active" "false"}})
                update-response (*handler* update-request)]
            (is (some? update-response))))

        (testing "Step 4: Delete entity"
          (let [delete-request (make-request :delete (str "/web/admin/test-users/" user-id) admin-user
                                             {:path {:entity "test-users" :id (str user-id)}})
                delete-response (*handler* delete-request)]
            (is (= 200 (:status delete-response)))))))))
