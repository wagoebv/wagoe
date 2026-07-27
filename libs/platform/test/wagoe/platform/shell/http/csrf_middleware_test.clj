(ns wagoe.platform.shell.http.csrf-middleware-test
  "Ring-middleware form of CSRF protection (`wrap-csrf`), for consumers whose
   handlers run outside the Pedestal-style interceptor stack (e.g. apps that mount
   their own routes in front of the platform handler). Mirrors the
   `http-csrf-protection` interceptor semantics: session/pre-session binding,
   403 on a bad/absent token for state-changing requests, and `csrf/*token*` bound
   around the handler so forms/`<meta>` can emit it.

   All tests are tagged ^:security ^:unit."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.platform.shell.http.interceptors :as interceptors]
            [buddy.core.nonce :as nonce]))

(def ^:private secret "test-secret-at-least-32-chars-long!!")
(def ^:private session "session-token-xyz")

(defn- valid-token []
  (csrf/generate-token secret session (nonce/random-bytes 16)))

(defn- session-request
  "Request carrying a session cookie, optionally with a CSRF token form field."
  [method uri & {:keys [token]}]
  (cond-> {:request-method method
           :uri uri
           :cookies {"session-token" {:value session}}}
    token (assoc :form-params {"__anti-forgery-token" token})))

(defn- ok-handler
  "Handler returning 200; records that it was called and the token bound at render."
  [calls]
  (fn [_req]
    (swap! calls conj (csrf/current-token))
    {:status 200 :body "ok"}))

(def ^:private cfg {:enabled? true :secret secret :exempt-paths ["/api/v1/payments/webhook"]})

(deftest ^:security ^:unit wrap-csrf-rejects-state-changing-without-token-test
  (testing "session POST without a token → 403, handler not called"
    (let [calls   (atom [])
          handler (interceptors/wrap-csrf (ok-handler calls) cfg)
          resp    (handler (session-request :post "/web/profile/update"))]
      (is (= 403 (:status resp)))
      (is (empty? @calls) "handler must not run when CSRF rejects"))))

(deftest ^:security ^:unit wrap-csrf-allows-valid-token-test
  (testing "session POST with a valid form token → handler runs (200)"
    (let [calls   (atom [])
          handler (interceptors/wrap-csrf (ok-handler calls) cfg)
          resp    (handler (session-request :post "/web/profile/update" :token (valid-token)))]
      (is (= 200 (:status resp)))
      (is (= 1 (count @calls)))))

  (testing "token via X-CSRF-Token header is accepted"
    (let [calls   (atom [])
          handler (interceptors/wrap-csrf (ok-handler calls) cfg)
          req     (-> (session-request :post "/web/profile/update")
                      (assoc-in [:headers "x-csrf-token"] (valid-token)))
          resp    (handler req)]
      (is (= 200 (:status resp))))))

(deftest ^:security ^:unit wrap-csrf-rejects-invalid-token-test
  (testing "session POST with a bogus token → 403"
    (let [handler (interceptors/wrap-csrf (ok-handler (atom [])) cfg)]
      (is (= 403 (:status (handler (session-request :post "/web/profile/update"
                                                    :token "bogus.token"))))))))

(deftest ^:security ^:unit wrap-csrf-binds-token-during-render-test
  (testing "csrf/*token* is bound while the handler renders a safe GET (so forms/meta emit it)"
    (let [calls   (atom [])
          handler (interceptors/wrap-csrf (ok-handler calls) cfg)
          _resp   (handler (session-request :get "/web/profile"))]
      (is (= 1 (count @calls)))
      (is (string? (first @calls)) "handler observed a bound token")
      (is (= 2 (count (str/split (first @calls) #"\."))) "token has nonce.mac form")))

  (testing "the token bound on a safe GET validates on a subsequent POST"
    (let [calls   (atom [])
          handler (interceptors/wrap-csrf (ok-handler calls) cfg)
          _       (handler (session-request :get "/web/profile"))
          minted  (first @calls)
          resp    (handler (session-request :post "/web/profile/update" :token minted))]
      (is (= 200 (:status resp)) "a token minted during render must validate"))))

(deftest ^:security ^:unit wrap-csrf-skips-safe-and-exempt-test
  (testing "GET/HEAD/OPTIONS are never checked"
    (doseq [m [:get :head :options]]
      (let [handler (interceptors/wrap-csrf (ok-handler (atom [])) cfg)]
        (is (= 200 (:status (handler (session-request m "/web/profile"))))
            (str m " must skip CSRF")))))

  (testing "exempt path is skipped even with a session cookie"
    (let [handler (interceptors/wrap-csrf (ok-handler (atom [])) cfg)]
      (is (= 200 (:status (handler (session-request :post "/api/v1/payments/webhook")))))))

  (testing "session-less non-web POST (token-auth API) is skipped"
    (let [handler (interceptors/wrap-csrf (ok-handler (atom [])) cfg)]
      (is (= 200 (:status (handler {:request-method :post :uri "/api/v1/users"})))))))

(deftest ^:security ^:unit wrap-csrf-disabled-is-noop-test
  (testing "disabled config: no validation, no token issuance"
    (let [calls   (atom [])
          handler (interceptors/wrap-csrf (ok-handler calls) {:enabled? false :secret secret})
          resp    (handler (session-request :post "/web/profile/update"))]
      (is (= 200 (:status resp)) "no 403 when disabled")
      (is (= [nil] @calls) "no token bound when disabled")))

  (testing "missing :enabled? key defaults to off (opt-in parity with the interceptor)"
    (let [handler (interceptors/wrap-csrf (ok-handler (atom [])) {:secret secret})]
      (is (= 200 (:status (handler (session-request :post "/web/profile/update"))))))))

(deftest ^:security ^:unit wrap-csrf-pre-session-cookie-test
  (testing "unauthenticated /web GET mints a pre-session cookie on the response"
    (let [handler (interceptors/wrap-csrf (ok-handler (atom [])) {:enabled? true :secret secret})
          resp    (handler {:request-method :get :uri "/web/login"})
          cookie  (get-in resp [:cookies "csrf-session"])]
      (is (= 200 (:status resp)))
      (is (string? (:value cookie)) "pre-session cookie set")
      (is (= :strict (:same-site cookie)))
      (is (true? (:http-only cookie))))))
