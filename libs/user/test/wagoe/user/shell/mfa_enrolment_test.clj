(ns wagoe.user.shell.mfa-enrolment-test
  "A user the app requires a second factor of, who has none — after an admin
   reset it, say — is led from sign-in to setting one up."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.in-memory-repository-test :as mem]
            [wagoe.user.shell.mfa :as mfa]
            [wagoe.user.shell.mfa-enrolment :as enrolment]
            [wagoe.user.shell.service :as service]
            [wagoe.user.shell.web-handlers :as web-handlers]
            [wagoe.user.totp-clock :as totp-clock]))

(use-fixtures :each
  (fn [t]
    (with-redefs [auth/get-jwt-secret (constantly "test-secret-minimum-32-characters-long-for-testing")]
      (t)))
  totp-clock/frozen)

(def ^:private password "Correct-Horse-Battery-1!")

(defn- system
  "The user service on in-memory repositories, in an app whose hook requires
   a second factor of everyone, and Ada, who had one."
  []
  (let [users    (mem/->MemoryUserRepository (atom {}))
        sessions (mem/->MemorySessionRepository (atom {}))
        mfa-svc  (mfa/create-mfa-service users {})
        auth-svc (auth/create-authentication-service users sessions mfa-svc
                                                     {:mfa-required? (constantly true)})
        svc      (service/create-user-service users sessions (mem/->MemoryAuditRepository (atom []))
                                              {} auth-svc)
        user     (ports/create-user users {:email "ada@example.com" :name "Ada" :role :user :active true
                                           :password-hash (auth/hash-password password)
                                           :mfa-enabled true :mfa-secret (mfa/generate-totp-secret)
                                           :mfa-backup-codes []})]
    {:svc svc :mfa mfa-svc :users users :user user}))

(defn- sign-in [{:keys [svc]} & [form]]
  ((web-handlers/login-submit-handler svc {})
   {:request-method :post :uri "/web/login" :remote-addr "127.0.0.1" :headers {"user-agent" "test"}
    :form-params (merge {"email" "ada@example.com" "password" password} form)}))

(defn- ticket-of [response]
  (get-in response [:cookies "mfa-enrolment" :value]))

(defn- with-ticket [ticket request]
  (assoc request :cookies {"mfa-enrolment" {:value ticket}}))

(deftest ^:unit ^:security after-a-reset-sign-in-leads-to-enrolment
  (let [{:keys [svc mfa users user] :as sys} (system)]
    (is (true? (ports/reset-user-mfa svc (:id user))))
    (let [refused (sign-in sys {"return-to" "/web/orders"})
          ticket  (ticket-of refused)
          cookie  (get-in refused [:cookies "mfa-enrolment"])]
      (testing "the right password goes to setup, not back to the form"
        (is (= 303 (:status refused)))
        (is (= "/web/login/mfa-setup" (get-in refused [:headers "Location"])))
        (is (= [true :strict "/web/login/mfa-setup"] [(:http-only cookie) (:same-site cookie) (:path cookie)]))
        (is (nil? (get-in refused [:cookies "session-token"])) "and no session"))
      (testing "the wrong password does not"
        (is (nil? (ticket-of (sign-in sys {"password" "Wrong-Password-1!"})))))
      (testing "setup shows the authenticator step"
        (let [page ((web-handlers/mfa-enrolment-page-handler mfa {})
                    (with-ticket ticket {:request-method :get :uri "/web/login/mfa-setup"}))]
          (is (= 200 (:status page)))
          (is (str/includes? (:body page) "name=\"secret\""))
          (is (str/includes? (:body page) "action=\"/web/login/mfa-setup\""))))
      (let [secret (mfa/generate-totp-secret)
            submit (fn [code]
                     ((web-handlers/mfa-enrolment-submit-handler mfa {})
                      (with-ticket ticket {:request-method :post :uri "/web/login/mfa-setup"
                                           :form-params {"secret" secret "backup-codes" (pr-str ["AAAA-BBBB"])
                                                         "verification-code" code}})))]
        (testing "a wrong code shows the step again"
          (is (= 400 (:status (submit "000000"))))
          (is (not (:mfa-enabled (ports/find-user-by-id users (:id user))))))
        (testing "the right one turns the second factor on and leads back to sign in"
          (let [done (submit (totp-clock/code secret))]
            (is (= 200 (:status done)))
            (is (str/includes? (:body done) "AAAA-BBBB"))
            (is (str/includes? (:body done) "/web/login?return-to=%2Fweb%2Forders"))
            (is (= 0 (get-in done [:cookies "mfa-enrolment" :max-age])) "the ticket is spent")
            (is (true? (:mfa-enabled (ports/find-user-by-id users (:id user)))))))
        (testing "after which sign-in asks for the code, and takes it"
          (is (= 200 (:status (sign-in sys))))
          (let [signed-in (sign-in sys {"mfa-code" (totp-clock/code secret)})]
            (is (= 302 (:status signed-in)))
            (is (some? (get-in signed-in [:cookies "session-token" :value])))))
        (testing "the ticket does not enrol twice"
          (is (= "/web/login" (get-in (submit (totp-clock/code secret)) [:headers "Location"]))))))))

(deftest ^:unit ^:security setup-takes-only-a-valid-ticket
  (let [{:keys [svc mfa user]} (system)
        page (fn [ticket]
               ((web-handlers/mfa-enrolment-page-handler mfa {})
                (cond-> {:request-method :get :uri "/web/login/mfa-setup"}
                  ticket (->> (with-ticket ticket)))))]
    (testing "a user whose second factor is on has nothing to set up"
      (is (= 303 (:status (page (enrolment/ticket user nil))))))
    (ports/reset-user-mfa svc (:id user))
    (is (= 200 (:status (page (enrolment/ticket user nil)))))
    (doseq [[why ticket] [["none" nil]
                          ["forged" "not-a-ticket"]
                          ["sealed under another secret"
                           (with-redefs [auth/get-jwt-secret (constantly (apply str (repeat 40 "x")))]
                             (enrolment/ticket user nil))]]]
      (testing why
        (let [response (page ticket)]
          (is (= 303 (:status response)))
          (is (= "/web/login" (get-in response [:headers "Location"]))))))))

(deftest ^:unit a-new-account-goes-to-setup-too
  (let [{:keys [svc]} (system)
        response ((web-handlers/register-submit-handler svc {})
                  {:request-method :post :uri "/web/register" :remote-addr "127.0.0.1"
                   :headers {"user-agent" "test"}
                   :form-params {"name" "Bob" "email" "bob@example.com" "password" password}})]
    (is (= "/web/login/mfa-setup" (get-in response [:headers "Location"])))
    (is (some? (ticket-of response)))))
