(ns wagoe.user.shell.http-interceptors
  "HTTP-level interceptors for user module authentication, authorization, and audit.

   These interceptors work with the HTTP interceptor architecture (ADR-010) and operate
   on HTTP contexts with enter/leave/error semantics.

   Key Differences from Domain Interceptors:
   - Domain interceptors (user.shell.interceptors) handle validation/transformation pipelines
   - HTTP interceptors (this namespace) handle cross-cutting HTTP concerns (auth, audit, rate-limit)

   HTTP Context Shape:
   {:request       Ring request map
    :response      Ring response map
    :route         Route metadata
    :system        {:logger :metrics-emitter :error-reporter}
    :attrs         Additional attributes
    :correlation-id UUID
    :started-at    Instant}

   Usage in Normalized Routes:
   {:path \"/users\"
    :methods {:post {:handler create-user-handler
                     :interceptors ['user.http-interceptors/require-authenticated
                                    'user.http-interceptors/require-admin
                                    'user.http-interceptors/log-action]}}}"
  (:require [wagoe.observability.logging.ports :as logging]
            [wagoe.observability.metrics.ports :as metrics]
            [wagoe.tenant.core.membership :as membership-core]
            [clojure.string :as str]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- get-user
  "Extracts the authenticated user from the normalized request shape.

   Supports both:
   - :user added by Ring authentication middleware
   - [:session :user] for older/session-shaped callers"
  [request]
  (or (:user request)
      (get-in request [:session :user])))

(defn- authenticated?
  "Checks if request has authenticated user."
  [request]
  (some? (get-user request)))

(defn- normalize-role
  "Normalize role representations to a keyword when possible."
  [role]
  (cond
    (keyword? role) role
    (string? role) (keyword role)
    :else nil))

(defn- admin-user?
  "Return true when a user has an admin role.

   Accept both keyword and string role representations to keep the interceptors
   compatible with older session payloads and newer normalized user maps."
  [user]
  (= :admin (normalize-role (:role user))))

(defn- create-error-response
  "Creates standardized error response.

   Note: deliberately does NOT set a Content-Type header. muuntaja's
   format-response middleware skips body encoding when a response already
   carries a Content-Type, which would leave the Clojure-map :body unencoded
   and cause Jetty to write an empty body on the interceptor short-circuit
   path (ZZP-120). Leaving Content-Type unset lets muuntaja negotiate and
   encode the body exactly like a normal handler response."
  [status error-type message correlation-id]
  {:status status
   :headers {"X-Correlation-ID" correlation-id}
   :body {:error error-type
          :message message
          :correlation-id correlation-id}})

;; =============================================================================
;; Authentication Interceptors
;; =============================================================================

(def require-authenticated
  "Requires an authenticated user on the request.
   
   Checks for user in the normalized request shape. If not present, short-circuits
   with 401 Unauthorized response.
   
   Usage:
   {:path \"/users\"
    :methods {:get {:handler list-users
                    :interceptors ['user.http-interceptors/require-authenticated]}}}"
  {:name :require-authenticated
   :enter (fn [{:keys [request correlation-id system] :as ctx}]
            (if (authenticated? request)
              (do
                ;; Log successful authentication check
                (when-let [logger (:logger system)]
                  (logging/debug logger "Authentication check passed"
                                 {:user-id (:id (get-user request))
                                  :correlation-id correlation-id}))
                ctx)
              (do
                ;; Log authentication failure
                (when-let [logger (:logger system)]
                  (logging/warn logger "Authentication required but not provided"
                                {:uri (:uri request)
                                 :method (:request-method request)
                                 :correlation-id correlation-id}))

                ;; Increment auth failure metric
                (when-let [metrics (:metrics-emitter system)]
                  (metrics/increment metrics "http.auth.failures"
                                     {:reason "not-authenticated"}))

                ;; Short-circuit with 401
                (assoc ctx :response
                       (create-error-response 401 "unauthorized"
                                              "Authentication required"
                                              correlation-id)))))})

(def require-unauthenticated
  "Requires NO authenticated user in session.
   
   Useful for routes like /register or /login where being logged in
   should redirect or prevent access.
   
   Usage:
   {:path \"/register\"
    :methods {:get {:handler register-page
                    :interceptors ['user.http-interceptors/require-unauthenticated]}}}"
  {:name :require-unauthenticated
   :enter (fn [{:keys [request correlation-id system] :as ctx}]
            (if-not (authenticated? request)
              ctx
              (do
                ;; Log attempt to access unauthenticated-only route
                (when-let [logger (:logger system)]
                  (logging/debug logger "Already authenticated, access denied"
                                 {:user-id (:id (get-user request))
                                  :uri (:uri request)
                                  :correlation-id correlation-id}))

                ;; Short-circuit with 403
                (assoc ctx :response
                       (create-error-response 403 "forbidden"
                                              "Already authenticated"
                                              correlation-id)))))})

;; =============================================================================
;; Authorization Interceptors
;; =============================================================================

(def require-admin
  "Requires user to have 'admin' role.
   
   Assumes require-authenticated has already run (should be earlier in chain).
   
   Usage:
   {:path \"/users\"
   :methods {:post {:handler create-user
                    :interceptors ['user.http-interceptors/require-authenticated
                                    'user.http-interceptors/require-admin]}}}"
  {:name :require-admin
   :enter (fn [{:keys [request correlation-id system] :as ctx}]
            (let [user (get-user request)]
              (if (and user (admin-user? user))
                (do
                  ;; Log successful authorization
                  (when-let [logger (:logger system)]
                    (logging/debug logger "Admin authorization check passed"
                                   {:user-id (:id user)
                                    :correlation-id correlation-id}))
                  ctx)
                (do
                  ;; Log authorization failure
                  (when-let [logger (:logger system)]
                    (logging/warn logger "Admin role required but not present"
                                  {:user-id (:id user)
                                   :user-role (:role user)
                                   :uri (:uri request)
                                   :correlation-id correlation-id}))

                  ;; Increment auth failure metric
                  (when-let [metrics (:metrics-emitter system)]
                    (metrics/increment metrics "http.auth.failures"
                                       {:reason "insufficient-permissions"}))

                  ;; Short-circuit with 403
                  (assoc ctx :response
                         (create-error-response 403 "forbidden"
                                                "Admin role required"
                                                correlation-id))))))})

(def require-platform-admin
  "Requires the current user to be a platform-level admin without tenant context."
  {:name :require-platform-admin
   :enter (fn [{:keys [request correlation-id system] :as ctx}]
            (let [user (get-user request)]
              (if (and user
                       (admin-user? user)
                       (nil? (:tenant-id user)))
                (do
                  (when-let [logger (:logger system)]
                    (logging/debug logger "Platform admin authorization check passed"
                                   {:user-id (:id user)
                                    :correlation-id correlation-id}))
                  ctx)
                (do
                  (when-let [logger (:logger system)]
                    (logging/warn logger "Platform admin required but not present"
                                  {:user-id (:id user)
                                   :user-role (:role user)
                                   :tenant-id (:tenant-id user)
                                   :uri (:uri request)
                                   :correlation-id correlation-id}))
                  (when-let [metrics (:metrics-emitter system)]
                    (metrics/increment metrics "http.auth.failures"
                                       {:reason "insufficient-permissions"
                                        :required-role "platform-admin"}))
                  (assoc ctx :response
                         (create-error-response 403 "forbidden"
                                                "Platform admin required"
                                                correlation-id))))))})

(defn require-role
  "Factory function to create role-checking interceptor.
   
   Args:
     required-role: Role keyword or string to check (e.g., :admin or \"admin\")
   
   Returns:
     Interceptor that checks for the required role
   
   Usage:
   {:path \"/manager-reports\"
    :methods {:get {:handler manager-reports
                    :interceptors ['user.http-interceptors/require-authenticated
                                   (user.http-interceptors/require-role \"manager\")]}}}"
  [required-role]
  {:name (keyword (str "require-" required-role))
   :enter (fn [{:keys [request correlation-id system] :as ctx}]
            (let [user (get-user request)
                  normalized-required-role (if (keyword? required-role)
                                             required-role
                                             (keyword required-role))]
              (if (and user (= normalized-required-role (normalize-role (:role user))))
                (do
                  ;; Log successful authorization
                  (when-let [logger (:logger system)]
                    (logging/debug logger (str "Role check passed: " required-role)
                                   {:user-id (:id user)
                                    :correlation-id correlation-id}))
                  ctx)
                (do
                  ;; Log authorization failure
                  (when-let [logger (:logger system)]
                    (logging/warn logger (str "Role required: " required-role)
                                  {:user-id (:id user)
                                   :user-role (:role user)
                                   :required-role normalized-required-role
                                   :uri (:uri request)
                                   :correlation-id correlation-id}))

                  ;; Increment auth failure metric
                  (when-let [metrics (:metrics-emitter system)]
                    (metrics/increment metrics "http.auth.failures"
                                       {:reason "insufficient-permissions"
                                        :required-role normalized-required-role}))

                  ;; Short-circuit with 403
                  (assoc ctx :response
                         (create-error-response 403 "forbidden"
                                                (str "Role required: " required-role)
                                                correlation-id))))))})

(def require-self-or-admin
  "Requires user to be accessing their own resource OR be an admin.
   
   Checks if :id path parameter matches session user ID, or if user is admin.
   
   Usage:
   {:path \"/users/:id\"
    :methods {:put {:handler update-user
                    :interceptors ['user.http-interceptors/require-authenticated
                                   'user.http-interceptors/require-self-or-admin]}}}"
  {:name :require-self-or-admin
   :enter (fn [{:keys [request path-params correlation-id system] :as ctx}]
            (let [user (get-user request)
                  target-id (:id path-params)
                  user-id (str (:id user))
                  is-admin? (admin-user? user)
                  is-self? (= target-id user-id)]
              (if (or is-admin? is-self?)
                (do
                  ;; Log successful authorization
                  (when-let [logger (:logger system)]
                    (logging/debug logger "Self-or-admin check passed"
                                   {:user-id user-id
                                    :target-id target-id
                                    :is-admin is-admin?
                                    :is-self is-self?
                                    :correlation-id correlation-id}))
                  ctx)
                (do
                  ;; Log authorization failure
                  (when-let [logger (:logger system)]
                    (logging/warn logger "Access denied: not self or admin"
                                  {:user-id user-id
                                   :target-id target-id
                                   :user-role (:role user)
                                   :uri (:uri request)
                                   :correlation-id correlation-id}))

                  ;; Increment auth failure metric
                  (when-let [metrics (:metrics-emitter system)]
                    (metrics/increment metrics "http.auth.failures"
                                       {:reason "not-self-or-admin"}))

                  ;; Short-circuit with 403
                  (assoc ctx :response
                         (create-error-response 403 "forbidden"
                                                "Access denied"
                                                correlation-id))))))})

;; =============================================================================
;; Audit Logging Interceptors
;; =============================================================================

(def log-action
  "Logs successful actions in leave phase for audit trail.
   
   Only logs successful actions (2xx responses). Failures are already
   logged by error-reporting interceptors.
   
   Usage:
   {:path \"/users\"
    :methods {:post {:handler create-user
                     :interceptors ['user.http-interceptors/require-authenticated
                                    'user.http-interceptors/require-admin
                                    'user.http-interceptors/log-action]}}}"
  {:name :log-action
   :leave (fn [{:keys [request response correlation-id system] :as ctx}]
            (let [status (:status response)
                  user (get-user request)]
              ;; Only log successful actions (2xx)
              (when (and (>= status 200) (< status 300))
                (when-let [logger (:logger system)]
                  (logging/info logger "Action completed successfully"
                                {:action (:uri request)
                                 :method (:request-method request)
                                 :user-id (:id user)
                                 :user-role (:role user)
                                 :status status
                                 :correlation-id correlation-id}))

                ;; Increment successful action metric
                (when-let [metrics (:metrics-emitter system)]
                  (metrics/increment metrics "http.actions.success"
                                     {:action (:uri request)
                                      :method (name (:request-method request))})))
              ctx))})

(def log-all-actions
  "Logs ALL actions (success and failure) in leave phase.
   
   More verbose than log-action. Use for high-security endpoints.
   
   Usage:
   {:path \"/admin/users/:id/hard-delete\"
    :methods {:post {:handler hard-delete-user
                     :interceptors ['user.http-interceptors/require-authenticated
                                    'user.http-interceptors/require-admin
                                    'user.http-interceptors/log-all-actions]}}}"
  {:name :log-all-actions
   :leave (fn [{:keys [request response correlation-id system] :as ctx}]
            (let [status (:status response)
                  user (get-user request)
                  success? (and (>= status 200) (< status 300))]
              (when-let [logger (:logger system)]
                (logging/info logger (if success?
                                       "Action completed successfully"
                                       "Action failed")
                              {:action (:uri request)
                               :method (:request-method request)
                               :user-id (:id user)
                               :user-role (:role user)
                               :status status
                               :success success?
                               :correlation-id correlation-id}))

              ;; Increment metric
              (when-let [metrics (:metrics-emitter system)]
                (metrics/increment metrics (if success?
                                             "http.actions.success"
                                             "http.actions.failure")
                                   {:action (:uri request)
                                    :method (name (:request-method request))}))
              ctx))})

;; =============================================================================
;; Combined Interceptor Stacks
;; =============================================================================

(def admin-endpoint-stack
  "Standard interceptor stack for admin-only endpoints.
   
   Applies authentication, admin authorization, and audit logging.
   
   Usage:
   {:path \"/users\"
    :methods {:post {:handler create-user
                     :interceptors user.http-interceptors/admin-endpoint-stack}}}"
  [require-authenticated
   require-admin
   log-action])

(def user-endpoint-stack
  "Standard interceptor stack for authenticated user endpoints.
   
   Applies authentication and audit logging (no role check).
   
   Usage:
   {:path \"/users/:id\"
    :methods {:get {:handler get-user
                    :interceptors user.http-interceptors/user-endpoint-stack}}}"
  [require-authenticated
   log-action])

(def public-endpoint-stack
  "Interceptor stack for public endpoints (no auth required).
   
   Only applies audit logging for successful actions.
   
   Usage:
   {:path \"/health\"
    :methods {:get {:handler health-check
                    :interceptors user.http-interceptors/public-endpoint-stack}}}"
  [log-action])

;; =============================================================================
;; Helper: Create Custom Stack
;; =============================================================================

(defn create-custom-stack
  "Creates a custom interceptor stack from components.

   Args:
     components: Map of interceptor components
       - :auth - Authentication interceptor (optional)
       - :authz - Authorization interceptor (optional)
       - :audit - Audit interceptor (optional)

   Returns:
     Vector of interceptors in correct order

   Example:
   (create-custom-stack {:auth require-authenticated
                         :authz (require-role \"manager\")
                         :audit log-all-actions})"
  [{:keys [auth authz audit]}]
  (filterv some? [auth authz audit]))

;; =============================================================================
;; Tenant Membership Interceptors (ADR-016)
;; =============================================================================

(def require-tenant-member
  "Requires an active tenant membership to be present on the request.

   Reads :tenant-membership set by wrap-tenant-membership middleware.
   Short-circuits with 403 when no active membership is found.

   Usage:
   {:path \"/api/tenants/:tenant-id/documents\"
    :methods {:get {:handler list-documents
                    :interceptors ['user.http-interceptors/require-authenticated
                                   'user.http-interceptors/require-tenant-member]}}}"
  {:name  ::require-tenant-member
   :enter (fn [{:keys [request correlation-id system] :as ctx}]
            (let [membership (:tenant-membership request)]
              (if (and membership (membership-core/active-member? membership))
                ctx
                (do
                  (when-let [logger (:logger system)]
                    (logging/warn logger "Active tenant membership required"
                                  {:uri            (:uri request)
                                   :correlation-id correlation-id}))
                  (when-let [metrics (:metrics-emitter system)]
                    (metrics/increment metrics "http.auth.failures"
                                       {:reason "not-a-member"}))
                  (assoc ctx :response
                         (create-error-response 403 "forbidden"
                                                "Active tenant membership required"
                                                correlation-id))))))})

(defn require-tenant-role
  "Factory function: returns an interceptor that requires the given tenant role(s).

   Reads :tenant-membership set by wrap-tenant-membership middleware.
   Short-circuits with 403 when the membership is absent or has insufficient role.

   Args:
     allowed-roles - set of allowed role keywords, e.g. #{:admin}

   Returns:
     Interceptor map."
  [allowed-roles]
  {:name  ::require-tenant-role
   :enter (fn [{:keys [request correlation-id system] :as ctx}]
            (let [membership (:tenant-membership request)]
              (if (and membership
                       (membership-core/active-member? membership)
                       (membership-core/has-role? membership allowed-roles))
                ctx
                (do
                  (when-let [logger (:logger system)]
                    (logging/warn logger "Insufficient tenant role"
                                  {:uri            (:uri request)
                                   :required-roles allowed-roles
                                   :actual-role    (:role membership)
                                   :correlation-id correlation-id}))
                  (when-let [metrics (:metrics-emitter system)]
                    (metrics/increment metrics "http.auth.failures"
                                       {:reason "insufficient-tenant-role"}))
                  (assoc ctx :response
                         (create-error-response 403 "forbidden"
                                                "Insufficient tenant role"
                                                correlation-id))))))})

(def require-tenant-admin
  "Requires the current user to have the :admin role in the active tenant membership.

   Shorthand for (require-tenant-role #{:admin}).

   Usage:
   {:path \"/api/tenants/:tenant-id/settings\"
    :methods {:put {:handler update-settings
                    :interceptors ['user.http-interceptors/require-authenticated
                                   'user.http-interceptors/require-tenant-admin]}}}"
  (require-tenant-role #{:admin}))

(def require-web-tenant-admin
  "Requires the current user to have the :admin role in the active tenant membership.

   Like require-tenant-admin, but web-aware: when the request URI starts with /web
   it redirects to /web/login (302) instead of returning a JSON 403. Use this on
   HTML routes so the browser lands on the login page rather than a bare error body.

   Usage:
   {:path \"/web/tenants/:tenant-id/settings\"
    :methods {:get {:handler settings-page
                    :interceptors ['user.http-interceptors/require-authenticated
                                   'user.http-interceptors/require-web-tenant-admin]}}}"
  {:name  ::require-web-tenant-admin
   :enter (fn [{:keys [request correlation-id system] :as ctx}]
            (let [membership (:tenant-membership request)]
              (if (and membership
                       (membership-core/active-member? membership)
                       (membership-core/has-role? membership #{:admin}))
                ctx
                (do
                  (when-let [logger (:logger system)]
                    (logging/warn logger "Insufficient tenant role"
                                  {:uri            (:uri request)
                                   :actual-role    (:role membership)
                                   :correlation-id correlation-id}))
                  (when-let [metrics (:metrics-emitter system)]
                    (metrics/increment metrics "http.auth.failures"
                                       {:reason "insufficient-tenant-role"}))
                  (assoc ctx :response
                         (if (str/starts-with? (get request :uri "") "/web")
                           {:status  302
                            :headers {"Location" (str "/web/login?return-to="
                                                      (java.net.URLEncoder/encode
                                                       (:uri request) "UTF-8"))}
                            :body    ""}
                           (create-error-response 403 "forbidden"
                                                  "Insufficient tenant role"
                                                  correlation-id)))))))})
