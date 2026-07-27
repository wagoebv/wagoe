(ns wagoe.platform.shell.security-test
  "Security-focused tests covering error mapping, CSRF routing logic,
   Hiccup XSS escaping, SQL parameterization, and sensitive field stripping.

   All tests are tagged ^:security ^:unit."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.platform.core.http.problem-details :as problem-details]
            [wagoe.platform.shell.http.interceptors :as interceptors]
            [wagoe.platform.shell.http.reitit-router :as reitit-router]
            [wagoe.platform.ports.http :as http-ports]
            [wagoe.admin.core.schema-introspection :as schema-intro]
            [buddy.core.nonce :as nonce]
            [honey.sql :as sql]
            [hiccup2.core :as h]))

;; =============================================================================
;; Error-type → HTTP status mapping
;; =============================================================================

(deftest ^:security ^:unit error-type-http-status-mapping-test
  (testing "default-error-mappings contains expected status codes"
    (let [m problem-details/default-error-mappings]
      (is (= 400 (first (m :validation-error))))
      (is (= 404 (first (m :not-found))))
      (is (= 401 (first (m :unauthorized))))
      (is (= 403 (first (m :forbidden))))
      (is (= 409 (first (m :conflict))))))

  (testing "error handler interceptor maps types to correct status codes"
    (let [error-handler (:error interceptors/http-error-handler)
          make-ctx (fn [error-type]
                     {:exception      (ex-info "test" {:type error-type})
                      :correlation-id "test-corr-id"
                      :system         {}})]
      (is (= 400 (get-in (error-handler (make-ctx :validation-error)) [:response :status])))
      (is (= 404 (get-in (error-handler (make-ctx :not-found)) [:response :status])))
      (is (= 401 (get-in (error-handler (make-ctx :unauthorized)) [:response :status])))
      (is (= 403 (get-in (error-handler (make-ctx :forbidden)) [:response :status])))
      (is (= 409 (get-in (error-handler (make-ctx :conflict)) [:response :status])))))

  (testing "unknown error type falls back to 500"
    (let [error-handler (:error interceptors/http-error-handler)
          ctx {:exception      (ex-info "test" {:type :unknown-type})
               :correlation-id "test-corr-id"
               :system         {}}]
      (is (= 500 (get-in (error-handler ctx) [:response :status]))))))

(deftest ^:security ^:unit error-500-does-not-leak-internals-test
  (let [error-handler (:error interceptors/http-error-handler)]
    (testing "a raw exception's message/class never reach the client on 500"
      (let [secret "relation \"users\" does not exist at /var/app/db.clj"
            ctx    {:exception (RuntimeException. secret) :correlation-id "corr-500" :system {}}
            resp   (:response (error-handler ctx))
            body   (:body resp)]
        (is (= 500 (:status resp)))
        (is (= "Internal Server Error" (:message body)))
        (is (= "corr-500" (:correlation-id body)))
        (is (nil? (:details body)) "no ex-data details on a 500")
        (is (not (str/includes? (pr-str body) secret)) "raw exception message must not leak")
        (is (not (str/includes? (pr-str body) "RuntimeException")))))

    (testing "a typed :internal-error does not leak its ex-data"
      (let [ctx  {:exception (ex-info "boom" {:type :internal-error :sql "SELECT secret" :path "/etc/passwd"})
                  :correlation-id "corr-501" :system {}}
            body (get-in (error-handler ctx) [:response :body])]
        (is (= "Internal Server Error" (:message body)))
        (is (nil? (:details body)))
        (is (not (str/includes? (pr-str body) "/etc/passwd")))
        (is (not (str/includes? (pr-str body) "SELECT secret")))))

    (testing "typed 4xx still returns its intended domain message and details"
      (let [ctx  {:exception (ex-info "Email is required" {:type :validation-error :field :email})
                  :correlation-id "corr-400" :system {}}
            resp (:response (error-handler ctx))]
        (is (= 400 (:status resp)))
        (is (= "Email is required" (get-in resp [:body :message])))
        (is (= {:field :email} (get-in resp [:body :details])))))))

;; =============================================================================
;; CSRF interceptor routing logic
;; =============================================================================

(def ^:private csrf-secret "test-secret-at-least-32-chars-long!!")
(def ^:private csrf-session "session-token-xyz")

(defn- valid-csrf-token
  "A token bound to csrf-session, as the UI layer would emit."
  []
  (csrf/generate-token csrf-secret csrf-session (nonce/random-bytes 16)))

(defn- run-csrf
  "Run the CSRF interceptor :enter with the given csrf config and request.
   Returns the resulting context."
  [csrf-config request]
  ((:enter interceptors/http-csrf-protection)
   {:request request :system {:csrf csrf-config}}))

(defn- session-request
  "A request carrying a session cookie, optionally with a CSRF token form field."
  [method uri & {:keys [token]}]
  (cond-> {:request-method method
           :uri uri
           :cookies {"session-token" {:value csrf-session}}}
    token (assoc :form-params {"__anti-forgery-token" token})))

(deftest ^:security ^:unit csrf-interceptor-protection-test
  (let [cfg {:enabled? true :secret csrf-secret :exempt-paths ["/api/v1/payments/webhook"]}]

    (testing "session POST to /web without a token is rejected with 403"
      (is (= 403 (get-in (run-csrf cfg (session-request :post "/web/profile/update"))
                         [:response :status]))))

    (testing "session POST to /web with a valid token passes through"
      (is (nil? (:response (run-csrf cfg (session-request :post "/web/profile/update"
                                                          :token (valid-csrf-token)))))))

    (testing "session POST to /web with an invalid token is rejected"
      (is (= 403 (get-in (run-csrf cfg (session-request :post "/web/profile/update"
                                                        :token "bogus.token"))
                         [:response :status]))))

    (testing "token presented via X-CSRF-Token header is accepted"
      (let [req (-> (session-request :post "/web/profile/update")
                    (assoc-in [:headers "x-csrf-token"] (valid-csrf-token)))]
        (is (nil? (:response (run-csrf cfg req))))))

    (testing "/web/admin is now protected (no token → 403)"
      (is (= 403 (get-in (run-csrf cfg (session-request :post "/web/admin/users"))
                         [:response :status]))))

    (testing "session-authenticated /api route is protected (no token → 403)"
      (is (= 403 (get-in (run-csrf cfg (session-request :post "/api/v1/users"))
                         [:response :status]))))

    (testing "token-auth API request without a session cookie is skipped"
      (is (nil? (:response (run-csrf cfg {:request-method :post :uri "/api/v1/users"})))))

    (testing "exempt path (webhook) is skipped even with a session cookie"
      (is (nil? (:response (run-csrf cfg (session-request :post "/api/v1/payments/webhook"))))))

    (testing "GET / HEAD / OPTIONS are never checked"
      (doseq [m [:get :head :options]]
        (is (nil? (:response (run-csrf cfg (session-request m "/web/profile"))))
            (str m " must skip CSRF"))))

    (testing "PUT/DELETE/PATCH with a session and no token are rejected"
      (doseq [m [:put :delete :patch]]
        (is (= 403 (get-in (run-csrf cfg (session-request m "/web/profile/update"))
                           [:response :status]))
            (str m " should be checked as state-changing"))))

    (testing "disabled config skips all checks"
      (is (nil? (:response (run-csrf {:enabled? false :secret csrf-secret}
                                     (session-request :post "/web/profile/update"))))))

    (testing "wildcard exempt matches on a path-segment boundary, not bare prefix"
      (let [wild {:enabled? true :secret csrf-secret :exempt-paths ["/api/hooks/*"]}]
        ;; under the exempt subtree -> skipped
        (is (nil? (:response (run-csrf wild (session-request :post "/api/hooks/stripe")))))
        (is (nil? (:response (run-csrf wild (session-request :post "/api/hooks")))))
        ;; sibling path sharing the prefix but not the boundary -> still protected
        (is (= 403 (get-in (run-csrf wild (session-request :post "/api/hooks-evil"))
                           [:response :status])))))))

(deftest ^:security ^:unit csrf-default-is-opt-in-test
  (testing "a :csrf map with a secret but NO :enabled? key is a no-op (opt-in, default off)"
    (let [cfg {:secret csrf-secret :exempt-paths []} ; note: no :enabled? key
          ctx (run-csrf cfg (session-request :post "/web/profile/update"))]
      (is (nil? (:response ctx)) "missing :enabled? must default to off — no 403")
      (is (nil? (get-in ctx [:request :anti-forgery-token]))
          "default-off must not run the issuance path either"))))

(defn- run-csrf-full
  "Run both :enter and :leave so pre-session cookie effects are observable."
  [csrf-config request]
  (let [enter (:enter interceptors/http-csrf-protection)
        leave (:leave interceptors/http-csrf-protection)]
    (leave (enter {:request request :system {:csrf csrf-config}}))))

(deftest ^:security ^:unit csrf-pre-session-test
  (let [cfg {:enabled? true :secret csrf-secret :exempt-paths []}]

    (testing "unauthenticated /web page GET mints a pre-session cookie and a token"
      (let [ctx (run-csrf-full cfg {:request-method :get :uri "/web/login"})
            cookie (get-in ctx [:response :cookies "csrf-session"])]
        (is (string? (get-in ctx [:request :anti-forgery-token])) "form token issued")
        (is (string? (:value cookie)) "pre-session cookie set")
        (is (= :strict (:same-site cookie)))
        (is (true? (:http-only cookie)))))

    (testing "login POST with a token bound to the pre-session cookie passes"
      ;; The GET mints the binding (delivered as the csrf-session cookie); a token
      ;; bound to that value, submitted with the cookie, validates on POST.
      (let [get-ctx   (run-csrf-full cfg {:request-method :get :uri "/web/login"})
            cookie-id (get-in get-ctx [:response :cookies "csrf-session" :value])
            token     (csrf/generate-token csrf-secret cookie-id (nonce/random-bytes 16))
            post      {:request-method :post :uri "/web/login"
                       :cookies {"csrf-session" {:value cookie-id}}
                       :form-params {"__anti-forgery-token" token}}]
        (is (string? cookie-id))
        (is (nil? (:response (run-csrf cfg post))))))

    (testing "login POST without a token is rejected"
      (is (= 403 (get-in (run-csrf cfg {:request-method :post :uri "/web/login"
                                        :cookies {"csrf-session" {:value "abc"}}})
                         [:response :status]))))

    (testing "login POST with a token bound to a different pre-session is rejected"
      (let [token (csrf/generate-token csrf-secret "other-pre-session" (nonce/random-bytes 16))]
        (is (= 403 (get-in (run-csrf cfg {:request-method :post :uri "/web/login"
                                          :cookies {"csrf-session" {:value "abc"}}
                                          :form-params {"__anti-forgery-token" token}})
                           [:response :status])))))

    (testing "unauthenticated /web POST with no session and no pre-session cookie fails closed"
      (is (= 403 (get-in (run-csrf cfg {:request-method :post :uri "/web/login"})
                         [:response :status]))))))

(deftest ^:security ^:unit csrf-token-bound-during-render-test
  (testing "csrf/*token* is bound to the issued token while the handler renders"
    (let [captured (atom :unset)
          handler  (fn [_req] (reset! captured (csrf/current-token)) {:status 200})
          _resp    (interceptors/run-http-interceptors
                    handler
                    [interceptors/http-csrf-protection]
                    {:request-method :get :uri "/web/login" :cookies {}}
                    {:csrf {:enabled? true :secret csrf-secret}})]
      (is (string? @captured) "handler observed a bound CSRF token")
      (is (= 2 (count (str/split @captured #"\."))) "token has nonce.mac form")))

  (testing "no token is bound for a session-less, non-web request"
    (let [captured (atom :unset)
          handler  (fn [_req] (reset! captured (csrf/current-token)) {:status 200})]
      (interceptors/run-http-interceptors
       handler [interceptors/http-csrf-protection]
       {:request-method :get :uri "/api/v1/ping" :cookies {}}
       {:csrf {:enabled? true :secret csrf-secret}})
      (is (nil? @captured)))))

;; =============================================================================
;; CSRF through the real router (regression for the :no-doc interceptor-skip bug:
;; /web routes are :no-doc true, and previously :no-doc dropped the default
;; interceptor stack, so CSRF never ran on the web UI it is meant to protect.)
;; =============================================================================

(defn- compile-web-handler
  "Compile a single normalized route through the real reitit router with CSRF on."
  [route]
  (http-ports/compile-routes
   (reitit-router/->ReititRouter)
   [route]
   {:swagger-enabled false
    :system {:csrf {:enabled? true :secret csrf-secret :exempt-paths ["/web/hook"]}}}))

(defn- req
  "Build a request for the compiled handler. Cookies/token go through real headers
   because the router's wrap-cookies/wrap-params reparse them from the raw request."
  [method uri & {:keys [session? token]}]
  (cond-> {:request-method method :uri uri :headers {}}
    session? (assoc-in [:headers "cookie"] (str "session-token=" csrf-session))
    token    (assoc-in [:headers "x-csrf-token"] token)))

(deftest ^:security ^:unit csrf-runs-on-web-routes-through-router-test
  (let [web-route {:path "/web/profile/update"
                   :no-doc true                       ; as wiring marks every /web route
                   :methods {:post {:handler (fn [_] {:status 200 :body "ok"})}
                             :get  {:handler (fn [_] {:status 200 :body "ok"})}}}
        handler   (compile-web-handler web-route)]

    (testing "session POST to a :no-doc /web route WITHOUT a token is blocked (regression)"
      (is (= 403 (:status (handler (req :post "/web/profile/update" :session? true))))))

    (testing "session POST with a valid token passes the router stack"
      (let [token (csrf/generate-token csrf-secret csrf-session (nonce/random-bytes 16))]
        (is (= 200 (:status (handler (req :post "/web/profile/update"
                                          :session? true :token token)))))))

    (testing "GET to the same route is not blocked"
      (is (= 200 (:status (handler (req :get "/web/profile/update" :session? true))))))

    (testing "session-less /web POST with no pre-session cookie fails closed (403)"
      (is (= 403 (:status (handler (req :post "/web/profile/update"))))))

    (testing "session-less non-web POST is skipped (token-auth API, not CSRF-vulnerable)"
      (let [api ((compile-web-handler {:path "/api/v1/things"
                                       :methods {:post {:handler (fn [_] {:status 200 :body "ok"})}}})
                 (req :post "/api/v1/things"))]
        (is (= 200 (:status api)))))))

(deftest ^:security ^:unit csrf-skip-interceptors-flag-test
  (testing ":skip-interceptors? routes bypass the stack (health/internal endpoints)"
    (let [route   {:path "/health"
                   :no-doc true
                   :methods {:post {:skip-interceptors? true
                                    :handler (fn [_] {:status 200 :body "ok"})}}}
          handler (compile-web-handler route)]
      ;; A session POST with no token would be 403 if CSRF ran; skip flag lets it pass.
      (is (= 200 (:status (handler (req :post "/health" :session? true))))))))

;; =============================================================================
;; Hiccup XSS escaping
;; =============================================================================

(deftest ^:security ^:unit hiccup-xss-escaping-test
  (testing "Script tags in text content are escaped"
    (let [malicious "<script>alert('xss')</script>"
          html (str (h/html [:p malicious]))]
      (is (not (str/includes? html "<script>")))
      (is (str/includes? html "&lt;script&gt;"))))

  (testing "HTML entities in attribute values are escaped"
    (let [malicious "\" onmouseover=\"alert('xss')\""
          html (str (h/html [:div {:title malicious} "content"]))]
      ;; Quotes must be escaped to &quot; preventing attribute breakout.
      ;; Hiccup 2 renders: <div title="&quot; onmouseover=&quot;alert(...)&quot;">
      ;; The escaped quotes prevent the attribute value from breaking out.
      (is (str/includes? html "&quot;")
          "Double quotes should be escaped to &quot;")
      ;; Verify the title attribute is properly closed — no unescaped " before onmouseover
      (is (not (re-find #"title=\"[^\"]*\"\s+onmouseover" html))
          "Attribute breakout should be prevented by quote escaping"))))

;; =============================================================================
;; SQL parameterization
;; =============================================================================

(deftest ^:security ^:unit sql-parameterization-test
  (testing "HoneySQL produces parameterized queries, not interpolated SQL"
    (let [user-input "Robert'; DROP TABLE users;--"
          query {:select [:id :name]
                 :from   [:users]
                 :where  [:= :name user-input]}
          [sql-str & params] (sql/format query)]
      ;; SQL string uses ? placeholder
      (is (str/includes? sql-str "?"))
      ;; Malicious input is in params, not in the SQL string
      (is (not (str/includes? sql-str user-input)))
      (is (= user-input (first params)))))

  (testing "Multiple parameters are all parameterized"
    (let [query {:select [:id]
                 :from   [:users]
                 :where  [:and
                          [:= :email "test@example.com"]
                          [:= :role "admin"]]}
          [sql-str & params] (sql/format query)]
      (is (= 2 (count params)))
      (is (not (str/includes? sql-str "test@example.com")))
      (is (not (str/includes? sql-str "admin"))))))

;; =============================================================================
;; Sensitive field stripping
;; =============================================================================

(deftest ^:security ^:unit sensitive-field-stripping-test
  (testing "should-be-hidden? returns true for known sensitive fields"
    (doseq [field [:password-hash :secret :token :api-key :private-key :salt :hash]]
      (is (true? (schema-intro/should-be-hidden? field))
          (str field " should be hidden"))))

  (testing "should-be-hidden? returns false for safe fields"
    (doseq [field [:email :name :id :role :created-at]]
      (is (false? (schema-intro/should-be-hidden? field))
          (str field " should not be hidden")))))

;; =============================================================================
;; Security response headers (BOU-168)
;; =============================================================================

(defn- apply-security-headers
  "Run the http-security-headers interceptor :leave over a bare 200 response."
  []
  (let [interceptor (interceptors/http-security-headers)
        ctx         {:response {:status 200 :headers {} :body ""}}]
    (get-in ((:leave interceptor) ctx) [:response :headers])))

(deftest ^:security ^:unit security-response-headers-test
  (let [headers (apply-security-headers)]
    (testing "clickjacking + framing protection"
      (is (= "DENY" (get headers "X-Frame-Options")))
      (is (str/includes? (get headers "Content-Security-Policy") "frame-ancestors 'none'")))
    (testing "transport security (HSTS forces HTTPS for a year, with preload)"
      (is (= "max-age=31536000; includeSubDomains; preload"
             (get headers "Strict-Transport-Security"))))
    (testing "MIME-sniffing protection"
      (is (= "nosniff" (get headers "X-Content-Type-Options"))))
    (testing "CSP restricts default + object sources"
      (let [csp (get headers "Content-Security-Policy")]
        (is (str/includes? csp "default-src 'self'"))
        (is (str/includes? csp "object-src 'none'"))
        (is (str/includes? csp "base-uri 'self'"))))
    (testing "referrer, cross-origin isolation, and feature policy are locked down"
      (is (= "strict-origin-when-cross-origin" (get headers "Referrer-Policy")))
      (is (= "same-origin" (get headers "Cross-Origin-Opener-Policy")))
      (is (= "geolocation=(), microphone=(), camera=()" (get headers "Permissions-Policy")))))
  (testing "the interceptor is actually mounted on the router stack, not just callable in isolation"
    (let [handler (compile-web-handler {:path    "/web/headers-probe"
                                        :no-doc  true
                                        :methods {:get {:handler (fn [_] {:status 200 :body "ok"})}}})
          resp    (handler (req :get "/web/headers-probe"))]
      (is (= "DENY" (get-in resp [:headers "X-Frame-Options"]))
          "a routed response carries the security headers → interceptor is wired in")
      (is (some? (get-in resp [:headers "Strict-Transport-Security"]))))))
