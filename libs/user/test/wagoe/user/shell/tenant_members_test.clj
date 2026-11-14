(ns wagoe.user.shell.tenant-members-test
  "One identity across tenants (BOU-995): an email another tenant holds joins
   this one without saying so, a tenant lists its members, a shared identity's
   own fields are changed by no tenant's admin, and sign-in asks which tenant."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.tenant.ports :as tenant-ports]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.auth :as auth]
            [wagoe.user.shell.in-memory-repository-test :as mem]
            [wagoe.user.shell.interceptors :as interceptors]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.service :as service]
            [wagoe.user.shell.tenant-scope :as tenant-scope]
            [wagoe.user.shell.web-handlers :as web-handlers])
  (:import [java.util UUID]))

(def ^:private tenant-a #uuid "a0000000-0000-0000-0000-00000000000a")
(def ^:private tenant-b #uuid "b0000000-0000-0000-0000-00000000000b")

(defn- membership-repo
  "Memberships in an atom: what the user service reads and writes."
  [state]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify tenant-ports/ITenantMembershipRepository
    (find-memberships-by-user [_ user-id]
      (filterv #(= (str user-id) (str (:user-id %))) @state))
    (find-memberships-by-tenant [_ tenant-id {:keys [status]}]
      (filterv #(and (= (str tenant-id) (str (:tenant-id %)))
                     (or (nil? status) (= status (:status %))))
               @state))
    (create-membership [_ m] (swap! state conj m) m)))

(defn- user-service
  [memberships]
  (let [users    (mem/->MemoryUserRepository (atom {}))
        sessions (mem/->MemorySessionRepository (atom {}))]
    (service/create-user-service users sessions (mem/->MemoryAuditRepository (atom []))
                                 {}
                                 (auth/create-authentication-service users sessions nil {})
                                 {:memberships (membership-repo memberships)})))

(deftest ^:integration ^:security creating-a-user-answers-the-same-whether-the-email-exists-in-another-tenant
  ;; It said "already exists" for another tenant's email (BOU-992 pinned that)
  (let [memberships (atom [])
        svc         (user-service memberships)
        theirs      (ports/register-user svc {:email "b@example.com" :name "Bea" :password "Correct-Horse-9"
                                              :role :user :tenant-id tenant-b})
        hash-before (:password-hash (ports/find-user-by-email (:user-repository svc) "b@example.com"))
        admin-of-a  {:id (UUID/randomUUID) :role :admin :tenant-id tenant-a}
        create      (fn [email]
                      ((web-handlers/create-user-htmx-handler svc nil {})
                       {:user admin-of-a
                        :form-params {"name" "New" "email" email "password" "Other-Horse-77" "role" "user"}}))
        free        (create "free@example.com")
        taken       (create "b@example.com")]
    (is (= (:status free) (:status taken)) "the same answer")
    (is (= (get-in free [:headers "HX-Redirect"]) (get-in taken [:headers "HX-Redirect"])))
    (is (not (re-find #"(?i)already|taken|in use" (str (:body taken)))))
    (testing "the identity joined tenant A and kept its password"
      (is (= #{(str tenant-a) (str tenant-b)} (ports/tenant-ids svc theirs)))
      (is (= hash-before (:password-hash (ports/find-user-by-email (:user-repository svc) "b@example.com")))))
    (testing "within one tenant an email in use is still said to be"
      (is (= 400 (:status (create "b@example.com")))))))

(deftest ^:integration a-tenants-listing-holds-its-members
  (let [ctx (factory/db-context (factory/h2-config (str "mem:tenant_members_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
    (try
      (persistence/initialize-user-schema! ctx)
      (let [repo   (persistence/create-user-repository ctx)
            add!   #(ports/create-user repo {:email %1 :name %1 :role :user :active true :password-hash "x" :tenant-id %2})
            _      (add! "a@example.com" tenant-a)
            member (add! "b@example.com" tenant-b)
            listed #(set (map :email (:users (ports/find-users repo %))))]
        (is (= #{"a@example.com"} (listed {:filter-tenant-id tenant-a})))
        (is (= #{"a@example.com" "b@example.com"}
               (listed {:filter-tenant-id tenant-a :filter-tenant-member-ids [(:id member)]}))))
      (finally (factory/close-db-context! ctx)))))

(defn- service-knowing
  "A user service that knows `users` by id and their tenants."
  [users]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify
    ports/IUserService
    (get-user-by-id [_ id] (get users id))
    ports/ITenantMembers
    (tenant-ids [_ user] (:in user))
    (tenants [_ user] (mapv #(hash-map :id % :name %) (sort (:in user))))))

(deftest ^:unit ^:security no-tenants-admin-changes-a-user-shared-with-another-tenant
  (let [shared  {:id (UUID/randomUUID) :tenant-id tenant-a :in #{(str tenant-a) (str tenant-b)}}
        own     {:id (UUID/randomUUID) :tenant-id tenant-a :in #{(str tenant-a)}}
        guarded ((tenant-scope/wrap-target-user (service-knowing {(:id shared) shared (:id own) own}))
                 (constantly {:status 200}))
        call    (fn [method caller target]
                  (:status (guarded {:request-method method :user caller
                                     :path-params {:id (str (:id target))}})))
        admin-of-a {:id (UUID/randomUUID) :role :admin :tenant-id tenant-a}]
    (is (= 403 (call :put admin-of-a shared)) "not even its own tenant's admin")
    (is (= 403 (call :delete admin-of-a shared)))
    (is (= 200 (call :get admin-of-a shared)) "they see the user")
    (is (= 200 (call :put admin-of-a own)) "a user of one tenant stays theirs")
    (is (= 200 (call :put {:id (:id shared) :role :user :tenant-id tenant-a} shared)) "the user themselves")
    (is (= 200 (call :put {:id (UUID/randomUUID) :role :admin} shared)) "a platform admin")))

(defn- signed-in-service
  "A user service whose sign-in succeeds for a user in `tenants`."
  [tenants]
  (let [user {:id (UUID/randomUUID) :email "x@example.com" :role :user :in tenants}]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify
      ports/IUserService
      (authenticate-user [_ _]
        {:authenticated true :user user :session {:session-token "token"}})
      ports/ITenantMembers
      (tenant-ids [_ u] (:in u))
      (tenants [_ u] (mapv #(hash-map :id % :name %) (sort (:in u)))))))

(defn- sign-in [svc]
  ((web-handlers/login-submit-handler svc {})
   {:form-params {"email" "x@example.com" "password" "Correct-Horse-9"} :headers {}}))

(deftest ^:unit sign-in-asks-which-tenant-when-there-are-several
  (let [several (sign-in (signed-in-service #{(str tenant-a) (str tenant-b)}))
        one     (sign-in (signed-in-service #{(str tenant-a)}))]
    (is (str/starts-with? (get-in several [:headers "Location"]) "/web/choose-tenant?return-to="))
    (is (not (str/starts-with? (get-in one [:headers "Location"]) "/web/choose-tenant")))
    (is (= 0 (get-in several [:cookies "wagoe-tenant" :max-age])) "the last sign-in's choice is cleared")))

(deftest ^:unit ^:security a-tenant-is-chosen-only-among-ones-own
  (let [svc    (signed-in-service nil)
        user   {:id (UUID/randomUUID) :role :user :in #{(str tenant-a) (str tenant-b)}}
        choose #((web-handlers/choose-tenant-submit-handler svc {})
                 {:user user :headers {} :form-params {"tenant-id" (str %) "return-to" "/web/admin"}})
        mine   (choose tenant-b)]
    (is (= 303 (:status mine)))
    (is (= (str tenant-b) (get-in mine [:cookies "wagoe-tenant" :value])))
    (is (= 403 (:status (choose (UUID/randomUUID)))))))

(deftest ^:unit the-api-sign-in-names-the-tenants-of-one-in-several
  (let [user    {:id (UUID/randomUUID) :email "x@example.com" :in #{(str tenant-a) (str tenant-b)}}
        respond #(-> ((:enter interceptors/format-session-creation-response)
                      {:system  {:user-service (signed-in-service nil)}
                       :session {:authenticated true :user (assoc user :in %) :session {}}})
                     :response :body :tenants)]
    (is (= 2 (count (respond #{(str tenant-a) (str tenant-b)}))))
    (is (nil? (respond #{(str tenant-a)})) "one tenant: nothing to choose")))
