(ns wagoe.user.shell.mfa-hook-test
  "The :mfa-required? hook decides per password login whether a second
   factor is asked for."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [one-time.core :as otp]
            [wagoe.platform.core.http.client-ip :as client-ip]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.in-memory-repository-test :as mem]
            [wagoe.user.shell.interceptors :as interceptors]
            [wagoe.user.shell.mfa :as mfa]))

(use-fixtures :each
  (fn [t]
    (with-redefs [auth/get-jwt-secret (constantly "test-secret-minimum-32-characters-long-for-testing")]
      (t))))

(def ^:private password "Correct-Horse-Battery-1!")

(defn- setup [auth-config & {:as attrs}]
  (let [users    (mem/->MemoryUserRepository (atom {}))
        sessions (mem/->MemorySessionRepository (atom {}))
        svc      (auth/create-authentication-service users sessions (mfa/create-mfa-service users {})
                                                     auth-config)
        user     (ports/create-user users (merge {:email "ada@example.com" :name "Ada" :role :user
                                                  :active true :password-hash (auth/hash-password password)}
                                                 attrs))]
    {:svc svc :users users :user user}))

(defn- login [{:keys [svc]} & [ctx]]
  (auth/authenticate-user svc "ada@example.com" password
                          (merge {:ip-address "10.0.0.5" :user-agent "test"} ctx)))

(def ^:private outside-office? (fn [_user ctx] (not (client-ip/in-any? (:ip-address ctx) ["10.0.0.0/8"]))))

(deftest ^:unit ^:security default-asks-only-users-with-mfa
  (let [secret (mfa/generate-totp-secret)
        with   (setup {} :mfa-enabled true :mfa-secret secret)]
    (is (= :mfa-required (get-in (login with) [:error :type])))
    (is (:success? (login with {:mfa-code (str (otp/get-totp-token secret))}))))
  (is (:success? (login (setup {})))))

(deftest ^:unit ^:security hook-waives-mfa-without-turning-it-off
  (let [{:keys [users user] :as s} (setup {:mfa-required? outside-office?}
                                          :mfa-enabled true :mfa-secret (mfa/generate-totp-secret))]
    (testing "inside the office: no second factor"
      (let [result (login s)]
        (is (:success? result))
        (is (some? (:session result)))))
    (testing "MFA stays enabled in the database"
      (is (true? (:mfa-enabled (ports/find-user-by-id users (:id user))))))
    (testing "outside it is still asked for"
      (is (= :mfa-required (get-in (login s {:ip-address "203.0.113.7"}) [:error :type]))))
    (testing "and so is a client the proxy did not name (BOU-695)"
      (is (= :mfa-required (get-in (login s {:ip-address nil}) [:error :type]))))))

(deftest ^:unit ^:security hook-requiring-mfa-refuses-a-user-without-it
  (let [{:keys [svc user]} (setup {:mfa-required? outside-office?})
        result (login {:svc svc} {:ip-address "203.0.113.7"})]
    (is (false? (:success? result)))
    (is (= :mfa-enrolment-required (get-in result [:error :type])))
    (is (empty? (ports/find-sessions-by-user (:session-repository svc) (:id user))))
    (testing "inside the office the same user signs in"
      (is (:success? (login {:svc svc}))))))

(defn office-hook [_user _ctx] false)

(def not-a-hook true)

(deftest ^:unit hook-is-resolved-from-a-symbol
  (let [s (setup {:mfa-required? `office-hook} :mfa-enabled true :mfa-secret (mfa/generate-totp-secret))]
    (is (:success? (login s))))
  (testing "a symbol that does not resolve to a function fails at construction"
    (doseq [bad ['wagoe.user.shell.mfa-hook-test/missing 'no.such.ns/hook "office-hook"
                 `not-a-hook]]
      (is (= :configuration-error
             (:type (ex-data (try (auth/create-authentication-service nil nil nil {:mfa-required? bad})
                                  (catch clojure.lang.ExceptionInfo e e)))))
          (pr-str bad)))))

(deftest ^:unit ^:security api-login-takes-the-connection-address-not-the-body
  (let [enter (:enter interceptors/transform-session-creation-data)
        ctx   (enter {:interface-type   :http
                      :request          {:remote-addr "203.0.113.7"}
                      :raw-session-data {:email "ada@example.com" :password password
                                         :deviceInfo {:ipAddress "10.0.0.5"}}})]
    (is (= "203.0.113.7" (get-in ctx [:session-data :ip-address])))))
