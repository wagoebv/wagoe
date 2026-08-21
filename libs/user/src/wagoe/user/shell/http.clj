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
            [wagoe.user.shell.http-interceptors]
            [wagoe.user.shell.interceptors :as user-interceptors]
            [wagoe.user.shell.middleware :as user-middleware]
            [wagoe.user.shell.web-handlers :as web-handlers]
            [wagoe.user.shell.mfa :as mfa]
            [cheshire.core :as json]))

;; =============================================================================
;; User-Specific Error Mappings
;; =============================================================================

(def user-error-mappings
  "User module specific error type mappings for RFC 7807 problem details."
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

(defn extract-observability-services
  "Extracts observability services from user-service for interceptor context.
   
   Note: Since service layer cleanup removed direct observability dependencies,
   the interceptor context will obtain these services from the system wiring."
  [user-service]
  {:user-service user-service})

(defn create-interceptor-context
  "Creates interceptor context with real observability services and error mappings."
  [operation-type user-service request]
  (-> (interceptor-context/create-http-context
       operation-type
       (extract-observability-services user-service)
       request)
      (assoc :error-mappings user-error-mappings)))

;; =============================================================================
;; User Module Routes (Normalized Format)
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
;; User Module Routes (Normalized Format)
;; =============================================================================

(defn create-session-handler
  "POST /api/sessions - Create session using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :session-create user-service request)

          ;; Create the interceptor pipeline for session creation
          pipeline       (user-interceptors/create-session-creation-pipeline :http)

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
          pipeline       (user-interceptors/create-session-creation-pipeline :http)

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
          pipeline       (user-interceptors/create-session-validation-pipeline :http)

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
          pipeline       (user-interceptors/create-session-invalidation-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

;; =============================================================================
;; MFA Handlers
;; =============================================================================

(defn mfa-setup-handler
  "POST /api/auth/mfa/setup - Initiate MFA setup for authenticated user."
  [mfa-service]
  (fn [request]
    (try
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
          {:status 400
           :headers {"Content-Type" "application/json"}
           ;; The message, not the map. mfa moved to the ADR-036 §3 return
           ;; ({:error {:type … :message …}}); flattening here keeps this
           ;; endpoint answering exactly what it answered before (BOU-323).
           :body (json/generate-string {:error (get-in result [:error :message])})}))
      (catch Exception e
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error (.getMessage e)})}))))

(defn mfa-enable-handler
  "POST /api/auth/mfa/enable - Enable MFA after verification."
  [mfa-service]
  (fn [request]
    (try
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
          {:status 400
           :headers {"Content-Type" "application/json"}
           ;; The message, not the map. mfa moved to the ADR-036 §3 return
           ;; ({:error {:type … :message …}}); flattening here keeps this
           ;; endpoint answering exactly what it answered before (BOU-323).
           :body (json/generate-string {:error (get-in result [:error :message])})}))
      (catch Exception e
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error (.getMessage e)})}))))

(defn mfa-disable-handler
  "POST /api/auth/mfa/disable - Disable MFA for authenticated user."
  [mfa-service]
  (fn [request]
    (try
      (let [user-id (get-in request [:user :id])
            _ (when-not user-id
                (throw (ex-info "User not authenticated" {:type :unauthorized})))
            result (mfa/disable-mfa mfa-service user-id)]
        (if (:success? result)
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:message "MFA disabled successfully"})}
          {:status 400
           :headers {"Content-Type" "application/json"}
           ;; The message, not the map. mfa moved to the ADR-036 §3 return
           ;; ({:error {:type … :message …}}); flattening here keeps this
           ;; endpoint answering exactly what it answered before (BOU-323).
           :body (json/generate-string {:error (get-in result [:error :message])})}))
      (catch Exception e
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error (.getMessage e)})}))))

(defn mfa-status-handler
  "GET /api/auth/mfa/status - Get MFA status for authenticated user."
  [mfa-service]
  (fn [request]
    (try
      (let [user-id (get-in request [:user :id])
            _ (when-not user-id
                (throw (ex-info "User not authenticated" {:type :unauthorized})))
            status (mfa/get-mfa-status mfa-service user-id)]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string
                {:enabled (:enabled status)
                 :enabledAt (:enabled-at status)
                 :backupCodesRemaining (:backup-codes-remaining status)})})
      (catch Exception e
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error (.getMessage e)})}))))

;; =============================================================================
;; User Module Routes (Normalized Format)
;; =============================================================================

(defn normalized-api-routes
  "Define API routes in normalized format.
   
   Args:
     user-service: User service instance
     mfa-service: MFA service instance
     
   Returns:
     Vector of normalized route maps"
  [user-service mfa-service]
  (let [auth-middleware (user-middleware/flexible-authentication-middleware user-service)]
    [{:path    "/users"
      :meta    {:middleware [auth-middleware]}
      :methods {:post {:handler      (create-user-handler user-service)
                       :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                                      'wagoe.user.shell.http-interceptors/require-admin
                                      'wagoe.user.shell.http-interceptors/log-action]
                       :summary      "Create user"
                       :tags         ["users"]
                       :parameters   {:body [:map {:closed true}
                                             [:email :string]
                                             [:name :string]
                                             [:password {:optional true} :string]
                                             [:role [:enum "admin" "user" "viewer"]]
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
                                            [:role {:optional true} [:enum "admin" "user" "viewer"]]
                                            [:active {:optional true} :boolean]]}}}}
     {:path    "/users/:id"
      :meta    {:middleware [auth-middleware]}
      :methods {:get    {:handler    (get-user-handler user-service)
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
                                             [:role {:optional true} [:enum "admin" "user" "viewer"]]
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
                         :parameters {:path [:map [:id :string]]}}}}
     {:path    "/auth/login"
      :methods {:post {:handler    (login-handler user-service)
                       :summary    "Authenticate user with email/password"
                       :tags       ["authentication"]
                       :parameters {:body [:map {:closed true}
                                           [:email :string]
                                           [:password :string]
                                           [:deviceInfo {:optional true} [:map
                                                                          [:userAgent {:optional true} :string]
                                                                          [:ipAddress {:optional true} :string]]]]}}}}
     {:path    "/sessions"
      :methods {:post {:handler    (create-session-handler user-service)
                       :summary    "Create session (login by user ID)"
                       :tags       ["sessions"]
                       :parameters {:body [:or
                                           [:map {:closed true}
                                            [:userId :string]
                                            [:deviceInfo {:optional true} [:map
                                                                           [:userAgent {:optional true} :string]
                                                                           [:ipAddress {:optional true} :string]]]]
                                           [:map {:closed true}
                                            [:email :string]
                                            [:password :string]
                                            [:deviceInfo {:optional true} [:map
                                                                           [:userAgent {:optional true} :string]
                                                                           [:ipAddress {:optional true} :string]]]]]}}}}
     {:path    "/sessions/:token"
      :methods {:get    {:handler    (validate-session-handler user-service)
                         :summary    "Validate session"
                         :tags       ["sessions"]
                         :parameters {:path [:map [:token :string]]}}
                :delete {:handler    (invalidate-session-handler user-service)
                         :summary    "Invalidate session (logout)"
                         :tags       ["sessions"]
                         :parameters {:path [:map [:token :string]]}}}}
     {:path    "/auth/mfa/setup"
      :meta    {:middleware [(user-middleware/flexible-authentication-middleware user-service)]}
      :methods {:post {:handler    (mfa-setup-handler mfa-service)
                       :summary    "Initiate MFA setup"
                       :tags       ["mfa"]
                       :description "Returns TOTP secret, QR code URL, and backup codes for MFA setup"}}}
     {:path    "/auth/mfa/enable"
      :meta    {:middleware [(user-middleware/flexible-authentication-middleware user-service)]}
      :methods {:post {:handler    (mfa-enable-handler mfa-service)
                       :summary    "Enable MFA after verification"
                       :tags       ["mfa"]
                       :parameters {:body [:map {:closed true}
                                           [:secret :string]
                                           [:backupCodes [:vector :string]]
                                           [:verificationCode :string]]}}}}
     {:path    "/auth/mfa/disable"
      :meta    {:middleware [(user-middleware/flexible-authentication-middleware user-service)]}
      :methods {:post {:handler    (mfa-disable-handler mfa-service)
                       :summary    "Disable MFA"
                       :tags       ["mfa"]}}}
     {:path    "/auth/mfa/status"
      :meta    {:middleware [(user-middleware/flexible-authentication-middleware user-service)]}
      :methods {:get {:handler    (mfa-status-handler mfa-service)
                      :summary    "Get MFA status"
                      :tags       ["mfa"]
                      :description "Returns whether MFA is enabled and remaining backup codes"}}}]))

(defn normalized-web-routes
  "Define web UI routes in normalized format (WITHOUT /web prefix).

   NOTE: These routes will be mounted under /web by the top-level router.
   Do NOT include /web prefix in paths here.

   Args:
     user-service: User service instance
     mfa-service: MFA service instance
     config: Application configuration map

   Returns:
     Vector of normalized route maps"
  [user-service mfa-service config]
  (let [auth-middleware (user-middleware/flexible-authentication-middleware user-service)
        email-sender    (:email-sender config)]
    [{:path    ""
      :meta    {:no-doc true}
      :methods {:get {:handler (web-handlers/web-root-page-handler user-service config)
                      :summary "Web root landing page"}}}
     {:path    "/register"
      :meta    {:no-doc true}
      :methods {:get  {:handler (web-handlers/register-page-handler config)
                       :summary "Self-service registration page"}
                :post {:handler (web-handlers/register-submit-handler user-service config)
                       :summary "Submit registration form"}}}
     {:path    "/login"
      :meta    {:no-doc true}
      :methods {:get  {:handler (web-handlers/login-page-handler config)
                       :summary "Login page"}
                :post {:handler (web-handlers/login-submit-handler user-service config)
                       :summary "Submit login form"}}}
     {:path    "/logout"
      :meta    {:no-doc true}
      :methods {:post {:middleware [auth-middleware]
                       :handler    (web-handlers/logout-handler user-service config)
                       :summary    "Logout current user"}}}
     {:path    "/dashboard"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/dashboard-page-handler user-service mfa-service config)
                      :summary "User dashboard page"}}}
     ;; User creation routes (admin-only). The admin panel delegates to this flow
     ;; for split-table entities like :users where the generic admin create cannot
     ;; write both auth_users and users atomically.
     {:path    "/users/new"
      :meta    {:no-doc     true
                :middleware [auth-middleware user-middleware/require-admin-middleware]}
      :methods {:get {:handler (web-handlers/create-user-page-handler config)
                      :summary "Create user page"}}}
     {:path    "/users"
      :meta    {:no-doc     true
                :middleware [auth-middleware user-middleware/require-admin-middleware]}
      :methods {:get  {:handler (web-handlers/users-page-handler user-service config)
                       :summary "Users management list page (admin)"}
                :post {:handler (web-handlers/create-user-htmx-handler user-service email-sender config)
                       :summary "Create user (HTMX fragment)"}}}
     {:path    "/users/table"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/users-table-fragment-handler user-service config)
                      :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                                     'wagoe.user.shell.http-interceptors/require-admin]
                      :summary "Users table fragment (HTMX refresh)"}}}
     {:path    "/users/bulk"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:post {:handler (web-handlers/bulk-update-users-htmx-handler user-service config)
                       :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                                      'wagoe.user.shell.http-interceptors/require-admin]
                       :summary "Bulk user operations (HTMX)"}}}
     {:path    "/users/:id"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get    {:handler (web-handlers/user-detail-page-handler user-service config)
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
                         :summary "Deactivate user (HTMX)"}}}
     {:path    "/users/:id/hard-delete"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:post {:handler (web-handlers/hard-delete-user-handler user-service config)
                       :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                                      'wagoe.user.shell.http-interceptors/require-admin]
                       :summary "Permanently delete user (admin)"}}}
      ;; Session management routes (user-specific, not duplicated in admin)
     {:path    "/users/:id/sessions"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/user-sessions-page-handler user-service config)
                      ;; The handler reads the :id path param, so a non-admin must
                      ;; not view another user's sessions (IDOR) — own or admin only.
                      :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                                     'wagoe.user.shell.http-interceptors/require-self-or-admin]
                      :summary "User sessions management page"}}}
     {:path    "/users/:id/sessions/revoke-all"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:post {:handler (web-handlers/revoke-all-sessions-handler user-service config)
                       ;; Revoking by :id path param — own or admin only, else a
                       ;; non-admin could force-logout any user (IDOR / DoS).
                       :interceptors ['wagoe.user.shell.http-interceptors/require-authenticated
                                      'wagoe.user.shell.http-interceptors/require-self-or-admin]
                       :summary "Revoke all user sessions"}}}
     {:path    "/sessions/revoke"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:post {:handler (web-handlers/revoke-session-handler user-service config)
                       :summary "Revoke specific session"}}}
     {:path    "/sessions/:token/revoke"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:post {:handler (web-handlers/revoke-session-handler user-service config)
                       :summary "Revoke specific session"}}}
     ;; Audit trail routes
     {:path    "/audit"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/audit-page-handler user-service config)
                      :interceptors ['wagoe.user.shell.http-interceptors/require-platform-admin]
                      :summary "Audit trail page"}}}
     {:path    "/audit/table"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/audit-table-fragment-handler user-service config)
                      :interceptors ['wagoe.user.shell.http-interceptors/require-platform-admin]
                      :summary "Audit table fragment (HTMX refresh)"}}}
      ;; Profile routes
     {:path    "/profile"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get  {:handler (web-handlers/profile-page-handler user-service mfa-service config)
                       :summary "User profile page"}
                :post {:handler (web-handlers/profile-edit-handler user-service config)
                       :summary "Update profile (HTMX fragment)"}}}
     {:path    "/profile/edit"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/profile-edit-form-handler user-service config)
                      :summary "Show profile edit form (HTMX fragment)"}}}
     {:path    "/profile/info"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/profile-info-fragment-handler user-service config)
                      :summary "Show profile info card (HTMX fragment)"}}}
     {:path    "/profile/preferences/edit"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/preferences-edit-form-handler user-service config)
                      :summary "Show preferences edit form (HTMX fragment)"}}}
     {:path    "/profile/preferences"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get  {:handler (web-handlers/preferences-fragment-handler user-service config)
                       :summary "Show preferences card (HTMX fragment)"}
                :post {:handler (web-handlers/preferences-edit-handler user-service config)
                       :summary "Update preferences (HTMX fragment)"}}}
     {:path    "/profile/password/form"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/password-change-form-handler config)
                      :summary "Show password change form (HTMX fragment)"}}}
     {:path    "/profile/password"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get  {:handler (web-handlers/password-section-fragment-handler config)
                       :summary "Show collapsed password section (HTMX fragment)"}
                :post {:handler (web-handlers/password-change-handler user-service config)
                       :summary "Change password (HTMX fragment)"}}}
     ;; MFA routes
     {:path    "/profile/mfa/setup"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get  {:handler (web-handlers/mfa-setup-page-handler mfa-service config)
                       :summary "MFA setup page"}
                :post {:handler (web-handlers/mfa-setup-initiate-handler mfa-service config)
                       :summary "Initiate MFA setup (HTMX fragment)"}}}
     {:path    "/profile/mfa/verify"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:post {:handler (web-handlers/mfa-verify-handler mfa-service config)
                       :summary "Verify MFA code (HTMX fragment)"}}}
     {:path    "/profile/mfa/backup-codes"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/mfa-backup-codes-page-handler mfa-service config)
                      :summary "View MFA backup codes"}}}
     {:path    "/profile/mfa/disable"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get  {:handler (web-handlers/mfa-disable-page-handler mfa-service config)
                       :summary "MFA disable confirmation page"}
                :post {:handler (web-handlers/mfa-disable-handler user-service mfa-service config)
                       :summary "Disable MFA"}}}]))

(defn user-routes-normalized
  "Define user module routes in normalized format for top-level composition.
   
   DEPRECATED: Use user-routes-normalized instead for new code.
   This function returns routes in Reitit-specific format.

   Returns a map with route categories:
   - :api - REST API routes (will be mounted under /api)
   - :web - Web UI routes (will be mounted under /web)
   - :static - Static asset routes (empty - served at handler level)
   
   Args:
     user-service: User service instance
     mfa-service: MFA service instance
     config: Application configuration map

   Returns:
     Map with keys :api, :web, :static containing normalized route vectors"
  [user-service mfa-service config]
  (let [web-ui-enabled? (get-in config [:active :wagoe/settings :features :user-web-ui :enabled?] true)]
    {:api    (normalized-api-routes user-service mfa-service)
     :web    (when web-ui-enabled? (normalized-web-routes user-service mfa-service config))
     :static []}))
