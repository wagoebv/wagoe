(ns wagoe.user.shell.landing-test
  "Where a user lands after signing in, and who serves /web/dashboard."
  (:require [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.http.reitit-router :as reitit-router]
            [wagoe.platform.shell.system.wiring :as wiring]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.http :as http]
            [wagoe.user.shell.landing :as landing]
            [wagoe.user.shell.web-handlers :as web-handlers]))

(defn- config [user-settings]
  {:active {:wagoe/user user-settings}})

(defn- signs-in-as
  "A service that authenticates anyone as a user with `role`."
  [role]
  (reify ports/IUserService
    (authenticate-user [_ _]
      {:authenticated true :user {:role role} :session {:session-token "t"}})))

(defn- login-location
  ([cfg role] (login-location cfg role nil))
  ([cfg role return-to]
   (-> ((web-handlers/login-submit-handler (signs-in-as role) (landing/prepare cfg))
        {:form-params (cond-> {"email" "user@example.com" "password" "password123"}
                        return-to (assoc "return-to" return-to))})
       (get-in [:headers "Location"]))))

(defn landing-of
  "A :login-redirect function, named by symbol in the tests below."
  [user]
  (case (:role user)
    :admin  "/web/beheer"
    :viewer "https://evil.example"
    nil))

(deftest ^:contract defaults-are-unchanged
  (is (= "/web/admin/users" (login-location {} :admin)))
  (is (= "/web/dashboard" (login-location {} :user))))

(deftest ^:contract per-role-redirect
  (let [cfg (config {:login-redirect {:user "/web/overzicht" :default "/web/start"}})]
    (is (= "/web/overzicht" (login-location cfg :user)))
    (is (= "/web/start" (login-location cfg :viewer)))
    (is (= "/web/admin/users" (login-location cfg :admin)) "unnamed admin keeps its default")
    (testing "a safe return-to still wins"
      (is (= "/web/profile" (login-location cfg :user "/web/profile")))
      (is (= "/web/overzicht" (login-location cfg :user "https://evil.example"))))))

(deftest ^:contract fn-redirect
  (testing "a symbol, resolved at boot"
    (let [cfg (config {:login-redirect `landing-of})]
      (is (= "/web/beheer" (login-location cfg :admin)))
      (is (= "/web/dashboard" (login-location cfg :user)) "nil means the default")
      (is (= "/web/dashboard" (login-location cfg :viewer)) "an off-site answer is refused")))
  (testing "a function value"
    (is (= "/web/x" (login-location (config {:login-redirect (constantly "/web/x")}) :user)))))

(deftest ^:contract password-mfa-lands-per-config
  (let [svc (reify ports/IUserService
              (authenticate-user [_ {:keys [mfa-code]}]
                (if mfa-code
                  {:authenticated true :user {:role :admin} :session {:session-token "t"}}
                  {:authenticated false :requires-mfa? true :user {:role :admin}})))
        handler (web-handlers/login-submit-handler
                 svc (config {:login-redirect {:admin "/web/beheer"}}))
        form    {"email" "admin@example.com" "password" "password123"}]
    (is (= 200 (:status (handler {:form-params form}))))
    (is (= "/web/beheer"
           (get-in (handler {:form-params (assoc form "mfa-code" "123456")})
                   [:headers "Location"])))))

(deftest ^:contract web-root-sends-a-signed-in-user-to-their-landing
  (let [svc (reify ports/IUserService
              (validate-session [_ _] {:user {:role :user}}))
        handler (web-handlers/web-root-page-handler
                 svc (config {:login-redirect {:user "/web/overzicht"}}))]
    (is (= "/web/overzicht"
           (get-in (handler {:cookies {"session-token" {:value "t"}}}) [:headers "Location"])))))

(deftest ^:unit bad-settings-fail-boot
  (doseq [settings [{:login-redirect {:user "https://evil.example"}}
                    {:login-redirect {:owner "/web/x"}}
                    {:login-redirect "/web/x"}
                    {:login-redirect 'no.such.ns/landing}
                    {:dashboard "off"}
                    {:dashboard {:handler 'no.such.ns/page}}
                    {:dashboard {:handler `landing-of :extra 1}}]]
    (is (= :configuration-error
           (:type (ex-data (try (landing/prepare (config settings)) nil
                                (catch Exception e e)))))
        (pr-str settings))))

;; =============================================================================
;; /web/dashboard
;; =============================================================================

(defn- user-web-routes [settings]
  (:web (http/user-routes (reify ports/IUserService) nil (config settings))))

(defn- dashboard-route [settings]
  (first (filter #(= "/dashboard" (first %)) (user-web-routes settings))))

(def ^:private app-dashboard
  {:web [["/dashboard" {:public true
                        :get    {:handler (fn [_] {:status 200 :body "app dashboard"})}}]]})

(defn- mounted [settings]
  (reitit-router/compile-routes
   (:web (wiring/module-route-contributions [{:web (user-web-routes settings)} app-dashboard]))
   {:swagger-enabled false}))

(deftest ^:contract dashboard-false-leaves-the-path-to-the-app
  (is (nil? (dashboard-route {:dashboard false})))
  (is (= "app dashboard"
         (:body ((mounted {:dashboard false}) {:request-method :get :uri "/web/dashboard"})))))

(deftest ^:contract default-dashboard-still-claims-the-path
  (is (some? (dashboard-route {})))
  (is (= :wagoe/ambiguous-routes
         (:type (ex-data (try (mounted {}) nil (catch Exception e e)))))))

(defn custom-dashboard [_request]
  {:status 200 :body "custom"})

(deftest ^:contract custom-dashboard-handler-is-served
  (let [handler (get-in (dashboard-route {:dashboard {:handler `custom-dashboard}}) [1 :get :handler])]
    (is (= "custom" (:body (handler {}))))))
