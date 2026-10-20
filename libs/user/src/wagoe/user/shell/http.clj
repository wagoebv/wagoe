(ns wagoe.user.shell.http
  "HTTP routing and handlers for user module - provides structured route definitions.

   This namespace implements the REST API and Web UI for user and session management.
   
   Route Structure:
   ----------------
   The module exports routes in a structured format {:api [...] :web [...] :static [...]}
   for composition by the top-level router, which applies appropriate prefixes:
   
   API Endpoints (mounted under /api):
   - POST   /api/users           - Create user
   - GET    /api/users/:id       - Get user by ID
   - GET    /api/users           - List users (with filters)
   - PUT    /api/users/:id       - Update user
   - DELETE /api/users/:id       - Soft delete user
   - POST   /api/sessions        - Create session (login)
   - GET    /api/sessions/:token - Validate session
   - DELETE /api/sessions/:token - Invalidate session (logout)
   - POST   /api/auth/login      - Authenticate with email/password

   Web UI Endpoints (mounted under /web):
   - GET    /web/users           - Users listing page
   - GET    /web/users/new       - Create user page
   - GET    /web/users/:id       - User detail page
   - POST   /web/users           - Create user (HTMX fragment)
   - PUT    /web/users/:id       - Update user (HTMX fragment)
   - DELETE /web/users/:id       - Delete user (HTMX fragment)
   
   Static Assets (mounted at root):
   - GET    /css/*                - CSS assets
   - GET    /js/*                 - JavaScript assets
   - GET    /modules/*            - Module-specific assets
   - GET    /docs/*               - Documentation

   All observability is handled automatically by interceptors."
  (:require [wagoe.core.interceptor :as interceptor]
            [wagoe.core.interceptor-context :as interceptor-context]
            [wagoe.platform.core.http.access :as access]
            [wagoe.platform.core.http.errors :as errors]
            [wagoe.user.shell.http-interceptors]
            [wagoe.user.shell.landing :as landing]
            [wagoe.user.shell.interceptors :as user-interceptors]
            [wagoe.user.shell.service :as user-svc]
            [wagoe.user.shell.middleware :as user-middleware]
            [wagoe.user.shell.web-handlers :as web-handlers]
            [wagoe.user.shell.group-handlers :as group-handlers]
            [wagoe.user.shell.mfa :as mfa]
            [wagoe.platform.ports.privacy :as privacy]
            [wagoe.user.core.audit :as audit]
            [wagoe.user.ports :as user-ports]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]))

;; =============================================================================
;; User-Specific Error Mappings
;; =============================================================================

(def user-error-mappings
  "User module specific error type -> HTTP status mappings."
  {:user-exists               [409 "User Already Exists"]
   :user-not-found            [404 "User Not Found"]
   :session-not-found         [404 "Session Not Found"]
   :deletion-not-allowed      [403 "Deletion Not Allowed"]
   :hard-deletion-not-allowed [403 "Hard Deletion Not Allowed"]})

;; =============================================================================
;; Error Reporting Helpers
;; =============================================================================

;; =============================================================================
;; Observability Service Extraction Helpers
;; =============================================================================

(defn create-interceptor-context
  "Creates interceptor context with real observability services and error mappings."
  [operation-type user-service request]
  (-> (interceptor-context/create-http-context
       operation-type
       (user-svc/interceptor-system user-service)
       request)
      (assoc :error-mappings user-error-mappings)))

;; =============================================================================
;; User Module Routes
;; =============================================================================

(defn create-user-handler
  "Create a new user using interceptor pipeline.

   This version demonstrates the interceptor-based approach that eliminates
   manual observability boilerplate while providing comprehensive tracking."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :user-create user-service request)

          ;; Create the interceptor pipeline for user creation
          pipeline       (user-interceptors/create-user-creation-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn get-user-handler
  "GET /api/users/:id - Get user by ID using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :user-get user-service request)

          ;; Create the interceptor pipeline for user get
          pipeline       (user-interceptors/create-user-get-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn list-users-handler
  "GET /api/users - List users using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :user-list user-service request)

           ;; Create the interceptor pipeline for user list
          pipeline       (user-interceptors/create-user-list-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn update-user-handler
  "PUT /api/users/:id - Update user using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :user-update user-service request)

          ;; Create the interceptor pipeline for user update
          pipeline       (user-interceptors/create-user-update-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn delete-user-handler
  "DELETE /api/users/:id - Delete user using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :user-delete user-service request)

          ;; Create the interceptor pipeline for user delete
          pipeline       (user-interceptors/create-user-delete-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

;; =============================================================================
;; User Module Routes
;; =============================================================================

(defn create-session-handler
  "POST /api/sessions - Create session using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :session-create user-service request)

          ;; Create the interceptor pipeline for session creation
          pipeline       (user-interceptors/create-session-creation-pipeline)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn login-handler
  "POST /auth/login - Authenticate user with email/password using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :user-login user-service request)

          ;; Create the interceptor pipeline for user authentication
          pipeline       (user-interceptors/create-session-creation-pipeline)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn validate-session-handler
  "GET /api/sessions/:token - Validate session using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :session-validate user-service request)

          ;; Create the interceptor pipeline for session validation
          pipeline       (user-interceptors/create-session-validation-pipeline)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn invalidate-session-handler
  "DELETE /api/sessions/:token - Invalidate session using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :session-invalidate user-service request)

          ;; Create the interceptor pipeline for session invalidation
          pipeline       (user-interceptors/create-session-invalidation-pipeline)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

;; =============================================================================
;; MFA Handlers
;; =============================================================================

(def ^:private mfa-failures
  "The types mfa.clj answers when something threw: our fault, not the caller's."
  #{:mfa-setup-failed :mfa-enable-failed :mfa-disable-failed})

(defn- mfa-refusal
  "A validation-error 400 for an MFA refusal the caller can act on, its MFA
   type as the reason; a 500 when mfa.clj caught an exception, which it logged
   (BOU-586)."
  [result]
  (let [{:keys [type message]} (:error result)
        body (if (mfa-failures type)
               (errors/body :internal-error "Internal Server Error")
               (errors/body :validation-error message {:details {:reason (errors/type-name type)}}))]
    {:status  (if (mfa-failures type) 500 400)
     :headers {"Content-Type" "application/json"}
     :body    (json/generate-string body)}))

;; Untyped exceptions are not caught here: the platform's http-error-handler
;; logs them and answers a generic 500 (BOU-557).

(defn mfa-setup-handler
  "POST /api/auth/mfa/setup - Initiate MFA setup for authenticated user."
  [mfa-service]
  (fn [request]
    (let [user-id (get-in request [:user :id])
          _ (when-not user-id
              (throw (ex-info "User not authenticated" {:type :unauthorized})))
          result (mfa/setup-mfa mfa-service user-id)]
      (if (:success? result)
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string
                {:secret (:secret result)
                 :qrCodeUrl (:qr-code-url result)
                 :backupCodes (:backup-codes result)
                 :issuer (:issuer result)
                 :accountName (:account-name result)})}
        (mfa-refusal result)))))

(defn mfa-enable-handler
  "POST /api/auth/mfa/enable - Enable MFA after verification."
  [mfa-service]
  (fn [request]
    (let [user-id (get-in request [:user :id])
          _ (when-not user-id
              (throw (ex-info "User not authenticated" {:type :unauthorized})))
          body (get request :body-params)
          secret (get body :secret)
          backup-codes (get body :backupCodes)
          verification-code (get body :verificationCode)
          result (mfa/enable-mfa mfa-service user-id secret backup-codes verification-code)]
      (if (:success? result)
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:message "MFA enabled successfully"})}
        (mfa-refusal result)))))

(defn mfa-disable-handler
  "POST /api/auth/mfa/disable - Disable MFA for authenticated user."
  [mfa-service]
  (fn [request]
    (let [user-id (get-in request [:user :id])
          _ (when-not user-id
              (throw (ex-info "User not authenticated" {:type :unauthorized})))
          result (mfa/disable-mfa mfa-service user-id)]
      (if (:success? result)
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:message "MFA disabled successfully"})}
        (mfa-refusal result)))))

(defn mfa-status-handler
  "GET /api/auth/mfa/status - Get MFA status for authenticated user."
  [mfa-service]
  (fn [request]
    (let [user-id (get-in request [:user :id])
          _ (when-not user-id
              (throw (ex-info "User not authenticated" {:type :unauthorized})))
          status (mfa/get-mfa-status mfa-service user-id)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
              {:enabled (:enabled status)
               :enabledAt (:enabled-at status)
               :backupCodesRemaining (:backup-codes-remaining status)})})))

;; =============================================================================
;; User Module Routes
;; =============================================================================

(def ^:private credentials-body
  "Body of POST /auth/login and POST /sessions. Coercion strips undeclared
   keys, so `mfaCode` must be listed or an MFA user can never finish signing in."
  [:map {:closed true}
   [:email :string]
   [:password :string]
   [:mfaCode {:optional true} :string]
   [:deviceInfo {:optional true} [:map
                                  [:userAgent {:optional true} :string]
                                  [:ipAddress {:optional true} :string]]]])

(def ^:private login-responses
  "What POST /auth/login and POST /sessions answer (BOU-691)."
  {200 {:description "Signed in: sessionToken, jwtToken, user and session. In user and session, read the camelCase keys: their kebab-case twins are deprecated. With requiresMfa true instead, the password was right; repeat the request with mfaCode."}
   400 {:description "Malformed body."}
   401 {:description "Wrong email or password (type unauthorized, the same body whether or not the account exists), or a wrong mfaCode (type mfa-verification-failed)."}
   403 {:description "MFA is required but the user has not enrolled (type mfa-enrolment-required)."}
   429 {:description "Account locked after repeated failures (type account-locked). Retry-After gives the seconds to wait."}})

(defn api-routes
  "The module's API routes, as Reitit data.

   Paths are relative: the platform mounts them under /api/v1."
  [user-service mfa-service]
  (let [auth-middleware (user-middleware/flexible-authentication-middleware user-service)]
    [["/users"
      {:authorization :self
       :middleware [auth-middleware]
       :post {:handler      (create-user-handler user-service)
              :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                             'wagoe.user.shell.http-interceptors/require-admin
                             'wagoe.user.shell.http-interceptors/log-action]
              :summary      "Create user"
              :tags         ["users"]
              :parameters   {:body [:map {:closed true}
                                    [:email :string]
                                    [:name :string]
                                    [:password {:optional true} :string]
                                    [:role [:enum "admin" "user"]]
                                    [:active {:optional true} :boolean]
                                    ;; Preferences (flattened into user entity)
                                    [:notificationsEmail {:optional true} :boolean]
                                    [:notificationsPush {:optional true} :boolean]
                                    [:notificationsSms {:optional true} :boolean]
                                    [:theme {:optional true} [:enum "light" "dark" "auto"]]
                                    [:language {:optional true} :string]
                                    [:timezone {:optional true} :string]
                                    [:dateFormat {:optional true} [:enum "iso" "us" "eu"]]
                                    [:timeFormat {:optional true} [:enum "12h" "24h"]]]}}
       :get  {:handler    (list-users-handler user-service)
              ;; Listing all users is an admin operation (consistent with
              ;; POST create); non-admins must not enumerate the directory.
              :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                             'wagoe.user.shell.http-interceptors/require-admin]
              :summary    "List users with pagination and filters"
              :tags       ["users"]
              :parameters {:query [:map
                                   [:limit {:optional true} :int]
                                   [:offset {:optional true} :int]
                                   [:role {:optional true} [:enum "admin" "user"]]
                                   [:active {:optional true} :boolean]]}}}]
     ["/users/:id"
      {:authorization :self
       :middleware [auth-middleware]
       :get    {:handler    (get-user-handler user-service)
                ;; Read own record or admin — closes the cross-user IDOR
                ;; (a non-admin cannot fetch another user's record by :id).
                :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                               'wagoe.user.shell.http-interceptors/require-self-or-admin]
                :summary    "Get user by ID"
                :tags       ["users"]
                :parameters {:path [:map [:id :string]]}}
       :put    {:handler    (update-user-handler user-service)
                ;; Admin-only: the update body includes :role, so a
                ;; self-or-admin guard would allow privilege escalation
                ;; via self-edit. Self-service profile edits go through
                ;; the /profile web routes, which do not accept :role.
                :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                               'wagoe.user.shell.http-interceptors/require-admin]
                :summary    "Update user"
                :tags       ["users"]
                :parameters {:path [:map [:id :string]]
                             :body [:map {:closed true}
                                    [:name {:optional true} :string]
                                    [:email {:optional true} :string]
                                    [:role {:optional true} [:enum "admin" "user"]]
                                    [:active {:optional true} :boolean]
                                    ;; Preferences (flattened into user entity)
                                    [:notificationsEmail {:optional true} :boolean]
                                    [:notificationsPush {:optional true} :boolean]
                                    [:notificationsSms {:optional true} :boolean]
                                    [:theme {:optional true} [:enum "light" "dark" "auto"]]
                                    [:language {:optional true} :string]
                                    [:timezone {:optional true} :string]
                                    [:dateFormat {:optional true} [:enum "iso" "us" "eu"]]
                                    [:timeFormat {:optional true} [:enum "12h" "24h"]]]}}
       :delete {:handler    (delete-user-handler user-service)
                ;; Admin-only: deleting a user is an administrative action.
                :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                               'wagoe.user.shell.http-interceptors/require-admin]
                :summary    "Soft delete user"
                :tags       ["users"]
                :parameters {:path [:map [:id :string]]}}}]
     ;; Public: signing in is how a caller becomes authenticated (BOU-568).
     ["/auth/login"
      {:public true
       :post {:handler    (login-handler user-service)
              :summary    "Authenticate user with email/password"
              :tags       ["authentication"]
              :parameters {:body credentials-body}
              :responses  login-responses}}]
     ["/sessions"
      {:public true
       :post {:handler    (create-session-handler user-service)
              :summary    "Create session with email/password"
              :tags       ["sessions"]
              :parameters {:body credentials-body}
              :responses  login-responses}}]
     ;; The token in the path is the credential.
     ["/sessions/:token"
      {:public true
       :get    {:handler    (validate-session-handler user-service)
                :summary    "Validate session"
                :tags       ["sessions"]
                :parameters {:path [:map [:token :string]]}}
       :delete {:handler    (invalidate-session-handler user-service)
                :summary    "Invalidate session (logout)"
                :tags       ["sessions"]
                :parameters {:path [:map [:token :string]]}}}]
     ["/auth/mfa/setup"
      {:authorization :signed-in
       :middleware [(user-middleware/flexible-authentication-middleware user-service)]
       :post {:handler    (mfa-setup-handler mfa-service)
              :summary    "Initiate MFA setup"
              :tags       ["mfa"]
              :description "Returns TOTP secret, QR code URL, and backup codes for MFA setup"}}]
     ["/auth/mfa/enable"
      {:authorization :signed-in
       :middleware [(user-middleware/flexible-authentication-middleware user-service)]
       :post {:handler    (mfa-enable-handler mfa-service)
              :summary    "Enable MFA after verification"
              :tags       ["mfa"]
              :parameters {:body [:map {:closed true}
                                  [:secret :string]
                                  [:backupCodes [:vector :string]]
                                  [:verificationCode :string]]}}}]
     ["/auth/mfa/disable"
      {:authorization :signed-in
       :middleware [(user-middleware/flexible-authentication-middleware user-service)]
       :post {:handler    (mfa-disable-handler mfa-service)
              :summary    "Disable MFA"
              :tags       ["mfa"]}}]
     ["/auth/mfa/status"
      {:authorization :signed-in
       :middleware [(user-middleware/flexible-authentication-middleware user-service)]
       :get {:handler    (mfa-status-handler mfa-service)
             :summary    "Get MFA status"
             :tags       ["mfa"]
             :description "Returns whether MFA is enabled and remaining backup codes"}}]]))

(defn web-routes
  "The module's web UI routes, as Reitit data.

   Without the /web prefix — the platform adds it when mounting."
  [user-service mfa-service config]
  (let [auth-middleware (user-middleware/flexible-authentication-middleware user-service)
        email-sender    (:email-sender config)]
    ;; filterv: the dashboard route is nil under `:dashboard false`, and
    ;; registration under `:registration false`.
    (filterv some?
     [;; Public: the landing page, registration and sign-in come before a
      ;; session exists (BOU-568).
      [""
       {:no-doc true
        :public true
        :get {:handler (web-handlers/web-root-page-handler user-service config)
              :summary "Web root landing page"}}]
      (when (landing/registration? config)
        ["/register"
         {:no-doc true
          :public true
          :get  {:handler (web-handlers/register-page-handler config)
                 :summary "Self-service registration page"}
          :post {:handler (web-handlers/register-submit-handler user-service config)
                 :summary "Submit registration form"}}])
      ["/login"
       {:no-doc true
        :public true
        :get  {:handler (web-handlers/login-page-handler config)
               :summary "Login page"}
        :post {:handler (web-handlers/login-submit-handler user-service config)
               :summary "Submit login form"}}]
      ["/logout"
       {:no-doc true
        :authorization :signed-in
        :post {:middleware [auth-middleware]
               :handler    (web-handlers/logout-handler user-service config)
               :summary    "Logout current user"}}]
      (when-let [handler (landing/dashboard-handler
                          config #(web-handlers/dashboard-page-handler user-service mfa-service config))]
        ["/dashboard"
         {:no-doc     true
          :authorization :signed-in
          :middleware [auth-middleware]
          :get {:handler handler
                :summary "User dashboard page"}}])
      ;; User creation routes (admin-only). The admin panel delegates to this flow
      ;; for split-table entities like :users where the generic admin create cannot
      ;; write both auth_users and users atomically.
      ["/users/new"
       {:no-doc     true
        :authorization :self
        :middleware [auth-middleware user-middleware/require-admin-middleware]
        :get {:handler (web-handlers/create-user-page-handler config)
              :summary "Create user page"}}]
      ["/users"
       {:no-doc     true
        :authorization :self
        :middleware [auth-middleware user-middleware/require-admin-middleware]
        :get  {:handler (web-handlers/users-page-handler user-service config)
               :summary "Users management list page (admin)"}
        :post {:handler (web-handlers/create-user-htmx-handler user-service email-sender config)
               :summary "Create user (HTMX fragment)"}}]
      ["/users/table"
       {:no-doc     true
        :authorization :self
        :middleware [auth-middleware]
        :get {:handler (web-handlers/users-table-fragment-handler user-service config)
              :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                             'wagoe.user.shell.http-interceptors/require-admin]
              :summary "Users table fragment (HTMX refresh)"}}]
      ["/users/bulk"
       {:no-doc     true
        :authorization :self
        :middleware [auth-middleware]
        :post {:handler (web-handlers/bulk-update-users-htmx-handler user-service config)
               :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                              'wagoe.user.shell.http-interceptors/require-admin]
               :summary "Bulk user operations (HTMX)"}}]
      ["/users/:id"
       {:no-doc     true
        :authorization :self
        :middleware [auth-middleware]
        :get    {:handler (web-handlers/user-detail-page-handler user-service config)
                 ;; Admin-only: this is the user-MANAGEMENT detail page
                 ;; (edit/deactivate/delete controls). Self-service lives at
                 ;; /web/profile, so a non-admin has no reason to load it —
                 ;; not even for their own id.
                 :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                                'wagoe.user.shell.http-interceptors/require-admin]
                 :summary "User detail page (admin)"}
        :put    {:handler (web-handlers/update-user-htmx-handler user-service config)
                 ;; Admin-only: updates include role — self-or-admin would
                 ;; allow privilege escalation via self-edit.
                 :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                                'wagoe.user.shell.http-interceptors/require-admin]
                 :summary "Update user (HTMX)"}
        :delete {:handler (web-handlers/delete-user-htmx-handler user-service config)
                 :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                                'wagoe.user.shell.http-interceptors/require-admin]
                 :summary "Delete user (HTMX)"}}]
      ["/users/:id/hard-delete"
       {:no-doc     true
        :authorization :self
        :middleware [auth-middleware]
        :post {:handler (web-handlers/hard-delete-user-handler user-service config)
               :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                              'wagoe.user.shell.http-interceptors/require-admin]
               :summary "Permanently delete user (admin)"}}]
       ;; Session management routes (user-specific, not duplicated in admin)
      ["/users/:id/sessions"
       {:no-doc     true
        :authorization :self
        :middleware [auth-middleware]
        :get {:handler (web-handlers/user-sessions-page-handler user-service config)
              ;; The handler reads the :id path param, so a non-admin must
              ;; not view another user's sessions (IDOR) — own or admin only.
              :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                             'wagoe.user.shell.http-interceptors/require-self-or-admin]
              :summary "User sessions management page"}}]
      ["/users/:id/sessions/revoke-all"
       {:no-doc     true
        :authorization :self
        :middleware [auth-middleware]
        :post {:handler (web-handlers/revoke-all-sessions-handler user-service config)
               ;; Revoking by :id path param — own or admin only, else a
               ;; non-admin could force-logout any user (IDOR / DoS).
               :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                              'wagoe.user.shell.http-interceptors/require-self-or-admin]
               :summary "Revoke all user sessions"}}]
      ["/users/:id/sessions/:session-id/revoke"
       {:no-doc     true
        :authorization :self
        :middleware [auth-middleware]
        :post {:handler (web-handlers/revoke-session-handler user-service config)
               :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                              'wagoe.user.shell.http-interceptors/require-self-or-admin]
               :summary "Revoke specific session"}}]
      ;; Audit trail routes
      ["/audit"
       {:no-doc     true
        :authorization :self
        :middleware [auth-middleware]
        :get {:handler (web-handlers/audit-page-handler user-service config)
              :interceptors ['wagoe.user.shell.http-interceptors/require-platform-admin]
              :summary "Audit trail page"}}]
      ["/audit/table"
       {:no-doc     true
        :authorization :self
        :middleware [auth-middleware]
        :get {:handler (web-handlers/audit-table-fragment-handler user-service config)
              :interceptors ['wagoe.user.shell.http-interceptors/require-platform-admin]
              :summary "Audit table fragment (HTMX refresh)"}}]
       ;; Profile routes
      ["/profile"
       {:no-doc     true
        :authorization :signed-in
        :middleware [auth-middleware]
        :get  {:handler (web-handlers/profile-page-handler user-service mfa-service config)
               :summary "User profile page"}
        :post {:handler (web-handlers/profile-edit-handler user-service config)
               :summary "Update profile (HTMX fragment)"}}]
      ["/profile/edit"
       {:no-doc     true
        :authorization :signed-in
        :middleware [auth-middleware]
        :get {:handler (web-handlers/profile-edit-form-handler user-service config)
              :summary "Show profile edit form (HTMX fragment)"}}]
      ["/profile/info"
       {:no-doc     true
        :authorization :signed-in
        :middleware [auth-middleware]
        :get {:handler (web-handlers/profile-info-fragment-handler user-service config)
              :summary "Show profile info card (HTMX fragment)"}}]
      ["/profile/preferences/edit"
       {:no-doc     true
        :authorization :signed-in
        :middleware [auth-middleware]
        :get {:handler (web-handlers/preferences-edit-form-handler user-service config)
              :summary "Show preferences edit form (HTMX fragment)"}}]
      ["/profile/preferences"
       {:no-doc     true
        :authorization :signed-in
        :middleware [auth-middleware]
        :get  {:handler (web-handlers/preferences-fragment-handler user-service config)
               :summary "Show preferences card (HTMX fragment)"}
        :post {:handler (web-handlers/preferences-edit-handler user-service config)
               :summary "Update preferences (HTMX fragment)"}}]
      ["/profile/password/form"
       {:no-doc     true
        :authorization :signed-in
        :middleware [auth-middleware]
        :get {:handler (web-handlers/password-change-form-handler config)
              :summary "Show password change form (HTMX fragment)"}}]
      ["/profile/password"
       {:no-doc     true
        :authorization :signed-in
        :middleware [auth-middleware]
        :get  {:handler (web-handlers/password-section-fragment-handler config)
               :summary "Show collapsed password section (HTMX fragment)"}
        :post {:handler (web-handlers/password-change-handler user-service config)
               :summary "Change password (HTMX fragment)"}}]
      ;; MFA routes
      ["/profile/mfa/setup"
       {:no-doc     true
        :authorization :signed-in
        :middleware [auth-middleware]
        :get  {:handler (web-handlers/mfa-setup-page-handler mfa-service config)
               :summary "MFA setup page"}
        :post {:handler (web-handlers/mfa-setup-initiate-handler mfa-service config)
               :summary "Initiate MFA setup (HTMX fragment)"}}]
      ["/profile/mfa/verify"
       {:no-doc     true
        :authorization :signed-in
        :middleware [auth-middleware]
        :post {:handler (web-handlers/mfa-verify-handler mfa-service config)
               :summary "Verify MFA code (HTMX fragment)"}}]
      ["/profile/mfa/backup-codes"
       {:no-doc     true
        :authorization :signed-in
        :middleware [auth-middleware]
        :get {:handler (web-handlers/mfa-backup-codes-page-handler mfa-service config)
              :summary "View MFA backup codes"}}]
      ["/profile/mfa/disable"
       {:no-doc     true
        :authorization :signed-in
        :middleware [auth-middleware]
        :get  {:handler (web-handlers/mfa-disable-page-handler mfa-service config)
               :summary "MFA disable confirmation page"}
        :post {:handler (web-handlers/mfa-disable-handler mfa-service config)
               :summary "Disable MFA"}}]])))

(defn oidc-routes
  "OpenID Connect sign-in, mounted under /web. Public: the provider's redirect
   arrives before a session exists, and the sealed cookie is what ties each
   step to the one before."
  [oidc-client config]
  {:api    []
   :web    [["/auth/oidc/mfa"
             {:no-doc true
              :public true
              :post   {:handler (web-handlers/oidc-mfa-handler oidc-client config)
                       :summary "Second factor after an OIDC sign-in"}}]
            ["/auth/oidc/:provider/start"
             {:no-doc true
              :public true
              :get    {:handler (web-handlers/oidc-start-handler oidc-client config)
                       :summary "Start an OIDC sign-in"}}]
            ["/auth/oidc/:provider/callback"
             {:no-doc true
              :public true
              :get    {:handler (web-handlers/oidc-callback-handler oidc-client config)
                       :summary "OIDC redirect back from the provider"}}]]
   :static []})

(defn web-ui-enabled?
  "False when `:wagoe/settings :features :user-web-ui :enabled?` is false:
   an API-only deployment, where this module contributes no HTML pages."
  [config]
  (get-in config [:active :wagoe/settings :features :user-web-ui :enabled?] true))

(def nav
  "This module's sidebar group: the admin's users and its own admin pages."
  {:user {:label    [:t :user/nav-title]
          :icon     :users
          :entities [:users]
          :items    [{:href "/web/admin/groups" :icon :shield :access :admin
                      :label [:t :admin/groups-title] :description [:t :admin/groups-description]}
                     {:href "/web/audit" :icon :file-text :access :platform-admin
                      :label [:t :admin/audit-trail-title] :description [:t :admin/audit-trail-description]}]}})

(defn user-routes
  "This module's contribution to the application's route table.

   :api    versioned, mounted under /api/v1
   :web    mounted under /web, and absent when the web UI is switched off
   :static `/` when `:landing` is set; user's assets are served at handler level
   :nav    its sidebar group, with the web UI"
  [user-service mfa-service config]
  (let [web? (web-ui-enabled? config)]
    {:api    (api-routes user-service mfa-service)
     :web    (when web? (web-routes user-service mfa-service config))
     :static (landing/root-routes config)
     :nav    (when web? nav)}))

(defn group-routes
  "Group management, mounted under /web/admin beside the admin UI: admins only.
   Absent when the web UI is switched off."
  [user-service group-service config]
  (let [guard [(user-middleware/flexible-authentication-middleware user-service)
               user-middleware/require-admin-middleware]
        route (fn [path methods] [path (assoc methods :no-doc true :authorization :self :middleware guard)])]
    {:api        []
     :static     []
     :web-prefix "/web/admin"
     :web        (when (web-ui-enabled? config)
                   [["/groups"
                    (route ""
                           {:get  {:handler (group-handlers/list-page group-service)}
                            :post {:handler (group-handlers/create group-service)}})
                    (route "/:id"
                           {:get  {:handler (group-handlers/show group-service)}
                            :post {:handler (group-handlers/update-group group-service)}})
                    (route "/:id/delete"
                           {:post {:handler (group-handlers/delete-group group-service)}})
                    (route "/:id/members"
                           {:post {:handler (group-handlers/add-member user-service group-service)}})
                    (route "/:id/members/options"
                           {:get {:handler (group-handlers/member-options user-service group-service)}})
                    (route "/:id/members/:user-id/remove"
                           {:post {:handler (group-handlers/remove-member group-service)}})
                    (route "/:id/roles"
                           {:post {:handler (group-handlers/add-role group-service)}})
                    (route "/:id/roles/:assignment-id/remove"
                           {:post {:handler (group-handlers/remove-role group-service)}})]])}))

(defn- export-response
  "`doc`, a user's data export, as a download, logged with who asked."
  [data-export doc request]
  (log/info "Data export" {:subject-user-id (get-in doc [:subject :id]) :actor-id (get-in request [:user :id])})
  (privacy/export-response data-export doc request))

(defn export-own-data-handler
  "The signed-in user's own data export (GDPR Art. 20)."
  [data-export]
  (fn [request]
    (let [user-id (get-in request [:user :id])]
      (when-not user-id
        (throw (ex-info "User not authenticated" {:type :unauthorized})))
      (export-response data-export (privacy/export-subject data-export {:type :user :id user-id}) request))))

(defn- path-user-id [request]
  (or (parse-uuid (str (get-in request [:path-params :id])))
      (throw (ex-info "User id must be a UUID" {:type :validation-error}))))

(defn- audit-export!
  "Record `request`'s export of `profile`. Before the export is served or
   queued, and not caught: an export nobody can trace is not made."
  [audit-repository request profile]
  (user-ports/create-audit-log audit-repository
                               (audit/data-export-audit-entry (user-svc/request-audit-context request) profile)))

(defn- user-not-found [user-id]
  (ex-info "User not found" {:type :not-found :user-id user-id}))

(defn export-user-data-handler
  "An admin's export of any user's data, for a request made another way.
   Recorded in the user's audit trail."
  [data-export audit-repository]
  (fn [request]
    (let [user-id (path-user-id request)
          doc     (privacy/export-subject data-export {:type :user :id user-id})
          ;; Other modules' exporters may answer for any id; ours names the account.
          profile (or (get-in doc [:sections :user :profile]) (throw (user-not-found user-id)))]
      (audit-export! audit-repository request profile)
      (export-response data-export doc request))))

(defn request-user-export-job-handler
  "An admin's export of any user's data as a background job, downloaded from
   the job's status endpoint. Recorded in the user's audit trail when asked
   for: that is where the actor and their request are."
  [user-service export-jobs audit-repository]
  (fn [request]
    (let [user-id (path-user-id request)
          profile (or (user-ports/get-user-by-id user-service user-id) (throw (user-not-found user-id)))]
      (audit-export! audit-repository request profile)
      (privacy/request-export-job! export-jobs {:type :user :id user-id} (get-in request [:user :id])))))

(defn- require-platform-admin-middleware
  "An admin outside any tenant only: the export carries the user's audit trail,
   which only a platform admin reads (/web/audit)."
  [handler]
  (fn [request]
    (if (access/platform-admin? (:user request))
      (handler request)
      (user-middleware/create-forbidden-response "Platform admin required" :insufficient-role request))))

(defn export-routes
  "Data export: a user's own at GET /api/v1/me/export and /web/profile/export,
   anyone's for an admin at GET /api/v1/users/:id/export, and with
   `:export-jobs` (privacy/IExportJobs) as a background job at
   POST /api/v1/users/:id/export-jobs."
  [{:keys [user-service data-export audit-repository export-jobs config]}]
  (let [auth  (user-middleware/flexible-authentication-middleware user-service)
        admin [auth require-platform-admin-middleware]
        own   {:authorization :signed-in
               :middleware [auth]
               :get        {:handler (export-own-data-handler data-export)
                            :summary "Download your data"}}]
    {:api    (cond-> [["/me/export" (assoc-in own [:get :tags] ["users"])]
                      ["/users/:id/export"
                       {:authorization :self
                        :middleware admin
                        :get        {:handler    (export-user-data-handler data-export audit-repository)
                                     :summary    "Download a user's data (admin)"
                                     :tags       ["users"]
                                     :parameters {:path [:map [:id :string]]}}}]]
               export-jobs
               (conj ["/users/:id/export-jobs"
                      {:authorization :self
                       :middleware admin
                       :post       {:handler    (request-user-export-job-handler user-service export-jobs audit-repository)
                                    :summary    "Ask for a user's data as a background job (admin)"
                                    :tags       ["users"]
                                    :parameters {:path [:map [:id :string]]}}}]))
     :web    (when (web-ui-enabled? config)
               [["/profile/export" (assoc own :no-doc true)]])
     :static []}))
