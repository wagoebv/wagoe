(ns wagoe.user.shell.middleware
  "Authentication and authorization middleware for HTTP endpoints.
   
   This middleware provides:
   - JWT token validation
   - Session-based authentication
   - Role-based authorization
   - Request context enrichment with user information"
  (:require [wagoe.user.ports :as ports]
            [wagoe.user.core.session :as session-core]
            [wagoe.user.core.user :as user-core]
            [wagoe.user.core.view-as :as view-as]
            [wagoe.shared.ui.core.session-timeout :as session-timeout]
            [wagoe.shared.ui.core.view-as :as view-as-ui]
            [wagoe.user.shell.auth :as auth-shell]
            [wagoe.user.shell.service :as user-service]
            [wagoe.core.http.access :as core-access]
            [wagoe.core.utils.redirect :as redirect]
            [wagoe.core.utils.type-conversion :as type-conv]
            [wagoe.platform.core.http.access :as access]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [ring.util.response]
            [ring.websocket :as ring-ws]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(def without-credentials
  "`user` with its secrets removed, ready to put on a request."
  user-core/without-credentials)

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
  "401 for an API request; a /web request is redirected to the login page.
   The shape is platform's, shared with its default-deny guard (BOU-568).
   `_reason` is no longer sent: one 401 shape framework-wide."
  ([message reason] (create-unauthorized-response message reason nil))
  ([message _reason request]
   (access/unauthorized-response message request
                                 (access/correlation-id request (str (random-uuid))))))

(defn create-forbidden-response
  "403 for a signed-in caller who may not: the error body for an API request,
   a page for a /web one. Not the login form, which they are past."
  ([message reason] (create-forbidden-response message reason nil))
  ([message _reason request]
   (access/refusal-response 403 "forbidden" message (access/correlation-id request (str (random-uuid))))))

;; =============================================================================
;; JWT Authentication Middleware
;; =============================================================================

(defn- claims-user
  "The user a token's claims name, as the token says."
  [claims]
  {:id    (type-conv/string->uuid (:sub claims))
   :email (:email claims)
   ;; written with `name`, read back as a keyword
   :role  (some-> (:role claims) keyword)})

(def ^:private refusal-messages
  {:user-not-found "User not found"
   :user-inactive  "Account is deactivated"
   :user-deleted   "User not found"
   :session-ended  "Session has ended"})

(defn- jwt-user
  "`{:user u}` for the user a bearer token authenticates as `(user-of claims)`,
   or `{:refusal response}`, the 401 that refuses it."
  [user-of token request]
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
  ;;
  ;; No catch: a bad token is answered as a map, so what throws here is the
  ;; user lookup, and a database error is a 5xx, not a 401 (BOU-666).
  (let [{:keys [valid? claims]} (auth-shell/validate-jwt-token token)]
    (if valid?
      (let [user (user-of claims)]
        (if (keyword? user)
          {:refusal (create-unauthorized-response (refusal-messages user) user request)}
          {:user user}))
      {:refusal (create-unauthorized-response "Invalid JWT token" :invalid-jwt request)})))

(defn- jwt-middleware
  "Authenticate a bearer token as `(user-of claims)`: a user, or the
   `account-refusal` keyword that makes it a 401. The handler runs outside the
   try, so what it throws reaches the error mapping unchanged (BOU-682)."
  [user-of handler]
  (fn [request]
    (if-let [token (extract-bearer-token request)]
      (let [{:keys [user refusal]} (jwt-user user-of token request)]
        (or refusal (handler (assoc request :user user :auth-type :jwt))))
      (create-unauthorized-response "JWT token required" :missing-jwt request))))

(defonce ^:private claims-only-warned? (atom false))

(defn- warn-claims-only!
  "Once per process: the deprecated arity trusts a token's claims."
  []
  (when (compare-and-set! claims-only-warned? false true)
    (log/warn (str "jwt-authentication-middleware and protect-with-jwt without a user service"
                   " are deprecated: a deactivated user's JWT, or one whose session ended, passes. Pass the user service (BOU-682)."))))

(defonce ^:private no-jwt-sessions-warned? (atom false))

(defn- warn-no-jwt-sessions!
  "Once per process: without IJwtSessions a revoked session's JWT cannot be
   told apart, so every JWT is refused (BOU-1006)."
  []
  (when (compare-and-set! no-jwt-sessions-warned? false true)
    (log/warn "WGE-107: the user service does not implement IJwtSessions; every JWT is refused (BOU-1006)")))

(defn jwt-authentication-middleware
  "Middleware that validates JWT tokens from Authorization header.
   
   Extracts Bearer token, validates it, and adds user information to request.
   On validation failure, returns 401 response.

   The token's user is read and refused when gone or inactive (BOU-668), and
   the token when its session has ended (BOU-1006).

   Deprecated: the 1-arity, without `user-service`, trusts the token's claims,
   so a deactivated user passes until the token expires (BOU-682).
   
   Args:
     user-service: User service the token's user is read from
     handler: Next handler in the chain
     
   Returns:
     Wrapped handler function"
  ([handler]
   (warn-claims-only!)
   (jwt-middleware claims-user handler))
  ([user-service handler]
   ;; Fails closed: the user module refuses to boot without IJwtSessions, and
   ;; a service wired some other way gets no JWT through (WGE-107)
   (let [live? (if (satisfies? ports/IJwtSessions user-service)
                 #(and %2 (ports/jwt-session-live? user-service %1 %2))
                 (do (warn-no-jwt-sessions!) (constantly false)))]
     (jwt-middleware (fn [claims]
                       (let [id   (type-conv/string->uuid (:sub claims))
                             user (ports/get-user-by-id user-service id)]
                         (or (session-core/account-refusal user)
                             ;; The token ends with its session (BOU-1006)
                             (when-not (live? id (:sid claims)) :session-ended)
                             (without-credentials user))))
                     handler))))

;; =============================================================================
;; Session Authentication Middleware
;; =============================================================================

(defn- viewed-as
  "The user `session`, `admin`'s, views the app as now, as they may still be
   viewed as; nil for none (BOU-868). A view past its time, or of one no longer
   to be viewed as, is ended and audited, so it neither blocks a new one nor
   comes back."
  [user-service admin session]
  (when (:view-as-user-id session)
    (let [now    (java.time.Instant/now)
          target (when (view-as/active? session now)
                   (some->> (:view-as-user-id session) (ports/get-user-by-id user-service) without-credentials))]
      (if (and target (nil? (view-as/refusal admin target (dissoc session :view-as-user-id) now)))
        target
        (do (when (satisfies? ports/IViewAs user-service) (ports/stop-view-as user-service admin session))
            nil)))))

(defn- session-user
  "`{:user u :session s}` for the session `token` names, or `{:refusal response}`,
   the 401 that refuses it. An administrator viewing the app as another user is
   that user, with `:viewing-as` {:admin :until} (BOU-868)."
  [user-service token request]
  (if (re-find #"\p{Cntrl}" token)
    ;; No issued token has one, and PostgreSQL fails a query carrying a NUL:
    ;; refused here, or any caller could turn a request into a 5xx.
    {:refusal (create-unauthorized-response "Invalid or expired session" :invalid-session request)}
    (try
      (log/info "Validating session token" {:uri (:uri request)})
      ;; validate-session refuses a gone or inactive user and hands back the
      ;; one it read, so there is no second read here (BOU-668).
      (if-let [{:keys [user] :as session} (ports/validate-session user-service token)]
        (do
          (log/info "Session validation successful" {:user-id (:user-id session)})
          (if user
            (let [admin   (without-credentials user)
                  session (dissoc session :user)]
              (if-let [target (viewed-as user-service admin session)]
                {:user target :session session :viewing-as {:admin admin :until (:view-as-until session)}}
                {:user admin :session session}))
            {:refusal (create-unauthorized-response "User not found" :user-not-found request)}))
        (do
          (log/info "Session invalid or expired" {:uri (:uri request)})
          {:refusal (create-unauthorized-response "Invalid or expired session" :invalid-session request)}))
      ;; Only a refused session is a 401. A failed lookup propagates to the error
      ;; mapping as a 5xx: the caller is not unauthenticated, the store is down
      ;; (BOU-666).
      (catch clojure.lang.ExceptionInfo ex
        (if (= :session-invalid (:type (ex-data ex)))
          (do (log/info "Session refused" {:uri (:uri request) :reason (:reason (ex-data ex))})
              {:refusal (create-unauthorized-response "Invalid or expired session" :invalid-session request)})
          (throw ex))))))

(defn- browser-page?
  "Whether `request` is a browser's for a whole page, which a redirect with a
   message answers better than a bare refusal."
  [request]
  (and (str/starts-with? (str (:uri request)) "/web/") (nil? (get-in request [:headers "hx-request"]))))

(defn- back-path
  "The path and query the Referer names, never another site."
  [request]
  (let [p (some-> (get-in request [:headers "referer"]) (str/replace #"^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*" ""))]
    (if (and p (redirect/local-path? p)) p "/web")))

(defn- read-only-refusal
  "Refuses a request that would change something while viewing the app as
   another user, but for stopping or signing out (BOU-868): a browser's form
   goes back to its page, the bar still there, with why; anything else gets a
   403. Marked, so `authenticate-if-present` answers it rather than passing the
   request on unauthenticated."
  [request]
  (when (and (:viewing-as request)
             ;; A socket's messages could write: none opens while viewing (BOU-1004)
             (or (ring-ws/upgrade-request? request)
                 (and (view-as/writes? (:request-method request))
                      (not (view-as/allowed-writes (:uri request))))))
    (assoc (if (browser-page? request)
             (assoc (ring.util.response/redirect (back-path request) :see-other)
                    :flash {:error :user.view-as/disabled})
             (create-forbidden-response "Read-only while viewing as another user" :view-as-read-only request))
           ::read-only true)))

(defn- with-viewing
  "Call `f` with the page bar bound when `request` views as another user."
  [request f]
  (if (:viewing-as request)
    (binding [view-as-ui/*viewing* {:name      (or (not-empty (get-in request [:user :name])) (get-in request [:user :email]))
                                    :stop-path view-as/stop-path}]
      (f))
    (f)))

(defn- with-page-session
  "Call `f` as `with-viewing` does, with when the session ends bound for the
   page's dialog and sent as headers for a page that swaps only a part
   (BOU-1007)."
  [request f]
  (let [{:keys [expires-at ends-at]} (:session request)]
    (if (and expires-at ends-at)
      (let [timer {:expires-at  (.toEpochMilli ^java.time.Instant expires-at)
                   :ends-at     (.toEpochMilli ^java.time.Instant ends-at)
                   :now         (System/currentTimeMillis)
                   :extend-path session-core/extend-path
                   :reauth-path session-core/reauth-path
                   :login-path  "/web/login"
                   :logout-path "/web/logout"}
            resp  (binding [session-timeout/*session* timer] (with-viewing request f))]
        (cond-> resp
          ;; A handler that opened a new session says so itself
          (map? resp) (update :headers #(merge {"X-Session-Expires" (str (:expires-at timer))
                                                "X-Session-Ends"    (str (:ends-at timer))
                                                "X-Session-Now"     (str (:now timer))}
                                               %))))
      (with-viewing request f))))

(defn session-authentication-middleware
  "Middleware that validates session tokens and updates session access time.
   
   Extracts session token, validates it through user service, and adds user
   information to request. On validation failure, returns 401 response. What
   the handler throws reaches the error mapping, not a 401 (BOU-682).
   
   Args:
     user-service: User service instance for session validation
     handler: Next handler in the chain
     
   Returns:
     Wrapped handler function"
  [user-service handler]
  (fn [request]
    (if-let [session-token (extract-session-token request)]
      (let [{:keys [user session refusal viewing-as]} (session-user user-service session-token request)
            request (cond-> (assoc request :user user :session session :auth-type :session)
                      viewing-as (assoc :viewing-as viewing-as))]
        (or refusal (read-only-refusal request) (with-page-session request #(handler request))))

      ;; No session token provided
      (do
        (log/info "No session token provided" {:uri (:uri request) :cookies (keys (:cookies request))})
        (create-unauthorized-response "Session token required" :missing-session request)))))

;; =============================================================================
;; Pass-through Authentication Middleware
;; =============================================================================

(defn- end-refused-view
  "Where the tenant a request runs in refuses the view (BOU-1004): it ends,
   audited, and a browser is back as the administrator with why; anything else
   gets the refusal."
  [user-service request response]
  (when (satisfies? ports/IViewAs user-service)
    (ports/stop-view-as user-service (get-in request [:viewing-as :admin]) (:session request)))
  (if (browser-page? request)
    (assoc (ring.util.response/redirect "/web" :see-other) :flash {:error :user.view-as/ended-refused})
    response))

(defn authenticate-if-present
  "Middleware factory: sets `:user` when the request carries valid credentials,
   and passes the request through untouched when it does not.

   Unlike `flexible-authentication-middleware`, this never answers 401. That is
   what lets it run globally: it sits outermost in platform's pipeline, ahead of
   the tenant middleware, so `wrap-tenant-membership` — which looks the
   membership up from `[:user :id]` — has a user to work with. Before BOU-373
   nothing set `:user` before it ran, so `:tenant-membership` was nil on every
   request and `require-tenant-member` rejected all of them.

   Rejecting stays with the per-route guards (`require-authenticated`,
   `protect-with-*`). An invalid token is therefore not an error here: a public
   route should not 401 over one, and a protected route still sees no `:user`
   and is refused by its own guard.

   A WebSocket upgrade may carry its JWT as `?token=`, because a browser cannot
   set a header on one. Only an upgrade: anywhere else a token in the URL ends
   up in logs and history, and is ignored (BOU-568).

   Args:
     user-service - validates sessions and reads a JWT's user

   Returns:
     `(fn [handler] ...)`, Reitit- and Ring-compatible."
  [user-service]
  (fn [handler]
    (fn [request]
      (let [;; Reuse the validating middleware rather than reimplementing JWT
            ;; and session handling, but discard their 401. They call their
            ;; handler only on success, so a capturing one tells us whether
            ;; authentication worked and hands back the enriched request; on
            ;; failure it is never called and `captured` stays nil.
            captured (volatile! nil)
            capture  (fn [enriched] (vreset! captured enriched) nil)
            ws-token (when (and (ring-ws/upgrade-request? request)
                                (not (extract-bearer-token request)))
                       (let [t (get-in request [:query-params "token"])]
                         (when (string? t) t)))
            request  (cond-> request
                       ws-token (assoc-in [:headers "authorization"] (str "Bearer " ws-token)))
            answered (cond
                       (extract-bearer-token request)
                       ((jwt-authentication-middleware user-service capture) request)

                       (extract-session-token request)
                       ((session-authentication-middleware user-service capture) request)

                       :else nil)
            request  (or @captured request)]
        ;; A change while viewing as another user is refused, not passed on
        (if (::read-only answered)
          answered
          (let [response (with-page-session request #(handler request))]
            (if (and (:viewing-as request) (core-access/view-refused? response))
              (end-refused-view user-service request response)
              response)))))))

;; =============================================================================
;; Flexible Authentication Middleware
;; =============================================================================

(defn- wrap-audit-context
  "Make the signed-in user the actor of what the user service audits and
   guards during the request (BOU-665)."
  [handler]
  (fn [request]
    (if (:user request)
      (user-service/with-audit-context (user-service/request-audit-context request)
        #(handler request))
      (handler request))))

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
   (let [handler (wrap-audit-context handler)]
     (fn [request]
       (if (:user request)
         ;; Already authenticated upstream. `authenticate-if-present` runs
         ;; globally since BOU-373 and has validated these same credentials, and a
         ;; second pass is not free: `validate-session` reads
         ;; the session, writes its access time, then fetches the user. Repeating
         ;; that doubles the database work on every authenticated request to a
         ;; route carrying this middleware — most of user, admin and workflow.
         ;;
         ;; Only the middleware chain can set `:user`; it is not readable from
         ;; anything the client sends.
         (handler request)

         (let [session-token (extract-session-token request)
               bearer-token  (extract-bearer-token request)]
           (log/info "Processing authentication request"
                     {:uri (:uri request)
                      :method (:request-method request)
                      :has-session (boolean session-token)
                      :has-bearer (boolean bearer-token)
                      :cookies (keys (:cookies request))})
           (cond
             ;; Try JWT authentication first
             bearer-token
             ((jwt-authentication-middleware user-service handler) request)

             ;; Fall back to session authentication
             session-token
             (do
               (log/info "Attempting session authentication")
               ((session-authentication-middleware user-service handler) request))

             ;; No authentication provided
             :else
             (do
               (log/info "No authentication credentials provided"
                         {:headers-keys (keys (:headers request))
                          :cookie-header (get-in request [:headers "cookie"])})
               (create-unauthorized-response "Authentication required" :no-credentials request)))))))))

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
           (if (= #{:admin} required-roles)
             "Administrator required"
             (format "Role '%s' required. User has role '%s'"
                     (str/join ", " (map name required-roles))
                     (some-> user-role name)))
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
   
   Deprecated: the 1-arity, without `user-service`, lets a deactivated user's
   token pass until it expires (BOU-682).

   Args:
     user-service: User service the token's user is read from
     handler: Handler to protect
     
   Returns:
     Protected handler"
  ([handler] (jwt-authentication-middleware handler))
  ([user-service handler] (jwt-authentication-middleware user-service handler)))

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
