(ns wagoe.user.shell.auth-hooks-test
  "Sign-in hooks contributed by an application module as components, so they
   can read state a component holds rather than a process-global atom."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [acme.office.shell.module-wiring]
            [wagoe.platform.shell.system.config :as system-config]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.hooks :as hooks]
            [wagoe.user.shell.in-memory-repository-test :as mem]
            [wagoe.user.shell.landing :as landing]
            [wagoe.user.shell.mfa :as mfa]
            [wagoe.user.shell.module-wiring]
            [wagoe.user.shell.web-handlers :as web-handlers]))

(use-fixtures :each
  (fn [t]
    (with-redefs [auth/get-jwt-secret (constantly "test-secret-minimum-32-characters-long-for-testing")]
      (t))))

(def ^:private user {:id 1 :role :user :mfa-enabled true})

(defn- config-error [f]
  (:type (ex-data (try (f) nil (catch clojure.lang.ExceptionInfo e e)))))

(defn always-mfa [_user _ctx] true)

(deftest ^:unit each-hook-takes-a-fn-a-symbol-or-a-component
  (let [policy  (reify ports/IMfaPolicy (mfa-required? [_ _ ctx] (= "x" (:ip-address ctx))))
        landing (reify ports/ILoginRedirect (login-path [_ _ ctx] (:ip-address ctx)))
        h       (hooks/prepare {} {:mfa-required? policy :login-redirect landing})]
    (is (true? ((:mfa-required? h) user {:ip-address "x"})))
    (is (= "/p" ((:login-redirect h) user {:ip-address "/p"}))))
  (is (true? ((:mfa-required? (hooks/prepare {} {:mfa-required? `always-mfa})) user {})))
  (is (= "/f" ((:login-redirect (hooks/prepare {} {:login-redirect (fn [_ _] "/f")})) user {})))
  (testing "config alone"
    (is (= {:login-redirect {:user "/x"}}
           (hooks/prepare {:mfa-required? nil :login-redirect {:user "/x"}} nil)))))

(deftest ^:unit a-bad-hook-fails-boot
  (doseq [[configured contributed] [[{:mfa-required? `always-mfa} {:mfa-required? always-mfa}]
                                    [{:login-redirect {:user "/x"}} {:login-redirect (fn [_ _])}]
                                    [{} {:mfa-requried? always-mfa}]
                                    [{} {:mfa-required? "always-mfa"}]
                                    [{} {:login-redirect {:user "/x"}}]
                                    [{} {:mfa-required? 'no.such.ns/hook}]]]
    (is (= :configuration-error (config-error #(hooks/prepare configured contributed)))
        (pr-str [configured contributed]))))

;; =============================================================================
;; Two systems in one JVM
;; =============================================================================

(defn- system
  "The auth hooks and service of one system, with the office module's hook
   components for `office`."
  [office]
  (let [users (mem/->MemoryUserRepository (atom {}))]
    (ig/init {:wagoe/office-mfa-policy {:office office}
              :wagoe/office-landing    {:office office}
              :wagoe/auth-hooks        {:configured  {}
                                        :contributed {:mfa-required?  (ig/ref :wagoe/office-mfa-policy)
                                                      :login-redirect (ig/ref :wagoe/office-landing)}}
              :wagoe/auth-service      {:user-repository    users
                                        :session-repository (mem/->MemorySessionRepository (atom {}))
                                        :mfa-service        (mfa/create-mfa-service users {})
                                        :auth-config        {}
                                        :hooks              (ig/ref :wagoe/auth-hooks)}})))

(defn- lands-at
  "Where a user signing in from `ip` lands, with `hooks`."
  [hooks ip]
  (let [svc (reify ports/IUserService
              (authenticate-user [_ _]
                {:authenticated true :user {:role :user} :session {:session-token "t"}}))]
    (-> ((web-handlers/login-submit-handler svc (landing/with-hooks {} hooks))
         {:remote-addr ip
          :form-params {"email" "user@example.com" "password" "password123"}})
        (get-in [:headers "Location"]))))

(deftest ^:integration two-systems-keep-their-own-hooks
  (let [a (system "10.")
        b (system "192.168.")]
    (try
      (testing "the MFA policy is each system's own component"
        (is (false? (auth/mfa-required? (:wagoe/auth-service a) user {:ip-address "10.0.0.5"})))
        (is (true? (auth/mfa-required? (:wagoe/auth-service b) user {:ip-address "10.0.0.5"}))))
      (testing "and the landing page sees the request's client IP"
        (is (= "/web/kantoor" (lands-at (:wagoe/auth-hooks a) "10.0.0.5")))
        (is (= "/web/thuis" (lands-at (:wagoe/auth-hooks b) "10.0.0.5")))
        (is (= "/web/kantoor" (lands-at (:wagoe/auth-hooks b) "192.168.1.2"))))
      (finally (ig/halt! a) (ig/halt! b)))))

;; =============================================================================
;; Contributed through the module graph
;; =============================================================================

(defn- config [& kvs]
  {:wagoe/profile :test
   :active (into {:wagoe/settings {:name "test"}
                  :wagoe/h2       {:memory true}}
                 (partition-all 2)
                 kvs)})

(deftest ^:integration an-app-module-contributes-the-hooks
  (let [c (system-config/system-config
           (config :wagoe/user {} :wagoe/office {:enabled? true :office "10."})
           {:base-ns "acme"})]
    (is (= {:mfa-required?  (ig/ref :wagoe/office-mfa-policy)
            :login-redirect (ig/ref :wagoe/office-landing)}
           (get-in c [:wagoe/auth-hooks :contributed])))
    (let [s (ig/init c [:wagoe/auth-hooks])]
      (try
        (is (false? ((get-in s [:wagoe/auth-hooks :mfa-required?]) user {:ip-address "10.1.1.1"})))
        (is (= "/web/thuis" (lands-at (:wagoe/auth-hooks s) "203.0.113.7")))
        (finally (ig/halt! s))))))

(deftest ^:integration a-hook-set-twice-or-with-no-user-module-fails-boot
  (testing "in config and by a module"
    (let [c (system-config/system-config
             (config :wagoe/user {:auth {:mfa-required? `always-mfa}}
                     :wagoe/office {:enabled? true :office "10."})
             {:base-ns "acme"})]
      (is (= :configuration-error
             (some-> (try (ig/init c [:wagoe/auth-hooks]) nil (catch Exception e e))
                     ex-cause ex-data :type)))))
  (testing "with the user module off"
    (is (= :configuration-error
           (config-error #(system-config/system-config
                           (config :wagoe/office {:enabled? true :office "10."})
                           {:base-ns "acme"}))))))
