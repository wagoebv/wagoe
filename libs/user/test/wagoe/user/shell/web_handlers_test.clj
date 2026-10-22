(ns wagoe.user.shell.web-handlers-test
  "Tests for user web UI handlers.
   
   Tests cover:
   - Page handlers (full HTML pages)
   - HTMX fragment handlers (partial updates)
   - Validation and error handling
   
   - HTML response structure"
  (:require [wagoe.user.shell.web-handlers :as web-handlers]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.service :as service]
            [wagoe.user.shell.mfa :as mfa]
            [wagoe.user.shell.in-memory-repository-test :as mem]
            [wagoe.email.ports :as email-ports]
            [wagoe.i18n.shell.middleware :as i18n-middleware]
            [wagoe.i18n.shell.catalogue :as catalogue]
            [clojure.test :refer [deftest testing is]]
            [clojure.tools.logging.test :as log-test]
            [clojure.string :as str])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- html-contains?
  "Check if HTML response contains a substring."
  [response text]
  (and (string? (:body response))
       (str/includes? (:body response) text)))

(def ^:private dutch
  "Request keys that render a page through the real Dutch catalogue."
  {:i18n/t (i18n-middleware/resolve-t-fn
            {:i18n/catalogue      (catalogue/load-catalogue "wagoe/i18n/translations")
             :i18n/default-locale :nl})})

(defn- has-header?
  "Check if response has a header with specific value."
  [response header-name expected-value]
  (= expected-value (get-in response [:headers header-name])))

;; =============================================================================
;; Mock User Service
;; =============================================================================

(defrecord MockUserService [state]
  ports/IUserService

  (register-user [_ user-data]
    (let [user-id (UUID/randomUUID)
          now (Instant/now)
          created-user (assoc user-data
                              :id user-id
                              :created-at now
                              :updated-at nil
                              :deleted-at nil)]
      (swap! state assoc user-id created-user)
      created-user))

  (register-or-authenticate-user [_ user-data _login-context]
    (if-let [existing (->> @state vals (filter #(= (:email %) (:email user-data))) first)]
      {:user existing
       :created? false
       :authenticated? true
       :auth-result nil}
      (let [created (.register-user ^wagoe.user.ports.IUserService _ user-data)]
        {:user created
         :created? true
         :authenticated? false
         :auth-result nil})))

  (claim-user-identity [_ {:keys [user-data login-context]}]
    (let [result (.register-or-authenticate-user ^wagoe.user.ports.IUserService _
                                                 user-data
                                                 login-context)]
      (assoc result :mode (if (:created? result) :registered :authenticated))))

  (get-user-by-id [_ user-id]
    (get @state user-id))

  (get-user-by-email [_ email]
    (->> @state
         vals
         (filter #(= (:email %) email))
         first))

  (list-users [_ options]
    (let [users (->> @state
                     vals
                     (filter #(nil? (:deleted-at %))))
          total-count (count users)
          limit (or (:limit options) 20)
          offset (or (:offset options) 0)
          page (take limit (drop offset users))]
      {:users page
       :total-count total-count}))

  (update-user-profile [_ user-entity]
    (let [user-id (:id user-entity)
          existing (get @state user-id)]
      (if existing
        (let [updated (assoc user-entity :updated-at (Instant/now))]
          (swap! state assoc user-id updated)
          updated)
        (throw (ex-info "User not found"
                        {:type :user-not-found
                         :user-id user-id})))))

  (deactivate-user [_ user-id]
    (boolean (when (get @state user-id)
               (swap! state assoc-in [user-id :active] false))))

  (delete-user [_ user-id]
    (let [existing (get @state user-id)]
      (if existing
        (do
          (swap! state update user-id
                 #(assoc % :deleted-at (Instant/now) :active false))
          true)
        (throw (ex-info "User not found"
                        {:type :user-not-found
                         :user-id user-id})))))

  (permanently-delete-user [_ user-id]
    (if (get @state user-id)
      (do
        (swap! state dissoc user-id)
        true)
      (throw (ex-info "User not found"
                      {:type :user-not-found
                       :user-id user-id}))))

  (authenticate-user [_ session-data]
    (let [session-id (UUID/randomUUID)
          now (Instant/now)
          session-token (str (UUID/randomUUID) (UUID/randomUUID))
          expires-at (.plusSeconds now 3600)
          session (assoc session-data
                         :id session-id
                         :session-token session-token
                         :created-at now
                         :expires-at expires-at
                         :last-accessed-at nil
                         :revoked-at nil)]
      (swap! state assoc-in [:sessions session-token] session)
      session))

  (validate-session [_ session-token]
    (let [session (get-in @state [:sessions session-token])
          now (Instant/now)]
      (when (and session
                 (nil? (:revoked-at session))
                 (.isAfter (:expires-at session) now))
        session)))

  (logout-user [_ session-token]
    (if (get-in @state [:sessions session-token])
      (do
        (swap! state assoc-in [:sessions session-token :revoked-at] (Instant/now))
        true)
      false))

  (logout-user-everywhere [_ user-id]
    (let [sessions (get-in @state [:sessions])
          user-sessions (filter #(= (:user-id (val %)) user-id) sessions)
          cnt (count user-sessions)]
      (doseq [[token _] user-sessions]
        (swap! state assoc-in [:sessions token :revoked-at] (Instant/now)))
      cnt))

  (get-user-sessions [_ user-id]
    (let [sessions (get-in @state [:sessions])
          now (Instant/now)]
      (->> sessions
           vals
           (filter #(and (= (:user-id %) user-id)
                         (nil? (:revoked-at %))
                         (.isAfter (:expires-at %) now)))
           vec)))

  (list-audit-logs [_ _options]
    {:audit-logs []
     :total-count 0})

  (get-audit-logs-for-user [_ _user-id _options]
    [])

  (change-password [_ _user-id _current-password _new-password]
    true))

(defn create-mock-service
  "Create a mock user service with initial state."
  ([]
   (create-mock-service {}))
  ([initial-state]
   (->MockUserService (atom initial-state))))

(defn create-service-rejecting-registration
  "A user service whose register-user throws `ex` — the shape the real service
   uses to refuse a duplicate email or a password the policy rejects."
  [ex]
  (reify ports/IUserService
    (register-user [_ _] (throw ex))
    (register-or-authenticate-user [_ _ _] (throw (UnsupportedOperationException.)))
    (claim-user-identity [_ _] (throw (UnsupportedOperationException.)))
    (get-user-by-id [_ _] nil)
    (get-user-by-email [_ _] nil)
    (list-users [_ _] {:users [] :total-count 0})
    (update-user-profile [_ _] (throw (UnsupportedOperationException.)))
    (deactivate-user [_ _] (throw (UnsupportedOperationException.)))
    (permanently-delete-user [_ _] (throw (UnsupportedOperationException.)))
    (authenticate-user [_ _] (throw (UnsupportedOperationException.)))
    (validate-session [_ _] nil)
    (logout-user [_ _] false)
    (logout-user-everywhere [_ _] 0)
    (get-user-sessions [_ _] [])
    (list-audit-logs [_ _] {:audit-logs [] :total-count 0})
    (get-audit-logs-for-user [_ _ _] [])
    (change-password [_ _ _ _] false)))

(defn create-test-user
  "Create a test user entity."
  [overrides]
  (merge {:id (UUID/randomUUID)
          :name "Test User"
          :email "test@example.com"
          :role :user

          :active true
          :created-at (Instant/now)
          :updated-at nil
          :deleted-at nil}
         overrides))

;; =============================================================================
;; Page Handler Tests
;; =============================================================================

(deftest ^:contract users-page-handler-test
  (testing "renders users page with users list"
    (let [user1 (create-test-user {:name "Alice" :email "alice@example.com"})
          user2 (create-test-user {:name "Bob" :email "bob@example.com"})
          service (create-mock-service {(:id user1) user1
                                        (:id user2) user2})
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/users-page-handler service config)
          request {}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (html-contains? response "Alice"))
      (is (html-contains? response "alice@example.com"))
      (is (html-contains? response "Bob"))
      (is (html-contains? response "bob@example.com"))))

  (testing "renders users list for all users"
    (let [user1 (create-test-user {})
          service (create-mock-service {(:id user1) user1})
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/users-page-handler service config)
          request {}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (html-contains? response "Test User"))))

  (testing "handles empty users list"
    (let [service (create-mock-service)
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/users-page-handler service config)
          request {}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (html-contains? response "empty-state-no-users"))))

  (testing "handles service errors gracefully"
    (let [service (reify ports/IUserService
                    (register-or-authenticate-user [_ _ _] {:user nil :created? false :authenticated? false :auth-result nil})
                    (claim-user-identity [_ _] {:mode :authenticated :user nil :created? false :authenticated? false :auth-result nil})
                    (register-user [_ _] (throw (UnsupportedOperationException.)))
                    (get-user-by-id [_ _] nil)
                    (get-user-by-email [_ _] nil)
                    (list-users [_ _]
                      (throw (Exception. "Database connection failed")))
                    (update-user-profile [_ _] (throw (UnsupportedOperationException.)))
                    (deactivate-user [_ _] (throw (UnsupportedOperationException.)))
                    (permanently-delete-user [_ _] (throw (UnsupportedOperationException.)))
                    (authenticate-user [_ _] (throw (UnsupportedOperationException.)))
                    (validate-session [_ _] nil)
                    (logout-user [_ _] false)
                    (logout-user-everywhere [_ _] 0)
                    (get-user-sessions [_ _] [])
                    (list-audit-logs [_ _] {:audit-logs [] :total-count 0})
                    (get-audit-logs-for-user [_ _ _] [])
                    (change-password [_ _ _ _] false))
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/users-page-handler service config)
          request {}
          response (handler request)]

      (is (= 500 (:status response)))
      (is (not (html-contains? response "Database connection failed")))
      (is (html-contains? response "error-generic")))))

(deftest ^:contract web-root-page-handler-test
  (testing "renders translated public landing page through html-response"
    (let [service (create-mock-service)
          handler (web-handlers/web-root-page-handler service {})
          request {:i18n/t (fn
                             ([k] (case k
                                    :user/page-welcome-heading "Welcome"
                                    :user/page-welcome-description "Sign in to continue"
                                    :user/link-signin "Sign in"
                                    (name k)))
                             ([k _params] (name k))
                             ([k _params _n] (name k)))}
          response (handler request)]
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (html-contains? response "Welcome"))
      (is (html-contains? response "Sign in to continue"))
      (is (html-contains? response "/web/login"))))

  (testing "redirects authenticated users to dashboard"
    (let [service (create-mock-service {:sessions {"valid-token" {:user-id (UUID/randomUUID)
                                                                  :expires-at (.plusSeconds (Instant/now) 3600)}}})
          handler (web-handlers/web-root-page-handler service {})
          request {:cookies {"session-token" {:value "valid-token"}}}
          response (handler request)]
      (is (= 302 (:status response)))
      (is (= "/web/admin/" (get-in response [:headers "Location"]))))))

(deftest ^:contract user-detail-page-handler-test
  (testing "renders user detail page for existing user"
    (let [user (create-test-user {:name "Alice" :email "alice@example.com"})
          service (create-mock-service {(:id user) user})
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/user-detail-page-handler service config)
          request {:path-params {:id (str (:id user))}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (html-contains? response "Alice"))
      (is (html-contains? response "alice@example.com"))))

  (testing "returns 404 for non-existent user"
    (let [service (create-mock-service)
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/user-detail-page-handler service config)
          request (assoc dutch :path-params {:id (str (UUID/randomUUID))})
          response (handler request)]

      (is (= 404 (:status response)))
      (is (html-contains? response "Gebruiker niet gevonden"))))

  (testing "returns 400 for invalid UUID"
    (let [service (create-mock-service)
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/user-detail-page-handler service config)
          request (assoc dutch :path-params {:id "not-a-uuid"})
          response (handler request)]

      (is (= 400 (:status response)))
      (is (html-contains? response "Ongeldig gebruikers-ID"))))

  (testing "handles service errors gracefully"
    (let [user (create-test-user {})
          service (reify ports/IUserService
                    (register-or-authenticate-user [_ _ _] {:user nil :created? false :authenticated? false :auth-result nil})
                    (claim-user-identity [_ _] {:mode :authenticated :user nil :created? false :authenticated? false :auth-result nil})
                    (register-user [_ _] (throw (UnsupportedOperationException.)))
                    (get-user-by-id [_ _]
                      (throw (Exception. "Database error")))
                    (get-user-by-email [_ _] nil)
                    (list-users [_ _] {:users [] :total-count 0})
                    (update-user-profile [_ _] (throw (UnsupportedOperationException.)))
                    (deactivate-user [_ _] (throw (UnsupportedOperationException.)))
                    (permanently-delete-user [_ _] (throw (UnsupportedOperationException.)))
                    (authenticate-user [_ _] (throw (UnsupportedOperationException.)))
                    (validate-session [_ _] nil)
                    (logout-user [_ _] false)
                    (logout-user-everywhere [_ _] 0)
                    (get-user-sessions [_ _] [])
                    (list-audit-logs [_ _] {:audit-logs [] :total-count 0})
                    (get-audit-logs-for-user [_ _ _] [])
                    (change-password [_ _ _ _] false))
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/user-detail-page-handler service config)
          request {:path-params {:id (str (:id user))}}
          response (handler request)]

      (is (= 500 (:status response)))
      (is (not (html-contains? response "Database error")))
      (is (html-contains? response "error-generic")))))

(deftest ^:contract create-user-page-handler-test
  (testing "renders create user page"
    (let [config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/create-user-page-handler config)
          request {}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (html-contains? response "form-create-title"))
      (is (html-contains? response "form"))))

  (testing "includes flash messages when present"
    (let [config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/create-user-page-handler config)
          request {:flash {:error "Previous creation failed"}}
          response (handler request)]

      (is (= 200 (:status response)))))

  ;; The listed rules used to be hardcoded to min-length + numbers, so a prod
  ;; config rejected on four rules while the form advertised two (BOU-381 review,
  ;; underlying key mismatch tracked as BOU-388).
  (testing "lists the rules the configured policy actually enforces"
    (let [config {:active {:wagoe/settings
                           {:user-validation
                            {:password-policy {:min-length 8
                                               :max-length 128
                                               :require-uppercase? true
                                               :require-lowercase? true
                                               :require-numbers? true
                                               :require-special-chars? true}}}}}
          response ((web-handlers/create-user-page-handler config) {})]

      (is (html-contains? response "password-requirement-uppercase"))
      (is (html-contains? response "password-requirement-lowercase"))
      (is (html-contains? response "password-requirement-special-char"))
      (is (html-contains? response "password-requirement-number"))))

  (testing "a laxer configured minimum does not undercut what is enforced anyway"
    ;; dev config says 6, but CreateUserRequest requires 8, so the form shows 8.
    ;; The flags it switches off are off: both validators read the same policy
    ;; since BOU-388.
    ;; The interpolated count only reaches the body through a real translator —
    ;; the fallback drops params, which would make an assertion on it vacuous.
    (let [config {:active {:wagoe/settings
                           {:user-validation
                            {:password-policy {:min-length 6
                                               :require-numbers? false
                                               :require-uppercase? false}}}}}
          t-fn (fn ([k] (str k))
                 ([k params] (str k " " (pr-str params)))
                 ([k params _n] (str k " " (pr-str params))))
          response ((web-handlers/create-user-page-handler config) {:i18n/t t-fn})]

      (is (not (html-contains? response "password-requirement-number"))
          ":require-numbers? false now reaches both validators, so it is not listed")
      (is (not (html-contains? response "password-requirement-uppercase")))
      (is (html-contains? response "{:n 8}")
          "the listed minimum should be 8, not the configured 6")
      (is (not (html-contains? response "{:n 6}"))))))

;; =============================================================================
;; HTMX Fragment Handler Tests
;; =============================================================================

(deftest ^:contract audit-page-handler-test
  (let [handler (web-handlers/audit-page-handler (create-mock-service) {})
        htmx    {"hx-request" "true" "hx-target" "audit-table-container"}]
    (testing "a browser gets the page in the admin shell"
      (let [response (handler {:query-params {}})]
        (is (= 200 (:status response)))
        (is (html-contains? response "admin-shell"))
        (is (= "HX-Request, HX-Target" (get-in response [:headers "Vary"])))))

    (testing "the table's controls get the table and the count out of band"
      (let [body (:body (handler {:query-params {} :headers htmx}))]
        (is (re-find #"^\s*<div [^>]*\bid=\"audit-table-container\"" body))
        (is (re-find #"id=\"audit-record-count\"[^>]*hx-swap-oob|hx-swap-oob[^>]*id=\"audit-record-count\"" body))
        (is (not (str/includes? body "admin-shell")))))

    (testing "a history restore gets the page"
      (is (html-contains? (handler {:query-params {}
                                    :headers (assoc htmx "hx-history-restore-request" "true")})
                          "admin-shell")))))

(deftest ^:contract users-table-fragment-handler-test
  (testing "returns users table fragment"
    (let [user1 (create-test-user {:name "Alice"})
          user2 (create-test-user {:name "Bob"})
          service (create-mock-service {(:id user1) user1
                                        (:id user2) user2})
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/users-table-fragment-handler service config)
          request {}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (html-contains? response "Alice"))
      (is (html-contains? response "Bob"))
      (is (html-contains? response "users-table-container"))))

  (testing "the fragment is rooted at the container its callers target"
    ;; They swap outerHTML on that assumption (BOU-386).
    (let [service (create-mock-service)
          config  {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}]
      (is (re-find #"^\s*<div [^>]*\bid=\"users-table-container\""
                   (:body ((web-handlers/users-table-fragment-handler service config) {}))))
      (is (re-find #"^\s*<div [^>]*\bid=\"audit-table-container\""
                   (:body ((web-handlers/audit-table-fragment-handler service config) {}))))))

  (testing "pushes no URL: a refresh after a create or update is no history entry"
    (let [handler (web-handlers/users-table-fragment-handler (create-mock-service) {})]
      (is (nil? (get-in (handler {:headers {"hx-request" "true"}
                                  :query-params {"sort" "email" "dir" "asc" "q" "ann"}})
                        [:headers "HX-Push-Url"]))))))

(deftest ^:contract users-page-serves-its-table-controls-test
  (let [handler (web-handlers/users-page-handler (create-mock-service) {})
        htmx    {"hx-request" "true" "hx-target" "users-table-container"}]
    (testing "a browser gets the page"
      (let [response (handler {:query-params {}})]
        (is (html-contains? response "admin-shell"))
        (is (= "HX-Request, HX-Target" (get-in response [:headers "Vary"])))))
    (testing "the table's controls get the table, and push their own URL"
      (let [response (handler {:query-params {"sort" "email" "dir" "asc"} :headers htmx})]
        (is (re-find #"^\s*<div [^>]*\bid=\"users-table-container\"" (:body response)))
        (is (not (html-contains? response "admin-shell")))
        (is (nil? (get-in response [:headers "HX-Push-Url"])))))
    (testing "a history restore gets the page"
      (is (html-contains? (handler {:query-params {}
                                    :headers (assoc htmx "hx-history-restore-request" "true")})
                          "admin-shell")))))

(deftest ^:contract users-table-fragment-handler-errors-test

  (testing "handles errors"
    (let [service (reify ports/IUserService
                    (register-or-authenticate-user [_ _ _] {:user nil :created? false :authenticated? false :auth-result nil})
                    (claim-user-identity [_ _] {:mode :authenticated :user nil :created? false :authenticated? false :auth-result nil})
                    (register-user [_ _] (throw (UnsupportedOperationException.)))
                    (get-user-by-id [_ _] nil)
                    (get-user-by-email [_ _] nil)
                    (list-users [_ _]
                      (throw (Exception. "Connection timeout")))
                    (update-user-profile [_ _] (throw (UnsupportedOperationException.)))
                    (deactivate-user [_ _] (throw (UnsupportedOperationException.)))
                    (permanently-delete-user [_ _] (throw (UnsupportedOperationException.)))
                    (authenticate-user [_ _] (throw (UnsupportedOperationException.)))
                    (validate-session [_ _] nil)
                    (logout-user [_ _] false)
                    (logout-user-everywhere [_ _] 0)
                    (get-user-sessions [_ _] [])
                    (list-audit-logs [_ _] {:audit-logs [] :total-count 0})
                    (get-audit-logs-for-user [_ _ _] [])
                    (change-password [_ _ _ _] false))
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/users-table-fragment-handler service config)
          request {}
          response (handler request)]

      (is (= 500 (:status response)))
      (is (not (html-contains? response "Connection timeout")))
      (is (html-contains? response "error-generic")))))

(deftest ^:contract create-user-htmx-handler-test
  (testing "creates user successfully and instructs HTMX to navigate to return-to"
    (let [service (create-mock-service)
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/create-user-htmx-handler service nil config)
          request {:form-params {"name" "New User"
                                 "email" "newuser@example.com"
                                 "password" "password123"
                                 "role" "user"
                                 "active" "true"
                                 "return-to" "/web/admin/users"}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (html-contains? response "pendingToast"))
      (is (html-contains? response "User New User created"))
      (is (html-contains? response "/web/admin/users"))))

  (testing "falls back to /web/admin/users when return-to is missing or unsafe"
    (let [service (create-mock-service)
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/create-user-htmx-handler service nil config)
          ;; Open-redirect attempt via scheme-relative URL
          request {:form-params {"name" "New User"
                                 "email" "newuser2@example.com"
                                 "password" "password123"
                                 "role" "user"
                                 "active" "true"
                                 "return-to" "//evil.example.com/phish"}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (html-contains? response "/web/admin/users"))
      (is (not (html-contains? response "evil.example.com")))))

  (testing "returns validation errors for invalid data"
    (let [service (create-mock-service)
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/create-user-htmx-handler service nil config)
          request {:form-params {"name" ""
                                 "email" "invalid-email"
                                 "password" "123"}}
          response (handler request)]

      (is (= 400 (:status response)))
      (is (html-contains? response "create-user-form"))))

  ;; Both of these used to leave as a 500 carrying no form, which htmx discards:
  ;; pressing Create did nothing at all. The request schema passes in each case,
  ;; so only the service can refuse them (BOU-381).
  (testing "an already-registered email comes back as a form, not a 500"
    (let [service (create-service-rejecting-registration
                   (ex-info "User already exists"
                            {:type :user-exists
                             :message "A user with this email already exists"}))
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/create-user-htmx-handler service nil config)
          request (assoc dutch :form-params {"name" "Test User"
                                             "email" "taken@example.com"
                                             "password" "password123"
                                             "role" "user"})
          response (handler request)]

      (is (= 400 (:status response)))
      (is (html-contains? response "create-user-form"))
      (is (html-contains? response "Er bestaat al een gebruiker met dit e-mailadres"))))

  (testing "a password the policy rejects comes back as a form, not a 500"
    (let [service (create-service-rejecting-registration
                   (ex-info "Password does not meet requirements"
                            {:type :validation-error
                             :errors [{:field :password
                                       :code :missing-number
                                       :message "Must contain at least one number"}]}))
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/create-user-htmx-handler service nil config)
          request (assoc dutch :form-params {"name" "Test User"
                                             "email" "new@example.com"
                                             "password" "abcdefgh"
                                             "role" "user"})
          response (handler request)]

      (is (= 400 (:status response)))
      (is (html-contains? response "create-user-form"))
      (is (html-contains? response "Moet een cijfer bevatten"))
      ;; The box comes back empty, so the rules carry no verdict — the message
      ;; above is what says why it was refused.
      (is (html-contains? response "requirement-pending"))
      (is (not (html-contains? response "requirement-met")))))

  (testing "handles service errors"
    (let [service (reify ports/IUserService
                    (register-or-authenticate-user [_ _ _] {:user nil :created? false :authenticated? false :auth-result nil})
                    (claim-user-identity [_ _] {:mode :authenticated :user nil :created? false :authenticated? false :auth-result nil})
                    (register-user [_ _]
                      (throw (Exception. "Email already exists")))
                    (get-user-by-id [_ _] nil)
                    (get-user-by-email [_ _] nil)
                    (list-users [_ _] {:users [] :total-count 0})
                    (update-user-profile [_ _] (throw (UnsupportedOperationException.)))
                    (deactivate-user [_ _] (throw (UnsupportedOperationException.)))
                    (permanently-delete-user [_ _] (throw (UnsupportedOperationException.)))
                    (authenticate-user [_ _] (throw (UnsupportedOperationException.)))
                    (validate-session [_ _] nil)
                    (logout-user [_ _] false)
                    (logout-user-everywhere [_ _] 0)
                    (get-user-sessions [_ _] [])
                    (list-audit-logs [_ _] {:audit-logs [] :total-count 0})
                    (get-audit-logs-for-user [_ _ _] [])
                    (change-password [_ _ _ _] false))
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/create-user-htmx-handler service nil config)
          request {:form-params {"name" "Test User"
                                 "email" "test@example.com"
                                 "password" "password123"
                                 "role" "user"
                                 "active" "true"}}
          response (handler request)]

      (is (= 500 (:status response)))
      (is (not (html-contains? response "Email already exists")))
      (is (html-contains? response "error-generic"))))

  (testing "sends welcome email when send-welcome is checked and email-sender provided"
    (let [sent-emails (atom [])
          service (create-mock-service)
          email-sender (reify email-ports/EmailSenderProtocol
                         (send-email! [_ email]
                           (swap! sent-emails conj email)
                           {:success? true :message-id "msg-1"})
                         (send-email-async! [this email]
                           (future (email-ports/send-email! this email))))
          config {:app-name "TestApp" :welcome-email-from "hello@test.com"}
          handler (web-handlers/create-user-htmx-handler service email-sender config)
          request {:form-params {"name" "Welcome User"
                                 "email" "welcome@example.com"
                                 "password" "password123"
                                 "role" "user"
                                 "send-welcome" "true"}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= 1 (count @sent-emails)))
      (is (= ["welcome@example.com"] (:to (first @sent-emails))))
      (is (= "hello@test.com" (:from (first @sent-emails))))
      (is (str/includes? (:subject (first @sent-emails)) "TestApp"))
      (is (html-contains? response "welcome email sent"))))

  (testing "gracefully handles welcome email failure"
    (let [service (create-mock-service)
          email-sender (reify email-ports/EmailSenderProtocol
                         (send-email! [_ _]
                           (throw (Exception. "SMTP connection refused")))
                         (send-email-async! [_ _] (future {:success? false})))
          config {:app-name "TestApp" :welcome-email-from "hello@test.com"}
          handler (web-handlers/create-user-htmx-handler service email-sender config)
          request {:form-params {"name" "Fail User"
                                 "email" "fail@example.com"
                                 "password" "password123"
                                 "role" "user"
                                 "send-welcome" "true"}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (html-contains? response "User Fail User created"))
      (is (not (html-contains? response "welcome email sent"))))))

(deftest ^:contract update-user-htmx-handler-test
  (testing "updates user successfully and re-renders form with success banner"
    (let [user (create-test-user {:name "Original Name"})
          service (create-mock-service {(:id user) user})
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/update-user-htmx-handler service config)
          request {:path-params {:id (str (:id user))}
                   :form-params {"name" "Updated Name"
                                 "email" "updated@example.com"
                                 "role" "admin"
                                 "active" "true"}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (has-header? response "HX-Trigger" "userUpdated"))
      ;; The form, and a toast out of band
      (is (html-contains? response "hx-swap-oob=\"beforeend:#toasts\""))
      (is (html-contains? response "message-updated"))
      (is (html-contains? response "Updated Name"))))

  (testing "active as the form sends it: the checkbox's hidden false, then true when checked"
    (doseq [[sent expected] [[["false" "true"] true] ["false" false] ["true" true]]]
      (let [user     (create-test-user {})
            written  (atom nil)
            service  (reify ports/IUserService
                       (get-user-by-id [_ _] user)
                       (update-user-profile [_ data] (reset! written data) (merge user data)))]
        ((web-handlers/update-user-htmx-handler service {})
         {:path-params {:id (str (:id user))}
          :form-params {"name" "Test User" "email" "test@example.com" "role" "user" "active" sent}})
        (is (= expected (:active @written)) (pr-str sent)))))

  (testing "returns validation errors for invalid data"
    (let [user (create-test-user {})
          service (create-mock-service {(:id user) user})
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/update-user-htmx-handler service config)
          request {:path-params {:id (str (:id user))}
                   :form-params {"name" ""
                                 "email" "invalid-email"}}
          response (handler request)]

      (is (= 400 (:status response)))))

  (testing "returns error for invalid UUID"
    (let [service (create-mock-service)
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/update-user-htmx-handler service config)
          request {:path-params {:id "not-a-uuid"}
                   :form-params {"name" "Test"
                                 "email" "test@example.com"}}
          response (handler request)]

      (is (= 400 (:status response)))
      (is (html-contains? response "error-invalid-user-id"))))

  (testing "handles service errors gracefully"
    (let [user (create-test-user {})
          service (reify ports/IUserService
                    (register-or-authenticate-user [_ _ _] {:user nil :created? false :authenticated? false :auth-result nil})
                    (claim-user-identity [_ _] {:mode :authenticated :user nil :created? false :authenticated? false :auth-result nil})
                    ;; minimal no-op implementations for unused methods
                    (register-user [_ _] (throw (UnsupportedOperationException.)))
                    (get-user-by-id [_ _] user)
                    (get-user-by-email [_ _] (throw (UnsupportedOperationException.)))
                    (list-users [_ _] {:users [user] :total-count 1})
                    (update-user-profile [_ _]
                      (throw (Exception. "Database error")))
                    (deactivate-user [_ _] (throw (UnsupportedOperationException.)))
                    (permanently-delete-user [_ _] (throw (UnsupportedOperationException.)))
                    (authenticate-user [_ _] (throw (UnsupportedOperationException.)))
                    (validate-session [_ _] nil)
                    (logout-user [_ _] false)
                    (logout-user-everywhere [_ _] 0)
                    (get-user-sessions [_ _] [])
                    (list-audit-logs [_ _] {:audit-logs [] :total-count 0})
                    (get-audit-logs-for-user [_ _ _] [])
                    (change-password [_ _ _ _] false))
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/update-user-htmx-handler service config)
          request {:path-params {:id (str (:id user))}
                   :form-params {"name" "Test User"
                                 "email" "test@example.com"
                                 "role" "user"
                                 "active" "true"}}
          response (handler request)]

      (is (= 500 (:status response)))
      (is (not (html-contains? response "Database error")))
      (is (html-contains? response "error-generic")))))

(deftest ^:contract delete-user-htmx-handler-test
  (testing "deactivates user successfully and returns success message"
    (let [user (create-test-user {})
          service (create-mock-service {(:id user) user})
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/delete-user-htmx-handler service config)
          request {:path-params {:id (str (:id user))}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (has-header? response "HX-Trigger" "userDeleted"))
      (is (html-contains? response "message-deleted"))
      (is (html-contains? response "/web/users"))))

  (testing "returns error for invalid UUID"
    (let [service (create-mock-service)
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/delete-user-htmx-handler service config)
          request {:path-params {:id "not-a-uuid"}}
          response (handler request)]

      (is (= 400 (:status response)))
      (is (html-contains? response "error-invalid-user-id"))))

  (testing "handles service errors"
    (let [user (create-test-user {})
          service (reify ports/IUserService
                    (register-or-authenticate-user [_ _ _] {:user nil :created? false :authenticated? false :auth-result nil})
                    (claim-user-identity [_ _] {:mode :authenticated :user nil :created? false :authenticated? false :auth-result nil})
                    (register-user [_ _] nil)
                    (get-user-by-id [_ _] nil)
                    (get-user-by-email [_ _] nil)
                    (list-users [_ _] nil)
                    (update-user-profile [_ _] nil)
                    (delete-user [_ _]
                      (throw (Exception. "Cannot delete user with active sessions")))
                    (permanently-delete-user [_ _] nil)
                    (authenticate-user [_ _] nil)
                    (validate-session [_ _] nil)
                    (logout-user [_ _] nil)
                    (logout-user-everywhere [_ _] nil)
                    (get-user-sessions [_ _] nil)
                    (list-audit-logs [_ _] nil)
                    (get-audit-logs-for-user [_ _ _] nil)
                    (change-password [_ _ _ _] nil))
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/delete-user-htmx-handler service config)
          request {:path-params {:id (str (:id user))}}
          response (handler request)]

      (is (= 500 (:status response)))
      (is (not (html-contains? response "Cannot delete user with active sessions")))
      (is (html-contains? response "error-generic")))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest ^:contract web-handlers-integration-test
  (testing "complete workflow: list -> create -> view -> update -> delete"
    (let [service (create-mock-service)
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}

          ;; 1. List users (empty)
          list-handler (web-handlers/users-page-handler service config)
          list-response (list-handler {})

          ;; 2. Create user
          create-handler (web-handlers/create-user-htmx-handler service nil config)
          create-response (create-handler {:form-params {"name" "Integration User"
                                                         "email" "integration@example.com"
                                                         "password" "password123"
                                                         "role" "user"
                                                         "active" "true"}})

          ;; Extract user ID from response (simplified - in reality would parse HTML)
          created-users (:users (ports/list-users service {}))
          user-id (str (:id (first created-users)))

          ;; 3. View user detail
          detail-handler (web-handlers/user-detail-page-handler service config)
          detail-response (detail-handler {:path-params {:id user-id}})

          ;; 4. Update user
          update-handler (web-handlers/update-user-htmx-handler service config)
          update-response (update-handler {:path-params {:id user-id}
                                           :form-params {"name" "Updated Integration User"
                                                         "email" "integration@example.com"
                                                         "role" "admin"
                                                         "active" "true"}})

          ;; 5. Delete user
          delete-handler (web-handlers/delete-user-htmx-handler service config)
          delete-response (delete-handler {:path-params {:id user-id}})]

      ;; Verify each step
      (is (= 200 (:status list-response)))
      (is (= 200 (:status create-response)))
      (is (html-contains? create-response "pendingToast"))
      (is (= 200 (:status detail-response)))
      (is (html-contains? detail-response "Integration User"))
      (is (= 200 (:status update-response)))
      (is (has-header? update-response "HX-Trigger" "userUpdated"))
      (is (= 200 (:status delete-response)))
      (is (has-header? delete-response "HX-Trigger" "userDeleted")))))

(deftest ^:contract ^:security login-cookie-secure-attribute-test
  (testing "session-token cookie :secure follows config :secure-cookies?"
    (let [auth-svc (reify ports/IUserService
                     (authenticate-user [_ _]
                       {:authenticated true
                        :user    {:role :user}
                        :session {:session-token "session-token-value"}}))
          request  {:form-params {"email" "user@example.com" "password" "password123"}
                    :remote-addr "127.0.0.1"
                    :headers {"user-agent" "test-agent"}}
          cookie-secure (fn [config]
                          (-> ((web-handlers/login-submit-handler auth-svc config) request)
                              (get-in [:cookies "session-token" :secure])))]
      ;; The shape the system actually supplies: `:wagoe/user-routes` is handed
      ;; the whole loaded config, `:active` and all. This test used to pass a
      ;; bare `{:wagoe/settings ...}` — the shape the function happened to read
      ;; — so it confirmed the lookup instead of checking it, and
      ;; `:secure-cookies? false` in the dev config never reached the cookie
      ;; (BOU-477).
      (testing "secure when config enables it (e.g. prod/acc over HTTPS)"
        (is (true? (cookie-secure {:active {:wagoe/settings {:secure-cookies? true}}}))))
      (testing "insecure when config disables it (local HTTP dev)"
        (is (false? (cookie-secure {:active {:wagoe/settings {:secure-cookies? false}}}))))
      (testing "defaults to secure when unset (fail-secure)"
        (is (true? (cookie-secure {:active {:wagoe/settings {}}})))
        (is (true? (cookie-secure {})))))))

(deftest ^:contract ^:security login-cookie-hardening-test
  (testing "session-token cookie is HttpOnly and SameSite=Strict (XSS/CSRF hardening)"
    (let [auth-svc (reify ports/IUserService
                     (authenticate-user [_ _]
                       {:authenticated true
                        :user    {:role :user}
                        :session {:session-token "session-token-value"}}))
          request  {:form-params {"email" "user@example.com" "password" "password123"}
                    :remote-addr "127.0.0.1"
                    :headers {"user-agent" "test-agent"}}
          cookie   (-> ((web-handlers/login-submit-handler auth-svc {}) request)
                       (get-in [:cookies "session-token"]))]
      (is (true? (:http-only cookie)) "HttpOnly blocks JS access to the session token")
      (is (= :strict (:same-site cookie)) "SameSite=Strict mitigates CSRF")
      (is (= "/" (:path cookie)) "cookie scoped to the whole app"))))

(defn- policy-service
  "The real UserService on in-memory repositories, with a 12-character minimum."
  []
  (service/create-user-service (mem/->MemoryUserRepository (atom {}))
                               (mem/->MemorySessionRepository (atom {}))
                               (mem/->MemoryAuditRepository (atom []))
                               {:password-policy {:min-length 12}}
                               nil))

(deftest ^:contract register-submit-rejects-policy-passwords-with-the-form
  ;; Both passwords pass the request schema, so only the service refuses them.
  ;; Each used to leave as a 500 error page (BOU-552).
  (let [submit (fn [email password]
                 ((web-handlers/register-submit-handler (policy-service) {})
                  (assoc dutch :form-params {"name" "Alice" "email" email "password" password})))]
    (testing "a password containing the email's local part"
      (let [response (submit "alice@x.org" "alice-Secret-123")]
        (is (= 400 (:status response)))
        (is (html-contains? response "register-form"))
        (is (html-contains? response "Mag je e-mailadres niet bevatten"))))

    (testing "a password shorter than the configured minimum shows that rule"
      (let [response (submit "bob@x.org" "Shortpass1")]
        (is (= 400 (:status response)))
        (is (html-contains? response "register-form"))
        (is (html-contains? response "Minstens 12 tekens"))))

    (testing "fields the request schema refuses"
      (let [response (submit "not-an-email" "")]
        (is (= 400 (:status response)))
        (is (html-contains? response "Heeft niet de verwachte vorm"))
        (is (html-contains? response "Verplicht"))))))

(deftest ^:contract register-submit-refuses-a-taken-email-with-the-form
  (let [svc (policy-service)
        _ (ports/register-user svc {:name "Alice" :email "alice@x.org"
                                    :password "Correct-horse-9" :role :user})
        response ((web-handlers/register-submit-handler svc {})
                  {:form-params {"name" "Alice" "email" "alice@x.org"
                                 "password" "Correct-horse-9"}})]
    ;; 409, as the API answers :user-exists. The wording does not confirm the
    ;; account the way "already exists" would; login is neutral too (BOU-552).
    (is (= 409 (:status response)))
    (is (html-contains? response "register-form"))
    (is (html-contains? response "register-email-unavailable"))))

(deftest ^:contract register-submit-error-page-hides-the-exception
  (let [svc (create-service-rejecting-registration
             (RuntimeException. "jdbc:postgresql://db:5432 password=hunter2"))
        response ((web-handlers/register-submit-handler svc {})
                  {:form-params {"name" "Alice" "email" "alice@x.org"
                                 "password" "Correct-horse-9"}})]
    (is (= 500 (:status response)))
    (is (not (html-contains? response "hunter2")))
    (is (html-contains? response "register-error-generic"))))

(deftest ^:contract ^:security login-return-to-stays-on-this-site
  ;; Browsers read `\\` as `/`, so `/\\evil.com` was followed as `//evil.com`
  ;; after login (BOU-553).
  (let [auth-svc (reify ports/IUserService
                   (authenticate-user [_ _]
                     {:authenticated true
                      :user    {:role :user}
                      :session {:session-token "t"}}))
        location (fn [return-to]
                   (-> ((web-handlers/login-submit-handler auth-svc {})
                        {:form-params {"email" "user@example.com"
                                       "password" "password123"
                                       "return-to" return-to}})
                       (get-in [:headers "Location"])))]
    (doseq [[return-to expected]
            [["/\\evil.com"        "/web/admin/"]
             ["/%5Cevil.com"      "/web/admin/"]
             ["/%5cevil.com"      "/web/admin/"]
             ["//evil.com"        "/web/admin/"]
             ["/%2F%2Fevil.com"   "/web/admin/"]
             ["https://evil.com"  "/web/admin/"]
             ["/web/x\ty"        "/web/admin/"]
             ["/web/x\ny"        "/web/admin/"]
             [""                  "/web/admin/"]
             ["/web/x?y=1"        "/web/x?y=1"]
             ["/web/x#frag"       "/web/x#frag"]]]
      (is (= expected (location return-to)) (pr-str return-to)))))

(deftest ^:contract ^:security error-pages-hide-the-exception
  ;; The exception message can carry driver or config detail (BOU-552).
  (let [boom (RuntimeException. "jdbc:postgresql://db:5432 password=hunter2")]
    (testing "login"
      (let [svc (reify ports/IUserService
                  (authenticate-user [_ _] (throw boom)))
            response ((web-handlers/login-submit-handler svc {})
                      {:form-params {"email" "user@example.com" "password" "password123"}})]
        (is (= 500 (:status response)))
        (is (not (html-contains? response "hunter2")))
        (is (html-contains? response "error-generic"))))
    (testing "change password, an untyped service error"
      (let [svc (reify ports/IUserService
                  (change-password [_ _ _ _]
                    (throw (ex-info "jdbc password=hunter2" {:type :internal-error}))))
            response ((web-handlers/password-change-handler svc {})
                      {:user {:id (UUID/randomUUID)}
                       :form-params {"current-password" "old-Password-1"
                                     "new-password" "new-Password-1"
                                     "confirm-password" "new-Password-1"}})]
        (is (= 500 (:status response)))
        (is (not (html-contains? response "hunter2")))
        (is (html-contains? response "error-generic"))))
    ;; The admin user-management pages had the same leak (BOU-555).
    (testing "admin users page"
      (let [svc (reify ports/IUserService
                  (list-users [_ _] (throw boom)))
            response ((web-handlers/users-page-handler svc {}) {})]
        (is (= 500 (:status response)))
        (is (not (html-contains? response "hunter2")))
        (is (html-contains? response "error-generic"))))
    (testing "admin user edit"
      (let [user (create-test-user {})
            svc  (reify ports/IUserService
                   (get-user-by-id [_ _] user)
                   (update-user-profile [_ _] (throw boom)))
            response ((web-handlers/update-user-htmx-handler svc {})
                      {:path-params {:id (str (:id user))}
                       :form-params {"name" "Test User" "email" "test@example.com"
                                     "role" "user" "active" "on"}})]
        (is (= 500 (:status response)))
        (is (not (html-contains? response "hunter2")))
        (is (html-contains? response "error-generic"))))
    (testing "admin bulk action, an untyped ex-info"
      (let [svc (reify ports/IUserService
                  (deactivate-user [_ _]
                    (throw (ex-info "jdbc password=hunter2" {:type :internal-error}))))
            response ((web-handlers/bulk-update-users-htmx-handler svc {})
                      {:form-params {"ids[]" (str (UUID/randomUUID))
                                     "action" "deactivate"}})]
        (is (= 500 (:status response)))
        (is (not (html-contains? response "hunter2")))
        (is (html-contains? response "error-generic"))))
    (testing "a curated bad-request message is still shown"
      (let [response ((web-handlers/bulk-update-users-htmx-handler (create-mock-service) {})
                      {:form-params {"ids[]" (str (UUID/randomUUID))
                                     "action" "explode"}})]
        (is (= 400 (:status response)))
        (is (html-contains? response "Unsupported bulk action"))))
    (testing "MFA setup, where the service folds the exception into its result"
      (let [mfa-svc  {:user-repository
                      (reify ports/IUserRepository
                        (find-user-by-id [_ _] (throw boom)))}
            response ((web-handlers/mfa-setup-initiate-handler mfa-svc {})
                      {:user {:id (UUID/randomUUID) :email "a@b.c"}})]
        (is (= 500 (:status response)))
        (is (not (html-contains? response "hunter2")))
        (is (html-contains? response "error-generic"))))))

(deftest ^:contract ^:security session-tokens-stay-out-of-the-log
  ;; A session token in the log is a login for whoever reads it (BOU-556).
  (let [token  "tok-3f9a1c-secret"
        logged (fn [f] (log-test/with-log (f) (pr-str (log-test/the-log))))]
    (testing "web login"
      (let [svc (reify ports/IUserService
                  (authenticate-user [_ _]
                    {:authenticated true
                     :user    {:id (UUID/randomUUID) :role :user}
                     :session {:session-token token}}))
            out (logged #((web-handlers/login-submit-handler svc {})
                          {:form-params {"email" "user@example.com" "password" "password123"}}))]
        (is (str/includes? out "Login successful") "the log was not captured")
        (is (not (str/includes? out token)))))
    (testing "admin sessions page"
      (let [user (create-test-user {})
            svc  (reify ports/IUserService
                   (get-user-by-id [_ _] user)
                   (get-user-sessions [_ _]
                     [{:id (UUID/randomUUID) :user-id (:id user) :session-token token
                       :created-at (Instant/now) :expires-at (Instant/now)}]))
            out (logged #((web-handlers/user-sessions-page-handler svc {})
                          {:path-params {:id (str (:id user))}}))]
        (is (str/includes? out "Sessions retrieved") "the log was not captured")
        (is (not (str/includes? out token)))))))

(deftest ^:unit profile-edits-send-only-the-edited-fields
  ;; The whole user went along, so a role or active read before a concurrent
  ;; demotion or deactivation was written back over it (BOU-685).
  (let [uid   (UUID/randomUUID)
        calls (atom [])
        svc   (reify ports/IUserService
                (get-user-by-id [_ id] {:id id :email "a@example.com" :name "A" :role :admin :active true})
                (update-user-profile [_ u]
                  (swap! calls conj u)
                  (merge {:email "a@example.com" :name "A" :role :admin :active true} u)))]
    ((web-handlers/profile-edit-handler svc {}) {:user {:id uid} :form-params {"name" "New"}})
    ((web-handlers/preferences-edit-handler svc {})
     {:user {:id uid} :form-params {"date-format" "iso" "time-format" "24h" "language" "en"}})
    (is (= [#{:id :name} #{:id :date-format :time-format :language}]
           (map (comp set keys) @calls)))))

(deftest ^:unit sessions-are-revoked-by-id
  ;; The page posted each session's token, so it showed them; it names them by
  ;; id now, and keep-current compared a key no session had (BOU-696).
  (let [uid     (UUID/randomUUID)
        current (UUID/randomUUID)
        others  [(UUID/randomUUID) (UUID/randomUUID)]
        revoked (atom [])
        svc     (reify ports/IUserService
                  (logout-user-everywhere [_ user-id keep-session-id]
                    (swap! revoked conj [user-id keep-session-id])
                    2)
                  (revoke-user-session [_ user-id session-id]
                    (swap! revoked conj [user-id session-id])
                    {:invalidated true}))]
    (testing "revoke-all keeping the current session"
      ((web-handlers/revoke-all-sessions-handler svc {})
       {:path-params {:id (str uid)} :form-params {"keep-current" "true"} :session {:id current}})
      (is (= [[uid current]] @revoked)))
    (testing "revoke-all"
      (reset! revoked [])
      ((web-handlers/revoke-all-sessions-handler svc {})
       {:path-params {:id (str uid)} :session {:id current}})
      (is (= [[uid nil]] @revoked)))
    (testing "one session, named in the path"
      (reset! revoked [])
      (let [resp ((web-handlers/revoke-session-handler svc {})
                  {:path-params {:id (str uid) :session-id (str (first others))}})]
        (is (= (str "/web/users/" uid "/sessions") (get-in resp [:headers "HX-Redirect"])))
        (is (re-find #"\"type\":\"success\"" (get-in resp [:headers "X-Toast"])))
        (is (= [[uid (first others)]] @revoked))))
    (testing "a malformed id is a 400, said in a toast: htmx swaps no 400 body"
      (let [resp ((web-handlers/revoke-session-handler svc {})
                  {:path-params {:id (str uid) :session-id "nope"}})]
        (is (= 400 (:status resp)))
        (is (re-find #"\"showToast\".*error-invalid-session" (get-in resp [:headers "HX-Trigger"])))))))

(defn- mfa-user-service [id password]
  (mfa/create-mfa-service (mem/->MemoryUserRepository (atom {id (mem/mfa-user id password)}))
                          {} {:auth-config {:max-failed-attempts 2}}))

(deftest ^:contract ^:security mfa-disable-form-needs-proof-and-locks-out
  ;; It checked the password, but did not count wrong ones, so a session
  ;; could guess at it (BOU-782).
  (let [id      (UUID/randomUUID)
        handler (web-handlers/mfa-disable-handler (mfa-user-service id "Right-Pass-1!") {})
        post    #(handler {:user {:id id} :form-params %})]
    (testing "a wrong password re-renders the form"
      (let [resp (post {"password" "wrong"})]
        (is (= 400 (:status resp)))
        (is (html-contains? resp "name=\"code\""))))
    (testing "the second wrong one locks, and then the right one is refused too"
      (post {"password" "wrong"})
      (is (= 429 (:status (post {"password" "Right-Pass-1!"})))))))

(deftest ^:contract mfa-disable-form-redirects-on-the-right-password
  (let [id   (UUID/randomUUID)
        resp ((web-handlers/mfa-disable-handler (mfa-user-service id "Right-Pass-1!") {})
              {:user {:id id} :form-params {"password" "Right-Pass-1!"}})]
    (is (= 302 (:status resp)))
    (is (has-header? resp "Location" "/web/profile"))))

(deftest ^:contract ^:security password-change-counts-wrong-passwords-and-locks-out
  ;; A wrong current password did not count, so a session could guess it
  ;; (BOU-785).
  (let [id      (UUID/randomUUID)
        users   (atom {id (mem/mfa-user id "Right-Pass-1!")})
        repo    (mem/->MemoryUserRepository users)
        sess    (mem/->MemorySessionRepository (atom {}))
        svc     (service/create-user-service repo sess (mem/->MemoryAuditRepository (atom []))
                                             {} (auth/create-authentication-service
                                                 repo sess nil {:max-failed-attempts 2}))
        handler (web-handlers/password-change-handler svc {})
        post    #(handler {:user {:id id}
                           :form-params {"current-password" %
                                         "new-password" "New-Horse-22!" "confirm-password" "New-Horse-22!"}})]
    (is (= 400 (:status (post "wrong"))))
    (is (= 1 (get-in @users [id :failed-login-count])))
    (post "wrong")
    (testing "locked: the right password is refused too"
      (let [resp (post "Right-Pass-1!")]
        (is (= 429 (:status resp)))
        (is (html-contains? resp "too-many-attempts"))))
    (testing "once the lockout has passed, the right password changes it and resets the count"
      (swap! users update id dissoc :lockout-until)
      (is (= 200 (:status (post "Right-Pass-1!"))))
      (is (zero? (get-in @users [id :failed-login-count]))))))

(deftest ^:contract mfa-disable-page-offers-the-password-and-a-code
  ;; A user who signs in through OIDC knows no password, so the code is there too.
  (let [id   (UUID/randomUUID)
        page ((web-handlers/mfa-disable-page-handler (mfa-user-service id nil) {}) {:user {:id id}})]
    (is (html-contains? page "name=\"password\""))
    (is (html-contains? page "name=\"code\""))))

;; =============================================================================
;; The app's error page (BOU-800)
;; =============================================================================

(deftest ^:unit the-error-page-is-the-apps-layout-with-the-reference
  (let [user {:id (UUID/randomUUID) :name "Ada Admin" :role :admin}
        page (fn [error] (web-handlers/error-page (assoc dutch :user user) error))]
    (testing "a client error shows its message and the reference to quote"
      (let [body (page {:status 404 :message "Entity not found" :correlation-id "cid-800"})]
        (is (str/includes? body "<html"))
        (is (str/includes? body "Ada Admin") "the layout's navigation, signed in")
        (is (str/includes? body "Pagina niet gevonden"))
        (is (str/includes? body "Entity not found"))
        (is (str/includes? body "Referentie: <code>cid-800</code>"))))
    (testing "a server error says nothing of the cause"
      (let [body (page {:status 500 :message "Internal Server Error" :correlation-id "cid-500"})]
        (is (str/includes? body "Er ging iets mis. Probeer het later opnieuw."))
        (is (not (str/includes? body "Internal Server Error")))
        (is (str/includes? body "cid-500"))))))
