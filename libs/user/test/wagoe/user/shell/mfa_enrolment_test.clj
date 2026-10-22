(ns wagoe.user.shell.mfa-enrolment-test
  "A user the app requires a second factor of, who has none, may set one up
   at sign-in with the password alone only just after an admin reset it, or
   on registering. Anyone else is refused as before."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.in-memory-repository-test :as mem]
            [wagoe.user.shell.mfa :as mfa]
            [wagoe.user.shell.mfa-enrolment :as enrolment]
            [wagoe.user.shell.service :as service]
            [wagoe.user.shell.web-handlers :as web-handlers]
            [wagoe.user.totp-clock :as totp-clock])
  (:import (java.time Duration Instant)))

(use-fixtures :each
  (fn [t]
    (with-redefs [auth/get-jwt-secret (constantly "test-secret-minimum-32-characters-long-for-testing")]
      (t)))
  totp-clock/frozen)

(def ^:private password "Correct-Horse-Battery-1!")

(defn- system
  "The user service on in-memory repositories, in an app whose hook requires
   a second factor of everyone, and Ada, with `attrs`: by default one who had
   a second factor."
  [& [attrs auth-config]]
  (let [users    (mem/->MemoryUserRepository (atom {}))
        sessions (mem/->MemorySessionRepository (atom {}))
        audit    (mem/->MemoryAuditRepository (atom []))
        mfa-svc  (mfa/create-mfa-service users {} {:audit-repository audit})
        auth-svc (auth/create-authentication-service users sessions mfa-svc
                                                     (merge {:mfa-required? (constantly true)} auth-config))
        svc      (service/create-user-service users sessions audit {} auth-svc)
        user     (ports/create-user users (merge {:email "ada@example.com" :name "Ada" :role :user :active true
                                                  :password-hash (auth/hash-password password)
                                                  :mfa-enabled true :mfa-secret (mfa/generate-totp-secret)
                                                  :mfa-backup-codes []}
                                                 attrs))]
    {:svc svc :mfa mfa-svc :users users :audit audit :user user}))

(defn- sign-in [{:keys [svc]} & [form]]
  ((web-handlers/login-submit-handler svc {})
   {:request-method :post :uri "/web/login" :remote-addr "127.0.0.1" :headers {"user-agent" "test"}
    :form-params (merge {"email" "ada@example.com" "password" password} form)}))

(defn- ticket-of [response]
  (get-in response [:cookies "mfa-enrolment" :value]))

(defn- with-ticket [ticket request]
  (assoc request :cookies {"mfa-enrolment" {:value ticket}}))

(defn- setup-page [mfa ticket]
  ((web-handlers/mfa-enrolment-page-handler mfa {})
   (cond-> {:request-method :get :uri "/web/login/mfa-setup"}
     ticket (->> (with-ticket ticket)))))

(defn- refused-as-before? [response]
  (and (= 401 (:status response))
       (nil? (ticket-of response))
       (str/includes? (:body response) "mfa-enrolment-required")))

(defn- hours-ago [h] (.minus (Instant/now) (Duration/ofHours h)))

(deftest ^:unit ^:security after-a-reset-sign-in-leads-to-enrolment
  (let [{:keys [svc mfa users audit user] :as sys} (system)]
    (is (true? (ports/reset-user-mfa svc (:id user))))
    (is (some? (:mfa-reset-at (ports/find-user-by-id users (:id user)))) "the reset opens a window")
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
        (let [page (setup-page mfa ticket)]
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
          (let [done (submit (totp-clock/code secret))
                after (ports/find-user-by-id users (:id user))]
            (is (= 200 (:status done)))
            (is (str/includes? (:body done) "AAAA-BBBB"))
            (is (str/includes? (:body done) "/web/login?return-to=%2Fweb%2Forders"))
            (is (= 0 (get-in done [:cookies "mfa-enrolment" :max-age])) "the ticket is spent")
            (is (true? (:mfa-enabled after)))
            (is (nil? (:mfa-reset-at after)) "and so is the reset's window")
            (is (some #(= [:mfa-enabled (:id user)] [(:action %) (:target-user-id %)]) @(:state audit))
                "audited")))
        (testing "after which sign-in asks for the code, and takes it"
          (is (= 200 (:status (sign-in sys))))
          (let [signed-in (sign-in sys {"mfa-code" (totp-clock/code secret)})]
            (is (= 302 (:status signed-in)))
            (is (some? (get-in signed-in [:cookies "session-token" :value])))))
        (testing "the ticket does not enrol twice"
          (is (= "/web/login" (get-in (submit (totp-clock/code secret)) [:headers "Location"]))))))))

(deftest ^:unit ^:security without-a-recent-reset-the-sign-in-is-refused
  (testing "never had a second factor, never reset"
    (is (refused-as-before? (sign-in (system {:mfa-enabled false :mfa-secret nil})))))
  (testing "reset longer ago than the window"
    (is (refused-as-before? (sign-in (system {:mfa-enabled false :mfa-secret nil :mfa-reset-at (hours-ago 73)})))))
  (testing "the window is configurable"
    (let [attrs {:mfa-enabled false :mfa-secret nil :mfa-reset-at (hours-ago 2)}]
      (is (refused-as-before? (sign-in (system attrs {:mfa-reset-enrolment-hours 1}))))
      (is (= 303 (:status (sign-in (system attrs))))))))

(deftest ^:unit ^:security a-changed-password-voids-the-ticket
  (let [{:keys [svc mfa users user] :as sys} (system)
        _      (ports/reset-user-mfa svc (:id user))
        ticket (ticket-of (sign-in sys))]
    (is (= 200 (:status (setup-page mfa ticket))))
    (ports/update-user users {:id (:id user) :password-hash (auth/hash-password "Another-Password-2!")})
    (let [response (setup-page mfa ticket)]
      (is (= 303 (:status response)))
      (is (= "/web/login" (get-in response [:headers "Location"]))))))

(deftest ^:unit ^:security setup-takes-only-a-valid-ticket
  (let [{:keys [svc mfa users user]} (system)
        valid #(enrolment/ticket user (auth/password-fingerprint (ports/find-user-by-id users (:id user))) nil)]
    (testing "a user whose second factor is on has nothing to set up"
      (is (= 303 (:status (setup-page mfa (valid))))))
    (ports/reset-user-mfa svc (:id user))
    (is (= 200 (:status (setup-page mfa (valid)))))
    (doseq [[why ticket] [["none" nil]
                          ["forged" "not-a-ticket"]
                          ["without a fingerprint" (enrolment/ticket user nil nil)]
                          ["sealed under another secret"
                           (with-redefs [auth/get-jwt-secret (constantly (apply str (repeat 40 "x")))]
                             (valid))]]]
      (testing why
        (let [response (setup-page mfa ticket)]
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
