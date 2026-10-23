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
        (f {:ctx ctx :users users :audit audit
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

(deftest ^:integration ^:security a-revoked-session-is-audited-with-who-revoked-it
  ;; It was audited as the user logging out, with no actor (BOU-698).
  (with-service "revoke_audit" {}
    (fn [{:keys [users audit service]}]
      (let [user  (create-user! users)
            admin (create-user! users)
            revoke! (fn [actor]
                      (let [{:keys [session]} (login service user password)]
                        (service/with-audit-context {:actor-id (:id actor) :actor-email (:email actor)
                                                     :ip-address "198.51.100.7"}
                          #(ports/revoke-user-session service (:id user) (:id session)))
                        session))]
        (doseq [[who actor] {"by an admin" admin "by the user" user}]
          (testing who
            (let [session (revoke! actor)
                  entry   (->> (:audit-logs (ports/find-audit-logs audit {:limit 50}))
                               (filter #(= (str (:id session)) (get-in % [:metadata :session-id])))
                               first)]
              (is (= :session-revoked (:action entry)))
              (is (= [(:id actor) (:email actor)] [(:actor-id entry) (:actor-email entry)]))
              (is (= (:id user) (:target-user-id entry)))
              (is (= "198.51.100.7" (:ip-address entry)) "the actor's address, not the device's"))))))))

(deftest ^:integration ^:security revoking-all-sessions-is-audited-once
  ;; Without keep-current nothing was audited; with it, one entry per session
  ;; (BOU-708).
  (with-service "revoke_all_audit" {}
    (fn [{:keys [users audit service]}]
      (let [user    (create-user! users)
            admin   (create-user! users)
            [kept & _] (mapv #(:session (login service user %)) (repeat 3 password))
            revoke! #(service/with-audit-context {:actor-id (:id admin) :actor-email (:email admin)}
                       (fn [] (ports/revoke-sessions-except service (:id user) %)))
            entries #(->> (:audit-logs (ports/find-audit-logs audit {:limit 50}))
                          (filter (comp #{:session-revoked} :action)))]
        (is (= 2 (revoke! (:id kept))))
        (is (= 1 (revoke! nil)))
        (is (= 0 (revoke! nil)) "nothing left, nothing audited")
        (is (= #{{:all true :count 2 :kept-session-id (str (:id kept))} {:all true :count 1}}
               (set (map :metadata (entries)))))
        (is (every? #(= [(:id admin) (:id user)] [(:actor-id %) (:target-user-id %)]) (entries)))))))

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

(deftest ^:integration ^:security parallel-attempts-stop-at-the-limit
  ;; The lockout was read before any failure was written, so a burst of
  ;; parallel guesses all passed the check (BOU-785).
  (let [limit 3 n 12]
    (with-service "claim_limit" {:max-failed-attempts limit :lockout-duration-minutes 15}
      (fn [{:keys [users service]}]
        (let [burst (fn [attempt]
                      (let [start (CountDownLatch. 1)
                            tries (doall (repeatedly n #(future (.await start) (attempt))))]
                        (.countDown start)
                        (mapv deref tries)))]
          (testing "sign-in"
            (let [user    (create-user! users)
                  results (burst #(login service user "wrong-password"))]
              (is (= limit (:failed-login-count (ports/find-user-by-id users (:id user))))
                  "only `limit` passwords were checked")
              (is (= (- n limit) (count (filter :retry-after results))) "the rest were told it is locked")))
          (testing "change-password"
            (let [user    (create-user! users)
                  results (burst #(try (ports/change-password service (:id user) "wrong-password" "New-Horse-2!")
                                       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
              (is (= limit (:failed-login-count (ports/find-user-by-id users (:id user)))))
              (is (= (- n limit) (count (filter #{:account-locked} results)))))))))))

(deftest ^:integration ^:security a-claim-counts-only-while-unlocked
  ;; Written separately, a lockout could land after a login that had just
  ;; reset the count (BOU-696); read separately, parallel attempts passed it
  ;; (BOU-785).
  (with-service "lockout_write" {}
    (fn [{:keys [users]}]
      (let [user    (create-user! users)
            now     (java.time.Instant/now)
            lockout {:max-attempts 2 :until (.plusSeconds now 900) :now now}
            claim   #(ports/claim-login-attempts users (:id user) 1 %)
            stored  #(ports/find-user-by-id users (:id user))]
        (is (= {:claimed? true :failed-login-count 1} (claim lockout)))
        (is (nil? (:lockout-until (stored))) "not before the limit")
        (is (= {:claimed? true :failed-login-count 2} (claim lockout)))
        (is (some? (:lockout-until (stored))))
        (let [refused (claim lockout)]
          (is (false? (:claimed? refused)) "locked: nothing is counted")
          (is (some? (:lockout-until refused))))
        (is (= 2 (:failed-login-count (stored))))
        (testing "release takes one back, and the lockout it set"
          (ports/release-login-attempt users (:id user) true)
          (is (= 1 (:failed-login-count (stored))))
          (is (nil? (:lockout-until (stored)))))
        (testing "an expired lockout claims again"
          (claim lockout)
          (is (:claimed? (claim (assoc lockout :now (.plusSeconds now 1000))))))
        (is (nil? (ports/claim-login-attempts users (UUID/randomUUID) 1 lockout)) "unknown user")))))
