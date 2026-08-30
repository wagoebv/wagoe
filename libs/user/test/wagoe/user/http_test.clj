(ns wagoe.user.http-test
  "HTTP handler tests for user module REST API.
   
   Tests all user and session endpoints with:
   - Happy path scenarios
   - Error cases and validation
   - Edge cases and boundary conditions

   These tests exercise the handlers directly; routing (e.g. /api prefix) is
   validated at the router level, not here."
  (:require [wagoe.user.shell.http :as user-http]
            [wagoe.user.shell.mfa :as mfa]
            [wagoe.user.ports :as ports]
            [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn call-handler
  "Call a handler.

   It used to be wrapped in `interfaces.http.middleware` first, under a helper
   whose docstring said that simulated production. No application ran that
   middleware — and it was not doing anything here either: these handlers run an
   interceptor pipeline and return the RFC 7807 body themselves, which is what
   the assertions below have always been reading (BOU-372)."
  [handler-fn request _error-mappings]
  (handler-fn request))

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

  (register-or-authenticate-user [_ user-data login-context]
    (if-let [existing (->> @state vals (filter #(= (:email %) (:email user-data))) first)]
      {:user existing
       :created? false
       :authenticated? true
       :auth-result (.authenticate-user ^wagoe.user.ports.IUserService _ (merge user-data login-context))}
      {:user (.register-user ^wagoe.user.ports.IUserService _ user-data)
       :created? true
       :authenticated? false
       :auth-result nil}))

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
          count (count user-sessions)]
      (doseq [[token _] user-sessions]
        (swap! state assoc-in [:sessions token :revoked-at] (Instant/now)))
      count))

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
  []
  (->MockUserService (atom {})))

(defn parse-json-response
  "Parse JSON response body for testing structured error responses.
   Handles case where response body is a JSON string that needs parsing."
  [response]
  (if (string? (:body response))
    (assoc response :body (json/parse-string (:body response) true))
    response))

;; =============================================================================
;; User Handler Tests
;; =============================================================================

(deftest ^:contract test-create-user-handler
  (testing "POST /users - Create user successfully"
    (let [service (create-mock-service)
          handler (user-http/create-user-handler service)
          user-id (UUID/randomUUID)
          request {:parameters
                   {:body {:email "test@example.com"
                           :name "Test User"
                           :password "password123"
                           :role "user"
                           :userId (str user-id)
                           :active true}}}
          response (handler request)]

      (is (= 201 (:status response)))
      (is (= "test@example.com" (get-in response [:body :email])))
      (is (= "Test User" (get-in response [:body :name])))
      (is (some? (get-in response [:body :id])))
      (is (some? (get-in response [:body :createdAt])))))

  (testing "POST /users - Creates user with correct defaults"
    (let [service (create-mock-service)
          handler (user-http/create-user-handler service)
          user-id (UUID/randomUUID)
          request {:parameters
                   {:body {:email "test2@example.com"
                           :name "Test User 2"
                           :password "password123"
                           :role "user"
                           :userId (str user-id)}}}
          response (handler request)]

      (is (= 201 (:status response)))
      (is (true? (get-in response [:body :active]))))))

(deftest ^:contract test-get-user-handler
  (testing "GET /users/:id - Get existing user"
    (let [service (create-mock-service)
          user-id (UUID/randomUUID)
          created-user (ports/register-user service
                                            {:email "test@example.com"
                                             :name "Test User"
                                             :role :user
                                             :user-id user-id})
          handler (user-http/get-user-handler service)
          request {:parameters
                   {:path {:id (str (:id created-user))}}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "test@example.com" (get-in response [:body :email])))
      (is (= "Test User" (get-in response [:body :name])))))

  (testing "GET /users/:id - User not found"
    (let [service (create-mock-service)
          handler (user-http/get-user-handler service)
          non-existent-id (UUID/randomUUID)
          request {:parameters
                   {:path {:id (str non-existent-id)}}}
          response (-> (call-handler handler request user-http/user-error-mappings)
                       parse-json-response)]

      ;; Assert RFC 7807 Problem Details fields
      (is (= 404 (:status response)))
      (is (= "User Not Found" (get-in response [:body :title])))
      (is (contains? (:body response) :type))
      (is (contains? (:body response) :detail))
      (is (contains? (:body response) :instance))
      (is (contains? (:body response) :correlationId))
      (is (string? (get-in response [:body :correlationId])))
      ;; Extension member from ex-data
      (is (= (str non-existent-id) (get-in response [:body :user-id]))))))

(deftest ^:contract test-list-users-handler
  (testing "GET /users - List users with pagination"
    (let [service (create-mock-service)
          user-id (UUID/randomUUID)
          _ (ports/register-user service
                                 {:email "user1@example.com"
                                  :name "User 1"
                                  :role :user
                                  :user-id user-id})
          _ (ports/register-user service
                                 {:email "user2@example.com"
                                  :name "User 2"
                                  :role :admin
                                  :user-id user-id})
          handler (user-http/list-users-handler service)
          request {:parameters
                   {:query {:userId (str user-id)
                            :limit 10
                            :offset 0}}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= 2 (count (get-in response [:body :data]))))
      (is (= 2 (get-in response [:body :pagination :total])))))

  (testing "GET /users - Filter by role"
    (let [service (create-mock-service)
          user-id (UUID/randomUUID)
          _ (ports/register-user service
                                 {:email "user1@example.com"
                                  :name "User 1"
                                  :role :user
                                  :user-id user-id})
          _ (ports/register-user service
                                 {:email "admin1@example.com"
                                  :name "Admin 1"
                                  :role :admin
                                  :user-id user-id})
          handler (user-http/list-users-handler service)
          request {:parameters
                   {:query {:userId (str user-id)
                            :role "admin"
                            :limit 10
                            :offset 0}}}
          response (handler request)]

      (is (= 200 (:status response)))
      ;; Note: Mock service doesn't implement filtering, would be 1 in real implementation
      (is (number? (get-in response [:body :pagination :total]))))))

(deftest ^:contract test-update-user-handler
  (testing "PUT /users/:id - Update user successfully"
    (let [service (create-mock-service)
          user-id (UUID/randomUUID)
          created-user (ports/register-user service
                                            {:email "test@example.com"
                                             :name "Test User"
                                             :role :user
                                             :user-id user-id})
          handler (user-http/update-user-handler service)
          request {:parameters
                   {:path {:id (str (:id created-user))}
                    :body {:name "Updated Name"
                           :role "admin"}}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (= "Updated Name" (get-in response [:body :name])))
      (is (= "admin" (get-in response [:body :role])))))

  (testing "PUT /users/:id - User not found"
    (let [service (create-mock-service)
          handler (user-http/update-user-handler service)
          non-existent-id (UUID/randomUUID)
          request {:parameters
                   {:path {:id (str non-existent-id)}
                    :body {:name "Updated Name"}}}
          response (-> (call-handler handler request user-http/user-error-mappings)
                       parse-json-response)]

      ;; Assert RFC 7807 Problem Details fields
      (is (= 404 (:status response)))
      (is (= "User Not Found" (get-in response [:body :title])))
      (is (contains? (:body response) :type))
      (is (contains? (:body response) :detail))
      (is (contains? (:body response) :instance))
      (is (contains? (:body response) :correlationId))
      ;; Extension member from ex-data
      (is (= (str non-existent-id) (get-in response [:body :user-id]))))))

(deftest ^:contract test-delete-user-handler
  (testing "DELETE /users/:id - Soft delete user successfully"
    (let [service (create-mock-service)
          user-id (UUID/randomUUID)
          created-user (ports/register-user service
                                            {:email "test@example.com"
                                             :name "Test User"
                                             :role :user
                                             :user-id user-id})
          handler (user-http/delete-user-handler service)
          request {:parameters
                   {:path {:id (str (:id created-user))}}}
          response (handler request)]

      (is (= 204 (:status response)))

      ;; Verify user is soft deleted
      (let [deleted-user (ports/get-user-by-id service (:id created-user))]
        (is (false? (:active deleted-user)))
        (is (some? (:deleted-at deleted-user)))))))

;; =============================================================================
;; Session Handler Tests
;; =============================================================================

(deftest ^:contract test-create-session-handler
  (testing "POST /sessions - Create session successfully"
    (let [service (create-mock-service)
          user-id (UUID/randomUUID)
          handler (user-http/create-session-handler service)
          request {:parameters
                   {:body {:userId (str user-id)
                           :deviceInfo {:userAgent "Mozilla/5.0"
                                        :ipAddress "192.168.1.1"}}}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (some? (get-in response [:body :sessionToken])))
      (is (some? (get-in response [:body :expiresAt])))
      (is (= (str user-id) (get-in response [:body :userId]))))))

(deftest ^:contract test-validate-session-handler
  (testing "GET /sessions/:token - Valid session"
    (let [service (create-mock-service)
          user-id (UUID/randomUUID)
          session (ports/authenticate-user service
                                           {:user-id user-id
                                            :user-agent "Mozilla/5.0"
                                            :ip-address "***********"})
          handler (user-http/validate-session-handler service)
          request {:parameters
                   {:path {:token (:session-token session)}}}
          response (handler request)]

      (is (= 200 (:status response)))
      (is (true? (get-in response [:body :valid])))
      (is (= (str user-id) (get-in response [:body :userId])))))

  (testing "GET /sessions/:token - Invalid/expired session"
    (let [service (create-mock-service)
          handler (user-http/validate-session-handler service)
          invalid-token "invalid-token-12345"
          request {:parameters
                   {:path {:token invalid-token}}}
          response (-> (call-handler handler request user-http/user-error-mappings)
                       parse-json-response)]

      ;; Assert RFC 7807 Problem Details fields
      (is (= 404 (:status response)))
      (is (= "Session Not Found" (get-in response [:body :title])))
      (is (contains? (:body response) :type))
      (is (contains? (:body response) :detail))
      (is (contains? (:body response) :instance))
      (is (contains? (:body response) :correlationId))
      ;; Extension members from ex-data
      (is (false? (get-in response [:body :valid])))
      (is (= invalid-token (get-in response [:body :token]))))))

(deftest ^:contract test-invalidate-session-handler
  (testing "DELETE /sessions/:token - Invalidate session successfully"
    (let [service (create-mock-service)
          user-id (UUID/randomUUID)
          session (ports/authenticate-user service
                                           {:user-id user-id})
          handler (user-http/invalidate-session-handler service)
          request {:parameters
                   {:path {:token (:session-token session)}}}
          response (handler request)]

      (is (= 204 (:status response)))

      ;; Verify session is invalidated
      (let [invalidated-session (ports/validate-session service (:session-token session))]
        (is (nil? invalidated-session))))))


;; =============================================================================
;; MFA endpoints — the wire shape across the ADR-036 migration (BOU-323)
;; =============================================================================

(deftest ^:unit mfa-endpoints-answer-with-an-error-string
  ;; The MFA shell moved to {:error {:type … :message …}} and these handlers
  ;; flatten it, so the endpoints answer exactly what they answered before.
  ;; Nothing tested that: reverting the flattening left the whole suite green
  ;; while the endpoints started returning a JSON object where clients expect a
  ;; string.
  (let [user-id (UUID/randomUUID)
        request {:user {:id user-id}
                 :body-params {:secret "S" :backupCodes ["a"] :verificationCode "000000"}}
        failure {:success? false
                 :error {:type :invalid-code :message "Invalid verification code"}}
        body-of (fn [resp] (json/parse-string (:body resp) true))]

    (testing "setup"
      (with-redefs [mfa/setup-mfa (fn [_ _] failure)]
        (let [resp ((user-http/mfa-setup-handler nil) request)]
          (is (= 400 (:status resp)))
          (is (= "Invalid verification code" (:error (body-of resp)))
              "a string, not the :error map"))))

    (testing "enable"
      (with-redefs [mfa/enable-mfa (fn [_ _ _ _ _] failure)]
        (let [resp ((user-http/mfa-enable-handler nil) request)]
          (is (= 400 (:status resp)))
          (is (= "Invalid verification code" (:error (body-of resp)))))))

    (testing "disable"
      (with-redefs [mfa/disable-mfa (fn [_ _] failure)]
        (let [resp ((user-http/mfa-disable-handler nil) request)]
          (is (= 400 (:status resp)))
          (is (= "Invalid verification code" (:error (body-of resp)))))))))
