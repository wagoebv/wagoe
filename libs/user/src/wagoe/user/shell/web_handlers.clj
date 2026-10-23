(ns wagoe.user.shell.web-handlers
  "Web UI handlers for user module.

   This namespace implements Ring handlers that return HTML responses
   for user-related web pages, either full pages or HTMX partial updates.

   Handler Categories:
   - Page Handlers: Return full HTML pages for initial browser requests
   - HTMX Handlers: Return HTML fragments for dynamic updates

   All handlers use the shared UI components and user shell services."
  (:require [wagoe.core.utils.text :as text]
            [wagoe.config :as wagoe-config]
            [wagoe.core.utils.redirect :as redirect]
            [wagoe.core.validation :as cv]
            [wagoe.i18n.shell.middleware :as i18n-middleware]
            [wagoe.i18n.shell.render :as i18n]
            [wagoe.shared.ui.core.components :as ui]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.shared.ui.core.form :as form]
            [wagoe.shared.ui.core.htmx :as htmx]
            [wagoe.shared.ui.core.toast :as toast]
            [wagoe.platform.core.http.access :as access]
            [wagoe.platform.core.http.client-ip :as client-ip]
            [wagoe.platform.shell.web.table :as web-table]
            [wagoe.user.core.authentication :as auth-core]
            [wagoe.user.core.oidc :as oidc-core]
            [wagoe.user.core.password-policy :as password-policy]
            [wagoe.user.core.ui :as user-ui]
            [wagoe.user.core.user :as user-core]
            [wagoe.user.core.profile-ui :as profile-ui]
            [wagoe.user.ports :as user-ports]
            [wagoe.user.schema :as user-schema]
            [wagoe.email.ports :as email-ports]
            [wagoe.user.shell.landing :as landing]
            [wagoe.user.shell.middleware :as user-middleware]
            [wagoe.user.shell.mfa :as mfa]
            [wagoe.user.shell.mfa-enrolment :as enrolment]
            [wagoe.user.shell.oidc :as oidc]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.util :as mu]
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
   local development over plain HTTP.

   Through `app-config`, not a `get-in` of its own: the config handed to these
   handlers is the whole loaded map, and reading `[:wagoe/settings ...]`
   straight off it missed every time, so the fail-secure default won even where
   the dev profile said false (BOU-477)."
  [config]
  (get (wagoe-config/app-config config) :secure-cookies? true))

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
  (if (redirect/local-path? url) url default))

(defn- display-password-policy
  "The password rules the create-user form should list.

   The configured policy, plus one rule config cannot relax:
   `CreateUserRequest` requires at least 8 characters whatever the config says,
   so the dev config's `:min-length 6` never applies and the form must not
   advertise it. Both validators now read the same policy (BOU-388), so there
   is nothing else to reconcile."
  [config]
  (let [policy         (password-policy/normalize
                        (:password-policy (wagoe-config/user-validation-config config)))
        schema-minimum 8]
    {:min-length            (max (:min-length policy) schema-minimum)
     :max-length            (min (:max-length policy) 255)
     :require-uppercase     (:require-uppercase? policy)
     :require-lowercase     (:require-lowercase? policy)
     :require-numbers       (:require-numbers? policy)
     :require-special-chars (:require-special-chars? policy)}))

(def ^:private form-schema
  "`schema` without its `:error/message`s. Those are English and the API
   answers with them; a form shows the i18n markers `form/errors` derives from
   the constraints instead."
  (memoize
   (fn [schema]
     (m/walk schema (m/schema-walker
                     #(if (:error/message (m/properties %))
                        (mu/update-properties % dissoc :error/message)
                        %))))))

(defn- request-errors
  "{field [marker]} for `data` that failed `schema`. A blank or absent field
   is checked as missing, so it reads Required rather than \"at least 1
   characters\". A blank optional field keeps its own error."
  [schema data]
  (let [schema  (form-schema schema)
        present (into {} (remove (fn [[_ v]] (or (nil? v) (and (string? v) (str/blank? v))))) data)]
    (merge (form/errors schema data)
           (form/errors schema present))))

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
      [false (request-errors schema transformed) transformed])))

(defn- current-time
  "Return the shell-controlled current time for rendering relative timestamps."
  []
  (Instant/now))

(defn- current-zone-id
  "Return the shell-controlled zone id for rendering presentation-only dates."
  []
  (java.time.ZoneId/systemDefault))

(defn html-response
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

(defn error-page
  "The page a browser sees in place of an error body on a /web request:
   platform's `:error-page`, which this module supplies."
  [request error]
  (:body (html-response request
                        (user-ui/error-page (assoc error :user (:user request)))
                        (:status error))))

;; An exception's message can carry driver, SQL or config detail: it goes to
;; the log, and the page says something generic (BOU-552, BOU-555).

(defn- server-error
  "Log `e` and answer 500 with the generic error fragment."
  [request e log-message]
  (log/error e log-message)
  (html-response request (ui/error-message [:t :common/error-generic]) 500))

(defn- server-error-page
  "As `server-error`, as a full page."
  [request e log-message]
  (log/error e log-message)
  (html-response request
                 (layout/pilot-page-layout [:t :common/error-title] (ui/error-message [:t :common/error-generic]))
                 500))

(defn- toast-text
  "`message`, a string or a catalogue key, as text for `request`."
  [request message]
  (let [t (or (i18n-middleware/resolve-t-fn request) (:i18n/t request) name)]
    (if (keyword? message) (t message) message)))

(defn- redirect-with-toast [request location type message]
  (toast/redirect-with-toast location type (toast-text request message)))

(defn- with-error-toast [response request message]
  (toast/with-error-toast response (toast-text request message)))

(defn- user-facing-message
  "The message of an ex-info whose type says its text was written for the
   user; nil for anything else."
  [e]
  (when (contains? #{:bad-request :user-exists} (:type (ex-data e)))
    (ex-message e)))

(defn- refused
  "A refused write (BOU-665): 409, saying why, in the body and as a toast
   for a request whose body htmx does not swap."
  [request e]
  (let [why (or (:message-key (ex-data e)) (ex-message e))]
    (-> (html-response request (ui/error-message (if (keyword? why) [:t why] why)) 409)
        (with-error-toast request why))))

(defn- mfa-error-message
  "What to show for a failed MFA result. `:cannot-*` carries the core's reason;
   the `:mfa-*-failed` types mean an exception, which mfa.clj has logged."
  [result]
  (let [{:keys [type message]} (:error result)]
    (if (contains? #{:cannot-enable :cannot-disable} type)
      message
      [:t :common/error-generic])))

(defn- field-errors
  "`{field [message …]}` from a :validation-error's `:errors`, for a form."
  [errors]
  (reduce (fn [m {:keys [field message]}]
            (update m (if (sequential? field) (first field) field) (fnil conj []) message))
          {} errors))

(def ^:private htmx-no-cache-headers
  {"Cache-Control" "no-store, no-cache, must-revalidate, max-age=0"
   "Pragma"        "no-cache"
   "Expires"       "0"
   "Vary"          "HX-Request"})

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

(defn- login-context [request]
  {:ip-address (client-ip/request-ip request)
   :user-agent (get-in request [:headers "user-agent"])})

(def ^:private secret-fields ["password" "mfa-code" :password :mfa-code])

(defn- without-secrets
  "`request` minus the submitted password and MFA code: the sign-in hook gets
   the request, and should not be one log line away from a plaintext password."
  [request]
  (reduce (fn [r k] (cond-> r (map? (get r k)) (update k #(apply dissoc % secret-fields))))
          request
          [:form-params :params :multipart-params]))

(defn- landing-path
  "Where `user`, signing in with `request`, lands."
  [config user request]
  (landing/path-for config user (assoc (login-context request) :request (without-secrets request))))

(defn web-root-page-handler
  "Handler for the web root landing page (GET /web).

   Shows logo, welcome message and login link for unauthenticated users.
   Authenticated users (valid session cookie present) are redirected to their
   landing page. With `:landing` set, everyone is redirected there.

   Args:
     user-service: User service instance for session validation
     config: Application configuration map

   Returns:
     Ring handler function"
  [user-service config]
  (if-let [path (landing/front-page config)]
    (constantly (response/redirect path))
    (fn [request]
      (let [session-token (user-middleware/extract-session-token request)
            session (when session-token
                      (try
                        (user-ports/validate-session user-service session-token)
                        (catch Exception _ nil)))]
        (if session
          (response/redirect (landing-path config (:user session) request))
          (html-response request (user-ui/web-root-page {})))))))

(defn- users-list
  "The users table's users, table query, total and filters for `query-params`."
  [user-service query-params]
  (let [table-query (web-table/parse-table-query
                     query-params
                     {:default-sort      :created-at
                      :default-dir       :desc
                      :default-page-size 20})
        filters     (web-table/parse-search-filters query-params)
        result      (user-ports/list-users user-service (build-user-list-opts table-query filters))]
    [(:users result) table-query (:total-count result) filters]))

(defn users-page-handler
  "Handler for the users listing page (GET /web/users), with shared search
   filters (q, role, status). The table's controls request this URL and push
   it, so the address bar holds sort, filters and page; they get the table
   fragment back."
  [user-service _config]
  (fn [request]
    (try
      (let [[users table-query total-count filters] (users-list user-service (:query-params request))]
        ;; One URL, two bodies: a cache must not answer one with the other
        (-> (html-response request
                           (if (htmx/fragment-request? (:headers request) ["users-table-container"])
                             (user-ui/users-table-fragment users table-query total-count filters)
                             (user-ui/users-page users total-count
                                                 {:user        (:user request)
                                                  :flash       (:flash request)
                                                  :table-query table-query
                                                  :filters     filters})))
            (response/header "Vary" "HX-Request, HX-Target")))
      (catch Exception e
        (if (htmx/fragment-request? (:headers request) ["users-table-container"])
          (server-error request e "Error in users-page-handler")
          (server-error-page request e "Error in users-page-handler"))))))

(defn user-detail-redirect
  "GET /web/users/:id, the user's page before the admin's edit page replaced
   it: a redirect there, so old links keep working. Not an id: the list."
  [request]
  (response/redirect (str "/web/admin/users"
                          (some->> (parse-uuid (get-in request [:path-params :id])) (str "/")))))

(defn create-user-page-handler
  "Handler for the create user page (GET /web/users/new).

   Args:
     config: Application configuration map

   Returns:
     Ring handler function"
  [config]
  (fn [request]
    (let [raw-return-to (get-in request [:query-params "return-to"])
          return-to (safe-return-url raw-return-to "/web/admin/users")
          page-opts {:user (get request :user)
                     :flash (get request :flash)
                     :return-to return-to
                     ;; Same set the failed POST lists, so the rules do not
                     ;; change between opening the form and submitting it.
                     :password-policy (display-password-policy config)}]
      (html-response request (user-ui/create-user-page {} {} page-opts)))))

;; =============================================================================
;; Authentication Handlers (Login / Logout)
;; =============================================================================

(defn- sign-in-opts
  "Page options every sign-in page takes, the app's `:login-layout` among them
   with the request it may want to read, minus the password and MFA code. The
   OIDC pages take the 2-arity: they never rendered the user or a flash."
  ([config request]
   (let [app-layout (get-in config [:active :wagoe/user :login-layout])]
     (cond-> {:logo-url (get-in config [:wagoe/settings :logo-url])}
       app-layout (assoc :login-layout app-layout :request (without-secrets request)))))
  ([config request return-to]
   (assoc (sign-in-opts config request)
          :user      (get request :user)
          :flash     (get request :flash)
          :return-to return-to)))

(defn- login-branding
  "Login page options every rendering of it shares."
  [opts config]
  (assoc opts
         :registration?  (landing/registration? config)
         :oidc-providers (oidc-core/login-buttons (get-in config [:active :wagoe/user :oidc :providers]))))

(defn- session-cookie [config session-token]
  {:value session-token :http-only true :secure (secure-cookies? config) :same-site :strict :path "/"})

(def ^:private enrolment-cookie "mfa-enrolment")

(defn- enrolment-ticket-cookie [config value]
  {:value value :http-only true :secure (secure-cookies? config) :same-site :strict
   :path user-ui/mfa-enrolment-path :max-age enrolment/ttl-seconds})

(def ^:private enrolment-cookie-cleared {:value "" :max-age 0 :path user-ui/mfa-enrolment-path})

(defn- enrolment-needed?
  "Whether a refused sign-in was refused only for want of a second factor,
   the password being right."
  [auth-result]
  (and (= :mfa-enrolment-required (:reason auth-result)) (:user auth-result)))

(defn- start-enrolment
  "To the second-factor setup, with the ticket that proves the password."
  [config auth-result return-to]
  (-> (response/redirect user-ui/mfa-enrolment-path :see-other)
      (assoc-in [:cookies enrolment-cookie]
                (enrolment-ticket-cookie config (enrolment/ticket (:user auth-result)
                                                                  (:password-fingerprint auth-result)
                                                                  (when (redirect/local-path? return-to) return-to))))))

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
          page-opts (login-branding (sign-in-opts config request return-to) config)]
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
                         :mfa-code (get form-data "mfa-code")}
          prepared-data (merge prepared-data (login-context request))
          [valid? validation-errors validated-data]
          (validate-request-data user-schema/LoginRequest prepared-data)
          page-opts (login-branding (sign-in-opts config request raw-return-to) config)]
      (if-not valid?
        ;; Re-render login page with validation errors, preserving return-to
        (html-response request
                       (user-ui/login-page prepared-data validation-errors page-opts)
                       400)
        (try
          ;; Use IUserService/authenticate-user with validated data
          (let [auth-result (user-ports/authenticate-user user-service validated-data)
                user (:user auth-result)
                return-to (when (:authenticated auth-result)
                            (safe-return-url raw-return-to (landing-path config user request)))]
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
                          {:user-id (:id user)
                           :remember? remember?
                           :cookie-max-age cookie-max-age
                           :return-to return-to
                           :user-role (:role user)})
                  ;; Set session-token cookie and redirect to original URL or default
                (-> (response/redirect return-to)
                    (assoc-in [:cookies "session-token"]
                              (cond-> (session-cookie config session-token)
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

              (enrolment-needed? auth-result)
              (start-enrolment config auth-result raw-return-to)

              ;; MFA required - show MFA code input
              (:requires-mfa? auth-result)
              (do
                (log/info "MFA code required for login" {:email (:email prepared-data)})
                (html-response request
                               (user-ui/mfa-login-page prepared-data {} page-opts)
                               200))

              ;; Authentication failed (e.g. wrong password or invalid MFA code)
              :else
              (let [error-message (cond
                                    ;; Only a lockout carries :retry-after, whatever its :reason.
                                    (:retry-after auth-result)
                                    (let [n (auth-core/minutes-locked (:retry-after auth-result) (current-time))]
                                      [:t :user/error-locked {:n n} n])

                                    (= :mfa-verification-failed (:reason auth-result))
                                    "Invalid MFA code"

                                    (= :mfa-enrolment-required (:reason auth-result))
                                    [:t :user/mfa-enrolment-required]

                                    :else
                                    "Invalid email or password")]
                (log/warn "Login failed" {:email (:email prepared-data)
                                          :reason (:reason auth-result)
                                          :message (:message auth-result)})
                (html-response request
                               (if (:mfa-code prepared-data)
                   ;; If MFA code was provided, show MFA page with error
                                 (user-ui/mfa-login-page prepared-data {:mfa-code [error-message]} page-opts)
                   ;; Otherwise show regular login page
                                 (user-ui/login-page prepared-data {:password [error-message]} page-opts))
                               401))))
          (catch Exception e
            (log/error e "Login error" {:email (:email prepared-data)})
            (html-response request
                           (layout/pilot-page-layout "Login error"
                                                     (ui/error-message [:t :common/error-generic]))
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

;; =============================================================================
;; Second-factor setup before signing in
;; =============================================================================

(defn- read-backup-codes
  "The backup codes the setup form carries back, or nil unless they are a
   vector of strings."
  [s]
  (when s
    (try (let [codes (edn/read-string s)]
           (when (and (vector? codes) (every? string? codes)) codes))
         (catch Exception e
           (log/warn "Failed to parse backup codes" {:error (.getMessage e)})
           nil))))

(defn- enrolment-claims [mfa-service request]
  (enrolment/claims (get-in request [:cookies enrolment-cookie :value]) (:user-repository mfa-service)))

(defn- enrolment-expired
  "Back to sign in: the ticket is gone, or the second factor is on already."
  []
  (-> (response/redirect "/web/login" :see-other)
      (assoc-in [:cookies enrolment-cookie] enrolment-cookie-cleared)))

(defn mfa-enrolment-page-handler
  "GET /web/login/mfa-setup - the authenticator setup for a user the app
   requires a second factor of, who has none."
  [mfa-service config]
  (fn [request]
    (let [claims (enrolment-claims mfa-service request)
          setup  (when claims (mfa/setup-mfa mfa-service (:user-id claims)))]
      (if (:success? setup)
        (html-response request
                       (user-ui/mfa-enrolment-page setup {} (sign-in-opts config request (:return-to claims))))
        (enrolment-expired)))))

(defn mfa-enrolment-submit-handler
  "POST /web/login/mfa-setup - check the first code and turn the second factor
   on. The user then signs in with it."
  [mfa-service config]
  (fn [request]
    (if-let [claims (enrolment-claims mfa-service request)]
      (let [form         (:form-params request)
            secret       (get form "secret")
            ;; Parsed only once the ticket admits the caller: the route is public.
            backup-codes (read-backup-codes (get form "backup-codes"))
            opts         (sign-in-opts config request (:return-to claims))
            ;; A plain form post, so a whole page rather than a fragment
            error-page   (fn [message status]
                           (html-response request
                                          (layout/pilot-page-layout [:t :common/error-title]
                                                                    (ui/error-message message))
                                          status))]
        (if-not (and secret backup-codes)
          (error-page [:t :user/error-mfa-setup-missing] 400)
          (let [result (mfa/enable-mfa mfa-service (:user-id claims) secret backup-codes
                                       (get form "verification-code"))]
            (cond
              (:success? result)
              (do (mfa/audit-enrolment! mfa-service (:user-id claims) (:email claims) (login-context request))
                  (-> (html-response request (user-ui/mfa-enrolment-done-page backup-codes opts))
                      (assoc-in [:cookies enrolment-cookie] enrolment-cookie-cleared)))

              (= :invalid-code (get-in result [:error :type]))
              (html-response request
                             (user-ui/mfa-enrolment-page
                              (mfa/setup-view mfa-service secret (:email claims) backup-codes)
                              {:verification-code [[:t :user/mfa-enrolment-invalid-code]]}
                              opts)
                             400)

              ;; Already on, from another tab, or the account is gone
              (= :cannot-enable (get-in result [:error :type]))
              (enrolment-expired)

              :else
              (error-page (mfa-error-message result) 500)))))
      (enrolment-expired))))

;; =============================================================================
;; OpenID Connect sign-in
;; =============================================================================

(def ^:private oidc-pending-cookie "oidc-pending")
(def ^:private oidc-mfa-cookie "oidc-mfa")

(defn- oidc-cookie
  "A sealed sign-in cookie. Lax, because the provider's redirect back is a
   cross-site navigation and a Strict cookie would not come with it."
  [config value]
  {:value value :http-only true :secure (secure-cookies? config) :same-site :lax
   :path "/web/auth/oidc" :max-age oidc/pending-ttl-seconds})

(def ^:private oidc-cookie-cleared {:value "" :max-age 0 :path "/web/auth/oidc"})

(defn- clear-oidc-cookies [response]
  (-> response
      (assoc-in [:cookies oidc-pending-cookie] oidc-cookie-cleared)
      (assoc-in [:cookies oidc-mfa-cookie] oidc-cookie-cleared)))

(defn- oidc-failure
  "The login page again, saying why, for an :oidc/* error."
  [request config {:keys [type] :as error}]
  (log/warn "OIDC sign-in failed" (dissoc error :message))
  (let [[status message] (case type
                           :not-found                 [404 [:t :user/oidc-failed]]
                           :oidc/no-account           [403 [:t :user/oidc-no-account]]
                           :oidc/admin-link-refused   [403 [:t :user/oidc-admin-link-refused]]
                           :oidc/account-unavailable  [403 [:t :user/oidc-account-unavailable]]
                           :oidc/mfa-enrolment-required [403 [:t :user/mfa-enrolment-required]]
                           (:oidc/http-error :oidc/discovery-failed :oidc/token-exchange-failed)
                           [502 [:t :user/oidc-failed]]
                           [400 [:t :user/oidc-failed]])]
    (-> (html-response request
                       (user-ui/login-page {} {} (-> (sign-in-opts config request)
                                                     (login-branding config)
                                                     (assoc :error message)))
                       status)
        clear-oidc-cookies)))

(defn- oidc-signed-in
  "Set the session cookie exactly as password login does, and move on."
  [request config {:keys [user session return-to]}]
  (let [location (safe-return-url return-to (landing-path config user request))]
    (-> (html-response request (user-ui/oidc-continue-page location (sign-in-opts config request)) 200
                       {"Refresh" (str "0;url=" location)})
        (assoc-in [:cookies "session-token"] (session-cookie config (:session-token session)))
        clear-oidc-cookies)))

(defn- oidc-outcome [request config result]
  (cond
    (:error result)
    (oidc-failure request config (:error result))

    (= :mfa-required (get-in result [:ok :outcome]))
    (-> (html-response request (user-ui/oidc-mfa-page {} (sign-in-opts config request)))
        clear-oidc-cookies
        ;; Strict: only the same-site MFA form posts it back.
        (assoc-in [:cookies oidc-mfa-cookie]
                  (assoc (oidc-cookie config (get-in result [:ok :cookie])) :same-site :strict)))

    :else
    (oidc-signed-in request config (:ok result))))

(defn oidc-start-handler
  "GET /web/auth/oidc/:provider/start - send the browser to the provider."
  [oidc-client config]
  (fn [request]
    (let [return-to (get-in request [:query-params "return-to"])
          result    (oidc/start oidc-client (get-in request [:path-params :provider])
                                (when (redirect/local-path? return-to) return-to))]
      (if (:error result)
        (oidc-failure request config (:error result))
        (-> (response/redirect (get-in result [:ok :location]))
            (assoc-in [:cookies oidc-pending-cookie] (oidc-cookie config (get-in result [:ok :cookie]))))))))

(defn oidc-callback-handler
  "GET /web/auth/oidc/:provider/callback - finish the sign-in."
  [oidc-client config]
  (fn [request]
    (try
      (oidc-outcome request config
                    (oidc/callback oidc-client
                                   (get-in request [:path-params :provider])
                                   (:query-params request)
                                   (get-in request [:cookies oidc-pending-cookie :value])
                                   (login-context request)))
      (catch Exception e
        (server-error-page request e "OIDC callback error")))))

(defn oidc-mfa-handler
  "POST /web/auth/oidc/mfa - the second factor after a provider sign-in."
  [oidc-client config]
  (fn [request]
    (try
      (let [result (oidc/complete-mfa oidc-client
                                      (get-in request [:cookies oidc-mfa-cookie :value])
                                      (get-in request [:form-params "mfa-code"])
                                      (login-context request))]
        (if (= :oidc/mfa-failed (get-in result [:error :type]))
          (html-response request
                         (user-ui/oidc-mfa-page {:mfa-code [[:t :user/oidc-mfa-invalid]]}
                                                (sign-in-opts config request))
                         401)
          (oidc-outcome request config result)))
      (catch Exception e
        (server-error-page request e "OIDC MFA error")))))

(defn register-page-handler
  "GET /web/register - render self-service registration page."
  [config]
  (fn [request]
    (let [page-opts (sign-in-opts config request (get-in request [:query-params "return-to"]))]
      (html-response request (user-ui/register-page {} {} page-opts)))))

(defn- error-field-key
  "The form keys errors by field keyword, but a service :validation-error mixes
   two shapes: business rules report `:field :password`, while schema failures
   come from `humanized-errors->error-maps` as a path vector, `:field
   [:password]`. Keyed as-is, the vector ones land under `[:password]` and the
   form — which looks up `:password` — renders nothing at all."
  [field]
  (cond
    (keyword? field)             field
    (and (sequential? field)
         (keyword? (last field))) (last field)
    :else                        :form))

(defn- error-message
  "The i18n marker for a service error or password-policy violation, by its
   :code. An unknown code keeps its own :message."
  [{:keys [code params message]}]
  (case code
    (:missing-required-field :blank :role-required) [:t :validation/required]
    :too-short                     [:t :validation/too-short params]
    :too-long                      [:t :validation/too-long params]
    :schema-validation-failed      [:t :validation/invalid]
    :invalid-role                  [:t :validation/not-an-option]
    (:role-not-allowed :role-creation-restricted) [:t :user/error-role-not-allowed]
    :missing-uppercase             [:t :user/error-password-uppercase]
    :missing-lowercase             [:t :user/error-password-lowercase]
    :missing-number                [:t :user/error-password-number]
    :missing-special-char          [:t :user/error-password-special-char]
    :common-password               [:t :user/error-password-common]
    :contains-email                [:t :user/error-password-contains-email]
    :password-policy-violation     [:t :user/error-password-policy]
    :invalid-characters            [:t :user/error-name-invalid-characters]
    :invalid-domain                [:t :user/error-email-domain]
    :admin-email-domain-restricted [:t :user/error-email-admin-domain]
    message))

(defn- service-errors->field-errors
  "Group a service :validation-error's `:errors` — a vector of
   {:field :code :message} — into the field -> messages map the form takes.
   Anything without a usable field lands under :form, which the form renders as
   an unattached block. A password-policy refusal shows each rule it broke."
  [errors]
  (reduce (fn [acc {:keys [field violations] :as error}]
            (update acc (error-field-key field) (fnil into [])
                    (map error-message) (or (seq violations) [error])))
          {}
          errors))

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
          (validate-request-data user-schema/CreateUserRequest prepared-data)
          rerender (fn [errors status]
                     (html-response request
                                    (user-ui/register-page prepared-data errors
                                                           (sign-in-opts config request raw-return-to))
                                    status))
          ;; The exception message can carry driver or config detail; it goes
          ;; to the log, not the page (BOU-552).
          failed (fn [e]
                   (log/error e "Registration failed")
                   (html-response request
                                  (layout/pilot-page-layout "Registration error"
                                                            (ui/error-message [:t :user/register-error-generic]))
                                  500))]
      (if-not valid?
        (rerender validation-errors 400)
        (try
          (let [user-result (user-ports/register-user user-service prepared-data)
                ;; Automatically authenticate the newly registered user
                auth-data {:email (:email user-result)
                           :password (get prepared-data :password)
                           ;; The account was made just now: it may set up a
                           ;; second factor the app requires with the password.
                           :registration? true
                           :remember false
                           :ip-address (client-ip/request-ip request)
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
                    (assoc-in [:cookies "session-token"] (session-cookie config session-token))))
              (if (enrolment-needed? auth-result)
                (start-enrolment config auth-result raw-return-to)
                (response/redirect "/web/login"))))
          ;; The service applies the configured password policy, which the
          ;; request schema does not know, so its refusal is a form error too
          ;; (BOU-552).
          (catch clojure.lang.ExceptionInfo e
            (case (:type (ex-data e))
              :validation-error
              (rerender (service-errors->field-errors (:errors (ex-data e))) 400)

              ;; 409 as the API answers. Worded not to confirm the account,
              ;; as login does not either.
              :user-exists
              (rerender {:email [[:t :user/register-email-unavailable]]} 409)

              (failed e)))
          (catch Exception e
            (failed e)))))))

;; =============================================================================
;; HTMX Fragment Handlers
;; =============================================================================
(defn users-table-fragment-handler
  "Handler for the users table fragment (GET /web/users/table), for callers
   that ask for the table alone. The table's own controls request /web/users."
  [user-service _config]
  (fn [request]
    (try
      (html-response request
                     (apply user-ui/users-table-fragment (users-list user-service (:query-params request))))
      (catch Exception e
        (server-error request e "Error in users-table-fragment-handler")))))

(defn bulk-update-users-htmx-handler
  "HTMX handler for bulk user operations (POST /web/users/bulk).

   Supports multiple actions: deactivate, activate, delete, role changes.
   Returns the updated users table fragment using the same query
   parameters and filters as the current view."
  [user-service _config]
  (fn [request]
    (try
      (let [form-params (:form-params request)
            ids-param   (get form-params "ids[]")
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
                               (select-keys form-params ["q" "role" "status" "sort" "dir" "page" "page-size"]))]
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
          (doseq [id-str ids
                  :let [uuid (UUID/fromString id-str)]
                  :when (user-ports/get-user-by-id user-service uuid)]
            (user-ports/update-user-profile user-service {:id uuid :active true}))

          "delete"
          (doseq [id-str ids]
            (let [uuid (UUID/fromString id-str)]
              (user-ports/delete-user user-service uuid)))

          "role-admin"
          (doseq [id-str ids
                  :let [uuid (UUID/fromString id-str)]
                  :when (user-ports/get-user-by-id user-service uuid)]
            (user-ports/update-user-profile user-service {:id uuid :role :admin}))

          "role-user"
          (doseq [id-str ids
                  :let [uuid (UUID/fromString id-str)]
                  :when (user-ports/get-user-by-id user-service uuid)]
            (user-ports/update-user-profile user-service {:id uuid :role :user}))

          (throw (ex-info "Unsupported bulk action"
                          {:type :bad-request
                           :action action})))
        (html-response request (apply user-ui/users-table-fragment (users-list user-service qp))))
      (catch IllegalArgumentException _
        (-> (html-response request (ui/error-message [:t :user/error-invalid-user-id-bulk]) 400)
            (with-error-toast request :user/error-invalid-user-id-bulk)))

      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (cond
            (= :bad-request (:type data)) (-> (html-response request (ui/error-message (user-facing-message e)) 400)
                                              (with-error-toast request (user-facing-message e)))
            (user-core/refusal? e)        (refused request e)
            :else                         (-> (server-error request e "Bulk user operation failed")
                                              (with-error-toast request :common/error-generic)))))

      (catch Exception e
        (-> (server-error request e "Unexpected error in bulk-update-users-htmx-handler")
            (with-error-toast request :common/error-generic))))))

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
          [valid? validation-errors _] (validate-request-data user-schema/CreateUserRequest prepared-data)
          password-policy (display-password-policy config)
          ;; nil violations lets the form derive them from the password it is
          ;; handed, which is what the requirements list has to reflect.
          rerender (fn [errors violations]
                     (html-response request
                                    (user-ui/create-user-form prepared-data errors violations
                                                              password-policy
                                                              {:return-to return-to})
                                    400))]
      (if-not valid?
        (rerender validation-errors nil)
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
          (catch clojure.lang.ExceptionInfo e
            ;; A duplicate email and a password the policy rejects both pass the
            ;; request schema and are refused by the service. They used to reach
            ;; the catch below and leave as a 500 carrying no form, which htmx
            ;; discards — pressing Create did nothing at all (BOU-381).
            (let [data (ex-data e)]
              (case (:type data)
                :user-exists
                (rerender {:email [[:t :user/error-email-taken]]} nil)

                ;; Password policy refusals included. nil, not the violations:
                ;; the password box comes back empty, so the tick list has to
                ;; describe an empty field. What was wrong is in the message.
                :validation-error
                (rerender (service-errors->field-errors (:errors data)) nil)

                (server-error request e "Create user failed"))))
          (catch Exception e
            (server-error request e "Create user failed")))))))

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
          (-> (html-response request
                             (layout/error-layout 403 [:t :user/page-access-denied-title]
                                                  [:t :user/error-delete-admin-only])
                             403)
              (with-error-toast request :user/error-delete-admin-only))
          (do
            (user-ports/permanently-delete-user user-service uuid)
            (redirect-with-toast request "/web/admin/users" :success :user/toast-user-deleted))))
      (catch IllegalArgumentException _
        (-> (html-response request (ui/error-message [:t :user/error-invalid-user-id]) 400)
            (with-error-toast request :user/error-invalid-user-id)))
      (catch Exception e
        (-> (server-error request e "Error permanently deleting user")
            (with-error-toast request :common/error-generic))))))

;; =============================================================================
;; Session Management Handlers
;; =============================================================================

(defn account-recovery-allowed?
  "Whether `actor` may unlock user `target-id` or reset their second factor: a
   platform admin, as for the audit trail, and not on their own account, whose
   second factor the profile turns off with a password or code."
  [actor target-id]
  (and (access/platform-admin? actor)
       (not (access/same-id? (:id actor) target-id))))

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
            _ (log/info "Sessions retrieved:" {:count (count sessions)})
            current-session-id (get-in request [:session :id])
            opts {:user (:user request)
                  :account-recovery? (account-recovery-allowed? (:user request) user-id)
                  :current-time (current-time)
                  :zone-id (current-zone-id)
                  :flash (:flash request)}]
        (if user
          (html-response request (user-ui/user-sessions-page user sessions current-session-id opts))
          (html-response request
                         (layout/error-layout 404 [:t :user/page-user-not-found-title] [:t :user/page-user-not-found-message])
                         404)))
      (catch IllegalArgumentException e
        (log/error e "Invalid user ID format - user-id:" (get-in request [:path-params :id]) "path-params:" (:path-params request))
        (html-response request (ui/error-message [:t :user/error-invalid-user-id]) 400))
      (catch Exception e
        (server-error request e "Error loading user sessions page")))))

(defn revoke-session-handler
  "Handler for POST /web/users/:id/sessions/:session-id/revoke: ends one of the
   user's sessions, forcing that device to sign in again. Sessions are named by
   id; the page never sees a token (BOU-696).

   Args:
     user-service: User service instance
     config: Application configuration map

   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [user-id (get-in request [:path-params :id])
            {:keys [invalidated]} (user-ports/revoke-user-session
                                   user-service
                                   (UUID/fromString user-id)
                                   (UUID/fromString (get-in request [:path-params :session-id])))
            sessions-url (str "/web/users/" user-id "/sessions")]
        (if invalidated
          (redirect-with-toast request sessions-url :success :user/toast-session-revoked)
          (redirect-with-toast request sessions-url :error :user/toast-session-not-found)))
      (catch IllegalArgumentException _
        (-> (html-response request (ui/error-message [:t :user/error-invalid-session]) 400)
            (with-error-toast request :user/error-invalid-session)))
      (catch Exception e
        (-> (server-error request e "Error revoking session")
            (with-error-toast request :common/error-generic))))))

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
            keep-current? (= "true" (get-in request [:form-params "keep-current"]))]
        (user-ports/logout-user-everywhere user-service uuid
                                           (when keep-current? (get-in request [:session :id])))
        (redirect-with-toast request (str "/web/users/" user-id "/sessions") :success :user/toast-sessions-revoked))
      (catch IllegalArgumentException _
        (-> (html-response request (ui/error-message [:t :user/error-invalid-user-id]) 400)
            (with-error-toast request :user/error-invalid-user-id)))
      (catch Exception e
        (-> (server-error request e "Error revoking all sessions")
            (with-error-toast request :common/error-generic))))))

(defn- account-recovery-handler
  "POST to `(change user-service user-id)`, an IUserAccountRecovery call,
   back to the user's sessions page with `done` or `nothing`, the toast for
   true or false."
  [user-service change done nothing]
  (fn [request]
    (if-let [user-id (parse-uuid (str (get-in request [:path-params :id])))]
      (try
        (let [back (str "/web/users/" user-id "/sessions")]
          (if (change user-service user-id)
            (redirect-with-toast request back :success done)
            (redirect-with-toast request back :info nothing)))
        (catch clojure.lang.ExceptionInfo e
          (if (= :user-not-found (:type (ex-data e)))
            (-> (html-response request (ui/error-message [:t :user.rights/user-not-found]) 404)
                (with-error-toast request :user.rights/user-not-found))
            (-> (server-error request e "Account recovery failed")
                (with-error-toast request :common/error-generic)))))
      (-> (html-response request (ui/error-message "Invalid user ID") 400)
          (with-error-toast request "Invalid user ID")))))

(defn unlock-user-handler
  "POST /web/users/:id/unlock: ends the user's sign-in lockout (admin)."
  [user-service _config]
  (account-recovery-handler user-service user-ports/unlock-user
                            :user/toast-account-unlocked :user/toast-account-not-locked))

(defn reset-mfa-handler
  "POST /web/users/:id/mfa/reset: turns off the user's second factor (admin)."
  [user-service _config]
  (account-recovery-handler user-service user-ports/reset-user-mfa
                            :user/toast-mfa-reset :user/toast-mfa-not-on))

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

(defn- audit-list
  "The audit page's table query, filters, entries and their total for a
   request."
  [user-service request]
  (let [qp          (:query-params request)
        table-query (web-table/parse-table-query
                     qp
                     {:default-sort      :created-at
                      :default-dir       :desc
                      :default-page-size 50})
        filters     (parse-audit-filters qp)
        result      (user-ports/list-audit-logs user-service
                                                (build-audit-list-opts table-query filters))]
    [(:audit-logs result) table-query (:total-count result) filters]))

(defn audit-page-handler
  "Handler for the audit trail page (GET /web/audit). Its own controls
   request this URL and push it, so the address bar holds sort, filters and
   page; they get the table fragment back.

   Args:
     user-service: User service instance
     config: Application configuration map

   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (let [args (audit-list user-service request)]
        ;; One URL, two bodies: a cache must not answer one with the other
        (-> (html-response request
                           (if (htmx/fragment-request? (:headers request) ["audit-table-container"])
                             (apply user-ui/audit-table-fragment args)
                             (apply user-ui/audit-page (conj args {:user  (:user request)
                                                                   :flash (:flash request)}))))
            (response/header "Vary" "HX-Request, HX-Target")))
      (catch Exception e
        (server-error-page request e "Error in audit-page-handler")))))

(defn audit-table-fragment-handler
  "Handler for the audit table fragment (GET /web/audit/table), for callers
   that ask for the table alone.

   Args:
     user-service: User service instance
     config: Application configuration map

   Returns:
     Ring handler function"
  [user-service _config]
  (fn [request]
    (try
      (html-response request (apply user-ui/audit-table-fragment (audit-list user-service request)))
      (catch Exception e
        (server-error request e "Error in audit-table-fragment-handler")))))

;; =============================================================================
;; Profile Page Handlers
;; =============================================================================

(defn- profile-opts
  "Page options every full profile page takes: the app's `:profile-layout`,
   with the request it may want to read."
  [config request]
  (let [app-layout (get-in config [:active :wagoe/user :profile-layout])]
    (cond-> {:user (:user request) :flash (:flash request)}
      app-layout (assoc :profile-layout app-layout :request (without-secrets request)))))

(defn profile-page-handler
  "Handler for the profile page (GET /web/profile).
   
   Displays user profile with all sections: info, preferences, security, MFA.
   
   Args:
     user-service: User service instance
     mfa-service: MFA service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service mfa-service config]
  (fn [request]
    (try
      (let [current-user (:user request)
            user-id (:id current-user)
            hx-request? (some-> (get-in request [:headers "hx-request"])
                                text/lower-case
                                (= "true"))
            hx-target (get-in request [:headers "hx-target"])
            ;; Get fresh user data
            user (user-ports/get-user-by-id user-service user-id)
            ;; Get MFA status
            mfa-status (mfa/get-mfa-status mfa-service user-id)
            page-opts (profile-opts config request)]
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
                         (layout/error-layout 404 [:t :user/page-profile-not-found-title]
                                              [:t :user/page-profile-not-found-message])
                         404)))
      (catch Exception e
        (server-error-page request e "Error in profile-page-handler")))))

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
          (html-response request (ui/error-message [:t :user/error-user-not-found]) 404)))
      (catch Exception e
        (server-error request e "Error in profile-edit-form-handler")))))

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
          (html-response request (ui/error-message [:t :user/error-user-not-found]) 404)))
      (catch Exception e
        (server-error request e "Error in profile-info-fragment-handler")))))

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
            ;; Only the edited field: a role or active read before a concurrent
            ;; demotion or deactivation would undo it (BOU-685).
            (let [updated-user (user-ports/update-user-profile user-service
                                                               (assoc prepared-data :id user-id))]
              ;; Return success with updated info card
              (html-response request
                             (profile-ui/profile-info-fragment updated-user)))
            (catch Exception e
              (server-error request e "Error updating profile")))))
      (catch Exception e
        (server-error request e "Error in profile-edit-handler")))))

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
          (html-response request (ui/error-message [:t :user/error-user-not-found]) 404)))
      (catch Exception e
        (server-error request e "Error in preferences-edit-form-handler")))))

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
          (html-response request (ui/error-message [:t :user/error-user-not-found]) 404)))
      (catch Exception e
        (server-error request e "Error in preferences-fragment-handler")))))

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
        (if-let [user (user-ports/get-user-by-id user-service user-id)]
          (try
            ;; Only the edited fields, as in the profile edit (BOU-685).
            (let [updated-user (user-ports/update-user-profile
                                user-service (assoc prepared-data :id user-id))]
              (html-response request (profile-ui/preferences-fragment updated-user)))
            (catch clojure.lang.ExceptionInfo e
              (if (= :validation-error (:type (ex-data e)))
                (html-response request
                               (profile-ui/preferences-edit-form user (field-errors (:errors (ex-data e))))
                               400)
                (server-error request e "Error updating preferences"))))
          (html-response request (ui/error-message [:t :user/error-user-not-found]) 404)))
      (catch Exception e
        (server-error request e "Error in preferences-edit-handler")))))

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
                  :account-locked
                  (html-response request
                                 (profile-ui/password-section-fragment true
                                                                       {:current-password [[:t :user/too-many-attempts]]})
                                 429
                                 htmx-no-cache-headers)
                  :password-policy-violation
                  (html-response request
                                 (profile-ui/password-section-fragment true
                                                                       {:new-password (or (seq (map error-message (:violations data)))
                                                                                          [[:t :user/error-password-policy]])})
                                 400
                                 htmx-no-cache-headers)
                  :user-not-found
                  (html-response request (ui/error-message [:t :user/error-user-not-found]) 404)
                  ;; Default error: the detail goes to the log, not the page.
                  (do (log/error e "Error changing password")
                      (html-response request (ui/error-message [:t :common/error-generic]) 500)))))
            (catch Exception e
              (log/error e "Error changing password")
              (html-response request (ui/error-message [:t :common/error-generic]) 500)))))
      (catch Exception e
        (log/error e "Error in password-change-handler")
        (html-response request (ui/error-message [:t :common/error-generic]) 500)))))

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
  [_mfa-service config]
  (fn [request]
    (try
      (let [page-opts (profile-opts config request)]
        (html-response request (profile-ui/mfa-setup-page page-opts)))
      (catch Exception e
        (server-error-page request e "Error in mfa-setup-page-handler")))))

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
          ;; A refusal is the caller's 400; a caught exception, our 500
          (html-response request (ui/error-message (mfa-error-message setup-result))
                         (if (= :mfa-setup-failed (get-in setup-result [:error :type])) 500 400))))
      (catch Exception e
        (server-error request e "Error in mfa-setup-initiate-handler")))))

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
            backup-codes (read-backup-codes backup-codes-str)]
        (log/info "MFA verify request" {:user-id user-id
                                        :verification-code verification-code
                                        :has-secret? (boolean secret)
                                        :has-backup-codes? (boolean backup-codes)
                                        :backup-codes-count (when backup-codes (count backup-codes))})
        (if (and secret backup-codes)
          (let [;; Call MFA service to enable MFA
                enable-result (mfa/enable-mfa mfa-service user-id secret backup-codes verification-code)]
            (log/info "MFA enable result" {:success? (:success? enable-result)
                                           :error (get-in enable-result [:error :message])})
            (if (:success? enable-result)
              ;; Success - show backup codes directly (HTMX response)
              (html-response request
                             (profile-ui/mfa-backup-codes-display backup-codes false
                                                                  {:user current-user
                                                                   :flash {:success "Two-factor authentication enabled successfully"}}))
              ;; Invalid code - regenerate QR code data URL from the submitted secret
              (let [{:keys [qr-code-url issuer account-name]}
                    (mfa/setup-view mfa-service secret (:email current-user) backup-codes)]
                (html-response request
                               (profile-ui/mfa-qr-code-step secret qr-code-url issuer account-name backup-codes
                                                            {:verification-code ["Invalid verification code. Please try again."]})
                               400))))
          ;; No setup data in form
          (html-response request (ui/error-message [:t :user/error-mfa-setup-missing]) 400)))
      (catch Exception e
        (server-error request e "Error in mfa-verify-handler")))))

(defn mfa-backup-codes-page-handler
  "Handler for backup codes display page (GET /web/profile/mfa/backup-codes).
   
   Shows backup codes after MFA enable or regeneration.
   
   Args:
     mfa-service: MFA service instance
     config: Application configuration map
     
    Returns:
     Ring handler function"
  [_mfa-service config]
  (fn [request]
    (try
      (let [flash (:flash request)
            backup-codes (:mfa-backup-codes flash)
            page-opts (profile-opts config request)]
        (if backup-codes
          (html-response request
                         (profile-ui/mfa-backup-codes-display backup-codes true page-opts))
          ;; No backup codes in flash - redirect to profile
          (response/redirect "/web/profile")))
      (catch Exception e
        (server-error-page request e "Error in mfa-backup-codes-page-handler")))))

(defn mfa-disable-page-handler
  "Handler for MFA disable confirmation page (GET /web/profile/mfa/disable).

   Asks for the password or an authentication code.

   Args:
     mfa-service: MFA service instance
     config: Application configuration map

    Returns:
     Ring handler function"
  [_mfa-service config]
  (fn [request]
    (try
      (let [page-opts (profile-opts config request)]
        (html-response request (profile-ui/mfa-disable-confirm-page {} page-opts)))
      (catch Exception e
        (server-error-page request e "Error in mfa-disable-page-handler")))))

(defn- mfa-disable-refusal
  "[status form-errors] for a refused disable."
  [result]
  (case (get-in result [:error :type])
    (:invalid-proof :proof-required) [400 {:code [[:t :user/mfa-disable-wrong-proof]]}]
    :account-locked                  [429 {:code [[:t :user/too-many-attempts]]}]
    [400 {:code [(mfa-error-message result)]}]))

(defn mfa-disable-handler
  "Handler for MFA disable submission (POST /web/profile/mfa/disable).

   Checks the password or code and disables MFA; see `mfa/disable-mfa`.

   Args:
     mfa-service: MFA service instance
     config: Application configuration map

   Returns:
     Ring handler function"
  [mfa-service config]
  (fn [request]
    (try
      (let [current-user (:user request)
            user-id (:id current-user)
            params (:form-params request)
            result (mfa/disable-mfa mfa-service user-id
                                    {:password (get params "password") :code (get params "code")}
                                    (login-context request))]
        (if (:success? result)
          (-> (response/redirect "/web/profile")
              (assoc :flash {:success "Two-factor authentication has been disabled"}))
          (let [[status errors] (mfa-disable-refusal result)]
            (html-response request
                           (profile-ui/mfa-disable-confirm-page errors (profile-opts config request))
                           status))))
      (catch Exception e
        (server-error request e "Error in mfa-disable-handler")))))
