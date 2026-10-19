(ns wagoe.user.shell.login-layout-test
  "An application's `:login-layout` around Wagoe's sign-in forms."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.shared.ui.core.layout :as layout]
            [wagoe.user.core.ui :as user-ui]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.hooks :as hooks]
            [wagoe.user.shell.landing :as landing]
            [wagoe.user.shell.web-handlers :as web-handlers]))

(def ^:private seen (atom nil))

(defn rijks-layout
  "An app layout: its own header and footer, a hero and an extra button."
  [title body opts]
  (reset! seen opts)
  [:html
   [:head [:title title] [:link {:rel "stylesheet" :href "/css/rijkshuisstijl.css"}]]
   [:body
    [:header "Ministerie van BZK"]
    [:img.hero {:src "/img/hero.jpg"}]
    body
    [:a.button {:href "/werkbezoek"} "Werkbezoek aanvragen"]
    [:footer "Rijksoverheid"]]])

(def ^:private config {:active {:wagoe/user {:login-layout rijks-layout}}})

(defn- body-of [response] (str (:body response)))

(defn- with-token [f]
  (binding [csrf/*token* "csrf-token-123"] (f)))

(defn- submit [svc cfg form]
  (with-token #((web-handlers/login-submit-handler svc cfg) {:form-params form})))

(deftest ^:unit the-default-login-page-is-unchanged
  (let [opts {:logo-url "/logo.svg" :return-to "/web/x"}]
    (is (= (layout/pilot-page-layout
            [:t :user/link-signin]
            [:div.login-page.auth-page
             [:div.page-header.auth-header
              [:img.auth-logo {:src "/logo.svg" :alt "" :style "height:48px; width:auto; display:block; margin:0 auto 12px;"}]
              [:h1 [:t :wagoe/app-name]]
              [:p [:t :user/login-subtitle]]]
             nil
             (user-ui/login-form {:return-to "/web/x"} {})]
            opts)
           (user-ui/login-page {} {} opts))))
  (testing "through the handler, with no hook set"
    (let [body (body-of (with-token #((web-handlers/login-page-handler {}) {})))]
      (is (str/includes? body "login-subtitle"))
      (is (str/includes? body "csrf-token-123"))
      (is (not (str/includes? body "Ministerie"))))))

(deftest ^:unit the-app-layout-wraps-the-login-form
  (let [body (body-of (with-token #((web-handlers/login-page-handler config)
                                    {:query-params {"return-to" "/web/y"}})))]
    (is (str/includes? body "Ministerie van BZK"))
    (is (str/includes? body "Werkbezoek aanvragen"))
    (is (str/includes? body "rijkshuisstijl.css"))
    (is (str/includes? body "action=\"/web/login\""))
    (is (str/includes? body "csrf-token-123") "the CSRF field stays in the form")
    (is (str/includes? body "value=\"/web/y\"") "return-to is kept")
    (is (not (str/includes? body "auth-header")) "Wagoe's own header is left out")
    (is (not (str/includes? body "pilot")) "and so is its CSS")
    (is (= :login (:page @seen)))
    (is (some? (:request @seen)))
    (is (not (contains? @seen :login-layout)))))

(deftest ^:unit errors-render-inside-the-app-layout
  (let [svc      #_{:clj-kondo/ignore [:missing-protocol-method]}
                 (reify ports/IUserService
                   (authenticate-user [_ _] {:authenticated false :reason :invalid-credentials}))
        response (submit svc config {"email" "a@b.nl" "password" "wrong-password"})]
    (is (= 401 (:status response)))
    (is (str/includes? (body-of response) "Invalid email or password"))
    (is (str/includes? (body-of response) "Ministerie van BZK"))
    (is (str/includes? (body-of response) "csrf-token-123"))
    (is (= {"email" "a@b.nl"} (get-in @seen [:request :form-params]))
        "the layout never sees the password"))
  (testing "a request the schema refuses"
    (let [response (submit nil config {"email" "not-an-email" "password" ""})]
      (is (= 400 (:status response)))
      (is (str/includes? (body-of response) "Ministerie van BZK"))
      (is (str/includes? (body-of response) "field-errors")))))

(deftest ^:unit the-mfa-step-uses-the-app-layout
  (let [svc      #_{:clj-kondo/ignore [:missing-protocol-method]}
                 (reify ports/IUserService
                   (authenticate-user [_ creds]
                     (if (:mfa-code creds)
                       {:authenticated false :reason :mfa-verification-failed}
                       {:requires-mfa? true})))
        asked    (submit svc config {"email" "a@b.nl" "password" "password123"})]
    (is (= 200 (:status asked)))
    (is (str/includes? (body-of asked) "name=\"mfa-code\""))
    (is (str/includes? (body-of asked) "Ministerie van BZK"))
    (is (str/includes? (body-of asked) "csrf-token-123"))
    (is (= :mfa (:page @seen)))
    (let [refused (submit svc config {"email" "a@b.nl" "password" "password123" "mfa-code" "000000"})]
      (is (= 401 (:status refused)))
      (is (str/includes? (body-of refused) "Invalid MFA code"))
      (is (str/includes? (body-of refused) "Ministerie van BZK"))))
  (testing "after a provider sign-in"
    (let [page (with-token #(user-ui/oidc-mfa-page {:mfa-code ["bad code"]}
                                                   {:login-layout rijks-layout}))]
      (is (= :oidc-mfa (:page @seen)))
      (is (str/includes? (pr-str page) "Ministerie van BZK"))
      (is (str/includes? (pr-str page) "bad code"))
      (is (str/includes? (pr-str page) "csrf-token-123")))))

(deftest ^:unit registration-uses-the-app-layout
  (let [body (body-of (with-token #((web-handlers/register-page-handler config) {})))]
    (is (str/includes? body "action=\"/web/register\""))
    (is (str/includes? body "href=\"/web/login\"") "the way back to login stays")
    (is (str/includes? body "Ministerie van BZK"))
    (is (= :register (:page @seen)))))

(deftest ^:unit the-hook-takes-a-fn-a-symbol-or-a-component
  (let [component (reify ports/ILoginLayout
                    (login-page [_ title _body opts] [:p title (:page opts)]))]
    (is (= [:p "T" :login]
           ((:login-layout (hooks/prepare {} {:login-layout component})) "T" nil {:page :login}))))
  (is (= rijks-layout
         (get-in (landing/prepare {:active {:wagoe/user {:login-layout `rijks-layout}}})
                 [:active :wagoe/user :login-layout])))
  (testing "a module's layout reaches the handlers' config"
    (is (= rijks-layout
           (get-in (landing/with-hooks {} (hooks/prepare {} {:login-layout rijks-layout}))
                   [:active :wagoe/user :login-layout]))))
  (testing "a bad value fails boot"
    (doseq [bad [{:active {:wagoe/user {:login-layout "rijks"}}}
                 {:active {:wagoe/user {:login-layout 'not-qualified}}}]]
      (is (= :configuration-error
             (:type (ex-data (try (landing/prepare bad) nil
                                  (catch clojure.lang.ExceptionInfo e e)))))))
    (is (= :configuration-error
           (:type (ex-data (try (hooks/prepare {:login-layout rijks-layout}
                                               {:login-layout rijks-layout})
                                nil
                                (catch clojure.lang.ExceptionInfo e e))))))))

(deftest ^:unit registration-off-hides-the-link-inside-the-app-layout
  (let [cfg  (assoc-in config [:active :wagoe/user :registration] false)
        body (body-of (with-token #((web-handlers/login-page-handler cfg) {})))]
    (is (str/includes? body "Ministerie van BZK"))
    (is (not (str/includes? body "/web/register")))))
