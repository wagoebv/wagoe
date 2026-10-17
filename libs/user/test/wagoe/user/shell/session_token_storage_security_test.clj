(ns wagoe.user.shell.session-token-storage-security-test
  "user_sessions holds a hash of each token, not the token, and parallel wrong
   passwords each count towards the lockout (BOU-696). WAGOE_TEST_DB picks the
   engine."
  (:require [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.platform.database :as db]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as service])
  (:import [java.util UUID]
           [java.util.concurrent CountDownLatch]))

(def ^:private password "Correct-Horse-1!")

(defn- with-service [prefix auth-config f]
  (test-db/with-db prefix
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users    (persistence/create-user-repository ctx)
            sessions (persistence/create-session-repository ctx)
            audit    (persistence/create-audit-repository ctx {:default-limit 20})
            auth-svc (auth/create-authentication-service users sessions nil auth-config)]
        (f {:ctx ctx :users users
            :service (service/create-user-service users sessions audit {} auth-svc)})))))

(defn- create-user! [users]
  (ports/create-user users {:email         (str "u-" (UUID/randomUUID) "@example.com")
                            :name          "U"
                            :role          :user
                            :active        true
                            :password-hash (auth/hash-password password)}))

(defn- login [service user pw]
  (ports/authenticate-user service {:email (:email user) :password pw
                                    :ip-address "203.0.113.9" :user-agent "test"}))

(defn- stored-tokens [ctx]
  (set (map :session-token (db/execute-query! ctx ["SELECT session_token FROM user_sessions"]))))

(deftest ^:integration ^:security a-login-stores-the-hash-not-the-token
  (with-service "token_hash" {}
    (fn [{:keys [ctx users service]}]
      (let [user  (create-user! users)
            token (:session-token (login service user password))]
        (is (string? token))
        (testing "the table holds the token's hash only"
          (is (= #{(persistence/session-token-hash token)} (stored-tokens ctx))))
        (testing "the token validates; the session list carries no token"
          (is (= (:id user) (:user-id (ports/validate-session service token))))
          (is (seq (ports/get-user-sessions service (:id user))))
          (is (not-any? :session-token (ports/get-user-sessions service (:id user)))))
        (testing "what the table holds is not a credential"
          (is (nil? (ports/validate-session service (persistence/session-token-hash token)))))
        (testing "logout revokes it"
          (is (:invalidated (ports/logout-user service token)))
          (is (nil? (ports/validate-session service token))))))))

(deftest ^:integration ^:security a-session-is-revoked-by-id-and-only-by-its-owner
  (with-service "revoke_by_id" {}
    (fn [{:keys [users service]}]
      (let [user       (create-user! users)
            other      (create-user! users)
            {:keys [session-token session]} (login service user password)]
        (is (false? (:invalidated (ports/revoke-user-session service (:id other) (:id session)))))
        (is (some? (ports/validate-session service session-token)) "another user's id revokes nothing")
        (is (true? (:invalidated (ports/revoke-user-session service (:id user) (:id session)))))
        (is (nil? (ports/validate-session service session-token)))))))

(deftest ^:integration ^:security an-upgrade-hashes-a-plaintext-session-in-place
  (with-service "token_upgrade" {}
    (fn [{:keys [ctx users service]}]
      (let [user  (create-user! users)
            {:keys [session-token session]} (login service user password)]
        ;; As a release before BOU-696 stored it.
        (db/execute-update! ctx ["UPDATE user_sessions SET session_token = ? WHERE id = ?"
                                 session-token (str (:id session))])
        (is (= #{session-token} (stored-tokens ctx)))
        (dotimes [_ 2] (persistence/initialize-user-schema! ctx))
        (is (= #{(persistence/session-token-hash session-token)} (stored-tokens ctx))
            "hashed once, however often boot runs")
        (is (= (:id user) (:user-id (ports/validate-session service session-token)))
            "the user stays signed in")))))

(deftest ^:integration ^:security parallel-wrong-passwords-each-count
  ;; The count was read, incremented in memory and written back, so parallel
  ;; failures overwrote each other and the lockout came late.
  (let [n 8]
    (with-service "failed_logins" {:max-failed-attempts n :lockout-duration-minutes 15}
      (fn [{:keys [users service]}]
        (let [user  (create-user! users)
              start (CountDownLatch. 1)
              tries (doall (repeatedly n #(future (.await start) (login service user "wrong-password"))))]
          (.countDown start)
          (is (every? (comp false? :authenticated deref) tries))
          (let [stored (ports/find-user-by-id users (:id user))]
            (is (= n (:failed-login-count stored)))
            (is (some? (:lockout-until stored)) "locked at the threshold"))
          (is (= :account-locked (:reason (login service user password)))))))))

(deftest ^:integration ^:security the-lockout-is-set-in-the-increment-that-reaches-the-limit
  ;; Written separately, a lockout could land after a login that had just
  ;; reset the count.
  (with-service "lockout_write" {}
    (fn [{:keys [users]}]
      (let [user    (create-user! users)
            lockout {:max-attempts 2 :until (.plusSeconds (java.time.Instant/now) 900)}]
        (is (= 1 (ports/increment-failed-login-count users (:id user) lockout)))
        (is (nil? (:lockout-until (ports/find-user-by-id users (:id user)))) "not before the limit")
        (is (= 2 (ports/increment-failed-login-count users (:id user) lockout)))
        (is (some? (:lockout-until (ports/find-user-by-id users (:id user)))))
        (is (nil? (ports/increment-failed-login-count users (UUID/randomUUID) lockout)) "unknown user")))))
