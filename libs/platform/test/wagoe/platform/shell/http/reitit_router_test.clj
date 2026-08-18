(ns wagoe.platform.shell.http.reitit-router-test
  "Tests for Reitit router adapter."
  (:require [wagoe.platform.ports.http :as ports]
            [wagoe.platform.shell.http.reitit-router :as reitit]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn response-body-as-map
  "Return response body as a Clojure map.

   Error responses no longer set their own Content-Type, so muuntaja encodes
   them like any other body and they arrive as a stream — which is what makes
   an EDN or transit client able to read them, and what stopped Ring being
   handed a raw map on an HTML request (BOU-321)."
  [response]
  (let [body (:body response)]
    (cond
      (string? body)                       (json/parse-string body true)
      (instance? java.io.InputStream body) (json/parse-string (slurp body) true)
      :else                                body)))

;; =============================================================================
;; Test Handlers
;; =============================================================================

(defn test-list-handler
  "Simple handler for listing items."
  [_request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body {:items ["item1" "item2"]}})

(defn test-create-handler
  "Simple handler for creating items."
  [_request]
  {:status 201
   :headers {"Content-Type" "application/json"}
   :body {:id "123" :message "Created"}})

(defn test-get-handler
  "Simple handler for getting an item by ID."
  [request]
  (let [id (get-in request [:path-params :id])]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body {:id id :name "Test Item"}}))

(defn test-delete-handler
  "Simple handler for deleting an item."
  [_request]
  {:status 204})

(defn test-throwing-handler
  "Handler that throws to test default HTTP error handling."
  [_request]
  (throw (ex-info "boom"
                  {:type :validation-error
                   :message "Invalid input"
                   :foo "bar"})))

;; =============================================================================
;; Test Route Specs
;; =============================================================================

(def simple-routes
  "Simple normalized route specifications for testing."
  [{:path "/api/items"
    :methods {:get {:handler `test-list-handler
                    :summary "List items"
                    :tags ["items"]}
              :post {:handler `test-create-handler
                     :summary "Create item"
                     :tags ["items"]}}}])

(def nested-routes
  "Nested route specifications for testing."
  [{:path "/api/users"
    :methods {:get {:handler `test-list-handler
                    :summary "List users"
                    :tags ["users"]}
              :post {:handler `test-create-handler
                     :summary "Create user"
                     :tags ["users"]}}
    :children [{:path "/:id"
                :methods {:get {:handler `test-get-handler
                                :summary "Get user by ID"
                                :tags ["users"]
                                :coercion {:path [:map [:id :string]]}}
                          :delete {:handler `test-delete-handler
                                   :summary "Delete user"
                                   :tags ["users"]
                                   :coercion {:path [:map [:id :string]]}}}}]}])

(defn test-list-products-handler
  "Handler that returns data matching the coercion schema."
  [_request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   ;; Return vector of maps matching schema
   :body [{:id "1" :name "Product 1"}
          {:id "2" :name "Product 2"}]})

(def routes-with-coercion
  "Routes with Malli coercion for testing."
  [{:path "/api/products"
    :methods {:get {:handler `test-list-products-handler
                    :summary "List products"
                    :tags ["products"]
                    :coercion {:query [:map
                                       [:limit {:optional true} :int]
                                       [:offset {:optional true} :int]]}}
              ;; POST without body coercion for simpler testing
              :post {:handler `test-create-handler
                     :summary "Create product"
                     :tags ["products"]}}}])

;; =============================================================================
;; Router Tests
;; =============================================================================

(deftest ^:unit create-router-test
  (testing "Can create Reitit router instance"
    (let [router (reitit/create-reitit-router)]
      (is (some? router))
      (is (satisfies? ports/IRouter router)))))

(deftest ^:contract compile-simple-routes-test
  (testing "Can compile simple routes to Ring handler"
    (let [router (reitit/create-reitit-router)
          handler (ports/compile-routes router simple-routes {})]
      (is (fn? handler))

      (testing "GET request works"
        (let [response (handler {:request-method :get
                                 :uri "/api/items"})]
          (is (= 200 (:status response)))
          (is (= {:items ["item1" "item2"]} (:body response)))))

      (testing "POST request works"
        (let [response (handler {:request-method :post
                                 :uri "/api/items"})]
          (is (= 201 (:status response)))
          (is (= {:id "123" :message "Created"} (:body response)))))

      (testing "Unknown route returns 404"
        (let [response (handler {:request-method :get
                                 :uri "/api/unknown"})]
          (is (= 404 (:status response))))))))

(deftest ^:contract compile-nested-routes-test
  (testing "Can compile nested routes with path parameters"
    (let [router (reitit/create-reitit-router)
          handler (ports/compile-routes router nested-routes {})]

      (testing "Parent route works"
        (let [response (handler {:request-method :get
                                 :uri "/api/users"})]
          (is (= 200 (:status response)))))

      (testing "Child route with path param works"
        (let [response (handler {:request-method :get
                                 :uri "/api/users/123"})]
          (is (= 200 (:status response)))
          (is (= "123" (get-in response [:body :id])))))

      (testing "DELETE on child route works"
        (let [response (handler {:request-method :delete
                                 :uri "/api/users/123"})]
          (is (= 204 (:status response))))))))

(deftest ^:contract compile-routes-with-coercion-test
  (testing "Can compile routes with Malli coercion"
    (let [router (reitit/create-reitit-router)
          handler (ports/compile-routes router routes-with-coercion {})]

      (testing "Route with query coercion compiles"
        (let [response (handler {:request-method :get
                                 :uri "/api/products"})]
          (is (= 200 (:status response)))))

      (testing "Route with body coercion compiles"
        (let [response (handler {:request-method :post
                                 :uri "/api/products"})]
          (is (= 201 (:status response))))))))

(deftest ^:contract router-with-middleware-test
  (testing "Can compile routes with custom middleware"
    (let [router (reitit/create-reitit-router)
          ;; Simple middleware that adds header
          add-header-mw (fn [handler]
                          (fn [request]
                            (let [response (handler request)]
                              (assoc-in response [:headers "X-Custom"] "test"))))
          config {:middleware [add-header-mw]}
          handler (ports/compile-routes router simple-routes config)]

      (testing "Middleware is applied"
        (let [response (handler {:request-method :get
                                 :uri "/api/items"})]
          (is (= 200 (:status response)))
          (is (= "test" (get-in response [:headers "X-Custom"]))))))))

;; =============================================================================
;; Symbol Resolution Tests
;; =============================================================================

(deftest ^:contract symbol-resolution-test
  (testing "Handler symbols are resolved to functions"
    (let [router (reitit/create-reitit-router)
          ;; Use quoted symbols (will be resolved by adapter)
          routes [{:path "/test"
                   :methods {:get {:handler `test-list-handler}}}]
          handler (ports/compile-routes router routes {})]

      (testing "Resolved handler works"
        (let [response (handler {:request-method :get
                                 :uri "/test"})]
          (is (= 200 (:status response)))
          (is (= {:items ["item1" "item2"]} (:body response))))))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest ^:contract method-not-allowed-test
  (testing "Returns 405 for unsupported methods"
    (let [router (reitit/create-reitit-router)
          ;; Only GET is supported
          routes [{:path "/api/items"
                   :methods {:get {:handler `test-list-handler}}}]
          handler (ports/compile-routes router routes {})]
      (is (= 405 (:status (handler {:request-method :post
                                    :uri "/api/items"})))))))

(deftest ^:contract not-found-test
  (testing "Returns 404 for unknown routes"
    (let [router (reitit/create-reitit-router)
          handler (ports/compile-routes router simple-routes {})
          response (handler {:request-method :get
                             :uri "/api/nonexistent"})]
      (is (= 404 (:status response)))
      ;; Body may be string or map depending on router implementation
      (is (or (string? (:body response))
              (and (map? (:body response))
                   (contains? (:body response) :error)))))))

;; =============================================================================
;; HTTP Interceptor Tests
;; =============================================================================

(defn add-request-header-middleware
  "Middleware that adds a marker header to the request."
  [handler]
  (fn [request]
    (handler (update request :headers (fnil assoc {}) "x-from-mw" "yes"))))

(def test-interceptor-sees-middleware
  "Test interceptor that checks if it can see request modifications from middleware."
  {:name :test-sees-middleware
   :enter (fn [ctx]
            (assoc-in ctx [:attrs :saw-mw-header?]
                      (= "yes" (get-in ctx [:request :headers "x-from-mw"]))))
   :leave (fn [ctx]
            (update-in ctx [:response :headers]
                       assoc
                       "x-saw-mw"
                       (if (get-in ctx [:attrs :saw-mw-header?]) "true" "false")))})

(def test-interceptor-enter
  "Test interceptor that modifies request in enter phase."
  {:name :test-enter
   :enter (fn [ctx]
            (update-in ctx [:request :headers] assoc "x-test-enter" "yes"))})

(def test-interceptor-leave
  "Test interceptor that modifies response in leave phase."
  {:name :test-leave
   :leave (fn [ctx]
            (update-in ctx [:response :headers] assoc "x-test-leave" "yes"))})

(def test-interceptor-error
  "Test interceptor that captures errors."
  {:name :test-error
   :error (fn [ctx]
            (assoc ctx :response
                   {:status 500
                    :headers {"Content-Type" "application/json"}
                    :body {:error "Interceptor caught error"
                           :message (ex-message (:exception ctx))}}))})

(defn routes-with-interceptors
  "Route specs with interceptor usage."
  []
  [{:path "/api/intercepted"
    :methods {:get {:handler `test-list-handler
                    :interceptors [test-interceptor-enter test-interceptor-leave]
                    :summary "Route with interceptors"}}}])

(deftest ^:contract compile-routes-with-interceptors-test
  (testing "Can compile routes with interceptors"
    (let [router (reitit/create-reitit-router)
          handler (ports/compile-routes router (routes-with-interceptors) {})]

      (is (fn? handler))

      (testing "Interceptors run in correct order"
        (let [response (handler {:request-method :get
                                 :uri "/api/intercepted"})]
          (is (= 200 (:status response)))
          ;; Verify leave interceptor ran
          (is (= "yes" (get-in response [:headers "x-test-leave"]))))))))

(deftest ^:contract mixed-middleware-and-interceptors-test
  (testing "Can use both middleware and interceptors together"
    (let [router (reitit/create-reitit-router)
          ;; Middleware adds header
          test-middleware (fn [handler]
                            (fn [request]
                              (let [response (handler request)]
                                (assoc-in response [:headers "x-middleware"] "yes"))))
          routes [{:path "/api/mixed"
                   :methods {:get {:handler `test-list-handler
                                   :middleware [test-middleware]
                                   :interceptors [test-interceptor-leave]
                                   :summary "Route with both"}}}]
          handler (ports/compile-routes router routes {})]

      (testing "Both middleware and interceptors execute"
        (let [response (handler {:request-method :get
                                 :uri "/api/mixed"})]
          (is (= 200 (:status response)))
          (is (= "yes" (get-in response [:headers "x-middleware"])))
          (is (= "yes" (get-in response [:headers "x-test-leave"]))))))))

;; =============================================================================
;; Default HTTP Interceptor Behavior Tests
;; =============================================================================

(deftest ^:contract default-interceptors-add-correlation-id-test
  (testing "Default interceptors add/propagate X-Correlation-ID header for matched routes"
    (let [router (reitit/create-reitit-router)
          handler (ports/compile-routes router simple-routes {})
          correlation-id "test-correlation-id"
          response (handler {:request-method :get
                             :uri "/api/items"
                             :headers {"x-correlation-id" correlation-id}})
          response-correlation-id (or (get-in response [:headers "X-Correlation-ID"])
                                      (get-in response [:headers "x-correlation-id"]))]
      (is (= 200 (:status response)))
      (is (= correlation-id response-correlation-id)))))

(deftest ^:contract default-error-handler-converts-exceptions-test
  (testing "Default interceptors convert exceptions into safe error responses"
    (let [router (reitit/create-reitit-router)
          routes [{:path "/api/boom"
                   :methods {:get {:handler `test-throwing-handler}}}]
          handler (ports/compile-routes router routes {})
          correlation-id "test-correlation-id"
          response (handler {:request-method :get
                             :uri "/api/boom"
                             :headers {"x-correlation-id" correlation-id}})
          response-correlation-id (or (get-in response [:headers "X-Correlation-ID"])
                                      (get-in response [:headers "x-correlation-id"]))
          body (response-body-as-map response)]
      (is (= 400 (:status response)))
      (is (= correlation-id response-correlation-id))
      (is (= "validation-error" (:error body)))
      (is (= "Invalid input" (:message body)))
      (is (= correlation-id (:correlation-id body)))
      (is (= {:foo "bar"} (:details body))))))

(deftest ^:contract route-middleware-runs-before-interceptors-test
  (testing "Route middleware runs before interceptors (interceptors see modified request)"
    (let [router (reitit/create-reitit-router)
          routes-with-middleware
          [{:path "/api/order"
            :meta {:middleware [add-request-header-middleware]}
            :methods {:get {:handler `test-list-handler
                            :interceptors [test-interceptor-sees-middleware]}}}]

          routes-without-middleware
          [{:path "/api/order"
            :methods {:get {:handler `test-list-handler
                            :interceptors [test-interceptor-sees-middleware]}}}]

          handler-with-middleware (ports/compile-routes router routes-with-middleware {})
          handler-without-middleware (ports/compile-routes router routes-without-middleware {})

          resp-with-mw (handler-with-middleware {:request-method :get :uri "/api/order"})
          resp-without-mw (handler-without-middleware {:request-method :get :uri "/api/order"})]
      (is (= "true" (get-in resp-with-mw [:headers "x-saw-mw"])))
      (is (= "false" (get-in resp-without-mw [:headers "x-saw-mw"]))))))


;; =============================================================================
;; Coercion failures (BOU-321)
;; =============================================================================

(defn- error-body
  "The response body as data.

   Read through `slurp`: the exception middleware sits inside
   `format-response`, so its bodies are negotiated and encoded like any other
   response — which is the point, and which means the body arrives as a
   stream rather than the map the handler returned."
  [resp]
  (json/parse-string (slurp (:body resp)) true))

(defn- login-like-handler
  "A route whose body schema requires two fields, wired the way the user module
   wires login."
  [system]
  (ports/compile-routes
   (reitit/create-reitit-router)
   [{:path    "/auth/login"
     :methods {:post {:handler    (fn [_] {:status 200 :body {:ok true}})
                      :parameters {:body [:map {:closed true}
                                          [:email :string]
                                          [:password :string]]}}}}]
   {:system system}))

(deftest ^:unit a-request-that-fails-coercion-is-a-400-not-a-500
  ;; Reitit applies a :middleware vector first-to-outermost, and the exception
  ;; middleware sat last — inside coerce-request. A request missing a required
  ;; field threw past every handler, so the app answered 500 to a malformed
  ;; request and the client learned nothing.
  (let [resp ((login-like-handler {}) {:request-method :post
                                       :uri            "/auth/login"
                                       :headers        {}
                                       :body-params    {}})
        body (error-body resp)]
    (is (= 400 (:status resp)))
    (is (= "validation-error" (:error body)))

    (testing "and it names the fields the caller got wrong"
      (is (= {:email ["invalid"] :password ["invalid"]} (:details body))))

    (testing "but not the schema it checked them against"
      (is (nil? (:schema body))))))

(deftest ^:unit ^:security production-does-not-explain-the-schema-it-rejected-you-with
  ;; me/humanize renders the schema's constraints into its messages, so the
  ;; full text hands an unauthenticated caller every enum member and every
  ;; bound in exchange for one malformed POST. The field names are the
  ;; caller's own input and stay; the why is dev-only.
  (let [handler (ports/compile-routes
                 (reitit/create-reitit-router)
                 [{:path    "/users"
                   :methods {:post {:handler    (fn [_] {:status 201 :body {}})
                                    :parameters {:body [:map {:closed true}
                                                        [:role [:enum "admin" "superuser" "internal-auditor"]]
                                                        [:age [:int {:min 18 :max 120}]]]}}}}]
                 {})
        resp    (handler {:request-method :post :uri "/users" :headers {}
                          :body-params {:role "peasant" :age 4}})
        raw     (slurp (:body resp))]
    (is (= 400 (:status resp)))
    (doseq [secret ["superuser" "internal-auditor" "should be at least 18" "120"]]
      (is (not (str/includes? raw secret))
          (str "leaked schema detail: " secret)))
    (is (= {:role ["invalid"] :age ["invalid"]} (:details (json/parse-string raw true))))))

(deftest ^:unit dev-gets-the-explanation-production-does-not
  (let [handler (fn [system]
                  (ports/compile-routes
                   (reitit/create-reitit-router)
                   [{:path    "/users"
                     :methods {:post {:handler    (fn [_] {:status 201 :body {}})
                                      :parameters {:body [:map {:closed true} [:role [:enum "admin"]]]}}}}]
                   {:system system}))
        details (fn [system]
                  (:details (json/parse-string
                             (slurp (:body ((handler system) {:request-method :post :uri "/users"
                                                              :headers {} :body-params {}})))
                             true)))]
    (is (= {:role ["missing required key"]}
           (details {:error-enricher (constantly {:code "BND-201"}) :environment "dev"})))
    (is (= {:role ["invalid"]}
           (details {:error-enricher (constantly {:code "BND-201"}) :environment "prod"})))))

(deftest ^:unit coercion-failures-carry-the-bnd-code-in-dev-only
  (let [enricher (fn [_] {:code "BND-201" :category :validation})
        body-of  (fn [system]
                   (error-body ((login-like-handler system) {:request-method :post
                                                             :uri            "/auth/login"
                                                             :headers        {}
                                                             :body-params    {}})))]
    (testing "dev"
      (is (= "BND-201" (get-in (body-of {:error-enricher enricher :environment "development"})
                               [:dev :code]))))

    (testing "production, with the same enricher wired"
      (is (nil? (:dev (body-of {:error-enricher enricher :environment "production"})))))

    (testing "no enricher"
      (is (nil? (:dev (body-of {:environment "development"})))))))
