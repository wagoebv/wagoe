(ns wagoe.user.shell.user-api-authz-security-test
  "Regression test for the IDOR fix (BOU-190): the /users and /users/:id API
   routes must actually MOUNT the authorization guards, not merely have guards
   that work in isolation. Reads the real route table from `normalized-api-routes`,
   resolves the interceptors each method declares, and asserts both that the
   correct guard is mounted and that the mounted guard denies a non-admin acting
   on another user's id."
  (:require [wagoe.user.shell.http :as http]
            [wagoe.user.shell.http-interceptors]
            [clojure.test :refer [deftest is testing]]))

(defn- interceptors-of [routes path method]
  (->> routes
       (filter #(= path (:path %)))
       first :methods method :interceptors
       (map #(deref (resolve %)))))

(def ^:private api-routes (http/normalized-api-routes nil nil))
(def ^:private web-routes (http/normalized-web-routes nil nil nil))

(defn- method-interceptors [path method] (interceptors-of api-routes path method))
(defn- web-method-interceptors [path method] (interceptors-of web-routes path method))

(defn- mounted? [path method interceptor-name]
  (boolean (some #(= interceptor-name (:name %)) (method-interceptors path method))))

(defn- web-mounted? [path method interceptor-name]
  (boolean (some #(= interceptor-name (:name %)) (web-method-interceptors path method))))

(defn- guard [path method interceptor-name]
  (first (filter #(= interceptor-name (:name %)) (method-interceptors path method))))

(defn- web-guard [path method interceptor-name]
  (first (filter #(= interceptor-name (:name %)) (web-method-interceptors path method))))

(defn- denied? [interceptor ctx]
  (= 403 (get-in ((:enter interceptor) ctx) [:response :status])))

;; A non-admin user acting on a DIFFERENT user's id.
(def ^:private cross-user-ctx
  {:request        {:user {:id "user-A" :role :user} :uri "/api/v1/users/user-B"}
   :path-params    {:id "user-B"}
   :system         {:logger nil :metrics-emitter nil}
   :correlation-id "idor-test"})

;; =============================================================================
;; Guards are mounted on the real routes (the BOU-190 finding)
;; =============================================================================

(deftest ^:security ^:unit users-by-id-routes-mount-authorization-guards
  (testing "GET /users/:id is guarded by require-self-or-admin (read own or admin)"
    (is (mounted? "/users/:id" :get :require-self-or-admin)))
  (testing "PUT /users/:id is admin-only (mutation incl. :role → no self-escalation)"
    (is (mounted? "/users/:id" :put :require-admin)))
  (testing "DELETE /users/:id is admin-only"
    (is (mounted? "/users/:id" :delete :require-admin)))
  (testing "GET /users (list) is admin-only (no directory enumeration by non-admins)"
    (is (mounted? "/users" :get :require-admin)))
  (testing "every guarded method also runs require-authenticated first"
    (doseq [[path method] [["/users/:id" :get] ["/users/:id" :put]
                           ["/users/:id" :delete] ["/users" :get]]]
      (is (mounted? path method :require-authenticated)
          (str path " " method " must authenticate before authorizing")))))

;; =============================================================================
;; The mounted guards deny cross-user / non-admin access (behavioural IDOR proof)
;; =============================================================================

(deftest ^:security ^:unit mounted-guards-deny-non-admin-cross-user-access
  (testing "GET /users/:id: a non-admin fetching another user's id is forbidden"
    (is (denied? (guard "/users/:id" :get :require-self-or-admin) cross-user-ctx)))
  (testing "PUT /users/:id: a non-admin is forbidden (cannot edit/escalate another user)"
    (is (denied? (guard "/users/:id" :put :require-admin) cross-user-ctx)))
  (testing "DELETE /users/:id: a non-admin is forbidden"
    (is (denied? (guard "/users/:id" :delete :require-admin) cross-user-ctx)))
  (testing "GET /users: a non-admin cannot list the user directory"
    (is (denied? (guard "/users" :get :require-admin) cross-user-ctx))))

;; =============================================================================
;; Web session routes act on the :id path param — also IDOR-guarded
;; =============================================================================

(deftest ^:security ^:unit web-user-session-routes-guard-cross-user-access
  (testing "GET /users/:id/sessions is self-or-admin (cannot view another user's sessions)"
    (is (web-mounted? "/users/:id/sessions" :get :require-self-or-admin))
    (is (denied? (web-guard "/users/:id/sessions" :get :require-self-or-admin) cross-user-ctx)))
  (testing "POST /users/:id/sessions/revoke-all is self-or-admin (cannot force-logout another user)"
    (is (web-mounted? "/users/:id/sessions/revoke-all" :post :require-self-or-admin))
    (is (denied? (web-guard "/users/:id/sessions/revoke-all" :post :require-self-or-admin) cross-user-ctx))))

;; =============================================================================
;; Web user-management routes (re-mounted in BOU-197) — same guard model
;; =============================================================================

(deftest ^:security ^:unit web-user-management-routes-guard-cross-user-access
  (testing "GET /users/:id (management detail) is admin-only (self-service is /profile)"
    (is (web-mounted? "/users/:id" :get :require-admin))
    (is (denied? (web-guard "/users/:id" :get :require-admin) cross-user-ctx)))
  (testing "PUT /users/:id (update) is admin-only — no self-escalation / cross-user edit"
    (is (web-mounted? "/users/:id" :put :require-admin))
    (is (denied? (web-guard "/users/:id" :put :require-admin) cross-user-ctx)))
  (testing "DELETE /users/:id (deactivate) is admin-only"
    (is (web-mounted? "/users/:id" :delete :require-admin))
    (is (denied? (web-guard "/users/:id" :delete :require-admin) cross-user-ctx)))
  (testing "POST /users/:id/hard-delete is admin-only"
    (is (web-mounted? "/users/:id/hard-delete" :post :require-admin))
    (is (denied? (web-guard "/users/:id/hard-delete" :post :require-admin) cross-user-ctx)))
  (testing "the table-fragment and bulk routes are admin-only (no directory enumeration / mass edit)"
    (is (web-mounted? "/users/table" :get :require-admin))
    (is (denied? (web-guard "/users/table" :get :require-admin) cross-user-ctx))
    (is (web-mounted? "/users/bulk" :post :require-admin))
    (is (denied? (web-guard "/users/bulk" :post :require-admin) cross-user-ctx))))
;; Note: GET /users (list) shares the /users route with POST create, guarded by
;; route-level require-admin-middleware (the same trusted guard that already
;; protects create) — not a per-method interceptor, so not asserted here.
