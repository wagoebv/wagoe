(ns wagoe.platform.shell.http.reitit-router-test
  "Tests for Reitit router adapter."
  (:require [wagoe.platform.shell.http.reitit-router :as reitit]
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
  "Simple Reitit route data for testing."
  [["/api/items"
    {:get {:handler test-list-handler
           :summary "List items"
           :tags ["items"]}
     :post {:handler test-create-handler
            :summary "Create item"
            :tags ["items"]}}]])

(def nested-routes
  "Nested route specifications for testing."
  ;; Nesting is Reitit's own: [path data & children] (ADR-037). `:coercion`
  ;; becomes `:parameters`, which is what reitit reads.
  [["/api/users"
    [""
     {:get  {:handler test-list-handler
             :summary "List users"
             :tags ["users"]}
      :post {:handler test-create-handler
             :summary "Create user"
             :tags ["users"]}}]
    ["/:id"
     {:get    {:handler test-get-handler
               :summary "Get user by ID"
               :tags ["users"]
               :parameters {:path [:map [:id :string]]}}
      :delete {:handler test-delete-handler
               :summary "Delete user"
               :tags ["users"]
               :parameters {:path [:map [:id :string]]}}}]]])

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
  [["/api/products"
    {:get {:handler test-list-products-handler
           :summary "List products"
           :tags ["products"]
           :parameters {:query [:map
                                [:limit {:optional true} :int]
                                [:offset {:optional true} :int]]}}
     ;; POST without body coercion for simpler testing
     :post {:handler test-create-handler
            :summary "Create product"
            :tags ["products"]}}]])

;; =============================================================================
;; Router Tests
;; =============================================================================

(deftest ^:unit compile-routes-is-a-function-not-a-protocol
  ;; IRouter had one implementation and existed to make the normalized format
  ;; swappable. Both are gone, so this is a function call (ADR-037).
  (is (fn? reitit/compile-routes))
  (is (fn? (reitit/compile-routes simple-routes {}))))

(deftest ^:contract compile-simple-routes-test
  (testing "Can compile simple routes to Ring handler"
    (let [handler (reitit/compile-routes simple-routes {})]
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
    (let [handler (reitit/compile-routes nested-routes {})]

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
    (let [handler (reitit/compile-routes routes-with-coercion {})]

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
    (let [;; Simple middleware that adds header
          add-header-mw (fn [handler]
                          (fn [request]
                            (let [response (handler request)]
                              (assoc-in response [:headers "X-Custom"] "test"))))
          config {:middleware [add-header-mw]}
          handler (reitit/compile-routes simple-routes config)]

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
    (let [;; Use quoted symbols (will be resolved by adapter)
          routes [["/test"
                   {:get {:handler test-list-handler}}]]
          handler (reitit/compile-routes routes {})]

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
    (let [;; Only GET is supported
          routes [["/api/items"
                   {:get {:handler test-list-handler}}]]
          handler (reitit/compile-routes routes {})]
      (is (= 405 (:status (handler {:request-method :post
                                    :uri "/api/items"})))))))

(deftest ^:contract not-found-test
  (testing "Returns 404 for unknown routes"
    (let [handler (reitit/compile-routes simple-routes {})
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
  [["/api/intercepted"
    {:get {:handler test-list-handler
           :interceptors [test-interceptor-enter test-interceptor-leave]
           :summary "Route with interceptors"}}]])

(deftest ^:contract compile-routes-with-interceptors-test
  (testing "Can compile routes with interceptors"
    (let [handler (reitit/compile-routes (routes-with-interceptors) {})]

      (is (fn? handler))

      (testing "Interceptors run in correct order"
        (let [response (handler {:request-method :get
                                 :uri "/api/intercepted"})]
          (is (= 200 (:status response)))
          ;; Verify leave interceptor ran
          (is (= "yes" (get-in response [:headers "x-test-leave"]))))))))

(deftest ^:contract mixed-middleware-and-interceptors-test
  (testing "Can use both middleware and interceptors together"
    (let [;; Middleware adds header
          test-middleware (fn [handler]
                            (fn [request]
                              (let [response (handler request)]
                                (assoc-in response [:headers "x-middleware"] "yes"))))
          routes [["/api/mixed"
                   {:get {:handler test-list-handler
                          :middleware [test-middleware]
                          :interceptors [test-interceptor-leave]
                          :summary "Route with both"}}]]
          handler (reitit/compile-routes routes {})]

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
    (let [handler (reitit/compile-routes simple-routes {})
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
    (let [routes [["/api/boom"
                   {:get {:handler test-throwing-handler}}]]
          handler (reitit/compile-routes routes {})
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
    (let [routes-with-middleware
          [["/api/order"
            {:middleware [add-request-header-middleware]
             :get {:handler test-list-handler
                   :interceptors [test-interceptor-sees-middleware]}}]]

          routes-without-middleware
          [["/api/order"
            {:get {:handler test-list-handler
                   :interceptors [test-interceptor-sees-middleware]}}]]

          handler-with-middleware (reitit/compile-routes routes-with-middleware {})
          handler-without-middleware (reitit/compile-routes routes-without-middleware {})

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
  (reitit/compile-routes [["/auth/login"
                           {:post {:handler    (fn [_] {:status 200 :body {:ok true}})
                                   :parameters {:body [:map {:closed true}
                                                       [:email :string]
                                                       [:password :string]]}}}]]
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
  (let [handler (reitit/compile-routes [["/users"
                                         {:post {:handler    (fn [_] {:status 201 :body {}})
                                                 :parameters {:body [:map {:closed true}
                                                                     [:role [:enum "admin" "superuser" "internal-auditor"]]
                                                                     [:age [:int {:min 18 :max 120}]]]}}}]]
                                       {})
        resp    (handler {:request-method :post :uri "/users" :headers {}
                          :body-params {:role "peasant" :age 4}})
        raw     (slurp (:body resp))]
    (is (= 400 (:status resp)))
    ;; Each of these is a phrase Malli only produces when humanizing the schema.
    ;; A bare "120" was here too and made this test flaky: the body carries a
    ;; random correlation-id UUID, and roughly one run in a hundred contains
    ;; those three hex characters by coincidence (BOU-377).
    (doseq [secret ["superuser" "internal-auditor"
                    "should be at least 18" "should be at most 120"]]
      (is (not (str/includes? raw secret))
          (str "leaked schema detail: " secret)))
    (is (= {:role ["invalid"] :age ["invalid"]} (:details (json/parse-string raw true))))))

(deftest ^:unit dev-gets-the-explanation-production-does-not
  (let [handler (fn [system]
                  (reitit/compile-routes [["/users"
                                           {:post {:handler    (fn [_] {:status 201 :body {}})
                                                   :parameters {:body [:map {:closed true} [:role [:enum "admin"]]]}}}]]
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

;; =============================================================================
;; Reitit route data, used as-is (ADR-037 / BOU-331)
;; =============================================================================

(deftest ^:unit reitit-data-passes-through-and-still-gets-the-interceptor-stack
  ;; The one thing the normalized format did that Reitit does not: every
  ;; endpoint gets the default interceptor stack — security headers, CSRF, rate
  ;; limiting, metrics — unless it opts out. Collapsing the format must not
  ;; collapse that.
  (let [decorate #'reitit/decorate-reitit-route
        [_ data] (decorate ["/users" {:get {:handler identity}}] {})]

    (testing "the endpoint keeps its handler"
      (is (= identity (get-in data [:get :handler]))))

    (testing "and gains the default stack as middleware"
      (is (= 1 (count (get-in data [:get :middleware])))))

    (testing "an endpoint that opts out gets none of it"
      (let [[_ d] (decorate ["/health" {:get {:handler identity
                                              :skip-interceptors? true}}] {})]
        (is (nil? (get-in d [:get :middleware])))
        (is (not (contains? (:get d) :skip-interceptors?))
            ":skip-interceptors? is ours, not Reitit's — it must not reach the router")))

    (testing "route-level data Reitit understands is left alone"
      (let [[path d] (decorate ["/users" {:name ::users
                                          :middleware [:route-mw]
                                          :get {:handler identity}}] {})]
        (is (= "/users" path))
        (is (= ::users (:name d)) "named routes are Reitit's, and reverse routing needs them")
        (is (= [:route-mw] (:middleware d)))))

    (testing "nested routes are walked, so children are decorated too"
      (let [[_ _ child] (decorate ["/api" {} ["/users" {:get {:handler identity}}]] {})
            [_ child-data] child]
        (is (= 1 (count (get-in child-data [:get :middleware]))))))

    (testing "non-endpoint keys are not mistaken for endpoints"
      (let [[_ d] (decorate ["/x" {:conflicting true :get {:handler identity}}] {})]
        (is (true? (:conflicting d)))))

    (testing "Reitit's bare-handler shorthand gets the stack too"
      ;; `{:get my-handler}` is valid Reitit and has nowhere to hang
      ;; middleware. Skipping it would serve that endpoint without security
      ;; headers, CSRF or rate limiting — a hole opened by writing less.
      (let [[_ d] (decorate ["/shorthand" {:get identity}] {})]
        (is (= identity (get-in d [:get :handler]))
            "the shorthand is expanded, not dropped")
        (is (= 1 (count (get-in d [:get :middleware])))
            "an endpoint written shorthand is as protected as one written long")))))

(deftest ^:unit every-route-in-the-table-is-decorated
  ;; compile-routes maps over the table; decorate-reitit-route takes one route.
  ;; An earlier version of this test handed the whole table to the single-route
  ;; function and passed, because [[..] [..]] destructures as [path data] and
  ;; comes back out with two elements — the assertions held while nothing was
  ;; decorated.
  (let [decorate #'reitit/decorate-reitit-route
        table    [["/a" {:get {:handler identity}}]
                  ["/b" {:post {:handler identity}}]]
        out      (mapv #(decorate % {}) table)]
    (is (= ["/a" "/b"] (mapv first out)))
    (is (= 1 (count (get-in (second (first out)) [:get :middleware]))))
    (is (= 1 (count (get-in (second (second out)) [:post :middleware]))))))

;; ===========================================================================
;; BOU-356: two routes that can both match the same request
;; ===========================================================================

(deftest ^:unit routes-precedence-decides-are-allowed
  ;; The shape every REST router has, and all six overlaps in the live route
  ;; table: a literal beside a parameter. Reitit matches the literal first, so
  ;; which handler runs is not in question.
  (testing "a literal sibling of a parameter"
    (is (some? (reitit/compile-routes
                [["/web/users/new" {:get {:handler identity}}]
                 ["/web/users/:id" {:get {:handler identity}}]]
                {}))))

  (testing "several literals against one parameter"
    (is (some? (reitit/compile-routes
                [["/web/users/new"   {:get {:handler identity}}]
                 ["/web/users/table" {:get {:handler identity}}]
                 ["/web/users/bulk"  {:get {:handler identity}}]
                 ["/web/users/:id"   {:get {:handler identity}}]]
                {}))))

  (testing "and routes that do not overlap at all"
    (is (some? (reitit/compile-routes
                [["/a" {:get {:handler identity}}]
                 ["/b" {:get {:handler identity}}]]
                {})))))

(deftest ^:unit routes-nothing-decides-between-fail-the-boot
  ;; Detection was off entirely, so these built a router and the first one
  ;; silently won. Which one answers depended on the order the route table was
  ;; concatenated in.
  (testing "two parameters in the same position are the same route twice"
    (let [e (try (reitit/compile-routes
                  [["/users/:id"  {:get {:handler identity}}]
                   ["/users/:uid" {:get {:handler identity}}]]
                  {})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "an ambiguous pair must not build")
      (is (= :wagoe/ambiguous-routes (:type (ex-data e))))
      (testing "and the message names both paths, since either could be the wrong one"
        (is (str/includes? (ex-message e) "/users/:id"))
        (is (str/includes? (ex-message e) "/users/:uid")))))

  (testing "a catch-all swallowing a sibling is not decided either"
    ;; `*path` matching everything under it is exactly the accident worth
    ;; reporting, so catch-alls are never treated as decided by precedence.
    (is (thrown? clojure.lang.ExceptionInfo
                 (reitit/compile-routes
                  [["/files/*path" {:get {:handler identity}}]
                   ["/files/:name" {:get {:handler identity}}]]
                  {})))))

(deftest ^:unit an-application-can-still-choose-its-own-conflict-policy
  ;; :conflicts is config, and nil is Reitit's "do not check" — an application
  ;; that genuinely wants the old behaviour can still ask for it.
  (is (some? (reitit/compile-routes
              [["/users/:id"  {:get {:handler identity}}]
               ["/users/:uid" {:get {:handler identity}}]]
              {:conflicts nil}))))

;; ===========================================================================
;; BOU-357: :wagoe/router settings reach the router, or say why not
;; ===========================================================================

(deftest ^:unit the-coercion-setting-means-something
  (let [options #'reitit/create-router-options]

    (testing "a named coercion resolves to the implementation"
      (is (some? (get-in (options {:coercion :malli}) [:data :coercion]))))

    (testing "absent falls back to Malli rather than to nothing"
      ;; nil here would disable coercion silently, which is the shape of the
      ;; bug this ticket is about.
      (is (some? (get-in (options {}) [:data :coercion])))
      (is (= (get-in (options {}) [:data :coercion])
             (get-in (options {:coercion :malli}) [:data :coercion]))))

    (testing "an instance is passed through untouched"
      (let [sentinel (reify Object)]
        (is (identical? sentinel (get-in (options {:coercion sentinel}) [:data :coercion])))))

    (testing "a name nobody implements fails, and says what exists"
      ;; The whole point: `:coercion :malli` sat in every config and reached
      ;; nothing, so a typo could not have been noticed.
      (let [e (try (options {:coercion :sepc}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= :configuration-error (:type (ex-data e))))
        (is (str/includes? (ex-message e) ":sepc"))
        (is (str/includes? (ex-message e) "malli"))))))

(deftest ^:unit an-applications-own-middleware-is-appended-not-substituted
  ;; :middleware in :wagoe/router is the application's, and the framework's
  ;; pipeline still has to run — so it goes after, seeing a request the
  ;; framework has already prepared.
  (let [mine    (fn [h] h)
        options (#'reitit/create-router-options {:middleware [mine]})
        stack   (get-in options [:data :middleware])]
    (is (= mine (last stack)) "the application's middleware runs last")
    (is (< 1 (count stack)) "and the framework's pipeline is still there")))

(def middleware-ran (atom false))

(defn stamping-middleware
  "Named by symbol from config, as an application would."
  [handler]
  (fn [request] (reset! middleware-ran true) (handler request)))

(deftest ^:unit middleware-named-by-symbol-resolves-to-a-function
  ;; EDN can only express a symbol, so this is the only way an application can
  ;; put its own middleware in config. `requiring-resolve` returns a Var, and
  ;; Reitit has no IntoMiddleware implementation for one — this failed with
  ;; "No implementation of method: :into-middleware ... clojure.lang.Var" the
  ;; first time the setting actually reached the router (BOU-357).
  (reset! middleware-ran false)
  (let [handler (reitit/compile-routes
                 [["/ping" {:get {:handler (fn [_] {:status 200 :body "pong"})}}]]
                 {:middleware ['wagoe.platform.shell.http.reitit-router-test/stamping-middleware]})]
    (is (= 200 (:status (handler {:request-method :get :uri "/ping"}))))
    (is (true? @middleware-ran) "middleware named in config must actually run")))

(deftest ^:unit a-symbol-that-resolves-to-nothing-is-a-configuration-error
  ;; Both ways a config symbol can be wrong, because they fail by different
  ;; routes: a missing namespace throws FileNotFoundException out of the
  ;; `require` with no ex-data, a missing var in a namespace that loads just
  ;; returns nil. An earlier version of this test only asserted that something
  ;; was thrown, so the untyped one went unnoticed.
  (doseq [[what sym] [["a namespace that does not exist" 'no.such.namespace/missing]
                      ["a var that does not exist"       'clojure.string/no-such-middleware]]]
    (testing what
      (let [e (try (reitit/compile-routes
                    [["/ping" {:get {:handler identity}}]]
                    {:middleware [sym]})
                   nil
                   (catch Exception e e))]
        (is (some? e) "an unresolvable middleware symbol must not be ignored")
        (is (= :configuration-error (:type (ex-data e)))
            "and must carry the shape the HTTP boundary maps (ADR-022)")
        (is (= sym (:symbol (ex-data e))) "naming the symbol that is wrong")))))

;; ===========================================================================
;; BOU-372: error handling, end to end through the stack that ships
;; ===========================================================================
;;
;; `security_test` covers the mapping and the leak rules by calling the
;; `http-error-handler` interceptor directly. What nothing covered was the whole
;; way through: a handler throws, and what the client receives.
;;
;; `error_handling_integration_test` claimed to, and did not. It assembled its
;; own stack out of `interfaces.http.middleware` — a namespace no application
;; ran — and asserted RFC 7807 (`application/problem+json`, `:title`,
;; `:context`), which this path does not produce. Both are gone.

(defn- throwing-handler-response
  "Response from a compiled route whose handler throws `ex`.

   `:body` is decoded to a string: muuntaja encodes it to a ByteArrayInputStream,
   so asserting over the raw value tests a stream's `toString` and passes
   whatever the body contains — which is how the first version of the leak test
   below passed vacuously."
  [ex & [request]]
  (let [handler (reitit/compile-routes
                 [["/boom" {:get {:handler (fn [_] (throw ex))}}]]
                 {:system {}})
        resp    (handler (merge {:request-method :get :uri "/boom"} request))]
    (cond-> resp
      (instance? java.io.InputStream (:body resp))
      (update :body slurp))))

(deftest ^:unit a-thrown-typed-error-reaches-the-client-as-its-status
  (doseq [[type status] {:validation-error 400
                         :not-found        404
                         :unauthorized     401
                         :forbidden        403
                         :conflict         409}]
    (testing (str type " → " status)
      (is (= status (:status (throwing-handler-response
                              (ex-info "nope" {:type type}))))))))

(deftest ^:unit ^:security an-untyped-throw-is-a-500-that-leaks-nothing
  ;; An untyped throw is caught by the reitit exception middleware rather than
  ;; the interceptor stack, so it answers the `missing-error-type` diagnostic
  ;; rather than the plain "Internal Server Error" `security_test` asserts of
  ;; the interceptor. Either way the client must learn nothing about the cause.
  (let [secret "relation \"users\" does not exist at /var/app/db.clj"
        resp   (throwing-handler-response (RuntimeException. secret))
        body   (:body resp)]
    (is (= 500 (:status resp)))
    (is (string? body) "decoded, so the assertions below read the real content")
    (is (not (str/includes? body secret))
        "the exception message must not reach the client")
    (is (not (str/includes? body "/var/app/db.clj"))
        "nor the path it names")))

(deftest ^:unit an-error-response-carries-the-caller-s-correlation-id
  ;; So a client reporting "your API 500s me" has an id to hand over.
  (let [resp (throwing-handler-response
              (ex-info "nope" {:type :not-found})
              {:headers {"x-correlation-id" "given-by-the-caller"}})]
    (is (= "given-by-the-caller" (get-in resp [:headers "X-Correlation-ID"])))))
