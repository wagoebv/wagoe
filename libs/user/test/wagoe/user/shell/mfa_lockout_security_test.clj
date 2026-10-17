(ns wagoe.user.shell.mfa-lockout-security-test
  "Wrong MFA codes on the password form and the JSON login count towards the
   lockout, as a wrong password does; they did not, so a code could be guessed
   at the rate limit (BOU-698)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.http :as user-http]
            [wagoe.user.shell.in-memory-repository-test :as mem]
            [wagoe.user.shell.mfa :as mfa]
            [wagoe.user.shell.mfa-crypto :as mfa-crypto]
            [wagoe.user.shell.service :as service]
            [wagoe.user.shell.web-handlers :as handlers]
            [wagoe.user.totp-clock :as totp-clock]))

(use-fixtures :each totp-clock/frozen)

(def ^:private password "Correct-Horse-9")
(def ^:private backup-code "ABCD-EFGH-JKLM")

(defn- fixture
  "A user service over in-memory repositories, locking at three failures, and
   Bob, who has MFA on."
  []
  (let [users  (mem/->MemoryUserRepository (atom {}))
        sess   (mem/->MemorySessionRepository (atom {}))
        svc    (service/create-user-service users sess (mem/->MemoryAuditRepository (atom []))
                                            {} (auth/create-authentication-service
                                                users sess nil {:max-failed-attempts 3}))
        bob    (ports/register-user svc {:email "bob@x.org" :name "Bob" :password password :role :user})
        secret (mfa/generate-totp-secret)]
    (.update-user ^wagoe.user.ports.IUserRepository users
                  (assoc (.find-user-by-id ^wagoe.user.ports.IUserRepository users (:id bob))
                         :mfa-enabled true
                         :mfa-secret secret
                         :mfa-backup-codes [(mfa-crypto/hash-backup-code backup-code)]
                         :mfa-backup-codes-used []))
    {:svc svc :users users :bob bob :secret secret}))

(defn- stored [{:keys [users bob]}]
  (ports/find-user-by-id users (:id bob)))

(defn- totp [{:keys [secret]}]
  (totp-clock/code secret))

(defn- api-login [handler code]
  (handler {:parameters {:body {:email "bob@x.org" :password password :mfaCode code}}}))

(defn- form-login [handler code]
  (handler {:form-params {"email" "bob@x.org" "password" password "mfa-code" code}
            :headers {"user-agent" "test"} :remote-addr "127.0.0.1"}))

(deftest ^:contract ^:security wrong-codes-lock-the-account-on-the-api
  (doseq [[path make-handler] {"/auth/login" user-http/login-handler
                               "/sessions"   user-http/create-session-handler}]
    (testing path
      (let [{:keys [svc] :as f} (fixture)
            handler (make-handler svc)]
        (is (= [401 401 401] (mapv #(:status (api-login handler %)) ["000000" "WRONG-BACKUP-CODE" "000000"]))
            "a wrong backup code counts as well")
        (is (= 3 (:failed-login-count (stored f))))
        (let [resp (api-login handler (totp f))]
          (is (= 429 (:status resp)) "locked: the right code is refused too")
          (is (= "account-locked" (get-in resp [:body :error :type]))))))))

(deftest ^:contract ^:security wrong-codes-lock-the-account-on-the-form
  (let [{:keys [svc] :as f} (fixture)
        handler (handlers/login-submit-handler svc {})]
    (is (= [401 401 401] (mapv #(:status (form-login handler %)) ["000000" "WRONG-BACKUP-CODE" "000000"])))
    (is (some? (:lockout-until (stored f))))
    (let [resp (form-login handler (totp f))]
      (is (= 401 (:status resp)))
      (is (nil? (get-in resp [:cookies "session-token"])) "locked: the right code is refused too"))))

(deftest ^:contract ^:security the-right-code-resets-the-count
  (let [{:keys [svc] :as f} (fixture)
        handler (user-http/login-handler svc)]
    (is (true? (get-in (api-login handler "") [:body :requiresMfa])) "a blank code asks for one")
    (is (= 401 (:status (api-login handler "000000"))))
    (is (= 1 (:failed-login-count (stored f))) "and does not count")
    (is (= 200 (:status (api-login handler backup-code))) "a backup code signs in")
    (is (zero? (:failed-login-count (stored f))))))
