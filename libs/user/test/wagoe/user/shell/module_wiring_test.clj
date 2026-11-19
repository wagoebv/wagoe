(ns wagoe.user.shell.module-wiring-test
  (:require [wagoe.tenant.ports]
            [wagoe.user.shell.module-wiring :as sut]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging.test :as log-test]
            [wagoe.user.shell.auth-persistence]
            [wagoe.user.ports]
            [integrant.core :as ig]))

(deftest ^:unit user-http-middleware-is-a-seq-the-handler-can-thread
  ;; A seq, like tenant's, because platform concatenates it into the pipeline.
  (testing "with a user service, one middleware"
    (let [mw (ig/init-key :wagoe/user-http-middleware {:user-service ::svc})]
      (is (= 1 (count mw)))
      (is (fn? (first mw)) "and it is a (fn [handler] ...)")))

  (testing "without one, none — rather than a middleware that cannot work"
    (is (= [] (ig/init-key :wagoe/user-http-middleware {})))
    (is (= [] (ig/init-key :wagoe/user-http-middleware {:user-service nil})))))

(deftest ^:unit user-contributes-its-authentication-middleware-to-the-handler
  ;; :auth-middleware, not :extra-middleware. Platform puts the former
  ;; outermost, ahead of the tenant middleware that reads [:user :id]; module
  ;; :extra-middleware is collected in module-key order, and :wagoe/tenant
  ;; sorts before :wagoe/user, so the order could not come from module
  ;; iteration (BOU-373).
  (let [{:keys [components http]} (sut/ig-config nil {:config {} :enabled #{}})]
    (is (contains? components :wagoe/user-http-middleware))
    (is (= (ig/ref :wagoe/user-service)
           (:user-service (:wagoe/user-http-middleware components)))
        "it needs the service to validate sessions with")
    (is (= (ig/ref :wagoe/user-http-middleware) (:auth-middleware http)))
    (is (= (ig/ref :wagoe/user-home-middleware) (:extra-middleware http))
        "only the home binding, whose position does not matter")))

(deftest ^:unit the-deprecated-repository-says-so-when-it-is-wired
  ;; A `### Deprecated` entry the running system says nothing about is an
  ;; announcement to whoever already read it (BOU-433). The module contributes
  ;; :wagoe/user-repository and not this key, so init only runs for a
  ;; hand-wired one.
  (testing "init warns, naming the replacement and the removal"
    (log-test/with-log
      (with-redefs [wagoe.user.shell.auth-persistence/create-auth-user-repository
                    (constantly ::repo)]
        (is (= ::repo (ig/init-key :wagoe/auth-user-repository {:ctx ::ctx}))))
      (is (log-test/logged? 'wagoe.user.shell.module-wiring :warn #"DEPRECATED"))
      (is (log-test/logged? 'wagoe.user.shell.module-wiring :warn #":wagoe/user-repository"))
      (is (log-test/logged? 'wagoe.user.shell.module-wiring :warn #"removed in 2\.0")))))

(deftest ^:unit oidc-is-wired-only-when-a-provider-is-named
  (let [without (sut/ig-config nil {:config {}})
        with    (sut/ig-config {:oidc {:providers {:google {:issuer "https://accounts.google.com"
                                                            :client-id "app"}}}}
                               {:config {}})]
    (is (not (contains? (:components without) :wagoe/user-oidc)))
    (is (= [(ig/ref :wagoe/user-routes) (ig/ref :wagoe/user-group-routes) (ig/ref :wagoe/user-export-routes)]
           (:routes without)))
    (is (contains? (:components with) :wagoe/user-oidc))
    (is (= [(ig/ref :wagoe/user-routes) (ig/ref :wagoe/user-group-routes)
            (ig/ref :wagoe/user-export-routes) (ig/ref :wagoe/user-oidc-routes)]
           (:routes with)))))

(deftest ^:unit oidc-refuses-to-boot-misconfigured
  (doseq [settings [{:providers {:google {:issuer "https://accounts.google.com" :client-id "app"}}}
                    {:base-url "https://app.example.com"
                     :providers {:google {:issuer "https://accounts.google.com"}}}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"OIDC"
                          (ig/init-key :wagoe/user-oidc {:settings settings})))))

(deftest ^:unit auth-settings-reach-the-auth-service
  (let [{:keys [components]} (sut/ig-config {:auth {:mfa-required? 'my.app/mfa? :max-login-attempts 3}}
                                            {:config {}})]
    (is (= {:max-login-attempts 3} (get-in components [:wagoe/auth-service :auth-config])))
    (is (= 'my.app/mfa? (get-in components [:wagoe/auth-hooks :configured :mfa-required?]))
        "the hook goes through :wagoe/auth-hooks, where module contributions meet it"))
  (is (= {} (get-in (sut/ig-config nil {:config {}})
                    [:components :wagoe/auth-service :auth-config]))))

(deftest ^:unit user-supplies-the-error-page-in-the-apps-layout
  (let [{:keys [http]} (sut/ig-config nil {:config {} :enabled #{}})]
    (is (fn? (:error-page http)))))

(deftest ^:unit extra-cards-without-the-admin-warn
  (let [config {:dashboard-extra-cards [[:div "Reports"]]}]
    (log-test/with-log
      (sut/ig-config nil {:config config :enabled #{:wagoe/user}})
      (is (log-test/logged? 'wagoe.user.shell.module-wiring :warn #":dashboard-extra-cards is shown nowhere")))
    (log-test/with-log
      (sut/ig-config nil {:config config :enabled #{:wagoe/user :wagoe/admin}})
      (is (not (log-test/logged? 'wagoe.user.shell.module-wiring :warn #":dashboard-extra-cards"))
          "the admin shows them, and warns itself"))))

(deftest ^:unit expired-access-is-removed-on-the-jobs-module
  ;; BOU-856
  (testing "without jobs, nothing is scheduled"
    (let [g (sut/ig-config nil {:config {} :enabled #{:wagoe/user}})]
      (is (empty? (:job-handlers g)))
      (is (empty? (:recurring g)))))
  (let [g (sut/ig-config nil {:config {} :enabled #{:wagoe/user :wagoe/jobs}})]
    (testing "with jobs, a handler and its default schedule"
      (is (= [(ig/ref :wagoe.user/job-handlers)] (:job-handlers g)))
      (is (= {sut/access-expiry-job-type sut/access-expiry-schedule} (:recurring g))))
    (testing "the handler counts what it removed"
      (let [handler (get (ig/init-key :wagoe.user/job-handlers
                                      {:group-service (reify wagoe.user.ports/ITimeBoundAccess
                                                        (add-group-member-until [_ _ _ _] nil)
                                                        (expire-access! [_ _] {:members [{}] :assignments []}))})
                         sut/access-expiry-job-type)]
        (is (= {:success? true :result {:members 1 :assignments 0}} (handler {})))))))

(def ^:private group-protocols
  '[wagoe.user.ports/IAccessExplain wagoe.user.ports/IRightsPreview wagoe.user.ports/IRoleAssignments
    wagoe.user.ports/IGrantOptions wagoe.user.ports/ITimeBoundAccess])

(defmacro ^:private group-service-without
  "A group service implementing IUserGroupService and every protocol beside it
   but `missing`."
  [missing]
  `(reify wagoe.user.ports/IUserGroupService ~@(remove #{missing} group-protocols)))

(defmacro ^:private each-missing
  "{protocol name, a group service lacking only it} for each of `group-protocols`."
  []
  (into {} (for [p group-protocols] [(name p) `(group-service-without ~p)])))

(defn- boot-error [k service & [config]]
  (try (ig/init-key k {:user-service nil :group-service service :config (or config {})})
       nil
       (catch clojure.lang.ExceptionInfo e
         [(ex-message e) (ex-data e)])))

(deftest ^:unit a-group-service-lacking-a-newer-protocol-refuses-to-boot
  ;; BOU-859: refused at startup, not on the first request to its page.
  (doseq [[proto service] (each-missing)]
    (testing proto
      (let [[message data] (boot-error :wagoe/user-group-routes service)]
        (is (= (str "WGE-107: the group service does not implement " proto) message))
        (is (= :configuration-error (:type data))))))
  (testing "the expiry job needs ITimeBoundAccess"
    (is (= "WGE-107: the group service does not implement ITimeBoundAccess"
           (first (boot-error :wagoe.user/job-handlers
                              (group-service-without wagoe.user.ports/ITimeBoundAccess))))))
  (testing "one implementing only IUserGroupService is refused"
    (is (re-find #"^WGE-107: " (first (boot-error :wagoe/user-group-routes (reify wagoe.user.ports/IUserGroupService))))))
  (testing "without the web UI no page calls them, so it boots"
    (is (nil? (boot-error :wagoe/user-group-routes (reify wagoe.user.ports/IUserGroupService)
                          {:active {:wagoe/settings {:features {:user-web-ui {:enabled? false}}}}}))))
  (testing "one implementing them all boots"
    (is (nil? (boot-error :wagoe/user-group-routes (group-service-without nil))))))

(deftest ^:unit ^:security a-user-service-without-jwt-sessions-refuses-to-boot
  ;; Without it the JWT middleware lets no token through (BOU-1006)
  (let [svc #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify wagoe.user.ports/IUserDeletion wagoe.user.ports/IUserSessionRevocation)]
    (is (= "WGE-107: the user service does not implement IJwtSessions"
           (try (ig/init-key :wagoe/user-routes {:user-service svc :config {}}) nil
                (catch clojure.lang.ExceptionInfo e (ex-message e)))))))

(deftest ^:unit ^:security the-membership-service-reaches-the-user-service-and-oidc
  ;; Without it a link by email, and an email change, spare no tenant's admin (BOU-1008)
  (let [{:keys [components]} (sut/ig-config {:oidc {:providers {:google {:issuer "https://accounts.google.com"
                                                                         :client-id "x" :client-secret "y"}}}}
                                            {:config {}})]
    (is (= (ig/refset :wagoe/membership-service) (get-in components [:wagoe/user-service :membership-services])))
    (is (= (ig/refset :wagoe/membership-service) (get-in components [:wagoe/user-oidc :membership-services])))
    (is (= (ig/refset :wagoe/user-identity-repository) (get-in components [:wagoe/user-service :identity-repositories])))))

(deftest ^:unit ^:security a-membership-service-that-cannot-list-a-users-memberships-refuses-to-boot
  (let [m #_{:clj-kondo/ignore [:missing-protocol-method]} (reify wagoe.tenant.ports/ITenantMembershipService)]
    (is (= "WGE-107: the membership service does not implement ITenantMembershipsOfUser"
           (try (#'sut/require-membership-lookup! #{m}) nil
                (catch clojure.lang.ExceptionInfo e (ex-message e)))))))

(deftest ^:unit ^:security linking-by-email-with-open-registration-warns-at-boot
  ;; Accounts registered before BOU-1009 carry no mark
  (let [warn! #'sut/warn-unmarked-registrations!
        on    {:providers {:google {:link-by-email? true} :azure {}}}]
    (log-test/with-log
      (warn! on true (constantly 3))
      (is (log-test/logged? 'wagoe.user.shell.module-wiring :warn #"3 accounts from before BOU-1009")))
    (log-test/with-log
      (warn! on false (constantly 3))
      (warn! {:providers {:google {}}} true (constantly 3))
      (warn! on true (constantly 0))
      (is (not (log-test/logged? 'wagoe.user.shell.module-wiring :warn #"BOU-1009"))
          "registration off, no provider linking by email, or every account marked"))))

(deftest ^:unit ^:security a-user-repository-that-cannot-spend-codes-refuses-to-boot-the-mfa-service
  ;; Without them a backup code (BOU-708) or a TOTP code (BOU-1016) signs in twice
  (doseq [[proto repo] [["IBackupCodes" #_{:clj-kondo/ignore [:missing-protocol-method]}
                         (reify wagoe.user.ports/ITotpSteps)]
                        ["ITotpSteps" #_{:clj-kondo/ignore [:missing-protocol-method]}
                         (reify wagoe.user.ports/IBackupCodes)]]]
    (is (= (str "WGE-107: the user repository does not implement " proto)
           (try (ig/init-key :wagoe/mfa-service {:user-repository repo}) nil
                (catch clojure.lang.ExceptionInfo e (ex-message e)))))))

(deftest ^:unit ^:security a-short-or-example-oidc-state-secret-is-refused
  ;; BOU-1021: it signs the state that carries a sign-in back
  (let [check #(try (#'sut/check-state-secret! {:state-secret %1} %2) nil
                    (catch clojure.lang.ExceptionInfo e (ex-message e)))]
    (is (re-find #"at least 32" (str (check "short" true))))
    (is (re-find #"example value" (str (check "your-oidc-state-secret-at-least-32-chars" false))))
    (is (nil? (check nil false)) "without one, JWT_SECRET, checked where it is read")
    (is (false? (get-in (sut/ig-config {:oidc {:providers {:google {:issuer "https://accounts.google.com"
                                                                    :client-id "x" :client-secret "y"}}}}
                                       {:config {:wagoe/profile :prod}})
                        [:components :wagoe/user-oidc :dev-like?])))))
