(ns support.handler-test-helpers
  "Helper utilities for HTTP handler and interceptor testing.

   Provides:
   - Ring request builders (make-request, make-get, make-post, etc.)
   - Response assertion helpers (assert-status, assert-redirect, assert-header)
   - Authentication / CSRF helpers for request enrichment"
  (:require [clojure.test :refer [is]]
            [wagoe.platform.core.csrf :as csrf]
            [buddy.core.nonce :as nonce])
  (:import [java.util UUID]))

;; =============================================================================
;; Ring Request Builders
;; =============================================================================

(defn make-request
  "Build a Ring request map with sensible defaults.

   Args:
     opts: Map with optional keys:
       :method       - HTTP method keyword (default :get)
       :uri          - Request URI (default \"/\")
       :params       - Query/form params map
       :headers      - Headers map
       :body         - Request body
       :user         - Authenticated user map
       :session      - Session map
       :path-params  - Path parameters map
       :content-type - Content-Type header value

   Returns:
     Ring request map"
  [& [opts]]
  (let [{:keys [method uri params headers body user session
                path-params content-type]} opts]
    (cond-> {:request-method (or method :get)
             :uri            (or uri "/")
             :headers        (or headers {})
             :server-name    "localhost"
             :server-port    8080
             :scheme         :http}
      params       (assoc :params params)
      body         (assoc :body body)
      user         (assoc :user user)
      session      (assoc :session session)
      path-params  (assoc :path-params path-params)
      content-type (assoc-in [:headers "content-type"] content-type))))

(defn make-get
  "Build a GET request.

   Args:
     uri:  Request URI
     opts: Additional options (see make-request)"
  [uri & [opts]]
  (make-request (merge opts {:method :get :uri uri})))

(defn make-post
  "Build a POST request.

   Args:
     uri:  Request URI
     opts: Additional options (see make-request)"
  [uri & [opts]]
  (make-request (merge opts {:method :post :uri uri})))

(defn make-put
  "Build a PUT request.

   Args:
     uri:  Request URI
     opts: Additional options (see make-request)"
  [uri & [opts]]
  (make-request (merge opts {:method :put :uri uri})))

(defn make-delete
  "Build a DELETE request.

   Args:
     uri:  Request URI
     opts: Additional options (see make-request)"
  [uri & [opts]]
  (make-request (merge opts {:method :delete :uri uri})))

;; =============================================================================
;; Response Assertions
;; =============================================================================

(defn assert-status
  "Assert that the response has the expected HTTP status code.

   Args:
     response: Ring response map
     expected: Expected HTTP status code (integer)"
  [response expected]
  (is (= expected (:status response))
      (str "Expected status " expected ", got " (:status response))))

(defn assert-redirect
  "Assert that the response is a redirect (3xx) with the given Location.

   Args:
     response: Ring response map
     location: Expected Location header value"
  [response location]
  (is (<= 300 (:status response) 399)
      (str "Expected 3xx redirect, got " (:status response)))
  (is (= location (get-in response [:headers "Location"]))
      (str "Expected redirect to " location)))

(defn assert-header
  "Assert that the response has a specific header value.

   Args:
     response: Ring response map
     header:   Header name (string)
     expected: Expected header value"
  [response header expected]
  (is (= expected (get-in response [:headers header]))
      (str "Expected header " header " = " expected)))

;; =============================================================================
;; Authentication & CSRF Helpers
;; =============================================================================

(defn with-authenticated-user
  "Add an authenticated user to the request.

   Args:
     request: Ring request map
     user:    User map (or uses a default test user)

   Returns:
     Updated request with :user and :auth-type"
  [request & [user]]
  (let [default-user {:id        (UUID/randomUUID)
                      :email     "test@example.com"
                      :name      "Test User"
                      :role      :user
                      :active    true}]
    (assoc request
           :user (or user default-user)
           :auth-type :session)))

(defn with-csrf-token
  "Add a CSRF token to the request (form-params + header).

   Args:
     request: Ring request map
     token:   CSRF token string (defaults to a generated UUID)

   Returns:
     Updated request with CSRF token in form-params and X-CSRF-Token header"
  [request & [token]]
  (let [csrf-token (or token (str (UUID/randomUUID)))]
    (-> request
        (assoc-in [:form-params (keyword csrf/field-name)] csrf-token)
        (assoc-in [:form-params csrf/field-name] csrf-token)
        (assoc-in [:headers csrf/header-name] csrf-token)
        (assoc :anti-forgery-token csrf-token))))

(defn- request-csrf-binding
  "The binding a real token must be signed against for this request: the session
   token, else the pre-session csrf-session cookie."
  [request]
  (or (get-in request [:headers "x-session-token"])
      (get-in request [:cookies "session-token" :value])
      (get-in request [:cookies "csrf-session" :value])))

(defn with-valid-csrf-token
  "Attach a CSRF token that passes real validation: signed with `secret` and bound
   to the request's session (or pre-session) binding. Use in CSRF-enabled handler
   tests; with-csrf-token's random token only suffices when CSRF is disabled."
  [request secret]
  (with-csrf-token request
    (csrf/generate-token secret (request-csrf-binding request) (nonce/random-bytes 16))))
