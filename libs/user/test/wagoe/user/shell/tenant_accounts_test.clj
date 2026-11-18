(ns wagoe.user.shell.tenant-accounts-test
  "One account per tenant (BOU-995): an email is unique within a tenant, not
   across them. Sign-in takes the tenant from the URL, else from the accounts
   email and password both open, choosing only among those."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [support.test-db :as test-db]
            [wagoe.platform.database :as db]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.http :as user-http]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as service]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.user.core.ui :as user-ui]
            [wagoe.user.shell.tenant-scope :as tenant-scope]
            [wagoe.user.shell.web-handlers :as web-handlers])
  (:import [java.util UUID]))

^{:kaocha.testable/meta {:integration true :user true}}

(def ^:private tenant-a #uuid "a0000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b #uuid "b0000000-0000-0000-0000-00000000000b")

(def ^:private ^:dynamic *ctx* nil)


(use-fixtures :each
  (fn [f]
    (test-db/with-tenant-db "tenant_accounts"
      (fn [ctx]
        (persistence/initialize-user-schema! ctx)
        (binding [*ctx* ctx] (f))))))

(defn- user-service [& [observability]]
  (let [users    (persistence/create-user-repository *ctx*)
        sessions (persistence/create-session-repository *ctx*)]
    (service/create-user-service users sessions (persistence/create-audit-repository *ctx* {:default-limit 50}) {}
                                 (auth/create-authentication-service users sessions nil {})
                                 (or observability {}))))

(defn- register! [svc email password tenant-id]
  (ports/register-user svc (cond-> {:email email :name "Someone" :password password :role :user}
                             tenant-id (assoc :tenant-id tenant-id))))

(defn- thrown-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(def ^:private pw-a "Correct-Horse-A1")
(def ^:private pw-b "Correct-Horse-B2")

(deftest ^:integration ^:security creating-a-user-answers-the-same-whether-the-email-exists-in-another-tenant
  ;; It said "already exists" for another tenant's email (BOU-992 pinned that)
  (let [svc        (user-service)
        theirs     (register! svc "b@example.com" pw-b tenant-b)
        admin-of-a {:id (UUID/randomUUID) :role :admin :tenant-id tenant-a}
        create     (fn [email]
                     ((web-handlers/create-user-htmx-handler svc nil {})
                      {:user admin-of-a
                       :form-params {"name" "New" "email" email "password" "Other-Horse-77" "role" "user"}}))
        free       (create "free@example.com")
        taken      (create "b@example.com")]
    (is (= (:status free) (:status taken)) "the same answer")
    (is (= (get-in free [:headers "HX-Redirect"]) (get-in taken [:headers "HX-Redirect"])))
    (is (not (re-find #"(?i)already|taken|in use" (str (:body taken)))))
    (testing "tenant A has an account of its own; B's is untouched"
      (let [mine (ports/get-user-by-tenant-and-email svc tenant-a "b@example.com")]
        (is (some? mine))
        (is (not= (:id theirs) (:id mine)))
        (is (= (:id theirs) (:id (ports/get-user-by-tenant-and-email svc tenant-b "b@example.com"))))))
    (testing "within one tenant an email is still taken"
      (is (= 400 (:status (create "b@example.com")))))))

(deftest ^:integration ^:security an-email-is-unique-per-tenant
  (let [svc (user-service)]
    (register! svc "x@example.com" pw-a tenant-a)
    (is (= :user-exists (thrown-type #(register! svc "x@example.com" pw-a tenant-a))))
    (is (some? (register! svc "x@example.com" pw-b tenant-b)))
    (testing "accounts of no tenant are one key among themselves"
      (register! svc "root@example.com" pw-a nil)
      (is (= :user-exists (thrown-type #(register! svc "root@example.com" pw-a nil))))
      (is (some? (register! svc "root@example.com" pw-a tenant-a)) "a tenant may have the same email"))
    (testing "the database refuses a second account of one tenant and email"
      (let [repo (persistence/create-user-repository *ctx*)]
        (is (thrown? Exception (ports/create-user repo {:email "x@example.com" :name "Dup" :role :user :active true
                                                        :password-hash "x" :tenant-id tenant-a})))
        (when (= :pg test-db/engine)
          (is (thrown? Exception (ports/create-user repo {:email "root@example.com" :name "Dup" :role :user
                                                          :active true :password-hash "x"}))
              "on PostgreSQL, and among accounts of no tenant"))))))

(defn- sign-in [svc email password & [tenant-id]]
  (ports/authenticate-user svc (cond-> {:email email :password password}
                                 tenant-id (assoc :tenant-given? true :tenant-id (str tenant-id)))))

(deftest ^:integration ^:security sign-in-finds-the-account-of-the-tenant-or-of-the-password
  (let [svc (user-service)
        a   (register! svc "x@example.com" pw-a tenant-a)
        b   (register! svc "x@example.com" pw-b tenant-b)]
    (testing "a tenant named: that account only"
      (is (= (:id a) (get-in (sign-in svc "x@example.com" pw-a tenant-a) [:user :id])))
      (is (false? (:authenticated (sign-in svc "x@example.com" pw-b tenant-a))) "B's password is not A's"))
    (testing "no tenant: the account the password opens"
      (is (= (:id b) (get-in (sign-in svc "x@example.com" pw-b) [:user :id])))
      (is (false? (:authenticated (sign-in svc "x@example.com" "Wrong-Horse-99")))))
    (testing "the same password in both: a choice among those two only"
      (register! svc "y@example.com" pw-a tenant-a)
      (register! svc "y@example.com" pw-a tenant-b)
      (register! svc "y@example.com" pw-b nil)
      (let [r (sign-in svc "y@example.com" pw-a)]
        (is (= :tenant-choice-required (:reason r)))
        (is (= #{(str tenant-a) (str tenant-b)} (set (map :id (:tenants r)))))))))

(deftest ^:integration ^:security a-wrong-password-gives-no-choice-and-tells-nothing
  (let [svc (user-service)]
    (register! svc "y@example.com" pw-a tenant-a)
    (register! svc "y@example.com" pw-a tenant-b)
    (let [r (sign-in svc "y@example.com" "Wrong-Horse-99")]
      (is (false? (:authenticated r)))
      (is (nil? (:tenants r))))))

(deftest ^:integration the-sign-in-form-asks-which-tenant-and-takes-the-choice
  (let [svc    (user-service)
        _      (register! svc "y@example.com" pw-a tenant-a)
        b      (register! svc "y@example.com" pw-a tenant-b)
        submit #((web-handlers/login-submit-handler svc {})
                 (merge {:form-params (merge {"email" "y@example.com" "password" pw-a} %1)
                         :headers {"user-agent" "test"} :remote-addr "127.0.0.1"}
                        %2))
        asked  (submit {} {})]
    (is (= 200 (:status asked)))
    (is (str/includes? (str (:body asked)) (str "value=\"" tenant-b "\"")))
    (testing "the choice signs in to that tenant's account"
      (let [chosen (submit {"tenant-id" (str tenant-b)} {})]
        (is (= 302 (:status chosen)))
        (is (some? (get-in chosen [:cookies "session-token" :value])))))
    (testing "on a tenant's URL there is nothing to choose"
      (is (= 302 (:status (submit {} {:tenant {:id tenant-a}})))))
    (is (some? b))))

(deftest ^:integration the-api-sign-in-answers-409-with-the-tenants
  (let [svc     (user-service)
        _       (register! svc "y@example.com" pw-a tenant-a)
        _       (register! svc "y@example.com" pw-a tenant-b)
        handler (user-http/login-handler svc)
        login   #(handler {:parameters {:body (merge {:email "y@example.com" :password pw-a} %)}})
        asked   (login {})]
    (is (= 409 (:status asked)))
    (is (= 2 (count (get-in asked [:body :tenants]))))
    (is (= 200 (:status (login {:tenantId (str tenant-a)}))))))

(deftest ^:integration existing-accounts-get-their-tenant-and-the-new-key
  ;; An install from before: auth_users had no tenant, and a unique email
  (let [svc (user-service)
        a   (register! svc "x@example.com" pw-a tenant-a)]
    (db/execute-update! *ctx* {:update :auth_users :set {:tenant_id nil} :where [:= :id (str (:id a))]})
    (persistence/initialize-user-schema! *ctx*)
    (is (= (str tenant-a) (str (:tenant-id (db/execute-one! *ctx* {:select [:tenant_id] :from [:auth_users]
                                                                   :where [:= :id (str (:id a))]})))))
    (is (some? (register! svc "x@example.com" pw-b tenant-b)) "and twice is harmless")))

(deftest ^:unit ^:security a-lookup-by-email-names-the-callers-tenants-account
  (let [svc #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify ports/ITenantAccountLookup
          (get-user-by-tenant-and-email [_ t _] {:id (str "of-" t)})
          (get-users-by-email [_ _] [{:id 1} {:id 2}]))]
    (is (= {:id (str "of-" tenant-a)}
           (tenant-scope/user-by-email svc {:user {:id (UUID/randomUUID) :role :admin :tenant-id tenant-a}} "x@e.com")))
    (is (nil? (tenant-scope/user-by-email svc {:user {:id (UUID/randomUUID) :role :admin}} "x@e.com"))
        "a platform admin naming an email of several accounts names none")))

(defn- stored [id]
  (db/execute-one! *ctx* {:select [:failed_login_count :lockout_until] :from [:auth_users] :where [:= :id (str id)]}))

(deftest ^:integration ^:security a-right-password-does-not-count-against-the-other-accounts
  ;; Every account of the email was tried, and only the one opened was reset: the others locked in time
  (let [svc (user-service)
        _   (register! svc "x@example.com" pw-a tenant-a)
        b   (register! svc "x@example.com" pw-b tenant-b)]
    (dotimes [_ 6] (sign-in svc "x@example.com" pw-a))
    (is (zero? (or (:failed-login-count (stored (:id b))) (:failed_login_count (stored (:id b))) 0)))
    (is (true? (:authenticated (sign-in svc "x@example.com" pw-b tenant-b))) "B still signs in")
    (testing "a wrong password still counts on each"
      (sign-in svc "x@example.com" "Wrong-Horse-99")
      (is (= 1 (or (:failed-login-count (stored (:id b))) (:failed_login_count (stored (:id b)))))))))

(deftest ^:integration ^:security a-failed-sign-in-on-an-email-of-several-accounts-is-audited
  (let [svc (user-service)
        a   (register! svc "x@example.com" pw-a tenant-a)
        b   (register! svc "x@example.com" pw-b tenant-b)]
    (sign-in svc "x@example.com" "Wrong-Horse-99")
    (is (= #{(:id a) (:id b)}
           (set (map :target-user-id (filter #(and (= :login (:action %)) (= :failure (:result %)))
                                             (:audit-logs (ports/list-audit-logs svc {:limit 50})))))))))

(deftest ^:integration a-deleted-tenants-account-is-still-a-choice
  ;; Its name is gone with it; get-tenant throws, and the choice answered 500
  (let [tenants #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify tenant-ports/ITenantService
          (get-tenant [_ id]
            (if (= id tenant-a) {:id id :name "Alpha"} (throw (ex-info "Tenant not found" {:type :not-found})))))
        svc     (user-service {:tenants tenants})]
    (register! svc "y@example.com" pw-a tenant-a)
    (register! svc "y@example.com" pw-a tenant-b)
    (let [r (sign-in svc "y@example.com" pw-a)]
      (is (= :tenant-choice-required (:reason r)))
      (is (= #{"Alpha" nil} (set (map :name (:tenants r))))))))

(deftest ^:integration ^:security an-email-change-is-checked-in-its-tenant-only
  (let [svc (user-service)
        a   (register! svc "a@example.com" pw-a tenant-a)
        _   (register! svc "b@example.com" pw-b tenant-b)
        _   (register! svc "taken@example.com" pw-a tenant-a)]
    (is (some? (ports/update-user-profile svc {:id (:id a) :email "b@example.com"})) "B's email is free in A")
    (is (= :conflict (thrown-type #(ports/update-user-profile svc {:id (:id a) :email "taken@example.com"}))))))

(deftest ^:integration registration-signs-in-to-the-account-it-made
  ;; It tried every account of the email, and a tenant's same password asked for a choice
  (let [svc      (user-service)
        _        (register! svc "z@example.com" pw-a tenant-a)
        response ((web-handlers/register-submit-handler svc {})
                  {:form-params {"name" "Zed" "email" "z@example.com" "password" pw-a}
                   :headers {"user-agent" "test"} :remote-addr "127.0.0.1"})]
    (is (some? (get-in response [:cookies "session-token" :value])) "signed in, not sent to a choice")))

(deftest ^:unit the-code-step-keeps-the-tenant-chosen
  (let [page (str (user-ui/mfa-login-page {:email "y@example.com" :password "p" :tenant-id (str tenant-b)} {} {}))]
    (is (str/includes? page (str tenant-b)))))

(deftest ^:integration a-malformed-tenant-is-refused-not-read-as-none
  (let [svc     (user-service)
        _       (register! svc "y@example.com" pw-a nil)
        handler (user-http/login-handler svc)]
    (is (= 400 (:status (handler {:parameters {:body {:email "y@example.com" :password pw-a :tenantId "garbage"}}}))))))

(deftest ^:integration ^:security a-lookup-by-email-of-several-accounts-is-none
  (let [svc (user-service)]
    (register! svc "y@example.com" pw-a tenant-a)
    (is (some? (ports/get-user-by-email svc "y@example.com")) "one account: that one")
    (register! svc "y@example.com" pw-a tenant-b)
    (is (nil? (ports/get-user-by-email svc "y@example.com")) "several: none, not any")))

(deftest ^:integration ^:security a-registration-is-marked-self-registered
  ;; OIDC never links to it by email: nobody proved the address (BOU-1009)
  (let [svc   (user-service)
        _     ((web-handlers/register-submit-handler svc {})
               {:form-params {"name" "Zed" "email" "z@example.com" "password" pw-a}
                :headers {"user-agent" "test"} :remote-addr "127.0.0.1"})
        _     (register! svc "y@example.com" pw-a nil)
        read  #(auth/account-in-tenant (:user-repository svc) nil %)]
    (is (true? (:self-registered (read "z@example.com"))) "registered at /web/register")
    (is (not (:self-registered (read "y@example.com"))) "made by an admin or the API")))

