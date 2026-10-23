(ns wagoe.user.shell.profile-layout-test
  "An application's `:profile-layout` around Wagoe's profile pages."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.platform.core.csrf :as csrf]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.hooks :as hooks]
            [wagoe.user.shell.landing :as landing]
            [wagoe.user.shell.web-handlers :as web-handlers]))

(def ^:private seen (atom nil))

(defn rijks-layout
  "An app layout: its own header and footer around the profile."
  [title body opts]
  (reset! seen opts)
  [:html
   [:head [:title title] [:link {:rel "stylesheet" :href "/css/profile.css"}]]
   [:body [:header "Ministerie van BZK"] body [:footer "Rijksoverheid"]]])

(def ^:private config {:active {:wagoe/user {:profile-layout rijks-layout}}})

(defn- body-of [handler request]
  (str (:body (binding [csrf/*token* "csrf-token-123"] (handler request)))))

(deftest ^:unit the-default-profile-pages-are-unchanged
  (let [body (body-of (web-handlers/mfa-setup-page-handler nil {}) {:user {:email "a@b.c"}})]
    (is (str/includes? body "admin-shell"))
    (is (not (str/includes? body "Ministerie")))))

(deftest ^:unit the-app-layout-wraps-the-profile-pages
  (doseq [[page handler] {:mfa-setup   (web-handlers/mfa-setup-page-handler nil config)
                          :mfa-disable (web-handlers/mfa-disable-page-handler nil config)}]
    (testing page
      (let [body (body-of handler {:user {:email "a@b.c"} :headers {"cookie" "secret"}})]
        (is (str/includes? body "Ministerie van BZK"))
        (is (str/includes? body "profile-page"))
        (is (not (str/includes? body "admin-shell")) "Wagoe's shell is left out")
        (is (= "a@b.c" (get-in @seen [:user :email])))
        (is (some? (:request @seen)))
        (is (not (contains? @seen :profile-layout)))))))

(deftest ^:unit a-plain-form-stays-signed
  (is (str/includes? (body-of (web-handlers/mfa-disable-page-handler nil config) {:user {:email "a@b.c"}})
                     "csrf-token-123")))

(deftest ^:unit the-hook-takes-a-fn-a-symbol-or-a-component
  (let [component (reify ports/IProfileLayout
                    (profile-page [_ title _body _opts] [:p title]))]
    (is (= [:p "T"] ((:profile-layout (hooks/prepare {} {:profile-layout component})) "T" nil {}))))
  (is (= rijks-layout
         (get-in (landing/prepare {:active {:wagoe/user {:profile-layout `rijks-layout}}})
                 [:active :wagoe/user :profile-layout])))
  (testing "a module's layout reaches the handlers' config"
    (is (= rijks-layout
           (get-in (landing/with-hooks {} (hooks/prepare {} {:profile-layout rijks-layout}))
                   [:active :wagoe/user :profile-layout]))))
  (testing "a bad value fails boot"
    (is (= :configuration-error
           (:type (ex-data (try (landing/prepare {:active {:wagoe/user {:profile-layout "rijks"}}}) nil
                                (catch clojure.lang.ExceptionInfo e e))))))))
