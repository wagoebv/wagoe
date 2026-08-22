(ns wagoe.platform.shell.http.versioning-test
  "Integration tests for HTTP API versioning.
   
   These tests verify version prefix wrapping, version headers, redirect
   generation, and overall versioning middleware behavior."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [wagoe.platform.shell.http.versioning :as versioning]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def sample-routes
  "Sample unversioned routes for testing."
  [["/users"
    {:get  {:handler (fn [_] {:status 200 :body "list users"})}
     :post {:handler (fn [_] {:status 201 :body "create user"})}}]
   ["/users/:id"
    {:get    {:handler (fn [_] {:status 200 :body "get user"})}
     :put    {:handler (fn [_] {:status 200 :body "update user"})}
     :delete {:handler (fn [_] {:status 204 :body "delete user"})}}]
   ["/items"
    {:get {:handler (fn [_] {:status 200 :body "list items"})}}]])

(def sample-config
  "Sample versioning configuration."
  {:active {:wagoe/api-versioning
            {:default-version :v1
             :latest-stable :v1
             :deprecated-versions #{}
             :sunset-dates {}
             :supported-versions #{:v1}}}})

(def deprecated-config
  "Configuration with deprecated version."
  {:active {:wagoe/api-versioning
            {:default-version :v1
             :latest-stable :v2
             :deprecated-versions #{:v1}
             :sunset-dates {:v1 "2026-06-01"}
             :supported-versions #{:v1 :v2}}}})

;; =============================================================================
;; Version Configuration Tests
;; =============================================================================

(deftest ^:unit version-config-test
  (testing "Get version config with custom values"
    (let [config {:active {:wagoe/api-versioning
                           {:default-version :v2
                            :latest-stable :v2}}}
          result (versioning/version-config config)]
      (is (= :v2 (:default-version result)))
      (is (= :v2 (:latest-stable result)))))

  (testing "Get version config with defaults when not specified"
    (let [config {}
          result (versioning/version-config config)]
      (is (= :v1 (:default-version result)))
      (is (= :v1 (:latest-stable result)))
      (is (= #{} (:deprecated-versions result)))
      (is (= {} (:sunset-dates result)))
      (is (= #{:v1} (:supported-versions result)))))

  (testing "Partial config merges with defaults"
    (let [config {:active {:wagoe/api-versioning
                           {:default-version :v3}}}
          result (versioning/version-config config)]
      (is (= :v3 (:default-version result)))
      ;; Other fields use defaults
      (is (= :v1 (:latest-stable result)))
      (is (= #{} (:deprecated-versions result))))))

;; =============================================================================
;; Route Wrapping Tests
;; =============================================================================

(deftest ^:unit wrap-routes-with-version-test
  (testing "Wrap routes with v1 prefix"
    (let [routes [["/users" {}]
                  ["/items" {}]]
          result (versioning/wrap-routes-with-version routes :v1)]
      (is (= 2 (count result)))
      (is (= "/api/v1/users" (versioning/route-path (first result))))
      (is (= "/api/v1/items" (versioning/route-path (second result))))))

  (testing "Wrap routes with v2 prefix"
    (let [routes [["/users" {}]]
          result (versioning/wrap-routes-with-version routes :v2)]
      (is (= "/api/v2/users" (versioning/route-path (first result))))))

  (testing "Wrap routes preserves methods"
    (let [routes [["/users"
                   {:get  {:handler 'list-users}
                    :post {:handler 'create-user}}]]
          result (versioning/wrap-routes-with-version routes :v1)
          wrapped (first result)]
      (is (= "/api/v1/users" (versioning/route-path wrapped)))
      (is (= 'list-users (get-in (second wrapped) [:get :handler])))
      (is (= 'create-user (get-in (second wrapped) [:post :handler])))))

  (testing "Wrap empty routes returns empty vector"
    (let [result (versioning/wrap-routes-with-version [] :v1)]
      (is (vector? result))
      (is (empty? result))))

  (testing "Wrap routes with path parameters"
    (let [routes [["/users/:id" {}]]
          result (versioning/wrap-routes-with-version routes :v1)]
      (is (= "/api/v1/users/:id" (versioning/route-path (first result)))))))

;; =============================================================================
;; Version Headers Middleware Tests
;; =============================================================================

(deftest ^:unit version-headers-middleware-test
  (testing "Add version headers to response"
    (let [handler (fn [_] {:status 200 :headers {} :body "test"})
          config {:latest-stable :v1
                  :deprecated-versions #{}
                  :sunset-dates {}}
          wrapped (versioning/version-headers-middleware handler :v1 config)
          response (wrapped {:uri "/test"})]
      (is (= 200 (:status response)))
      (is (= "v1" (get-in response [:headers "X-API-Version"])))
      (is (= "v1" (get-in response [:headers "X-API-Version-Latest"])))))

  (testing "Add deprecated header when version is deprecated"
    (let [handler (fn [_] {:status 200 :headers {} :body "test"})
          config {:latest-stable :v2
                  :deprecated-versions #{:v1}
                  :sunset-dates {}}
          wrapped (versioning/version-headers-middleware handler :v1 config)
          response (wrapped {:uri "/test"})]
      (is (= "v1" (get-in response [:headers "X-API-Version"])))
      (is (= "v2" (get-in response [:headers "X-API-Version-Latest"])))
      (is (= "true" (get-in response [:headers "X-API-Deprecated"])))))

  (testing "Add sunset header when sunset date exists"
    (let [handler (fn [_] {:status 200 :headers {} :body "test"})
          config {:latest-stable :v2
                  :deprecated-versions #{:v1}
                  :sunset-dates {:v1 "2026-06-01"}}
          wrapped (versioning/version-headers-middleware handler :v1 config)
          response (wrapped {:uri "/test"})]
      (is (= "v1" (get-in response [:headers "X-API-Version"])))
      (is (= "true" (get-in response [:headers "X-API-Deprecated"])))
      (is (= "2026-06-01" (get-in response [:headers "X-API-Sunset"])))))

  (testing "No deprecated header for current version"
    (let [handler (fn [_] {:status 200 :headers {} :body "test"})
          config {:latest-stable :v2
                  :deprecated-versions #{:v1}
                  :sunset-dates {}}
          wrapped (versioning/version-headers-middleware handler :v2 config)
          response (wrapped {:uri "/test"})]
      (is (= "v2" (get-in response [:headers "X-API-Version"])))
      (is (= "v2" (get-in response [:headers "X-API-Version-Latest"])))
      (is (nil? (get-in response [:headers "X-API-Deprecated"])))))

  (testing "Merge with existing headers"
    (let [handler (fn [_] {:status 200
                           :headers {"Content-Type" "application/json"
                                     "X-Custom" "value"}
                           :body "test"})
          config {:latest-stable :v1
                  :deprecated-versions #{}
                  :sunset-dates {}}
          wrapped (versioning/version-headers-middleware handler :v1 config)
          response (wrapped {:uri "/test"})]
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (= "value" (get-in response [:headers "X-Custom"])))
      (is (= "v1" (get-in response [:headers "X-API-Version"])))))

  (testing "Preserve response body and status"
    (let [handler (fn [_] {:status 404
                           :headers {}
                           :body {:error "Not found"}})
          config {:latest-stable :v1
                  :deprecated-versions #{}
                  :sunset-dates {}}
          wrapped (versioning/version-headers-middleware handler :v1 config)
          response (wrapped {:uri "/test"})]
      (is (= 404 (:status response)))
      (is (= {:error "Not found"} (:body response)))
      (is (= "v1" (get-in response [:headers "X-API-Version"]))))))

;; =============================================================================
;; Backward Compatibility Redirect Tests
;; =============================================================================

(deftest ^:unit create-redirect-route-test
  (testing "Create redirect route for users endpoint"
    (let [route (versioning/create-redirect-route "/users" :v1)]
      ;; Reitit data — ["/api/users" {:get {..} :post {..} ..}] (ADR-037).
      (is (= "/api/users" (versioning/route-path route)))
      (is (every? (second route) [:get :post :put :delete :patch]))))

  (testing "Redirect handler returns 307 status"
    (let [route (versioning/create-redirect-route "/users" :v1)
          handler (get-in (second route) [:get :handler])
          response (handler {:uri "/api/users"})]
      (is (= 307 (:status response)))
      (is (= "/api/v1/users" (get-in response [:headers "Location"])))
      (is (= "true" (get-in response [:headers "X-API-Deprecated-Path"])))))

  (testing "Redirect handler includes informative body"
    (let [route (versioning/create-redirect-route "/users/:id" :v1)
          handler (get-in (second route) [:get :handler])
          response (handler {:uri "/api/users/123"})]
      (is (map? (:body response)))
      (is (= "Please use versioned API endpoint" (:message (:body response))))
      (is (= "/api/v1/users/:id" (:location (:body response))))
      (is (= "v1" (:version (:body response))))))

  (testing "All HTTP methods use same redirect handler"
    (let [route (versioning/create-redirect-route "/items" :v2)
          get-handler (get-in (second route) [:get :handler])
          post-handler (get-in (second route) [:post :handler])
          get-response (get-handler {:uri "/api/items"})
          post-response (post-handler {:uri "/api/items"})]
      (is (= 307 (:status get-response)))
      (is (= 307 (:status post-response)))
      (is (= "/api/v2/items" (get-in get-response [:headers "Location"])))
      (is (= "/api/v2/items" (get-in post-response [:headers "Location"]))))))

(deftest ^:unit create-backward-compatibility-routes-test
  (testing "Create redirects for versioned routes"
    (let [versioned-routes [["/api/v1/users" {}]
                            ["/api/v1/items" {}]]
          redirects (versioning/create-backward-compatibility-routes
                     versioned-routes :v1)]
      (is (= 2 (count redirects)))
      ;; Should create /api/users and /api/items redirects
      (is (some #(= "/api/users" (versioning/route-path %)) redirects))
      (is (some #(= "/api/items" (versioning/route-path %)) redirects))))

  (testing "Only create redirects for matching version"
    (let [versioned-routes [["/api/v1/users" {}]
                            ["/api/v2/users" {}]]
          redirects (versioning/create-backward-compatibility-routes
                     versioned-routes :v1)]
      ;; Should only create redirect for v1 routes
      (is (= 1 (count redirects)))
      (is (= "/api/users" (versioning/route-path (first redirects))))))

  (testing "Handle routes with path parameters"
    (let [versioned-routes [["/api/v1/users/:id" {}]]
          redirects (versioning/create-backward-compatibility-routes
                     versioned-routes :v1)]
      (is (= 1 (count redirects)))
      (is (= "/api/users/:id" (versioning/route-path (first redirects))))))

  (testing "Empty routes returns empty redirects"
    (let [redirects (versioning/create-backward-compatibility-routes [] :v1)]
      (is (empty? redirects))))

  (testing "Default version is v1"
    (let [versioned-routes [["/api/v1/users" {}]]
          redirects (versioning/create-backward-compatibility-routes versioned-routes)]
      (is (= 1 (count redirects)))
      (let [route (first redirects)
            handler (get-in (second route) [:get :handler])
            response (handler {:uri "/api/users"})]
        (is (= "/api/v1/users" (get-in response [:headers "Location"]))))))

  (testing "Deduplicate paths"
    (let [versioned-routes [["/api/v1/users" {}]
                            ["/api/v1/users" {}]]  ; Duplicate
          redirects (versioning/create-backward-compatibility-routes
                     versioned-routes :v1)]
      ;; Should only create one redirect even with duplicate input
      (is (= 1 (count redirects))))))

;; =============================================================================
;; High-Level API Tests
;; =============================================================================

(deftest ^:unit apply-versioning-test
  (testing "Apply versioning to routes"
    (let [routes [["/users" {}]
                  ["/items" {}]]
          result (versioning/apply-versioning routes sample-config)]
      ;; Should have 2 versioned routes + 2 redirect routes
      (is (= 4 (count result)))
      ;; Check versioned routes
      (is (some #(= "/api/v1/users" (versioning/route-path %)) result))
      (is (some #(= "/api/v1/items" (versioning/route-path %)) result))
      ;; Check redirect routes
      (is (some #(= "/api/users" (versioning/route-path %)) result))
      (is (some #(= "/api/items" (versioning/route-path %)) result))))

  (testing "Apply versioning preserves route methods"
    (let [routes [["/users"
                   {:get  {:handler 'list-users}
                    :post {:handler 'create-user}}]]
          result (versioning/apply-versioning routes sample-config)
          versioned (first (filter #(str/includes? (versioning/route-path %) "v1") result))]
      (is (= "/api/v1/users" (versioning/route-path versioned)))
      (is (= 'list-users (get-in (second versioned) [:get :handler])))
      (is (= 'create-user (get-in (second versioned) [:post :handler])))))

  (testing "Apply versioning with different default version"
    (let [routes [["/users" {}]]
          config {:active {:wagoe/api-versioning
                           {:default-version :v2
                            :latest-stable :v2
                            :supported-versions #{:v2}}}}
          result (versioning/apply-versioning routes config)]
      ;; Should have v2 versioned route
      (is (some #(= "/api/v2/users" (versioning/route-path %)) result))
      ;; Redirect should point to v2
      (let [redirect (first (filter #(= "/api/users" (versioning/route-path %)) result))
            handler (get-in (second redirect) [:get :handler])
            response (handler {:uri "/api/users"})]
        (is (= "/api/v2/users" (get-in response [:headers "Location"]))))))

  (testing "Apply versioning to empty routes"
    (let [result (versioning/apply-versioning [] sample-config)]
      (is (empty? result))))

  (testing "Apply versioning returns vector"
    (let [routes [["/users" {}]]
          result (versioning/apply-versioning routes sample-config)]
      (is (vector? result)))))

(deftest ^:unit wrap-handler-with-version-headers-test
  (testing "Wrap handler adds version headers"
    (let [handler (fn [_] {:status 200 :headers {} :body "test"})
          wrapped (versioning/wrap-handler-with-version-headers
                   handler sample-config)
          response (wrapped {:uri "/test"})]
      (is (= 200 (:status response)))
      (is (= "v1" (get-in response [:headers "X-API-Version"])))
      (is (= "v1" (get-in response [:headers "X-API-Version-Latest"])))))

  (testing "Wrap handler uses config default version"
    (let [handler (fn [_] {:status 200 :headers {} :body "test"})
          config {:active {:wagoe/api-versioning
                           {:default-version :v3
                            :latest-stable :v3}}}
          wrapped (versioning/wrap-handler-with-version-headers handler config)
          response (wrapped {:uri "/test"})]
      (is (= "v3" (get-in response [:headers "X-API-Version"])))
      (is (= "v3" (get-in response [:headers "X-API-Version-Latest"])))))

  (testing "Wrap handler with deprecated version"
    (let [handler (fn [_] {:status 200 :headers {} :body "test"})
          wrapped (versioning/wrap-handler-with-version-headers
                   handler deprecated-config)
          response (wrapped {:uri "/test"})]
      (is (= "v1" (get-in response [:headers "X-API-Version"])))
      (is (= "v2" (get-in response [:headers "X-API-Version-Latest"])))
      (is (= "true" (get-in response [:headers "X-API-Deprecated"])))
      (is (= "2026-06-01" (get-in response [:headers "X-API-Sunset"]))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest ^:unit full-versioning-flow-test
  (testing "Complete versioning flow with sample routes"
    (let [;; Step 1: Apply versioning to routes
          versioned-routes (versioning/apply-versioning sample-routes sample-config)

          ;; Verify route structure
          versioned (filter #(str/includes? (versioning/route-path %) "v1") versioned-routes)
          redirects (filter #(and (not (str/includes? (versioning/route-path %) "v1"))
                                  (str/starts-with? (versioning/route-path %) "/api/"))
                            versioned-routes)]

      ;; Should have 3 versioned routes (users, users/:id, items)
      (is (= 3 (count versioned)))

      ;; Should have 3 redirect routes
      (is (= 3 (count redirects)))

      ;; Test a redirect handler
      (let [users-redirect (first (filter #(= "/api/users" (versioning/route-path %)) redirects))
            get-handler (get-in (second users-redirect) [:get :handler])
            response (get-handler {:uri "/api/users"})]
        (is (= 307 (:status response)))
        (is (= "/api/v1/users" (get-in response [:headers "Location"]))))

      ;; Test a versioned route handler
      (let [users-versioned (first (filter #(= "/api/v1/users" (versioning/route-path %)) versioned))
            get-handler (get-in (second users-versioned) [:get :handler])
            response (get-handler {})]
        (is (= 200 (:status response)))
        (is (= "list users" (:body response))))))

  (testing "Versioning with deprecated version configuration"
    (let [versioned-routes (versioning/apply-versioning sample-routes deprecated-config)

          ;; Create a mock handler from versioned routes
          handler (fn [request]
                    (let [route (first (filter #(= (:uri request) (:path %))
                                               versioned-routes))]
                      (if route
                        (let [method-handler (get-in route [:methods (:request-method request) :handler])]
                          (if method-handler
                            (method-handler request)
                            {:status 405 :body "Method not allowed"}))
                        {:status 404 :body "Not found"})))

          ;; Wrap with version headers
          wrapped-handler (versioning/wrap-handler-with-version-headers
                           handler deprecated-config)

          ;; Test request to versioned endpoint
          response (wrapped-handler {:uri "/api/v1/users" :request-method :get})]

      ;; Should have version headers indicating deprecation
      (is (= "v1" (get-in response [:headers "X-API-Version"])))
      (is (= "v2" (get-in response [:headers "X-API-Version-Latest"])))
      (is (= "true" (get-in response [:headers "X-API-Deprecated"])))
      (is (= "2026-06-01" (get-in response [:headers "X-API-Sunset"]))))))

;; =============================================================================
;; Edge Cases and Error Handling
;; =============================================================================

(deftest ^:unit edge-cases-test
  (testing "Version config with nil values"
    (let [config {:active {:wagoe/api-versioning nil}}
          result (versioning/version-config config)]
      ;; Should use defaults
      (is (= :v1 (:default-version result)))
      (is (= :v1 (:latest-stable result)))))

  (testing "Wrap routes with keyword version"
    (let [routes [["/users" {}]]
          result (versioning/wrap-routes-with-version routes :v123)]
      (is (= "/api/v123/users" (versioning/route-path (first result))))))

  (testing "Redirect route with complex path"
    (let [route (versioning/create-redirect-route "/users/:id/orders/:order_id" :v1)]
      (is (= "/api/users/:id/orders/:order_id" (versioning/route-path route)))
      (let [handler (get-in (second route) [:get :handler])
            response (handler {:uri "/api/users/123/orders/456"})]
        (is (= "/api/v1/users/:id/orders/:order_id"
               (get-in response [:headers "Location"]))))))

  (testing "Multiple deprecated versions"
    (let [handler (fn [_] {:status 200 :headers {} :body "test"})
          config {:latest-stable :v3
                  :deprecated-versions #{:v1 :v2}
                  :sunset-dates {:v1 "2026-01-01" :v2 "2026-06-01"}}
          wrapped-v1 (versioning/version-headers-middleware handler :v1 config)
          wrapped-v2 (versioning/version-headers-middleware handler :v2 config)
          response-v1 (wrapped-v1 {:uri "/test"})
          response-v2 (wrapped-v2 {:uri "/test"})]
      ;; Both should be marked as deprecated
      (is (= "true" (get-in response-v1 [:headers "X-API-Deprecated"])))
      (is (= "true" (get-in response-v2 [:headers "X-API-Deprecated"])))
      ;; Different sunset dates
      (is (= "2026-01-01" (get-in response-v1 [:headers "X-API-Sunset"])))
      (is (= "2026-06-01" (get-in response-v2 [:headers "X-API-Sunset"])))))

  (testing "Routes with leading slashes are handled correctly"
    (let [routes [["/users" {}]]
          result (versioning/wrap-routes-with-version routes :v1)]
      (is (= "/api/v1/users" (versioning/route-path (first result))))
      ;; Should not have double slashes
      (is (not (str/includes? (versioning/route-path (first result)) "//")))))

  (testing "Empty path edge case"
    (let [routes [{:path "" :methods {}}]
          result (versioning/wrap-routes-with-version routes :v1)]
      (is (= "/api/v1" (versioning/route-path (first result)))))))
