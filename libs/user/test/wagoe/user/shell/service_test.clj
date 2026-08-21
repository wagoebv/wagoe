(ns wagoe.user.shell.service-test
  (:require [wagoe.user.shell.auth :as auth-shell]
            [wagoe.user.shell.service :as sut]
            [wagoe.cache.ports :as cache-ports]
            [wagoe.user.ports :as ports]
            [clojure.test :refer [deftest is testing]])
  (:import (java.time Instant)
           (java.util UUID)))

(defrecord SessionRepoStub [session]
  ports/IUserSessionRepository
  (create-session [_ session-entity] session-entity)
  (find-session-by-token [_ _] session)
  (find-sessions-by-user [_ _] [])
  (invalidate-session [_ _] true)
  (invalidate-all-user-sessions [_ _] 0)
  (cleanup-expired-sessions [_ _] 0)
  (update-session [_ _] nil)
  (find-all-sessions [_] [])
  (delete-session [_ _] true))

(defrecord UserRepoStub [state]
  ports/IUserRepository
  (find-user-by-id [_ user-id]
    (get @state user-id))
  (find-user-by-email [_ email]
    (->> (vals @state)
         (filter #(= email (:email %)))
         first))
  (find-users [_ _options]
    {:users [] :total-count 0})
  (create-user [_ user-entity]
    (swap! state assoc (:id user-entity) user-entity)
    user-entity)
  (update-user [_ user-entity]
    (swap! state assoc (:id user-entity) user-entity)
    user-entity)
  (soft-delete-user [_ _user-id] true)
  (hard-delete-user [_ _user-id] true)
  (find-active-users-by-role [_ _role] [])
  (count-users [_] (count @state))
  (find-users-created-since [_ _since-date] [])
  (find-users-by-email-domain [_ _email-domain] [])
  (create-users-batch [_ _user-entities] [])
  (update-users-batch [_ _user-entities] []))

(defrecord AuditRepoStub []
  ports/IUserAuditRepository
  (create-audit-log [_ audit-entity] audit-entity)
  (find-audit-logs [_ _options] {:audit-logs [] :total-count 0})
  (find-audit-logs-by-user [_ _user-id _options] [])
  (find-audit-logs-by-actor [_ _actor-id _options] [])
  (count-audit-logs [_ _filters] 0))

(defrecord AuditRepoCapture [entries]
  ports/IUserAuditRepository
  (create-audit-log [_ audit-entity]
    (swap! entries conj audit-entity)
    audit-entity)
  (find-audit-logs [_ _options] {:audit-logs [] :total-count 0})
  (find-audit-logs-by-user [_ _user-id _options] [])
  (find-audit-logs-by-actor [_ _actor-id _options] [])
  (count-audit-logs [_ _filters] (count @entries)))

(defrecord CacheStub [state ops]
  cache-ports/ICache
  (get-value [_ key]
    (swap! ops conj [:get key])
    (get @state key))
  (set-value! [_ key value]
    (swap! ops conj [:set key value nil])
    (swap! state assoc key value)
    true)
  (set-value! [_ key value ttl-seconds]
    (swap! ops conj [:set key value ttl-seconds])
    (swap! state assoc key value)
    true)
  (delete-key! [_ key]
    (swap! ops conj [:delete key])
    (swap! state dissoc key)
    true)
  (exists? [_ key]
    (contains? @state key))
  (ttl [_ _key] nil)
  (expire! [_ _key _ttl-seconds] true))

(deftest ^:unit validate-session-survives-nil-update-test
  (testing "a valid session is returned even when update-session returns nil"
    (let [user-id (UUID/randomUUID)
          session {:id (UUID/randomUUID)
                   :user-id user-id
                   :session-token "token-123"
                   :expires-at (.plusSeconds (Instant/now) 3600)
                   :created-at (Instant/now)
                   :last-accessed-at nil
                   :revoked-at nil}
          service (sut/->UserService
                   nil
                   (->SessionRepoStub session)
                   nil
                   {}
                   nil
                   nil)]
      (is (= "token-123"
             (:session-token (ports/validate-session service "token-123"))))
      (is (= user-id
             (:user-id (ports/validate-session service "token-123")))))))

(deftest ^:unit register-or-authenticate-user-test
  (testing "registers a brand new user when email does not exist yet"
    (let [state (atom {})
          service (sut/->UserService
                   (->UserRepoStub state)
                   nil
                   (->AuditRepoStub)
                   {}
                   nil
                   nil)]
      (with-redefs [sut/generate-user-id (fn [] #uuid "00000000-0000-0000-0000-000000000111")
                    sut/current-timestamp (fn [] (Instant/parse "2026-03-24T10:00:00Z"))
                    auth-shell/hash-password (fn [_] (apply str (repeat 60 "x")))
                    auth-shell/authenticate-user
                    (fn [_auth-service email password login-context]
                      (is (= "new@example.nl" email))
                      (is (= "Password123!" password))
                      (is (= "127.0.0.1" (:ip-address login-context)))
                      {:success? true
                       :user {:id #uuid "00000000-0000-0000-0000-000000000111"
                              :email email}
                       :session {:session-token "session-new"}})]
        (let [result (ports/register-or-authenticate-user
                      service
                      {:name "New User"
                       :email "new@example.nl"
                       :password "Password123!"
                       :role :user
                       :active true}
                      {:ip-address "127.0.0.1" :user-agent "test"})]
          (is (= true (:created? result)))
          (is (= false (:authenticated? result)))
          (is (= "new@example.nl" (get-in result [:user :email])))))))

  (testing "authenticates an existing user instead of creating a duplicate"
    (let [user-id (UUID/randomUUID)
          existing-user {:id user-id
                         :name "Existing User"
                         :email "existing@example.nl"
                         :password-hash "stored-hash"
                         :role :user
                         :active true}
          state (atom {user-id existing-user})
          service (sut/->UserService
                   (->UserRepoStub state)
                   nil
                   (->AuditRepoStub)
                   {}
                   nil
                   nil)]
      (with-redefs [auth-shell/authenticate-user
                    (fn [_auth-service email password login-context]
                      (is (= "existing@example.nl" email))
                      (is (= "Password123!" password))
                      (is (= "127.0.0.1" (:ip-address login-context)))
                      {:success? true
                       :user (dissoc existing-user :password-hash)
                       :session {:session-token "session-123"}})]
        (let [result (ports/register-or-authenticate-user
                      service
                      {:name "Existing User"
                       :email "existing@example.nl"
                       :password "Password123!"
                       :role :user
                       :active true}
                      {:ip-address "127.0.0.1" :user-agent "test"})]
          (is (= false (:created? result)))
          (is (= true (:authenticated? result)))
          (is (= user-id (get-in result [:user :id])))
          (is (= "session-123" (get-in result [:auth-result :session :session-token]))))))))

(deftest ^:unit claim-user-identity-test
  (testing "registers a new user and returns an authenticated session"
    (let [state (atom {})
          service (sut/->UserService
                   (->UserRepoStub state)
                   nil
                   (->AuditRepoStub)
                   {}
                   nil
                   nil)]
      (with-redefs [sut/generate-user-id (fn [] #uuid "00000000-0000-0000-0000-000000000121")
                    sut/current-timestamp (fn [] (Instant/parse "2026-03-24T10:00:00Z"))
                    auth-shell/hash-password (fn [_] (apply str (repeat 60 "x")))
                    auth-shell/authenticate-user
                    (fn [_auth-service email password login-context]
                      (is (= "new@example.nl" email))
                      (is (= "Password123!" password))
                      (is (= "127.0.0.1" (:ip-address login-context)))
                      {:success? true
                       :user {:id #uuid "00000000-0000-0000-0000-000000000121"
                              :email email}
                       :session {:session-token "session-new"}})]
        (let [result (ports/claim-user-identity
                      service
                      {:user-data {:name "New User"
                                   :email "new@example.nl"
                                   :password "Password123!"
                                   :role :user
                                   :active true}
                       :login-context {:ip-address "127.0.0.1" :user-agent "test"}})]
          (is (= :registered (:mode result)))
          (is (= true (:created? result)))
          (is (= true (:authenticated? result)))
          (is (= "session-new" (get-in result [:auth-result :session :session-token])))))))

  (testing "authenticates an existing user and keeps :mode as :authenticated"
    (let [user-id (UUID/randomUUID)
          existing-user {:id user-id
                         :name "Existing User"
                         :email "existing@example.nl"
                         :password-hash "stored-hash"
                         :role :user
                         :active true}
          state (atom {user-id existing-user})
          service (sut/->UserService
                   (->UserRepoStub state)
                   nil
                   (->AuditRepoStub)
                   {}
                   nil
                   nil)]
      (with-redefs [auth-shell/authenticate-user
                    (fn [_auth-service email _password _login-context]
                      {:success? true
                       :user {:id user-id :email email}
                       :session {:session-token "session-existing"}})]
        (let [result (ports/claim-user-identity
                      service
                      {:user-data {:name "Existing User"
                                   :email "existing@example.nl"
                                   :password "Password123!"
                                   :role :user
                                   :active true}
                       :login-context {:ip-address "127.0.0.1" :user-agent "test"}})]
          (is (= :authenticated (:mode result)))
          (is (= false (:created? result)))
          (is (= true (:authenticated? result)))
          (is (= "session-existing" (get-in result [:auth-result :session :session-token])))))))

  (testing "rejects an existing account when authentication fails"
    (let [user-id (UUID/randomUUID)
          existing-user {:id user-id
                         :name "Existing User"
                         :email "existing@example.nl"
                         :password-hash "stored-hash"
                         :role :user
                         :active true}
          state (atom {user-id existing-user})
          service (sut/->UserService
                   (->UserRepoStub state)
                   nil
                   (->AuditRepoStub)
                   {}
                   nil
                   nil)]
      (with-redefs [auth-shell/authenticate-user
                    (fn [_auth-service _email _password _login-context]
                      ;; The shape auth-shell produces since ADR-036 — a stub
                      ;; that lies about its producer teaches the wrong
                      ;; convention to whoever copies it next (BOU-323).
                      {:success? false
                       :error {:type :invalid-credentials :message "Nope"}})]
        (let [ex (is (thrown? clojure.lang.ExceptionInfo
                              (ports/claim-user-identity
                               service
                               {:user-data {:email "existing@example.nl"
                                            :password "Password123!"}
                                :login-context {:ip-address "127.0.0.1"}})))]
          (is (= :unauthorized (:type (ex-data ex))))
          (is (= "existing@example.nl" (:email (ex-data ex))))))))

  (testing "fails loudly when a fresh account cannot authenticate afterwards"
    (let [state (atom {})
          service (sut/->UserService
                   (->UserRepoStub state)
                   nil
                   (->AuditRepoStub)
                   {}
                   nil
                   nil)]
      (with-redefs [sut/generate-user-id (fn [] #uuid "00000000-0000-0000-0000-000000000131")
                    sut/current-timestamp (fn [] (Instant/parse "2026-03-24T10:00:00Z"))
                    auth-shell/hash-password (fn [_] (apply str (repeat 60 "x")))
                    auth-shell/authenticate-user
                    (fn [_auth-service _email _password _login-context]
                      {:success? false
                       :error {:type :session-creation-failed :message "session boom"}})]
        (let [ex (is (thrown? clojure.lang.ExceptionInfo
                              (ports/claim-user-identity
                               service
                               {:user-data {:name "New User"
                                            :email "new@example.nl"
                                            :password "Password123!"
                                            :role :user
                                            :active true}
                                :login-context {:ip-address "127.0.0.1"}})))]
          (is (= :internal-error (:type (ex-data ex))))
          (is (= "new@example.nl" (:email (ex-data ex)))))))))

(deftest ^:unit permanently-delete-user-blocks-tenant-references-test
  (testing "hard delete is blocked when tenant references still exist"
    (let [user-id (UUID/randomUUID)
          user {:id user-id :email "existing@example.nl"}
          repo (reify ports/IUserRepository
                 (find-user-by-id [_ id] (when (= id user-id) user))
                 (find-user-by-email [_ _] nil)
                 (find-users [_ _options] {:users [] :total-count 0})
                 (create-user [_ user-entity] user-entity)
                 (update-user [_ user-entity] user-entity)
                 (soft-delete-user [_ _user-id] true)
                 (hard-delete-user [_ _user-id]
                   (throw (org.postgresql.util.PSQLException.
                           "update or delete on table \"users\" violates foreign key constraint \"fk_tenant_memberships_user\" on table \"tenant_memberships\""
                           org.postgresql.util.PSQLState/FOREIGN_KEY_VIOLATION)))
                 (find-active-users-by-role [_ _role] [])
                 (count-users [_] 0)
                 (find-users-created-since [_ _since-date] [])
                 (find-users-by-email-domain [_ _email-domain] [])
                 (create-users-batch [_ _user-entities] [])
                 (update-users-batch [_ _user-entities] []))
          service (sut/->UserService repo nil nil {} nil nil)]
      (try
        (ports/permanently-delete-user service user-id)
        (is false "Expected permanently-delete-user to throw")
        (catch clojure.lang.ExceptionInfo ex
          (is (= :hard-deletion-not-allowed (:type (ex-data ex)))))))))

(deftest ^:unit user-query-operations-test
  (testing "get-user-by-id strips password hash and caches the safe user"
    (let [user-id (UUID/randomUUID)
          repo-state (atom {user-id {:id user-id
                                     :email "cache@example.nl"
                                     :password-hash "secret"
                                     :role :user
                                     :active true}})
          cache-state (atom {})
          cache-ops (atom [])
          service (sut/->UserService
                   (->UserRepoStub repo-state)
                   nil
                   nil
                   {}
                   nil
                   (->CacheStub cache-state cache-ops))]
      (is (= {:id user-id
              :email "cache@example.nl"
              :role :user
              :active true}
             (ports/get-user-by-id service user-id)))
      (is (= {:id user-id
              :email "cache@example.nl"
              :role :user
              :active true}
             (get @cache-state (str "user:" user-id))))
      (is (some #(= [:set (str "user:" user-id)
                     {:id user-id
                      :email "cache@example.nl"
                      :role :user
                      :active true}
                     300]
                    %)
                @cache-ops))))

  (testing "get-user-by-email and list-users never return password hashes"
    (let [user-id (UUID/randomUUID)
          user {:id user-id
                :email "safe@example.nl"
                :password-hash "secret"
                :role :admin
                :active true}
          repo (reify ports/IUserRepository
                 (find-user-by-id [_ _] nil)
                 (find-user-by-email [_ email] (when (= "safe@example.nl" email) user))
                 (find-users [_ _options] {:users [user] :total-count 1})
                 (create-user [_ user-entity] user-entity)
                 (update-user [_ user-entity] user-entity)
                 (soft-delete-user [_ _user-id] true)
                 (hard-delete-user [_ _user-id] true)
                 (find-active-users-by-role [_ _role] [])
                 (count-users [_] 1)
                 (find-users-created-since [_ _since-date] [])
                 (find-users-by-email-domain [_ _email-domain] [])
                 (create-users-batch [_ _user-entities] [])
                 (update-users-batch [_ _user-entities] []))
          service (sut/->UserService repo nil nil {} nil nil)]
      (is (= (dissoc user :password-hash)
             (ports/get-user-by-email service "safe@example.nl")))
      (is (= {:users [(dissoc user :password-hash)] :total-count 1}
             (ports/list-users service {:limit 10}))))))

(deftest ^:unit user-mutation-operations-test
  (testing "logout invalidates cache and writes an audit entry for an existing session"
    (let [user-id (UUID/randomUUID)
          session-id (UUID/randomUUID)
          cache-state (atom {"session:token-1" {:cached true}})
          cache-ops (atom [])
          audit-entries (atom [])
          user {:id user-id :email "logout@example.nl" :role :user :active true}
          session {:id session-id
                   :user-id user-id
                   :session-token "token-1"
                   :ip-address "127.0.0.1"
                   :user-agent "browser"}
          service (sut/->UserService
                   (reify ports/IUserRepository
                     (find-user-by-id [_ id] (when (= id user-id) user))
                     (find-user-by-email [_ _] nil)
                     (find-users [_ _options] {:users [] :total-count 0})
                     (create-user [_ user-entity] user-entity)
                     (update-user [_ user-entity] user-entity)
                     (soft-delete-user [_ _user-id] true)
                     (hard-delete-user [_ _user-id] true)
                     (find-active-users-by-role [_ _role] [])
                     (count-users [_] 1)
                     (find-users-created-since [_ _since-date] [])
                     (find-users-by-email-domain [_ _email-domain] [])
                     (create-users-batch [_ _user-entities] [])
                     (update-users-batch [_ _user-entities] []))
                   (->SessionRepoStub session)
                   (->AuditRepoCapture audit-entries)
                   {}
                   nil
                   (->CacheStub cache-state cache-ops))]
      (is (= {:invalidated true :session-id session-id}
             (ports/logout-user service "token-1")))
      (is (nil? (get @cache-state "session:token-1")))
      (is (some #(= [:delete "session:token-1"] %) @cache-ops))
      (is (= 1 (count @audit-entries)))))

  (testing "update-user-profile invalidates cached user and keeps audit actor context"
    (let [user-id (UUID/randomUUID)
          now (Instant/parse "2026-03-25T09:00:00Z")
          cache-state (atom {(str "user:" user-id) {:cached true}})
          cache-ops (atom [])
          audit-entries (atom [])
          existing-user {:id user-id
                         :name "Old Name"
                         :email "profile@example.nl"
                         :role :user
                         :active true
                         :created-at (Instant/parse "2026-03-20T09:00:00Z")
                         :updated-at nil
                         :deleted-at nil}
          repo-state (atom {user-id existing-user})
          service (sut/->UserService
                   (->UserRepoStub repo-state)
                   nil
                   (->AuditRepoCapture audit-entries)
                   {}
                   nil
                   (->CacheStub cache-state cache-ops))]
      (with-redefs [sut/current-timestamp (fn [] now)]
        (sut/with-audit-context
          {:actor-id #uuid "00000000-0000-0000-0000-000000000901"
           :actor-email "admin@example.nl"
           :ip-address "10.0.0.1"
           :user-agent "test-suite"}
          (fn []
            (let [updated (ports/update-user-profile
                           service
                           {:id user-id
                            :name "New Name"
                            :email "profile@example.nl"
                            :role :user
                            :active true
                            :created-at (:created-at existing-user)
                            :updated-at nil
                            :deleted-at nil})]
              (is (= "New Name" (:name updated)))
              (is (= now (:updated-at (get @repo-state user-id))))
              (is (nil? (get @cache-state (str "user:" user-id))))
              (is (some #(= [:delete (str "user:" user-id)] %) @cache-ops))
              (is (= 1 (count @audit-entries)))
              (is (= #uuid "00000000-0000-0000-0000-000000000901"
                     (:actor-id (first @audit-entries))))
              (is (= "admin@example.nl"
                     (:actor-email (first @audit-entries))))))))))

  (testing "deactivate-user invalidates cache and returns true"
    (let [user-id (UUID/randomUUID)
          cache-state (atom {(str "user:" user-id) {:cached true}})
          cache-ops (atom [])
          audit-entries (atom [])
          user {:id user-id :email "deactivate@example.nl" :role :user :active true}
          service (sut/->UserService
                   (reify ports/IUserRepository
                     (find-user-by-id [_ id] (when (= id user-id) user))
                     (find-user-by-email [_ _] nil)
                     (find-users [_ _options] {:users [] :total-count 0})
                     (create-user [_ user-entity] user-entity)
                     (update-user [_ user-entity] user-entity)
                     (soft-delete-user [_ id] (= id user-id))
                     (hard-delete-user [_ _user-id] true)
                     (find-active-users-by-role [_ _role] [])
                     (count-users [_] 1)
                     (find-users-created-since [_ _since-date] [])
                     (find-users-by-email-domain [_ _email-domain] [])
                     (create-users-batch [_ _user-entities] [])
                     (update-users-batch [_ _user-entities] []))
                   nil
                   (->AuditRepoCapture audit-entries)
                   {}
                   nil
                   (->CacheStub cache-state cache-ops))]
      (is (= true (ports/deactivate-user service user-id)))
      (is (nil? (get @cache-state (str "user:" user-id))))
      (is (some #(= [:delete (str "user:" user-id)] %) @cache-ops))
      (is (= 1 (count @audit-entries))))))

(deftest ^:unit change-password-test
  (testing "change-password updates the stored hash and records an audit entry"
    (let [user-id (UUID/randomUUID)
          now (Instant/parse "2026-03-25T11:00:00Z")
          audit-entries (atom [])
          existing-user {:id user-id
                         :email "password@example.nl"
                         :password-hash "old-hash"
                         :role :user
                         :active true
                         :created-at (Instant/parse "2026-03-20T09:00:00Z")
                         :updated-at nil
                         :deleted-at nil}
          repo-state (atom {user-id existing-user})
          service (sut/->UserService
                   (->UserRepoStub repo-state)
                   #_{:clj-kondo/ignore [:missing-protocol-method]}
                   (reify ports/IUserSessionRepository
                     (invalidate-all-user-sessions [_ _] 0))
                   (->AuditRepoCapture audit-entries)
                   {:password-policy {:min-length 8}}
                   nil
                   nil)]
      (with-redefs [sut/current-timestamp (fn [] now)
                    auth-shell/verify-password (fn [plain hashed]
                                                 (and (= "Current123!" plain)
                                                      (= "old-hash" hashed)))
                    auth-shell/hash-password (fn [_] "new-hash")]
        (is (= true
               (ports/change-password service user-id "Current123!" "BetterPass123!")))
        (is (= "new-hash" (:password-hash (get @repo-state user-id))))
        (is (= now (:updated-at (get @repo-state user-id))))
        (is (= 1 (count @audit-entries)))))

    (testing "change-password rejects an invalid current password"
      (let [user-id (UUID/randomUUID)
            existing-user {:id user-id
                           :email "password@example.nl"
                           :password-hash "old-hash"
                           :role :user
                           :active true
                           :created-at (Instant/parse "2026-03-20T09:00:00Z")
                           :updated-at nil
                           :deleted-at nil}
            service (sut/->UserService
                     (->UserRepoStub (atom {user-id existing-user}))
                     nil
                     (->AuditRepoStub)
                     {:password-policy {:min-length 8}}
                     nil
                     nil)]
        (with-redefs [auth-shell/verify-password (fn [_ _] false)]
          (let [ex (is (thrown? clojure.lang.ExceptionInfo
                                (ports/change-password service user-id "wrong" "BetterPass123!")))]
            (is (= :invalid-current-password (:type (ex-data ex))))))))))

(deftest ^:unit failed-login-keeps-its-public-shape
  ;; auth-shell moved to the ADR-036 §3 return — {:error {:type … :message …}}
  ;; — and this service is the layer that flattens it. If the flattening is
  ;; wrong, every caller of `authenticate-user` silently gets nil for :reason
  ;; and :message: the login page shows an empty error, the audit log records
  ;; "Authentication failed" for everything, and no test would have noticed
  ;; (BOU-323).
  (let [user-id (UUID/randomUUID)
        state   (atom {user-id {:id user-id :email "a@example.nl" :active true}})
        service (sut/->UserService (->UserRepoStub state) nil (->AuditRepoStub) {} nil nil)
        retry   (Instant/parse "2026-08-21T10:15:00Z")]
    (with-redefs [auth-shell/authenticate-user
                  (fn [_ _ _ _]
                    {:success? false
                     :error {:type        :authentication-failed
                             :message     "Account temporarily locked due to failed login attempts"
                             :retry-after retry}})]
      (let [result (ports/authenticate-user service {:email "a@example.nl"
                                                     :password "wrong"
                                                     :ip-address "127.0.0.1"
                                                     :user-agent "test"})]
        (is (= false (:authenticated result)))
        (is (= :authentication-failed (:reason result))
            ":reason is the type, as it was before the shape moved")
        (is (= "Account temporarily locked due to failed login attempts" (:message result)))
        (is (= retry (:retry-after result))
            ":retry-after survives the move into the :error map"))))

  (testing "and so does the MFA-required branch"
    ;; This is the branch the first version of the migration missed. Nothing in
    ;; the repository reads this :message — web_handlers branches on
    ;; :requires-mfa? and the interceptor hardcodes its own string — so it went
    ;; from "MFA code required" to nil with every test still green.
    (let [user-id (UUID/randomUUID)
          state   (atom {user-id {:id user-id :email "a@example.nl" :active true}})
          service (sut/->UserService (->UserRepoStub state) nil (->AuditRepoStub) {} nil nil)]
      (with-redefs [auth-shell/authenticate-user
                    (fn [_ _ _ _]
                      {:success? false
                       :requires-mfa? true
                       :user {:id user-id :email "a@example.nl"}
                       :error {:type :mfa-required :message "MFA code required"}})]
        (let [result (ports/authenticate-user service {:email "a@example.nl"
                                                       :password "right"
                                                       :ip-address "127.0.0.1"
                                                       :user-agent "test"})]
          (is (true? (:requires-mfa? result)))
          (is (= "MFA code required" (:message result))))))))
