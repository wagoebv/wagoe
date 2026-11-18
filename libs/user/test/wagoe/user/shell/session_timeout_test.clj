(ns wagoe.user.shell.session-timeout-test
  "A session slides by the hours its sign-in got, within its last day, and the
   page asks before it ends (BOU-1007). WAGOE_TEST_DB picks the engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.shared.ui.core.session-timeout :as session-timeout]
            [wagoe.user.core.session :as session-core]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.middleware :as middleware]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as service]
            [wagoe.user.shell.web-handlers :as web-handlers])
  (:import [java.time Duration Instant]))

(def ^:private password "Correct-Horse-1!")

(defn- with-service
  ([f] (with-service {} f))
  ([auth-config f]
   (test-db/with-db "session_timeout"
     (fn [ctx]
       (persistence/initialize-user-schema! ctx)
       (let [users    (persistence/create-user-repository ctx)
             sessions (persistence/create-session-repository ctx)
             audit    (persistence/create-audit-repository ctx {:default-limit 20})
             svc      (service/create-user-service users sessions audit {}
                                                   (auth/create-authentication-service users sessions nil auth-config))
             user!    (fn [email role]
                        (ports/create-user users {:email email :name email :role role :active true
                                                  :password-hash (auth/hash-password password)}))]
         (f {:svc svc :users users :sessions sessions :user! user!}))))))

(defn- sign-in [svc user]
  (:session (ports/authenticate-user svc {:email (:email user) :password password
                                          :ip-address "203.0.113.9" :user-agent "test"})))

(defn- hours-left [^Instant expires-at]
  (.toHours (Duration/between (Instant/now) expires-at)))

(deftest ^:integration ^:security a-session-keeps-the-hours-its-sign-in-got
  (with-service
    (fn [{:keys [svc sessions user!]}]
      (doseq [role [:user :admin]
              :let [token  (:session-token (sign-in svc (user! (str (name role) "@example.com") role)))
                    stored (ports/find-session-by-token sessions token)
                    hours  (:duration-hours stored)]]
        (testing (name role)
          (is (= hours (Math/round (/ (.getSeconds (Duration/between (:created-at stored) (:expires-at stored))) 3600.0)))
              "stored with the session: the hours its expiry was set by")
          (when (= :user role) (is (= 24 hours)))
          (testing "and used after a minute it slides by those hours, not 24"
            (ports/update-session sessions (assoc stored :last-accessed-at (.minusSeconds (Instant/now) 120)
                                                  :expires-at (.plusSeconds (Instant/now) 600)))
            (let [validated (ports/validate-session svc token)]
              (is (<= (dec hours) (hours-left (:expires-at validated)) hours))
              (is (= (session-core/ends-at stored session-core/default-max-session-days)
                     (:ends-at validated))))))))))

(defn- through-session-auth [svc token]
  ((middleware/session-authentication-middleware
    svc (fn [_] {:status 200 :body (pr-str (layout/page-layout "T" [:p "x"]))}))
   {:request-method :get :uri "/web/x" :headers {"x-session-token" token}}))

(deftest ^:integration a-signed-in-page-carries-the-dialog-and-when-the-session-ends
  (with-service
    (fn [{:keys [svc user!]}]
      (let [token (:session-token (sign-in svc (user! "ada@example.com" :user)))
            resp  (through-session-auth svc token)]
        (is (re-matches #"\d+" (get-in resp [:headers "X-Session-Expires"])))
        (is (re-matches #"\d+" (get-in resp [:headers "X-Session-Ends"])))
        (is (str/includes? (:body resp) "session-timeout"))
        (is (str/includes? (:body resp) "/js/session-timeout.js")))))
  (testing "a page without a session has neither"
    (let [page (pr-str (layout/page-layout "T" [:p "x"]))]
      (is (nil? session-timeout/*session*))
      (is (not (str/includes? page "session-timeout"))))))

(deftest ^:integration ^:security signing-in-again-opens-a-new-session-and-ends-the-old
  (with-service
    (fn [{:keys [svc sessions user!]}]
      (let [ada     (user! "ada@example.com" :user)
            token   (:session-token (sign-in svc ada))
            reauth  (web-handlers/session-reauth-handler svc {})
            request (fn [pw] {:request-method :post :uri session-core/reauth-path
                              :user ada :cookies {"session-token" {:value token}}
                              :form-params {"reauth-password" pw} :headers {}})]
        (testing "a wrong password is refused, and the session stays"
          (let [r (reauth (request "wrong-password"))]
            (is (= 401 (:status r)))
            (is (= "refused" (get-in r [:headers "X-Session-Reauth"])))
            (is (some? (ports/find-session-by-token sessions token)))))
        (testing "the right one: a new session in the cookie, the old one ended"
          (let [r     (reauth (request password))
                fresh (get-in r [:cookies "session-token" :value])]
            (is (= 204 (:status r)))
            (is (and fresh (not= token fresh)))
            (is (some? (ports/find-session-by-token sessions fresh)))
            (is (nil? (ports/find-session-by-token sessions token)))))))))

(deftest ^:integration ^:security viewing-as-another-signs-the-administrator-in-again
  ;; The page's user is the one viewed; the session, and the password, the administrator's
  (with-service
    (fn [{:keys [svc users sessions user!]}]
      (let [admin  (user! "admin@example.com" :admin)
            ada    (user! "ada@example.com" :user)
            token  (:session-token (sign-in svc admin))
            r      ((web-handlers/session-reauth-handler svc {})
                    {:request-method :post :uri session-core/reauth-path
                     :user ada :viewing-as {:admin admin}
                     :cookies {"session-token" {:value token}}
                     :form-params {"reauth-password" password} :headers {}})
            fresh  (get-in r [:cookies "session-token" :value])]
        (is (= 204 (:status r)))
        (is (= (:id admin) (:user-id (ports/find-session-by-token sessions fresh))))
        (is (zero? (or (:failed-login-count (ports/find-user-by-id users (:id ada))) 0))
            "nothing counted against the user viewed")))))

(deftest ^:unit ^:security signing-in-again-tries-only-the-own-tenants-account
  (let [asked (atom nil)
        svc   #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify ports/IUserService
          (authenticate-user [_ creds] (reset! asked creds) {:authenticated false}))]
    ((web-handlers/session-reauth-handler svc {})
     {:request-method :post :user {:id (random-uuid) :email "a@b.c" :tenant-id #uuid "00000000-0000-0000-0000-0000000000aa"}
      :form-params {"reauth-password" "x"} :headers {}})
    (is (true? (:tenant-given? @asked)))
    (is (= "00000000-0000-0000-0000-0000000000aa" (:tenant-id @asked)))))

(deftest ^:integration ^:security no-session-or-jwt-outlasts-the-last-day
  ;; A sign-in's hours above :max-session-days are cut to it, the JWT's too
  (test-db/with-db "session_cap"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users    (persistence/create-user-repository ctx)
            sessions (persistence/create-session-repository ctx)
            audit    (persistence/create-audit-repository ctx {:default-limit 20})
            svc      (service/create-user-service users sessions audit {}
                                                  (auth/create-authentication-service
                                                   users sessions nil {:default-session-hours 720 :max-session-days 2}))
            user     (ports/create-user users {:email "ada@example.com" :name "Ada" :role :user :active true
                                               :password-hash (auth/hash-password password)})
            session  (sign-in svc user)
            claims   (:claims (auth/validate-jwt-token (:jwt-token session)))]
        (is (= 48 (:duration-hours (ports/find-session-by-token sessions (:session-token session)))))
        (is (<= (- (:exp claims) (:iat claims)) (* 48 3600)))))))

(deftest ^:unit max-session-days-must-be-a-number-of-days
  (is (some? (auth/create-authentication-service nil nil nil {:max-session-days nil})) "nil: the default")
  (doseq [bad [0 -1 "7" 1.5]]
    (is (= :configuration-error
           (try (auth/create-authentication-service nil nil nil {:max-session-days bad}) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
        (pr-str bad))))

(deftest ^:integration ^:security a-configured-sign-in-gets-its-configured-hours
  (with-service {:default-session-hours 4}
    (fn [{:keys [svc sessions user!]}]
      (let [token (:session-token (sign-in svc (user! "ada@example.com" :user)))]
        (is (= 4 (:duration-hours (ports/find-session-by-token sessions token))))))))

(defn- refusal [svc token]
  (try (ports/validate-session svc token) nil
       (catch clojure.lang.ExceptionInfo e ((juxt :type :reason) (ex-data e)))))

(deftest ^:integration ^:security a-session-past-its-last-day-is-refused-however-used
  (with-service
    (fn [{:keys [svc sessions user!]}]
      (let [token  (:session-token (sign-in svc (user! "ada@example.com" :user)))
            stored (ports/find-session-by-token sessions token)]
        (ports/update-session sessions (assoc stored :created-at (.minusSeconds (Instant/now) (* 8 24 3600))
                                              :expires-at (.plusSeconds (Instant/now) 3600)))
        (is (= [:session-invalid :expired] (refusal svc token)))))))

(deftest ^:integration a-session-from-before-slides-24-hours
  ;; No duration_hours: "Stay signed in" keeps it all the same
  (with-service
    (fn [{:keys [svc sessions user!]}]
      (let [token  (:session-token (sign-in svc (user! "ada@example.com" :user)))
            stored (ports/find-session-by-token sessions token)]
        (ports/update-session sessions (assoc stored :duration-hours nil
                                              :last-accessed-at (.minusSeconds (Instant/now) 120)
                                              :expires-at (.plusSeconds (Instant/now) 60)))
        (is (= 23 (hours-left (:expires-at (ports/validate-session svc token)))))))))

(deftest ^:integration the-headers-are-the-sessions
  (with-service
    (fn [{:keys [svc user!]}]
      (let [token     (:session-token (sign-in svc (user! "ada@example.com" :user)))
            resp      (through-session-auth svc token)
            validated (ports/validate-session svc token)]
        (is (= (str (.toEpochMilli ^Instant (:expires-at validated))) (get-in resp [:headers "X-Session-Expires"])))
        (is (= (str (.toEpochMilli ^Instant (:ends-at validated))) (get-in resp [:headers "X-Session-Ends"])))
        (is (re-matches #"\d+" (get-in resp [:headers "X-Session-Now"])))))))

(deftest ^:unit staying-hands-the-page-its-csrf-token-uncached
  (let [r (binding [csrf/*token* "a-token"] ((web-handlers/session-extend-handler) {}))]
    (is (= 204 (:status r)))
    (is (= "a-token" (get-in r [:headers "X-CSRF-Token"])))
    (is (= "no-store" (get-in r [:headers "Cache-Control"])))))

(defn- reauth-with [answer request]
  ((web-handlers/session-reauth-handler
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify ports/IUserService (authenticate-user [_ _] answer)) {})
   (merge {:request-method :post :user {:id #uuid "00000000-0000-0000-0000-000000000001" :email "a@b.c"}
           :form-params {"reauth-password" "x"} :headers {}}
          request)))

(deftest ^:unit ^:security signing-in-again-asks-the-second-factor-when-due
  (let [r (reauth-with {:authenticated false :requires-mfa? true} {})]
    (is (= 401 (:status r)))
    (is (= "mfa" (get-in r [:headers "X-Session-Reauth"]))))
  (is (= "refused" (get-in (reauth-with {:authenticated false :reason :mfa-verification-failed} {})
                           [:headers "X-Session-Reauth"]))))

(deftest ^:unit signing-in-again-keeps-a-remembered-sign-in
  (let [ok {:authenticated true :user {:id #uuid "00000000-0000-0000-0000-000000000001"}
            :session {:session-token "new"}}]
    (is (= (* 30 24 60 60) (get-in (reauth-with ok {:cookies {"remembered-email" {:value "a@b.c"}}})
                                   [:cookies "session-token" :max-age])))
    (is (nil? (get-in (reauth-with ok {}) [:cookies "session-token" :max-age])))))

