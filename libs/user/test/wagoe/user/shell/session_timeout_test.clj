(ns wagoe.user.shell.session-timeout-test
  "A session slides by the hours its sign-in got, within its last day, and the
   page asks before it ends (BOU-1007). WAGOE_TEST_DB picks the engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [support.test-db :as test-db]
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

(defn- with-service [f]
  (test-db/with-db "session_timeout"
    (fn [ctx]
      (persistence/initialize-user-schema! ctx)
      (let [users    (persistence/create-user-repository ctx)
            sessions (persistence/create-session-repository ctx)
            audit    (persistence/create-audit-repository ctx {:default-limit 20})
            svc      (service/create-user-service users sessions audit {}
                                                  (auth/create-authentication-service users sessions nil {}))
            user!    (fn [email role]
                       (ports/create-user users {:email email :name email :role role :active true
                                                 :password-hash (auth/hash-password password)}))]
        (f {:svc svc :sessions sessions :user! user!})))))

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
                              :form-params {"password" pw} :headers {}})]
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
