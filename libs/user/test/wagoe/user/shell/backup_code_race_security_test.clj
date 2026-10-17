(ns wagoe.user.shell.backup-code-race-security-test
  "Parallel logins could each spend one backup code, and a code marked used by
   one could be lost to another's write (BOU-708). WAGOE_TEST_DB picks the
   engine."
  (:require [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.mfa :as mfa]
            [wagoe.user.shell.mfa-crypto :as mfa-crypto]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as service])
  (:import [java.util UUID]
           [java.util.concurrent CountDownLatch]))

(def ^:private password "Correct-Horse-1!")
(def ^:private codes ["AAAA-BBBB-CCCC" "DDDD-EEEE-FFFF"])

(defn- with-mfa-user [prefix f]
  (test-db/with-db prefix
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users    (persistence/create-user-repository ctx)
            sessions (persistence/create-session-repository ctx)
            audit    (persistence/create-audit-repository ctx {:default-limit 20})
            svc      (service/create-user-service
                      users sessions audit {}
                      (auth/create-authentication-service users sessions nil {:max-failed-attempts 10}))
            user     (ports/create-user users {:email         (str "u-" (UUID/randomUUID) "@example.com")
                                               :name          "U"
                                               :role          :user
                                               :active        true
                                               :password-hash (auth/hash-password password)})]
        (ports/update-user users {:id                    (:id user)
                                  :mfa-enabled           true
                                  :mfa-secret            (mfa/generate-totp-secret)
                                  :mfa-backup-codes      (mapv mfa-crypto/hash-backup-code codes)
                                  :mfa-backup-codes-used []})
        (f {:users users :service svc :user user})))))

(defn- parallel-logins
  "Log in once per code, all at the same moment; the results in order."
  [service user codes]
  (let [start (CountDownLatch. 1)
        tries (mapv (fn [code]
                      (future (.await start)
                              (ports/authenticate-user service {:email (:email user) :password password
                                                                :mfa-code code :ip-address "203.0.113.9"
                                                                :user-agent "test"})))
                    codes)]
    (.countDown start)
    (mapv deref tries)))

(deftest ^:integration ^:security a-backup-code-signs-in-once
  (let [n 4]
    (with-mfa-user "backup_once"
      (fn [{:keys [users service user]}]
        (let [results (parallel-logins service user (repeat n (first codes)))
              stored  (ports/find-user-by-id users (:id user))]
          (is (= 1 (count (filter :authenticated results))) "one login spends it")
          (is (= (repeat (dec n) :mfa-verification-failed) (keep :reason results))
              "the others are wrong codes")
          (is (= 1 (count (:mfa-backup-codes-used stored))))
          (testing "and it is spent"
            (is (not (:authenticated (first (parallel-logins service user [(first codes)])))))))))))

(deftest ^:integration ^:security parallel-codes-are-each-marked-used
  (with-mfa-user "backup_both"
    (fn [{:keys [users service user]}]
      (is (every? :authenticated (parallel-logins service user codes)))
      (is (= 2 (count (:mfa-backup-codes-used (ports/find-user-by-id users (:id user)))))
          "neither mark is lost"))))
