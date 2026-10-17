(ns wagoe.user.shell.mfa-backup-code-storage-test
  "Enabling MFA stores ten bcrypt-hashed backup codes, which did not fit the
   VARCHAR(255) column they had on PostgreSQL, MySQL and H2 (BOU-715).
   WAGOE_TEST_DB picks the engine."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [support.test-db :as test-db]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.mfa :as mfa]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as service]
            [wagoe.user.totp-clock :as totp-clock])
  (:import [java.util UUID]))

(use-fixtures :each totp-clock/frozen)

(def ^:private password "Correct-Horse-1!")

(deftest ^:integration ^:security mfa-enrols-with-ten-codes-and-a-backup-code-signs-in
  (test-db/with-db "mfa_codes"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users    (persistence/create-user-repository ctx)
            sessions (persistence/create-session-repository ctx)
            audit    (persistence/create-audit-repository ctx {:default-limit 20})
            mfa-svc  (mfa/create-mfa-service users {})
            svc      (service/create-user-service users sessions audit {}
                                                  (auth/create-authentication-service users sessions mfa-svc {}))
            user     (ports/create-user users {:email         (str "mfa-" (UUID/randomUUID) "@example.com")
                                               :name          "M"
                                               :role          :user
                                               :active        true
                                               :password-hash (auth/hash-password password)})
            {:keys [secret backup-codes]} (mfa/setup-mfa mfa-svc (:id user))
            login    #(ports/authenticate-user svc {:email (:email user) :password password :mfa-code %
                                                    :ip-address "203.0.113.9" :user-agent "test"})]
        (is (= 10 (count backup-codes)))
        (is (= {:success? true}
               (mfa/enable-mfa mfa-svc (:id user) secret backup-codes
                               (totp-clock/code secret))))
        (is (= 10 (count (:mfa-backup-codes (ports/find-user-by-id users (:id user))))) "all ten are stored")
        (with-redefs [auth/get-jwt-secret (constantly "test-secret-minimum-32-characters-long-for-testing")]
          (testing "a backup code signs in, once"
            (is (:authenticated (login (first backup-codes))))
            (is (not (:authenticated (login (first backup-codes))))))
          (testing "another still works"
            (is (:authenticated (login (second backup-codes))))))))))
