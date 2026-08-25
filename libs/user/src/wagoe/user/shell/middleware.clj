(ns wagoe.user.shell.middleware
  "Authentication and authorization middleware for HTTP endpoints.
   
   This middleware provides:
   - JWT token validation
   - Session-based authentication
   - Role-based authorization
   - Request context enrichment with user information"
  (:require [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth-shell]
            [wagoe.core.utils.type-conversion :as type-conv]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn extract-bearer-token
  "Extracts Bearer token from Authorization header.
   
   Args:
     request: HTTP request map
     
   Returns:
     String token or nil if not found"
  [request]
  (when-let [auth-header (get-in request [:headers "authorization"])]
    (when (str/starts-with? auth-header "Bearer ")
      (str/trim (subs auth-header 7)))))

(defn- decode-token
  "Decode percent-encoded session tokens from cookies/headers.

   Some HTTP clients send cookie values back URL-encoded (for example `%2F`
   and `%3D`). Session lookup must use the raw token persisted in the database,
   so decode percent escapes when present. Do not treat raw `+` as a space,
   because session tokens are ordinary Base64 strings and may legitimately
   contain `+`."
  [token]
  (when token
    (if (str/includes? token "%")
      (try
        (java.net.URLDecoder/decode (str/replace token "+" "%2B") "UTF-8")
        (catch IllegalArgumentException _
          token))
      token)))

(defn extract-session-token
  "Extracts session token from request headers or cookies.
   
   Looks for token in:
   1. X-Session-Token header
   2. session-token cookie
   
   Args:
     request: HTTP request map
     
   Returns:
     String token or nil if not found"
  [request]
  (or
    ;; Check X-Session-Token header
   (some-> (get-in request [:headers "x-session-token"]) decode-token)
    ;; Check session-token cookie
   (some-> (get-in request [:cookies "session-token" :value]) decode-token)))

(defn create-unauthorized-response
  "Creates standardized 401 Unauthorized response.
   
   For web UI requests (path starts with /web), redirects to login page.
   For API requests, returns JSON error response.
   
   Args:
     message: Error message string
     reason: Keyword reason code
     request: Optional request map to determine response type
     
   Returns:
     Ring response map"
  ([message reason]
   (create-unauthorized-response message reason nil))
  ([message reason request]
   (if (and request (str/starts-with? (get request :uri "") "/web"))
     ;; Web UI request - redirect to login with return-to parameter
     (let [return-to (:uri request)
           login-url (str "/web/login?return-to=" (java.net.URLEncoder/encode return-to "UTF-8"))]
       {:status  302
        :headers {"Location" login-url}
        :body    ""})
     ;; API request - return JSON error
     {:status  401
      :headers {"Content-Type" "application/json"}
      :body    {:type   "authentication-required"
                :title  "Authentication Required"
                :status 401
                :detail message
                :reason reason}})))

(defn create-forbidden-response
  "Creates standardized 403 Forbidden response.
   
   For web UI requests (path starts with /web), redirects to login page.
   For API requests, returns JSON error response.
   
   Args:
     message: Error message string
     reason: Keyword reason code
     request: Optional request map to determine response type
     
   Returns:
     Ring response map"
  ([message reason]
   (create-forbidden-response message reason nil))
  ([message reason request]
   (if (and request (str/starts-with? (get request :uri "") "/web"))
     ;; Web UI request - redirect to login with return-to parameter (forbidden = not logged in or insufficient perms)
     (let [return-to (:uri request)
           login-url (str "/web/login?return-to=" (java.net.URLEncoder/encode return-to "UTF-8"))]
       {:status  302
        :headers {"Location" login-url}
        :body    ""})
     ;; API request - return JSON error
     {:status  403
      :headers {"Content-Type" "application/json"}
      :body    {:type   "access-forbidden"
                :title  "Access Forbidden"
                :status 403
                :detail message
                :reason reason}})))

;; =============================================================================
;; JWT Authentication Middleware
;; =============================================================================

(defn jwt-authentication-middleware
  "Middleware that validates JWT tokens from Authorization header.
   
   Extracts Bearer token, validates it, and adds user information to request.
   On validation failure, returns 401 response.
   
   Args:
     handler: Next handler in the chain
     
   Returns:
     Wrapped handler function"
  [handler]
  (fn [request]
    (if-let [token (extract-bearer-token request)]
      (try
        ;; Destructured, not tested for truthiness. `validate-jwt-token`
        ;; answers a map either way — `{:valid? false :error ...}` on failure —
        ;; so `(if jwt-claims ...)` was always true: the rejection branch was
        ;; unreachable and any Authorization header authenticated, including a
        ;; token signed with somebody else's secret. `require-authenticated`
        ;; passed too, because `{:id nil}` is still `some?`.
        ;;
        ;; The claims are inside `:claims`, and the id is `:sub` — a string, as
        ;; `create-jwt-token` writes it — so reading `:user-id` off the wrapper
        ;; produced `{:id nil :email nil :role nil}` even for a good token.
        (let [{:keys [valid? claims]} (auth-shell/validate-jwt-token token)]
          (if valid?
            (let [enriched-request (assoc request
                                          :user {:id    (type-conv/string->uuid (:sub claims))
                                                 :email (:email claims)
                                                 ;; written with `name`, read back as a keyword
                                                 :role  (some-> (:role claims) keyword)}
                                          :auth-type :jwt)]
              (handler enriched-request))
            (create-unauthorized-response "Invalid JWT token" :invalid-jwt request)))
        (catch Exception ex
          (log/warn ex "JWT validation failed")
          (create-unauthorized-response "JWT validation failed" :jwt-validation-error request)))

      ;; No token provided
      (create-unauthorized-response "JWT token required" :missing-jwt request))))

;; =============================================================================
;; Session Authentication Middleware
;; =============================================================================

(defn session-authentication-middleware
  "Middleware that validates session tokens and updates session access time.
   
   Extracts session token, validates it through user service, and adds user
   information to request. On validation failure, returns 401 response.
   
   Args:
     user-service: User service instance for session validation
     handler: Next handler in the chain
     
   Returns:
     Wrapped handler function"
  [user-service handler]
  (fn [request]
    (if-let [session-token (extract-session-token request)]
      (try
        (log/info "Validating session token" {:session-token session-token :uri (:uri request)})
        (if-let [session (ports/validate-session user-service session-token)]
          ;; Session valid - fetch full user and add to request
          (do
            (log/info "Session validation successful" {:user-id (:user-id session)})
            (if-let [user (ports/get-user-by-id user-service (:user-id session))]
              (let [enriched-request (assoc request
                                            :user user  ; Full user object with role, email, etc.
                                            :session session
                                            :auth-type :session)]
                (handler enriched-request))
              ;; User not found (deleted after session was created?)
              (do
                (log/warn "Session valid but user not found" {:user-id (:user-id session)})
                (create-unauthorized-response "User not found" :user-not-found request))))
          ;; Session invalid or expired
          (do
            (log/info "Session invalid or expired" {:session-token session-token})
            (create-unauthorized-response "Invalid or expired session" :invalid-session request)))
        (catch Exception ex
          (log/warn ex "Session validation failed" {:session-token (str (take 8 session-token) "...")})
          (create-unauthorized-response "Session validation failed" :session-validation-error request)))

      ;; No session token provided
      (do
        (log/info "No session token provided" {:uri (:uri request) :cookies (keys (:cookies request))})
        (create-unauthorized-response "Session token required" :missing-session request)))))

;; =============================================================================
;; Flexible Authentication Middleware
;; =============================================================================

(defn flexible-authentication-middleware
  "Middleware that accepts either JWT or session token authentication.
   
   Tries JWT first (from Authorization header), then falls back to session token.
   Adds user information to request on successful authentication.
   
   This is a middleware factory function compatible with Reitit.
   Call with user-service to get a middleware function.
   
   Args:
     user-service: User service instance for session validation
     handler: (optional, for direct wrapping) Handler function
     
   Returns:
     Middleware function that takes handler and returns wrapped handler
     
   Example:
     ;; In Reitit routes:
     {:middleware [[flexible-authentication-middleware user-service]]}"
  ([user-service]
   (log/trace "Creating flexible authentication middleware" {:user-service (type user-service)})
   (fn [handler]
     (log/trace "Wrapping handler with flexible authentication" {:handler (type handler)})
     (flexible-authentication-middleware user-service handler)))
  ([user-service handler]
   (log/trace "Initializing flexible authentication middleware"
              {:user-service (type user-service) :handler (type handler)})
   (fn [request]
     (let [session-token (extract-session-token request)
           bearer-token  (extract-bearer-token request)]
       (log/info "Processing authentication request"
                 {:uri (:uri request)
                  :method (:request-method request)
                  :has-session (boolean session-token)
                  :has-bearer (boolean bearer-token)
                  :cookies (keys (:cookies request))
                  :session-token-value (when session-token (subs session-token 0 (min 8 (count session-token))))})
       (cond
         ;; Try JWT authentication first
         bearer-token
         ((jwt-authentication-middleware handler) request)

         ;; Fall back to session authentication
         session-token
         (do
           (log/info "Attempting session authentication" {:token-preview (subs session-token 0 8)})
           ((session-authentication-middleware user-service handler) request))

         ;; No authentication provided
         :else
         (do
           (log/info "No authentication credentials provided"
                     {:headers-keys (keys (:headers request))
                      :cookie-header (get-in request [:headers "cookie"])})
           (create-unauthorized-response "Authentication required" :no-credentials request)))))))

;; =============================================================================
;; Authorization Middleware
;; =============================================================================

(defn require-role-middleware
  "Middleware that requires user to have specific role(s).
   
   Must be used after authentication middleware that sets :user in request.
   
   Args:
     required-roles: Set of required roles (keywords)
     handler: Next handler in the chain
     
   Returns:
     Wrapped handler function"
  [required-roles handler]
  (fn [request]
    (if-let [user (:user request)]
      (let [user-role (:role user)]
        (if (contains? required-roles user-role)
          ;; User has required role
          (handler request)
          ;; User lacks required role
          (create-forbidden-response
           (format "Role '%s' required. User has role '%s'"
                   (str/join ", " (map name required-roles))
                   (name user-role))
           :insufficient-role
           request)))

      ;; No user in request (authentication middleware not run?)
      (create-unauthorized-response "Authentication required" :missing-user request))))

(defn require-admin-middleware
  "Middleware that requires admin role.
   
   Args:
     handler: Next handler in the chain
     
   Returns:
     Wrapped handler function"
  [handler]
  (require-role-middleware #{:admin} handler))

;; =============================================================================
;; Utility Functions for Route Protection
;; =============================================================================

(defn protect-with-jwt
  "Wraps handler with JWT authentication.
   
   Args:
     handler: Handler to protect
     
   Returns:
     Protected handler"
  [handler]
  (jwt-authentication-middleware handler))

(defn protect-with-session
  "Wraps handler with session authentication.
   
   Args:
     user-service: User service for session validation
     handler: Handler to protect
     
   Returns:
     Protected handler"
  [user-service handler]
  (session-authentication-middleware user-service handler))

(defn protect-with-flexible-auth
  "Wraps handler with flexible authentication (JWT or session).
   
   Args:
     user-service: User service for session validation
     handler: Handler to protect
     
   Returns:
     Protected handler"
  [user-service handler]
  ((flexible-authentication-middleware user-service) handler))

(defn protect-admin-only
  "Wraps handler with admin-only access control.
   
   Args:
     user-service: User service for session validation
     handler: Handler to protect
     
   Returns:
     Protected handler with authentication and admin authorization"
  [user-service handler]
  (-> handler
      require-admin-middleware
      ((flexible-authentication-middleware user-service))))
