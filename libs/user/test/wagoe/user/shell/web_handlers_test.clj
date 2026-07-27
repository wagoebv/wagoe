(ns wagoe.user.shell.web-handlers-test
  "Tests for user web UI handlers.
   
   Tests cover:
   - Page handlers (full HTML pages)
   - HTMX fragment handlers (partial updates)
   - Validation and error handling
   
   - HTML response structure"
  (:require [wagoe.user.shell.web-handlers :as web-handlers]
            [wagoe.user.ports :as ports]
            [wagoe.email.ports :as email-ports]
            [clojure.test :refer [deftest testing is]]
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
      (is (html-contains? response "Database connection failed")))))

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
      (is (= "/web/dashboard" (get-in response [:headers "Location"]))))))

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
          request {:path-params {:id (str (UUID/randomUUID))}}
          response (handler request)]

      (is (= 404 (:status response)))
      (is (html-contains? response "User Not Found"))))

  (testing "returns 400 for invalid UUID"
    (let [service (create-mock-service)
          config {:active {:wagoe/settings {:user-limits {:max-users 1000}}}}
          handler (web-handlers/user-detail-page-handler service config)
          request {:path-params {:id "not-a-uuid"}}
          response (handler request)]

      (is (= 400 (:status response)))
      (is (html-contains? response "Invalid User ID"))))

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
      (is (html-contains? response "Database error")))))

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

      (is (= 200 (:status response))))))

;; =============================================================================
;; HTMX Fragment Handler Tests
;; =============================================================================

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
      (is (html-contains? response "Connection timeout")))))

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
      (is (html-contains? response "Email already exists"))))

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
      ;; Now returns success banner and form instead of separate success page
      (is (html-contains? response "User updated successfully"))
      (is (html-contains? response "success-banner"))
      (is (html-contains? response "Updated Name"))))

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
      (is (html-contains? response "Invalid user ID"))))

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
      (is (html-contains? response "Database error")))))

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
      (is (html-contains? response "Invalid user ID"))))

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
                    (deactivate-user [_ _]
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
      (is (html-contains? response "Cannot delete user with active sessions")))))

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
      (testing "secure when config enables it (e.g. prod/acc over HTTPS)"
        (is (true? (cookie-secure {:wagoe/settings {:secure-cookies? true}}))))
      (testing "insecure when config disables it (local HTTP dev)"
        (is (false? (cookie-secure {:wagoe/settings {:secure-cookies? false}}))))
      (testing "defaults to secure when unset (fail-secure)"
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
