(ns wagoe.user.shell.web-handlers
  "Web UI handlers for user module.

   This namespace implements Ring handlers that return HTML responses
   for user-related web pages, either full pages or HTMX partial updates.

   Handler Categories:
   - Page Handlers: Return full HTML pages for initial browser requests
   - HTMX Handlers: Return HTML fragments for dynamic updates

   All handlers use the shared UI components and user shell services."
  (:require [wagoe.core.validation :as cv]
            [wagoe.i18n.shell.middleware :as i18n-middleware]
            [wagoe.i18n.shell.render :as i18n]
            [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.shared.ui.core.validation :as validation]
            [wagoe.platform.shell.web.table :as web-table]
            [wagoe.user.core.ui :as user-ui]
            [wagoe.user.core.profile-ui :as profile-ui]
            [wagoe.user.ports :as user-ports]
            [wagoe.user.schema :as user-schema]
            [wagoe.email.ports :as email-ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.middleware :as user-middleware]
            [wagoe.user.shell.mfa :as mfa]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [ring.util.response :as response])
  (:import (java.time Instant)
           (java.util UUID)))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- secure-cookies?
  "Whether auth cookies should carry the `Secure` attribute (HTTPS-only).
   Read from config; defaults to true (fail-secure) so a production deployment
   that forgets to set it still gets Secure cookies. Set to false only for
   local development over plain HTTP."
  [config]
  (get-in config [:wagoe/settings :secure-cookies?] true))

(defn- escape-js-string
  "Escape a string for safe embedding inside a JavaScript single-quoted
   string literal within a <script> tag. Prevents XSS via quote-breaking
   and </script> tag injection."
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "'" "\\'")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\u0000" "\\u0000")
      (str/replace "</" "<\\/")))

(defn- safe-return-url
  "Validate and sanitize return URL to prevent open redirect attacks.
   
   Args:
     url: String URL to validate
     default: Default URL if validation fails
     
   Returns:
     Safe local URL string"
  [url default]
  (if (and url
           (string? url)
           (str/starts-with? url "/")
           (not (str/starts-with? url "//")))
    url
    default))

(defn- validate-request-data
  "Validate request data against schema with transformation.
   
   Args:
     schema: Malli schema
     data: Data to validate (typically string form params)
     
   Returns:
     [valid? errors transformed-data] tuple where errors is field-keyed map or nil"
  [schema data]
  (let [transformed ((cv/decoder schema user-schema/user-request-transformer) data)
        valid? (cv/valid? schema transformed)]
    (if valid?
      [true nil transformed]
      (let [explain (cv/explain schema transformed)
            field-errors (validation/explain->field-errors explain)]
        [false field-errors transformed]))))

(defn- current-time
  "Return the shell-controlled current time for rendering relative timestamps."
  []
  (Instant/now))

(defn- current-zone-id
  "Return the shell-controlled zone id for rendering presentation-only dates."
  []
  (java.time.ZoneId/systemDefault))

(defn- html-response
  "Create HTML response map, resolving any [:t ...] i18n markers in the Hiccup tree.

   Args:
     request: Ring request map (used to extract :i18n/t translation function)
     html: HTML string or Hiccup structure
     status: HTTP status code (optional, defaults to 200)
     headers: Additional headers map (optional)

   Returns:
     Ring response map"
  ([request html]
   (html-response request html 200))
  ([request html status]
   (html-response request html status {}))
  ([request html status extra-headers]
   (let [fallback-t (fn
                      ([k] (name k))
                      ([k _params] (name k))
                      ([k _params _n] (name k)))
         t-fn (or (i18n-middleware/resolve-t-fn request)
                  (get request :i18n/t)
                  fallback-t)
         body-content (i18n/render html t-fn)
         response {:status status
                   :headers (merge {"Content-Type" "text/html; charset=utf-8"} extra-headers)
                   :body body-content}]
     ;; Debug logging to check response structure
     (clojure.tools.logging/debug "HTML response created"
                                  {:status status
                                   :body-type (type body-content)
                                   :body-string? (string? body-content)
                                   :body-length (count (str body-content))})
     response)))

(def ^:private htmx-no-cache-headers
  {"Cache-Control" "no-store, no-cache, must-revalidate, max-age=0"
   "Pragma"        "no-cache"
   "Expires"       "0"
   "Vary"          "HX-Request"})

(defn- remove-nil-values
  "Remove keys with nil values from a map.
   
   This is needed because optional schema fields like :boolean or :int
   don't accept nil values - they must either be present with the correct
   type or completely absent from the map.
   
   Fields with [:maybe ...] in the schema CAN have nil values and should
   be kept (e.g., :mfa-secret, :updated-at, :deleted-at).
   
   Args:
     m: Map to filter
     
   Returns:
     Map with nil values removed"
  [m]
  (into {} (filter (fn [[_ v]] (some? v)) m)))

(defn- build-user-list-opts
  "Build list-users options from table query and search filters.

   Only active (non-deleted) users are returned."
  [table-query filters]
  (let [base {:limit          (:limit table-query)
              :offset         (:offset table-query)
              :sort-by        (:sort table-query)
              :sort-direction (:dir table-query)
              :filter-active  true}]
    (cond-> base
      (:q filters)
      (assoc :filter-email-contains (:q filters))

      (:role filters)
      (assoc :filter-role (keyword (:role filters))))))

;; =============================================================================
;; Page Handlers (Full HTML)
;; =============================================================================

(defn home-page-handler
  "Handler for the home/landing page (GET /).

   Redirects authenticated users to /web/dashboard, unauthenticated to /web.

   Args:
     config: Application configuration map

   Returns:
     Ring handler function"
  [_config]
  (fn [request]
    (let [user (:user request)]
      (if user
        (response/redirect "/web/dashboard")
        (response/redirect "/web")))))

(defn web-root-page-handler
  "Handler for the web root landing page (GET /web).

   Shows logo, welcome message and login link for unauthenticated users.
   Authenticated users (valid session cookie present) are redirected to the dashboard.

   Args:
     user-service: User service instance for session validation
     _config: Application configuration map

   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (let [session-token (user-middleware/extract-session-token request)
          authenticated? (when session-token
                           (try
                             (boolean (user-ports/validate-session user-service session-token))
                             (catch Exception _ false)))]
      (if authenticated?
        (response/redirect "/web/dashboard")
        (html-response request (user-ui/web-root-page {}))))))

(defn dashboard-page-handler
  "Handler for the dashboard page (GET /web/dashboard).
   
   Shows a welcome page with quick links and account statistics.
   
   Args:
     user-service: User service instance  
     mfa-service: MFA service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service mfa-service config]
  (fn [request]
    (try
      (let [user (:user request)
            user-id (:id user)
            ;; Fetch dashboard data
            sessions (user-ports/get-user-sessions user-service user-id)
            active-sessions (count (filter #(nil? (:revoked-at %)) sessions))
            mfa-status (mfa/get-mfa-status mfa-service user-id)
            mfa-enabled? (:enabled mfa-status false)
            dashboard-data {:active-sessions-count active-sessions
                            :mfa-enabled mfa-enabled?}
            extra-cards (:dashboard-extra-cards config)
            page-opts (cond-> {:user user
                               :current-time (current-time)
                               :zone-id (current-zone-id)
                               :flash (get request :flash)}
                        extra-cards (assoc :extra-cards extra-cards))]
        (html-response request (user-ui/dashboard-page user dashboard-data page-opts)))
      (catch Exception e
        (log/error e "Error in dashboard-page-handler")
        (html-response request
                       (layout/pilot-page-layout "Error"
                                                 (ui/error-message (.getMessage e)))
                       500)))))

(defn users-page-handler
  "Handler for the users listing page (GET /web/users).
   
   Fetches users from the user service and renders them in a table, with
   support for shared search filters (q, role, status)."
  [user-service _config]
  (fn [request]
    (try
      (let [qp          (:query-params request)
            table-query (web-table/parse-table-query
                         qp
                         {:default-sort      :created-at
                          :default-dir       :desc
                          :default-page-size 20})
            filters     (web-table/parse-search-filters qp)
            list-opts   (build-user-list-opts table-query filters)
            users-result (user-ports/list-users user-service list-opts)
            page-opts    {:user        (get request :user)
                          :flash       (get request :flash)
                          :table-query table-query
                          :filters     filters}]
        (html-response request
                       (user-ui/users-page
                        (:users users-result)
                        (:total-count users-result)
                        page-opts)))
      (catch Exception e
        (clojure.tools.logging/error e "Error in users-page-handler")
        (html-response request
                       (layout/pilot-page-layout "Error"
                                                 (ui/error-message (.getMessage e)))
                       500)))))

(defn user-detail-page-handler
  "Handler for individual user detail page (GET /web/users/:id).
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [user-id (get-in request [:path-params :id])
            user-result (user-ports/get-user-by-id user-service (UUID/fromString user-id))
            page-opts {:user (get request :user)
                       :flash (get request :flash)}]
        (if user-result
          (html-response request (user-ui/user-detail-page user-result page-opts))
          (html-response request
                         (layout/error-layout 404 "User Not Found"
                                              "The requested user could not be found.")
                         404)))
      (catch IllegalArgumentException _
        (html-response request
                       (layout/error-layout 400 "Invalid User ID"
                                            "User ID must be a valid UUID.")
                       400))
      (catch Exception e
        (html-response request
                       (layout/pilot-page-layout "Error"
                                                 (ui/error-message (.getMessage e)))
                       500)))))

(defn create-user-page-handler
  "Handler for the create user page (GET /web/users/new).

   Args:
     config: Application configuration map

   Returns:
     Ring handler function"
  [_config]
  (fn [request]
    (let [raw-return-to (get-in request [:query-params "return-to"])
          return-to (safe-return-url raw-return-to "/web/admin/users")
          page-opts {:user (get request :user)
                     :flash (get request :flash)
                     :return-to return-to}]
      (html-response request (user-ui/create-user-page {} {} page-opts)))))

;; =============================================================================
;; Authentication Handlers (Login / Logout)
;; =============================================================================

(defn login-page-handler
  "GET /web/login - render login page."
  [config]
  (fn [request]
    (let [return-to (get-in request [:query-params "return-to"])
          ;; Check if we have a remembered email from previous login
          remembered-email (get-in request [:cookies "remembered-email" :value])
          initial-data (if remembered-email
                         {:email remembered-email
                          :remember true}
                         {})
          page-opts {:user (get request :user)
                     :flash (get request :flash)
                     :return-to return-to
                     :logo-url (get-in config [:wagoe/settings :logo-url])}]
      (html-response request (user-ui/login-page initial-data {} page-opts)))))

(defn login-submit-handler
  "POST /web/login - validate credentials, authenticate, set session cookie."
  [user-service config]
  (fn [request]
    (let [form-data (:form-params request)
          raw-return-to (or (get form-data "return-to")
                            (get-in request [:query-params "return-to"]))
          prepared-data {:email (get form-data "email")
                         :password (get form-data "password")
                         :remember (boolean (get form-data "remember"))
                         :mfa-code (get form-data "mfa-code")
                         :ip-address (:remote-addr request)
                         :user-agent (get-in request [:headers "user-agent"])}
          [valid? validation-errors validated-data]
          (validate-request-data user-schema/LoginRequest prepared-data)
          logo-url (get-in config [:wagoe/settings :logo-url])]
      (if-not valid?
        ;; Re-render login page with validation errors, preserving return-to
        (html-response request
                       (user-ui/login-page prepared-data validation-errors
                                           {:user (get request :user)
                                            :flash (get request :flash)
                                            :return-to raw-return-to
                                            :logo-url logo-url})
                       400)
        (try
          ;; Use IUserService/authenticate-user with validated data
          (let [auth-result (user-ports/authenticate-user user-service validated-data)
                user (:user auth-result)
                ;; Determine default redirect based on user role (if authenticated)
                default-redirect (if (and user (= :admin (:role user)))
                                   "/web/admin/users"
                                   "/web/dashboard")
                return-to (safe-return-url raw-return-to default-redirect)]
            (log/info "Login attempt" {:email (:email validated-data)
                                       :authenticated (:authenticated auth-result)
                                       :result-keys (keys auth-result)
                                       :ip-address (:ip-address validated-data)
                                       :user-agent (:user-agent validated-data)})
            (cond
              ;; Authentication successful
              (:authenticated auth-result)
              (let [session (:session auth-result)
                    session-token (:session-token session)
                    remember? (:remember validated-data)
                      ;; Calculate cookie max-age based on remember-me checkbox
                      ;; 30 days if remember-me is checked, otherwise session cookie (no max-age)
                    cookie-max-age (when remember? (* 30 24 60 60))]
                (log/info "Login successful, setting cookie and redirecting"
                          {:session-token session-token
                           :session-token-type (type session-token)
                           :remember? remember?
                           :cookie-max-age cookie-max-age
                           :return-to return-to
                           :user-role (:role user)})
                  ;; Set session-token cookie and redirect to original URL or default
                (-> (response/redirect return-to)
                    (assoc-in [:cookies "session-token"]
                              (cond-> {:value session-token
                                       :http-only true
                                       :secure (secure-cookies? config)
                                       :same-site :strict
                                       :path "/"}
                                  ;; Only set max-age if remember-me is checked
                                  ;; Without max-age, cookie is a session cookie (expires when browser closes)
                                remember? (assoc :max-age cookie-max-age)))
                      ;; Set or clear the remembered-email cookie
                    (assoc-in [:cookies "remembered-email"]
                              (if remember?
                                  ;; Remember the email for 30 days
                                {:value (:email validated-data)
                                 :http-only false  ; Allow JavaScript to read it if needed
                                 :secure (secure-cookies? config)
                                 :path "/"
                                 :max-age cookie-max-age}
                                  ;; Clear the remembered email by setting expired cookie
                                {:value ""
                                 :max-age 0
                                 :path "/"}))))

              ;; MFA required - show MFA code input
              (:requires-mfa? auth-result)
              (do
                (log/info "MFA code required for login" {:email (:email prepared-data)})
                (html-response request
                               (user-ui/mfa-login-page prepared-data
                                                       {}
                                                       {:user (get request :user)
                                                        :flash (get request :flash)
                                                        :return-to return-to
                                                        :logo-url logo-url})
                               200))

              ;; Authentication failed (e.g. wrong password or invalid MFA code)
              :else
              (let [error-message (cond
                                    (= :account-locked (:reason auth-result))
                                    "Too many failed attempts, please try again in 15 minutes"

                                    (= :mfa-verification-failed (:reason auth-result))
                                    "Invalid MFA code"

                                    :else
                                    "Invalid email or password")]
                (log/warn "Login failed" {:email (:email prepared-data)
                                          :reason (:reason auth-result)
                                          :message (:message auth-result)})
                (html-response request
                               (if (:mfa-code prepared-data)
                   ;; If MFA code was provided, show MFA page with error
                                 (user-ui/mfa-login-page prepared-data
                                                         {:mfa-code [error-message]}
                                                         {:user (get request :user)
                                                          :flash (get request :flash)
                                                          :return-to return-to
                                                          :logo-url logo-url})
                   ;; Otherwise show regular login page
                                 (user-ui/login-page prepared-data
                                                     {:password [error-message]}
                                                     {:user (get request :user)
                                                      :flash (get request :flash)
                                                      :return-to return-to
                                                      :logo-url logo-url}))
                               401))))
          (catch Exception e
            (log/error e "Login error" {:email (:email prepared-data)})
            (html-response request
                           (layout/pilot-page-layout "Login error"
                                                     (ui/error-message (.getMessage e)))
                           500)))))))

(defn logout-handler
  "POST /web/logout - clear session cookie and redirect to home."
  [user-service _config]
  (fn [request]
    (let [session-token (or (get-in request [:cookies "session-token" :value])
                            (get-in request [:headers "x-session-token"]))]
      (when session-token
        (try
          ;; Best-effort server-side logout
          (log/info "Attempting to invalidate session" {:has-token true})
          (let [result (user-ports/logout-user user-service session-token)]
            (log/info "Session invalidation result" {:result result}))
          (catch Exception e
            (log/error e "Error during logout"))))
      (-> (response/redirect "/")
          (assoc :cookies {"session-token"
                           {:value "" :max-age 0 :path "/"}})))))

(defn register-page-handler
  "GET /web/register - render self-service registration page."
  [_config]
  (fn [request]
    (let [page-opts {:user (get request :user)
                     :flash (get request :flash)
                     :return-to (get-in request [:query-params "return-to"])}]
      (html-response request (user-ui/register-page {} {} page-opts)))))

(defn register-submit-handler
  "POST /web/register - validate data, create user account."
  [user-service config]
  (fn [request]
    (let [form-data (:form-params request)
          raw-return-to (or (get form-data "return-to")
                            (get-in request [:query-params "return-to"]))
          prepared-data {:name (get form-data "name")
                         :email (get form-data "email")
                         :password (get form-data "password")
                         ;; Self-service accounts are always regular users and active
                         :role :user
                         :active true}
          [valid? validation-errors _]
          (validate-request-data user-schema/CreateUserRequest prepared-data)]
      (if-not valid?
        (html-response request
                       (user-ui/register-page prepared-data validation-errors
                                              {:user (get request :user)
                                               :flash (get request :flash)
                                               :return-to raw-return-to})
                       400)
        (try
          (let [user-result (user-ports/register-user user-service prepared-data)
                ;; Automatically authenticate the newly registered user
                auth-data {:email (:email user-result)
                           :password (get prepared-data :password)
                           :remember false
                           :ip-address (:remote-addr request)
                           :user-agent (get-in request [:headers "user-agent"])}
                auth-result (user-ports/authenticate-user user-service auth-data)]
            (if (:authenticated auth-result)
              (let [session (:session auth-result)
                    session-token (:session-token session)
                    return-to (safe-return-url raw-return-to "/web/profile")]
                ;; Automatically log in and redirect to profile with welcome message
                (-> (response/redirect return-to)
                    (assoc :status 303) ; See Other
                    (assoc :flash {:type :success
                                   :message (str "Welcome to Wagoe, " (:name user-result) "! "
                                                 "Your account has been created successfully. "
                                                 "Take a moment to review your profile and set up "
                                                 "Multi-Factor Authentication for enhanced security.")})
                    (assoc-in [:cookies "session-token"]
                              {:value session-token
                               :http-only true
                               :path "/"
                               :secure (secure-cookies? config)
                               :same-site :strict})))
              ;; Shouldn't happen, but fallback to login page
              (response/redirect "/web/login")))
          (catch Exception e
            (html-response request
                           (layout/pilot-page-layout "Registration error"
                                                     (ui/error-message (.getMessage e)))
                           500)))))))

;; =============================================================================
;; HTMX Fragment Handlers
;; =============================================================================
(defn users-table-fragment-handler
  "Handler for refreshing the users table (GET /web/users/table).

   Returns only the table container fragment for HTMX replacement."
  [user-service _config]
  (fn [request]
    (try
      (let [qp          (:query-params request)
            table-query (web-table/parse-table-query
                         qp
                         {:default-sort      :created-at
                          :default-dir       :desc
                          :default-page-size 20})
            filters     (web-table/parse-search-filters qp)
            list-opts   (build-user-list-opts table-query filters)
            users-result (user-ports/list-users user-service list-opts)]
        (html-response request
                       (user-ui/users-table-fragment
                        (:users users-result)
                        table-query
                        (:total-count users-result)
                        filters)))
      (catch Exception e
        (html-response request (ui/error-message (.getMessage e)) 500)))))

(defn bulk-update-users-htmx-handler
  "HTMX handler for bulk user operations (POST /web/users/bulk).

   Supports multiple actions: deactivate, activate, delete, role changes.
   Returns the updated users table fragment using the same query
   parameters and filters as the current view."
  [user-service config]
  (fn [request]
    (try
      (let [form-params (:form-params request)
            ids-param   (get form-params "user-ids")
            action      (get form-params "action")
            ids         (cond
                          (nil? ids-param) []
                          (sequential? ids-param) ids-param
                          :else [ids-param])
            _           (log/info "Bulk user operation" {:action action
                                                         :ids-count (count ids)})
            ;; Merge query params from URL and hidden form fields to
            ;; preserve current filters and table state during bulk ops
            qp          (merge (:query-params request)
                               (select-keys form-params ["q" "role" "status" "sort" "dir" "page" "page-size"]))
            _table-query (web-table/parse-table-query
                          qp
                          {:default-sort      :created-at
                           :default-dir       :desc
                           :default-page-size 20})
            _filters     (web-table/parse-search-filters qp)]
        (when (or (empty? ids) (str/blank? (str action)))
          (throw (ex-info "No users selected or action missing"
                          {:type :bad-request})))
        ;; Execute bulk operation based on action type
        (case action
          "deactivate"
          (doseq [id-str ids]
            (let [uuid (UUID/fromString id-str)]
              (user-ports/deactivate-user user-service uuid)))

          "activate"
          (doseq [id-str ids]
            (let [uuid (UUID/fromString id-str)
                  user (user-ports/get-user-by-id user-service uuid)]
              (when user
                ;; Reactivate by updating with active=true and clearing deleted_at
                (user-ports/update-user-profile user-service (assoc user :active true :deleted_at nil)))))

          "delete"
          (doseq [id-str ids]
            (let [uuid (UUID/fromString id-str)]
              (user-ports/permanently-delete-user user-service uuid)))

          "role-admin"
          (doseq [id-str ids]
            (let [uuid (UUID/fromString id-str)
                  user (user-ports/get-user-by-id user-service uuid)]
              (when user
                (user-ports/update-user-profile user-service (assoc user :role :admin)))))

          "role-user"
          (doseq [id-str ids]
            (let [uuid (UUID/fromString id-str)
                  user (user-ports/get-user-by-id user-service uuid)]
              (when user
                (user-ports/update-user-profile user-service (assoc user :role :user)))))

          (throw (ex-info "Unsupported bulk action"
                          {:type :bad-request
                           :action action})))
        ;; After bulk operation, reuse users-table-fragment-handler logic
        (let [fragment-handler (users-table-fragment-handler user-service config)
              get-request      {:query-params qp}]
          (fragment-handler get-request)))
      (catch IllegalArgumentException _
        (html-response request (ui/error-message "Invalid user ID in bulk operation") 400))

      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (if (= (:type data) :bad-request)
            (html-response request (ui/error-message (.getMessage e)) 400)
            (do
              (log/error e "Bulk user operation failed")
              (html-response request (ui/error-message (.getMessage e)) 500)))))

      (catch Exception e
        (log/error e "Unexpected error in bulk-update-users-htmx-handler")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

(defn- send-welcome-email!
  "Send welcome email to a newly created user through the email lib.
   email-sender satisfies wagoe.email.ports/EmailSenderProtocol (wired from
   :wagoe/email), so welcome mail flows through the framework's email module
   rather than the lower-level external SMTP provider.
   Config keys used: :welcome-email-from, :app-name (set at wiring time)."
  [email-sender user config]
  (let [app-name (or (:app-name config) "Wagoe")
        from     (or (:welcome-email-from config) "no-reply@localhost")]
    (email-ports/send-email!
     email-sender
     {:id      (random-uuid)
      :to      [(:email user)]
      :from    from
      :subject (str "Welcome to " app-name)
      :body    (str "Hello " (:name user) ",\n\n"
                    "Your account has been created successfully.\n\n"
                    "You can log in at any time.\n\n"
                    "— " app-name)})))

(defn create-user-htmx-handler
  "HTMX handler for creating a new user (POST /web/users).

   Validates form data and on success instructs HTMX to navigate back to the
   caller-provided `return-to` URL (validated via `safe-return-url`), falling
   back to the admin users list. Uses the `HX-Redirect` response header rather
   than injecting JavaScript so the URL value never reaches an HTML/JS context.

   Args:
     user-service: User service instance
     email-sender: Optional ISmtpProvider for sending welcome emails
     config: Application configuration map

   Returns:
     Ring handler function"
  [user-service email-sender config]
  (fn [request]
    (let [form-data (:form-params request)
          raw-return-to (get form-data "return-to")
          return-to (safe-return-url raw-return-to "/web/admin/users")
          send-welcome? (let [v (get form-data "send-welcome")]
                          (or (= "on" v) (= "true" v)
                              (and (sequential? v) (some #{"on" "true"} v))))
          ;; Prepare data with kebab-case keyword keys for validation
          prepared-data {:name (get form-data "name")
                         :email (get form-data "email")
                         :password (get form-data "password")
                         :role (keyword (get form-data "role"))
                         :send-welcome send-welcome?}
          [valid? validation-errors _] (validate-request-data user-schema/CreateUserRequest prepared-data)]
      (if-not valid?
        (html-response request
                       (user-ui/create-user-form prepared-data validation-errors nil
                                                 {:min-length 8 :require-numbers true}
                                                 {:return-to return-to})
                       400)
        (try
          (let [user-data (dissoc prepared-data :send-welcome)
                user-result (user-ports/register-user user-service user-data)
                email-sent? (when (and send-welcome? email-sender)
                              (try
                                (send-welcome-email! email-sender user-result config)
                                true
                                (catch Exception e
                                  (log/warn e "Failed to send welcome email" {:email (:email user-result)})
                                  false)))
                flash-msg (str "User " (:name user-result) " created"
                               (when email-sent?
                                 (str ", welcome email sent to " (:email user-result))))
                toast-json (str "{\"type\":\"success\",\"message\":\""
                                (escape-js-string flash-msg) "\"}")
                ;; Return inline script that stores toast + redirects.
                ;; This avoids HX-Redirect (which skips XHR event listeners)
                ;; and cached JS issues (inline script always executes fresh).
                body (str "<script>"
                          "try{sessionStorage.setItem('pendingToast','"
                          (escape-js-string toast-json)
                          "')}catch(e){}"
                          "window.location.href='" (escape-js-string return-to) "';"
                          "</script>")]
            (-> (response/response body)
                (response/status 200)
                (response/header "Content-Type" "text/html; charset=utf-8")))
          (catch Exception e
            (log/error e "Create user failed")
            (html-response request (ui/error-message (.getMessage e)) 500)))))))

(defn update-user-htmx-handler
  "HTMX handler for updating a user (PUT /web/users/:id).
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [user-id (get-in request [:path-params :id])
            form-data (:form-params request)
            _ (log/info "Update user form data" {:form-data form-data
                                                 :role-value (get form-data "role")
                                                 :active-value (get form-data "active")})
            ;; Prepare data with kebab-case keyword keys for validation
            ;; Note: Checkbox fields are "on" when checked, absent when unchecked
            prepared-data {:name (get form-data "name")
                           :email (get form-data "email")
                           :role (when-let [role (get form-data "role")] (keyword role))
                           :active (= "on" (get form-data "active"))}
            _ (log/info "Prepared data for update" {:prepared-data prepared-data})
            [valid? _validation-errors _] (validate-request-data user-schema/UpdateUserRequest prepared-data)]
        (if-not valid?
          (html-response request (user-ui/user-detail-form (assoc prepared-data :id (UUID/fromString user-id))) 400)
          (try
            ;; Fetch existing user and merge updates
            (let [uuid (UUID/fromString user-id)
                  existing-user (user-ports/get-user-by-id user-service uuid)]
              (if-not existing-user
                (html-response request (ui/error-message "User not found") 404)
                (let [;; Merge form data into existing user (only update provided fields)
                      user-data (merge existing-user
                                       (select-keys prepared-data [:name :email :role :active]))
                      user-result (user-ports/update-user-profile user-service user-data)]
                  ;; Re-render the form with updated user data and a success indicator
                  (html-response
                   request
                   [:div
                    [:div.success-banner {:style "background: #d4edda; color: #155724; padding: 10px; margin-bottom: 15px; border-radius: 4px;"}
                     "✓ User updated successfully"]
                    (user-ui/user-detail-form user-result)]
                   200
                   {"HX-Trigger" "userUpdated"}))))
            (catch Exception e
              (log/error e "Error updating user")
              (html-response request (ui/error-message (.getMessage e)) 500)))))
      (catch IllegalArgumentException _
        (html-response request (ui/error-message "Invalid user ID") 400)))))

(defn delete-user-htmx-handler
  "HTMX handler for deactivating a user (DELETE /web/users/:id).
   
   Performs soft delete via deactivate-user.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [user-id (get-in request [:path-params :id])
            uuid (UUID/fromString user-id)]
        (user-ports/deactivate-user user-service uuid)
        (html-response request (user-ui/user-deleted-success user-id)
                       200
                       {"HX-Trigger" "userDeleted"}))
      (catch IllegalArgumentException _
        (html-response request (ui/error-message "Invalid user ID") 400))
      (catch Exception e
        (html-response request (ui/error-message (.getMessage e)) 500)))))

(defn hard-delete-user-handler
  "Handler for permanently deleting a user (POST /web/users/:id/hard-delete).
   
   ⚠️  IRREVERSIBLE OPERATION - Permanently removes user and all related data.
   Should only be used for GDPR compliance or similar requirements.
   Requires admin role.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [user-id (get-in request [:path-params :id])
            uuid (UUID/fromString user-id)
            current-user (:user request)]
        ;; Check if current user is admin
        (if-not (= :admin (:role current-user))
          (html-response request
                         (layout/error-layout 403 "Access Denied"
                                              "Only administrators can permanently delete users.")
                         403)
          (do
            (user-ports/permanently-delete-user user-service uuid)
            (-> (response/redirect "/web/users")
                (assoc :flash {:success "User permanently deleted"})))))
      (catch IllegalArgumentException _
        (html-response request (ui/error-message "Invalid user ID") 400))
      (catch Exception e
        (log/error e "Error permanently deleting user")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

;; =============================================================================
;; Session Management Handlers
;; =============================================================================

(defn user-sessions-page-handler
  "Handler for user sessions management page (GET /web/users/:id/sessions).
   
   Displays all active sessions for a user with ability to revoke individual
   sessions or all sessions except current.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [user-id (get-in request [:path-params :id])
            _ (log/info "Sessions page handler - user-id:" user-id "path-params:" (:path-params request) "uri:" (:uri request))
            uuid (UUID/fromString user-id)
            user (user-ports/get-user-by-id user-service uuid)
            sessions (user-ports/get-user-sessions user-service uuid)
            _ (log/info "Sessions retrieved:" {:count (count sessions)
                                               :first-session (first sessions)
                                               :session-keys (when (seq sessions) (keys (first sessions)))})
            current-token (get-in request [:cookies "session-token" :value])
            opts {:user (:user request)
                  :current-time (current-time)
                  :zone-id (current-zone-id)
                  :flash (:flash request)}]
        (if user
          (html-response request (user-ui/user-sessions-page user sessions current-token opts))
          (html-response request
                         (layout/error-layout 404 "User Not Found" "The requested user could not be found.")
                         404)))
      (catch IllegalArgumentException e
        (log/error e "Invalid user ID format - user-id:" (get-in request [:path-params :id]) "path-params:" (:path-params request))
        (html-response request (ui/error-message "Invalid user ID") 400))
      (catch Exception e
        (log/error e "Error loading user sessions page")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

(defn revoke-session-handler
  "Handler for revoking a single session.

   Supports:
   - POST /web/sessions/revoke (preferred; token in form param)
   - POST /web/sessions/:token/revoke (legacy; token in path)
   
   Invalidates the specified session token, forcing that device to sign in again.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [session-token (or (get-in request [:form-params "session-token"])
                              (get-in request [:params :session-token])
                              (get-in request [:path-params :token]))
            user-id (get-in request [:form-params "user-id"])]
        (when (str/blank? (str session-token))
          (throw (ex-info "Missing session token" {:type :validation-error})))
        (user-ports/logout-user user-service session-token)
        (-> (response/redirect (str "/web/users/" user-id "/sessions"))
            (assoc :flash {:success "Session revoked successfully"})))
      (catch clojure.lang.ExceptionInfo e
        (if (= :validation-error (:type (ex-data e)))
          (html-response request (ui/error-message "Invalid session token") 400)
          (do
            (log/error e "Error revoking session")
            (html-response request (ui/error-message (.getMessage e)) 500))))
      (catch Exception e
        (log/error e "Error revoking session")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

(defn revoke-all-sessions-handler
  "Handler for revoking all user sessions (POST /web/users/:id/sessions/revoke-all).
   
   Invalidates all sessions for a user, optionally keeping the current session active.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [user-id (get-in request [:path-params :id])
            uuid (UUID/fromString user-id)
            keep-current? (= "true" (get-in request [:form-params "keep-current"]))
            current-token (when keep-current?
                            (get-in request [:cookies "session-token" :value]))]
        (if keep-current?
          ;; Revoke all except current
          (let [sessions (user-ports/get-user-sessions user-service uuid)]
            (doseq [session sessions]
              (when (not= (:token session) current-token)
                (user-ports/logout-user user-service (:token session)))))
          ;; Revoke all including current
          (user-ports/logout-user-everywhere user-service uuid))
        (-> (response/redirect (str "/web/users/" user-id "/sessions"))
            (assoc :flash {:success "Sessions revoked successfully"})))
      (catch IllegalArgumentException _
        (html-response request (ui/error-message "Invalid user ID") 400))
      (catch Exception e
        (log/error e "Error revoking all sessions")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

;; =============================================================================
;; Audit Trail Handlers
;; =============================================================================

(defn- parse-audit-filters
  "Parse audit-specific filter parameters from raw query params.

   Extracts action, result, target-email, and actor-email, ignoring blanks.

   Args:
     query-params: String-keyed map of query parameters from Ring

   Returns:
     Map with :action, :result, :target-email, :actor-email keys (non-blank only)"
  [query-params]
  (let [action       (get query-params "action")
        result       (get query-params "result")
        target-email (get query-params "target-email")
        actor-email  (get query-params "actor-email")]
    (cond-> {}
      (some-> action str/blank? not)       (assoc :action action)
      (some-> result str/blank? not)       (assoc :result result)
      (some-> target-email str/blank? not) (assoc :target-email target-email)
      (some-> actor-email str/blank? not)  (assoc :actor-email actor-email))))

(defn- build-audit-list-opts
  "Build list-audit-logs options from table query and search filters.

   Supports filtering by:
   - action: Audit action type (:create, :update, :delete, :login, etc.)
   - result: Audit result (:success or :failure)
   - target-email: Target user email (contains search)
   - actor-email: Actor email (contains search)

   Args:
     table-query: Parsed table query with limit/offset/sort/dir
     filters: Parsed search filters from query params

   Returns:
     Options map for list-audit-logs service call"
  [table-query filters]
  (let [base {:limit          (:limit table-query)
              :offset         (:offset table-query)
              :sort-by        (:sort table-query)
              :sort-direction (:dir table-query)}]
    (cond-> base
      (:action filters)
      (assoc :filter-action (keyword (:action filters)))

      (:result filters)
      (assoc :filter-result (keyword (:result filters)))

      (:target-email filters)
      (assoc :filter-target-email (:target-email filters))

      (:actor-email filters)
      (assoc :filter-actor-email (:actor-email filters)))))

(defn audit-page-handler
  "Handler for the audit trail page (GET /web/audit).
   
   Displays audit logs in a table with filtering, sorting, and pagination.
   Supports filters for action type, result, target email, and actor email.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [qp          (:query-params request)
            table-query (web-table/parse-table-query
                         qp
                         {:default-sort      :created-at
                          :default-dir       :desc
                          :default-page-size 50})
            filters     (parse-audit-filters qp)
            list-opts   (build-audit-list-opts table-query filters)
            audit-result (user-ports/list-audit-logs user-service list-opts)
            page-opts    {:user        (get request :user)
                          :flash       (get request :flash)
                          :table-query table-query
                          :filters     filters}]
        (html-response request
                       (user-ui/audit-page
                        (:audit-logs audit-result)
                        table-query
                        (:total-count audit-result)
                        filters
                        page-opts)))
      (catch Exception e
        (log/error e "Error in audit-page-handler")
        (html-response request
                       (layout/pilot-page-layout "Error"
                                                 (ui/error-message (.getMessage e)))
                       500)))))

(defn audit-table-fragment-handler
  "Handler for the audit table fragment (GET /web/audit/table).
   
   Returns only the audit table HTML for HTMX refresh when filters change.
   This enables client-side filtering without full page reload.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [qp          (:query-params request)
            table-query (web-table/parse-table-query
                         qp
                         {:default-sort      :created-at
                          :default-dir       :desc
                          :default-page-size 50})
            filters     (parse-audit-filters qp)
            list-opts   (build-audit-list-opts table-query filters)
            audit-result (user-ports/list-audit-logs user-service list-opts)
            _page-opts    {:table-query table-query
                           :filters     filters}]
        (html-response request
                       (user-ui/audit-logs-table
                        (:audit-logs audit-result)
                        table-query
                        (:total-count audit-result)
                        filters)))
      (catch Exception e
        (log/error e "Error in audit-table-fragment-handler")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

;; =============================================================================
;; Profile Page Handlers
;; =============================================================================

(defn profile-page-handler
  "Handler for the profile page (GET /web/profile).
   
   Displays user profile with all sections: info, preferences, security, MFA.
   
   Args:
     user-service: User service instance
     mfa-service: MFA service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service mfa-service _config]
  (fn [request]
    (try
      (let [current-user (:user request)
            user-id (:id current-user)
            hx-request? (some-> (get-in request [:headers "hx-request"])
                                str/lower-case
                                (= "true"))
            hx-target (get-in request [:headers "hx-target"])
            ;; Get fresh user data
            user (user-ports/get-user-by-id user-service user-id)
            ;; Get MFA status
            mfa-status (mfa/get-mfa-status mfa-service user-id)
            page-opts {:user current-user
                       :flash (:flash request)}]
        (if user
          (cond
            ;; HTMX fragment requests from profile cards (e.g. Cancel in edit form)
            (and hx-request? (= "#profile-info-card" hx-target))
            (html-response request (profile-ui/profile-info-fragment user))

            (and hx-request? (= "#preferences-card" hx-target))
            (html-response request (profile-ui/preferences-fragment user))

            :else
            (html-response request (profile-ui/profile-page user mfa-status page-opts)))
          (html-response request
                         (layout/error-layout 404 "Profile Not Found"
                                              "Your profile could not be loaded.")
                         404)))
      (catch Exception e
        (log/error e "Error in profile-page-handler")
        (html-response request
                       (layout/pilot-page-layout "Error"
                                                 (ui/error-message (.getMessage e)))
                       500)))))

(defn profile-edit-form-handler
  "Handler to show profile edit form (GET /web/profile/edit).
   
   HTMX handler that returns the edit form fragment.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [current-user (:user request)
            user-id (:id current-user)
            ;; Get fresh user data
            user (user-ports/get-user-by-id user-service user-id)]
        (if user
          (html-response request (profile-ui/profile-edit-form user))
          (html-response request (ui/error-message "User not found") 404)))
      (catch Exception e
        (log/error e "Error in profile-edit-form-handler")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

(defn profile-info-fragment-handler
  "Handler to return profile info card fragment (GET /web/profile/info)."
  [user-service _config]
  (fn [request]
    (try
      (let [current-user (:user request)
            user-id (:id current-user)
            user (user-ports/get-user-by-id user-service user-id)]
        (if user
          (html-response request (profile-ui/profile-info-fragment user))
          (html-response request (ui/error-message "User not found") 404)))
      (catch Exception e
        (log/error e "Error in profile-info-fragment-handler")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

(defn profile-edit-handler
  "Handler for profile edit submission (POST /web/profile).
   
   HTMX handler that updates profile and returns updated card.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [current-user (:user request)
            user-id (:id current-user)
            form-data (:form-params request)
            prepared-data {:name (get form-data "name")}
            [valid? validation-errors _] (validate-request-data user-schema/UpdateUserRequest prepared-data)]
        (if-not valid?
          ;; Return form with errors
          (html-response request
                         (profile-ui/profile-edit-form (merge current-user prepared-data) validation-errors)
                         400)
          (try
            ;; Get existing user and update
            (let [user (user-ports/get-user-by-id user-service user-id)
                  ;; Remove nil values to avoid schema validation errors for optional fields
                  clean-user (remove-nil-values user)
                  updated-user (user-ports/update-user-profile user-service
                                                               (merge clean-user prepared-data))]
              ;; Return success with updated info card
              (html-response request
                             (profile-ui/profile-info-fragment updated-user)))
            (catch Exception e
              (log/error e "Error updating profile")
              (html-response request (ui/error-message (.getMessage e)) 500)))))
      (catch Exception e
        (log/error e "Error in profile-edit-handler")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

(defn preferences-edit-form-handler
  "Handler to show preferences edit form (GET /web/profile/preferences/edit).
   
   HTMX handler that returns the edit form fragment.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [current-user (:user request)
            user-id (:id current-user)
            ;; Get fresh user data
            user (user-ports/get-user-by-id user-service user-id)]
        (if user
          (html-response request (profile-ui/preferences-edit-form user))
          (html-response request (ui/error-message "User not found") 404)))
      (catch Exception e
        (log/error e "Error in preferences-edit-form-handler")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

(defn preferences-fragment-handler
  "Handler to return preferences card fragment (GET /web/profile/preferences)."
  [user-service _config]
  (fn [request]
    (try
      (let [current-user (:user request)
            user-id (:id current-user)
            user (user-ports/get-user-by-id user-service user-id)]
        (if user
          (html-response request (profile-ui/preferences-fragment user))
          (html-response request (ui/error-message "User not found") 404)))
      (catch Exception e
        (log/error e "Error in preferences-fragment-handler")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

(defn preferences-edit-handler
  "Handler for preferences edit submission (POST /web/profile/preferences).
   
   HTMX handler that updates preferences and returns updated card.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [current-user (:user request)
            user-id (:id current-user)
            form-data (:form-params request)
            prepared-data {:date-format (keyword (get form-data "date-format"))
                           :time-format (keyword (get form-data "time-format"))
                           :language    (get form-data "language")}]
        (try
          ;; Get existing user and update preferences
          (let [user (user-ports/get-user-by-id user-service user-id)
                ;; Remove nil values to avoid schema validation errors for optional fields
                clean-user (remove-nil-values user)
                updated-user (user-ports/update-user-profile user-service
                                                             (merge clean-user prepared-data))]
            ;; Return success with updated preferences card
            (html-response request
                           (profile-ui/preferences-fragment updated-user)))
          (catch Exception e
            (log/error e "Error updating preferences")
            (html-response request (ui/error-message (.getMessage e)) 500))))
      (catch Exception e
        (log/error e "Error in preferences-edit-handler")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

;; =============================================================================
;; Password Change Handlers
;; =============================================================================

(defn password-change-form-handler
  "Handler to show password change form (GET /web/profile/password/form).
   
   HTMX handler that returns expanded password change card.
   
   Args:
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [_config]
  (fn [request]
    (html-response request (profile-ui/password-section-fragment true) 200 htmx-no-cache-headers)))

(defn password-section-fragment-handler
  "Handler to show collapsed password section fragment (GET /web/profile/password)."
  [_config]
  (fn [request]
    (html-response request (profile-ui/password-section-fragment false) 200 htmx-no-cache-headers)))

(defn password-change-handler
  "Handler for password change submission (POST /web/profile/password).
   
   HTMX handler that validates and changes password.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [current-user (:user request)
            user-id (:id current-user)
            form-data (:form-params request)
            current-password (get form-data "current-password")
            new-password (get form-data "new-password")
            confirm-password (get form-data "confirm-password")]
        ;; Validate passwords match
        (if (not= new-password confirm-password)
          (html-response request
                         (profile-ui/password-section-fragment true
                                                               {:confirm-password ["Passwords do not match"]})
                         400
                         htmx-no-cache-headers)
          (try
            ;; Call service to change password
            (user-ports/change-password user-service user-id current-password new-password)
            ;; Return success message
            (html-response request (profile-ui/password-change-success) 200 htmx-no-cache-headers)
            (catch clojure.lang.ExceptionInfo e
              (let [data (ex-data e)]
                (case (:type data)
                  :invalid-current-password
                  (html-response request
                                 (profile-ui/password-section-fragment true
                                                                       {:current-password ["Current password is incorrect"]})
                                 400
                                 htmx-no-cache-headers)
                  :password-policy-violation
                  (html-response request
                                 (profile-ui/password-section-fragment true
                                                                       {:new-password ["Password does not meet requirements"]})
                                 400
                                 htmx-no-cache-headers)
                  ;; Default error
                  (html-response request (ui/error-message (.getMessage e)) 500))))
            (catch Exception e
              (log/error e "Error changing password")
              (html-response request (ui/error-message (.getMessage e)) 500)))))
      (catch Exception e
        (log/error e "Error in password-change-handler")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

;; =============================================================================
;; MFA Web Handlers
;; =============================================================================

(defn mfa-setup-page-handler
  "Handler for MFA setup page (GET /web/profile/mfa/setup).
   
   Shows introduction and start button for MFA setup.
   
   Args:
     mfa-service: MFA service instance
     config: Application configuration map
     
    Returns:
     Ring handler function"
  [_mfa-service _config]
  (fn [request]
    (try
      (let [page-opts {:user (:user request)
                       :flash (:flash request)}]
        (html-response request (profile-ui/mfa-setup-page page-opts)))
      (catch Exception e
        (log/error e "Error in mfa-setup-page-handler")
        (html-response request
                       (layout/pilot-page-layout "Error"
                                                 (ui/error-message (.getMessage e)))
                       500)))))

(defn mfa-setup-initiate-handler
  "Handler for MFA setup initiation (POST /web/profile/mfa/setup).
   
   HTMX handler that generates QR code and returns setup form.
   
   Args:
     mfa-service: MFA service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [mfa-service _config]
  (fn [request]
    (try
      (let [current-user (:user request)
            user-id (:id current-user)
            ;; Call MFA service to generate setup data
            setup-result (mfa/setup-mfa mfa-service user-id)]
        (if (:success? setup-result)
          (let [{:keys [secret qr-code-url issuer account-name backup-codes]} setup-result]
            ;; Return QR code step (backup codes passed via hidden form field)
            (html-response request
                           (profile-ui/mfa-qr-code-step secret qr-code-url issuer account-name backup-codes)))
          ;; Error during setup
          (html-response request (ui/error-message (:error setup-result)) 500)))
      (catch Exception e
        (log/error e "Error in mfa-setup-initiate-handler")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

(defn mfa-verify-handler
  "Handler for MFA code verification (POST /web/profile/mfa/verify).
   
   HTMX handler that verifies TOTP code and enables MFA.
   
   Args:
     mfa-service: MFA service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [mfa-service config]
  (fn [request]
    (log/info "mfa-verify-handler called" {:mfa-service (type mfa-service)
                                           :has-config (boolean config)})
    (try
      (let [current-user (:user request)
            user-id (:id current-user)
            form-data (:form-params request)
            verification-code (get form-data "verification-code")
            ;; Get setup data from form (hidden fields)
            secret (get form-data "secret")
            backup-codes-str (get form-data "backup-codes")
            backup-codes (when backup-codes-str
                           (try
                             (edn/read-string backup-codes-str)
                             (catch Exception e
                               (log/warn "Failed to parse backup codes" {:error (.getMessage e)})
                               nil)))]
        (log/info "MFA verify request" {:user-id user-id
                                        :verification-code verification-code
                                        :has-secret? (boolean secret)
                                        :has-backup-codes? (boolean backup-codes)
                                        :backup-codes-count (when backup-codes (count backup-codes))})
        (if (and secret backup-codes)
          (let [;; Call MFA service to enable MFA
                enable-result (mfa/enable-mfa mfa-service user-id secret backup-codes verification-code)]
            (log/info "MFA enable result" {:success? (:success? enable-result)
                                           :error (:error enable-result)})
            (if (:success? enable-result)
              ;; Success - show backup codes directly (HTMX response)
              (html-response request
                             (profile-ui/mfa-backup-codes-display backup-codes false
                                                                  {:user current-user
                                                                   :flash {:success "Two-factor authentication enabled successfully"}}))
              ;; Invalid code - regenerate QR code data URL from the submitted secret
              (let [issuer (get-in mfa-service [:config :issuer] "Wagoe Framework")
                    account-name (:email current-user)
                    totp-uri (mfa/generate-totp-uri secret account-name issuer)
                    qr-code-url (mfa/generate-qr-code-data-url totp-uri)]
                (html-response request
                               (profile-ui/mfa-qr-code-step secret qr-code-url issuer account-name backup-codes
                                                            {:verification-code ["Invalid verification code. Please try again."]})
                               400))))
          ;; No setup data in form
          (html-response request (ui/error-message "MFA setup data missing. Please start again.") 400)))
      (catch Exception e
        (log/error e "Error in mfa-verify-handler")
        (html-response request (ui/error-message (.getMessage e)) 500)))))

(defn mfa-backup-codes-page-handler
  "Handler for backup codes display page (GET /web/profile/mfa/backup-codes).
   
   Shows backup codes after MFA enable or regeneration.
   
   Args:
     mfa-service: MFA service instance
     config: Application configuration map
     
    Returns:
     Ring handler function"
  [_mfa-service _config]
  (fn [request]
    (try
      (let [flash (:flash request)
            backup-codes (:mfa-backup-codes flash)
            page-opts {:user (:user request)
                       :flash flash}]
        (if backup-codes
          (html-response request
                         (profile-ui/mfa-backup-codes-display backup-codes true page-opts))
          ;; No backup codes in flash - redirect to profile
          (response/redirect "/web/profile")))
      (catch Exception e
        (log/error e "Error in mfa-backup-codes-page-handler")
        (html-response request
                       (layout/pilot-page-layout "Error"
                                                 (ui/error-message (.getMessage e)))
                       500)))))

(defn mfa-disable-page-handler
  "Handler for MFA disable confirmation page (GET /web/profile/mfa/disable).
   
   Shows password confirmation form to disable MFA.
   
   Args:
     mfa-service: MFA service instance
     config: Application configuration map
     
    Returns:
     Ring handler function"
  [_mfa-service _config]
  (fn [request]
    (try
      (let [page-opts {:user (:user request)
                       :flash (:flash request)}]
        (html-response request (profile-ui/mfa-disable-confirm-page {} page-opts)))
      (catch Exception e
        (log/error e "Error in mfa-disable-page-handler")
        (html-response request
                       (layout/pilot-page-layout "Error"
                                                 (ui/error-message (.getMessage e)))
                       500)))))

(defn mfa-disable-handler
  "Handler for MFA disable submission (POST /web/profile/mfa/disable).
   
   Verifies password and disables MFA.
   
   Args:
     user-service: User service instance
     mfa-service: MFA service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service mfa-service _config]
  (fn [request]
    (try
      (let [current-user (:user request)
            user-id (:id current-user)
            form-data (:form-params request)
            password (get form-data "password")
            ;; Verify password first
            user (user-ports/get-user-by-id user-service user-id)]
        (if (auth/verify-password password (:password-hash user))
          ;; Password correct - disable MFA
          (let [disable-result (mfa/disable-mfa mfa-service user-id)]
            (if (:success? disable-result)
              (-> (response/redirect "/web/profile")
                  (assoc :flash {:success "Two-factor authentication has been disabled"}))
              (html-response request
                             (profile-ui/mfa-disable-confirm-page {:password [(:error disable-result)]})
                             400)))
          ;; Password incorrect
          (html-response request
                         (profile-ui/mfa-disable-confirm-page {:password ["Incorrect password"]})
                         400)))
      (catch Exception e
        (log/error e "Error in mfa-disable-handler")
        (html-response request (ui/error-message (.getMessage e)) 500)))))
