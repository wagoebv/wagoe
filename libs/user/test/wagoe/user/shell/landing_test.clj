(ns wagoe.user.shell.landing-test
  "Where a user lands after signing in, and who serves /web/dashboard."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.platform.core.http.pages :as pages]
            [wagoe.platform.shell.http.reitit-router :as reitit-router]
            [wagoe.platform.shell.system.wiring :as wiring]
            [wagoe.shared.ui.core.home :as home]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.http :as http]
            [wagoe.user.shell.landing :as landing]
            [wagoe.user.shell.module-wiring :as wiring-user]
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
  [user _login-context]
  (case (:role user)
    :admin  "/web/beheer"
    :viewer "https://evil.example"
    nil))

(def not-a-fn "/web/x")

(defn custom-dashboard [_request]
  {:status 200 :body "custom"})

(deftest ^:contract everyone-lands-on-the-dashboard
  (is (= "/web/admin/" (login-location {} :admin)))
  (is (= "/web/admin/" (login-location {} :user)))
  (testing "unless the app serves /web/dashboard itself"
    (is (= "/web/dashboard" (login-location (config {:dashboard false}) :user)))
    (is (= "/web/dashboard" (login-location (config {:dashboard {:handler `custom-dashboard}}) :user)))))

(deftest ^:contract per-role-redirect
  (let [cfg (config {:login-redirect {:user "/web/overzicht" :default "/web/start"}})]
    (is (= "/web/overzicht" (login-location cfg :user)))
    (is (= "/web/start" (login-location cfg :viewer)))
    (is (= "/web/start" (login-location cfg :admin)) "unnamed admin takes :default")
    (testing "a safe return-to still wins"
      (is (= "/web/profile" (login-location cfg :user "/web/profile")))
      (is (= "/web/overzicht" (login-location cfg :user "https://evil.example"))))))

(deftest ^:contract fn-redirect
  (testing "a symbol, resolved at boot"
    (let [cfg (config {:login-redirect `landing-of})]
      (is (= "/web/beheer" (login-location cfg :admin)))
      (is (= "/web/admin/" (login-location cfg :user)) "nil means the default")
      (is (= "/web/admin/" (login-location cfg :viewer)) "an off-site answer is refused")))
  (testing "a function value"
    (is (= "/web/x" (login-location (config {:login-redirect (constantly "/web/x")}) :user))))
  (testing "a function that throws means the default"
    (is (= "/web/admin/"
           (login-location (config {:login-redirect (fn [_ _] (throw (ex-info "boom" {})))}) :user)))))

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
                    {:login-redirect `not-a-fn}
                    {:dashboard {:handler `not-a-fn}}
                    {:dashboard "off"}
                    {:dashboard {:handler 'no.such.ns/page}}
                    {:dashboard {:handler `landing-of :extra 1}}
                    {:home "https://evil.example"}
                    {:landing "https://evil.example"}
                    {:landing "/"}
                    {:landing "/web"}
                    {:landing "/?ref=home"}
                    {:landing "/web#top"}
                    {:registration "no"}]]
    (is (= :configuration-error
           (:type (ex-data (try (landing/prepare (config settings)) nil
                                (catch Exception e e)))))
        (pr-str settings)))
  (testing "the module wiring checks them, so boot stops"
    (is (thrown? clojure.lang.ExceptionInfo
                 (wiring-user/ig-config nil {:config (config {:dashboard "off"})}))))
  (testing "a namespace that does not load keeps its cause"
    (is (some? (ex-cause (try (landing/prepare (config {:login-redirect 'no.such.ns/landing})) nil
                              (catch Exception e e)))))))

;; =============================================================================
;; /web/dashboard
;; =============================================================================

(defn- user-web-routes [settings]
  (:web (http/user-routes (reify ports/IUserService) nil (landing/prepare (config settings)))))

(defn- dashboard-route [settings]
  (first (filter #(= "/dashboard" (first %)) (user-web-routes settings))))

(def ^:private app-dashboard
  {:web [["/dashboard" {:public true
                        :get    {:handler (fn [_] {:status 200 :body "app dashboard"})}}]]})

(defn- mounted [settings & contributions]
  (reitit-router/compile-routes
   (:web (wiring/module-route-contributions (cons {:web (user-web-routes settings)} contributions)))
   {:swagger-enabled false}))

(deftest ^:contract dashboard-false-leaves-the-path-to-the-app
  (is (nil? (dashboard-route {:dashboard false})))
  (is (= "app dashboard"
         (:body ((mounted {:dashboard false} app-dashboard) {:request-method :get :uri "/web/dashboard"})))))

(deftest ^:contract default-dashboard-redirects-to-the-admin-home
  (is (= "/web/admin/"
         (get-in ((get-in (dashboard-route {}) [1 :get :handler]) {}) [:headers "Location"])))
  (is (= :wagoe/ambiguous-routes
         (:type (ex-data (try (mounted {} app-dashboard) nil (catch Exception e e)))))))

(deftest ^:contract custom-dashboard-handler-is-served
  (let [handler (get-in (dashboard-route {:dashboard {:handler `custom-dashboard}}) [1 :get :handler])]
    (is (= "custom" (:body (handler {}))))))

(deftest ^:contract without-the-admin-the-dashboard-is-the-profile
  (let [cfg      (landing/prepare (config {}))
        redirect #(get-in ((landing/dashboard-handler cfg) {}) [:headers "Location"])]
    (is (= "/web/admin/" (redirect)))
    (binding [pages/*mounted?* (complement #{"/web/admin/"})]
      (is (= "/web/profile" (redirect)))
      (is (= "/web/profile" (landing/path-for cfg {:role :user} {}))))))

;; =============================================================================
;; :home — what the layouts link to
;; =============================================================================

(defn- home-under [settings]
  (((landing/wrap-home (landing/prepare (config settings)))
    (fn [_] home/*path*))
   {}))

(deftest ^:contract home-path-reaches-the-layouts
  (is (= "/web/dashboard" (home-under {})) "which redirects to the dashboard")
  (is (= "/web/dashboard" (home-under {:dashboard {:handler `custom-dashboard}})))
  (is (= "/web" (home-under {:dashboard false}))
      "the app owns /web/dashboard, so home is /web, which sends a user to their landing")
  (is (= "/app" (home-under {:dashboard false :home "/app"})))
  (is (= "/app" (home-under {:home "/app"}))))

;; =============================================================================
;; :landing — where / and /web send everyone
;; =============================================================================

(defn- web-root [settings session]
  ((web-handlers/web-root-page-handler
    (reify ports/IUserService (validate-session [_ _] session))
    (landing/prepare (config settings)))
   {:cookies {"session-token" {:value "t"}}}))

(defn- root-location [settings]
  (let [routes (:static (http/user-routes (reify ports/IUserService) nil (landing/prepare (config settings))))]
    (when-let [[_ data] (first (filter #(= "/" (first %)) routes))]
      (is (true? (:public data)) "a signed-out visitor must reach it")
      (get-in ((get-in data [:get :handler]) {}) [:headers "Location"]))))

(deftest ^:contract landing-unset-keeps-the-defaults
  (is (= 200 (:status (web-root {} nil))) "the welcome page")
  (is (= "/web/admin/" (get-in (web-root {} {:user {:role :user}}) [:headers "Location"])))
  (is (nil? (root-location {})) "the platform's / stays"))

(deftest ^:contract landing-sends-everyone-there
  (let [settings {:landing "/web/welkom" :login-redirect {:user "/web/overzicht"}}]
    (testing "/web, signed out and signed in"
      (is (= "/web/welkom" (get-in (web-root settings nil) [:headers "Location"])))
      (is (= "/web/welkom" (get-in (web-root settings {:user {:role :user}}) [:headers "Location"]))))
    (testing "/, which the module contributes for everyone"
      (is (= "/web/welkom" (root-location settings))))
    (testing ":login-redirect still decides where signing in lands"
      (is (= "/web/overzicht" (login-location (config settings) :user))))))

;; =============================================================================
;; :registration — whether /web/register exists
;; =============================================================================

(defn- links-to-register? [response]
  (str/includes? (str (:body response)) "/web/register"))

(defn- login-pages
  "The login page as GET renders it, and as a failed sign-in re-renders it."
  [settings]
  (let [cfg (landing/prepare (config settings))]
    [((web-handlers/login-page-handler cfg) {})
     ((web-handlers/login-submit-handler (reify ports/IUserService) cfg)
      {:form-params {"email" "user@example.com"}})]))

(deftest ^:contract registration-on-by-default
  (let [app (mounted {})]
    (is (= 200 (:status (app {:request-method :get :uri "/web/register"})))))
  (doseq [page (login-pages {})]
    (is (links-to-register? page))))

(deftest ^:contract registration-false-drops-the-routes-and-the-link
  (let [app (mounted {:registration false})]
    (is (= 404 (:status (app {:request-method :get :uri "/web/register"}))))
    (is (= 404 (:status (app {:request-method :post :uri "/web/register"
                              :form-params {"email" "x@example.com" "password" "Secret-pass-123"}})))
        "a signed-out POST creates nothing"))
  (doseq [page (login-pages {:registration false})]
    (is (not (links-to-register? page)))))
